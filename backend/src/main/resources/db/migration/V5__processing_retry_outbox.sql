ALTER TABLE processing_attempts DROP CONSTRAINT processing_attempts_stage_check;
ALTER TABLE processing_attempts ADD CONSTRAINT processing_attempts_stage_check
    CHECK (stage IN ('AWAITING_UPLOAD', 'UPLOAD_OBSERVED', 'QUEUED', 'SUCCEEDED', 'FAILED', 'UPLOAD_EXPIRED'));
ALTER TABLE processing_attempts ADD COLUMN queued_at TIMESTAMPTZ;

CREATE TABLE processing_outbox (
    attempt_id UUID PRIMARY KEY,
    upload_id UUID NOT NULL,
    payload TEXT NOT NULL CHECK (length(payload) > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp,
    delivery_attempts INTEGER NOT NULL DEFAULT 0 CHECK (delivery_attempts >= 0),
    claim_id UUID,
    claim_until TIMESTAMPTZ,
    dispatched_at TIMESTAMPTZ,
    last_error VARCHAR(64),
    FOREIGN KEY (upload_id, attempt_id) REFERENCES processing_attempts(upload_id, attempt_id),
    CHECK ((claim_id IS NULL) = (claim_until IS NULL))
);
CREATE INDEX idx_processing_outbox_pending ON processing_outbox(next_attempt_at, created_at, attempt_id)
    WHERE dispatched_at IS NULL;
