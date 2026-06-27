package dev.iamkavindu.streamapp.backend.video.model;

import org.jspecify.annotations.NonNull;

import java.time.OffsetDateTime;
import java.util.UUID;

public record VideoRecord(
        @NonNull UUID uploadId,
        @NonNull VideoStatus status,
        @NonNull String fileName,
        @NonNull OffsetDateTime createdAt,
        @NonNull OffsetDateTime updatedAt
) {
}
