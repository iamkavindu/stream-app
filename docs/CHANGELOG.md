# Changelog

## 2026-10-11 — SA-023 slice 2: Java integration CI and explicit test evidence

**Summary:** Automate backend and Lambda JVM integration checks and prevent empty or unexpectedly skipped suites from appearing green.

**Changed**

- `.github/workflows/java-ci.yml` — Java 25 module matrix, Docker prerequisite, fresh Maven builds/codegen, FFmpeg on the Lambda runner, failure-independent jobs, read-only permissions, pinned actions, and seven-day Surefire artifacts
- `scripts/ci/summarize-surefire.py` and regression tests — aggregate XML reports, reject missing/empty/malformed/failed/skipped runs, and publish test counts plus excluded tiers in the job summary
- Project/backlog — record PR #10 merge, new JVM gate, and remaining native/browser/PowerShell acceptance

**Validation:** Four report-checker regression tests, workflow YAML/matrix/action-input inspection, and diff checks pass here. GitHub Java execution is pending; this workspace lacks Java 25 and Docker. Existing POM tag exclusions remain explicit; no application, dependency, wrapper, or lockfile changes are included.

---

## 2026-10-11 — SA-023 slice 1: frontend CI and review evidence

**Summary:** Run repeatable frontend checks on pull requests and capture validation scope before review.

**Changed**

- `.github/workflows/frontend-ci.yml` — locked npm installation, Vitest, production/type-check build, dependency-manifest diff check, read-only permissions, pinned action revisions, bounded runtime, and cancellation of superseded runs
- `.github/pull_request_template.md` — task/slice, before/after behavior, compatibility, stack order, actual validation, and outstanding local reproduction
- Project/backlog — describe this CI tier and its limits; record PRs #6–#8 merged and keep remaining browser/PowerShell/native gates explicit

**Validation:** Workflow YAML and verified action inputs pass inspection. A clean `npm ci`, all 95 frontend tests, the production/type-checked build, and dependency-manifest/diff checks pass locally on Node 24.19.0. The first GitHub-hosted run passed every step; frontend CI does not replace Java/Floci, native Lambda, PowerShell, or browser playback acceptance. No lockfile, wrapper, dependency, or application-code changes are included.

---

## 2026-10-11 — SA-019 slice 1: prevent stale playback and polling responses

**Summary:** Keep selection and library state consistent under slow responses, route unmount, and failed polling.

**Changed**

- Stream playback composable — invalidate previous selection requests; ignore late URL/list callbacks after disposal; share in-flight list requests; schedule non-overlapping polls with capped error backoff
- Library reconciliation — clear removed selections and non-ready playback, and load a selected processing video when it becomes ready
- Deferred-response/fake-timer tests — stale successes/errors/loading, status changes, unmount, slow fetches, and backoff/recovery
- Project/backlog — document the behavior, remaining access-expiry integration, and ready-for-review PR workflow

**Validation:** Full frontend Vitest suite and production type-checked build pass. No API/schema/dependency changes; lockfiles, wrappers, and generated output are excluded. This slice does not verify actual media access or native Lambda delivery.

---

## 2026-10-11 — SA-006 slice 1: explicit queue delivery and dead-letter provisioning

**Summary:** Configure bounded retries for all three message queues, including existing resources, and guard the native deployment against unsafe delivery settings.

**Changed**

- Shell and PowerShell AWS bootstrap — source visibility/retention, matching DLQs with longer retention and restricted redrive permission, and redrive policies reapplied on every run without queue replacement/purge
- Native Lambda deployment script — require adequate source visibility and the expected DLQ, retain timeout 300 seconds, set batch size one/window zero on existing and new mappings, and stop on AWS mutation failures
- `docker/infra/aws/tests/test_queue_bootstrap.py` — stateful CLI-double execution checks repeated updates preserve payloads and API failure stops provisioning
- `docker/infra/aws/verify-queue-delivery.py` — isolated local Floci acceptance probe for repeat provisioning, payload preservation, five receives, DLQ arrival, and replay
- Project/backlog and queue-delivery guide — record actual PR #6 results and outstanding local/native gates

