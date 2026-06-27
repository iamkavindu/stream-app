package dev.iamkavindu.streamapp.backend.video;

import dev.iamkavindu.streamapp.backend.aws.S3Service;
import dev.iamkavindu.streamapp.backend.aws.S3UploadEventParser;
import dev.iamkavindu.streamapp.backend.exception.DuplicateVideoUploadException;
import dev.iamkavindu.streamapp.backend.exception.VideoNotFoundException;
import dev.iamkavindu.streamapp.backend.exception.VideoNotReadyException;
import dev.iamkavindu.streamapp.backend.support.TestData;
import dev.iamkavindu.streamapp.backend.video.model.VideoRecord;
import dev.iamkavindu.streamapp.backend.video.model.VideoStatus;
import dev.iamkavindu.streamapp.backend.video.model.VideoStatusUpdateRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoServiceUnitTest {

    @Mock
    VideoRepository videoRepository;

    @Mock
    S3Service s3Service;

    @Mock
    S3UploadEventParser uploadEventParser;

    @InjectMocks
    VideoService videoService;

    ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        videoService = new VideoService(videoRepository, s3Service, uploadEventParser, objectMapper);
    }

    @Test
    void createSignedUploadUrl_returnsPresignedUrl() {
        when(s3Service.createSignedPutUrl(any(), eq("demo.mp4"))).thenReturn("http://floci/signed");

        var result = videoService.createSignedUploadUrl("demo.mp4", TestData.VALID_SHA256_HEX);

        assertThat(result.signedUrl()).isEqualTo("http://floci/signed");
        assertThat(result.fileName()).isEqualTo("demo.mp4");
        verify(videoRepository).createPendingUploadEntry(any(), eq("demo.mp4"), any());
    }

    @Test
    void createSignedUploadUrl_duplicateHashThrows() {
        doThrow(new DuplicateKeyException("duplicate"))
                .when(videoRepository)
                .createPendingUploadEntry(any(), eq("demo.mp4"), any());

        assertThatThrownBy(() -> videoService.createSignedUploadUrl("demo.mp4", TestData.VALID_SHA256_HEX))
                .isInstanceOf(DuplicateVideoUploadException.class);

        verify(s3Service, never()).createSignedPutUrl(any(), any());
    }

    @Test
    void createSignedGetUrl_requiresPlayReady() {
        var uploadId = UUID.randomUUID();
        when(videoRepository.findByUploadId(uploadId))
                .thenReturn(Optional.of(videoRecord(uploadId, VideoStatus.AWAITING_UPLOAD)));

        assertThatThrownBy(() -> videoService.createSignedGetUrl(uploadId))
                .isInstanceOf(VideoNotReadyException.class);
    }

    @Test
    void createSignedGetUrl_notFound() {
        var uploadId = UUID.randomUUID();
        when(videoRepository.findByUploadId(uploadId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> videoService.createSignedGetUrl(uploadId))
                .isInstanceOf(VideoNotFoundException.class);
    }

    @Test
    void createSignedGetUrl_playReady() {
        var uploadId = UUID.randomUUID();
        when(videoRepository.findByUploadId(uploadId))
                .thenReturn(Optional.of(videoRecord(uploadId, VideoStatus.PLAY_READY)));
        when(s3Service.createSignedPlaylistGetUrl(uploadId)).thenReturn("http://floci/playlist");

        var result = videoService.createSignedGetUrl(uploadId);

        assertThat(result.signedUrl()).isEqualTo("http://floci/playlist");
        assertThat(result.objectKey()).endsWith("/index.m3u8");
    }

    @Test
    void onUploadComplete_transitionsStatus() {
        var uploadId = UUID.randomUUID();
        when(uploadEventParser.parseSqsMessage("msg")).thenReturn(Optional.of(
                new S3UploadEventParser.S3UploadEvent(uploadId, "streamapp-uploads", uploadId + "/demo.mp4")));
        when(videoRepository.updateStatus(uploadId, VideoStatus.AWAITING_UPLOAD, VideoStatus.TRANSCODING_IN_PROGRESS))
                .thenReturn(true);

        videoService.onUploadComplete("msg");

        verify(videoRepository).updateStatus(
                uploadId, VideoStatus.AWAITING_UPLOAD, VideoStatus.TRANSCODING_IN_PROGRESS);
    }

    @Test
    void onUploadComplete_ignoresUnparseableMessage() {
        when(uploadEventParser.parseSqsMessage("bad")).thenReturn(Optional.empty());

        videoService.onUploadComplete("bad");

        verify(videoRepository, never()).updateStatus(any(), any(), any());
    }

    @Test
    void onTranscodeComplete_playReady() {
        var uploadId = UUID.randomUUID();
        var message = """
                {"uploadId":"%s","status":"PLAY_READY"}
                """.formatted(uploadId);
        when(videoRepository.updateStatus(uploadId, VideoStatus.TRANSCODING_IN_PROGRESS, VideoStatus.PLAY_READY))
                .thenReturn(true);

        videoService.onTranscodeComplete(message);

        verify(videoRepository).updateStatus(
                uploadId, VideoStatus.TRANSCODING_IN_PROGRESS, VideoStatus.PLAY_READY);
    }

    @Test
    void onTranscodeComplete_ignoresUnexpectedStatus() {
        var uploadId = UUID.randomUUID();
        var message = """
                {"uploadId":"%s","status":"AWAITING_UPLOAD"}
                """.formatted(uploadId);

        videoService.onTranscodeComplete(message);

        verify(videoRepository, never()).updateStatus(any(), any(), any());
    }

    private static VideoRecord videoRecord(UUID uploadId, VideoStatus status) {
        var now = OffsetDateTime.ofInstant(Instant.EPOCH, ZoneId.of("UTC"));
        return new VideoRecord(uploadId, status, "demo.mp4", now, now);
    }
}
