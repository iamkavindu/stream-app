# Deploys the native Lambda to local Floci and wires SQS event source mapping.
# Run from transcode-lambda/ after 03-package-native-lambda.ps1 and 01-publish-ffmpeg-layer.ps1.

$ErrorActionPreference = "Stop"

$endpoint = "http://localhost:4566"
$region = "us-east-1"
$accountId = "000000000000"
$functionName = "streamapp-transcode-lambda"
$zipFile = "target/native-deployment.zip"
$lambdaQueue = "video-processing-lambda"
$layerName = "local-ffmpeg-layer"

if (-not (Test-Path $zipFile)) {
    Write-Error "Deployment package not found at $zipFile. Run scripts/03-package-native-lambda.ps1 first."
    exit 1
}

$layerVersions = aws --endpoint-url $endpoint lambda list-layer-versions `
    --layer-name $layerName `
    --region $region `
    --output json | ConvertFrom-Json

if (-not $layerVersions.LayerVersions -or $layerVersions.LayerVersions.Count -eq 0) {
    Write-Error "No FFmpeg layer found. Run scripts/00-pre-setup-ffmpeg-layer.ps1 and 01-publish-ffmpeg-layer.ps1 first."
    exit 1
}

$layerArn = ($layerVersions.LayerVersions | Sort-Object -Property Version -Descending | Select-Object -First 1).LayerVersionArn
Write-Host "Using FFmpeg layer: $layerArn" -ForegroundColor Cyan

$envVars = @(
    "APP_AWS_ENDPOINT=http://host.docker.internal:4566",
    "APP_AWS_REGION=us-east-1",
    "APP_AWS_ACCESS_KEY=test",
    "APP_AWS_SECRET_KEY=test",
    "APP_AWS_PATH_STYLE_ACCESS=true",
    "APP_BUCKET_NAME_UPLOAD_BUCKET=streamapp-uploads",
    "APP_BUCKET_NAME_STREAM_BUCKET=streamapp-streams",
    "APP_SQS_QUEUE_TRANSCODE_COMPLETE_QUEUE=video-transcode-complete-backend",
    "APP_FFMPEG_PATH=/opt/bin/ffmpeg",
    "SPRING_PROFILES_ACTIVE=prod"
) -join ","

Write-Host "Deploying Lambda '$functionName' to Floci..." -ForegroundColor Cyan

$functionExists = $false
aws --endpoint-url $endpoint lambda get-function --function-name $functionName --region $region 2>$null | Out-Null
if ($LASTEXITCODE -eq 0) {
    $functionExists = $true
}

if ($functionExists) {
    Write-Host "Updating function code..."
    aws --endpoint-url $endpoint lambda update-function-code `
        --function-name $functionName `
        --zip-file "fileb://$zipFile" `
        --region $region

    Write-Host "Updating function configuration (layers, memory, timeout, env)..."
    aws --endpoint-url $endpoint lambda update-function-configuration `
        --function-name $functionName `
        --region $region `
        --layers $layerArn `
        --memory-size 2048 `
        --timeout 300 `
        --environment "Variables={$envVars}"
} else {
    Write-Host "Creating function..."
    aws --endpoint-url $endpoint lambda create-function `
        --function-name $functionName `
        --runtime provided.al2023 `
        --role "arn:aws:iam::${accountId}:role/local-execution-role" `
        --handler bootstrap `
        --zip-file "fileb://$zipFile" `
        --layers $layerArn `
        --memory-size 2048 `
        --timeout 300 `
        --region $region `
        --environment "Variables={$envVars}"
}

$queueArn = "arn:aws:sqs:${region}:${accountId}:${lambdaQueue}"
Write-Host "Ensuring SQS event source mapping ($lambdaQueue -> $functionName)..."
$existingMappings = aws --endpoint-url $endpoint lambda list-event-source-mappings `
    --function-name $functionName `
    --region $region `
    --output json | ConvertFrom-Json

$hasMapping = $false
foreach ($mapping in $existingMappings.EventSourceMappings) {
    if ($mapping.EventSourceArn -eq $queueArn) {
        $hasMapping = $true
        break
    }
}

if (-not $hasMapping) {
    aws --endpoint-url $endpoint lambda create-event-source-mapping `
        --function-name $functionName `
        --event-source-arn $queueArn `
        --batch-size 1 `
        --region $region
}

Write-Host "Deployment complete." -ForegroundColor Green
