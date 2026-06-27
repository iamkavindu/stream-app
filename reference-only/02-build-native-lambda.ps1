# Ensure the script stops on errors
$ErrorActionPreference = "Stop"

# Use WSL to run the native compilation
Write-Host "Building native image for video-streamer-lambda inside WSL..." -ForegroundColor Cyan

# We assume WSL has the necessary tools (GraalVM, Maven, etc.)
# Mapping Windows path to WSL path (assuming C: is /mnt/c)
$currentPath = (Get-Location).FullName
# Convert to WSL path and ensure it's a clean string
$wslPath = (wsl wslpath -a -u "'$currentPath'").Trim().Replace("`0", "")

# Use interactive login shell to ensure SDKMAN! is initialized
# First install the dependencies locally (applying formatting fixes if needed), then build the native image
wsl bash -i -l -c "cd '$wslPath' && ./mvnw spotless:apply && ./mvnw install -DskipTests && ./mvnw -pl video-streamer-lambda -Pnative native:compile"

if ($LASTEXITCODE -ne 0) {
    Write-Error "Native compilation failed."
    exit $LASTEXITCODE
}

Write-Host "Native compilation successful." -ForegroundColor Green
