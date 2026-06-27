CREATE TABLE videos
(
    upload_id   UUID PRIMARY KEY,
    status      VARCHAR(50) NOT NULL,
    file_name   VARCHAR(255),
    sha256_hash BYTEA,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX idx_videos_upload_id ON videos (upload_id);
CREATE UNIQUE INDEX idx_videos_sha256_hash ON videos (sha256_hash);
