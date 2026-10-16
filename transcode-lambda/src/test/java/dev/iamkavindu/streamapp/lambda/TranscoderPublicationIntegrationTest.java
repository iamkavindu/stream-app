package dev.iamkavindu.streamapp.lambda;

import dev.iamkavindu.streamapp.lambda.aws.S3UploadEventParser;
import dev.iamkavindu.streamapp.lambda.support.LambdaIntegrationTest;
import dev.iamkavindu.streamapp.lambda.support.MessagingFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@LambdaIntegrationTest
class TranscoderPublicationIntegrationTest {
    @Autowired S3Client s3;
    @Autowired ObjectMapper mapper;

    @Test
    void committedResultSurvivesSendFailureAndRestartWithoutRewritingOutput() {
        var id = source();
        var sends = new ArrayList<String>();
        var sendFailures = new AtomicInteger(1);
        var runs = new AtomicInteger();
        var queue = queue(sends, sendFailures);
        var payload = MessagingFixtures.sqsLambdaEnvelope(id, "demo.mp4").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThatThrownBy(() -> worker(s3, queue, runs).transcodeVideo().accept(payload))
                .isInstanceOf(IllegalStateException.class);
        var files = outputs(id);
        assertThat(files).hasSize(2);
        s3.deleteObject(r -> r.bucket("streamapp-uploads").key(id + "/demo.mp4"));
        // Fresh worker instance, same S3 state: no source read/FFmpeg/output rewrite needed.
        worker(s3, queue, runs).transcodeVideo().accept(payload);
        assertThat(runs.get()).isEqualTo(1);
        assertThat(sends).hasSize(2);
        assertThat(sends.getFirst()).isEqualTo(sends.getLast());
        assertThat(outputs(id)).isEqualTo(files);
        var key = mapper.readTree(sends.getLast()).path("playlistKey").asText();
        assertThat(key).startsWith(id + "/attempts/" + id + "/executions/").endsWith("/index.m3u8");
        assertThat(s3.headObject(r -> r.bucket("streamapp-streams").key(key)).contentType())
                .isEqualTo("application/vnd.apple.mpegurl");
    }

    @Test
    void partialOutputFailureReleasesClaimAndNextExecutionUsesDifferentPrefix() {
        var id = source();
        var writes = new AtomicInteger();
        var client = mock(S3Client.class, org.mockito.AdditionalAnswers.delegatesTo(s3));
        doAnswer(invocation -> {
            PutObjectRequest request = invocation.getArgument(0);
            Path path = invocation.getArgument(1);
            if (writes.incrementAndGet() == 2) throw SdkClientException.create("Manifest storage unavailable");
            return s3.putObject(request, path);
        }).when(client).putObject(any(PutObjectRequest.class), any(Path.class));
        var sends = new ArrayList<String>();
        var worker = worker(client, queue(sends, new AtomicInteger()), new AtomicInteger());
        var payload = MessagingFixtures.sqsLambdaEnvelope(id, "demo.mp4").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThatThrownBy(() -> worker.transcodeVideo().accept(payload)).isInstanceOf(IllegalStateException.class);
        assertThat(sends).isEmpty();
        var partial = outputs(id);
        assertThat(partial).hasSize(1);
        assertThat(partial.getFirst().key()).endsWith("/media.mp4");
        worker.transcodeVideo().accept(payload);
        assertThat(sends).hasSize(1);
        assertThat(outputs(id)).hasSize(3);
        var playlist = mapper.readTree(sends.getFirst()).path("playlistKey").asText();
        assertThat(playlist.substring(0, playlist.lastIndexOf('/')))
                .isNotEqualTo(partial.getFirst().key().substring(0, partial.getFirst().key().lastIndexOf('/')));
    }

