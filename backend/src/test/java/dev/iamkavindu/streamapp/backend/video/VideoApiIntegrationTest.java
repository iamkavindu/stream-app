package dev.iamkavindu.streamapp.backend.video;

import dev.iamkavindu.streamapp.backend.support.IntegrationTest;
import dev.iamkavindu.streamapp.backend.support.TestData;
import dev.iamkavindu.streamapp.backend.video.model.VideoStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
@AutoConfigureMockMvc
class VideoApiIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    VideoRepository videoRepository;

    @Autowired
    S3Client s3Client;

    @Test
    void createSignedUpload_returns201() throws Exception {
        mockMvc.perform(post("/api/v1/videos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fileName":"demo.mp4","sha256Hex":"%s"}
                                """.formatted(TestData.uniqueShaHex())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fileName").value("demo.mp4"))
                .andExpect(jsonPath("$.signedUrl").isString())
                .andExpect(jsonPath("$.uploadId").isString());
    }

    @Test
    void createSignedUpload_duplicateSha256Returns409() throws Exception {
        var sha = TestData.uniqueShaHex();
        mockMvc.perform(post("/api/v1/videos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fileName":"first.mp4","sha256Hex":"%s"}
                                """.formatted(sha)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/videos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fileName":"second.mp4","sha256Hex":"%s"}
                                """.formatted(sha)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://stream-app.dev/problems/duplicate-video-upload"));
    }

    @Test
    void listVideos_includesCreatedRow() throws Exception {
        var uploadId = UUID.randomUUID();
        videoRepository.createPendingUploadEntry(uploadId, "listed.mp4", TestData.uniqueShaBytes());

        mockMvc.perform(get("/api/v1/videos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.uploadId == '%s')].fileName".formatted(uploadId)).value("listed.mp4"));
    }

    @Test
    void signedUrl_notFound() throws Exception {
        var missing = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/videos/{uploadId}/signed-url", missing))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://stream-app.dev/problems/video-not-found"));
    }

    @Test
    void signedUrl_notReady() throws Exception {
        var uploadId = UUID.randomUUID();
        videoRepository.createPendingUploadEntry(uploadId, "waiting.mp4", TestData.uniqueShaBytes());

        mockMvc.perform(get("/api/v1/videos/{uploadId}/signed-url", uploadId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://stream-app.dev/problems/video-not-ready"));
    }

    @Test
    void signedUrl_playReady() throws Exception {
        var uploadId = UUID.randomUUID();
        videoRepository.createPendingUploadEntry(uploadId, "ready.mp4", TestData.uniqueShaBytes());
        videoRepository.updateStatus(uploadId, VideoStatus.AWAITING_UPLOAD, VideoStatus.TRANSCODING_IN_PROGRESS);
        videoRepository.updateStatus(uploadId, VideoStatus.TRANSCODING_IN_PROGRESS, VideoStatus.PLAY_READY);

        s3Client.putObject(
                b -> b.bucket("streamapp-streams").key(uploadId + "/index.m3u8"),
                RequestBody.fromString("#EXTM3U\n"));

        mockMvc.perform(get("/api/v1/videos/{uploadId}/signed-url", uploadId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.objectKey").value(uploadId + "/index.m3u8"))
                .andExpect(jsonPath("$.signedUrl").isString());
    }
    @Test
    void retryUpload_missingSourceReturnsUrlForSameId() throws Exception {
        var uploadId = UUID.randomUUID();
        videoRepository.createPendingUploadEntry(uploadId, "retry.mp4", TestData.uniqueShaBytes());
        mockMvc.perform(post("/api/v1/videos/{uploadId}/upload-url", uploadId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadId").value(uploadId.toString()))
                .andExpect(jsonPath("$.sourceReceived").value(false))
                .andExpect(jsonPath("$.signedUrl").isString());
    }

    @Test
    void retryUpload_presentSourceSkipsTransferAndObservesUpload() throws Exception {
        var uploadId = UUID.randomUUID();
        videoRepository.createPendingUploadEntry(uploadId, "received.mp4", TestData.uniqueShaBytes());
        s3Client.putObject(b -> b.bucket("streamapp-uploads").key(uploadId + "/received.mp4"),
                RequestBody.fromString("received"));
        mockMvc.perform(post("/api/v1/videos/{uploadId}/upload-url", uploadId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceReceived").value(true))
                .andExpect(jsonPath("$.signedUrl").doesNotExist());
        assertThat(videoRepository.findByUploadId(uploadId).orElseThrow().status())
                .isEqualTo(VideoStatus.TRANSCODING_IN_PROGRESS);
    }

    @Test
    void retryUpload_unknownReturns404AndFailedReturns409() throws Exception {
        mockMvc.perform(post("/api/v1/videos/{uploadId}/upload-url", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://stream-app.dev/problems/video-not-found"));
        var uploadId = UUID.randomUUID();
        videoRepository.createPendingUploadEntry(uploadId, "failed.mp4", TestData.uniqueShaBytes());
        videoRepository.updateStatus(uploadId, VideoStatus.AWAITING_UPLOAD, VideoStatus.FAILED);
        mockMvc.perform(post("/api/v1/videos/{uploadId}/upload-url", uploadId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://stream-app.dev/problems/upload-not-retryable"));
    }
}
