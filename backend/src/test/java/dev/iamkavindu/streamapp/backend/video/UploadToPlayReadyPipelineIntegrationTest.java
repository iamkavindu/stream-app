package dev.iamkavindu.streamapp.backend.video;

import dev.iamkavindu.streamapp.backend.aws.AwsMessagingResources;
import dev.iamkavindu.streamapp.backend.support.MessagingFixtures;
import dev.iamkavindu.streamapp.backend.support.MessagingIntegrationTest;
import dev.iamkavindu.streamapp.backend.support.PresignedUrlTestSupport;
import dev.iamkavindu.streamapp.backend.support.TestAwsBootstrap;
import dev.iamkavindu.streamapp.backend.support.TestData;
import io.floci.testcontainers.FlociContainer;
import dev.iamkavindu.streamapp.backend.video.model.VideoStatus;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@MessagingIntegrationTest
@AutoConfigureMockMvc
@Tag("pipeline")
class UploadToPlayReadyPipelineIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    VideoRepository videoRepository;

    @Autowired
    S3Client s3Client;

    @Autowired
    SqsClient sqsClient;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    FlociContainer flociContainer;

    @Test
    void uploadThroughTranscodeComplete_yieldsSignedStreamUrl() throws Exception {
        var sha = TestData.uniqueShaHex();
        var createResult = mockMvc.perform(post("/api/v1/videos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fileName":"pipeline.mp4","sha256Hex":"%s"}
                                """.formatted(sha)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode created = objectMapper.readTree(createResult.getResponse().getContentAsString());
        var uploadId = UUID.fromString(created.get("uploadId").asText());
        var signedPutUrl = PresignedUrlTestSupport.toPathStyleHttpUrl(
                created.get("signedUrl").asText(), flociContainer);

        var putResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create(signedPutUrl))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[] {0x00, 0x01, 0x02}))
                        .header("Content-Type", "video/mp4")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(putResponse.statusCode()).isBetween(200, 204);

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> assertThat(
                        videoRepository.findByUploadId(uploadId).orElseThrow().status())
                .isEqualTo(VideoStatus.TRANSCODING_IN_PROGRESS));

        s3Client.putObject(
                req -> req.bucket(TestAwsBootstrap.STREAM_BUCKET)
                        .key(uploadId + "/index.m3u8"),
                RequestBody.fromString("#EXTM3U\n"));

        var completeQueueUrl = sqsClient
                .getQueueUrl(req -> req.queueName(AwsMessagingResources.TRANSCODE_COMPLETE_QUEUE))
                .queueUrl();
        sqsClient.sendMessage(req -> req.queueUrl(completeQueueUrl)
                .messageBody(MessagingFixtures.transcodeCompleteMessage(uploadId, "PLAY_READY")));

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> assertThat(
                        videoRepository.findByUploadId(uploadId).orElseThrow().status())
                .isEqualTo(VideoStatus.PLAY_READY));

        mockMvc.perform(get("/api/v1/videos/{uploadId}/signed-url", uploadId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signedUrl").isString())
                .andExpect(jsonPath("$.objectKey").value(uploadId + "/index.m3u8"));
    }
}
