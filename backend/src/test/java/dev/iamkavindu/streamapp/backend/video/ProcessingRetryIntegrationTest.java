package dev.iamkavindu.streamapp.backend.video;

import dev.iamkavindu.streamapp.backend.support.IntegrationTest;
import dev.iamkavindu.streamapp.backend.support.TestData;
import dev.iamkavindu.streamapp.backend.exception.ProcessingNotRetryableException;
import dev.iamkavindu.streamapp.backend.video.dispatch.ProcessingOutboxRepository;
import dev.iamkavindu.streamapp.backend.video.dispatch.ProcessingOutboxDispatcher;
import dev.iamkavindu.streamapp.backend.video.model.ProcessingJobRecord;
import dev.iamkavindu.streamapp.backend.video.model.VideoStatus;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.*;
import tools.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import static dev.iamkavindu.streamapp.backend.jooq.tables.Videos.VIDEOS;
import static dev.iamkavindu.streamapp.backend.jooq.tables.ProcessingAttempts.PROCESSING_ATTEMPTS;
import static dev.iamkavindu.streamapp.backend.jooq.tables.ProcessingOutbox.PROCESSING_OUTBOX;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@IntegrationTest
@AutoConfigureMockMvc
class ProcessingRetryIntegrationTest {
    @Autowired VideoRepository videos;
    @Autowired ProcessingRetryService retries;
    @Autowired ProcessingOutboxRepository outbox;
    @Autowired VideoService listeners;
    @Autowired DSLContext dsl;
    @Autowired S3Client s3;
    @Autowired SqsClient sqs;
    @Autowired SqsAsyncClient asyncSqs;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;

    @BeforeEach @AfterEach
    void clearOutbox() { dsl.deleteFrom(PROCESSING_OUTBOX).execute(); }

    UUID failed(boolean source) {
        var id = UUID.randomUUID();
        videos.createPendingUploadEntry(id, "demo.mp4", TestData.uniqueShaBytes());
        videos.completeTranscode(id, VideoStatus.FAILED);
        if (source) s3.putObject(r -> r.bucket("streamapp-uploads").key(id + "/demo.mp4"), RequestBody.fromString("source"));
        return id;
    }

    @Test
    void apiQueuesFreshAttemptAndStaleResultsCannotSettleIt() throws Exception {
        var id = failed(true);
        var original = videos.findAttempts(id).getFirst();
        var response = mvc.perform(post("/api/v1/videos/{id}/processing-retries", id))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.uploadId").value(id.toString()))
                .andExpect(jsonPath("$.status").value("TRANSCODING_IN_PROGRESS")).andReturn();
        var attempt = UUID.fromString(mapper.readTree(response.getResponse().getContentAsString()).path("attemptId").asText());
        assertThat(attempt).isNotEqualTo(id);
        var queued = videos.findAttempts(id).getLast();
        assertThat(queued.stage()).isEqualTo("QUEUED");
        assertThat(queued.queuedAt()).isNotNull();
        assertThat(queued.uploadObservedAt()).isNull();
        assertThat(queued.completedAt()).isNull();
        assertThat(videos.findAttempts(id).getFirst().completedAt()).isEqualTo(original.completedAt());
        assertThat(videos.completeTranscode(id, VideoStatus.PLAY_READY)).isFalse();
        assertThat(videos.completeTranscode(id, id, VideoStatus.PLAY_READY)).isFalse();
        listeners.onUploadComplete(dev.iamkavindu.streamapp.backend.support.MessagingFixtures.snsNotificationEnvelope(id, "demo.mp4"));
        assertThat(videos.findAttempts(id).getLast()).isEqualTo(queued);
        var row = dsl.selectFrom(PROCESSING_OUTBOX).fetchSingle();
        var job = mapper.readValue(row.getPayload(), ProcessingJobRecord.class);
        assertThat(job.attemptId()).isEqualTo(attempt);
        assertThat(job.sourceKey()).isEqualTo(id + "/demo.mp4");
        assertThat(videos.completeTranscode(id, attempt, VideoStatus.FAILED)).isTrue();
        assertThat(videos.findAttempts(id).getLast().queuedAt()).isEqualTo(queued.queuedAt());
    }

