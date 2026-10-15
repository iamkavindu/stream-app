CREATE TABLE processing_attempts (
    attempt_id UUID PRIMARY KEY,
    upload_id UUID NOT NULL REFERENCES videos(upload_id),
    stage VARCHAR(50) NOT NULL CHECK (stage IN ('AWAITING_UPLOAD', 'UPLOAD_OBSERVED', 'SUCCEEDED', 'FAILED', 'UPLOAD_EXPIRED')),
    created_at TIMESTAMPTZ NOT NULL,
    upload_observed_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    failure_code VARCHAR(64),
    failure_detail VARCHAR(512),
    imported BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (upload_id, attempt_id)
);

-- Preserve known state; do not fabricate upload/worker/completion observations.
INSERT INTO processing_attempts (attempt_id, upload_id, stage, created_at, failure_code, imported)
SELECT active_attempt_id, upload_id,
       CASE status
           WHEN 'TRANSCODING_IN_PROGRESS' THEN 'UPLOAD_OBSERVED'
           WHEN 'PLAY_READY' THEN 'SUCCEEDED'
           WHEN 'FAILED' THEN 'FAILED'
           ELSE 'AWAITING_UPLOAD'
       END,
       created_at,
       CASE WHEN status = 'FAILED' THEN 'LEGACY_FAILURE_UNKNOWN' END,
       TRUE
FROM videos;

CREATE INDEX idx_processing_attempts_video_created ON processing_attempts(upload_id, created_at, attempt_id);
