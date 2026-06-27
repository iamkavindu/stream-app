package dev.iamkavindu.streamapp.backend.video;

import dev.iamkavindu.streamapp.backend.aws.AwsMessagingResources;
import dev.iamkavindu.streamapp.backend.support.MessagingFixtures;
import dev.iamkavindu.streamapp.backend.support.MessagingIntegrationTest;
import dev.iamkavindu.streamapp.backend.support.TestData;
import dev.iamkavindu.streamapp.backend.video.model.VideoStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@MessagingIntegrationTest
class SqsTranscodeCompleteListenerIntegrationTest {

    @Autowired
    VideoRepository videoRepository;

    @Autowired
    SqsClient sqsClient;

    @Test
    void playReadyMessage_transitionsStatus() {
        var uploadId = UUID.randomUUID();
        videoRepository.createPendingUploadEntry(uploadId, "demo.mp4", TestData.uniqueShaBytes());
        videoRepository.updateStatus(uploadId, VideoStatus.AWAITING_UPLOAD, VideoStatus.TRANSCODING_IN_PROGRESS);

        publishTranscodeComplete(uploadId, "PLAY_READY");

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> assertThat(
                        videoRepository.findByUploadId(uploadId).orElseThrow().status())
                .isEqualTo(VideoStatus.PLAY_READY));
    }

    @Test
    void failedMessage_transitionsStatus() {
        var uploadId = UUID.randomUUID();
        videoRepository.createPendingUploadEntry(uploadId, "demo.mp4", TestData.uniqueShaBytes());
        videoRepository.updateStatus(uploadId, VideoStatus.AWAITING_UPLOAD, VideoStatus.TRANSCODING_IN_PROGRESS);

        publishTranscodeComplete(uploadId, "FAILED");

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> assertThat(
                        videoRepository.findByUploadId(uploadId).orElseThrow().status())
                .isEqualTo(VideoStatus.FAILED));
    }

    private void publishTranscodeComplete(UUID uploadId, String status) {
        var queueUrl = sqsClient
                .getQueueUrl(req -> req.queueName(AwsMessagingResources.TRANSCODE_COMPLETE_QUEUE))
                .queueUrl();
        sqsClient.sendMessage(req -> req.queueUrl(queueUrl)
                .messageBody(MessagingFixtures.transcodeCompleteMessage(uploadId, status)));
    }
}