**Validation:** Two CLI-double shell tests, shell syntax, Python compilation/help, and diff checks pass. The user ran the isolated probe against actual Floci and confirmed all three PASS results: repeat provisioning, payload preservation/exhaustion, and DLQ replay/acknowledgement. PowerShell parity and native deployment/retry checks remain unverified. PR #6's user logs confirm 76 backend and 19 Lambda tests pass without skips on Java 27/Linux with Floci 2.1.0, including the original overwrite assertion and real FFmpeg invalid-media test. Fault-injection ERROR logs are expected test outcomes. Frontend tests/build were already green. Browser retry and Compose/native acceptance remain unconfirmed.

---

## 2026-10-06 — PR #6 backend test corrections and aligned Floci image

**Summary:** Fix Mockito re-stubbing and align the emulator release used by tests and Compose while preserving the conditional-overwrite acceptance gate.

**Changed**

- `S3ServiceUnitTest` — use `doThrow` when replacing an existing throwing stub; assert the same 403/503 exception propagates without a bucket check
- Backend and Lambda Testcontainers configurations — explicitly select `floci/floci:2.1.0` with the compatibility declaration required by Java module 2.0.0
- `docker/infra/aws/docker-compose.yaml` — use the same release instead of floating `latest`
- `docs/PROJECT.md` — record the image choice and actual test results

**Validation:** User confirmed frontend tests pass. Initial backend run compiled and ran 76 tests: 74 passed, one conditional-write assertion failed (412 expected, 200 received on `hectorvent/floci:latest`), and one unit test errored during re-stubbing. The overwrite assertion and original-byte check remain unchanged. Diff and Java syntax checks pass for the correction; backend/Lambda runtime tests and Compose/native acceptance are pending because this workspace lacks Java 25/Docker and cached Maven dependencies.

---

## 2026-10-05 — SA-005 slice 1: retry a known upload without duplicate registration

**Summary:** Preserve registration before PUT and reconcile/renew that upload on retry instead of registering the same hash again.

**Changed**

- Backend video controller/service/repository and `UploadRetryRecord` — new retry endpoint, row lock during source reconciliation/signing, and activity refresh for pending transfers
- `S3Service` — check source existence without treating missing bucket/access errors as a missing object
- `UploadNotRetryableException` and ProblemDetail handler — explicit conflict for failed/expired terminal rows
- Frontend upload API/composable — retain upload identity through transfer/renewal errors, skip already-received transfers, and avoid repeated hashing/registration after a PUT failure
- Browser transfer utility — add `If-None-Match: *` to guard concurrent source overwrites
- Backend and frontend regression tests — identity retention, renewal, received-source reconciliation, failed/unknown sessions, storage errors, cleanup activity, and Floci conditional-write acceptance
- `docs/PROJECT.md`, `docs/BACKLOG.md` — document the API and remaining SA-005 slices; record PR #5 merge and user-confirmed passing Lambda tests

**Validation:** All 84 frontend tests and the production build pass. Java syntax parsing and diff checks pass; backend compilation/tests and Floci conditional-write behavior require local verification (Java 25/Docker). No backend runtime success is claimed. Lost-registration idempotency and expired-session recovery remain pending, so SA-005 is not complete.

---

## 2026-10-05 — SA-004: propagate lost processing-result publication

**Summary:** Fail the Lambda invocation on publication and operational failures so redelivery can recover without reporting successful processing as a terminal failure.

**Changed**

- `transcode-lambda/.../Transcoder.java` — separate nonzero FFmpeg outcomes from retryable storage/start/publication failures; propagate result sends; clear cached queue URL on send failure; restore interrupts
- `transcode-lambda/.../TranscoderUnitTest.java` — deterministic fault injection and redelivery checks for success/failure sends, queue lookup, S3 input/output, missing worker/artifacts, interrupts, and temporary-directory cleanup
- `transcode-lambda/.../TranscoderFailureIntegrationTest.java` — require configured FFmpeg for actual invalid-media verification; missing executable now fails the invocation
- `docs/PROJECT.md`, `docs/BACKLOG.md` — document redelivery semantics and pending gates; record PR #4 merge and user-confirmed passing Linux backend tests after the import correction

**Validation:** Java compiler parsing of all three changed Java files and `git diff --check` pass. Parsing checks syntax only. Maven compilation/tests are pending: dependencies are unavailable in this environment, which also lacks Java 25/Docker. Native/deployed redelivery requires local acceptance verification. Nonzero FFmpeg exits retain the current terminal policy; finer classification remains SA-010.

