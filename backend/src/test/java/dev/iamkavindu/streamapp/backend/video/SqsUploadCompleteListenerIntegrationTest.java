package dev.iamkavindu.streamapp.backend.video;

import dev.iamkavindu.streamapp.backend.aws.AwsMessagingResources;
import dev.iamkavindu.streamapp.backend.support.MessagingFixtures;
import dev.iamkavindu.streamapp.backend.support.MessagingIntegrationTest;
import dev.iamkavindu.streamapp.backend.support.TestAwsBootstrap;
import dev.iamkavindu.streamapp.backend.support.TestData;
import dev.iamkavindu.streamapp.backend.video.model.VideoStatus;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@MessagingIntegrationTest
class SqsUploadCompleteListenerIntegrationTest {

    @Autowired
    VideoRepository videoRepository;

    @Autowired
    S3Client s3Client;

    @Autowired
    SqsClient sqsClient;

    @Test
    @Tag("slow")
    void s3Upload_triggersListenerAndTransitionsToTranscoding() {
        var uploadId = UUID.randomUUID();
        var fileName = "demo.mp4";
        videoRepository.createPendingUploadEntry(uploadId, fileName, TestData.uniqueShaBytes());

        s3Client.putObject(
                req -> req.bucket(TestAwsBootstrap.UPLOAD_BUCKET)
                        .key(uploadId + "/" + fileName),
                RequestBody.fromBytes(new byte[] {0x00, 0x01, 0x02}));

        await().atMost(60, TimeUnit.SECONDS).untilAsserted(() -> assertThat(
                        videoRepository.findByUploadId(uploadId).orElseThrow().status())
                .isEqualTo(VideoStatus.TRANSCODING_IN_PROGRESS));
    }

    @Test
    void directSqsPublish_triggersListener() {
        var uploadId = UUID.randomUUID();
        videoRepository.createPendingUploadEntry(uploadId, "demo.mp4", TestData.uniqueShaBytes());

        var queueUrl = sqsClient
                .getQueueUrl(req -> req.queueName(AwsMessagingResources.UPLOAD_BACKEND_QUEUE))
                .queueUrl();
        sqsClient.sendMessage(req -> req.queueUrl(queueUrl)
                .messageBody(MessagingFixtures.snsNotificationEnvelope(uploadId, "demo.mp4")));

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> assertThat(
                        videoRepository.findByUploadId(uploadId).orElseThrow().status())
                .isEqualTo(VideoStatus.TRANSCODING_IN_PROGRESS));
    }
}
