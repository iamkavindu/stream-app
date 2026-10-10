# Queue delivery and local verification (SA-006 slice 1)

The bootstrap scripts configure the same delivery settings on new and existing standard queues. Each queue has its own DLQ; names follow custom `BACKEND_QUEUE_NAME`, `LAMBDA_QUEUE_NAME`, and `TRANSCODE_COMPLETE_QUEUE_NAME` values too.

| Source | Visibility | Retention | DLQ | Maximum receives |
|---|---:|---:|---|---:|
| `video-processing-backend` | 30 seconds | 4 days | `video-processing-backend-dlq` | 5 |
| `video-processing-lambda` | 1,800 seconds | 4 days | `video-processing-lambda-dlq` | 5 |
| `video-transcode-complete-backend` | 30 seconds | 4 days | `video-transcode-complete-backend-dlq` | 5 |

DLQs retain messages for 14 days and accept redrive only from their matching source ARN. Bootstrap does not purge/delete source queues. Retention settings still expire messages normally. Queue access policies and SNS envelopes are unchanged.

The Lambda timeout remains 300 seconds with batch size one and batch window zero. Source visibility is six times the timeout, following [AWS guidance](https://docs.aws.amazon.com/lambda/latest/dg/services-sqs-configure.html). Deployment checks the actual queue before updating the function and reapplies batch/window settings to existing mappings too. If you change the timeout or batch window, update both bootstrap implementations and deployment together. These timeouts do not provide a worker lease or prevent duplicate processing; SA-008/SA-009 remain necessary.

This is a local Floci configuration. The completion queue's existing permissive local policy is not a production IAM execution-role policy. A message reaching a DLQ does not automatically mark its video FAILED; lifecycle-aware reconciliation belongs to SA-015. Do not call this item complete until pinned-emulator and deployed-native acceptance are recorded.

## First local gate: repeat provisioning, exhaustion, and replay

Prerequisites: Python 3, AWS CLI v2, Docker, and `floci/floci:2.1.0`. Run from the repository root:

```bash
docker compose -f docker/infra/aws/docker-compose.yaml up -d floci
python3 docker/infra/aws/verify-queue-delivery.py
```

The probe creates uniquely named temporary bucket/topic/queues, puts a sentinel payload in an existing queue, and invokes the real shell bootstrap twice. It checks delivery attributes for all three source/DLQ pairs, preserves the sentinel through provisioning, deliberately leaves five receives unacknowledged, confirms the unchanged body reaches the DLQ, and invokes `StartMessageMoveTask` to replay it. It acknowledges the replayed payload and removes only its own resources. Per-receive zero visibility accelerates this isolated exercise; application queue configuration is not shortened. A failure exits nonzero; report it without weakening assertions. If interrupted before cleanup, remove only resources with the printed `sa006-check-...` prefix.

Expected output includes three PASS lines: provisioning/settings, preserved payload/DLQ arrival, and replay/acknowledgement. This verifies SQS behavior; it does not invoke the native Lambda or prove its mapping failure behavior.

Local checks that do not require Docker:

```bash
python3 -m unittest discover -s docker/infra/aws/tests -v
sh -n docker/infra/aws/init-aws-resources.sh
```

## Apply to the application's existing queues

With the backend-created buckets present:

```bash
AWS_ENDPOINT_URL=http://localhost:4566 sh docker/infra/aws/init-aws-resources.sh
```

Run twice; both runs must succeed without changing existing message bodies or creating duplicate subscriptions. The existing bucket-notification skip behavior is unchanged in this slice and remains SA-020. On Windows use `docker/infra/aws/init-aws-resources.ps1`; PowerShell execution/parity is a separate verification gate. Re-run the existing native deployment workflow after applying bootstrap; missing visibility/redrive configuration must stop deployment before function mutation. Confirm the resulting mapping is enabled, batch size one, batch window zero, and function timeout 300 seconds.

## Inspect without consuming; controlled replay

Floci 2.1.0 documents a non-destructive inspection endpoint in [its SQS contract](https://github.com/floci-io/floci/blob/2.1.0/docs/services/sqs.md). This GET does not advance receive counts or change visibility:

```bash
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_DEFAULT_REGION=us-east-1
export AWS_ENDPOINT_URL=http://localhost:4566
DLQ_URL=$(aws sqs get-queue-url --queue-name video-processing-lambda-dlq --query QueueUrl --output text)
curl --get --data-urlencode "QueueUrl=$DLQ_URL" http://localhost:4566/_aws/sqs/messages
```

Fix the underlying error before replay. The automated probe demonstrates replay on isolated queues. Production-style bulk replay of app jobs is intentionally deferred until SA-008/SA-009 can reject stale attempts and coordinate concurrent workers. For a controlled single-user demo, inspect each message and current video/source state before choosing replay: terminal videos should not be blindly retranscoded, deleted/missing sources cannot recover by redrive alone, and result-publication failures may have already written artifacts. Never purge a DLQ to simulate successful recovery.

## Deployed native gate (still pending)

With one current nonterminal test upload, exercise an operational failure and confirm the Lambda invocation throws, its source message is not acknowledged, and retry after repair publishes the expected completion. Separately exhaust failures and confirm the original job reaches the matching DLQ. With production visibility this takes time; do not change the real application's visibility just to speed up the exercise. Use an isolated native acceptance environment for accelerated settings and restore/validate its original configuration afterward. Verify success, invalid-media FAILED publication, duplicate delivery, and interruption paths without manually manufacturing completion messages.

Native processing idempotency, bounded FFmpeg execution, lifecycle-aware replay, and database reconciliation remain later slices. Linux shell acceptance alone does not prove the Windows/WSL PowerShell/native deployment path.
