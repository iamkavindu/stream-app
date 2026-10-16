package dev.iamkavindu.streamapp.lambda;

import dev.iamkavindu.streamapp.lambda.aws.S3UploadEventParser;
import dev.iamkavindu.streamapp.lambda.coordination.ProcessingLeaseStore;
import dev.iamkavindu.streamapp.lambda.support.MessagingFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

class TranscoderUnitTest {

    private final ProcessingLeaseStore leaseStore = mock(ProcessingLeaseStore.class);
    private final java.util.Map<UUID, ProcessingLeaseStore.Lease> leases = new java.util.HashMap<>();
    private final S3Client s3Client = mock(S3Client.class);
    private final SqsClient sqsClient = mock(SqsClient.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> attemptedStatuses = new ArrayList<>();
    private final List<String> deliveredStatuses = new ArrayList<>();
    private final List<Path> workDirs = new ArrayList<>();
    private final List<String> resultMessages = new ArrayList<>();
    private int sendFailures;
    private Exception workerFailure;
    private boolean createArtifacts = true;
    private Transcoder transcoder;
    private byte[] payload;

    @BeforeEach
    void setUp() {
        payload = MessagingFixtures.sqsLambdaEnvelope(UUID.randomUUID(), "demo.mp4")
                .getBytes(StandardCharsets.UTF_8);
        when(leaseStore.acquire(any(), any())).thenAnswer(invocation -> {
            UUID id = invocation.getArgument(0);
            UUID attempt = invocation.getArgument(1);
            return leases.computeIfAbsent(attempt, key -> new ProcessingLeaseStore.Lease(id, attempt, UUID.randomUUID(),
                    UUID.randomUUID().toString(), java.time.Instant.now().plusSeconds(600), null));
        });
        when(leaseStore.complete(any(), any())).thenAnswer(invocation -> {
            ProcessingLeaseStore.Lease lease = invocation.getArgument(0);
            String result = invocation.getArgument(1);
            leases.put(lease.attemptId(), new ProcessingLeaseStore.Lease(lease.uploadId(), lease.attemptId(),
                    lease.executionId(), lease.etag(), lease.expiresAt(), result));
            return result;
        });
        doAnswer(invocation -> {
            ProcessingLeaseStore.Lease lease = invocation.getArgument(0);
            var current = leases.get(lease.attemptId());
            if (current != null && current.completedResult() == null) leases.remove(lease.attemptId());
            return null;
        }).when(leaseStore).release(any());
        when(sqsClient.getQueueUrl(org.mockito.ArgumentMatchers.<Consumer<GetQueueUrlRequest.Builder>>any()))
                .thenReturn(GetQueueUrlResponse.builder().queueUrl("http://floci/results").build());
        doAnswer(invocation -> {
            Consumer<SendMessageRequest.Builder> configure = invocation.getArgument(0);
            var builder = SendMessageRequest.builder();
            configure.accept(builder);
            resultMessages.add(builder.build().messageBody());
            var status = mapper.readTree(builder.build().messageBody()).path("status").asText();
            attemptedStatuses.add(status);
            if (sendFailures > 0) {
                sendFailures--;
                throw SdkClientException.create("SQS unavailable");
            }
            deliveredStatuses.add(status);
            return SendMessageResponse.builder().messageId("result").build();
        }).when(sqsClient).sendMessage(org.mockito.ArgumentMatchers.<Consumer<SendMessageRequest.Builder>>any());
        transcoder = new Transcoder(s3Client, sqsClient, mapper, new S3UploadEventParser(mapper),
                "streamapp-uploads", "streamapp-streams", "unused-ffmpeg", "results", leaseStore) {
            @Override
            void executeNativeProcess(List<String> command) throws Exception {
                var manifest = Path.of(command.getLast());
                workDirs.add(manifest.getParent().getParent());
                if (workerFailure != null) {
                    throw workerFailure;
                }
                if (createArtifacts) {
                    Files.writeString(manifest, "#EXTM3U");
                    Files.writeString(manifest.resolveSibling("media.mp4"), "media");
                }
            }
        };
    }

    @Test
    void publishesSharedVersionTwoContractForSuccessAndMediaFailure() {
        for (var status : new String[] {"PLAY_READY", "FAILED"}) {
            var uploadId = UUID.randomUUID();
            payload = MessagingFixtures.sqsLambdaEnvelope(uploadId, "demo.mp4").getBytes(StandardCharsets.UTF_8);
            workerFailure = status.equals("FAILED") ? new Transcoder.FfmpegExitException(1) : null;
            transcoder.transcodeVideo().accept(payload);
            var expected = MessagingFixtures.loadTemplate(status.equals("FAILED")
                    ? "messaging/transcode-complete-failed-v2.json" : "messaging/transcode-complete-execution-v2.json")
                    .replace("{{uploadId}}", uploadId.toString())
                    .replace("{{attemptId}}", uploadId.toString())
                    .replace("{{status}}", status)
                    .replace("{{executionId}}", leases.get(uploadId).executionId().toString());
            assertThat(mapper.readTree(resultMessages.getLast())).isEqualTo(mapper.readTree(expected));
        }
        assertWorkDirsCleaned();
    }

    @Test
    void generatedObjectsHaveBrowserMediaContentTypes() {
        transcoder.transcodeVideo().accept(payload);
        var requests = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client, times(2)).putObject(requests.capture(), any(Path.class));
        assertThat(requests.getAllValues()).anySatisfy(request -> {
            assertThat(request.key()).endsWith("/index.m3u8");
            assertThat(request.contentType()).isEqualTo("application/vnd.apple.mpegurl");
        }).anySatisfy(request -> {
            assertThat(request.key()).endsWith("/media.mp4");
            assertThat(request.contentType()).isEqualTo("video/mp4");
        });
        assertThat(requests.getAllValues().getFirst().key()).endsWith("/media.mp4");
        assertThat(requests.getAllValues().getLast().key()).endsWith("/index.m3u8");
        assertThat(requests.getAllValues()).allSatisfy(r -> assertThat(r.ifNoneMatch()).isEqualTo("*"));
        assertThat(deliveredStatuses).containsExactly("PLAY_READY");
        assertWorkDirsCleaned();
    }

