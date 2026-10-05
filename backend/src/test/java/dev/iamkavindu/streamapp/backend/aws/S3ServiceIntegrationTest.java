package dev.iamkavindu.streamapp.backend.aws;

import dev.iamkavindu.streamapp.backend.support.IntegrationTest;
import dev.iamkavindu.streamapp.backend.support.PresignedUrlTestSupport;
import io.floci.testcontainers.FlociContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.util.UUID;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
class S3ServiceIntegrationTest {

    private static final String UPLOAD_BUCKET = "streamapp-uploads";
    private static final String STREAM_BUCKET = "streamapp-streams";

    @Autowired
    S3Service s3Service;

    @Autowired
    S3Client s3Client;

    @Autowired
    FlociContainer floci;

    @Test
    void createSignedPutUrl_targetsUploadBucketObjectKey() {
        var uploadId = UUID.randomUUID();
        var objectKey = S3ObjectKeys.uploadObjectKey(uploadId, "demo.mp4");

        var signedUrl = s3Service.createSignedPutUrl(uploadId, "demo.mp4");

        assertThat(signedUrl).contains(UPLOAD_BUCKET).contains(objectKey);
    }

    @Test
    void uploadObjectKeyLayout_worksOnFloci() {
        var uploadId = UUID.randomUUID();
        var objectKey = S3ObjectKeys.uploadObjectKey(uploadId, "demo.mp4");

        s3Client.putObject(
                b -> b.bucket(UPLOAD_BUCKET).key(objectKey),
                RequestBody.fromString("video-bytes"));

        assertThat(s3Client.headObject(b -> b.bucket(UPLOAD_BUCKET).key(objectKey)).contentLength())
                .isEqualTo("video-bytes".length());
    }

    @Test
    void sourceExists_distinguishesMissingAndStoredSource() {
        var uploadId = UUID.randomUUID();
        assertThat(s3Service.uploadSourceExists(uploadId, "demo.mp4")).isFalse();
        s3Client.putObject(b -> b.bucket(UPLOAD_BUCKET).key(S3ObjectKeys.uploadObjectKey(uploadId, "demo.mp4")),
                RequestBody.fromString("source"));
        assertThat(s3Service.uploadSourceExists(uploadId, "demo.mp4")).isTrue();
    }

    @Test
    void conditionalPresignedPut_preventsOverwriteOnFloci() throws Exception {
        var uploadId = UUID.randomUUID();
        var url = PresignedUrlTestSupport.toPathStyleHttpUrl(
                s3Service.createSignedPutUrl(uploadId, "demo.mp4"), floci);
        try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            var first = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                    .header("If-None-Match", "*")
                    .PUT(HttpRequest.BodyPublishers.ofString("original")).build();
            assertThat(http.send(first, HttpResponse.BodyHandlers.discarding()).statusCode()).isBetween(200, 299);
            var second = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                    .header("If-None-Match", "*")
                    .PUT(HttpRequest.BodyPublishers.ofString("replacement")).build();
            assertThat(http.send(second, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(412);
        }
        assertThat(s3Client.getObjectAsBytes(b -> b.bucket(UPLOAD_BUCKET)
                .key(S3ObjectKeys.uploadObjectKey(uploadId, "demo.mp4"))).asUtf8String()).isEqualTo("original");
    }

    @Test
    void createSignedPlaylistGetUrl_targetsStreamManifestKey() {
        var uploadId = UUID.randomUUID();
        var objectKey = S3ObjectKeys.streamPlaylistKey(uploadId);

        var signedUrl = s3Service.createSignedPlaylistGetUrl(uploadId);

        assertThat(signedUrl).contains(STREAM_BUCKET).contains(objectKey);
    }

    @Test
    void streamManifestKeyLayout_worksOnFloci() {
        var uploadId = UUID.randomUUID();
        var objectKey = S3ObjectKeys.streamPlaylistKey(uploadId);

        s3Client.putObject(
                b -> b.bucket(STREAM_BUCKET).key(objectKey),
                RequestBody.fromString("#EXTM3U\n"));

        var body = s3Client.getObjectAsBytes(b -> b.bucket(STREAM_BUCKET).key(objectKey)).asUtf8String();
        assertThat(body).contains("#EXTM3U");
    }

    @Test
    void createSignedPlaylistGetUrl_missingObjectStillSigns() {
        var uploadId = UUID.randomUUID();
        var signedUrl = s3Service.createSignedPlaylistGetUrl(uploadId);

        assertThat(signedUrl).contains(S3ObjectKeys.streamPlaylistKey(uploadId));
        assertThatThrownBy(() ->
                        s3Client.headObject(b -> b.bucket(STREAM_BUCKET).key(S3ObjectKeys.streamPlaylistKey(uploadId))))
                .isInstanceOf(NoSuchKeyException.class);
    }
}
