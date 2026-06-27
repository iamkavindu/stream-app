package dev.iamkavindu.streamapp.backend.transcode;

import dev.iamkavindu.streamapp.backend.aws.S3ObjectKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HlsTranscodeCommandTest {

  @TempDir Path outputDir;

  @Test
  void build_usesSingleFileFmp4HlsOutputNames() {
    var input = outputDir.resolve("input.mp4");
    var command = HlsTranscodeCommand.build("/usr/bin/ffmpeg", input, outputDir);

    assertEquals("/usr/bin/ffmpeg", command.getFirst());
    assertTrue(command.contains("-hls_flags"));
    assertTrue(command.contains("single_file"));
    assertTrue(command.contains("-hls_segment_type"));
    assertTrue(command.contains("fmp4"));

    var manifestIndex = command.indexOf(outputDir.resolve(S3ObjectKeys.PLAYLIST_FILE).toString());
    var mediaIndex = command.indexOf(outputDir.resolve(S3ObjectKeys.MEDIA_FILE).toString());
    assertTrue(manifestIndex > mediaIndex, "manifest path must be the final FFmpeg argument");
    assertEquals(
        outputDir.resolve(S3ObjectKeys.MEDIA_FILE).toString(),
        command.get(command.indexOf("-hls_segment_filename") + 1));
  }
}
