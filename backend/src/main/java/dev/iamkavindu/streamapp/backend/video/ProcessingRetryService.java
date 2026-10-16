package dev.iamkavindu.streamapp.backend.video;

import dev.iamkavindu.streamapp.backend.aws.S3ObjectKeys;
import dev.iamkavindu.streamapp.backend.aws.S3Service;
import dev.iamkavindu.streamapp.backend.exception.ProcessingNotRetryableException;
import dev.iamkavindu.streamapp.backend.exception.VideoNotFoundException;
import dev.iamkavindu.streamapp.backend.video.model.ProcessingJobRecord;
import dev.iamkavindu.streamapp.backend.video.model.ProcessingRetryRecord;
import dev.iamkavindu.streamapp.backend.video.model.VideoStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.util.UUID;

@Service
public class ProcessingRetryService {
    private final VideoRepository videos;
    private final S3Service storage;
    private final ObjectMapper mapper;

    public ProcessingRetryService(VideoRepository videos, S3Service storage, ObjectMapper mapper) {
        this.videos = videos;
        this.storage = storage;
        this.mapper = mapper;
    }

    @Transactional
    public ProcessingRetryRecord retry(UUID uploadId) {
        var video = videos.findByUploadIdForUpdate(uploadId)
                .orElseThrow(() -> new VideoNotFoundException(uploadId));
        if (video.status() != VideoStatus.FAILED) {
            throw new ProcessingNotRetryableException("Only failed processing can be retried; current status is " + video.status());
        }
        if (!storage.uploadSourceExists(uploadId, video.fileName())) {
            throw new ProcessingNotRetryableException("The original upload is missing; processing cannot be retried without its source.");
        }
        var attemptId = UUID.randomUUID();
        var job = new ProcessingJobRecord(1, "TRANSCODE", uploadId, attemptId,
                S3ObjectKeys.uploadObjectKey(uploadId, video.fileName()));
        videos.createProcessingRetry(uploadId, attemptId, mapper.writeValueAsString(job));
        return new ProcessingRetryRecord(uploadId, attemptId, VideoStatus.TRANSCODING_IN_PROGRESS);
    }
}
