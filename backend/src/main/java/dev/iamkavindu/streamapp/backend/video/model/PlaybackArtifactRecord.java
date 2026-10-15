package dev.iamkavindu.streamapp.backend.video.model;

import java.util.UUID;

public record PlaybackArtifactRecord(UUID attemptId, String playlistKey) {}
