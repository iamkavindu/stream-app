#!/usr/bin/env sh
set -eu

# Idempotent Floci bootstrap: SNS topic, SQS queues, S3 upload-bucket notification.
# Buckets and CORS are created by the Spring backend on startup (ResourceInitialize).
#
# Upload flow: S3 ObjectCreated -> SNS -> video-processing-backend + video-processing-lambda
# Transcode complete (Lambda -> backend): video-transcode-complete-backend
#
# Environment (defaults suit docker compose aws-init service):
#   AWS_ENDPOINT_URL              Floci endpoint (default: http://floci:4566)
#   AWS_DEFAULT_REGION            AWS region (default: us-east-1)
#   UPLOAD_BUCKET                 S3 bucket for client uploads (default: streamapp-uploads)
#   UPLOAD_TOPIC_NAME             SNS topic for upload-complete events (default: video-upload-events)
#   BACKEND_QUEUE_NAME            Backend status queue (default: video-processing-backend)
#   LAMBDA_QUEUE_NAME             Lambda transcode trigger queue (default: video-processing-lambda)
#   TRANSCODE_COMPLETE_QUEUE_NAME Lambda completion queue (default: video-transcode-complete-backend)
#   AWS_ACCOUNT_ID                Floci account id (default: 000000000000)
#   BUCKET_WAIT_SECONDS           Max wait for upload bucket before skipping notification (default: 120)

ENDPOINT_URL="${AWS_ENDPOINT_URL:-http://floci:4566}"
REGION="${AWS_DEFAULT_REGION:-us-east-1}"
UPLOAD_BUCKET="${UPLOAD_BUCKET:-streamapp-uploads}"
UPLOAD_TOPIC_NAME="${UPLOAD_TOPIC_NAME:-video-upload-events}"
BACKEND_QUEUE_NAME="${BACKEND_QUEUE_NAME:-video-processing-backend}"
LAMBDA_QUEUE_NAME="${LAMBDA_QUEUE_NAME:-video-processing-lambda}"
TRANSCODE_COMPLETE_QUEUE_NAME="${TRANSCODE_COMPLETE_QUEUE_NAME:-video-transcode-complete-backend}"
ACCOUNT_ID="${AWS_ACCOUNT_ID:-000000000000}"
BUCKET_WAIT_SECONDS="${BUCKET_WAIT_SECONDS:-120}"

export AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID:-test}"
export AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY:-test}"
export AWS_DEFAULT_REGION="$REGION"

BUCKET_ARN="arn:aws:s3:::${UPLOAD_BUCKET}"
TOPIC_ARN="arn:aws:sns:${REGION}:${ACCOUNT_ID}:${UPLOAD_TOPIC_NAME}"

log() {
  printf '%s\n' "$*"
}

aws_cmd() {
  aws --endpoint-url "$ENDPOINT_URL" "$@"
}

wait_for_floci() {
  log "Waiting for Floci at ${ENDPOINT_URL}..."
  attempts=0
  max_attempts=30
  until aws_cmd s3 ls >/dev/null 2>&1; do
    attempts=$((attempts + 1))
    if [ "$attempts" -ge "$max_attempts" ]; then
      log "ERROR: Floci not reachable at ${ENDPOINT_URL}"
      exit 1
    fi
    sleep 2
  done
  log "Floci is ready."
}

ensure_topic() {
  log "Ensuring SNS topic: ${UPLOAD_TOPIC_NAME}"
  aws_cmd sns create-topic --name "$UPLOAD_TOPIC_NAME" >/dev/null
  log "Topic ARN: ${TOPIC_ARN}"
}

ensure_queue() {
  queue_name="$1"
  log "Ensuring SQS queue: ${queue_name}"
  queue_url="$(aws_cmd sqs create-queue --queue-name "$queue_name" --query QueueUrl --output text)"
  queue_arn="$(aws_cmd sqs get-queue-attributes \
    --queue-url "$queue_url" \
    --attribute-names QueueArn \
    --query 'Attributes.QueueArn' \
    --output text)"
  log "Queue URL: ${queue_url}"
  log "Queue ARN: ${queue_arn}"
}

get_queue_arn() {
  queue_name="$1"
  queue_url="$(aws_cmd sqs get-queue-url --queue-name "$queue_name" --query QueueUrl --output text)"
  aws_cmd sqs get-queue-attributes \
    --queue-url "$queue_url" \
    --attribute-names QueueArn \
    --query 'Attributes.QueueArn' \
    --output text
}

set_queue_policy() {
  queue_name="$1"
  policy="$2"
  queue_url="$(aws_cmd sqs get-queue-url --queue-name "$queue_name" --query QueueUrl --output text)"
  attrs_file="$(mktemp)"
  policy_escaped="$(printf '%s' "$policy" | sed 's/\\/\\\\/g' | sed 's/"/\\"/g')"
  printf '{\n  "Policy": "%s"\n}\n' "$policy_escaped" > "$attrs_file"
  aws_cmd sqs set-queue-attributes \
    --queue-url "$queue_url" \
    --attributes "file://${attrs_file}"
  rm -f "$attrs_file"
}

