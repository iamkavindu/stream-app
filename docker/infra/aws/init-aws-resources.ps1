# Idempotent Floci bootstrap: SNS topic, SQS queues, S3 upload-bucket notification.
# Buckets and CORS are created by the Spring backend on startup (ResourceInitialize).
#
# Upload flow: S3 ObjectCreated -> SNS -> video-processing-backend + video-processing-lambda
# Transcode complete (Lambda -> backend): video-transcode-complete-backend
#
# Environment (defaults suit local host runs against Floci on localhost:4566):
#   AWS_ENDPOINT_URL              Floci endpoint (default: http://127.0.0.1:4566)
#   AWS_DEFAULT_REGION            AWS region (default: us-east-1)
#   UPLOAD_BUCKET                 S3 bucket for client uploads (default: streamapp-uploads)
#   UPLOAD_TOPIC_NAME             SNS topic for upload-complete events (default: video-upload-events)
#   BACKEND_QUEUE_NAME            Backend status queue (default: video-processing-backend)
#   LAMBDA_QUEUE_NAME             Lambda transcode trigger queue (default: video-processing-lambda)
#   TRANSCODE_COMPLETE_QUEUE_NAME Lambda completion queue (default: video-transcode-complete-backend)
#   AWS_ACCOUNT_ID                Floci account id (default: 000000000000)
#   BUCKET_WAIT_SECONDS           Max wait for upload bucket before skipping notification (default: 120)

$ErrorActionPreference = "Stop"

$EndpointUrl = if ($env:AWS_ENDPOINT_URL) { $env:AWS_ENDPOINT_URL } else { "http://127.0.0.1:4566" }
$Region = if ($env:AWS_DEFAULT_REGION) { $env:AWS_DEFAULT_REGION } else { "us-east-1" }
$UploadBucket = if ($env:UPLOAD_BUCKET) { $env:UPLOAD_BUCKET } else { "streamapp-uploads" }
$UploadTopicName = if ($env:UPLOAD_TOPIC_NAME) { $env:UPLOAD_TOPIC_NAME } else { "video-upload-events" }
$BackendQueueName = if ($env:BACKEND_QUEUE_NAME) { $env:BACKEND_QUEUE_NAME } else { "video-processing-backend" }
$LambdaQueueName = if ($env:LAMBDA_QUEUE_NAME) { $env:LAMBDA_QUEUE_NAME } else { "video-processing-lambda" }
$TranscodeCompleteQueueName = if ($env:TRANSCODE_COMPLETE_QUEUE_NAME) { $env:TRANSCODE_COMPLETE_QUEUE_NAME } else { "video-transcode-complete-backend" }
$AccountId = if ($env:AWS_ACCOUNT_ID) { $env:AWS_ACCOUNT_ID } else { "000000000000" }
$BucketWaitSeconds = if ($env:BUCKET_WAIT_SECONDS) { [int]$env:BUCKET_WAIT_SECONDS } else { 120 }

$env:AWS_ACCESS_KEY_ID = if ($env:AWS_ACCESS_KEY_ID) { $env:AWS_ACCESS_KEY_ID } else { "test" }
$env:AWS_SECRET_ACCESS_KEY = if ($env:AWS_SECRET_ACCESS_KEY) { $env:AWS_SECRET_ACCESS_KEY } else { "test" }
$env:AWS_DEFAULT_REGION = $Region

$BucketArn = "arn:aws:s3:::$UploadBucket"
$TopicArn = "arn:aws:sns:${Region}:${AccountId}:$UploadTopicName"

function Write-Log {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Message)
    Write-Host ($Message -join " ")
}

function Invoke-AwsCmd {
    & aws --endpoint-url $EndpointUrl @args
    if ($LASTEXITCODE -ne 0) {
        throw "aws command failed (exit $LASTEXITCODE): aws --endpoint-url $EndpointUrl $($args -join ' ')"
    }
}

