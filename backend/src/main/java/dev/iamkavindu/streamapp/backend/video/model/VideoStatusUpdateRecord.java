package dev.iamkavindu.streamapp.backend.video.model;

import java.util.UUID;

/**
 * Message published by the transcode Lambda to {@code video-transcode-complete-backend}
 * after HLS output is written to {@code streamapp-streams}.
 */
public record VideoStatusUpdateRecord(
        UUID uploadId,
        VideoStatus status
) {}
