package dev.iamkavindu.streamapp.backend.aws;

import java.nio.file.Path;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Shared S3 object-key layout for upload, transcode (Lambda), and HLS playback.
 * <p>
 * Upload bucket: {@code {uploadId}/{fileName}}
 * Stream bucket: {@code {uploadId}/index.m3u8} and {@code {uploadId}/media.mp4} (FFmpeg
 * {@code -hls_flags single_file} + {@code -hls_segment_type fmp4}).
 */
public final class S3ObjectKeys {

    public static final String PLAYLIST_FILE = "index.m3u8";

    /** Single fMP4 media file produced by FFmpeg {@code -hls_flags single_file}. */
    public static final String MEDIA_FILE = "media.mp4";

    private static final Pattern UNSAFE_FILE_NAME = Pattern.compile("[^a-zA-Z0-9._-]");

    private S3ObjectKeys() {}

    public static String uploadObjectKey(UUID uploadId, String fileName) {
        return uploadId + "/" + sanitizeFileName(fileName);
    }

    public static String streamPlaylistKey(UUID uploadId) {
        return uploadId + "/" + PLAYLIST_FILE;
    }

    public static String streamMediaKey(UUID uploadId) {
        return uploadId + "/" + MEDIA_FILE;
    }

    public static String streamPrefix(UUID uploadId) {
        return uploadId + "/";
    }

    public static String sanitizeFileName(String fileName) {
        var baseName = Path.of(fileName).getFileName().toString().trim();
        if (baseName.isEmpty()) {
            throw new IllegalArgumentException("fileName must not be empty");
        }
        return UNSAFE_FILE_NAME.matcher(baseName).replaceAll("_");
    }
}
