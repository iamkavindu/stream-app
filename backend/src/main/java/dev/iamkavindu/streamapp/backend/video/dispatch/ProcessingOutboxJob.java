package dev.iamkavindu.streamapp.backend.video.dispatch;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "streamapp.processing.dispatch.enabled", havingValue = "true", matchIfMissing = true)
public class ProcessingOutboxJob {
    private final ProcessingOutboxDispatcher dispatcher;
    public ProcessingOutboxJob(ProcessingOutboxDispatcher dispatcher) { this.dispatcher = dispatcher; }
    @Scheduled(fixedDelayString = "${streamapp.processing.dispatch.interval:5s}", initialDelayString = "5s")
    public void dispatch() { dispatcher.dispatchBatch(); }
}
