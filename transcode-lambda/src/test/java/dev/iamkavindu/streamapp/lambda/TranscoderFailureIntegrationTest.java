package dev.iamkavindu.streamapp.lambda;

import dev.iamkavindu.streamapp.lambda.aws.S3ObjectKeys;
import dev.iamkavindu.streamapp.lambda.support.LambdaIntegrationTest;
import dev.iamkavindu.streamapp.lambda.support.LambdaTestAwsBootstrap;
import dev.iamkavindu.streamapp.lambda.support.MessagingFixtures;
import dev.iamkavindu.streamapp.lambda.transcode.HlsTranscodeCommand;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@LambdaIntegrationTest
class TranscoderFailureIntegrationTest {

    @Autowired
    Consumer<byte[]> transcodeVideo;

    @Autowired
    S3Client s3Client;

    @Autowired
    SqsClient sqsClient;

    @Value("${app.sqs.queue.transcode-complete-queue}")
    String transcodeCompleteQueue;

    @Test
    void invalidUploadBytes_publishesFailedStatus() {
        var uploadId = UUID.randomUUID();
        var objectKey = S3ObjectKeys.uploadObjectKey(uploadId, "bad.mp4");
        s3Client.putObject(
                req -> req.bucket(LambdaTestAwsBootstrap.UPLOAD_BUCKET).key(objectKey),
                RequestBody.fromBytes(new byte[] {0x00, 0x01}));

        var payload = MessagingFixtures.sqsLambdaEnvelope(uploadId, "bad.mp4");
        transcodeVideo.accept(payload.getBytes(StandardCharsets.UTF_8));

        await().atMost(60, TimeUnit.SECONDS).untilAsserted(() -> {
            var messages = sqsClient.receiveMessage(req -> req.queueUrl(queueUrl()).maxNumberOfMessages(1))
                    .messages();
            assertThat(messages).isNotEmpty();
            assertThat(messages.getFirst().body()).contains("\"status\":\"FAILED\"");
            assertThat(messages.getFirst().body()).contains(uploadId.toString());
        });
    }

    private String queueUrl() {
        return sqsClient.getQueueUrl(req -> req.queueName(transcodeCompleteQueue)).queueUrl();
    }
}
