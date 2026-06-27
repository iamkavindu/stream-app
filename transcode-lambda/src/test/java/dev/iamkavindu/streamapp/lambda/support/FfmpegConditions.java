package dev.iamkavindu.streamapp.lambda.support;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

public final class FfmpegConditions {

    private FfmpegConditions() {}

    public static boolean isAvailable() {
        var configured = System.getProperty("app.ffmpeg.path");
        if (configured != null && probe(Path.of(configured))) {
            return true;
        }
        return probe(Path.of("/usr/bin/ffmpeg"))
                || probe(Path.of("C:/ffmpeg/bin/ffmpeg.exe"))
                || probe(Path.of("ffmpeg"));
    }

    public static boolean probe(Path ffmpegPath) {
        try {
            var process = new ProcessBuilder(ffmpegPath.toString(), "-version").start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