function Wait-ForFloci {
    Write-Log "Waiting for Floci at $EndpointUrl..."
    $attempts = 0
    $maxAttempts = 30
    while ($true) {
        aws --endpoint-url $EndpointUrl s3 ls 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) {
            Write-Log "Floci is ready."
            return
        }
        $attempts++
        if ($attempts -ge $maxAttempts) {
            Write-Log "ERROR: Floci not reachable at $EndpointUrl"
            exit 1
        }
        Start-Sleep -Seconds 2
    }
}

function Ensure-Topic {
    Write-Log "Ensuring SNS topic: $UploadTopicName"
    Invoke-AwsCmd sns create-topic --name $UploadTopicName | Out-Null
    Write-Log "Topic ARN: $TopicArn"
}

function Ensure-Queue {
    param([string]$QueueName)
    Write-Log "Ensuring SQS queue: $QueueName"
    $queueUrl = Invoke-AwsCmd sqs create-queue --queue-name $QueueName --query QueueUrl --output text
    $queueArn = Invoke-AwsCmd sqs get-queue-attributes `
        --queue-url $queueUrl `
        --attribute-names QueueArn `
        --query "Attributes.QueueArn" `
        --output text
    Write-Log "Queue URL: $queueUrl"
    Write-Log "Queue ARN: $queueArn"
}

function Get-QueueArn {
    param([string]$QueueName)
    $queueUrl = Invoke-AwsCmd sqs get-queue-url --queue-name $QueueName --query QueueUrl --output text
    Invoke-AwsCmd sqs get-queue-attributes `
        --queue-url $queueUrl `
        --attribute-names QueueArn `
        --query "Attributes.QueueArn" `
        --output text
}

function Write-Utf8NoBom {
    param(
        [string]$Path,
        [string]$Content
    )
    [System.IO.File]::WriteAllText($Path, $Content, [System.Text.UTF8Encoding]::new($false))
}

function Set-QueuePolicy {
    param(
        [string]$QueueName,
        [string]$Policy
    )
    $queueUrl = Invoke-AwsCmd sqs get-queue-url --queue-name $QueueName --query QueueUrl --output text
    $attrsFile = New-TemporaryFile
    try {
        $attrsJson = @{ Policy = $Policy } | ConvertTo-Json -Compress
        Write-Utf8NoBom -Path $attrsFile.FullName -Content $attrsJson
        $fileUri = "file://$($attrsFile.FullName -replace '\\', '/')"
        Invoke-AwsCmd sqs set-queue-attributes --queue-url $queueUrl --attributes $fileUri
    }
    finally {
        Remove-Item -Path $attrsFile.FullName -Force -ErrorAction SilentlyContinue
    }
}

