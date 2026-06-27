package dev.iamkavindu.streamapp.backend.aws;

import io.awspring.cloud.s3.S3Template;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

@Service
public class S3Service {
    private static final String UPLOAD_BUCKET = "streamapp-uploads";
    private static final String STREAM_BUCKET = "streamapp-streams";

    private final S3Template s3Template;

    public S3Service(S3Template s3Template) {
        this.s3Template = s3Template;
    }

    public String createSignedPutUrl(UUID uploadId, String fileName) {
        var objectKey = S3ObjectKeys.uploadObjectKey(uploadId, fileName);
        return s3Template.createSignedPutURL(UPLOAD_BUCKET, objectKey, Duration.ofMinutes(15))
                .toString();
    }

    /**
     * Presigned GET for the HLS media playlist ({@code {uploadId}/index.m3u8}) in the stream bucket.
     */
    public String createSignedPlaylistGetUrl(UUID uploadId) {
        var objectKey = S3ObjectKeys.streamPlaylistKey(uploadId);
        return s3Template.createSignedGetURL(STREAM_BUCKET, objectKey, Duration.ofMinutes(15))
                .toString();
    }
}
