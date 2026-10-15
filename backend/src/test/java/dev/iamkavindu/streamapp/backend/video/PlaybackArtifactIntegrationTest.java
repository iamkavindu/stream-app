package dev.iamkavindu.streamapp.backend.video;

import dev.iamkavindu.streamapp.backend.aws.S3ObjectKeys;
import dev.iamkavindu.streamapp.backend.support.IntegrationTest;
import dev.iamkavindu.streamapp.backend.support.MessagingFixtures;
import dev.iamkavindu.streamapp.backend.support.TestData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
@Transactional
class PlaybackArtifactIntegrationTest {
    @Autowired VideoRepository repository;
    @Autowired VideoService service;

    @Test
    void winningArtifactIsPersistedAndPlaybackUsesItsPathWithoutDuplicateRegression() {
        var id = create();
        var execution = UUID.randomUUID();
        var key = S3ObjectKeys.executionPlaylistKey(id, id, execution);
        var message = fixture(id, id, execution);
        service.onTranscodeComplete(message);
        assertThat(repository.findPlaybackArtifact(id).orElseThrow().playlistKey()).isEqualTo(key);
        assertThat(service.createSignedGetUrl(id).objectKey()).isEqualTo(key);
        service.onTranscodeComplete(message);
        service.onTranscodeComplete(MessagingFixtures.transcodeCompleteMessage(id, "FAILED"));
        service.onTranscodeComplete(fixture(id, id, UUID.randomUUID()));
        assertThat(repository.findPlaybackArtifact(id).orElseThrow().playlistKey()).isEqualTo(key);
    }

    @Test
    void legacyReadyVideoKeepsItsFixedPlaybackPath() {
        var id = create();
        service.onTranscodeComplete(MessagingFixtures.transcodeCompleteMessage(id, "PLAY_READY"));
        assertThat(repository.findPlaybackArtifact(id)).isEmpty();
        assertThat(service.createSignedGetUrl(id).objectKey()).isEqualTo(S3ObjectKeys.streamPlaylistKey(id));
    }

    @Test
    void wrongAttemptOrInvalidArtifactCannotSettleVideo() {
        var id = create();
        var before = repository.findAttempts(id);
        service.onTranscodeComplete(fixture(id, UUID.randomUUID(), UUID.randomUUID()));
        var valid = fixture(id, id, UUID.randomUUID());
        for (var path : new String[] {"other/index.m3u8", id + "/attempts/" + id + "/executions/../index.m3u8",
                S3ObjectKeys.executionPlaylistKey(UUID.randomUUID(), id, UUID.randomUUID())}) {
            var json = new tools.jackson.databind.ObjectMapper().readTree(valid);
            ((tools.jackson.databind.node.ObjectNode) json).put("playlistKey", path);
            service.onTranscodeComplete(json.toString());
        }
        assertThat(repository.findAttempts(id)).isEqualTo(before);
        assertThat(repository.findPlaybackArtifact(id)).isEmpty();
    }

    private String fixture(UUID id, UUID attemptId, UUID executionId) {
        return MessagingFixtures.loadTemplate("messaging/transcode-complete-execution-v2.json")
                .replace("{{uploadId}}", id.toString()).replace("{{attemptId}}", attemptId.toString())
                .replace("{{executionId}}", executionId.toString());
    }

    private UUID create() {
        var id = UUID.randomUUID();
        repository.createPendingUploadEntry(id, "demo.mp4", TestData.uniqueShaBytes());
        return id;
    }
}
