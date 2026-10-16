package dev.iamkavindu.streamapp.lambda;

import dev.iamkavindu.streamapp.lambda.aws.S3ObjectKeys;
import dev.iamkavindu.streamapp.lambda.aws.S3UploadEventParser;
import dev.iamkavindu.streamapp.lambda.model.ProcessingJobRecord;
import dev.iamkavindu.streamapp.lambda.model.VideoStatus;
import dev.iamkavindu.streamapp.lambda.model.VideoStatusUpdateRecord;
import dev.iamkavindu.streamapp.lambda.transcode.HlsTranscodeCommand;
import dev.iamkavindu.streamapp.lambda.coordination.ProcessingLeaseStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.time.Clock;
import java.time.Duration;

@Component
public class Transcoder {
    private static final Logger log = LoggerFactory.getLogger(Transcoder.class);

    private final S3Client s3Client;
    private final SqsClient sqsClient;
    private final ObjectMapper objectMapper;
    private final S3UploadEventParser uploadEventParser;
    private final String uploadBucket;
    private final String streamBucket;
    private final String ffmpegPath;
    private final String transcodeCompleteQueueName;
    private final ProcessingLeaseStore leaseStore;

    private String transcodeCompleteQueueUrl;

    @Autowired
    public Transcoder(
            S3Client s3Client,
            SqsClient sqsClient,
            ObjectMapper objectMapper,
            S3UploadEventParser uploadEventParser,
            @Value("${app.bucket-name.upload-bucket}") String uploadBucket,
            @Value("${app.bucket-name.stream-bucket}") String streamBucket,
            @Value("${app.ffmpeg.path}") String ffmpegPath,
            @Value("${app.sqs.queue.transcode-complete-queue}") String transcodeCompleteQueueName) {
        this(s3Client, sqsClient, objectMapper, uploadEventParser, uploadBucket, streamBucket,
                ffmpegPath, transcodeCompleteQueueName,
                new ProcessingLeaseStore(s3Client, objectMapper, streamBucket, Clock.systemUTC(), Duration.ofMinutes(10)));
    }

    Transcoder(S3Client s3Client, SqsClient sqsClient, ObjectMapper objectMapper,
            S3UploadEventParser uploadEventParser, String uploadBucket, String streamBucket,
            String ffmpegPath, String transcodeCompleteQueueName, ProcessingLeaseStore leaseStore) {
        this.s3Client = s3Client;
        this.sqsClient = sqsClient;
        this.objectMapper = objectMapper;
        this.uploadEventParser = uploadEventParser;
        this.uploadBucket = uploadBucket;
        this.streamBucket = streamBucket;
        this.ffmpegPath = ffmpegPath;
        this.transcodeCompleteQueueName = transcodeCompleteQueueName;
        this.leaseStore = leaseStore;
    }

    @Bean
    public Consumer<byte[]> transcodeVideo() {
        return payload -> {
            var bodies = uploadEventParser.parseSqsEnvelopeBodies(payload);
            if (bodies.isEmpty()) {
                log.warn("No SQS message bodies in Lambda payload");
                return;
            }
            for (var body : bodies) {
                processUploadMessage(body);
            }
        };
    }

