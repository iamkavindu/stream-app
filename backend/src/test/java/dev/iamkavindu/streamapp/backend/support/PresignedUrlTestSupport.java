package dev.iamkavindu.streamapp.backend.support;

import io.floci.testcontainers.FlociContainer;

import java.net.URI;

public final class PresignedUrlTestSupport {

    private PresignedUrlTestSupport() {}

    /**
     * Rewrites virtual-hosted Floci presigned URLs ({@code bucket.localhost:port/key}) to path-style
     * ({@code localhost:port/bucket/key}) so {@link java.net.http.HttpClient} can reach the container.
     */
    public static String toPathStyleHttpUrl(String presignedUrl, FlociContainer floci) {
        var signed = URI.create(presignedUrl);
        var endpoint = URI.create(floci.getEndpoint());

        var host = signed.getHost();
        if (host == null || !host.contains(".localhost")) {
            return presignedUrl;
        }

        var bucket = host.substring(0, host.indexOf(".localhost"));
        var path = "/" + bucket + signed.getPath();
        var query = signed.getQuery();
        var rewritten = new StringBuilder()
                .append(endpoint.getScheme())
                .append("://")
                .append(endpoint.getHost())
                .append(":")
                .append(endpoint.getPort())
                .append(path);
        if (query != null && !query.isBlank()) {
            rewritten.append('?').append(query);
        }
        return rewritten.toString();
    }
}