    @Test
    void successPublicationFailure_rethrowsAndRedeliveryPublishesOnlyPlayReady() {
        sendFailures = 1;
        assertThatThrownBy(() -> transcoder.transcodeVideo().accept(payload))
                .isInstanceOf(IllegalStateException.class);
        assertThat(deliveredStatuses).isEmpty();
        assertWorkDirsCleaned();

        transcoder.transcodeVideo().accept(payload);
        assertThat(attemptedStatuses).containsExactly("PLAY_READY", "PLAY_READY");
        assertThat(deliveredStatuses).containsExactly("PLAY_READY");
        assertThat(workDirs).hasSize(1);
        assertThat(resultMessages).hasSize(2);
        assertThat(resultMessages.get(0)).isEqualTo(resultMessages.get(1));
        assertWorkDirsCleaned();
    }

    @Test
    void failurePublicationFailure_rethrowsUntilFailedResultIsDelivered() {
        workerFailure = new Transcoder.FfmpegExitException(1);
        sendFailures = 1;
        assertThatThrownBy(() -> transcoder.transcodeVideo().accept(payload))
                .isInstanceOf(IllegalStateException.class);
        assertThat(deliveredStatuses).isEmpty();
        assertWorkDirsCleaned();

        transcoder.transcodeVideo().accept(payload);
        assertThat(attemptedStatuses).containsExactly("FAILED", "FAILED");
        assertThat(deliveredStatuses).containsExactly("FAILED");
        assertWorkDirsCleaned();
    }

    @Test
    void queueLookupFailure_rethrowsAndRedeliveryRecovers() {
        when(sqsClient.getQueueUrl(org.mockito.ArgumentMatchers.<Consumer<GetQueueUrlRequest.Builder>>any()))
                .thenThrow(SdkClientException.create("queue lookup unavailable"))
                .thenReturn(GetQueueUrlResponse.builder().queueUrl("http://floci/results").build());
        assertThatThrownBy(() -> transcoder.transcodeVideo().accept(payload))
                .isInstanceOf(IllegalStateException.class);
        assertThat(attemptedStatuses).isEmpty();
        transcoder.transcodeVideo().accept(payload);
        assertThat(deliveredStatuses).containsExactly("PLAY_READY");
        assertWorkDirsCleaned();
    }

    @Test
    void sourceFailure_rethrowsWithoutFailedResultAndRedeliveryRecovers() {
        when(s3Client.getObject(any(GetObjectRequest.class), any(Path.class)))
                .thenThrow(SdkClientException.create("source unavailable"))
                .thenReturn(GetObjectResponse.builder().build());
        assertThatThrownBy(() -> transcoder.transcodeVideo().accept(payload))
                .isInstanceOf(IllegalStateException.class);
        assertThat(attemptedStatuses).isEmpty();
        transcoder.transcodeVideo().accept(payload);
        assertThat(deliveredStatuses).containsExactly("PLAY_READY");
        assertWorkDirsCleaned();
    }

