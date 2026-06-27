package dev.iamkavindu.streamapp.backend.video;

import dev.iamkavindu.streamapp.backend.video.model.SignedUrlCreatedRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(VideoController.class)
class VideoControllerValidationTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    VideoService videoService;

    private static final String VALID_SHA =
            "a".repeat(64);

    @Test
    void rejectsBlankFileName() throws Exception {
        mockMvc.perform(post("/api/v1/videos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fileName":"","sha256Hex":"%s"}
                                """.formatted(VALID_SHA)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.type").value("https://stream-app.dev/problems/validation-failed"));

        verify(videoService, never()).createSignedUploadUrl(anyString(), anyString());
    }

    @Test
    void rejectsInvalidSha256Hex() throws Exception {
        mockMvc.perform(post("/api/v1/videos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fileName":"demo.mp4","sha256Hex":"not-a-valid-hash"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").isString());

        verify(videoService, never()).createSignedUploadUrl(anyString(), anyString());
    }

    @Test
    void acceptsValidRequest() throws Exception {
        when(videoService.createSignedUploadUrl("demo.mp4", VALID_SHA))
                .thenReturn(new SignedUrlCreatedRecord(
                        UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
                        "http://localhost:4566/signed",
                        "demo.mp4"));

        mockMvc.perform(post("/api/v1/videos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fileName":"demo.mp4","sha256Hex":"%s"}
                                """.formatted(VALID_SHA)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fileName").value("demo.mp4"));
    }
}
