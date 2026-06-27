package dev.iamkavindu.streamapp.backend.transcode;

import dev.iamkavindu.streamapp.backend.aws.S3ObjectKeys;

import java.nio.file.Path;
import java.util.List;

/**
 * FFmpeg command for raw MP4 → fragmented MP4 HLS (VOD) using a single media file.
 * <p>
 * Writes {@link S3ObjectKeys#PLAYLIST_FILE} and {@link S3ObjectKeys#MEDIA_FILE} under
 * {@code outputDir}. Lambda uploads both to {@code streamapp-streams/{uploadId}/}.
 */
public final class HlsTranscodeCommand {

    private HlsTranscodeCommand() {}

    public static Path playlistPath(Path outputDir) {
        return outputDir.resolve(S3ObjectKeys.PLAYLIST_FILE);
    }

    public static Path mediaPath(Path outputDir) {
        return outputDir.resolve(S3ObjectKeys.MEDIA_FILE);
    }

    public static List<String> build(String ffmpegPath, Path localInput, Path outputDir) {
        var localManifest = playlistPath(outputDir);
        var localMediaFile = mediaPath(outputDir);

        return List.of(
                ffmpegPath,
                "-y",
                "-i",
                localInput.toString(),
                "-vcodec",
                "libx264",
                "-preset",
                "fast",
                "-crf",
                "22",
                "-c:a",
                "aac",
                "-b:a",
                "128k",
                "-f",
                "hls",
                "-hls_time",
                "4",
                "-hls_playlist_type",
                "vod",
                "-hls_segment_type",
                "fmp4",
                "-hls_flags",
                "single_file",
                "-hls_segment_filename",
                localMediaFile.toString(),
                localManifest.toString());
    }
}
