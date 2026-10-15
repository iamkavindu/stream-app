package dev.iamkavindu.streamapp.lambda.model;

import java.util.UUID;

/** Versioned direct SQS command. The bucket is deployment configuration, never message input. */
public record ProcessingJobRecord(Integer schemaVersion, String jobType, UUID uploadId,
                                  UUID attemptId, String sourceKey) {
    public ProcessingJobRecord {
        if (!Integer.valueOf(1).equals(schemaVersion) || !"TRANSCODE".equals(jobType)
                || uploadId == null || attemptId == null || sourceKey == null) {
            throw new IllegalArgumentException("Invalid processing job identity or version");
        }
        var prefix = uploadId + "/";
        var fileName = sourceKey.startsWith(prefix) ? sourceKey.substring(prefix.length()) : "";
        if (fileName.isEmpty() || fileName.length() > 255 || fileName.equals(".") || fileName.equals("..")
                || !fileName.matches("[a-zA-Z0-9._-]+")) {
            throw new IllegalArgumentException("Processing source must be an existing upload key for this video");
        }
    }
}
