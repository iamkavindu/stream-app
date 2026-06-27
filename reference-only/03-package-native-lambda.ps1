# Ensure the script stops on errors
$ErrorActionPreference = "Stop"

Write-Host "Packaging native image for AWS Lambda deployment..." -ForegroundColor Cyan

$lambdaDir = "video-streamer-lambda"
$targetDir = "$lambdaDir\target"
$nativeBinary = "$targetDir\video-streamer-lambda"

if (-not (Test-Path $nativeBinary)) {
    Write-Error "Native binary not found at $nativeBinary. Did you run the build script?"
    exit 1
}

# Create a temporary packaging directory
$pkgDir = New-Item -ItemType Directory -Path "$targetDir\lambda-package" -Force

# Copy native binary as 'bootstrap'
Copy-Item -Path $nativeBinary -Destination "$pkgDir\bootstrap"

# Package as ZIP
$zipPath = "$targetDir\native-deployment.zip"
if (Test-Path $zipPath) { Remove-Item $zipPath }

# Using Compress-Archive for simple zipping
Compress-Archive -Path "$pkgDir\*" -DestinationPath $zipPath

Write-Host "Successfully packaged native image to $zipPath" -ForegroundColor Green
