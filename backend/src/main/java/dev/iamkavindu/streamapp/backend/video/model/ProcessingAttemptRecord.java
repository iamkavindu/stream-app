package dev.iamkavindu.streamapp.backend.video.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Backend observations, not claimed worker-start timestamps or processing percentages. */
public record ProcessingAttemptRecord(
        UUID attemptId, UUID uploadId, boolean active, String stage,
        OffsetDateTime createdAt, OffsetDateTime uploadObservedAt, OffsetDateTime completedAt,
        String failureCode, String failureDetail, boolean imported) {}