---

## 2026-10-05 — SA-003: settle completion independently of upload event order

**Summary:** Accept terminal processing results atomically from either active state so early completion is retained and late/duplicate events cannot regress terminal status.

**Changed**

- `backend/.../video/VideoRepository.java` — add conditional `completeTranscode` across awaiting-upload and processing states
- `backend/.../video/VideoService.java` — validate completion fields; distinguish unknown, duplicate, and conflicting results; propagate database failures for redelivery
- Backend repository, direct listener, service unit, and SQS listener tests — cover both delivery orders, terminal preservation, invalid/unknown messages, and database failure
- `docs/PROJECT.md`, `docs/BACKLOG.md` — document single-attempt semantics and pending verification; record user-confirmed local checks and merged PRs #1–#3

**Validation:** `git diff --check` passes. The focused Maven test command stopped at parent-POM resolution because Maven Central DNS is unavailable. Backend compilation/tests remain pending: this environment also has Java 17 and no Docker; the project requires Java 25 and Docker-backed jOOQ generation/Testcontainers. No backend test success is claimed.

---

## 2026-10-05 — SA-002: restore frontend type-checked build

**Summary:** Use the Vue Test Utils existence-query API for the player placeholder assertion so the test suite also passes production type checking.

**Changed**

- `frontend/src/features/stream/ui/StreamPanel.test.ts` — replace `get(...).exists()` with `find(...).exists()`; retain the same existence assertion and application behavior
- `docs/PROJECT.md`, `docs/BACKLOG.md` — record build recovery and validation status

**Validation:** `npm run test:run` passes 78 tests across 18 files; `npm run build` passes type checking and bundling. The existing large player-chunk warning is non-blocking.

---

## 2026-10-05 — SA-001: fix Compose bootstrap endpoint

**Summary:** Point the AWS initialization container at the Floci service so it does not try to reach an AWS endpoint inside its own container.

**Changed**

- `docker/infra/aws/docker-compose.yaml` — use `http://floci:4566` for `aws-init`
- `docs/PROJECT.md` — document host versus container endpoint selection
- `docs/BACKLOG.md` — record implementation and outstanding Docker verification

**Validation:** Compose YAML parsed and service/network configuration checked; shell syntax checked. Runtime initialization remains to be verified with Docker.

---

## 2026-10-05 — Prioritized local-demo implementation backlog

**Summary:** Scoped the remaining reliability, lifecycle, presentation, and release work against the reviewed code, preserving the existing architecture and coding conventions.

**Changed**

- `docs/BACKLOG.md` — 35 prioritized tasks with acceptance criteria, dependencies, delivery rules, and deferred extensions
- `docs/PROJECT.md` — link to planning backlog, correct stale scaffold/transcode descriptions, and distinguish user-reported local operation from automated acceptance evidence

---

All notable changes to **stream-app** are documented here, starting **2026-06-26**.

Format: newest entries first. Each entry lists what changed and why.

---

## 2026-06-28 — README license section

**Summary:** Updated README to reflect the added MIT `LICENSE` file instead of the previous placeholder note.

**Changed**

- `README.md` — License section links to `LICENSE` with copyright holder
- `docs/PROJECT.md` — moved `LICENSE` from not-done to done

---

## 2026-06-28 — Blog draft: video upload and HLS streaming pipeline

**Summary:** Added an iamkavindu.dev-format technical blog post covering stream-app architecture, presigned uploads, SNS/SQS transcode fan-out, fMP4 HLS, and playback trade-offs.

**Changed**

- `docs/building-a-video-upload-and-hls-streaming-pipeline.md` — full blog draft (frontmatter + GFM body, Mermaid diagrams)

---

## 2026-06-28 — README refresh

**Summary:** Updated root README with current features (retry, polling, validation), test stack, Windows AWS init, and test commands.

**Changed**

- `README.md` — enriched details, tech stack, diagrams, local dev, and testing quick start

---

## 2026-06-28 — Expanded Testcontainers integration tests

**Summary:** Implemented the planned test expansion: shared messaging fixtures, full Floci SNS/SQS/S3 bootstrap in tests, async SQS listener integration tests, transcode-lambda Floci integration, and optional slow/pipeline tiers.

**Changed**

