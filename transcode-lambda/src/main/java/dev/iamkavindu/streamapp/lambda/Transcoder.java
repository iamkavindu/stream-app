package dev.iamkavindu.streamapp.lambda;

import dev.iamkavindu.streamapp.lambda.aws.S3ObjectKeys;
import dev.iamkavindu.streamapp.lambda.aws.S3UploadEventParser;
import dev.iamkavindu.streamapp.lambda.model.VideoStatus;
import dev.iamkavindu.streamapp.lambda.model.VideoStatusUpdateRecord;
import dev.iamkavindu.streamapp.lambda.transcode.HlsTranscodeCommand;
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

    private String transcodeCompleteQueueUrl;

    public Transcoder(
            S3Client s3Client,
            SqsClient sqsClient,
            ObjectMapper objectMapper,
            S3UploadEventParser uploadEventParser,
            @Value("${app.bucket-name.upload-bucket}") String uploadBucket,
            @Value("${app.bucket-name.stream-bucket}") String streamBucket,
            @Value("${app.ffmpeg.path}") String ffmpegPath,
            @Value("${app.sqs.queue.transcode-complete-queue}") String transcodeCompleteQueueName) {
        this.s3Client = s3Client;
        this.sqsClient = sqsClient;
        this.objectMapper = objectMapper;
        this.uploadEventParser = uploadEventParser;
        this.uploadBucket = uploadBucket;
        this.streamBucket = streamBucket;
        this.ffmpegPath = ffmpegPath;
        this.transcodeCompleteQueueName = transcodeCompleteQueueName;
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

        var uploadId = event.uploadId();
        var srcKey = event.objectKey();
        log.info("Transcoding upload {} from s3://{}/{}", uploadId, event.bucketName(), srcKey);

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
                            .bucket(event.bucketName())
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

            var playlistKey = S3ObjectKeys.streamPlaylistKey(uploadId);
            var mediaKey = S3ObjectKeys.streamMediaKey(uploadId);

            s3Client.putObject(
                    PutObjectRequest.builder().bucket(streamBucket).key(playlistKey).build(),
                    localManifest);
            s3Client.putObject(
                    PutObjectRequest.builder().bucket(streamBucket).key(mediaKey).build(),
                    localMediaFile);

            publishStatus(uploadId, VideoStatus.PLAY_READY);
            log.info("Transcode complete for {} → s3://{}/{}", uploadId, streamBucket, playlistKey);
        } catch (Exception e) {
            log.error("Transcode failed for upload {}", uploadId, e);
            publishStatus(uploadId, VideoStatus.FAILED);
        } finally {
            if (workDir != null) {
                deleteRecursively(workDir.toFile());
            }
        }
    }

    private void publishStatus(UUID uploadId, VideoStatus status) {
        try {
            if (transcodeCompleteQueueUrl == null) {
                transcodeCompleteQueueUrl = sqsClient
                        .getQueueUrl(req -> req.queueName(transcodeCompleteQueueName))
                        .queueUrl();
            }
            var message = objectMapper.writeValueAsString(new VideoStatusUpdateRecord(uploadId, status));
            var queueUrl = transcodeCompleteQueueUrl;
            sqsClient.sendMessage(req -> req.queueUrl(queueUrl).messageBody(message));
        } catch (Exception e) {
            log.error("Failed to publish {} for upload {}", status, uploadId, e);
        }
    }

    private void executeNativeProcess(List<String> command) throws Exception {
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
            throw new RuntimeException("FFmpeg exited with status " + exitCode);
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
