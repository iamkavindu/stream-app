package dev.iamkavindu.streamapp.backend.aws;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class S3ObjectKeysTest {

  @Test
  void uploadObjectKey_usesUploadIdPrefixAndSanitizedFileName() {
    var uploadId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    assertEquals(
        "550e8400-e29b-41d4-a716-446655440000/demo.mp4",
        S3ObjectKeys.uploadObjectKey(uploadId, "demo.mp4"));
  }

  @Test
  void uploadObjectKey_stripsPathSegmentsFromFileName() {
    var uploadId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    assertEquals(
        "550e8400-e29b-41d4-a716-446655440000/evil.mp4",
        S3ObjectKeys.uploadObjectKey(uploadId, "../../evil.mp4"));
  }

  @Test
  void streamPlaylistKey_pointsToHlsManifest() {
    var uploadId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    assertEquals(
        "550e8400-e29b-41d4-a716-446655440000/index.m3u8",
        S3ObjectKeys.streamPlaylistKey(uploadId));
  }

  @Test
  void streamMediaKey_pointsToSingleFmp4MediaFile() {
    var uploadId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    assertEquals(
        "550e8400-e29b-41d4-a716-446655440000/media.mp4",
        S3ObjectKeys.streamMediaKey(uploadId));
  }

  @Test
  void sanitizeFileName_rejectsBlank() {
    assertThrows(IllegalArgumentException.class, () -> S3ObjectKeys.sanitizeFileName("  "));
  }
}
