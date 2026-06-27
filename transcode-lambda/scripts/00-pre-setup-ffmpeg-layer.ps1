# Downloads a static FFmpeg build and packages it as a Lambda layer ZIP.
# Run from transcode-lambda/ (repo root/transcode-lambda).

$ErrorActionPreference = "Stop"

$stagingDir = "staging"
if (-not (Test-Path $stagingDir)) {
    New-Item -ItemType Directory -Path $stagingDir | Out-Null
}

$binDir = "scripts\bin"
if (-not (Test-Path $binDir)) {
    New-Item -ItemType Directory -Path $binDir | Out-Null
}

$ffmpegArchive = "scripts\ffmpeg-release.tar.xz"
Write-Host "Downloading FFmpeg static build..." -ForegroundColor Cyan
Invoke-WebRequest -Uri "https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-amd64-static.tar.xz" -OutFile $ffmpegArchive

tar -xf $ffmpegArchive

$extractedDir = Get-ChildItem -Directory -Filter "ffmpeg-*-amd64-static" | Select-Object -First 1
if ($null -eq $extractedDir) {
    Throw "FFmpeg extracted directory not found."
}

Copy-Item -Path "$($extractedDir.FullName)\ffmpeg" -Destination "$binDir\"
Copy-Item -Path "$($extractedDir.FullName)\ffprobe" -Destination "$binDir\"

# Lambda merges zip contents under /opt (bin/ffmpeg -> /opt/bin/ffmpeg).
# Do not wrap in an extra opt/ folder: Floci mounts the archive root at /opt, so
# opt/bin/ffmpeg in the zip becomes /opt/opt/bin/ffmpeg and breaks APP_FFMPEG_PATH.
$layerRoot = "$stagingDir\layer"
$layerBin = "$layerRoot\bin"
New-Item -ItemType Directory -Path $layerBin -Force | Out-Null
Copy-Item -Path "$binDir\ffmpeg" -Destination "$layerBin\"
Copy-Item -Path "$binDir\ffprobe" -Destination "$layerBin\"

$layerZip = "$stagingDir\ffmpeg-layer.zip"
if (Test-Path $layerZip) { Remove-Item $layerZip -Force }
Compress-Archive -Path "$layerRoot\*" -DestinationPath $layerZip -Force

Remove-Item -Path $extractedDir.FullName -Recurse -Force
Remove-Item -Path $ffmpegArchive -Force
Remove-Item -Path $layerRoot -Recurse -Force

Write-Host "FFmpeg layer ZIP: $layerZip" -ForegroundColor Green
