package dev.iamkavindu.streamapp.backend.video;

import dev.iamkavindu.streamapp.backend.support.IntegrationTest;
import dev.iamkavindu.streamapp.backend.support.TestData;
import dev.iamkavindu.streamapp.backend.video.model.VideoStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
@Transactional
class VideoServiceListenerIntegrationTest {

    @Autowired
    VideoService videoService;

    @Autowired
    VideoRepository videoRepository;

    @Test
    void onUploadComplete_transitionsAwaitingUploadToTranscoding() {
        var uploadId = UUID.randomUUID();
        videoRepository.createPendingUploadEntry(uploadId, "demo.mp4", TestData.uniqueShaBytes());

        var s3Event = """
                {
                  "Records": [{
                    "eventSource": "aws:s3",
                    "s3": {
                      "bucket": { "name": "streamapp-uploads" },
                      "object": { "key": "%s/demo.mp4" }
                    }
                  }]
                }
                """.formatted(uploadId);

        videoService.onUploadComplete(s3Event);

        assertThat(videoRepository.findByUploadId(uploadId).orElseThrow().status())
                .isEqualTo(VideoStatus.TRANSCODING_IN_PROGRESS);
    }

    @Test
    void onTranscodeComplete_transitionsToPlayReady() {
        var uploadId = UUID.randomUUID();
        videoRepository.createPendingUploadEntry(uploadId, "demo.mp4", TestData.uniqueShaBytes());
        videoRepository.updateStatus(uploadId, VideoStatus.AWAITING_UPLOAD, VideoStatus.TRANSCODING_IN_PROGRESS);

        var message = """
                {"uploadId":"%s","status":"PLAY_READY"}
                """.formatted(uploadId);

        videoService.onTranscodeComplete(message);

        assertThat(videoRepository.findByUploadId(uploadId).orElseThrow().status())
                .isEqualTo(VideoStatus.PLAY_READY);
    }

    @Test
    void onTranscodeComplete_transitionsToFailed() {
        var uploadId = UUID.randomUUID();
        videoRepository.createPendingUploadEntry(uploadId, "demo.mp4", TestData.uniqueShaBytes());
        videoRepository.updateStatus(uploadId, VideoStatus.AWAITING_UPLOAD, VideoStatus.TRANSCODING_IN_PROGRESS);

        var message = """
                {"uploadId":"%s","status":"FAILED"}
                """.formatted(uploadId);

        videoService.onTranscodeComplete(message);

        assertThat(videoRepository.findByUploadId(uploadId).orElseThrow().status())
                .isEqualTo(VideoStatus.FAILED);
    }
}
