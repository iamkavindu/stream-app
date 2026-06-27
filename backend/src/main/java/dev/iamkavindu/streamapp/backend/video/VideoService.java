package dev.iamkavindu.streamapp.backend.video;



import tools.jackson.databind.ObjectMapper;

import dev.iamkavindu.streamapp.backend.aws.AwsMessagingResources;

import dev.iamkavindu.streamapp.backend.aws.S3ObjectKeys;

import dev.iamkavindu.streamapp.backend.aws.S3Service;

import dev.iamkavindu.streamapp.backend.aws.S3UploadEventParser;

import dev.iamkavindu.streamapp.backend.exception.DuplicateVideoUploadException;

import dev.iamkavindu.streamapp.backend.exception.VideoNotFoundException;

import dev.iamkavindu.streamapp.backend.exception.VideoNotReadyException;

import dev.iamkavindu.streamapp.backend.video.model.SignedGetUrlRecord;

import dev.iamkavindu.streamapp.backend.video.model.SignedUrlCreatedRecord;

import dev.iamkavindu.streamapp.backend.video.model.VideoRecord;

import dev.iamkavindu.streamapp.backend.video.model.VideoStatus;

import dev.iamkavindu.streamapp.backend.video.model.VideoStatusUpdateRecord;

import io.awspring.cloud.sqs.annotation.SqsListener;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

import org.springframework.dao.DuplicateKeyException;

import org.springframework.stereotype.Service;

import org.springframework.transaction.annotation.Propagation;

import org.springframework.transaction.annotation.Transactional;



import java.time.Duration;

import java.util.HexFormat;

import java.util.List;

import java.util.UUID;



import static io.awspring.cloud.sqs.annotation.SqsListenerAcknowledgementMode.ON_SUCCESS;



@Service

public class VideoService {



    private static final Logger log = LoggerFactory.getLogger(VideoService.class);



    private final VideoRepository videoRepository;

    private final S3Service s3Service;

    private final S3UploadEventParser uploadEventParser;

    private final ObjectMapper objectMapper;



    public VideoService(

            VideoRepository videoRepository,

            S3Service s3Service,

            S3UploadEventParser uploadEventParser,

            ObjectMapper objectMapper) {

        this.videoRepository = videoRepository;

        this.s3Service = s3Service;

        this.uploadEventParser = uploadEventParser;

        this.objectMapper = objectMapper;

    }



    @Transactional(

            propagation = Propagation.REQUIRES_NEW,

            rollbackFor = DuplicateVideoUploadException.class)

    public SignedUrlCreatedRecord createSignedUploadUrl(String fileName, String sha256Hex) {

        var uploadId = UUID.randomUUID();

        var shaHex = HexFormat.of().parseHex(sha256Hex);



        try {

            videoRepository.createPendingUploadEntry(uploadId, fileName, shaHex);

        } catch (DuplicateKeyException e) {

            throw new DuplicateVideoUploadException(fileName);

        }



        var signedUrl = s3Service.createSignedPutUrl(uploadId, fileName);

        return new SignedUrlCreatedRecord(uploadId, signedUrl, fileName);

    }



    public List<VideoRecord> listVideos() {

        return videoRepository.findAll();

    }

    @Transactional

    public int markStaleAwaitingUploadsFailed(Duration ttl) {

        return videoRepository.markStaleAwaitingUploadsFailed(ttl);

    }



    public SignedGetUrlRecord createSignedGetUrl(UUID uploadId) {

        var video = videoRepository.findByUploadId(uploadId)

                .orElseThrow(() -> new VideoNotFoundException(uploadId));



        if (video.status() != VideoStatus.PLAY_READY) {

            throw new VideoNotReadyException(uploadId, video.status());

        }



        var objectKey = S3ObjectKeys.streamPlaylistKey(uploadId);

        var signedUrl = s3Service.createSignedPlaylistGetUrl(uploadId);

        return new SignedGetUrlRecord(uploadId, objectKey, signedUrl);

    }



    @SqsListener(

            acknowledgementMode = ON_SUCCESS,

            queueNames = AwsMessagingResources.UPLOAD_BACKEND_QUEUE)

    @Transactional

    public void onUploadComplete(String message) {

        var event = uploadEventParser.parseSqsMessage(message);

        if (event.isEmpty()) {

            log.warn("Ignoring unparseable upload-complete message");

            return;

        }



        var uploadId = event.get().uploadId();

        var updated = videoRepository.updateStatus(

                uploadId, VideoStatus.AWAITING_UPLOAD, VideoStatus.TRANSCODING_IN_PROGRESS);



        if (updated) {

            log.info("Upload complete for {} — status set to TRANSCODING_IN_PROGRESS", uploadId);

        } else {

            log.warn(

                    "Upload complete for {} — no row updated (expected AWAITING_UPLOAD)",

                    uploadId);

        }

    }



    @SqsListener(

            acknowledgementMode = ON_SUCCESS,

            queueNames = AwsMessagingResources.TRANSCODE_COMPLETE_QUEUE)

    @Transactional

    public void onTranscodeComplete(String message) {

        VideoStatusUpdateRecord update;

        try {

            update = objectMapper.readValue(message, VideoStatusUpdateRecord.class);

        } catch (Exception e) {

            log.warn("Ignoring unparseable transcode-complete message: {}", e.getMessage());

            return;

        }



        if (update.status() != VideoStatus.PLAY_READY && update.status() != VideoStatus.FAILED) {

            log.warn(

                    "Ignoring transcode-complete message for {} with unexpected status {}",

                    update.uploadId(),

                    update.status());

            return;

        }



        var updated = videoRepository.updateStatus(

                update.uploadId(), VideoStatus.TRANSCODING_IN_PROGRESS, update.status());



        if (updated) {

            log.info("Transcode complete for {} — status set to {}", update.uploadId(), update.status());

        } else {

            log.warn(

                    "Transcode complete for {} — no row updated (expected TRANSCODING_IN_PROGRESS)",

                    update.uploadId());

        }

    }

}


