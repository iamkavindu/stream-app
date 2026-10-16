# One-terminal local application

Run from a checkout of this repository. The runner starts the actual application and deployed native Lambda; it does not substitute a local worker or fake completion messages.

## Prerequisites (once)

- Python 3.10+ (`python3` on Linux; Python launcher `py -3` or `python` on Windows).
- JDK 25+ with `java` and `javac` on PATH and a matching `JAVA_HOME` if set. Your working Java 27 setup is supported. Activate SDKMAN's JDK first if you use SDKMAN.
- Node 22.18+ in the 22.x line, or 24.12+; Node 24 is the tested baseline. `node` and `npm` must be on PATH.
- Docker Engine + Compose v2 on Linux, or Docker Desktop using **Linux containers / WSL2 engine** on Windows. Start Docker before running. The initial release supports an x86_64/amd64 Docker engine, matching the existing Lambda/FFmpeg setup. Leave enough Docker memory for the GraalVM native build (the previously used 16 GB machine is the local baseline, not an asserted minimum).
- Internet for images, Maven/npm dependencies and the initial FFmpeg download. No host AWS CLI, host GraalVM or manual WSL build shell is required.
- Stop manually started backend/frontend instances first; ports 8080 and 5173 must be available. Infrastructure continues using existing ports 5432, 4566 and 4500 and the existing Compose data locations. Run one checkout at a time.

## Start

Linux, from repository root:

```bash
./run-local.sh
```

Windows PowerShell, from repository root:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\run-local.ps1
```

`-ExecutionPolicy Bypass` applies only to that child PowerShell invocation. If your execution policy already permits local scripts, ` .\run-local.ps1` also works. Both entry points are checked in CI from a checkout directory containing spaces. Keep this single terminal open.

The runner:

1. Checks tools, JDK, Docker Linux/x86_64 mode and app ports; refuses a second active runner for the same checkout.
2. Starts existing PostgreSQL and Floci/UI Compose services. Waits for DB health and a real S3 API response.
3. Ensures the three demo buckets exist, runs the existing containerized messaging bootstrap, and verifies that the upload notification exists. A container-only copy normalizes shell line endings for existing Windows checkouts; tracked scripts are unchanged. The backend still owns CORS/local HLS read policy; bootstrap still owns SNS/SQS delivery configuration. This consolidates startup, not the future SA-020 provisioning ownership refactor.
4. Starts the backend with fixed local/test environment values; waits for its DB-backed API and Flyway migrations before replacing worker code.
5. Builds a Linux amd64 native Lambda in a GraalVM 25 container when its source fingerprint changes. The copy built inside Docker excludes host targets/test sources; Windows CRLF in the copied shell wrapper is normalized. Native Maven dependencies use a named Docker cache. Existing checked-in wrappers are untouched.
6. Reuses the latest existing `local-ffmpeg-layer`, or downloads and validates a static layer when absent. ZIPs have `bin/ffmpeg` and `bin/ffprobe` with Linux execution permissions even when packaged on Windows.
7. Uploads worker/layer ZIPs through the S3 deployment bucket, avoiding the 50 MiB direct API request limit. Existing mappings are paused and current in-flight deliveries drained before replacing code, then enabled with batch size one/window zero. Queue visibility/redrive is checked. No application message is manually received or purged. Deployment failures leave a paused mapping paused until a healthy rerun; a pre-deployment drain failure restores its previous enabled state. Stop new browser uploads while restarting.
8. Runs `npm ci`, starts Vite on a strict local port, and checks frontend readiness. Dependency manifests are not rewritten.

The first native build/download can take several minutes. Phase progress stays in the terminal; build/application output goes to `.local-run/setup.log`, `backend.log`, and `frontend.log`. Open http://localhost:5173 when **Ready** appears. API: http://localhost:8080/api/v1/videos. Floci UI: http://localhost:4500.

The runner supplies local `test` credentials and host/container endpoints to its children; it does not change your shell environment. Inherited AWS profiles/session tokens and Spring/application overrides are removed from these child environments. No `.env` edits are needed. Docker services keep their existing Compose networking/bind settings; this is the existing single-user local demo, not a remote deployment command.

## Stop and inspect

Press **Ctrl+C** in the runner terminal. It stops the backend/frontend process trees and any build it owns. Docker services and application data remain available. To stop infrastructure too, after Ctrl+C:

```bash
./run-local.sh down
./run-local.sh status
```

Windows:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\run-local.ps1 down
powershell -NoProfile -ExecutionPolicy Bypass -File .\run-local.ps1 status
```

`down` uses Compose **stop**, preserving volumes, the Floci data directory, buckets, queues and messages. There is deliberately no reset/purge option. `status` prints Docker service status and probes the app URLs. It is not proof that a video successfully decoded or streamed.

## Refresh caches and recover

Ordinary startup rebuilds worker code whenever `pom.xml`, Maven wrapper/config or `src/main` changes. It reuses the cached native binary otherwise; this cache is stored separately from `transcode-lambda/target` under ignored `.local-run/`. Published deployment object keys include a content hash; repeated starts do not create a fresh deployment object for identical ZIP content. Existing layer versions are reused.

Force a native rebuild or replace an old/broken layer:

```bash
./run-local.sh --rebuild-worker
./run-local.sh --rebuild-layer
```

Pass the same flags after `run-local.ps1` on Windows. Layer replacement downloads a fresh upstream static build and creates a new local layer version; the upstream release URL is not a reproducibly pinned FFmpeg build. Existing native/layer legacy scripts remain available, but this runner uses S3-backed package uploads and `http://floci:4566` inside Lambda.

On failure, the runner returns nonzero, names the failed phase, stops its own app processes, and retains logs/data. Fix the reported issue and run the same command again. A busy port is not treated as a healthy app instance. If an in-flight delivery remains invisible after a failed earlier invocation, startup may time out draining; let the configured queue visibility expire and rerun rather than deleting coordination or changing message visibility. If deployment failed after pausing a mapping, fix it and rerun to complete deployment/re-enable dispatch.

After startup, upload a new valid MP4, verify PLAY_READY, play and seek; try invalid MP4 bytes and verify FAILED. Stop/restart and verify old playback/data remain. CI checks shared runner behavior, package permissions, safe deployment command selection, child shutdown and both entry points. A complete Docker Desktop/native build/browser smoke run on Linux and Windows remains local acceptance; CI does not claim those runtimes were exercised.

PR #19 is stacked on PR #18. Testing `feat/sa-021-local-runner` includes both changes. Merge #18 before #19. After one-command startup, [processing-command-acceptance.md](./processing-command-acceptance.md) supplies the separate native command/replay probe; ordinary browser startup is not proof of that protocol.
