package dev.iamkavindu.streamapp.lambda;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.aws")
public record AwsConfigProperties(
        String endpoint, String region, String accessKey, String secretKey, boolean pathStyleAccess) {}
