# Local HLS delivery acceptance (SA-007)

The dev backend reapplies a generated-object read policy and CORS on startup. The stream bucket allows anonymous GET/HEAD of `*/index.m3u8` and `*/media.mp4`. The upload bucket has no read grant from this change. CORS allows the Vite origin `http://localhost:5173`; it is not authorization. The policy is local demo behavior, not private AWS delivery.

The manifest API continues returning a presigned URL. Relative media requests have no signature, and generated objects remain readable after that URL expires under this local policy. Turning `streamapp.local-hls.public-read-enabled` off does not delete a policy already installed. Private delivery and expiry require CloudFront or a proxy and separate acceptance.

## Automated transport checks

GitHub Java CI runs the default backend and Lambda suites. Locally:

```sh
(cd backend && sh mvnw test)
(cd transcode-lambda && sh mvnw test)
```

`LocalHlsDeliveryIntegrationTest` invokes the real initializer against pinned Floci, checks the persisted policy and CORS, loads a signed playlist, resolves its unsigned relative media URI, verifies exact initialization/segment byte ranges (`206`, `Content-Range`), MIME types, HEAD, preflight, and missing-object `404`. A worker regression verifies both output content types. Policy storage plus emulator HTTP success is not proof of AWS IAM enforcement. The HTTP fixture contains synthetic bytes and does not test decoding.

## Browser acceptance required before closing SA-007

1. Switch to the PR branch and start DB/Floci and the backend with `dev`; rerun resource initialization as in PROJECT.md. Start Vite on `http://localhost:5173`.
2. Rebuild/redeploy the changed Lambda using the existing native scripts. Upload a **new** valid MP4; existing output objects retain their previous content types. Wait for `PLAY_READY`.
3. In Chromium/Firefox, select it in Stream. Confirm playback starts, pause/resume works, and seeking forward/backward plays at the chosen position. In Network, the playlist must have `application/vnd.apple.mpegurl`; relative `media.mp4` must have `video/mp4`, no inherited presign query, and range requests must return `206` with matching `Content-Range`. No CORS errors should appear.
4. Repeat playback/seeking in Safari to exercise native HLS. If Safari is unavailable, report it as unverified.
5. For a disposable test video, delete only its generated media object in Floci, reload/select it, and confirm the player reports a media failure rather than playing indefinitely. Restore/retranscode it afterward. Missing-object HTTP behavior is automated; player behavior still needs this check.

Report browser versions, upload ID, playback/seek results, response status/content types, and any console error. These checks cover deployed native execution and real players that hosted JVM tests cannot exercise.

AWS references: [GetObject and byte ranges](https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetObject.html), [CORS configuration](https://docs.aws.amazon.com/AmazonS3/latest/userguide/ManageCorsUsing.html), and [public access policies](https://docs.aws.amazon.com/AmazonS3/latest/userguide/granting-public-access.html). Keep production buckets private; do not apply the demo policy there.
