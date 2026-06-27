package dev.iamkavindu.streamapp.backend.video.model;

import org.jspecify.annotations.NonNull;

import java.util.UUID;

public record SignedGetUrlRecord(
        @NonNull UUID uploadId,
        @NonNull String objectKey,
        @NonNull String signedUrl
) {
}
