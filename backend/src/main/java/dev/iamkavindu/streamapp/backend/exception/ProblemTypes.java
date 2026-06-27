package dev.iamkavindu.streamapp.backend.exception;

import java.net.URI;

public final class ProblemTypes {

    public static final URI BASE = URI.create("https://stream-app.dev/problems/");

    public static URI of(String slug) {
        return BASE.resolve(slug);
    }

    private ProblemTypes() {}
}
