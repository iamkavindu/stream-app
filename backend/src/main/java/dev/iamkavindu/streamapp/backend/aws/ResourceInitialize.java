package dev.iamkavindu.streamapp.backend.aws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CORSConfiguration;
import software.amazon.awssdk.services.s3.model.CORSRule;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

import java.util.List;

@Configuration
@Profile("dev")
public class ResourceInitialize {
    private static final Logger log = LoggerFactory.getLogger(ResourceInitialize.class);

    private static final String UPLOAD_BUCKET = "streamapp-uploads";
    private static final String STREAM_BUCKET = "streamapp-streams";

    private final S3Client s3Client;
    private final boolean localHlsPublicReadEnabled;

    public ResourceInitialize(S3Client s3Client,
            @Value("${streamapp.local-hls.public-read-enabled:false}") boolean localHlsPublicReadEnabled) {
        this.s3Client = s3Client;
        this.localHlsPublicReadEnabled = localHlsPublicReadEnabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    void init() {
        log.info("Initializing resources");
        createBucketIfMissing(UPLOAD_BUCKET);
        createBucketIfMissing(STREAM_BUCKET);
        configureUploadBucketCors();
        configureStreamBucketCors();
        if (localHlsPublicReadEnabled) {
            configureLocalStreamReadPolicy();
        }
    }

    private void createBucketIfMissing(String bucketName) {
        try {
            s3Client.headBucket(request -> request.bucket(bucketName));
        } catch (NoSuchBucketException e) {
            s3Client.createBucket(request -> request.bucket(bucketName));
        }
    }

    private void configureUploadBucketCors() {
        var corsRule = CORSRule.builder()
                .allowedHeaders("*")
                .allowedMethods("PUT")
                .allowedOrigins("http://localhost:5173")
                .maxAgeSeconds(3600)
                .build();

        var corsConfig = CORSConfiguration.builder()
                .corsRules(List.of(corsRule))
                .build();

        s3Client.putBucketCors(request -> request
                .bucket(UPLOAD_BUCKET)
                .corsConfiguration(corsConfig)
                .build());
    }

    private void configureStreamBucketCors() {
        var corsRule = CORSRule.builder()
                .allowedHeaders("*")
                .allowedMethods("GET", "HEAD")
                .allowedOrigins("http://localhost:5173")
                .exposeHeaders("Accept-Ranges", "Content-Range", "ETag")
                .maxAgeSeconds(3600)
                .build();

        var corsConfig = CORSConfiguration.builder()
                .corsRules(List.of(corsRule))
                .build();

        s3Client.putBucketCors(request -> request
                .bucket(STREAM_BUCKET)
                .corsConfiguration(corsConfig)
                .build());
    }

    private void configureLocalStreamReadPolicy() {
        // Local demo access only: the playlist's relative media URL has no presign query.
        var policy = """
                {
                  "Version": "2012-10-17",
                  "Statement": [{
                    "Sid": "LocalHlsRead",
                    "Effect": "Allow",
                    "Principal": "*",
                    "Action": "s3:GetObject",
                    "Resource": [
                      "arn:aws:s3:::%s/*/index.m3u8",
                      "arn:aws:s3:::%s/*/media.mp4"
                    ]
                  }]
                }
                """.formatted(STREAM_BUCKET, STREAM_BUCKET);
        s3Client.putBucketPolicy(request -> request.bucket(STREAM_BUCKET).policy(policy));
    }
}
