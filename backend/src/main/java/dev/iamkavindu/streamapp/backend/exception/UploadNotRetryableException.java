package dev.iamkavindu.streamapp.backend.exception;

import dev.iamkavindu.streamapp.backend.video.model.VideoStatus;

import java.util.UUID;

public class UploadNotRetryableException extends RuntimeException {
    public UploadNotRetryableException(UUID uploadId, VideoStatus status) {
        super("Upload cannot be retried: " + uploadId + " (status: " + status + ")");
    }
}
