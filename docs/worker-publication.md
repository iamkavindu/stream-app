# Fenced worker publication acceptance

PR #15 is the standalone lease/result primitive; PR #16 depends on it and wires native processing, isolated output and playback references. Merge #15 before #16. To test both before merging, use `feat/sa-009-safe-output-publication`.

## Rollout

1. Stop starting uploads and let existing worker invocations finish. An old worker does not participate in the new lease protocol.
2. Switch to the integration branch and restart the backend with the existing `dev` settings. Confirm Flyway V4 applies. Existing data and objects are retained.
3. Rebuild/package the native Lambda using the previously working Linux build commands. Redeploy its ZIP through the S3 artifact bucket; direct ZIP upload can exceed the 50 MiB request limit. The FFmpeg layer and Lambda environment remain unchanged. Keep the existing function timeout at 300 seconds; the worker lease is 600 seconds.
4. Start the frontend on `http://localhost:5173`. Verify an existing ready video still plays through its legacy fixed path.
5. Upload a new valid MP4 with a previously unused hash. Confirm `PLAY_READY`, playback and seeking. The signed-url API's `objectKey` should contain `/attempts/{attemptId}/executions/{executionId}/index.m3u8`. Network media requests must be relative to that prefix and return valid `206` byte ranges.
6. Upload new invalid MP4 bytes. Confirm `FAILED` with `FFMPEG_EXIT` in the attempts API. It must not create a playable artifact reference.

## Duplicate native delivery

Run from the repository root in a Bash terminal with AWS CLI/Python 3. Choose the **new** valid video tested above; the initial attempt ID equals its upload ID. Repeat with the invalid-media video afterward.

```bash
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_DEFAULT_REGION=us-east-1
export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_PAGER=""
unset AWS_SESSION_TOKEN

export VIDEO_ID="replace-with-new-upload-uuid"
verification_dir="$(mktemp -d)"

aws --endpoint-url "$AWS_ENDPOINT_URL" s3api get-object \
  --bucket streamapp-streams \
  --key "_processing/$VIDEO_ID/$VIDEO_ID.json" \
  "$verification_dir/before.json"

aws --endpoint-url "$AWS_ENDPOINT_URL" s3api list-objects-v2 \
  --bucket streamapp-streams --prefix "$VIDEO_ID/attempts/" \
  --query 'Contents[].[Key,ETag,LastModified]' --output json \
  > "$verification_dir/files-before.json"

source_key="$(aws --endpoint-url "$AWS_ENDPOINT_URL" s3api list-objects-v2 \
  --bucket streamapp-uploads --prefix "$VIDEO_ID/" \
  --query 'Contents[0].Key' --output text)"
if [ -z "$source_key" ] || [ "$source_key" = None ]; then
  echo "Choose a video with its original source still present."
  exit 1
fi
export SOURCE_KEY="$source_key"

python3 - <<'PY' > "$verification_dir/message.json"
import json, os
from urllib.parse import quote_plus
event = {"Records": [{"eventSource": "aws:s3", "eventName": "ObjectCreated:Put",
    "s3": {"bucket": {"name": "streamapp-uploads"},
           "object": {"key": quote_plus(os.environ["SOURCE_KEY"])}}}]}
print(json.dumps({"Type": "Notification", "Message": json.dumps(event)}))
PY

queue_url="$(aws --endpoint-url "$AWS_ENDPOINT_URL" sqs get-queue-url \
  --queue-name video-processing-lambda --query QueueUrl --output text)"
aws --endpoint-url "$AWS_ENDPOINT_URL" sqs send-message \
  --queue-url "$queue_url" --message-body "file://$verification_dir/message.json"
```

Wait for this invocation's Lambda log to show `Replaying retained processing result` with the chosen upload/attempt/execution IDs. An unchanged snapshot alone does not prove the queued message was consumed. Then run in the **same terminal**:

```bash
aws --endpoint-url "$AWS_ENDPOINT_URL" s3api get-object \
  --bucket streamapp-streams \
  --key "_processing/$VIDEO_ID/$VIDEO_ID.json" \
  "$verification_dir/after.json"

aws --endpoint-url "$AWS_ENDPOINT_URL" s3api list-objects-v2 \
  --bucket streamapp-streams --prefix "$VIDEO_ID/attempts/" \
  --query 'Contents[].[Key,ETag,LastModified]' --output json \
  > "$verification_dir/files-after.json"

diff -u "$verification_dir/before.json" "$verification_dir/after.json"
diff -u "$verification_dir/files-before.json" "$verification_dir/files-after.json"
```

Both diffs should be empty, no new execution directory should appear, and the video status/attempt timestamps should stay unchanged. No new `Transcoding upload`/FFmpeg work should run for that duplicate invocation. Report valid/invalid replay results plus playback/seek results.

## Automated evidence and limits

Pinned-Floci tests exercise competing ownership, expired takeover, stale-owner fencing, release safety and durable result immutability. Worker integration uses real S3 with synthetic FFmpeg output and injected publication/output failures: committed results survive restart even when the source is absent; partial output failure recovers under a different prefix. Backend tests cover artifact validation, atomic persistence, legacy fallback and HTTP ranges at both path depths. The existing default FFmpeg invalid-media test runs too; slow valid-media and deployed-native/browser checks remain separate.

Do not delete/overwrite live or completed coordination objects to force a retry. Native expired-claim takeover and real publication-outage acceptance remain additional scenarios before declaring all SA-009 acceptance complete. Abandoned execution files are isolated but are not cleaned by this slice; cleanup/deletion/retry dispatch follow later. Conditional-write tests on Floci do not prove production IAM, clock-skew handling, or exactly-once messaging.
