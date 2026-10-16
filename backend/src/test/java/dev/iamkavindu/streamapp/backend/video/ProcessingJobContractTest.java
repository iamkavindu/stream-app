package dev.iamkavindu.streamapp.backend.video;

import dev.iamkavindu.streamapp.backend.video.model.ProcessingJobRecord;
import dev.iamkavindu.streamapp.backend.support.MessagingFixtures;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class ProcessingJobContractTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final String fixture = MessagingFixtures.loadTemplate("messaging/processing-job-v1.json");

    @Test
    void sharedJobRoundTripsWithDistinctAttemptIdentity() {
        var job = mapper.readValue(fixture, ProcessingJobRecord.class);
        assertThat(job.uploadId()).isEqualTo(MessagingFixtures.SAMPLE_UPLOAD_ID);
        assertThat(job.attemptId()).isNotEqualTo(job.uploadId());
        assertThat(mapper.readTree(mapper.writeValueAsString(job))).isEqualTo(mapper.readTree(fixture));
    }

    @Test
    void rejectsMissingIdentityUnsupportedVersionAndWrongCommand() {
        for (var bad : new String[] {
                fixture.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"),
                fixture.replace("\"schemaVersion\": 1", "\"schemaVersion\": null"),
                fixture.replace("TRANSCODE", "DELETE"),
                fixture.replace("660e8400-e29b-41d4-a716-446655440000", "invalid")}) {
            assertThatThrownBy(() -> mapper.readValue(bad, ProcessingJobRecord.class)).isInstanceOf(RuntimeException.class);
        }
        assertThatThrownBy(() -> new ProcessingJobRecord(1, "TRANSCODE", null, UUID.randomUUID(), "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProcessingJobRecord(1, "TRANSCODE", UUID.randomUUID(), null, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsForeignNestedAndUnencodedSourceKeys() {
        var id = MessagingFixtures.SAMPLE_UPLOAD_ID;
        for (var key : new String[] {"other/demo.mp4", id + "/../demo.mp4", id + "/nested/demo.mp4",
                id + "/demo%2Fclip.mp4", id + "/", id + "/..", id + "/demo clip.mp4", id + "/" + "a".repeat(256)}) {
            assertThatThrownBy(() -> new ProcessingJobRecord(1, "TRANSCODE", id, UUID.randomUUID(), key))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