- `test-fixtures/` — shared SNS/SQS/S3 JSON templates mounted by both Java modules
- `backend/` — `TestAwsBootstrap`, `MessagingFixtures`, `@MessagingIntegrationTest`, `PresignedUrlTestSupport`, SQS listener + upload flow + pipeline tests, `BackendExceptionHandlerTest`, contract parser tests; Surefire excludes `slow`/`pipeline` by default
- `transcode-lambda/` — `LambdaTestcontainersConfiguration`, `TranscoderFailureIntegrationTest`, `@Tag("slow")` FFmpeg tests, contract parser tests; AWS SDK BOM
- `docs/PROJECT.md` — test run instructions and coverage

---

## 2026-06-27 — Backend integration tests with Floci Testcontainers

**Summary:** Added Floci Testcontainers (`spring-boot-testcontainers-floci` 2.0) alongside PostgreSQL 18 for backend unit and integration tests covering repository, S3, REST API, SQS listener logic, and stale-upload cleanup.

**Changed**

- `backend/pom.xml` — `testcontainers-floci`, `spring-boot-testcontainers-floci`, Awaitility
- `backend/src/test/.../TestcontainersConfiguration.java` — PostgreSQL 18 + `FlociContainer` with `@ServiceConnection`
- `backend/src/test/.../support/` — `@IntegrationTest`, `FlociMessagingInitializer` (S3 buckets + SQS queues), `TestData`
- `backend/src/test/.../video/` — `VideoServiceUnitTest`, `VideoRepositoryIntegrationTest`, `VideoApiIntegrationTest`, `VideoServiceListenerIntegrationTest`
- `backend/src/test/.../aws/S3ServiceIntegrationTest.java` — S3 presign + Floci SDK integration
- `backend/src/test/resources/application.properties` — test profile (SQS auto-startup off, cleanup job off)
- `docs/PROJECT.md` — backend test stack and coverage

---

## 2026-06-27 — Trim .gitignore to project essentials

**Summary:** Removed Spring Initializr boilerplate, duplicate IDE/OS entries, and unused tool patterns; each file now lists only artifacts this repo actually produces.

**Changed**

- `.gitignore` — root-only: OS, IDE, Cursor rules, `.env`, Floci data
- `backend/.gitignore` — Maven output, local Spring properties, GraalVM crash dumps
- `frontend/.gitignore` — npm/Vite/Vitest output and env files only
- `transcode-lambda/.gitignore` — Maven + FFmpeg/native Lambda build artifacts

---

## 2026-06-27 — Frontend test coverage expansion

**Summary:** Extended Vitest coverage for new stream polling/retry UX and remaining UI components; 78 tests across 18 files, all passing.

**Changed**

- `frontend/src/features/stream/composables/useStreamPlayback.test.ts` — manifest load, list errors, 404 formatting, unmount stops polling
- `frontend/src/features/stream/types.test.ts` — `isInProgress`, `isTerminal`, `isFailed`, badge classes
- `frontend/src/features/stream/ui/StreamPanel.test.ts`, `VideoPlaylistItem.test.ts`, `HlsPlayer.test.ts` — stream tab UI and hls.js player
- `frontend/src/features/upload/ui/FileDropZone.test.ts`, `UploadProgress.test.ts` — drop zone and progress bar
- `frontend/src/features/upload/types.test.ts`, `UploadQueueItemCard.test.ts`, `UploadPanel.test.ts`, `shared/ui/AppTabNav.test.ts` — phase helpers, complete/retry UI, tab active state
- `docs/PROJECT.md` — updated frontend test count

---

**Summary:** Layer ZIP used `opt/bin/ffmpeg`, which Floci mounted as `/opt/opt/bin/ffmpeg`; repackaged as `bin/ffmpeg` and deploy script now always attaches the latest layer on update.

**Changed**

- `transcode-lambda/scripts/00-pre-setup-ffmpeg-layer.ps1` — layer archive layout `bin/ffmpeg` (not `opt/bin/ffmpeg`)
- `transcode-lambda/scripts/04-deploy-native-lambda.ps1` — resolve latest layer version; set `--layers` on `update-function-configuration`
- `docs/PROJECT.md` — FFmpeg layer layout note

---

## 2026-06-27 — Native Lambda: parse raw SQS JSON instead of SQSEvent

