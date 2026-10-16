#!/usr/bin/env python3
"""Single-terminal local demo lifecycle. Python standard library only; no AWS SDK/CLI needed."""
from __future__ import annotations

import argparse
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import subprocess
import sys
import tarfile
import time
import urllib.error
import urllib.request
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[2]
STATE = ROOT / '.local-run'
AWS_COMPOSE = ROOT / 'docker/infra/aws/docker-compose.yaml'
DB_COMPOSE = ROOT / 'docker/infra/db/docker-compose.yaml'
CLI_IMAGE = 'amazon/aws-cli:2.27.29'
NATIVE_IMAGE = 'ghcr.io/graalvm/native-image-community:25'
FFMPEG_URL = 'https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-amd64-static.tar.xz'
FUNCTION = 'streamapp-transcode-lambda'
QUEUE = 'video-processing-lambda'
REGION = 'us-east-1'
ACCOUNT = '000000000000'
ARTIFACT_BUCKET = 'streamapp-deployment-artifacts'


def local_env():
    """Do not inherit a real AWS profile, session token, or user application overrides."""
    env = {k: v for k, v in os.environ.items() if not k.startswith(
        ('AWS_', 'SPRING_', 'APP_', 'STREAMAPP_', 'VITE_'))}
    env.update(AWS_ACCESS_KEY_ID='test', AWS_SECRET_ACCESS_KEY='test', AWS_DEFAULT_REGION=REGION,
               AWS_REGION=REGION, AWS_EC2_METADATA_DISABLED='true', AWS_PAGER='',
               AWS_ENDPOINT_URL='http://localhost:4566', SPRING_PROFILES_ACTIVE='dev',
               SPRING_DATASOURCE_URL='jdbc:postgresql://localhost:5432/streamappdb',
               SPRING_DATASOURCE_USERNAME='streamapp', SPRING_DATASOURCE_PASSWORD='streamapp',
               SPRING_CLOUD_AWS_ENDPOINT='http://localhost:4566',
               SPRING_CLOUD_AWS_REGION_STATIC=REGION, SPRING_CLOUD_AWS_S3_PATH_STYLE_ACCESS_ENABLED='true',
               SPRING_CLOUD_AWS_CREDENTIALS_ACCESS_KEY='test', SPRING_CLOUD_AWS_CREDENTIALS_SECRET_KEY='test',
               STREAMAPP_LOCAL_HLS_PUBLIC_READ_ENABLED='true', SERVER_ADDRESS='127.0.0.1', SERVER_PORT='8080',
               VITE_API_BASE_URL='')
    return env


def digest(paths):
    result = hashlib.sha256()
    for path in sorted(paths):
        if path.is_file():
            result.update(str(path.relative_to(ROOT)).encode())
            result.update(path.read_bytes())
    return result.hexdigest()


def native_digest():
    module = ROOT / 'transcode-lambda'
    source = digest([module / 'pom.xml', module / 'mvnw',
                     *module.joinpath('.mvn').rglob('*'), *module.joinpath('src/main').rglob('*')])
    return hashlib.sha256(('recipe-v1:' + NATIVE_IMAGE + source).encode()).hexdigest()


def executable_zip(destination, members):
    """Set Linux execution bits even when packaging on Windows."""
    with zipfile.ZipFile(destination, 'w', zipfile.ZIP_DEFLATED) as archive:
        for name, data in members.items():
            entry = zipfile.ZipInfo(name)
            entry.create_system = 3
            entry.external_attr = 0o100755 << 16
            entry.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(entry, data)


def pack_layer(archive_path, destination):
    # Read only the two regular files; never extract archive paths/symlinks onto the host.
    with tarfile.open(archive_path, 'r:xz') as archive:
        members = {}
        for name in ('ffmpeg', 'ffprobe'):
            matches = [m for m in archive.getmembers() if m.isfile() and Path(m.name).name == name]
            if len(matches) != 1:
                raise RuntimeError(f'FFmpeg archive needs exactly one regular {name}')
            with archive.extractfile(matches[0]) as stream:
                data = stream.read()
            if not data.startswith(b'\x7fELF'):
                raise RuntimeError(f'{name} is not a Linux ELF executable')
            members['bin/' + name] = data
    executable_zip(destination, members)