ensure_topic_policy() {
  log "Applying SNS topic policy (allow S3 upload events from ${BUCKET_ARN})"
  policy="$(printf '{"Version":"2012-10-17","Statement":[{"Sid":"AllowS3UploadEvents","Effect":"Allow","Principal":{"Service":"s3.amazonaws.com"},"Action":"SNS:Publish","Resource":"%s","Condition":{"ArnLike":{"aws:SourceArn":"%s"},"StringEquals":{"aws:SourceAccount":"%s"}}}]}' \
    "$TOPIC_ARN" "$BUCKET_ARN" "$ACCOUNT_ID")"
  attrs_file="$(mktemp)"
  policy_escaped="$(printf '%s' "$policy" | sed 's/\\/\\\\/g' | sed 's/"/\\"/g')"
  printf '{\n  "Attributes": {\n    "Policy": "%s"\n  }\n}\n' "$policy_escaped" > "$attrs_file"
  aws_cmd sns set-topic-attributes \
    --topic-arn "$TOPIC_ARN" \
    --attribute-name Policy \
    --attribute-value "$policy"
  rm -f "$attrs_file"
}

ensure_sns_queue_policy() {
  queue_name="$1"
  queue_arn="$(get_queue_arn "$queue_name")"
  log "Applying SQS policy for SNS fan-out on ${queue_name}"
  policy="$(printf '{"Version":"2012-10-17","Statement":[{"Sid":"AllowSnsFanOut","Effect":"Allow","Principal":{"Service":"sns.amazonaws.com"},"Action":"sqs:SendMessage","Resource":"%s","Condition":{"ArnEquals":{"aws:SourceArn":"%s"}}}]}' \
    "$queue_arn" "$TOPIC_ARN")"
  set_queue_policy "$queue_name" "$policy"
}

ensure_transcode_complete_queue_policy() {
  queue_arn="$(get_queue_arn "$TRANSCODE_COMPLETE_QUEUE_NAME")"
  log "Applying SQS policy for Lambda completion messages on ${TRANSCODE_COMPLETE_QUEUE_NAME}"
  policy="$(printf '{"Version":"2012-10-17","Statement":[{"Sid":"AllowLambdaCompletion","Effect":"Allow","Principal":{"Service":"lambda.amazonaws.com"},"Action":"sqs:SendMessage","Resource":"%s","Condition":{"StringEquals":{"aws:SourceAccount":"%s"}}}]}' \
    "$queue_arn" "$ACCOUNT_ID")"
  set_queue_policy "$TRANSCODE_COMPLETE_QUEUE_NAME" "$policy"
}

subscribe_queue_to_topic() {
  queue_name="$1"
  queue_arn="$(get_queue_arn "$queue_name")"
  log "Subscribing ${queue_name} to SNS topic ${UPLOAD_TOPIC_NAME}"
  aws_cmd sns subscribe \
    --topic-arn "$TOPIC_ARN" \
    --protocol sqs \
    --notification-endpoint "$queue_arn" \
    --attributes RawMessageDelivery=false \
    >/dev/null || true
}

wait_for_upload_bucket() {
  log "Waiting for upload bucket ${UPLOAD_BUCKET} (created by backend on first start)..."
  elapsed=0
  while [ "$elapsed" -lt "$BUCKET_WAIT_SECONDS" ]; do
    if aws_cmd s3api head-bucket --bucket "$UPLOAD_BUCKET" >/dev/null 2>&1; then
      log "Upload bucket ${UPLOAD_BUCKET} exists."
      return 0
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done
  log "WARN: Upload bucket ${UPLOAD_BUCKET} not found after ${BUCKET_WAIT_SECONDS}s."
  log "WARN: Skipping S3 event notification. Re-run this script after starting the backend:"
  log "WARN:   sh docker/infra/aws/init-aws-resources.sh"
  return 1
}

ensure_bucket_notification() {
  log "Configuring S3 event notification: ${UPLOAD_BUCKET} -> SNS ${UPLOAD_TOPIC_NAME}"
  notif_file="$(mktemp)"
  trap 'rm -f "$notif_file"' EXIT INT TERM
  cat > "$notif_file" <<EOF
{
  "TopicConfigurations": [{
    "Id": "upload-complete-sns",
    "TopicArn": "${TOPIC_ARN}",
    "Events": ["s3:ObjectCreated:*"]
  }]
}
EOF
  aws_cmd s3api put-bucket-notification-configuration \
    --bucket "$UPLOAD_BUCKET" \
    --notification-configuration "file://${notif_file}"
  log "S3 bucket notification configured."
}

main() {
  wait_for_floci
  ensure_topic
  ensure_queue "$BACKEND_QUEUE_NAME"
  ensure_queue "$LAMBDA_QUEUE_NAME"
  ensure_queue "$TRANSCODE_COMPLETE_QUEUE_NAME"
  ensure_topic_policy
  ensure_sns_queue_policy "$BACKEND_QUEUE_NAME"
  ensure_sns_queue_policy "$LAMBDA_QUEUE_NAME"
  ensure_transcode_complete_queue_policy
  subscribe_queue_to_topic "$BACKEND_QUEUE_NAME"
  subscribe_queue_to_topic "$LAMBDA_QUEUE_NAME"
  if wait_for_upload_bucket; then
    ensure_bucket_notification
  fi
  log "AWS resource initialization complete."
}

main "$@"
