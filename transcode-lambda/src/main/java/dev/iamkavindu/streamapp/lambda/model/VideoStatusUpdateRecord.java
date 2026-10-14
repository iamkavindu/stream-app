package dev.iamkavindu.streamapp.lambda.model;

import java.util.UUID;

/**
 * Message published by the transcode Lambda to {@code video-transcode-complete-backend}
 * after HLS output is written to {@code streamapp-streams}.
 */
public record VideoStatusUpdateRecord(UUID uploadId, VideoStatus status, UUID attemptId, Integer schemaVersion,
        String failureCode, String failureDetail) {
    public VideoStatusUpdateRecord(UUID uploadId, VideoStatus status) {
        this(uploadId, status, null, null, null, null);
    }

    public VideoStatusUpdateRecord(UUID uploadId, VideoStatus status, UUID attemptId, Integer schemaVersion) {
        this(uploadId, status, attemptId, schemaVersion, null, null);
    }
}
