"""Execute the shell bootstrap against a stateful CLI double; no Docker required."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

BOOTSTRAP = Path(__file__).resolve().parents[1] / 'init-aws-resources.sh'
FAKE_AWS = r'''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
p = Path(os.environ['FAKE_STATE'])
s = json.loads(p.read_text())
a = sys.argv[1:]
a = a[2:]  # --endpoint-url value
s['calls'].append(a)
def arg(name): return a[a.index(name) + 1]
def url(name): return 'http://fake/000000000000/' + name
def arn(name): return 'arn:aws:sqs:us-east-1:000000000000:' + name
out = ''
if a[:2] == ['sqs', 'create-queue']:
    name = arg('--queue-name')
    s['queues'].setdefault(name, {'attrs': {}, 'messages': []})
    out = url(name)
elif a[:2] == ['sqs', 'get-queue-url']:
    out = url(arg('--queue-name'))
elif a[:2] == ['sqs', 'get-queue-attributes']:
    out = arn(arg('--queue-url').split('/')[-1])
elif a[:2] == ['sqs', 'set-queue-attributes']:
    if os.environ.get('FAIL_ATTRIBUTES'):
        p.write_text(json.dumps(s))
        sys.exit(9)
    attrs = json.loads(Path(arg('--attributes').removeprefix('file://')).read_text())
    assert all(isinstance(v, str) for v in attrs.values())
    for key in ['RedrivePolicy', 'RedriveAllowPolicy', 'Policy']:
        if key in attrs: json.loads(attrs[key])
    s['queues'][arg('--queue-url').split('/')[-1]]['attrs'].update(attrs)
elif a[:2] not in [['s3', 'ls'], ['sns', 'create-topic'], ['sns', 'set-topic-attributes'],
                  ['sns', 'subscribe'], ['s3api', 'head-bucket'], ['s3api', 'put-bucket-notification-configuration']]:
    raise AssertionError('Unexpected command: ' + str(a))
p.write_text(json.dumps(s))
print(out)
'''


class BootstrapTest(unittest.TestCase):
    def test_repeated_bootstrap_updates_existing_queues_without_losing_messages(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'aws').write_text(FAKE_AWS)
            (root / 'aws').chmod(0o755)
            names = ['custom-backend', 'custom-lambda', 'custom-complete']
            state = root / 'state.json'
            state.write_text(json.dumps({'calls': [], 'queues': {
                n: {'attrs': {'VisibilityTimeout': '7'}, 'messages': ['existing-payload']} for n in names
            }}))
            env = dict(os.environ, PATH=f'{root}:{os.environ["PATH"]}', FAKE_STATE=str(state),
                       BACKEND_QUEUE_NAME=names[0], LAMBDA_QUEUE_NAME=names[1],
                       TRANSCODE_COMPLETE_QUEUE_NAME=names[2], BUCKET_WAIT_SECONDS='2')
            for _ in range(2):
                subprocess.run(['sh', str(BOOTSTRAP)], env=env, check=True, capture_output=True, text=True)
            actual = json.loads(state.read_text())
            for name, visibility in zip(names, ['30', '1800', '30']):
                queue = actual['queues'][name]
                self.assertEqual(queue['messages'], ['existing-payload'])
                self.assertEqual(queue['attrs']['VisibilityTimeout'], visibility)
                self.assertEqual(queue['attrs']['MessageRetentionPeriod'], '345600')
                redrive = json.loads(queue['attrs']['RedrivePolicy'])
                self.assertEqual(redrive['maxReceiveCount'], 5)
                self.assertTrue(redrive['deadLetterTargetArn'].endswith(':' + name + '-dlq'))
                dlq = actual['queues'][name + '-dlq']['attrs']
                self.assertEqual(dlq['MessageRetentionPeriod'], '1209600')
                allow = json.loads(dlq['RedriveAllowPolicy'])
                self.assertEqual(allow['redrivePermission'], 'byQueue')
                self.assertEqual(allow['sourceQueueArns'], ['arn:aws:sqs:us-east-1:000000000000:' + name])
            self.assertFalse(any(c[1] in ['purge-queue', 'delete-queue'] for c in actual['calls']))

    def test_delivery_api_failure_stops_bootstrap(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'aws').write_text(FAKE_AWS)
            (root / 'aws').chmod(0o755)
            state = root / 'state.json'
            state.write_text(json.dumps({'calls': [], 'queues': {}}))
            env = dict(os.environ, PATH=f'{root}:{os.environ["PATH"]}', FAKE_STATE=str(state), FAIL_ATTRIBUTES='1')
            result = subprocess.run(['sh', str(BOOTSTRAP)], env=env, capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertNotIn('AWS resource initialization complete.', result.stdout)
            self.assertFalse(any(c[:2] == ['sns', 'subscribe'] for c in json.loads(state.read_text())['calls']))


if __name__ == '__main__':
    unittest.main()