**Summary:** Switched the transcode function to `Consumer<byte[]>` and parse SQS envelope JSON with Spring Jackson so native images actually receive message bodies; AWS `SQSEvent` deserialization was succeeding silently with empty records.

**Changed**

- `transcode-lambda/.../Transcoder.java` — `Consumer<byte[]>` instead of `Consumer<SQSEvent>`
- `transcode-lambda/.../aws/S3UploadEventParser.java` — `parseSqsEnvelopeBodies(byte[])`
- `transcode-lambda/.../aothints/S3ReflectionHints.java` — drop `SQSEvent` / `LambdaEventSerializers` hints
- `transcode-lambda/.../aws/S3UploadEventParserTest.java` — envelope body extraction tests
- `docs/PROJECT.md` — native Lambda event parsing note

---

## 2026-06-27 — Fix native Lambda SQS deserialization (Joda Time tz data)

**Summary:** Register AWS Lambda’s repackaged Joda Time timezone resources in GraalVM AOT hints so `SQSEvent` deserialization no longer fails with `ZoneInfoMap` not found.

**Changed**

- `transcode-lambda/.../aothints/S3ReflectionHints.java` — `registerPattern` for `com/amazonaws/lambda/thirdparty/org/joda/time/tz/data/**`
- `docs/PROJECT.md` — native Lambda AOT note for Joda tz resources

---

## 2026-06-27 — PowerShell AWS init script for Windows hosts

**Summary:** Added a PowerShell port of the Floci SNS/SQS/S3 notification bootstrap so developers on Windows can run init without WSL or the Compose `aws-init` container.

**Changed**

- `docker/infra/aws/init-aws-resources.ps1` — idempotent Floci bootstrap (mirrors `init-aws-resources.sh`)
- `docs/PROJECT.md` — Windows host init instructions and key-files entry

---

## 2026-06-27 — UX polish, validation, orphan cleanup, root README

**Summary:** Added stream-list auto-polling, upload retry, clearer failed transcode/playback UX, Bean Validation on video creation, scheduled cleanup of stale pending uploads, and a concise root README.

**Changed**

- `README.md` — project overview, tech stack, Mermaid diagrams, local dev quick start, license note
- `frontend/src/features/stream/composables/useStreamPlayback.ts` — poll `GET /api/v1/videos` every 4s while videos are in progress; status-specific player messages; API error formatting
- `frontend/src/features/stream/types.ts` — `isInProgress`, `isTerminal`, `isFailed`, `playerStatusMessage`
- `frontend/src/features/stream/ui/StreamPanel.vue`, `VideoPlaylistItem.vue`, `HlsPlayer.vue` — failed-state playlist UX; separate API vs playback error panels
- `frontend/src/features/stream/utils/hlsErrors.ts` — manifest/segment/media error messages for hls.js
- `frontend/src/features/upload/composables/useUploadQueue.ts`, `UploadQueueItemCard.vue`, `UploadPanel.vue` — retry failed uploads from hashing; clearer error display
- `backend/.../video/model/SignedUrlCreateRequest.java` — `@Valid` constraints (`fileName`, `sha256Hex`)
- `backend/.../exception/BackendExceptionHandler.java` — `400 validation-failed` ProblemDetail
- `backend/.../video/StaleUploadCleanupJob.java`, `VideoRepository.java`, `VideoService.java` — mark stale `AWAITING_UPLOAD` rows as `FAILED`
- `backend/src/main/resources/application-dev.properties` — cleanup TTL/interval config
- `backend/BackendApplication.java` — `@EnableScheduling`
- Tests: `VideoControllerValidationTest`, `StaleUploadCleanupTest`, stream/upload Vitest updates
- `docs/PROJECT.md` — API validation, cleanup config, stream/upload UX, implementation status

---

## 2026-06-27 — Transcode Lambda aligned with stream-app pipeline

**Summary:** Adapted `transcode-lambda` from the videostreamer example to stream-app contracts: SQS input, `VideoStatusUpdateRecord` output, correct S3 keys/buckets, copied shared types, and WSL native build scripts.

**Changed**

