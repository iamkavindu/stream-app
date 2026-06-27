# Builds the GraalVM native image inside WSL (Linux toolchain required).
# Run from transcode-lambda/.
# Prerequisites: WSL with GraalVM JDK 25+, Maven wrapper, and native-image.

$ErrorActionPreference = "Stop"

Write-Host "Building native image for transcode-lambda inside WSL..." -ForegroundColor Cyan

$currentPath = (Get-Location).FullName
$wslPath = (wsl wslpath -a -u "'$currentPath'").Trim().Replace("`0", "")

wsl bash -i -l -c "cd '$wslPath' && ./mvnw install -DskipTests && ./mvnw -Pnative native:compile"

if ($LASTEXITCODE -ne 0) {
    Write-Error "Native compilation failed."
    exit $LASTEXITCODE
}

Write-Host "Native binary: target/transcode-lambda" -ForegroundColor Green
