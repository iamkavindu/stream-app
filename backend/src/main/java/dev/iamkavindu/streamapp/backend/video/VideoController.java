package dev.iamkavindu.streamapp.backend.video;

import dev.iamkavindu.streamapp.backend.video.model.SignedGetUrlRecord;
import dev.iamkavindu.streamapp.backend.video.model.SignedUrlCreateRequest;
import dev.iamkavindu.streamapp.backend.video.model.SignedUrlCreatedRecord;
import dev.iamkavindu.streamapp.backend.video.model.VideoRecord;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/videos")
public class VideoController {

    private final VideoService videoService;

    public VideoController(VideoService videoService) {
        this.videoService = videoService;
    }

    @GetMapping
    ResponseEntity<List<VideoRecord>> listVideos() {
        return ResponseEntity.ok(videoService.listVideos());
    }

    @GetMapping("/{uploadId}/signed-url")
    ResponseEntity<SignedGetUrlRecord> getSignedGetUrl(@PathVariable UUID uploadId) {
        return ResponseEntity.ok(videoService.createSignedGetUrl(uploadId));
    }

    @PostMapping
    ResponseEntity<SignedUrlCreatedRecord> createSignedUpload(@Valid @RequestBody SignedUrlCreateRequest request) {
        var response = videoService.createSignedUploadUrl(request.fileName(), request.sha256Hex());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
