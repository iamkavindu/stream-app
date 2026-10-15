package dev.iamkavindu.streamapp.backend.aws;

import dev.iamkavindu.streamapp.backend.support.IntegrationTest;
import dev.iamkavindu.streamapp.backend.support.PresignedUrlTestSupport;
import io.floci.testcontainers.FlociContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class LocalHlsDeliveryIntegrationTest {
    private static final String BUCKET = "streamapp-streams";
    private static final String ORIGIN = "http://localhost:5173";

    @Autowired S3Client s3Client;
    @Autowired S3Service s3Service;
    @Autowired FlociContainer floci;

    @Test
    void repeatBootstrapPersistsScopedReadPolicyAndRangeCors() {
        var initializer = new ResourceInitialize(s3Client, true);
        initializer.init();
        initializer.init();

        var policy = new ObjectMapper().readTree(s3Client.getBucketPolicy(b -> b.bucket(BUCKET)).policy());
        var statement = policy.path("Statement").get(0);
        assertThat(policy.path("Statement").size()).isEqualTo(1);
        assertThat(statement.path("Effect").asText()).isEqualTo("Allow");
        assertThat(statement.path("Principal").asText()).isEqualTo("*");
        assertThat(statement.path("Action").asText()).isEqualTo("s3:GetObject");
        assertThat(statement.path("Resource").size()).isEqualTo(2);
        assertThat(statement.path("Resource").get(0).asText())
                .isEqualTo("arn:aws:s3:::streamapp-streams/*/index.m3u8");
        assertThat(statement.path("Resource").get(1).asText())
                .isEqualTo("arn:aws:s3:::streamapp-streams/*/media.mp4");
        var cors = s3Client.getBucketCors(b -> b.bucket(BUCKET)).corsRules();
        assertThat(cors).hasSize(1);
        assertThat(cors.getFirst().allowedOrigins()).containsExactly(ORIGIN);
        assertThat(cors.getFirst().allowedMethods()).containsExactly("GET", "HEAD");
        assertThat(cors.getFirst().exposeHeaders()).contains("Accept-Ranges", "Content-Range", "ETag");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void signedManifestResolvesUnsignedMediaWithExactByteRangesAndHead(boolean scopedExecution) throws Exception {
        new ResourceInitialize(s3Client, true).init();
        var uploadId = UUID.randomUUID();
        var executionId = UUID.randomUUID();
        var playlistKey = scopedExecution ? S3ObjectKeys.executionPlaylistKey(uploadId, uploadId, executionId)
                : S3ObjectKeys.streamPlaylistKey(uploadId);
        var mediaKey = scopedExecution ? S3ObjectKeys.executionMediaKey(uploadId, uploadId, executionId)
                : S3ObjectKeys.streamMediaKey(uploadId);
        var manifest = "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:1\n"
                + "#EXT-X-MAP:URI=\"media.mp4\",BYTERANGE=\"4@0\"\n"
                + "#EXTINF:1.0,\n#EXT-X-BYTERANGE:6@4\nmedia.mp4\n#EXT-X-ENDLIST\n";
        s3Client.putObject(b -> b.bucket(BUCKET).key(playlistKey)
                .contentType("application/vnd.apple.mpegurl"), RequestBody.fromString(manifest));
        s3Client.putObject(b -> b.bucket(BUCKET).key(mediaKey)
                .contentType("video/mp4"), RequestBody.fromString("INIT012345"));
        var playlistUrl = URI.create(PresignedUrlTestSupport.toPathStyleHttpUrl(
                (scopedExecution ? s3Service.createSignedPlaylistGetUrl(uploadId, uploadId, playlistKey)
                        : s3Service.createSignedPlaylistGetUrl(uploadId)), floci));
        try (var http = HttpClient.newHttpClient()) {
            var playlist = http.send(request(playlistUrl).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(playlist.statusCode()).isEqualTo(200);
            assertThat(playlist.body()).isEqualTo(manifest);
            assertThat(playlist.headers().firstValue("Content-Type")).hasValue("application/vnd.apple.mpegurl");
            assertThat(playlist.headers().firstValue("Access-Control-Allow-Origin")).hasValue(ORIGIN);

            var mediaUrl = playlistUrl.resolve(playlist.body().lines().filter(l -> !l.startsWith("#"))
                    .filter(l -> !l.isBlank()).findFirst().orElseThrow());
            assertThat(mediaUrl.getQuery()).isNull();
            for (var range : new String[][] {{"bytes=0-3", "bytes 0-3/10", "INIT"},
                    {"bytes=4-9", "bytes 4-9/10", "012345"}}) {
                var media = http.send(request(mediaUrl).header("Range", range[0]).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(media.statusCode()).isEqualTo(206);
                assertThat(media.body()).isEqualTo(range[2]);
                assertThat(media.headers().firstValue("Content-Range")).hasValue(range[1]);
                assertThat(media.headers().firstValue("Content-Type")).hasValue("video/mp4");
                assertThat(media.headers().firstValue("Access-Control-Allow-Origin")).hasValue(ORIGIN);
                assertThat(media.headers().firstValue("Access-Control-Expose-Headers").orElse(""))
                        .containsIgnoringCase("Content-Range");
            }
            var head = http.send(request(mediaUrl).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding());
            assertThat(head.statusCode()).isEqualTo(200);
            assertThat(head.headers().firstValue("Content-Length")).hasValue("10");

            var preflight = http.send(request(mediaUrl).header("Access-Control-Request-Method", "GET")
                    .header("Access-Control-Request-Headers", "range")
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding());
            assertThat(preflight.statusCode()).isBetween(200, 299);
            assertThat(preflight.headers().firstValue("Access-Control-Allow-Origin")).hasValue(ORIGIN);
        } finally {
            s3Client.deleteObject(b -> b.bucket(BUCKET).key(playlistKey));
            s3Client.deleteObject(b -> b.bucket(BUCKET).key(mediaKey));
        }
    }

    @Test
    void missingOutputReturnsNotFoundInsteadOfSuccessfulMedia() throws Exception {
        new ResourceInitialize(s3Client, true).init();
        var base = URI.create(floci.getEndpoint() + "/" + BUCKET + "/" + UUID.randomUUID() + "/");
        try (var http = HttpClient.newHttpClient()) {
            for (var name : new String[] {"index.m3u8", "media.mp4"}) {
                var result = http.send(request(base.resolve(name)).GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                assertThat(result.statusCode()).isEqualTo(404);
            }
        }
    }

    private HttpRequest.Builder request(URI uri) {
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).header("Origin", ORIGIN);
    }
}
