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

        await().atMost(120, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(headObject(LambdaTestAwsBootstrap.STREAM_BUCKET, S3ObjectKeys.streamPlaylistKey(uploadId)))
                    .isTrue();
            assertThat(headObject(LambdaTestAwsBootstrap.STREAM_BUCKET, S3ObjectKeys.streamMediaKey(uploadId)))
                    .isTrue();
        });

        var messages = sqsClient.receiveMessage(req -> req.queueUrl(queueUrl()).maxNumberOfMessages(1))
                .messages();
        assertThat(messages).isNotEmpty();
        assertThat(messages.getFirst().body()).contains("\"status\":\"PLAY_READY\"");
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
