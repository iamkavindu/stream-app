package dev.iamkavindu.streamapp.backend.video;

import dev.iamkavindu.streamapp.backend.support.IntegrationTest;
import dev.iamkavindu.streamapp.backend.support.MessagingFixtures;
import dev.iamkavindu.streamapp.backend.support.TestData;
import dev.iamkavindu.streamapp.backend.video.model.VideoStatus;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static dev.iamkavindu.streamapp.backend.jooq.tables.Videos.VIDEOS;
import static dev.iamkavindu.streamapp.backend.jooq.tables.ProcessingAttempts.PROCESSING_ATTEMPTS;

@IntegrationTest
@Transactional
class VideoServiceListenerIntegrationTest {

    @Autowired
    VideoService videoService;

    @Autowired
    VideoRepository videoRepository;

    @Autowired
    DSLContext dsl;

    @Test
    void versionTwoInitialResultCanArriveBeforeUploadAndCannotRegressTerminalState() {
        for (var terminal : new VideoStatus[] {VideoStatus.PLAY_READY, VideoStatus.FAILED}) {
            var uploadId = UUID.randomUUID();
            videoRepository.createPendingUploadEntry(uploadId, "demo.mp4", TestData.uniqueShaBytes());
            videoService.onTranscodeComplete(attemptResult(uploadId, uploadId, terminal));
            var settled = videoRepository.findByUploadId(uploadId).orElseThrow();
            assertThat(settled.status()).isEqualTo(terminal);
            videoService.onUploadComplete(MessagingFixtures.snsNotificationEnvelope(uploadId, "demo.mp4"));
            videoService.onTranscodeComplete(attemptResult(uploadId, uploadId, terminal));
            videoService.onTranscodeComplete(attemptResult(uploadId, uploadId,
                    terminal == VideoStatus.PLAY_READY ? VideoStatus.FAILED : VideoStatus.PLAY_READY));
            assertThat(videoRepository.findByUploadId(uploadId).orElseThrow()).isEqualTo(settled);
        }
    }

    @Test
    void staleAndLegacyResultsCannotSettleAnotherActiveAttempt() {
        var uploadId = UUID.randomUUID();
        var activeAttempt = UUID.randomUUID();
        videoRepository.createPendingUploadEntry(uploadId, "demo.mp4", TestData.uniqueShaBytes());
        // Model a future retry without adding a public retry operation in this slice.
        dsl.insertInto(PROCESSING_ATTEMPTS)
                .set(PROCESSING_ATTEMPTS.ATTEMPT_ID, activeAttempt)
                .set(PROCESSING_ATTEMPTS.UPLOAD_ID, uploadId)
                .set(PROCESSING_ATTEMPTS.STAGE, "AWAITING_UPLOAD")
                .set(PROCESSING_ATTEMPTS.CREATED_AT,
                        java.time.OffsetDateTime.now())
                .execute();
        dsl.update(VIDEOS).set(VIDEOS.ACTIVE_ATTEMPT_ID, activeAttempt)
                .where(VIDEOS.UPLOAD_ID.eq(uploadId)).execute();
        var before = videoRepository.findByUploadId(uploadId).orElseThrow();
        for (var terminal : new VideoStatus[] {VideoStatus.PLAY_READY, VideoStatus.FAILED}) {
            videoService.onTranscodeComplete(attemptResult(uploadId, uploadId, terminal));
            videoService.onTranscodeComplete(MessagingFixtures.transcodeCompleteMessage(uploadId, terminal.name()));
            assertThat(videoRepository.findByUploadId(uploadId).orElseThrow()).isEqualTo(before);
        }
        videoService.onTranscodeComplete(attemptResult(uploadId, activeAttempt, VideoStatus.PLAY_READY));
        assertThat(videoRepository.findByUploadId(uploadId).orElseThrow().status()).isEqualTo(VideoStatus.PLAY_READY);
    }

    @Test
    void incompleteAndUnsupportedVersionContractsCannotSettleInitialAttempt() {
        var uploadId = UUID.randomUUID();
        videoRepository.createPendingUploadEntry(uploadId, "demo.mp4", TestData.uniqueShaBytes());
        var before = videoRepository.findByUploadId(uploadId).orElseThrow();
        for (var fields : new String[] {
                "\"schemaVersion\":2", "\"schemaVersion\":3", "\"schemaVersion\":0",
                "\"attemptId\":\"" + uploadId + "\"",
                "\"attemptId\":\"" + uploadId + "\",\"schemaVersion\":1",
                "\"attemptId\":\"" + uploadId + "\",\"schemaVersion\":3"}) {
            videoService.onTranscodeComplete("{\"uploadId\":\"" + uploadId
                    + "\",\"status\":\"PLAY_READY\"," + fields + "}");
            assertThat(videoRepository.findByUploadId(uploadId).orElseThrow()).isEqualTo(before);
        }
    }

