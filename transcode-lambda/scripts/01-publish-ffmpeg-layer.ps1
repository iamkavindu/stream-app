# Publishes the FFmpeg layer to local Floci.
# Run from transcode-lambda/ after 00-pre-setup-ffmpeg-layer.ps1.

$ErrorActionPreference = "Stop"

$endpoint = "http://localhost:4566"
$layerZip = "staging/ffmpeg-layer.zip"

if (-not (Test-Path $layerZip)) {
    Write-Error "Layer ZIP not found at $layerZip. Run scripts/00-pre-setup-ffmpeg-layer.ps1 first."
    exit 1
}

Write-Host "Publishing FFmpeg layer to Floci..." -ForegroundColor Cyan
aws --endpoint-url $endpoint lambda publish-layer-version `
    --layer-name local-ffmpeg-layer `
    --zip-file "fileb://$layerZip" `
    --compatible-runtimes provided.al2023 `
    --region us-east-1

Write-Host "Layer published." -ForegroundColor Green
