package dev.iamkavindu.streamapp.backend.exception;

import dev.iamkavindu.streamapp.backend.video.VideoController;
import dev.iamkavindu.streamapp.backend.video.VideoService;
import dev.iamkavindu.streamapp.backend.video.model.VideoStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(VideoController.class)
@Import(BackendExceptionHandler.class)
class BackendExceptionHandlerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    VideoService videoService;

    @Test
    void duplicateUpload_returnsProblemDetail() throws Exception {
        when(videoService.createSignedUploadUrl(any(), any()))
                .thenThrow(new DuplicateVideoUploadException("demo.mp4"));

        mockMvc.perform(post("/api/v1/videos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fileName":"demo.mp4","sha256Hex":"%s"}
                                """.formatted("a".repeat(64))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://stream-app.dev/problems/duplicate-video-upload"))
                .andExpect(jsonPath("$.title").value("Duplicate Video Upload"));
    }

    @Test
    void videoNotFound_returnsProblemDetail() throws Exception {
        var uploadId = UUID.randomUUID();
        when(videoService.createSignedGetUrl(uploadId)).thenThrow(new VideoNotFoundException(uploadId));

        mockMvc.perform(get("/api/v1/videos/{uploadId}/signed-url", uploadId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://stream-app.dev/problems/video-not-found"));
    }

    @Test
    void videoNotReady_returnsProblemDetail() throws Exception {
        var uploadId = UUID.randomUUID();
        when(videoService.createSignedGetUrl(uploadId))
                .thenThrow(new VideoNotReadyException(uploadId, VideoStatus.AWAITING_UPLOAD));

        mockMvc.perform(get("/api/v1/videos/{uploadId}/signed-url", uploadId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://stream-app.dev/problems/video-not-ready"));
    }
}