    @Test
    void outputFailure_rethrowsWithoutFailedResultAndRedeliveryRecovers() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(Path.class)))
                .thenThrow(SdkClientException.create("output unavailable"))
                .thenReturn(PutObjectResponse.builder().build());
        assertThatThrownBy(() -> transcoder.transcodeVideo().accept(payload))
                .isInstanceOf(IllegalStateException.class);
        assertThat(attemptedStatuses).isEmpty();
        transcoder.transcodeVideo().accept(payload);
        assertThat(deliveredStatuses).containsExactly("PLAY_READY");
        assertWorkDirsCleaned();
    }

    @Test
    void missingWorker_rethrowsWithoutFailedResultAndRedeliveryRecovers() {
        workerFailure = new IOException("FFmpeg executable missing");
        assertThatThrownBy(() -> transcoder.transcodeVideo().accept(payload))
                .isInstanceOf(IllegalStateException.class);
        assertThat(attemptedStatuses).isEmpty();
        assertWorkDirsCleaned();
        workerFailure = null;
        transcoder.transcodeVideo().accept(payload);
        assertThat(deliveredStatuses).containsExactly("PLAY_READY");
        assertWorkDirsCleaned();
    }

    @Test
    void busyLeaseDoesNotDownloadTranscodeOrPublish() {
        org.mockito.Mockito.doThrow(new ProcessingLeaseStore.LeaseUnavailableException())
                .when(leaseStore).acquire(any(), any());
        assertThatThrownBy(() -> transcoder.transcodeVideo().accept(payload))
                .isInstanceOf(ProcessingLeaseStore.LeaseUnavailableException.class);
        verify(s3Client, org.mockito.Mockito.never()).getObject(any(GetObjectRequest.class), any(Path.class));
        assertThat(workDirs).isEmpty();
        assertThat(resultMessages).isEmpty();
    }

    @Test
    void lostOwnershipCannotPublishReadiness() {
        org.mockito.Mockito.doThrow(new ProcessingLeaseStore.LeaseUnavailableException())
                .when(leaseStore).complete(any(), any());
        assertThatThrownBy(() -> transcoder.transcodeVideo().accept(payload)).isInstanceOf(IllegalStateException.class);
        assertThat(resultMessages).isEmpty();
        assertWorkDirsCleaned();
    }

    @Test
    void missingArtifacts_rethrowsWithoutFailedResult() {
        createArtifacts = false;
        assertThatThrownBy(() -> transcoder.transcodeVideo().accept(payload))
                .isInstanceOf(IllegalStateException.class);
        assertThat(attemptedStatuses).isEmpty();
        assertWorkDirsCleaned();
    }

    @Test
    void interruptedWorker_restoresInterruptAndFailsInvocation() {
        workerFailure = new InterruptedException("worker interrupted");
        try {
            assertThatThrownBy(() -> transcoder.transcodeVideo().accept(payload))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(attemptedStatuses).isEmpty();
            assertWorkDirsCleaned();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void directJobKeepsRetrySeparateFromInitialNotificationAndReplaysIdentically() {
        var job = MessagingFixtures.loadTemplate("messaging/processing-job-v1.json");
        var id = MessagingFixtures.SAMPLE_UPLOAD_ID;
        var attempt = UUID.fromString("660e8400-e29b-41d4-a716-446655440000");
        var retryPayload = MessagingFixtures.sqsLambdaEnvelope(job).getBytes(StandardCharsets.UTF_8);
        transcoder.transcodeVideo().accept(retryPayload);
        var retryResult = resultMessages.getLast();
        assertThat(mapper.readTree(retryResult).path("attemptId").asText()).isEqualTo(attempt.toString());
        assertThat(mapper.readTree(retryResult).path("playlistKey").asText())
                .startsWith(id + "/attempts/" + attempt + "/executions/");
        transcoder.transcodeVideo().accept(retryPayload);
        assertThat(resultMessages.getLast()).isEqualTo(retryResult);
        assertThat(workDirs).hasSize(1);
        transcoder.transcodeVideo().accept(MessagingFixtures.sqsLambdaEnvelope(id, "demo.mp4")
                .getBytes(StandardCharsets.UTF_8));
        assertThat(mapper.readTree(resultMessages.getLast()).path("attemptId").asText()).isEqualTo(id.toString());
        assertThat(leases).containsKeys(id, attempt);
        assertThat(workDirs).hasSize(2);
        assertWorkDirsCleaned();
    }

    @Test
    void invalidDirectJobFailsBeforeClaimOrSourceAccess() {
        var job = MessagingFixtures.loadTemplate("messaging/processing-job-v1.json").replace("TRANSCODE", "DELETE");
        assertThatThrownBy(() -> transcoder.transcodeVideo().accept(
                MessagingFixtures.sqsLambdaEnvelope(job).getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(RuntimeException.class);
        org.mockito.Mockito.verifyNoInteractions(leaseStore, s3Client, sqsClient);
        assertThat(workDirs).isEmpty();
    }

    private void assertWorkDirsCleaned() {
        assertThat(workDirs).isNotEmpty();
        for (var directory : workDirs) {
            assertThat(Files.exists(directory)).isFalse();
        }
    }
}
