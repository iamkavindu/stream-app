package dev.iamkavindu.streamapp.lambda.aws;

import dev.iamkavindu.streamapp.lambda.support.MessagingFixtures;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class S3UploadEventParserContractTest {

    private final S3UploadEventParser parser = new S3UploadEventParser(new ObjectMapper());

    @Test
    void parsesSharedSnsFixture() {
        var message = MessagingFixtures.snsNotificationEnvelope(MessagingFixtures.SAMPLE_UPLOAD_ID, "demo.mp4");

        var event = parser.parseSqsMessage(message);

        assertThat(event).isPresent();
        assertThat(event.get().uploadId()).isEqualTo(MessagingFixtures.SAMPLE_UPLOAD_ID);
        assertThat(event.get().bucketName()).isEqualTo("streamapp-uploads");
        assertThat(event.get().objectKey())
                .isEqualTo(MessagingFixtures.SAMPLE_UPLOAD_ID + "/demo.mp4");
    }

    @Test
    void parsesSharedSqsLambdaEnvelopeFixture() {
        var sqsBody = MessagingFixtures.sqsLambdaEnvelope(MessagingFixtures.SAMPLE_UPLOAD_ID, "clip.mp4");
        var bodies = parser.parseSqsEnvelopeBodies(sqsBody.getBytes(StandardCharsets.UTF_8));

        assertThat(bodies).hasSize(1);
        var event = parser.parseSqsMessage(bodies.getFirst());
        assertThat(event).isPresent();
        assertThat(event.get().uploadId()).isEqualTo(MessagingFixtures.SAMPLE_UPLOAD_ID);
        assertThat(event.get().objectKey())
                .isEqualTo(MessagingFixtures.SAMPLE_UPLOAD_ID + "/clip.mp4");
    }
}