    private String attemptResult(UUID uploadId, UUID attemptId, VideoStatus status) {
        return MessagingFixtures.loadTemplate(!uploadId.equals(attemptId) && status == VideoStatus.PLAY_READY
                        ? "messaging/transcode-complete-execution-v2.json" : "messaging/transcode-complete-v2.json")
                .replace("{{uploadId}}", uploadId.toString())
                .replace("{{attemptId}}", attemptId.toString())
                .replace("{{status}}", status.name())
                .replace("{{executionId}}", UUID.randomUUID().toString());
    }

    @Test
    void onUploadComplete_transitionsAwaitingUploadToTranscoding() {
        var uploadId = UUID.randomUUID();
        videoRepository.createPendingUploadEntry(uploadId, "demo.mp4", TestData.uniqueShaBytes());

        var s3Event = """
                {
                  "Records": [{
                    "eventSource": "aws:s3",
                    "s3": {
                      "bucket": { "name": "streamapp-uploads" },
                      "object": { "key": "%s/demo.mp4" }
                    }
                  }]
                }
                """.formatted(uploadId);

        videoService.onUploadComplete(s3Event);

        assertThat(videoRepository.findByUploadId(uploadId).orElseThrow().status())
                .isEqualTo(VideoStatus.TRANSCODING_IN_PROGRESS);
    }

    @Test
    void onTranscodeComplete_transitionsToPlayReady() {
        var uploadId = UUID.randomUUID();
        videoRepository.createPendingUploadEntry(uploadId, "demo.mp4", TestData.uniqueShaBytes());
        videoRepository.updateStatus(uploadId, VideoStatus.AWAITING_UPLOAD, VideoStatus.TRANSCODING_IN_PROGRESS);

        var message = """
                {"uploadId":"%s","status":"PLAY_READY"}
                """.formatted(uploadId);

        videoService.onTranscodeComplete(message);

        assertThat(videoRepository.findByUploadId(uploadId).orElseThrow().status())
                .isEqualTo(VideoStatus.PLAY_READY);
    }

    @Test
    void onTranscodeComplete_transitionsToFailed() {
        var uploadId = UUID.randomUUID();
        videoRepository.createPendingUploadEntry(uploadId, "demo.mp4", TestData.uniqueShaBytes());
        videoRepository.updateStatus(uploadId, VideoStatus.AWAITING_UPLOAD, VideoStatus.TRANSCODING_IN_PROGRESS);

        var message = """
                {"uploadId":"%s","status":"FAILED"}
                """.formatted(uploadId);

        videoService.onTranscodeComplete(message);

        assertThat(videoRepository.findByUploadId(uploadId).orElseThrow().status())
                .isEqualTo(VideoStatus.FAILED);
    }

    @Test
    void completionBeforeUpload_preservesSuccessAndFailureUnderLateAndDuplicateEvents() {
        assertDeliveryOrder(true);
    }

    @Test
    void uploadBeforeCompletion_preservesSuccessAndFailureUnderDuplicateEvents() {
        assertDeliveryOrder(false);
    }

    private void assertDeliveryOrder(boolean completionFirst) {
        for (var terminal : new VideoStatus[] {VideoStatus.PLAY_READY, VideoStatus.FAILED}) {
            var uploadId = UUID.randomUUID();
            videoRepository.createPendingUploadEntry(uploadId, "demo.mp4", TestData.uniqueShaBytes());
            var upload = MessagingFixtures.snsNotificationEnvelope(uploadId, "demo.mp4");
            var result = MessagingFixtures.transcodeCompleteMessage(uploadId, terminal.name());

            if (!completionFirst) {
                videoService.onUploadComplete(upload);
            }
            videoService.onTranscodeComplete(result);
            var settled = videoRepository.findByUploadId(uploadId).orElseThrow();
            assertThat(settled.status()).isEqualTo(terminal);

            videoService.onUploadComplete(upload);
            videoService.onUploadComplete(upload);
            videoService.onTranscodeComplete(result);
            var conflicting = terminal == VideoStatus.PLAY_READY ? VideoStatus.FAILED : VideoStatus.PLAY_READY;
            videoService.onTranscodeComplete(MessagingFixtures.transcodeCompleteMessage(uploadId, conflicting.name()));
            assertThat(videoRepository.findByUploadId(uploadId).orElseThrow()).isEqualTo(settled);
        }
    }
}
