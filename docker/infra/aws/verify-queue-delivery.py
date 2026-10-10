#!/usr/bin/env python3
"""Local Floci acceptance: bootstrap twice, preserve payload, exhaust retries, replay.

Requires Python 3, AWS CLI v2, and running Floci. Uses only uniquely named temporary
resources; never consumes or changes application queues. No native Lambda invoked.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import time
from urllib.parse import urlparse
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--endpoint', default='http://localhost:4566')
    args = parser.parse_args()
    host = urlparse(args.endpoint)
    if host.scheme != 'http' or host.hostname not in {'localhost', '127.0.0.1', '::1'}:
        parser.error('This acceptance exercise is restricted to local Floci over HTTP.')
    prefix = 'sa006-check-' + uuid.uuid4().hex[:12]
    names = [prefix + suffix for suffix in ['-backend', '-lambda', '-complete']]
    bucket, topic = prefix + '-uploads', prefix + '-events'
    env = dict(os.environ, AWS_ENDPOINT_URL=args.endpoint, AWS_DEFAULT_REGION='us-east-1',
               AWS_ACCESS_KEY_ID='test', AWS_SECRET_ACCESS_KEY='test', AWS_SESSION_TOKEN='', AWS_PAGER='',
               AWS_MAX_ATTEMPTS='1', UPLOAD_BUCKET=bucket, UPLOAD_TOPIC_NAME=topic,
               BACKEND_QUEUE_NAME=names[0], LAMBDA_QUEUE_NAME=names[1],
               TRANSCODE_COMPLETE_QUEUE_NAME=names[2], BUCKET_WAIT_SECONDS='2', AWS_ACCOUNT_ID='000000000000')
    topic_arn = None
    bucket_created = False
    queues = {}

    def aws(*command):
        result = subprocess.run(['aws', '--endpoint-url', args.endpoint, '--region', 'us-east-1',
                                 '--cli-connect-timeout', '5', '--cli-read-timeout', '10',
                                 *command, '--output', 'json'], env=env, check=True,
                                capture_output=True, text=True, timeout=20)
        return json.loads(result.stdout) if result.stdout.strip() else {}

    def attributes(url):
        return aws('sqs', 'get-queue-attributes', '--queue-url', url, '--attribute-names', 'All')['Attributes']

    def require(condition, message):
        if not condition:
            raise RuntimeError(message)

    def receive(url):
        return aws('sqs', 'receive-message', '--queue-url', url, '--max-number-of-messages', '1',
                   '--wait-time-seconds', '0', '--visibility-timeout', '0').get('Messages', [])

    try:
        aws('s3api', 'create-bucket', '--bucket', bucket)
        bucket_created = True
        topic_arn = aws('sns', 'create-topic', '--name', topic)['TopicArn']
        for name in names:
            queues[name] = aws('sqs', 'create-queue', '--queue-name', name)['QueueUrl']
        aws('sqs', 'set-queue-attributes', '--queue-url', queues[names[0]],
            '--attributes', json.dumps({'VisibilityTimeout': '7'}))
        sentinel = json.dumps({'check': prefix, 'purpose': 'retry-preservation'})
        aws('sqs', 'send-message', '--queue-url', queues[names[0]], '--message-body', sentinel)
        bootstrap = Path(__file__).with_name('init-aws-resources.sh')
        # Tests the real bootstrap, including updates to an existing queue and repeat runs.
        for _ in range(2):
            subprocess.run(['sh', str(bootstrap)], env=env, check=True, timeout=90)
        for name, visibility in zip(names, ['30', '1800', '30']):
            attrs = attributes(queues[name])
            require(attrs['VisibilityTimeout'] == visibility, f'{name}: visibility mismatch')
            require(attrs['MessageRetentionPeriod'] == '345600', f'{name}: retention mismatch')
            dlq_name = name + '-dlq'
            queues[dlq_name] = aws('sqs', 'get-queue-url', '--queue-name', dlq_name)['QueueUrl']
            dlq_attrs = attributes(queues[dlq_name])
            require(dlq_attrs['MessageRetentionPeriod'] == '1209600', f'{dlq_name}: retention mismatch')
            policy = json.loads(attrs['RedrivePolicy'])
            require(policy == {'deadLetterTargetArn': dlq_attrs['QueueArn'], 'maxReceiveCount': 5},
                    f'{name}: redrive mismatch')
            allow = json.loads(dlq_attrs['RedriveAllowPolicy'])
            require(allow == {'redrivePermission': 'byQueue', 'sourceQueueArns': [attrs['QueueArn']]},
                    f'{dlq_name}: allow policy mismatch')
        print('PASS: repeat provisioning updates existing queues and applies all delivery settings.')
        source_url, dlq_url = queues[names[0]], queues[names[0] + '-dlq']
        # Per-receive zero visibility accelerates this isolated probe; production settings stay intact.
        receives = 0
        quarantined = []
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            messages = receive(source_url)
            if messages:
                require(messages[0]['Body'] == sentinel, 'Bootstrap changed the existing message body')
                receives += 1
                require(receives <= 5, 'Source delivered beyond maxReceiveCount')
            quarantined = receive(dlq_url)
            if quarantined:
                break
            time.sleep(0.5)
        require(receives == 5 and quarantined and quarantined[0]['Body'] == sentinel,
                'Expected five receives then the unchanged message in its DLQ')
        print('PASS: existing payload preserved; exhausted retries reached DLQ unchanged.')
        task = aws('sqs', 'start-message-move-task', '--source-arn', attributes(dlq_url)['QueueArn'],
                   '--destination-arn', attributes(source_url)['QueueArn'])
        require(bool(task.get('TaskHandle')), 'No redrive task handle returned')
        replayed = []
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            replayed = receive(source_url)
            if replayed:
                break
            time.sleep(0.5)
        require(replayed and replayed[0]['Body'] == sentinel, 'Replay did not restore the original message')
        aws('sqs', 'delete-message', '--queue-url', source_url, '--receipt-handle', replayed[0]['ReceiptHandle'])
        print('PASS: DLQ replay restored the payload; successful processing acknowledged it.')
    finally:
        # Look up any DLQs created by bootstrap even if an earlier assertion failed.
        for name in [*names, *(n + '-dlq' for n in names)]:
            try:
                url = queues.get(name) or aws('sqs', 'get-queue-url', '--queue-name', name)['QueueUrl']
                aws('sqs', 'delete-queue', '--queue-url', url)
            except (subprocess.SubprocessError, KeyError) as failure:
                print(f'Cleanup: {name}: {failure}')
        if topic_arn:
            aws('sns', 'delete-topic', '--topic-arn', topic_arn)
        if bucket_created:
            aws('s3api', 'delete-bucket', '--bucket', bucket)


if __name__ == '__main__':
    main()
