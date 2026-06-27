package dev.iamkavindu.streamapp.backend;

import dev.iamkavindu.streamapp.backend.support.IntegrationTest;
import dev.iamkavindu.streamapp.backend.support.TestData;
import dev.iamkavindu.streamapp.backend.video.VideoRepository;
import dev.iamkavindu.streamapp.backend.video.VideoService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class StaleUploadCleanupTest {

    @Autowired
    VideoRepository videoRepository;

    @Autowired
    VideoService videoService;

    @Test
    void marksStaleAwaitingUploadRowsAsFailed() {
        var staleId = UUID.randomUUID();
        var recentId = UUID.randomUUID();

        videoRepository.createPendingUploadEntry(staleId, "stale.mp4", TestData.uniqueShaBytes());
        videoRepository.createPendingUploadEntry(recentId, "recent.mp4", TestData.uniqueShaBytes());

        assertThat(videoService.markStaleAwaitingUploadsFailed(Duration.ofMinutes(30))).isZero();
        assertThat(videoService.markStaleAwaitingUploadsFailed(Duration.ZERO)).isEqualTo(2);

        assertThat(videoRepository.findByUploadId(staleId).orElseThrow().status().name()).isEqualTo("FAILED");
        assertThat(videoRepository.findByUploadId(recentId).orElseThrow().status().name()).isEqualTo("FAILED");
    }
}
