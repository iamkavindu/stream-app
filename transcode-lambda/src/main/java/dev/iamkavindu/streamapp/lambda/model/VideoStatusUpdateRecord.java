package dev.iamkavindu.streamapp.lambda.model;

import java.util.UUID;

/**
 * Message published by the transcode Lambda to {@code video-transcode-complete-backend}
 * after HLS output is written to {@code streamapp-streams}.
 */
public record VideoStatusUpdateRecord(UUID uploadId, VideoStatus status, UUID attemptId, Integer schemaVersion,
        String failureCode, String failureDetail, String playlistKey) {
    public VideoStatusUpdateRecord(UUID uploadId, VideoStatus status, UUID attemptId, Integer schemaVersion,
            String failureCode, String failureDetail) {
        this(uploadId, status, attemptId, schemaVersion, failureCode, failureDetail, null);
    }

    public VideoStatusUpdateRecord(UUID uploadId, VideoStatus status) {
        this(uploadId, status, null, null, null, null, null);
    }

    public VideoStatusUpdateRecord(UUID uploadId, VideoStatus status, UUID attemptId, Integer schemaVersion) {
        this(uploadId, status, attemptId, schemaVersion, null, null, null);
    }

    public boolean hasValidFailureMetadata() {
        return (status == VideoStatus.FAILED || (failureCode == null && failureDetail == null))
                && (failureCode == null || failureCode.matches("[A-Z][A-Z0-9_]{0,63}"))
                && (failureDetail == null || failureDetail.length() <= 512);
    }
}
