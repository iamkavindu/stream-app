# Ensure the script stops on errors
$ErrorActionPreference = "Stop"

$endpoint = "http://localhost:4566"
$region = "us-east-1"
$functionName = "s3-video-transcoder"
$zipFile = "video-streamer-lambda/target/native-deployment.zip"

if (-not (Test-Path $zipFile)) {
    Write-Error "Deployment package not found at $zipFile. Did you run the package script?"
    exit 1
}

Write-Host "Creating/Updating Lambda function '$functionName' in Floci..." -ForegroundColor Cyan

# Check if function exists
Write-Host "Checking if function '$functionName' exists..."
$functionExists = $false
try {
    aws --endpoint-url $endpoint lambda get-function --function-name $functionName --region $region --output json 2>$null
    if ($LASTEXITCODE -eq 0) {
        $functionExists = $true
    }
} catch {
    $functionExists = $false
}

if ($functionExists) {
    Write-Host "Function already exists. Updating code..."
    aws --endpoint-url $endpoint lambda update-function-code `
        --function-name $functionName `
        --zip-file "fileb://$zipFile" `
        --region $region

    Write-Host "Updating function configuration..."
    aws --endpoint-url $endpoint lambda update-function-configuration `
        --function-name $functionName `
        --region $region `
        --environment "Variables={APP_AWS_ENDPOINT=http://floci:4566,APP_AWS_ACCESS_KEY=test,APP_AWS_SECRET_KEY=test,APP_AWS_PATH_STYLE_ACCESS=true,APP_BUCKET_NAME_UPLOAD_DIR=upload,APP_BUCKET_NAME_STREAM_DIR=stream,APP_SQS_QUEUE_VIDEO_PROCESSING_QUEUE=video-processing-events}"
} else {
    Write-Host "Creating new function..."
    aws --endpoint-url $endpoint lambda create-function `
        --function-name $functionName `
        --runtime provided.al2023 `
        --role arn:aws:iam::000000000000:role/local-execution-role `
        --handler bootstrap `
        --zip-file "fileb://$zipFile" `
        --layers "arn:aws:lambda:us-east-1:000000000000:layer:local-ffmpeg-layer:1" `
        --memory-size 2048 `
        --timeout 300 `
        --region $region `
        --environment "Variables={APP_AWS_ENDPOINT=http://floci:4566,APP_AWS_ACCESS_KEY=test,APP_AWS_SECRET_KEY=test,APP_AWS_PATH_STYLE_ACCESS=true,APP_BUCKET_NAME_UPLOAD_DIR=upload,APP_BUCKET_NAME_STREAM_DIR=stream,APP_SQS_QUEUE_VIDEO_PROCESSING_QUEUE=video-processing-events}"
}

Write-Host "Deployment completed successfully." -ForegroundColor Green
