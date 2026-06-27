package dev.iamkavindu.streamapp.backend.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Loads shared JSON templates from the repo-root {@code test-fixtures/} directory
 * (mounted as a Maven test resource).
 */
public final class MessagingFixtures {

    public static final UUID SAMPLE_UPLOAD_ID =
            UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    private MessagingFixtures() {}

    public static String s3ObjectCreatedEvent(UUID uploadId, String fileName) {
        return loadTemplate("messaging/s3-object-created.json")
                .replace("{{uploadId}}", uploadId.toString())
                .replace("{{fileName}}", fileName);
    }

    public static String snsNotificationEnvelope(String s3EventJson) {
        return loadTemplate("messaging/sns-notification.json")
                .replace("{{s3EventJson}}", jsonStringLiteral(s3EventJson));
    }

    public static String snsNotificationEnvelope(UUID uploadId, String fileName) {
        return snsNotificationEnvelope(s3ObjectCreatedEvent(uploadId, fileName));
    }

    public static String sqsLambdaEnvelope(String snsEnvelopeJson) {
        return loadTemplate("messaging/sqs-lambda-envelope.json")
                .replace("{{snsEnvelopeJson}}", jsonStringLiteral(snsEnvelopeJson));
    }

    public static String sqsLambdaEnvelope(UUID uploadId, String fileName) {
        return sqsLambdaEnvelope(snsNotificationEnvelope(uploadId, fileName));
    }

    public static String transcodeCompleteMessage(UUID uploadId, String status) {
        return loadTemplate("messaging/transcode-complete.json")
                .replace("{{uploadId}}", uploadId.toString())
                .replace("{{status}}", status);
    }

    public static String loadTemplate(String classpathRelativePath) {
        var resource = MessagingFixtures.class.getClassLoader().getResource(classpathRelativePath);
        if (resource == null) {
            throw new IllegalStateException("Missing test fixture: " + classpathRelativePath);
        }
        try (var stream = resource.openStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read fixture: " + classpathRelativePath, e);
        }
    }

    private static String jsonStringLiteral(String json) {
        var escaped = json.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
        return "\"" + escaped + "\"";
    }
}
