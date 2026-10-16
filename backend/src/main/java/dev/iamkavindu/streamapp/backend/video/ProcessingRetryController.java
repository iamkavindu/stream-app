package dev.iamkavindu.streamapp.backend.video;

import dev.iamkavindu.streamapp.backend.video.model.ProcessingRetryRecord;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/videos")
public class ProcessingRetryController {
    private final ProcessingRetryService retries;
    public ProcessingRetryController(ProcessingRetryService retries) {
        this.retries = retries;
    }
    @PostMapping("/{uploadId}/processing-retries")
    public ResponseEntity<ProcessingRetryRecord> retry(@PathVariable UUID uploadId) {
        return ResponseEntity.accepted().body(retries.retry(uploadId));
    }
}