    private void processUploadMessage(String sqsBody) {
        // A direct command is explicit: never infer a retry's identity from an S3 event.
        UUID uploadId;
        UUID attemptId;
        String srcKey;
        tools.jackson.databind.JsonNode commandNode;
        try {
            commandNode = objectMapper.readTree(sqsBody);
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalArgumentException("Malformed processing message", e);
        }
        if (commandNode != null && (commandNode.has("jobType") || commandNode.has("sourceKey"))) {
            var job = objectMapper.readValue(sqsBody, ProcessingJobRecord.class);
            uploadId = job.uploadId();
            attemptId = job.attemptId();
            srcKey = job.sourceKey();
        } else {
            var uploadEvent = uploadEventParser.parseSqsMessage(sqsBody);
            if (uploadEvent.isEmpty()) {
                log.warn("Ignoring unparseable upload message");
                return;
            }
            var event = uploadEvent.get();
            if (!uploadBucket.equals(event.bucketName())) {
                log.warn("Ignoring object from unexpected bucket: {}", event.bucketName());
                return;
            }
            uploadId = event.uploadId();
            attemptId = uploadId;
            srcKey = event.objectKey();
        }
        var lease = leaseStore.acquire(uploadId, attemptId);
        if (lease.completedResult() != null) {
            log.info("Replaying retained processing result for upload {} / attempt {} / execution {}",
                    lease.uploadId(), lease.attemptId(), lease.executionId());
            publishStoredResult(lease, lease.completedResult());
            return;
        }
        log.info("Transcoding upload {} from s3://{}/{}", uploadId, uploadBucket, srcKey);

        Path workDir = null;
        Path localInput = null;
        Path outputDir = null;
        try {
            workDir = Files.createTempDirectory("transcode-" + uploadId);
            localInput = workDir.resolve("input.mp4");
            outputDir = workDir.resolve("out");
            Files.createDirectories(outputDir);

            s3Client.getObject(
                    GetObjectRequest.builder()
                            .bucket(uploadBucket)
                            .key(srcKey)
                            .build(),
                    localInput);

            List<String> command = HlsTranscodeCommand.build(ffmpegPath, localInput, outputDir);
            executeNativeProcess(command);

            var localManifest = HlsTranscodeCommand.playlistPath(outputDir);
            var localMediaFile = HlsTranscodeCommand.mediaPath(outputDir);
            if (!Files.exists(localManifest) || !Files.exists(localMediaFile)) {
                throw new IllegalStateException("Required FFmpeg output artifacts are missing");
            }

            var playlistKey = S3ObjectKeys.executionPlaylistKey(uploadId, lease.attemptId(), lease.executionId());
            var mediaKey = S3ObjectKeys.executionMediaKey(uploadId, lease.attemptId(), lease.executionId());

            s3Client.putObject(
                    PutObjectRequest.builder().bucket(streamBucket).key(mediaKey)
                            .contentType("video/mp4").ifNoneMatch("*").build(),
                    localMediaFile);
            s3Client.putObject(
                    PutObjectRequest.builder().bucket(streamBucket).key(playlistKey)
                            .contentType("application/vnd.apple.mpegurl").ifNoneMatch("*").build(),
                    localManifest);

            finish(lease, VideoStatus.PLAY_READY, null, null, playlistKey);
            log.info("Transcode complete for {} → s3://{}/{}", uploadId, streamBucket, playlistKey);
        } catch (FfmpegExitException e) {
            log.error("FFmpeg rejected upload {}", uploadId, e);
            try {
                finish(lease, VideoStatus.FAILED, "FFMPEG_EXIT", e.getMessage(), null);
            } catch (RuntimeException resultFailure) {
                releaseUnfinished(lease, resultFailure);
                throw resultFailure;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            releaseUnfinished(lease, e);
            throw new IllegalStateException("Transcode interrupted for upload " + uploadId, e);
        } catch (Exception e) {
            releaseUnfinished(lease, e);
            log.error("Retryable processing error for upload {}", uploadId, e);
            throw new IllegalStateException("Processing must be retried for upload " + uploadId, e);
        } finally {
            if (workDir != null) {
                deleteRecursively(workDir.toFile());
            }
        }
    }

    private void releaseUnfinished(ProcessingLeaseStore.Lease lease, Exception original) {
        try {
            leaseStore.release(lease);
        } catch (Exception releaseFailure) {
            original.addSuppressed(releaseFailure);
        }
    }

    private void finish(ProcessingLeaseStore.Lease lease, VideoStatus status, String failureCode,
            String failureDetail, String playlistKey) {
        var result = objectMapper.writeValueAsString(new VideoStatusUpdateRecord(
                lease.uploadId(), status, lease.attemptId(), 2, failureCode, failureDetail, playlistKey));
        var committed = leaseStore.complete(lease, result);
        publishStoredResult(lease, committed);
    }

    private void publishStoredResult(ProcessingLeaseStore.Lease lease, String message) {
        var result = objectMapper.readValue(message, VideoStatusUpdateRecord.class);
        if (result == null || !lease.uploadId().equals(result.uploadId())
                || !lease.attemptId().equals(result.attemptId()) || !Integer.valueOf(2).equals(result.schemaVersion())
                || (result.status() != VideoStatus.PLAY_READY && result.status() != VideoStatus.FAILED)
                || !result.hasValidFailureMetadata()
                || (result.status() == VideoStatus.PLAY_READY && !S3ObjectKeys.executionPlaylistKey(
                        lease.uploadId(), lease.attemptId(), lease.executionId()).equals(result.playlistKey()))
                || (result.status() == VideoStatus.FAILED && result.playlistKey() != null)) {
            throw new IllegalStateException("Invalid retained processing result");
        }
        try {
            if (transcodeCompleteQueueUrl == null) {
                transcodeCompleteQueueUrl = sqsClient
                        .getQueueUrl(req -> req.queueName(transcodeCompleteQueueName))
                        .queueUrl();
            }
            var queueUrl = transcodeCompleteQueueUrl;
            sqsClient.sendMessage(req -> req.queueUrl(queueUrl).messageBody(message));
        } catch (Exception e) {
            transcodeCompleteQueueUrl = null;
            log.error("Failed to publish retained result for upload {}", lease.uploadId(), e);
            throw new IllegalStateException("Failed to publish result for upload " + lease.uploadId(), e);
        }
    }

    // Package visibility allows deterministic worker-failure tests without an installed FFmpeg.
    void executeNativeProcess(List<String> command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("[ffmpeg] {}", line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new FfmpegExitException(exitCode);
        }
    }

    static final class FfmpegExitException extends RuntimeException {
        FfmpegExitException(int exitCode) {
            super("FFmpeg exited with status " + exitCode);
        }
    }

    private void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }
}