- `transcode-lambda/.../Transcoder.java` — `Consumer<SQSEvent>`, `S3UploadEventParser`, `HlsTranscodeCommand`, publish `PLAY_READY`/`FAILED` to `video-transcode-complete-backend`
- `transcode-lambda/.../aws/` — copied `S3ObjectKeys`, `S3UploadEventParser`, `AwsMessagingResources`
- `transcode-lambda/.../transcode/HlsTranscodeCommand.java` — copied from backend
- `transcode-lambda/.../model/` — `VideoStatus`, `VideoStatusUpdateRecord`
- `transcode-lambda/src/main/resources/application*.properties` — `streamapp-uploads`, `streamapp-streams`, queue names
- `transcode-lambda/pom.xml` — `spring-boot-starter`, test deps, `native` profile
- `transcode-lambda/scripts/` — FFmpeg layer, WSL native build, package, Floci deploy (from `reference-only/`)
- `transcode-lambda/src/test/` — parser, FFmpeg command, context-load tests
- `docs/PROJECT.md` — transcode-lambda layout, implementation status

---

**Summary:** Replaced single upload queue with SNS fan-out to `video-processing-backend` and `video-processing-lambda`; backend now transitions video status on upload complete and transcode complete via dual `@SqsListener` handlers.

**Changed**

- `docker/infra/aws/init-aws-resources.sh` — SNS `video-upload-events`, queues `video-processing-backend`, `video-processing-lambda`, `video-transcode-complete-backend`, S3→SNS notification
- `docker/infra/aws/docker-compose.yaml` — updated `aws-init` environment variables
- `backend/.../aws/AwsMessagingResources.java` — shared topic and queue names
- `backend/.../aws/S3UploadEventParser.java` — parse SNS-wrapped S3 `ObjectCreated` events
- `backend/.../aws/S3UploadEventParserTest.java` — parser unit tests
- `backend/.../video/model/VideoStatusUpdateRecord.java` — Lambda transcode-complete DTO
- `backend/.../video/VideoRepository.java` — conditional `updateStatus`
- `backend/.../video/VideoService.java` — `onUploadComplete`, `onTranscodeComplete` listeners
- `backend/.../aws/ResourceInitialize.java` — `@Profile("dev")` for local bootstrap only
- `backend/src/test/resources/application.properties` — disable SQS listener auto-startup in tests
- Removed `backend/.../aws/ResourceConfig.java` (empty stub)
- `docs/PROJECT.md` — updated architecture diagram, messaging table, Lambda contract

---

## 2026-06-27 — Stream tab with HLS.js player

**Summary:** Implemented the Stream tab: lists videos from the API, fetches presigned HLS manifest URLs for `PLAY_READY` items, and plays them with hls.js.

**Changed**

- `frontend/package.json` — added `hls.js`
- `frontend/src/features/stream/` — `streamApi`, `useStreamPlayback`, `StreamPanel`, `HlsPlayer`, `VideoPlaylistItem`, types
- `frontend/src/pages/StreamPage.vue` — renders `StreamPanel`
- `frontend/src/features/stream/api/streamApi.test.ts`, `types.test.ts` — API and status helper tests
- `docs/PROJECT.md` — stream tab flow, `PLAY_READY` status naming, implementation status

---

## 2026-06-27 — FFmpeg single-file HLS transcode contract

**Summary:** Aligned stream-bucket layout and docs with the planned FFmpeg command (`-hls_flags single_file`, `-hls_segment_type fmp4`): two objects per video (`index.m3u8` + `media.mp4`); added shared `HlsTranscodeCommand` for ProcessBuilder.

**Changed**

- `backend/.../aws/S3ObjectKeys.java` — `MEDIA_FILE`, `streamMediaKey`
- `backend/.../transcode/HlsTranscodeCommand.java` — FFmpeg argument builder
- `backend/.../transcode/HlsTranscodeCommandTest.java`, `S3ObjectKeysTest.java` — tests
- `docs/PROJECT.md` — Lambda FFmpeg flags table, two-file stream layout

---

## 2026-06-27 — HLS-aligned S3 key layout and stream architecture

**Summary:** Aligned upload and stream S3 keys with the planned Lambda transcode pipeline (`{uploadId}/{fileName}` uploads, `{uploadId}/index.m3u8` HLS manifest); signed GET is `READY`-only; documented end-to-end upload → transcode → HLS playback flow.

**Changed**

