# Shared test fixtures

JSON templates and sample media used by `backend` and `transcode-lambda` integration tests.

Placeholders: `{{uploadId}}`, `{{fileName}}`, `{{status}}`, `{{attemptId}}`, `{{s3EventJson}}`, `{{snsEnvelopeJson}}`.

Both Maven modules mount this directory as a test resource root (`../test-fixtures`).

`messaging/transcode-complete.json` preserves the legacy result. `transcode-complete-v2.json` specifies version 2 with explicit attempt identity; both producer and consumer regressions use it.

`transcode-complete-failed-v2.json` adds the optional bounded FFmpeg failure fields. Success V2 fixtures keep those fields null.
