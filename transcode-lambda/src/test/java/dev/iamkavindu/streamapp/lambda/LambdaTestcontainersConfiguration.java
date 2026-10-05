package dev.iamkavindu.streamapp.lambda;

import io.floci.testcontainers.FlociContainer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;

import java.net.URI;

@TestConfiguration(proxyBeanMethods = false)
public class LambdaTestcontainersConfiguration {

    @Bean(destroyMethod = "stop")
    FlociContainer flociContainer() {
        // Version 2.0.0 of the Java module still defaults to the legacy image.
        // Keep this release aligned with docker/infra/aws/docker-compose.yaml.
        return new FlociContainer(DockerImageName.parse("floci/floci:2.1.0")
                .asCompatibleSubstituteFor("hectorvent/floci"));
    }

    @Bean
    DynamicPropertyRegistrar flociAwsProperties(FlociContainer floci) {
        return registry -> {
            registry.add("app.aws.endpoint", floci::getEndpoint);
            registry.add("app.aws.region", floci::getRegion);
            registry.add("app.aws.access-key", floci::getAccessKey);
            registry.add("app.aws.secret-key", floci::getSecretKey);
            registry.add("app.aws.path-style-access", () -> "true");
        };
    }

    @Bean
    SnsClient snsClient(FlociContainer floci) {
        return SnsClient.builder()
                .endpointOverride(URI.create(floci.getEndpoint()))
                .region(Region.of(floci.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(floci.getAccessKey(), floci.getSecretKey())))
                .build();
    }
}
