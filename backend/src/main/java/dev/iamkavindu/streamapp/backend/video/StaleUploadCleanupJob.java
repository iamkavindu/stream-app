package dev.iamkavindu.streamapp.backend.video;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConditionalOnProperty(name = "streamapp.upload.cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class StaleUploadCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(StaleUploadCleanupJob.class);

    private final VideoService videoService;
    private final Duration awaitingUploadTtl;

    public StaleUploadCleanupJob(
            VideoService videoService,
            @Value("${streamapp.upload.awaiting-upload-ttl:30m}") Duration awaitingUploadTtl) {
        this.videoService = videoService;
        this.awaitingUploadTtl = awaitingUploadTtl;
    }

    @Scheduled(fixedDelayString = "${streamapp.upload.cleanup-interval:5m}")
    public void cleanupStaleAwaitingUploads() {
        var updated = videoService.markStaleAwaitingUploadsFailed(awaitingUploadTtl);
        if (updated > 0) {
            log.info("Marked {} stale AWAITING_UPLOAD row(s) as FAILED (ttl={})", updated, awaitingUploadTtl);
        }
    }
}
