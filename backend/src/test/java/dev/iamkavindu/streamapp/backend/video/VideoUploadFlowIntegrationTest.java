package dev.iamkavindu.streamapp.backend.video;

import dev.iamkavindu.streamapp.backend.support.IntegrationTest;
import dev.iamkavindu.streamapp.backend.support.PresignedUrlTestSupport;
import dev.iamkavindu.streamapp.backend.support.TestAwsBootstrap;
import dev.iamkavindu.streamapp.backend.support.TestData;
import io.floci.testcontainers.FlociContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
@AutoConfigureMockMvc
class VideoUploadFlowIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    S3Client s3Client;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    FlociContainer flociContainer;

    @Test
    void createSignedUpload_thenPutToS3_storesObject() throws Exception {
        var sha = TestData.uniqueShaHex();
        var result = mockMvc.perform(post("/api/v1/videos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fileName":"flow.mp4","sha256Hex":"%s"}
                                """.formatted(sha)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.signedUrl").isString())
                .andExpect(jsonPath("$.uploadId").isString())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        var uploadId = body.get("uploadId").asText();
        var signedUrl = PresignedUrlTestSupport.toPathStyleHttpUrl(
                body.get("signedUrl").asText(), flociContainer);

        var response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create(signedUrl))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[] {0x00, 0x00, 0x01}))
                        .header("Content-Type", "video/mp4")
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isBetween(200, 204);
        assertThat(s3Client.headObject(req -> req.bucket(TestAwsBootstrap.UPLOAD_BUCKET)
                        .key(uploadId + "/flow.mp4"))
                .contentLength())
                .isEqualTo(3);
    }
}
