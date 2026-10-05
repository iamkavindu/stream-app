package dev.iamkavindu.streamapp.backend.aws;

import io.awspring.cloud.s3.S3Template;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.time.Duration;
import java.util.UUID;

@Service
public class S3Service {
    private static final String UPLOAD_BUCKET = "streamapp-uploads";
    private static final String STREAM_BUCKET = "streamapp-streams";

    private final S3Template s3Template;
    private final S3Client s3Client;

    public S3Service(S3Template s3Template, S3Client s3Client) {
        this.s3Template = s3Template;
        this.s3Client = s3Client;
    }

    public boolean uploadSourceExists(UUID uploadId, String fileName) {
        try {
            s3Client.headObject(request -> request.bucket(UPLOAD_BUCKET)
                    .key(S3ObjectKeys.uploadObjectKey(uploadId, fileName)));
            return true;
        } catch (S3Exception e) {
            // HEAD may omit an error body. Confirm the bucket exists before treating 404 as a missing key.
            if (e.statusCode() == 404) {
                s3Client.headBucket(request -> request.bucket(UPLOAD_BUCKET));
                return false;
            }
            throw e;
        }
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