    @Test
    void missingSourceUnknownAndActiveOrReadyVideosHaveExplicitConflicts() throws Exception {
        mvc.perform(post("/api/v1/videos/{id}/processing-retries", UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/videos/{id}/processing-retries", failed(false)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.type").value("https://stream-app.dev/problems/processing-not-retryable"));
        for (var status : new VideoStatus[] {VideoStatus.AWAITING_UPLOAD, VideoStatus.TRANSCODING_IN_PROGRESS, VideoStatus.PLAY_READY}) {
            var id = UUID.randomUUID();
            videos.createPendingUploadEntry(id, "demo.mp4", TestData.uniqueShaBytes());
            dsl.update(VIDEOS).set(VIDEOS.STATUS, status.name()).where(VIDEOS.UPLOAD_ID.eq(id)).execute();
            mvc.perform(post("/api/v1/videos/{id}/processing-retries", id)).andExpect(status().isConflict());
        }
        assertThat(dsl.fetchCount(PROCESSING_OUTBOX)).isZero();
    }

    @Test
    void retrySuccessRequiresItsOwnArtifactInsteadOfFallingBackToInitialOutput() {
        var retry = retries.retry(failed(true));
        var id = retry.uploadId();
        var attempt = retry.attemptId();
        listeners.onTranscodeComplete(mapper.writeValueAsString(
                new dev.iamkavindu.streamapp.backend.video.model.VideoStatusUpdateRecord(id, VideoStatus.PLAY_READY, attempt, 2)));
        assertThat(videos.findByUploadId(id).orElseThrow().status()).isEqualTo(VideoStatus.TRANSCODING_IN_PROGRESS);
        var key = dev.iamkavindu.streamapp.backend.aws.S3ObjectKeys.executionPlaylistKey(id, attempt, UUID.randomUUID());
        listeners.onTranscodeComplete(mapper.writeValueAsString(
                new dev.iamkavindu.streamapp.backend.video.model.VideoStatusUpdateRecord(id, VideoStatus.PLAY_READY,
                        attempt, 2, null, null, key)));
        assertThat(videos.findByUploadId(id).orElseThrow().status()).isEqualTo(VideoStatus.PLAY_READY);
        assertThat(videos.findPlaybackArtifact(id).orElseThrow().playlistKey()).isEqualTo(key);
    }

