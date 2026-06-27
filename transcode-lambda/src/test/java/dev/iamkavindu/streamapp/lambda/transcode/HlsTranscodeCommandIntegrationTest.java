package dev.iamkavindu.streamapp.lambda.transcode;

import dev.iamkavindu.streamapp.lambda.aws.S3ObjectKeys;
import dev.iamkavindu.streamapp.lambda.support.FfmpegConditions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Tag("slow")
@EnabledIf("dev.iamkavindu.streamapp.lambda.support.FfmpegConditions#isAvailable")
class HlsTranscodeCommandIntegrationTest {

    @Value("${app.ffmpeg.path}")
    String ffmpegPath;

    @TempDir
    Path workDir;

    @Test
    void ffmpegProducesHlsArtifacts() throws Exception {
        var input = workDir.resolve("input.mp4");
        generateMinimalMp4(input);
        var outputDir = workDir.resolve("out");
        Files.createDirectories(outputDir);

        var command = HlsTranscodeCommand.build(ffmpegPath, input, outputDir);
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        assertThat(process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();

        assertThat(Files.exists(HlsTranscodeCommand.playlistPath(outputDir))).isTrue();
        assertThat(Files.exists(HlsTranscodeCommand.mediaPath(outputDir))).isTrue();
        assertThat(Files.readString(HlsTranscodeCommand.playlistPath(outputDir))).contains("#EXTM3U");
    }

    private void generateMinimalMp4(Path output) throws Exception {
        var command = List.of(
                ffmpegPath,
                "-y",
                "-f",
                "lavfi",
                "-i",
                "testsrc=duration=1:size=320x240:rate=1",
                "-c:v",
                "libx264",
                "-preset",
                "ultrafast",
                "-t",
                "1",
                output.toString());
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        assertThat(process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();
    }
}
