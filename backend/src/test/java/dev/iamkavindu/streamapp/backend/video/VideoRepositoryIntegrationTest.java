package dev.iamkavindu.streamapp.backend.video;

import dev.iamkavindu.streamapp.backend.support.IntegrationTest;
import dev.iamkavindu.streamapp.backend.support.TestData;
import dev.iamkavindu.streamapp.backend.video.model.VideoStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
@Transactional
class VideoRepositoryIntegrationTest {

    @Autowired
    VideoRepository videoRepository;

    @Test
    void createPendingUploadEntry_persistsAwaitingUpload() {
        var uploadId = UUID.randomUUID();
        videoRepository.createPendingUploadEntry(uploadId, "demo.mp4", TestData.uniqueShaBytes());

        var video = videoRepository.findByUploadId(uploadId).orElseThrow();
        assertThat(video.status()).isEqualTo(VideoStatus.AWAITING_UPLOAD);
        assertThat(video.fileName()).isEqualTo("demo.mp4");
    }

    @Test
    void updateStatus_casTransition() {
        var uploadId = UUID.randomUUID();
        videoRepository.createPendingUploadEntry(uploadId, "demo.mp4", TestData.uniqueShaBytes());

        var updated = videoRepository.updateStatus(
                uploadId, VideoStatus.AWAITING_UPLOAD, VideoStatus.TRANSCODING_IN_PROGRESS);

        assertThat(updated).isTrue();
        assertThat(videoRepository.findByUploadId(uploadId).orElseThrow().status())
                .isEqualTo(VideoStatus.TRANSCODING_IN_PROGRESS);
    }

    @Test
    void updateStatus_wrongExpectedStatus() {
        var uploadId = UUID.randomUUID();
        videoRepository.createPendingUploadEntry(uploadId, "demo.mp4", TestData.uniqueShaBytes());

        var updated = videoRepository.updateStatus(
                uploadId, VideoStatus.TRANSCODING_IN_PROGRESS, VideoStatus.PLAY_READY);

        assertThat(updated).isFalse();
        assertThat(videoRepository.findByUploadId(uploadId).orElseThrow().status())
                .isEqualTo(VideoStatus.AWAITING_UPLOAD);
    }

    @Test
    void createPendingUploadEntry_duplicateSha256() {
        var sha = TestData.uniqueShaBytes();
        videoRepository.createPendingUploadEntry(UUID.randomUUID(), "first.mp4", sha);

        assertThatThrownBy(() ->
                        videoRepository.createPendingUploadEntry(UUID.randomUUID(), "second.mp4", sha))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void findAll_ordersByCreatedAtDesc() {
        var older = UUID.randomUUID();
        var newer = UUID.randomUUID();
        videoRepository.createPendingUploadEntry(older, "older.mp4", TestData.uniqueShaBytes());
        videoRepository.createPendingUploadEntry(newer, "newer.mp4", TestData.uniqueShaBytes());

        var videos = videoRepository.findAll();
        assertThat(videos).hasSizeGreaterThanOrEqualTo(2);
        assertThat(videos.getFirst().uploadId()).isEqualTo(newer);
    }
}
