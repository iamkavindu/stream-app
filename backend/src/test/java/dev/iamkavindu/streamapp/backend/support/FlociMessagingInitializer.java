package dev.iamkavindu.streamapp.backend.support;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;

@TestConfiguration(proxyBeanMethods = false)
public class FlociMessagingInitializer {

    @Bean
    ApplicationRunner testAwsBootstrapRunner(S3Client s3Client, SqsClient sqsClient, SnsClient snsClient) {
        return args -> new TestAwsBootstrap(s3Client, sqsClient, snsClient).bootstrap();
    }
}
