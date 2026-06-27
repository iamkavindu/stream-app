package dev.iamkavindu.streamapp.backend.exception;

import java.util.UUID;

public class VideoNotFoundException extends RuntimeException {
    public VideoNotFoundException(UUID uploadId) {
        super("Video not found: " + uploadId);
    }
}