class Runner:
    def __init__(self):
        STATE.mkdir(exist_ok=True)
        self.env = local_env()
        self.children = []
        self.handles = []
        self.native_container = None

    def start(self, args, cwd=ROOT, log='setup.log'):
        handle = open(STATE / log, 'ab', buffering=0)
        self.handles.append(handle)
        options = {'creationflags': subprocess.CREATE_NEW_PROCESS_GROUP} if os.name == 'nt' else {'start_new_session': True}
        child = subprocess.Popen([str(a) for a in args], cwd=cwd, env=self.env,
                                 stdout=handle, stderr=subprocess.STDOUT, **options)
        self.children.append(child)
        return child

    def run(self, args, cwd=ROOT, capture=False):
        if capture:
            result = subprocess.run([str(a) for a in args], cwd=cwd, env=self.env,
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=120)
            if result.returncode:
                # Keep command output out of terminal; local-only credentials are fixed above.
                with open(STATE / 'setup.log', 'a', encoding='utf-8') as log:
                    log.write(result.stderr)
                raise RuntimeError(f'Command failed: {args[0]} {args[1:3]}; see .local-run/setup.log')
            return result.stdout
        child = self.start(args, cwd)
        code = child.wait()
        if code:
            raise RuntimeError(f'Command failed ({code}): {args[0]}; see .local-run/setup.log')
        return ''

    def aws(self, *args):
        return json.loads(self.run(['docker', 'run', '--rm', '--network', 'floci_default',
            '--mount', f'type=bind,source={STATE},target=/artifacts,readonly',
            '-e', 'AWS_ACCESS_KEY_ID=test', '-e', 'AWS_SECRET_ACCESS_KEY=test',
            '-e', 'AWS_DEFAULT_REGION=' + REGION, '-e', 'AWS_PAGER=', CLI_IMAGE,
            '--endpoint-url', 'http://floci:4566', '--region', REGION, '--output', 'json', *args], capture=True) or '{}')

    def compose(self, file, *args):
        return self.run(['docker', 'compose', '-f', file, *args])

    def check(self):
        for tool in ('docker', 'java', 'javac', 'node', 'npm'):
            if not shutil.which(tool):
                raise RuntimeError(f'Missing {tool}. See docs/local-run.md prerequisites.')
        java_home = self.env.get('JAVA_HOME')
        java_bin = Path(java_home) / 'bin' if java_home else None
        for tool in ('java', 'javac'):
            executable = str(java_bin / (tool + ('.exe' if os.name == 'nt' else ''))) if java_bin else tool
            version = subprocess.run([executable, '-version'], capture_output=True, text=True, env=self.env, check=True)
            match = re.search(r'(?:version |javac )(?:")?(\d+)', version.stdout + version.stderr)
            if not match or int(match[1]) < 25:
                raise RuntimeError('JDK 25+ is required. Activate it and correct JAVA_HOME before running.')
        node = self.run(['node', '--version'], capture=True).strip().lstrip('v').split('.')
        major, minor = map(int, node[:2])
        if not ((major == 22 and minor >= 18) or (major == 24 and minor >= 12) or major >= 25):
            raise RuntimeError('Node 22.18+ (22.x) or 24.12+ is required by frontend/package.json.')
        info = json.loads(self.run(['docker', 'info', '--format', '{{json .}}'], capture=True))
        if info.get('OSType') != 'linux' or info.get('Architecture') not in ('x86_64', 'amd64'):
            raise RuntimeError('Use an x86_64 Linux Docker engine (Docker Desktop Linux containers on Windows).')
        self.run(['docker', 'compose', 'version'])
        for port in (8080, 5173):
            with socket.socket() as sock:
                try:
                    sock.bind(('127.0.0.1', port))
                except OSError as error:
                    raise RuntimeError(f'Port {port} is in use. Stop the existing app before starting this runner.') from error

    def wait(self, label, probe, seconds=120, services=()):
        deadline = time.monotonic() + seconds
        last = None
        while time.monotonic() < deadline:
            for service in services:
                if service.poll() is not None:
                    raise RuntimeError(f'{label}: app process exited; see .local-run logs.')
            try:
                if probe():
                    return
            except (RuntimeError, OSError, ValueError, urllib.error.URLError) as error:
                last = error
            time.sleep(2)
        raise RuntimeError(f'Timed out waiting for {label}: {last}; see .local-run logs.')

    def ensure_bucket(self, bucket):
        # List first: permission/infrastructure failure must not look like an absent bucket.
        names = {b['Name'] for b in self.aws('s3api', 'list-buckets').get('Buckets', [])}
        if bucket not in names:
            self.aws('s3api', 'create-bucket', '--bucket', bucket)

    def build_worker(self, rebuild):
        fingerprint = native_digest()
        cached = STATE / 'native.sha256'
        binary = STATE / 'bootstrap'
        if rebuild or not binary.exists() or not cached.exists() or cached.read_text() != fingerprint:
            print('Building Linux native Lambda in Docker (first build can take several minutes)...', flush=True)
            inputs = STATE / 'native-input'
            shutil.rmtree(inputs, ignore_errors=True)
            inputs.mkdir()
            module = ROOT / 'transcode-lambda'
            for name in ('pom.xml', 'mvnw', '.mvn', 'src/main'):
                src, dst = module / name, inputs / name
                if src.is_dir():
                    shutil.copytree(src, dst)
                else:
                    shutil.copy2(src, dst)
                    if name == 'mvnw':
                        dst.write_bytes(dst.read_bytes().replace(b'\r\n', b'\n'))
            self.native_container = 'streamapp-build-' + uuid.uuid4().hex[:12]
            self.run(['docker', 'run', '--rm', '--name', self.native_container, '--platform', 'linux/amd64',
                '--mount', f'type=bind,source={inputs},target=/input,readonly',
                '--mount', f'type=bind,source={STATE},target=/out',
                '--mount', 'type=volume,source=streamapp-native-maven-cache,target=/root/.m2',
                '--entrypoint', '/bin/bash', NATIVE_IMAGE, '-c',
                'set -eu\nmkdir /tmp/build\ncp -R /input/. /tmp/build/\ncd /tmp/build\n'
                'sh mvnw --batch-mode --no-transfer-progress install -Dmaven.test.skip=true\n'
                'sh mvnw --batch-mode --no-transfer-progress -Pnative native:compile -Dmaven.test.skip=true\n'
                'cp target/transcode-lambda /out/bootstrap\n'])
            self.native_container = None
            cached.write_text(fingerprint)
        if not binary.read_bytes().startswith(b'\x7fELF'):
            raise RuntimeError('Native worker is not a Linux ELF executable. Rerun with --rebuild-worker.')
        executable_zip(STATE / 'worker.zip', {'bootstrap': binary.read_bytes()})

    def layer(self, rebuild):
        versions = self.aws('lambda', 'list-layer-versions', '--layer-name', 'local-ffmpeg-layer').get('LayerVersions', [])
        if versions and not rebuild:
            return max(versions, key=lambda v: v['Version'])['LayerVersionArn']
        archive, layer = STATE / 'ffmpeg.tar.xz', STATE / 'ffmpeg-layer.zip'
        if not layer.exists() or rebuild:
            print('Preparing static Linux FFmpeg layer...', flush=True)
            for attempt in range(4):
                try:
                    # Restart failed downloads; never concatenate an error body to a partial archive.
                    with urllib.request.urlopen(FFMPEG_URL, timeout=60) as response, open(archive, 'wb') as target:
                        shutil.copyfileobj(response, target)
                    pack_layer(archive, layer)
                    break
                except (OSError, EOFError, http.client.HTTPException, tarfile.TarError, RuntimeError) as error:
                    archive.unlink(missing_ok=True)
                    if attempt == 3:
                        raise RuntimeError('FFmpeg download/archive validation failed; rerun to retry.') from error
                    time.sleep(2)
        key = self.upload_artifact(layer)
        return self.aws('lambda', 'publish-layer-version', '--layer-name', 'local-ffmpeg-layer',
            '--content', json.dumps({'S3Bucket': ARTIFACT_BUCKET, 'S3Key': key}),
            '--compatible-runtimes', 'provided.al2023', '--compatible-architectures', 'x86_64')['LayerVersionArn']

    def upload_artifact(self, path):
        key = 'local-run/' + hashlib.sha256(path.read_bytes()).hexdigest() + '/' + path.name
        self.aws('s3api', 'put-object', '--bucket', ARTIFACT_BUCKET, '--key', key,
                 '--body', '/artifacts/' + path.name)
        return key

    def deploy(self, layer):
        url = self.aws('sqs', 'get-queue-url', '--queue-name', QUEUE)['QueueUrl']
        attrs = self.aws('sqs', 'get-queue-attributes', '--queue-url', url,
                         '--attribute-names', 'VisibilityTimeout', 'RedrivePolicy')['Attributes']
        redrive = json.loads(attrs.get('RedrivePolicy', '{}'))
        arn = f'arn:aws:sqs:{REGION}:{ACCOUNT}:{QUEUE}'
        if int(attrs.get('VisibilityTimeout', 0)) < 1800 or redrive.get('deadLetterTargetArn') != arn + '-dlq' \
                or int(redrive.get('maxReceiveCount', 0)) < 5:
            raise RuntimeError('Unsafe Lambda queue delivery settings; provisioning did not complete.')
        key = self.upload_artifact(STATE / 'worker.zip')
        variables = dict(APP_AWS_ENDPOINT='http://floci:4566', APP_AWS_REGION=REGION,
            APP_AWS_ACCESS_KEY='test', APP_AWS_SECRET_KEY='test', APP_AWS_PATH_STYLE_ACCESS='true',
            APP_BUCKET_NAME_UPLOAD_BUCKET='streamapp-uploads', APP_BUCKET_NAME_STREAM_BUCKET='streamapp-streams',
            APP_SQS_QUEUE_TRANSCODE_COMPLETE_QUEUE='video-transcode-complete-backend',
            APP_FFMPEG_PATH='/opt/bin/ffmpeg', SPRING_PROFILES_ACTIVE='prod')
        config = ['--function-name', FUNCTION, '--layers', layer, '--memory-size', '2048', '--timeout', '300',
                  '--environment', json.dumps({'Variables': variables})]
        functions = self.aws('lambda', 'list-functions').get('Functions', [])
        exists = any(f['FunctionName'] == FUNCTION for f in functions)
        mappings = self.aws('lambda', 'list-event-source-mappings', '--function-name', FUNCTION,
                            '--event-source-arn', arn).get('EventSourceMappings', []) if exists else []
        if len(mappings) > 1:
            raise RuntimeError('Duplicate Lambda mappings exist. Resolve them before startup; none were deleted.')
        # Pause new dispatch before changing the executable, then drain current invocations.
        # No purging, message receives, visibility changes or coordination-object edits.
        paused = mappings and mappings[0].get('State') != 'Disabled'
        if mappings:
            mapping_id = mappings[0]['UUID']
            if paused:
                self.aws('lambda', 'update-event-source-mapping', '--uuid', mapping_id, '--no-enabled')
            try:
                self.wait('paused Lambda mapping', lambda: self.aws('lambda', 'get-event-source-mapping',
                          '--uuid', mapping_id).get('State') == 'Disabled')
                self.wait('in-flight Lambda deliveries to drain', lambda: int(self.aws('sqs',
                    'get-queue-attributes', '--queue-url', url, '--attribute-names',
                    'ApproximateNumberOfMessagesNotVisible')['Attributes'].get(
                        'ApproximateNumberOfMessagesNotVisible', 0)) == 0, seconds=330)
            except BaseException:
                if paused:
                    self.aws('lambda', 'update-event-source-mapping', '--uuid', mapping_id, '--enabled')
                raise
        try:
            if exists:
                self.aws('lambda', 'update-function-code', '--function-name', FUNCTION,
                         '--s3-bucket', ARTIFACT_BUCKET, '--s3-key', key)
                self.function_ready()
                self.aws('lambda', 'update-function-configuration', *config)
            else:
                self.aws('lambda', 'create-function', *config, '--runtime', 'provided.al2023', '--architectures', 'x86_64',
                    '--role', f'arn:aws:iam::{ACCOUNT}:role/local-execution-role', '--handler', 'bootstrap',
                    '--code', json.dumps({'S3Bucket': ARTIFACT_BUCKET, 'S3Key': key}))
            self.function_ready()
        except BaseException:
            # The code may already have changed: only resume dispatch after deployment is known healthy.
            if paused:
                print('Worker deployment incomplete; mapping remains paused. Fix the error and rerun up.', file=sys.stderr)
            raise
        common = ['--batch-size', '1', '--maximum-batching-window-in-seconds', '0', '--enabled']
        if mappings:
            mapping = self.aws('lambda', 'update-event-source-mapping', '--uuid', mappings[0]['UUID'], *common)
        else:
            mapping = self.aws('lambda', 'create-event-source-mapping', '--function-name', FUNCTION,
                               '--event-source-arn', arn, *common)
        self.wait('enabled Lambda mapping', lambda: self.aws('lambda', 'get-event-source-mapping',
                  '--uuid', mapping['UUID']).get('State') == 'Enabled')

    def function_ready(self):
        def ready():
            config = self.aws('lambda', 'get-function-configuration', '--function-name', FUNCTION)
            if config.get('State') == 'Failed' or config.get('LastUpdateStatus') == 'Failed':
                raise ValueError('Lambda deployment failed: ' + config.get('StateReason', 'inspect setup.log'))
            return config.get('State') == 'Active' and config.get('LastUpdateStatus', 'Successful') == 'Successful'
        self.wait('Lambda deployment', ready, seconds=180)

    def up(self, args):
        print('Checking prerequisites (details: .local-run/setup.log)...', flush=True)
        self.check()
        print('Starting PostgreSQL and Floci...', flush=True)
        self.compose(DB_COMPOSE, 'up', '-d', '--wait', 'postgres')
        self.compose(AWS_COMPOSE, 'up', '-d', 'floci', 'floci-ui')
        self.wait('Floci S3', lambda: 'Buckets' in self.aws('s3api', 'list-buckets'))
        for bucket in ('streamapp-uploads', 'streamapp-streams', ARTIFACT_BUCKET):
            self.ensure_bucket(bucket)
        print('Provisioning messaging and upload notifications...', flush=True)
        # Existing Windows checkouts may predate .gitattributes; normalize only a container copy.
        self.compose(AWS_COMPOSE, 'run', '--rm', '--entrypoint', '/bin/sh', 'aws-init', '-c',
                     "tr -d '\\r' < /init-aws-resources.sh > /tmp/init-lf.sh\nexec /bin/sh /tmp/init-lf.sh")
        notification = self.aws('s3api', 'get-bucket-notification-configuration', '--bucket', 'streamapp-uploads')
        topic = f'arn:aws:sns:{REGION}:{ACCOUNT}:video-upload-events'
        if not any(c.get('TopicArn') == topic and 's3:ObjectCreated:*' in c.get('Events', [])
                   for c in notification.get('TopicConfigurations', [])):
            raise RuntimeError('Upload notification missing after provisioning.')
        # Migrate backend before deploying the worker; readiness exercises the actual DB-backed API.
        print('Starting backend; waiting for migrations/API...', flush=True)
        wrapper = ['cmd.exe', '/d', '/c', 'mvnw.cmd'] if os.name == 'nt' else ['sh', 'mvnw']
        backend = self.start([*wrapper, '--batch-mode', '--no-transfer-progress', 'spring-boot:run',
                              '-Dspring-boot.run.profiles=dev'], ROOT / 'backend', 'backend.log')
        self.wait('backend API', lambda: http_ready('http://127.0.0.1:8080/api/v1/videos'), 600, (backend,))
        self.build_worker(args.rebuild_worker)
        print('Deploying native Lambda via S3 artifacts...', flush=True)
        self.deploy(self.layer(args.rebuild_layer))
        print('Installing locked frontend dependencies...', flush=True)
        npm = shutil.which('npm')
        if os.name == 'nt':
            npm_cli = Path(npm).parent / 'node_modules/npm/bin/npm-cli.js'
            if not npm_cli.exists():
                raise RuntimeError('Cannot locate npm-cli.js beside npm.cmd. Use the standard Node Windows installation.')
            npm = ['node', str(npm_cli)]
        else:
            npm = [npm]
        self.run([*npm, 'ci'], ROOT / 'frontend')
        frontend = self.start(['node', '--dns-result-order=ipv4first', ROOT / 'frontend/node_modules/vite/bin/vite.js', '--host', '127.0.0.1',
                               '--port', '5173', '--strictPort'], ROOT / 'frontend', 'frontend.log')
        self.wait('frontend', lambda: http_ready('http://127.0.0.1:5173'), 60, (backend, frontend))
        print('\nReady: http://localhost:5173\nAPI: http://localhost:8080/api/v1/videos\n'
              'Floci UI: http://localhost:4500\nLogs: .local-run/{setup,backend,frontend}.log\n'
              'Keep this terminal open. Ctrl+C stops backend/frontend; Docker data is retained.', flush=True)
        while True:
            if backend.poll() is not None or frontend.poll() is not None:
                raise RuntimeError('An application process exited; inspect .local-run logs.')
            time.sleep(1)

    def close(self):
        for child in reversed(self.children):
            if child.poll() is not None:
                continue
            if os.name == 'nt':
                subprocess.run(['taskkill', '/PID', str(child.pid), '/T', '/F'], capture_output=True)
            else:
                try:
                    os.killpg(child.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
        for child in self.children:
            try:
                child.wait(timeout=10)
            except subprocess.TimeoutExpired:
                if os.name != 'nt':
                    os.killpg(child.pid, signal.SIGKILL)
                child.wait(timeout=10)
        if self.native_container:
            subprocess.run(['docker', 'rm', '-f', self.native_container], capture_output=True, timeout=30)
        for handle in self.handles:
            handle.close()


def http_ready(url):
    with urllib.request.urlopen(url, timeout=3) as response:
        return response.status == 200


class Lock:
    """OS advisory lock: automatically released on process death; no stale PID killing."""
    def __enter__(self):
        STATE.mkdir(exist_ok=True)
        self.file = open(STATE / 'runner.lock', 'a+b')
        if self.file.seek(0, 2) == 0:
            self.file.write(b'0')
            self.file.flush()
        self.file.seek(0)
        try:
            if os.name == 'nt':
                import msvcrt
                msvcrt.locking(self.file.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(self.file, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            self.file.close()
            raise RuntimeError('Another local runner is active. Use Ctrl+C in its terminal first.')
        return self

    def __exit__(self, *_):
        self.file.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', nargs='?', choices=('up', 'down', 'status'), default='up')
    parser.add_argument('--rebuild-worker', action='store_true', help='Ignore the native source fingerprint cache')
    parser.add_argument('--rebuild-layer', action='store_true', help='Download and publish a fresh static FFmpeg layer')
    args = parser.parse_args()
    runner = Runner()
    try:
        if args.action == 'status':
            print(runner.run(['docker', 'compose', '-f', DB_COMPOSE, 'ps'], capture=True))
            print(runner.run(['docker', 'compose', '-f', AWS_COMPOSE, 'ps'], capture=True))
            for name, url in [('Backend', 'http://127.0.0.1:8080/api/v1/videos'), ('Frontend', 'http://127.0.0.1:5173')]:
                try:
                    print(f'{name}: {"ready" if http_ready(url) else "not ready"}')
                except OSError:
                    print(f'{name}: not reachable')
            print('Docker status: see .local-run/setup.log (app readiness is not an end-to-end media test).')
        else:
            with Lock():
                if args.action == 'down':
                    runner.compose(AWS_COMPOSE, 'stop')
                    runner.compose(DB_COMPOSE, 'stop')
                    print('Docker services stopped. Volumes, buckets, queues and cached artifacts are retained.')
                else:
                    runner.up(args)
        return 0
    except KeyboardInterrupt:
        print('\nStopping app processes; Docker services/data are retained.', flush=True)
        return 0
    except (RuntimeError, OSError, subprocess.SubprocessError, ValueError) as error:
        print(f'ERROR: {error}', file=sys.stderr, flush=True)
        return 1
    finally:
        runner.close()


if __name__ == '__main__':
    if sys.version_info < (3, 10):
        sys.exit('Python 3.10+ is required.')
    sys.exit(main())
