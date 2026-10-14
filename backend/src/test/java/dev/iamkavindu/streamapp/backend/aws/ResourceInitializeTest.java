package dev.iamkavindu.streamapp.backend.aws;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutBucketPolicyRequest;

import java.util.function.Consumer;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ResourceInitializeTest {
    @Test
    void disabledLocalReadDoesNotModifyBucketPolicy() {
        var client = mock(S3Client.class);
        new ResourceInitialize(client, false).init();
        verify(client, never()).putBucketPolicy(
                org.mockito.ArgumentMatchers.<Consumer<PutBucketPolicyRequest.Builder>>any());
    }
}
