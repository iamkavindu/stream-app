package dev.iamkavindu.streamapp.lambda;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;

@SpringBootTest
class TranscodeLambdaApplicationTests {

    @MockitoBean
    S3Client s3Client;

    @MockitoBean
    SqsClient sqsClient;

    @Test
    void contextLoads() {}
}