- `backend/.../aws/S3ObjectKeys.java` — shared key conventions and fileName sanitization
- `backend/.../aws/S3Service.java` — presigned PUT/GET use prefixed keys; playlist GET targets `index.m3u8`
- `backend/.../video/VideoService.java` — pass fileName to upload signing; `READY` gate for stream URL
- `backend/.../video/model/SignedGetUrlRecord.java` — added `objectKey`
- `backend/.../exception/VideoNotReadyException.java` — `409` when status is not `READY`
- `backend/.../aws/ResourceInitialize.java` — stream-bucket CORS (`GET`, `HEAD`)
- `backend/.../aws/S3ObjectKeysTest.java` — key layout tests
- `docs/PROJECT.md` — full three-part architecture, Lambda contract, HLS segment auth notes

---

## 2026-06-27 — List videos and signed stream URL endpoints

**Summary:** Added `GET /api/v1/videos` to list all videos and `GET /api/v1/videos/{uploadId}/signed-url` for presigned S3 GET URLs from `streamapp-streams`; fixed broken `VideoController` compile stub.

**Changed**

- `backend/.../video/VideoController.java` — list and signed GET URL endpoints
- `backend/.../video/VideoService.java` — `listVideos`, `createSignedGetUrl`
- `backend/.../video/VideoRepository.java` — `findAll`, `findByUploadId`
- `backend/.../video/model/VideoRecord.java` — list response DTO
- `backend/.../video/model/SignedGetUrlRecord.java` — signed GET response DTO
- `backend/.../exception/VideoNotFoundException.java` — 404 for unknown `uploadId`
- `backend/.../exception/BackendExceptionHandler.java` — `video-not-found` problem type
- `stream_app-endpoints.http` — sample GET requests
- `docs/PROJECT.md` — API table and response shapes

---

## 2026-06-27 — Documentation sync with current codebase

**Summary:** Refreshed `PROJECT.md` to match the repo as it exists today: expanded layout and frontend architecture, SQS stack details, dual-bucket S3 layout, test counts, placeholders, and known setup issues.

**Changed**

- `docs/PROJECT.md` — repository layout (`video/model/`, `exception/`, frontend `shared/`); tech stack (SQS, Node engines); upload phases/step chips; S3 upload vs stream buckets; `@SqsListener` behavior; implementation status and key files; known setup notes (incomplete `VideoController` stub, dev startup order)

---

**Summary:** Moved SQS queue and S3 upload event notification setup out of Java into an idempotent shell script and Compose `aws-init` service; backend `ResourceInitialize` only creates buckets and CORS.

**Changed**

- `docker/infra/aws/init-aws-resources.sh` — create `video-processing-queue`, queue policy, S3→SQS notification on `streamapp-uploads`
- `docker/infra/aws/docker-compose.yaml` — `aws-init` one-shot service (`amazon/aws-cli`)
- `backend/.../aws/ResourceInitialize.java` — removed SQS queue bootstrap; S3-only startup
- `docs/PROJECT.md` — Floci init flow, architecture diagram, key files

---

## 2026-06-27 — Upload error alerts and progress revamp

**Summary:** Frontend parses backend RFC 7807 problem responses (e.g. duplicate upload `409`) and shows per-file error alerts; upload queue cards now use step chips and a unified progress bar across all pipeline phases.

**Changed**

- `frontend/src/shared/api/apiError.ts` — `ApiError`, `readApiError`, `getErrorMessage`
- `frontend/src/features/upload/api/videoApi.ts` — throws `ApiError` with problem `detail`
- `frontend/src/features/upload/composables/useUploadQueue.ts` — `failed` phase, `error`, `failedAtPhase`
- `frontend/src/features/upload/types.ts` — `computeOverallProgress`, `stepStatus`, step labels
- `frontend/src/features/upload/ui/UploadQueueItemCard.vue` — step chips, progress bar, error alert
- `frontend/src/features/upload/ui/UploadProgress.vue` — success/error bar variants
- `frontend/src/**/*.test.ts` — extended coverage (29 tests)
- `docs/PROJECT.md` — API error shape, status updates

---

## 2026-06-27 — Multi-file upload queue UI

**Summary:** Upload tab now keeps the drop zone visible and shows a right-hand vertical queue so users can add MP4 files one after another while earlier uploads are still running.

**Changed**