function Ensure-TopicPolicy {
    Write-Log "Applying SNS topic policy (allow S3 upload events from $BucketArn)"
    $policy = (@{
        Version   = "2012-10-17"
        Statement = @(
            @{
                Sid       = "AllowS3UploadEvents"
                Effect    = "Allow"
                Principal = @{ Service = "s3.amazonaws.com" }
                Action    = "SNS:Publish"
                Resource  = $TopicArn
                Condition = @{
                    ArnLike       = @{ "aws:SourceArn" = $BucketArn }
                    StringEquals  = @{ "aws:SourceAccount" = $AccountId }
                }
            }
        )
    } | ConvertTo-Json -Compress -Depth 10)
    Invoke-AwsCmd sns set-topic-attributes `
        --topic-arn $TopicArn `
        --attribute-name Policy `
        --attribute-value $policy
}

function Ensure-SnsQueuePolicy {
    param([string]$QueueName)
    $queueArn = Get-QueueArn -QueueName $QueueName
    Write-Log "Applying SQS policy for SNS fan-out on $QueueName"
    $policy = (@{
        Version   = "2012-10-17"
        Statement = @(
            @{
                Sid       = "AllowSnsFanOut"
                Effect    = "Allow"
                Principal = @{ Service = "sns.amazonaws.com" }
                Action    = "sqs:SendMessage"
                Resource  = $queueArn
                Condition = @{
                    ArnEquals = @{ "aws:SourceArn" = $TopicArn }
                }
            }
        )
    } | ConvertTo-Json -Compress -Depth 10)
    Set-QueuePolicy -QueueName $QueueName -Policy $policy
}

function Ensure-TranscodeCompleteQueuePolicy {
    $queueArn = Get-QueueArn -QueueName $TranscodeCompleteQueueName
    Write-Log "Applying SQS policy for Lambda completion messages on $TranscodeCompleteQueueName"
    $policy = (@{
        Version   = "2012-10-17"
        Statement = @(
            @{
                Sid       = "AllowLambdaCompletion"
                Effect    = "Allow"
                Principal = @{ Service = "lambda.amazonaws.com" }
                Action    = "sqs:SendMessage"
                Resource  = $queueArn
                Condition = @{
                    StringEquals = @{ "aws:SourceAccount" = $AccountId }
                }
            }
        )
    } | ConvertTo-Json -Compress -Depth 10)
    Set-QueuePolicy -QueueName $TranscodeCompleteQueueName -Policy $policy
}

function Subscribe-QueueToTopic {
    param([string]$QueueName)
    $queueArn = Get-QueueArn -QueueName $QueueName
    Write-Log "Subscribing $QueueName to SNS topic $UploadTopicName"
    try {
        Invoke-AwsCmd sns subscribe `
            --topic-arn $TopicArn `
            --protocol sqs `
            --notification-endpoint $queueArn `
            --attributes RawMessageDelivery=false | Out-Null
    }
    catch {
        # Idempotent: subscription may already exist.
    }
}

function Wait-ForUploadBucket {
    Write-Log "Waiting for upload bucket $UploadBucket (created by backend on first start)..."
    $elapsed = 0
    while ($elapsed -lt $BucketWaitSeconds) {
        aws --endpoint-url $EndpointUrl s3api head-bucket --bucket $UploadBucket 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) {
            Write-Log "Upload bucket $UploadBucket exists."
            return $true
        }
        Start-Sleep -Seconds 2
        $elapsed += 2
    }
    Write-Log "WARN: Upload bucket $UploadBucket not found after ${BucketWaitSeconds}s."
    Write-Log "WARN: Skipping S3 event notification. Re-run this script after starting the backend:"
    Write-Log "WARN:   .\docker\infra\aws\init-aws-resources.ps1"
    return $false
}

function Ensure-BucketNotification {
    Write-Log "Configuring S3 event notification: $UploadBucket -> SNS $UploadTopicName"
    $notifFile = New-TemporaryFile
    try {
        $notification = @{
            TopicConfigurations = @(
                @{
                    Id       = "upload-complete-sns"
                    TopicArn = $TopicArn
                    Events   = @("s3:ObjectCreated:*")
                }
            )
        } | ConvertTo-Json -Depth 10
        Write-Utf8NoBom -Path $notifFile.FullName -Content $notification
        $fileUri = "file://$($notifFile.FullName -replace '\\', '/')"
        Invoke-AwsCmd s3api put-bucket-notification-configuration `
            --bucket $UploadBucket `
            --notification-configuration $fileUri
        Write-Log "S3 bucket notification configured."
    }
    finally {
        Remove-Item -Path $notifFile.FullName -Force -ErrorAction SilentlyContinue
    }
}

Wait-ForFloci
Ensure-Topic
Ensure-Queue -QueueName $BackendQueueName
Ensure-Queue -QueueName $LambdaQueueName
Ensure-Queue -QueueName $TranscodeCompleteQueueName
Ensure-TopicPolicy
Ensure-SnsQueuePolicy -QueueName $BackendQueueName
Ensure-SnsQueuePolicy -QueueName $LambdaQueueName
Ensure-TranscodeCompleteQueuePolicy
Subscribe-QueueToTopic -QueueName $BackendQueueName
Subscribe-QueueToTopic -QueueName $LambdaQueueName
if (Wait-ForUploadBucket) {
    Ensure-BucketNotification
}
Write-Log "AWS resource initialization complete."
