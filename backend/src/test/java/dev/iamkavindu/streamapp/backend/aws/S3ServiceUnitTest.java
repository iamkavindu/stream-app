package dev.iamkavindu.streamapp.backend.aws;

import io.awspring.cloud.s3.S3Template;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class S3ServiceUnitTest {
    private final S3Client client = mock(S3Client.class);
    private final S3Service service = new S3Service(mock(S3Template.class), client);

    @Test
    void missingKeyIsAbsentOnlyAfterBucketCheckSucceeds() {
        when(client.headObject(org.mockito.ArgumentMatchers.<Consumer<HeadObjectRequest.Builder>>any()))
                .thenThrow(S3Exception.builder().statusCode(404).build());
        assertThat(service.uploadSourceExists(UUID.randomUUID(), "demo.mp4")).isFalse();
        verify(client).headBucket(org.mockito.ArgumentMatchers.<Consumer<HeadBucketRequest.Builder>>any());
    }

    @Test
    void missingBucketPropagatesInsteadOfAllowingReupload() {
        when(client.headObject(org.mockito.ArgumentMatchers.<Consumer<HeadObjectRequest.Builder>>any()))
                .thenThrow(S3Exception.builder().statusCode(404).build());
        when(client.headBucket(org.mockito.ArgumentMatchers.<Consumer<HeadBucketRequest.Builder>>any()))
                .thenThrow(S3Exception.builder().statusCode(404).build());
        assertThatThrownBy(() -> service.uploadSourceExists(UUID.randomUUID(), "demo.mp4"))
                .isInstanceOf(S3Exception.class);
    }

    @Test
    void accessDeniedAndServerFailuresAreNotMissingObjects() {
        for (var status : new int[] {403, 503}) {
            when(client.headObject(org.mockito.ArgumentMatchers.<Consumer<HeadObjectRequest.Builder>>any()))
                    .thenThrow(S3Exception.builder().statusCode(status).build());
            assertThatThrownBy(() -> service.uploadSourceExists(UUID.randomUUID(), "demo.mp4"))
                    .isInstanceOf(S3Exception.class);
        }
        verify(client, never()).headBucket(org.mockito.ArgumentMatchers.<Consumer<HeadBucketRequest.Builder>>any());
    }
}
