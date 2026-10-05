package dev.iamkavindu.streamapp.lambda;

import dev.iamkavindu.streamapp.lambda.aws.S3UploadEventParser;
import dev.iamkavindu.streamapp.lambda.support.MessagingFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

class TranscoderUnitTest {

    private final S3Client s3Client = mock(S3Client.class);
    private final SqsClient sqsClient = mock(SqsClient.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> attemptedStatuses = new ArrayList<>();
    private final List<String> deliveredStatuses = new ArrayList<>();
    private final List<Path> workDirs = new ArrayList<>();
    private int sendFailures;
    private Exception workerFailure;
    private boolean createArtifacts = true;
    private Transcoder transcoder;
    private byte[] payload;

    @BeforeEach
    void setUp() {
        payload = MessagingFixtures.sqsLambdaEnvelope(UUID.randomUUID(), "demo.mp4")
                .getBytes(StandardCharsets.UTF_8);
        when(sqsClient.getQueueUrl(org.mockito.ArgumentMatchers.<Consumer<GetQueueUrlRequest.Builder>>any()))
                .thenReturn(GetQueueUrlResponse.builder().queueUrl("http://floci/results").build());
        doAnswer(invocation -> {
            Consumer<SendMessageRequest.Builder> configure = invocation.getArgument(0);
            var builder = SendMessageRequest.builder();
            configure.accept(builder);
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
                "streamapp-uploads", "streamapp-streams", "unused-ffmpeg", "results") {
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
    void successPublicationFailure_rethrowsAndRedeliveryPublishesOnlyPlayReady() {
        sendFailures = 1;
        assertThatThrownBy(() -> transcoder.transcodeVideo().accept(payload))
                .isInstanceOf(IllegalStateException.class);
        assertThat(deliveredStatuses).isEmpty();
        assertWorkDirsCleaned();

        transcoder.transcodeVideo().accept(payload);
        assertThat(attemptedStatuses).containsExactly("PLAY_READY", "PLAY_READY");
        assertThat(deliveredStatuses).containsExactly("PLAY_READY");
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

    private void assertWorkDirsCleaned() {
        assertThat(workDirs).isNotEmpty();
        for (var directory : workDirs) {
            assertThat(Files.exists(directory)).isFalse();
        }
    }
}