    @Test
    void concurrentClicksCreateOnlyOneActiveAttemptAndIntent() throws Exception {
        var id = failed(true);
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var task = (java.util.concurrent.Callable<Boolean>) () -> {
                gate.await();
                try { retries.retry(id); return true; }
                catch (ProcessingNotRetryableException e) { return false; }
            };
            var first = pool.submit(task);
            var second = pool.submit(task);
            gate.countDown();
            assertThat(java.util.List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(videos.findAttempts(id)).hasSize(2);
        assertThat(dsl.fetchCount(PROCESSING_OUTBOX)).isEqualTo(1);
    }

    @Test
    void outboxInsertFailureRollsBackVideoAndAttempt() {
        var id = failed(true);
        var attempt = UUID.randomUUID();
        var before = videos.findByUploadId(id);
        assertThatThrownBy(() -> videos.createProcessingRetry(id, attempt, "")).isInstanceOf(RuntimeException.class);
        assertThat(videos.findByUploadId(id)).isEqualTo(before);
        assertThat(videos.findAttempts(id)).hasSize(1);
        assertThat(dsl.fetchCount(PROCESSING_OUTBOX)).isZero();
    }

    @Test
    void competingDispatchersHaveOneOwnerAndExpiredOwnerCannotAcknowledgeSuccessor() throws Exception {
        retries.retry(failed(true));
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var task = (java.util.concurrent.Callable<java.util.Optional<ProcessingOutboxRepository.Claim>>) () -> { gate.await(); return outbox.claimNext(); };
            var first = pool.submit(task);
            var second = pool.submit(task);
            gate.countDown();
            var claims = java.util.stream.Stream.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))
                    .flatMap(java.util.Optional::stream).toList();
            assertThat(claims).hasSize(1);
            var old = claims.getFirst();
            expire(old);
            assertThat(outbox.delivered(old)).isFalse();
            var recovered = outbox.claimNext().orElseThrow();
            assertThat(recovered.claimId()).isNotEqualTo(old.claimId());
            assertThat(recovered.payload()).isEqualTo(old.payload());
            assertThat(outbox.delivered(old)).isFalse();
            assertThat(outbox.retryLater(old)).isFalse();
            assertThat(outbox.delivered(recovered)).isTrue();
        }
    }

    SqsAsyncClient isolatedQueue(String url) {
        var client = mock(SqsAsyncClient.class, org.mockito.AdditionalAnswers.delegatesTo(asyncSqs));
        doReturn(CompletableFuture.completedFuture(GetQueueUrlResponse.builder().queueUrl(url).build()))
                .when(client).getQueueUrl(org.mockito.ArgumentMatchers.<Consumer<GetQueueUrlRequest.Builder>>any());
        return client;
    }

    @Test
    void sqsOutageRetainsQueuedIntentAndBackoffThenRealDeliveryRecovers() {
        var retry = retries.retry(failed(true));
        var url = sqs.createQueue(r -> r.queueName("retry-test-" + UUID.randomUUID())).queueUrl();
        try {
            var client = isolatedQueue(url);
            doReturn(CompletableFuture.failedFuture(new IllegalStateException("injected outage")))
                    .when(client).sendMessage(org.mockito.ArgumentMatchers.<Consumer<SendMessageRequest.Builder>>any());
            new ProcessingOutboxDispatcher(outbox, client).dispatchBatch();
            var row = dsl.selectFrom(PROCESSING_OUTBOX).fetchSingle();
            assertThat(row.getDispatchedAt()).isNull();
            assertThat(row.getClaimId()).isNull();
            assertThat(row.getLastError()).isEqualTo("SQS_DISPATCH_FAILED");
            assertThat(row.getNextAttemptAt()).isAfter(row.getCreatedAt());
            assertThat(outbox.claimNext()).isEmpty();
            assertThat(videos.findAttempts(retry.uploadId()).getLast().stage()).isEqualTo("QUEUED");
            dsl.update(PROCESSING_OUTBOX).set(PROCESSING_OUTBOX.NEXT_ATTEMPT_AT, OffsetDateTime.now().minusSeconds(1)).execute();
            new ProcessingOutboxDispatcher(outbox, isolatedQueue(url)).dispatchBatch();
            assertThat(dsl.selectFrom(PROCESSING_OUTBOX).fetchSingle().getDispatchedAt()).isNotNull();
            var messages = sqs.receiveMessage(r -> r.queueUrl(url).maxNumberOfMessages(10)).messages();
            assertThat(messages).hasSize(1);
            assertThat(messages.getFirst().body()).isEqualTo(row.getPayload());
        } finally { sqs.deleteQueue(r -> r.queueUrl(url)); }
    }

    @Test
    void crashAfterAcceptedSendCanDeliverIdenticalBodyAgainAfterLeaseExpiry() {
        retries.retry(failed(true));
        var url = sqs.createQueue(r -> r.queueName("retry-test-" + UUID.randomUUID())).queueUrl();
        try {
            var interrupted = outbox.claimNext().orElseThrow();
            sqs.sendMessage(r -> r.queueUrl(url).messageBody(interrupted.payload()));
            expire(interrupted); // process died before recording delivery
            new ProcessingOutboxDispatcher(outbox, isolatedQueue(url)).dispatchBatch();
            var messages = sqs.receiveMessage(r -> r.queueUrl(url).maxNumberOfMessages(10)).messages();
            assertThat(messages).hasSize(2).allSatisfy(m -> assertThat(m.body()).isEqualTo(interrupted.payload()));
            assertThat(dsl.selectFrom(PROCESSING_OUTBOX).fetchSingle().getDeliveryAttempts()).isEqualTo(2);
        } finally { sqs.deleteQueue(r -> r.queueUrl(url)); }
    }

    void expire(ProcessingOutboxRepository.Claim claim) {
        dsl.update(PROCESSING_OUTBOX).set(PROCESSING_OUTBOX.CLAIM_UNTIL, OffsetDateTime.now().minusSeconds(1))
                .where(PROCESSING_OUTBOX.ATTEMPT_ID.eq(claim.attemptId())).execute();
    }
}