    @Test
    void explicitAttemptSurvivesRestartAndDoesNotReuseInitialCoordination() {
        var id = source();
        var attempt = UUID.randomUUID();
        var sends = new ArrayList<String>();
        var runs = new AtomicInteger();
        var queue = queue(sends, new AtomicInteger());
        var job = new dev.iamkavindu.streamapp.lambda.model.ProcessingJobRecord(
                1, "TRANSCODE", id, attempt, id + "/demo.mp4");
        var payload = MessagingFixtures.sqsLambdaEnvelope(mapper.writeValueAsString(job))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        worker(s3, queue, runs).transcodeVideo().accept(payload);
        var retryFiles = outputs(id);
        var result = mapper.readTree(sends.getFirst());
        assertThat(result.path("attemptId").asText()).isEqualTo(attempt.toString());
        assertThat(result.path("playlistKey").asText()).startsWith(id + "/attempts/" + attempt + "/");
        var retained = s3.getObjectAsBytes(r -> r.bucket("streamapp-streams")
                .key("_processing/" + id + "/" + attempt + ".json")).asUtf8String();
        assertThat(mapper.readTree(retained).path("completedResult").asText()).isEqualTo(sends.getFirst());
        worker(s3, queue, runs).transcodeVideo().accept(payload);
        assertThat(runs.get()).isEqualTo(1);
        assertThat(sends.getLast()).isEqualTo(sends.getFirst());
        assertThat(outputs(id)).isEqualTo(retryFiles);
        worker(s3, queue, runs).transcodeVideo().accept(MessagingFixtures.sqsLambdaEnvelope(id, "demo.mp4")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(runs.get()).isEqualTo(2);
        assertThat(outputs(id)).hasSize(4);
        assertThat(mapper.readTree(sends.getLast()).path("attemptId").asText()).isEqualTo(id.toString());
        assertThat(s3.getObjectAsBytes(r -> r.bucket("streamapp-streams")
                .key("_processing/" + id + "/" + attempt + ".json")).asUtf8String()).isEqualTo(retained);
    }

    private UUID source() {
        var id = UUID.randomUUID();
        s3.putObject(r -> r.bucket("streamapp-uploads").key(id + "/demo.mp4"), RequestBody.fromString("source"));
        return id;
    }

    private List<software.amazon.awssdk.services.s3.model.S3Object> outputs(UUID id) {
        return s3.listObjectsV2(r -> r.bucket("streamapp-streams").prefix(id + "/attempts/")).contents();
    }

    private SqsClient queue(List<String> sends, AtomicInteger failures) {
        var queue = mock(SqsClient.class);
        when(queue.getQueueUrl(org.mockito.ArgumentMatchers.<Consumer<GetQueueUrlRequest.Builder>>any()))
                .thenReturn(GetQueueUrlResponse.builder().queueUrl("http://unused/results").build());
        doAnswer(invocation -> {
            Consumer<SendMessageRequest.Builder> configure = invocation.getArgument(0);
            var builder = SendMessageRequest.builder();
            configure.accept(builder);
            sends.add(builder.build().messageBody());
            if (failures.getAndDecrement() > 0) throw SdkClientException.create("Publication unavailable");
            return SendMessageResponse.builder().messageId(UUID.randomUUID().toString()).build();
        }).when(queue).sendMessage(org.mockito.ArgumentMatchers.<Consumer<SendMessageRequest.Builder>>any());
        return queue;
    }

    private Transcoder worker(S3Client client, SqsClient queue, AtomicInteger runs) {
        return new Transcoder(client, queue, mapper, new S3UploadEventParser(mapper),
                "streamapp-uploads", "streamapp-streams", "unused", "results") {
            @Override
            void executeNativeProcess(List<String> command) throws Exception {
                runs.incrementAndGet();
                var manifest = Path.of(command.getLast());
                Files.writeString(manifest, "#EXTM3U\nmedia.mp4\n");
                Files.writeString(manifest.resolveSibling("media.mp4"), "synthetic-media");
            }
        };
    }
}
