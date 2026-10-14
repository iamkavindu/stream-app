package dev.iamkavindu.streamapp.lambda.model;

import java.util.UUID;

/**
 * Message published by the transcode Lambda to {@code video-transcode-complete-backend}
 * after HLS output is written to {@code streamapp-streams}.
 */
public record VideoStatusUpdateRecord(UUID uploadId, VideoStatus status, UUID attemptId, Integer schemaVersion) {
    public VideoStatusUpdateRecord(UUID uploadId, VideoStatus status) {
        this(uploadId, status, null, null);
    }
}
