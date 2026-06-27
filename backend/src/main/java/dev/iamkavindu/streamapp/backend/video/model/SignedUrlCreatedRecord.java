package dev.iamkavindu.streamapp.backend.video.model;

import org.jspecify.annotations.NonNull;

import java.util.UUID;

public record SignedUrlCreatedRecord(
        @NonNull UUID uploadId,
        @NonNull String signedUrl,
        @NonNull String fileName
) {
}
