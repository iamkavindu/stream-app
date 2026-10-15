# Native explicit-attempt command acceptance

This is SA-013 worker preparation. The application does not yet create retries. Use a disposable **FAILED** video whose source still exists, with its initial attempt already completed by the PR #16 worker. This probe creates one extra, unregistered worker attempt and isolated artifacts. The backend must ignore its result. It does not alter the active attempt, delete objects, or consume application queues manually. Cleanup remains later work.

Switch to `feat/sa-013-retry-job-contract`, rebuild/package/redeploy the native Lambda using your working Linux sequence and S3-backed ZIP upload. Keep the FFmpeg layer, environment, timeout 300 seconds and queue mapping unchanged. Restart the current backend (V4). This step verifies native record deserialization, which JVM CI cannot establish.

Run from repository root in one Bash terminal; stop on command failure:

```bash
set -euo pipefail
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test
export AWS_DEFAULT_REGION=us-east-1 AWS_ENDPOINT_URL=http://localhost:4566 AWS_PAGER=""
unset AWS_SESSION_TOKEN
export VIDEO_ID="replace-with-disposable-failed-video-uuid"
export ATTEMPT_ID="$(python3 -c 'import uuid; print(uuid.uuid4())')"
export PROBE_DIR="$(mktemp -d)"
export SOURCE_KEY="$(aws --endpoint-url "$AWS_ENDPOINT_URL" s3api list-objects-v2 \
  --bucket streamapp-uploads --prefix "$VIDEO_ID/" --query 'Contents[0].Key' --output text)"
aws --endpoint-url "$AWS_ENDPOINT_URL" s3api head-object --bucket streamapp-uploads --key "$SOURCE_KEY"
curl --fail --silent --show-error http://localhost:8080/api/v1/videos > "$PROBE_DIR/videos-before.json"
curl --fail --silent --show-error "http://localhost:8080/api/v1/videos/$VIDEO_ID/attempts" > "$PROBE_DIR/attempts-before.json"
python3 - <<'PY' > "$PROBE_DIR/job.json"
import json, os, uuid
from pathlib import Path
video_id = str(uuid.UUID(os.environ['VIDEO_ID']))
attempt_id = str(uuid.UUID(os.environ['ATTEMPT_ID']))
rows = json.loads((Path(os.environ['PROBE_DIR']) / 'videos-before.json').read_text())
video = next(row for row in rows if row['uploadId'] == video_id)
assert video['status'] == 'FAILED', 'Choose a disposable FAILED video'
assert attempt_id != video_id
key = os.environ['SOURCE_KEY']
assert key.startswith(video_id + '/'), 'Source must belong to the video'
print(json.dumps(dict(schemaVersion=1, jobType='TRANSCODE', uploadId=video_id,
                     attemptId=attempt_id, sourceKey=key)))
PY
queue_url="$(aws --endpoint-url "$AWS_ENDPOINT_URL" sqs get-queue-url \
  --queue-name video-processing-lambda --query QueueUrl --output text)"
aws --endpoint-url "$AWS_ENDPOINT_URL" sqs send-message --queue-url "$queue_url" \
  --message-body "file://$PROBE_DIR/job.json"
printf 'Probe attempt: %s\nSnapshots: %s\n' "$ATTEMPT_ID" "$PROBE_DIR"
```

Wait for this native invocation to finish and its completion message to be handled by backend. Fetch the retained result; a 404 means processing has not committed (inspect worker logs rather than editing coordination):

```bash
aws --endpoint-url "$AWS_ENDPOINT_URL" s3api get-object --bucket streamapp-streams \
  --key "_processing/$VIDEO_ID/$ATTEMPT_ID.json" "$PROBE_DIR/result-before.json"
python3 - <<'PY'
import json, os
from pathlib import Path
lease = json.loads((Path(os.environ['PROBE_DIR']) / 'result-before.json').read_text())
result = json.loads(lease['completedResult'])
assert result['uploadId'] == os.environ['VIDEO_ID']
assert result['attemptId'] == os.environ['ATTEMPT_ID']
assert result['schemaVersion'] == 2
assert result['status'] in ('FAILED', 'PLAY_READY')
if result['status'] == 'PLAY_READY':
    assert result['playlistKey'].startswith(f"{result['uploadId']}/attempts/{result['attemptId']}/executions/")
print('PASS: native command retained the explicit attempt identity')
PY
aws --endpoint-url "$AWS_ENDPOINT_URL" s3api list-objects-v2 --bucket streamapp-streams \
  --prefix "$VIDEO_ID/attempts/$ATTEMPT_ID/" \
  --query 'Contents[].[Key,ETag,LastModified]' --output json > "$PROBE_DIR/files-before.json"
aws --endpoint-url "$AWS_ENDPOINT_URL" sqs send-message --queue-url "$queue_url" \
  --message-body "file://$PROBE_DIR/job.json"
```

Wait for the matching `Replaying retained processing result` log with **this attempt UUID**. Unchanged snapshots alone are not proof of consumption. There must be no second FFmpeg execution. Then:

```bash
aws --endpoint-url "$AWS_ENDPOINT_URL" s3api get-object --bucket streamapp-streams \
  --key "_processing/$VIDEO_ID/$ATTEMPT_ID.json" "$PROBE_DIR/result-after.json"
aws --endpoint-url "$AWS_ENDPOINT_URL" s3api list-objects-v2 --bucket streamapp-streams \
  --prefix "$VIDEO_ID/attempts/$ATTEMPT_ID/" \
  --query 'Contents[].[Key,ETag,LastModified]' --output json > "$PROBE_DIR/files-after.json"
curl --fail --silent --show-error "http://localhost:8080/api/v1/videos/$VIDEO_ID/attempts" > "$PROBE_DIR/attempts-after.json"
diff -u "$PROBE_DIR/result-before.json" "$PROBE_DIR/result-after.json"
diff -u "$PROBE_DIR/files-before.json" "$PROBE_DIR/files-after.json"
diff -u "$PROBE_DIR/attempts-before.json" "$PROBE_DIR/attempts-after.json"
```

All diffs must be empty. Confirm the video remains FAILED and backend logs the unrelated attempt without settling it. Finally use the original-notification replay steps in [worker-publication.md](./worker-publication.md) for the same video: that invocation must replay the **initial** attempt (`VIDEO_ID`), leaving this probe attempt untouched. Report the command identity PASS, duplicate replay, unchanged backend history, and initial-notification replay. Do not enable an outbox producer before this acceptance passes.
