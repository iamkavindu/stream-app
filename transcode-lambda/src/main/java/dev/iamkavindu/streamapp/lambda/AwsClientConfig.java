package dev.iamkavindu.streamapp.lambda;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.net.URI;
import java.util.function.Consumer;

@Configuration
@EnableConfigurationProperties(AwsConfigProperties.class)
public class AwsClientConfig {

    @Bean
    S3Client s3Client(AwsConfigProperties props) {
        var builder = S3Client.builder()
                .credentialsProvider(credentialProvider(props))
                .serviceConfiguration(conf -> conf.pathStyleAccessEnabled(props.pathStyleAccess()))
                .httpClient(UrlConnectionHttpClient.create());
        applyRegion(builder::region, props);
        applyEndpoint(builder::endpointOverride, props);
        return builder.build();
    }

    @Bean
    SqsClient sqsClient(AwsConfigProperties props) {
        var builder = SqsClient.builder()
                .credentialsProvider(credentialProvider(props))
                .httpClient(UrlConnectionHttpClient.create());
        applyRegion(builder::region, props);
        applyEndpoint(builder::endpointOverride, props);
        return builder.build();
    }

    private AwsCredentialsProvider credentialProvider(AwsConfigProperties props) {
        if (StringUtils.hasText(props.accessKey())
                && !"to_be_overridden".equals(props.accessKey())
                && StringUtils.hasText(props.secretKey())
                && !"to_be_overridden".equals(props.secretKey())) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(props.accessKey(), props.secretKey()));
        }
        return DefaultCredentialsProvider.builder().build();
    }

    private void applyRegion(Consumer<Region> regionSetter, AwsConfigProperties props) {
        if (StringUtils.hasText(props.region()) && !"to_be_overridden".equals(props.region())) {
            regionSetter.accept(Region.of(props.region()));
        }
    }

    private void applyEndpoint(Consumer<URI> endpointSetter, AwsConfigProperties props) {
        if (StringUtils.hasText(props.endpoint()) && !"to_be_overridden".equals(props.endpoint())) {
            endpointSetter.accept(URI.create(props.endpoint()));
        }
    }
}
