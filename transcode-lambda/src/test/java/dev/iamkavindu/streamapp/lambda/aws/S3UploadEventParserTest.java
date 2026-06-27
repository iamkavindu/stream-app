package dev.iamkavindu.streamapp.lambda.aws;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class S3UploadEventParserTest {

    private static final UUID UPLOAD_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    private final S3UploadEventParser parser = new S3UploadEventParser(new ObjectMapper());

    @Test
    void parseSqsEnvelopeBodies_extractsBodiesFromSqsEventJson() {
        var sqsEvent = """
                {
                  "Records": [{
                    "messageId": "msg-1",
                    "body": "{\\"Type\\":\\"Notification\\",\\"Message\\":\\"{\\\\\\"Records\\\\\\":[]}\\"}"
                  }]
                }
                """;

        var bodies = parser.parseSqsEnvelopeBodies(sqsEvent.getBytes(StandardCharsets.UTF_8));

        assertThat(bodies).hasSize(1);
        assertThat(bodies.getFirst()).contains("\"Type\":\"Notification\"");
    }

    @Test
    void parseSqsEnvelopeBodies_returnsEmptyForMissingRecords() {
        assertThat(parser.parseSqsEnvelopeBodies("{\"Records\":[]}".getBytes(StandardCharsets.UTF_8)))
                .isEmpty();
        assertThat(parser.parseSqsEnvelopeBodies("not json".getBytes(StandardCharsets.UTF_8)))
                .isEmpty();
    }

    @Test
    void parseSqsMessage_extractsUploadIdFromSnsWrappedS3Event() {
        var s3Event = """
                {
                  "Records": [{
                    "eventSource": "aws:s3",
                    "s3": {
                      "bucket": { "name": "streamapp-uploads" },
                      "object": { "key": "550e8400-e29b-41d4-a716-446655440000/demo.mp4" }
                    }
                  }]
                }
                """;

        var snsEnvelope = """
                {
                  "Type": "Notification",
                  "MessageId": "msg-1",
                  "TopicArn": "arn:aws:sns:us-east-1:000000000000:video-upload-events",
                  "Message": %s
                }
                """.formatted(objectMapperEscape(s3Event));

        var event = parser.parseSqsMessage(snsEnvelope);

        assertThat(event).isPresent();
        assertThat(event.get().uploadId()).isEqualTo(UPLOAD_ID);
        assertThat(event.get().bucketName()).isEqualTo("streamapp-uploads");
        assertThat(event.get().objectKey()).isEqualTo("550e8400-e29b-41d4-a716-446655440000/demo.mp4");
    }

    @Test
    void parseSqsMessage_decodesUrlEncodedObjectKey() {
        var s3Event = """
                {
                  "Records": [{
                    "eventSource": "aws:s3",
                    "s3": {
                      "bucket": { "name": "streamapp-uploads" },
                      "object": { "key": "550e8400-e29b-41d4-a716-446655440000/my%20video.mp4" }
                    }
                  }]
                }
                """;

        var event = parser.parseSqsMessage(s3Event);

        assertThat(event).isPresent();
        assertThat(event.get().objectKey()).isEqualTo("550e8400-e29b-41d4-a716-446655440000/my video.mp4");
    }

    @Test
    void parseSqsMessage_returnsEmptyForInvalidPayload() {
        assertThat(parser.parseSqsMessage("not json")).isEmpty();
        assertThat(parser.parseSqsMessage("{\"Records\":[]}")).isEmpty();
    }

    @Test
    void parseUploadId_rejectsKeyWithoutUploadPrefix() {
        assertThat(S3UploadEventParser.parseUploadId("no-slash-key")).isEmpty();
        assertThat(S3UploadEventParser.parseUploadId("not-a-uuid/file.mp4")).isEmpty();
    }

    private static String objectMapperEscape(String json) {
        return "\"" + json.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
