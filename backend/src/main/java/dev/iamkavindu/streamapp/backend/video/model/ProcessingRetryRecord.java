package dev.iamkavindu.streamapp.backend.video.model;

import java.util.UUID;

/** Acceptance means durable queued intent, not worker start or completion. */
public record ProcessingRetryRecord(UUID uploadId, UUID attemptId, VideoStatus status) {}
