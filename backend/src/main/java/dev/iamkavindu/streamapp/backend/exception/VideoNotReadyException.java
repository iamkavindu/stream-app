package dev.iamkavindu.streamapp.backend.exception;

import dev.iamkavindu.streamapp.backend.video.model.VideoStatus;

import java.util.UUID;

public class VideoNotReadyException extends RuntimeException {
    public VideoNotReadyException(UUID uploadId, VideoStatus status) {
        super("Video is not ready for streaming: " + uploadId + " (status: " + status + ")");
    }
}
