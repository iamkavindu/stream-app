package dev.iamkavindu.streamapp.lambda.aws;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Parses S3 {@code ObjectCreated} events delivered via SNS → SQS.
 */
@Component
public class S3UploadEventParser {

    private final ObjectMapper objectMapper;

    public S3UploadEventParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Extracts SNS-wrapped S3 notification bodies from a raw SQS Lambda event JSON payload.
     * Uses Spring's {@link ObjectMapper} instead of AWS {@code LambdaEventSerializers} so GraalVM
     * native images deserialize reliably.
     */
    public List<String> parseSqsEnvelopeBodies(byte[] sqsEventJson) {
        try {
            var root = objectMapper.readTree(sqsEventJson);
            var records = root.path("Records");
            if (!records.isArray() || records.isEmpty()) {
                return List.of();
            }
            var bodies = new ArrayList<String>(records.size());
            for (var record : records) {
                var bodyNode = record.path("body");
                if (bodyNode.isMissingNode() || bodyNode.isNull()) {
                    continue;
                }
                var body = bodyNode.asText();
                if (!body.isBlank()) {
                    bodies.add(body);
                }
            }
            return bodies;
        } catch (Exception e) {
            return List.of();
        }
    }

    public Optional<S3UploadEvent> parseSqsMessage(String sqsBody) {
        try {
            var root = objectMapper.readTree(sqsBody);
            var s3EventJson = unwrapSnsEnvelope(root);
            var records = s3EventJson.path("Records");
            if (!records.isArray() || records.isEmpty()) {
                return Optional.empty();
            }

            for (var record : records) {
                if (!"aws:s3".equals(record.path("eventSource").asText())) {
                    continue;
                }
                var bucketName = record.path("s3").path("bucket").path("name").asText(null);
                var rawKey = record.path("s3").path("object").path("key").asText(null);
                if (bucketName == null || rawKey == null || rawKey.isBlank()) {
                    continue;
                }
                var objectKey = URLDecoder.decode(rawKey, StandardCharsets.UTF_8);
                return parseUploadId(objectKey).map(uploadId -> new S3UploadEvent(uploadId, bucketName, objectKey));
            }
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private JsonNode unwrapSnsEnvelope(JsonNode root) throws Exception {
        if (root.has("Message") && root.path("Type").asText("").equals("Notification")) {
            var message = root.path("Message").asText();
            return objectMapper.readTree(message);
        }
        return root;
    }

    static Optional<UUID> parseUploadId(String objectKey) {
        var slash = objectKey.indexOf('/');
        if (slash <= 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(objectKey.substring(0, slash)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public record S3UploadEvent(UUID uploadId, String bucketName, String objectKey) {}
}