- `frontend/src/features/upload/composables/useUploadQueue.ts` — replaces single-file composable; independent per-file upload pipelines
- `frontend/src/features/upload/ui/UploadPanel.vue` — two-pane layout (drop zone + queue stack)
- `frontend/src/features/upload/ui/UploadQueueItemCard.vue` — per-file status, progress, and remove when complete
- `frontend/src/features/upload/types.ts` — `UploadQueueItem`, `uploadPhaseLabel`
- `frontend/src/features/upload/**/*.test.ts` — updated/extended tests (18 total)
- `docs/PROJECT.md` — upload tab flow description

---

## 2026-06-27 — Frontend upload tab (Vue 3 + Tailwind)

**Summary:** Added a Vue 3 SPA with Upload | Stream tab navigation and a full client-side upload flow: MP4 selection, SHA-256 hashing, signed-url creation, and S3 PUT with progress tracking.

**Changed**

- `frontend/src/features/upload/` — upload API client, `useVideoUpload` composable, drop zone and progress UI
- `frontend/src/pages/`, `frontend/src/shared/`, `frontend/src/router/` — app shell, tab nav, routed pages
- `frontend/vite.config.ts` — Vitest config; existing `/api` proxy retained
- `frontend/package.json` — Vitest, `@vue/test-utils`, happy-dom; `test` / `test:run` scripts
- `frontend/.env.example` — `VITE_API_BASE_URL`
- `frontend/src/**/*.test.ts` — unit and component tests (15 tests)
- `docs/PROJECT.md` — frontend stack, architecture, local dev, status

---

## 2026-06-27 — Signed video upload flow and local S3

**Summary:** Implemented end-to-end presigned upload: API creates a pending DB row, returns an S3 PUT URL, and clients upload directly to object storage. Added Floci for local S3 emulation.

**Changed**

- `backend/src/main/java/.../video/VideoController.java` — `POST /api/v1/videos` returns `201 Created`
- `backend/src/main/java/.../video/VideoService.java` — orchestrates DB insert + presigned URL
- `backend/src/main/java/.../video/VideoRepository.java` — jOOQ insert with `AWAITING_UPLOAD`
- `backend/src/main/java/.../video/VideoStatus.java` — upload lifecycle enum
- `backend/src/main/java/.../video/SignedUrlCreatedRecord.java` — added `uploadId`
- `backend/src/main/java/.../aws/S3Service.java` — bucket auto-create, `createSignedPutURL` (15 min)
- `backend/pom.xml` — `spring-cloud-aws-starter-s3` dependency
- `backend/src/main/resources/application-dev.properties` — Flyway/jOOQ + Floci S3 endpoint config
- `docker/infra/aws/docker-compose.yaml` — Floci + Floci UI
- `stream_app-endpoints.http` — POST signed URL + PUT `demo.mp4`
- `demo.mp4` — sample upload payload
- `docs/PROJECT.md` — architecture, API, local S3 setup, implementation status

---

## 2026-06-26 — Fix jOOQ codegen: use PostgreSQL via Testcontainers

**Summary:** Replaced `DDLDatabase` (H2 DDL simulation) with Testcontainers-backed codegen so Flyway migrations run on real PostgreSQL and `BYTEA` unique indexes generate correctly.

**Changed**

- `backend/pom.xml` — `testcontainers-jooq-codegen-maven-plugin` replaces `jooq-codegen-maven` + `DDLDatabase`; Flyway `streamapp` schema; excludes `flyway_schema_history`
- `docs/PROJECT.md` — jOOQ codegen setup, Docker requirement, and `DDLDatabase` vs Testcontainers explanation

---

## 2026-06-26 — Project documentation baseline

**Summary:** Established living project documentation and a mandatory Cursor rule so future sessions use docs as base context.

**Added**

- `docs/PROJECT.md` — canonical project context (stack, architecture, API, status, dev setup)
- `docs/CHANGELOG.md` — this file; chronological change log from this date forward
- `.cursor/rules/project-documentation.mdc` — always-on rule: read and update docs on every meaningful change

**Context captured**

- Backend-only Spring Boot 4.1 / Java 25 video upload scaffold
- PostgreSQL + Flyway + jOOQ; Docker Compose for local DB
- `POST /api/v1/videos` stub for presigned upload URLs
- `videos` table with SHA-256 deduplication
