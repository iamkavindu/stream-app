# Packages the native binary as an AWS Lambda custom-runtime ZIP (bootstrap).
# Run from transcode-lambda/ after 02-build-native-lambda.ps1.

$ErrorActionPreference = "Stop"

Write-Host "Packaging native image for Lambda deployment..." -ForegroundColor Cyan

$nativeBinary = "target\transcode-lambda"

if (-not (Test-Path $nativeBinary)) {
    Write-Error "Native binary not found at $nativeBinary. Run scripts/02-build-native-lambda.ps1 first."
    exit 1
}

$pkgDir = New-Item -ItemType Directory -Path "target\lambda-package" -Force
Copy-Item -Path $nativeBinary -Destination "$pkgDir\bootstrap"

$zipPath = "target\native-deployment.zip"
if (Test-Path $zipPath) { Remove-Item $zipPath -Force }

Compress-Archive -Path "$pkgDir\*" -DestinationPath $zipPath

Write-Host "Deployment package: $zipPath" -ForegroundColor Green
