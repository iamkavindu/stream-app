package dev.iamkavindu.streamapp.backend.video;

import dev.iamkavindu.streamapp.backend.support.IntegrationTest;
import dev.iamkavindu.streamapp.backend.support.MessagingFixtures;
import dev.iamkavindu.streamapp.backend.support.TestData;
import dev.iamkavindu.streamapp.backend.video.model.VideoStatus;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import static dev.iamkavindu.streamapp.backend.jooq.tables.ProcessingAttempts.PROCESSING_ATTEMPTS;
import static dev.iamkavindu.streamapp.backend.jooq.tables.Videos.VIDEOS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
@AutoConfigureMockMvc
class ProcessingAttemptHistoryIntegrationTest {
    @Autowired VideoRepository repository;
    @Autowired VideoService service;
    @Autowired DSLContext dsl;
    @Autowired MockMvc mvc;

    @Test
    void observationsAndWorkerFailureArePersistedOnceAndExposedByApi() throws Exception {
        var uploadId = create();
        var initial = repository.findAttempts(uploadId).getFirst();
        assertThat(initial.stage()).isEqualTo("AWAITING_UPLOAD");
        assertThat(initial.active()).isTrue();
        assertThat(initial.imported()).isFalse();
        assertThat(initial.uploadObservedAt()).isNull();
        assertThat(initial.completedAt()).isNull();
        var upload = MessagingFixtures.snsNotificationEnvelope(uploadId, "demo.mp4");
        service.onUploadComplete(upload);
        var observed = repository.findAttempts(uploadId).getFirst();
        assertThat(observed.stage()).isEqualTo("UPLOAD_OBSERVED");
        assertThat(observed.uploadObservedAt()).isNotNull();
        service.onUploadComplete(upload);
        assertThat(repository.findAttempts(uploadId).getFirst()).isEqualTo(observed);

        var failure = MessagingFixtures.loadTemplate("messaging/transcode-complete-failed-v2.json")
                .replace("{{uploadId}}", uploadId.toString()).replace("{{attemptId}}", uploadId.toString());
        service.onTranscodeComplete(failure);
        var settled = repository.findAttempts(uploadId).getFirst();
        assertThat(settled.stage()).isEqualTo("FAILED");
        assertThat(settled.completedAt()).isNotNull();
        assertThat(settled.failureCode()).isEqualTo("FFMPEG_EXIT");
        assertThat(settled.failureDetail()).isEqualTo("FFmpeg exited with status 1");
        service.onTranscodeComplete(failure);
        service.onTranscodeComplete(MessagingFixtures.transcodeCompleteMessage(uploadId, "PLAY_READY"));
        assertThat(repository.findAttempts(uploadId).getFirst()).isEqualTo(settled);
        mvc.perform(get("/api/v1/videos/{id}/attempts", uploadId)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].attemptId").value(uploadId.toString()))
                .andExpect(jsonPath("$[0].active").value(true))
                .andExpect(jsonPath("$[0].failureCode").value("FFMPEG_EXIT"));
        mvc.perform(get("/api/v1/videos/{id}/attempts", UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void earlyCompletionDoesNotInventUploadObservation() {
        var uploadId = create();
        service.onTranscodeComplete(MessagingFixtures.transcodeCompleteMessage(uploadId, "PLAY_READY"));
        service.onUploadComplete(MessagingFixtures.snsNotificationEnvelope(uploadId, "demo.mp4"));
        var attempt = repository.findAttempts(uploadId).getFirst();
        assertThat(attempt.stage()).isEqualTo("SUCCEEDED");
        assertThat(attempt.completedAt()).isNotNull();
        assertThat(attempt.uploadObservedAt()).isNull();
        assertThat(attempt.failureCode()).isNull();
    }

    @Test
    void expiredUploadHasDistinctReasonAndCannotBeRevived() {
        var uploadId = create();
        dsl.update(VIDEOS).set(VIDEOS.UPDATED_AT, OffsetDateTime.now().minusHours(2))
                .where(VIDEOS.UPLOAD_ID.eq(uploadId)).execute();
        repository.markStaleAwaitingUploadsFailed(Duration.ofMinutes(30));
        var expired = repository.findAttempts(uploadId).getFirst();
        assertThat(expired.stage()).isEqualTo("UPLOAD_EXPIRED");
        assertThat(expired.failureCode()).isEqualTo("UPLOAD_EXPIRED");
        assertThat(expired.completedAt()).isNotNull();
        repository.markStaleAwaitingUploadsFailed(Duration.ofMinutes(30));
        service.onTranscodeComplete(MessagingFixtures.transcodeCompleteMessage(uploadId, "PLAY_READY"));
        assertThat(repository.findAttempts(uploadId).getFirst()).isEqualTo(expired);
    }

    @Test
    void missingLedgerRollsBackVideoSettlement() {
        var uploadId = create();
        var before = repository.findByUploadId(uploadId).orElseThrow();
        dsl.deleteFrom(PROCESSING_ATTEMPTS).where(PROCESSING_ATTEMPTS.ATTEMPT_ID.eq(uploadId)).execute();
        assertThatThrownBy(() -> repository.completeTranscode(uploadId, VideoStatus.PLAY_READY))
                .isInstanceOf(IllegalStateException.class);
        assertThat(repository.findByUploadId(uploadId).orElseThrow()).isEqualTo(before);
        // Remove only this intentionally inconsistent test row.
        dsl.deleteFrom(VIDEOS).where(VIDEOS.UPLOAD_ID.eq(uploadId)).execute();
    }

    @Test
    void invalidFailureMetadataCannotChangeVideoOrLedger() {
        var uploadId = create();
        var before = repository.findAttempts(uploadId);
        for (var fields : new String[] {"\"failureCode\":\"bad-code\"",
                "\"failureDetail\":\"" + "x".repeat(513) + "\""}) {
            service.onTranscodeComplete("{\"uploadId\":\"" + uploadId + "\",\"attemptId\":\"" + uploadId
                    + "\",\"schemaVersion\":2,\"status\":\"FAILED\"," + fields + "}");
        }
        assertThat(repository.findAttempts(uploadId)).isEqualTo(before);
        assertThat(repository.findByUploadId(uploadId).orElseThrow().status()).isEqualTo(VideoStatus.AWAITING_UPLOAD);
    }

    private UUID create() {
        var uploadId = UUID.randomUUID();
        repository.createPendingUploadEntry(uploadId, "demo.mp4", TestData.uniqueShaBytes());
        return uploadId;
    }
}
