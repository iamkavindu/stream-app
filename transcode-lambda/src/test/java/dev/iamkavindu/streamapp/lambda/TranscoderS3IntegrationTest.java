package dev.iamkavindu.streamapp.lambda;

import dev.iamkavindu.streamapp.lambda.aws.S3ObjectKeys;
import dev.iamkavindu.streamapp.lambda.support.FfmpegConditions;
import dev.iamkavindu.streamapp.lambda.support.LambdaIntegrationTest;
import dev.iamkavindu.streamapp.lambda.support.LambdaTestAwsBootstrap;
import dev.iamkavindu.streamapp.lambda.support.MessagingFixtures;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@LambdaIntegrationTest
@Tag("slow")
@EnabledIf("dev.iamkavindu.streamapp.lambda.support.FfmpegConditions#isAvailable")
class TranscoderS3IntegrationTest {

    @Autowired
    Consumer<byte[]> transcodeVideo;

    @Autowired
    S3Client s3Client;

    @Autowired
    SqsClient sqsClient;

    @Value("${app.ffmpeg.path}")
    String ffmpegPath;

    @Value("${app.sqs.queue.transcode-complete-queue}")
    String transcodeCompleteQueue;

    @TempDir
    Path tempDir;

    @Test
    void validMp4_transcodesToStreamBucket() throws Exception {
        var uploadId = UUID.randomUUID();
        var input = tempDir.resolve("input.mp4");
        generateMinimalMp4(input);

        var objectKey = S3ObjectKeys.uploadObjectKey(uploadId, "clip.mp4");
        s3Client.putObject(
                req -> req.bucket(LambdaTestAwsBootstrap.UPLOAD_BUCKET).key(objectKey),
                RequestBody.fromFile(input));

        var payload = MessagingFixtures.sqsLambdaEnvelope(uploadId, "clip.mp4");
        transcodeVideo.accept(payload.getBytes(StandardCharsets.UTF_8));

        var coordination = s3Client.getObjectAsBytes(r -> r.bucket(LambdaTestAwsBootstrap.STREAM_BUCKET)
                .key(dev.iamkavindu.streamapp.lambda.coordination.ProcessingLeaseStore.key(uploadId, uploadId)));
        var mapper = new tools.jackson.databind.ObjectMapper();
        var retained = mapper.readTree(coordination.asUtf8String()).path("completedResult").asText();
        var result = mapper.readTree(retained);
        assertThat(result.path("status").asText()).isEqualTo("PLAY_READY");
        var playlistKey = result.path("playlistKey").asText();
        assertThat(playlistKey).contains("/attempts/", "/executions/").endsWith("/index.m3u8");
        assertThat(headObject(LambdaTestAwsBootstrap.STREAM_BUCKET, playlistKey)).isTrue();
        var mediaKey = playlistKey.substring(0, playlistKey.lastIndexOf('/') + 1) + "media.mp4";
        assertThat(headObject(LambdaTestAwsBootstrap.STREAM_BUCKET, mediaKey)).isTrue();
        assertThat(s3Client.headObject(r -> r.bucket(LambdaTestAwsBootstrap.STREAM_BUCKET)
                .key(mediaKey)).contentType()).isEqualTo("video/mp4");
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            var messages = sqsClient.receiveMessage(r -> r.queueUrl(queueUrl()).maxNumberOfMessages(10)).messages();
            assertThat(messages).anySatisfy(message -> assertThat(message.body()).isEqualTo(retained));
        });
    }

    private void generateMinimalMp4(Path output) throws Exception {
        var outDir = tempDir.resolve("ffmpeg-out");
        Files.createDirectories(outDir);
        var command = List.of(
                ffmpegPath,
                "-y",
                "-f",
                "lavfi",
                "-i",
                "testsrc=duration=1:size=320x240:rate=1",
                "-c:v",
                "libx264",
                "-preset",
                "ultrafast",
                "-t",
                "1",
                output.toString());
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var exit = process.waitFor(60, TimeUnit.SECONDS);
        if (!exit || process.exitValue() != 0) {
            throw new IllegalStateException("Failed to generate test MP4 via FFmpeg");
        }
        assertThat(Files.size(output)).isPositive();
    }

    private boolean headObject(String bucket, String key) {
        try {
            s3Client.headObject(req -> req.bucket(bucket).key(key));
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    private String queueUrl() {
        return sqsClient.getQueueUrl(req -> req.queueName(transcodeCompleteQueue)).queueUrl();
    }
}
