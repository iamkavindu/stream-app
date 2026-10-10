package dev.iamkavindu.streamapp.backend.video.model;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

/** A transfer decision for an existing upload; signedUrl is absent when the source was received. */
public record UploadRetryRecord(
        UUID uploadId,
        String fileName,
        boolean sourceReceived,
        @Nullable String signedUrl
) {}
