# Shared test fixtures

JSON templates and sample media used by `backend` and `transcode-lambda` integration tests.

Placeholders: `{{uploadId}}`, `{{fileName}}`, `{{status}}`, `{{s3EventJson}}`, `{{snsEnvelopeJson}}`.

Both Maven modules mount this directory as a test resource root (`../test-fixtures`).
