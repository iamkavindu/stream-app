import argparse
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile

MODULE = Path(__file__).resolve().parents[1] / 'run.py'
spec = importlib.util.spec_from_file_location('local_runner', MODULE)
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class RunnerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.state = Path(self.temp.name)
        self.addCleanup(patch.stopall)
        patch.object(runner, 'STATE', self.state).start()

    def test_local_environment_discards_real_cloud_and_app_overrides(self):
        with patch.dict(os.environ, {'AWS_PROFILE': 'production', 'AWS_SESSION_TOKEN': 'secret',
                'AWS_ENDPOINT_URL': 'https://real-service', 'SPRING_APPLICATION_JSON': '{}',
                'APP_AWS_ENDPOINT': 'https://real-service', 'VITE_API_BASE_URL': 'https://real-api',
                'JAVA_HOME': 'keep-jdk'}, clear=True):
            env = runner.local_env()
        self.assertNotIn('AWS_PROFILE', env)
        self.assertNotIn('AWS_SESSION_TOKEN', env)
        self.assertNotIn('SPRING_APPLICATION_JSON', env)
        self.assertNotIn('APP_AWS_ENDPOINT', env)
        self.assertEqual('test', env['AWS_ACCESS_KEY_ID'])
        self.assertEqual('http://localhost:4566', env['AWS_ENDPOINT_URL'])
        self.assertEqual('', env['VITE_API_BASE_URL'])
        self.assertEqual('keep-jdk', env['JAVA_HOME'])

    def test_windows_packaging_preserves_linux_execution_bits_and_root_layout(self):
        target = self.state / 'worker.zip'
        runner.executable_zip(target, {'bootstrap': b'\x7fELFtest'})
        with zipfile.ZipFile(target) as archive:
            self.assertEqual(['bootstrap'], archive.namelist())
            self.assertEqual(0o100755, archive.getinfo('bootstrap').external_attr >> 16)
            self.assertEqual(3, archive.getinfo('bootstrap').create_system)
            self.assertIsNone(archive.testzip())

    def make_tar(self, entries):
        archive = self.state / 'ffmpeg.tar.xz'
        with tarfile.open(archive, 'w:xz') as tar:
            for name, content in entries:
                info = tarfile.TarInfo(name)
                info.size = len(content)
                tar.addfile(info, io.BytesIO(content))
        return archive

    def test_layer_only_reads_named_binaries_and_never_extracts_paths(self):
        tar = self.make_tar([('../do-not-extract', b'bad'), ('release/ffmpeg', b'\x7fELFmpeg'),
                             ('release/ffprobe', b'\x7fELFprobe')])
        target = self.state / 'layer.zip'
        runner.pack_layer(tar, target)
        with zipfile.ZipFile(target) as archive:
            self.assertEqual(['bin/ffmpeg', 'bin/ffprobe'], archive.namelist())
            self.assertEqual(0o100755, archive.getinfo('bin/ffmpeg').external_attr >> 16)
        self.assertFalse((self.state.parent / 'do-not-extract').exists())

    def test_layer_rejects_missing_duplicate_or_non_elf_binary(self):
        for entries in [[('release/ffmpeg', b'\x7fELF')],
                        [('a/ffmpeg', b'\x7fELF'), ('b/ffmpeg', b'\x7fELF'), ('a/ffprobe', b'\x7fELF')],
                        [('a/ffmpeg', b'html error'), ('a/ffprobe', b'\x7fELF')]]:
            with self.assertRaises(RuntimeError):
                runner.pack_layer(self.make_tar(entries), self.state / 'layer.zip')

    def test_source_change_invalidates_native_cache_but_target_changes_do_not(self):
        root = self.state / 'repo'
        module = root / 'transcode-lambda'
        (module / 'src/main').mkdir(parents=True)
        (module / 'target').mkdir()
        source = module / 'src/main/App.java'
        source.write_text('before')
        with patch.object(runner, 'ROOT', root):
            first = runner.native_digest()
            (module / 'target/generated').write_text('ignored')
            self.assertEqual(first, runner.native_digest())
            source.write_text('after')
            self.assertNotEqual(first, runner.native_digest())

    def test_os_lock_excludes_second_runner_and_releases_after_failure(self):
        with runner.Lock():
            with self.assertRaisesRegex(RuntimeError, 'Another local runner'):
                with runner.Lock():
                    pass
        with runner.Lock():
            pass

    def test_wait_fails_when_owned_application_exits(self):
        app = unittest.mock.Mock()
        app.poll.return_value = 1
        with self.assertRaisesRegex(RuntimeError, 'process exited'):
            runner.Runner().wait('backend', lambda: True, services=(app,))

    def test_supervisor_closes_a_real_child_process(self):
        app = runner.Runner()
        child = app.start([sys.executable, '-c', 'import time; time.sleep(120)'], log='child.log')
        app.close()
        self.assertIsNotNone(child.poll())

    def test_bucket_infrastructure_failure_is_not_treated_as_absent(self):
        app = runner.Runner()
        app.aws = unittest.mock.Mock(side_effect=RuntimeError('unavailable'))
        with self.assertRaises(RuntimeError):
            app.ensure_bucket('bucket')
        self.assertEqual(1, app.aws.call_count)

    def test_layer_reuses_existing_version_without_downloading(self):
        app = runner.Runner()
        app.aws = unittest.mock.Mock(return_value={'LayerVersions': [
            {'Version': 1, 'LayerVersionArn': 'old'}, {'Version': 3, 'LayerVersionArn': 'latest'}]})
        with patch.object(runner.urllib.request, 'urlopen') as download:
            self.assertEqual('latest', app.layer(False))
            download.assert_not_called()

    def deploy_double(self, existing=False, unsafe=False):
        app = runner.Runner()
        (self.state / 'worker.zip').write_bytes(b'large-package-representative')
        calls = []
        mapping = {'UUID': 'mapping', 'State': 'Enabled'}
        def aws(*args):
            calls.append(args)
            service, action = args[:2]
            if action == 'get-queue-url':
                return {'QueueUrl': 'http://floci/queue'}
            if action == 'get-queue-attributes':
                return {'Attributes': {'VisibilityTimeout': '30' if unsafe else '1800',
                    'ApproximateNumberOfMessagesNotVisible': '0',
                    'RedrivePolicy': json.dumps({'maxReceiveCount': 5, 'deadLetterTargetArn':
                        f'arn:aws:sqs:{runner.REGION}:{runner.ACCOUNT}:{runner.QUEUE}-dlq'})}}
            if action == 'list-functions':
                return {'Functions': [{'FunctionName': runner.FUNCTION}] if existing else []}
            if action == 'get-function-configuration':
                return {'State': 'Active', 'LastUpdateStatus': 'Successful'}
            if action == 'list-event-source-mappings':
                return {'EventSourceMappings': [dict(mapping)] if existing else []}
            if action in ('create-event-source-mapping', 'update-event-source-mapping'):
                mapping['State'] = 'Disabled' if '--no-enabled' in args else 'Enabled'
                return dict(mapping)
            if action == 'get-event-source-mapping':
                return dict(mapping)
            return {}
        app.aws = aws
        return app, calls

    def test_fresh_deploy_uses_s3_code_correct_container_endpoint_and_single_mapping(self):
        app, calls = self.deploy_double()
        app.deploy('layer-arn')
        create = next(c for c in calls if c[1] == 'create-function')
        code = json.loads(create[create.index('--code') + 1])
        self.assertEqual(runner.ARTIFACT_BUCKET, code['S3Bucket'])
        self.assertNotIn('--zip-file', create)
        env = json.loads(create[create.index('--environment') + 1])['Variables']
        self.assertEqual('http://floci:4566', env['APP_AWS_ENDPOINT'])
        mapping = next(c for c in calls if c[1] == 'create-event-source-mapping')
        self.assertEqual('1', mapping[mapping.index('--batch-size') + 1])
        self.assertEqual('0', mapping[mapping.index('--maximum-batching-window-in-seconds') + 1])

    def test_existing_deploy_pauses_drains_updates_then_enables_mapping_without_replacing(self):
        app, calls = self.deploy_double(existing=True)
        app.deploy('layer-arn')
        actions = [c[1] for c in calls]
        pause = next(i for i, c in enumerate(calls) if c[1] == 'update-event-source-mapping' and '--no-enabled' in c)
        drain = next(i for i, c in enumerate(calls) if 'ApproximateNumberOfMessagesNotVisible' in c)
        update = actions.index('update-function-code')
        self.assertLess(pause, drain)
        self.assertLess(drain, update)
        self.assertNotIn('create-function', actions)
        self.assertNotIn('create-event-source-mapping', actions)
        self.assertIn('--s3-key', calls[update])
        self.assertNotIn('--zip-file', calls[update])
        self.assertIn('--enabled', calls[-2])

    def test_drain_failure_restores_previous_mapping_without_updating_code(self):
        app, calls = self.deploy_double(existing=True)
        app.wait = unittest.mock.Mock(side_effect=RuntimeError('drain failed'))
        with self.assertRaisesRegex(RuntimeError, 'drain failed'):
            app.deploy('layer-arn')
        self.assertIn('--enabled', calls[-1])
        self.assertFalse(any(c[1] == 'update-function-code' for c in calls))

    def test_failed_code_update_keeps_mapping_paused_for_healthy_rerun(self):
        app, calls = self.deploy_double(existing=True)
        original = app.aws
        def fail(*args):
            if args[1] == 'update-function-code':
                raise RuntimeError('upload failed')
            return original(*args)
        app.aws = fail
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaisesRegex(RuntimeError, 'upload failed'):
            app.deploy('layer-arn')
        updates = [c for c in calls if c[1] == 'update-event-source-mapping']
        self.assertEqual(1, len(updates))
        self.assertIn('--no-enabled', updates[0])

    def test_down_stops_infrastructure_without_removing_data(self):
        app = runner.Runner()
        app.compose = unittest.mock.Mock()
        data = self.state / 'preserve-data'
        data.write_text('existing')
        with patch.object(runner, 'Runner', return_value=app), patch.object(sys, 'argv', ['run.py', 'down']), \
                contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, runner.main())
        self.assertEqual([unittest.mock.call(runner.AWS_COMPOSE, 'stop'),
                          unittest.mock.call(runner.DB_COMPOSE, 'stop')], app.compose.call_args_list)
        self.assertEqual('existing', data.read_text())

    def test_unsafe_queue_blocks_function_mutation(self):
        app, calls = self.deploy_double(unsafe=True)
        with self.assertRaisesRegex(RuntimeError, 'Unsafe Lambda queue'):
            app.deploy('layer-arn')
        self.assertFalse(any(c[1] in ('put-object', 'create-function', 'update-function-code') for c in calls))


if __name__ == '__main__':
    unittest.main()
