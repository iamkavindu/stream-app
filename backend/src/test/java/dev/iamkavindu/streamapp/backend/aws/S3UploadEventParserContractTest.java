package dev.iamkavindu.streamapp.backend.aws;

import dev.iamkavindu.streamapp.backend.support.MessagingFixtures;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

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
    void parsesUrlEncodedKeyFromFixtureTemplate() {
        var s3Event = MessagingFixtures.s3ObjectCreatedEvent(MessagingFixtures.SAMPLE_UPLOAD_ID, "my%20video.mp4");
        var event = parser.parseSqsMessage(s3Event);

        assertThat(event).isPresent();
        assertThat(event.get().objectKey())
                .isEqualTo(MessagingFixtures.SAMPLE_UPLOAD_ID + "/my video.mp4");
    }
}
