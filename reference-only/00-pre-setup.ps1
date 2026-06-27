## Setup and zip FFmpeg
$stagingDir = "staging"
if (-not (Test-Path $stagingDir)) {
    New-Item -ItemType Directory -Path $stagingDir
}

$binDir = "scripts\bin"
if (-not (Test-Path $binDir)) {
    New-Item -ItemType Directory -Path $binDir
}

$ffmpegArchive = "scripts\ffmpeg-release.tar.xz"
Invoke-WebRequest -Uri "https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-amd64-static.tar.xz" -OutFile $ffmpegArchive

tar -xf $ffmpegArchive

$extractedDir = Get-ChildItem -Directory -Filter "ffmpeg-*-amd64-static" | Select-Object -First 1
if ($null -eq $extractedDir) {
    Throw "FFmpeg extracted directory not found."
}

Copy-Item -Path "$($extractedDir.FullName)\ffmpeg" -Destination "$binDir\"
Copy-Item -Path "$($extractedDir.FullName)\ffprobe" -Destination "$binDir\"

Compress-Archive -Path $binDir -DestinationPath "$stagingDir\ffmpeg-layer.zip" -Force

# Cleanup extracted directory and archive
Remove-Item -Path $extractedDir.FullName -Recurse -Force
Remove-Item -Path $ffmpegArchive -Force
