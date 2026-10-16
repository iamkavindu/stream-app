package dev.iamkavindu.streamapp.backend.video.dispatch;

import dev.iamkavindu.streamapp.backend.aws.AwsMessagingResources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Component
public class ProcessingOutboxDispatcher {
    private static final Logger log = LoggerFactory.getLogger(ProcessingOutboxDispatcher.class);
    private final ProcessingOutboxRepository outbox;
    private final SqsAsyncClient sqs;
    public ProcessingOutboxDispatcher(ProcessingOutboxRepository outbox, SqsAsyncClient sqs) {
        this.outbox = outbox;
        this.sqs = sqs;
    }

    /** At most ten claims per pass; each claim is acquired immediately before its bounded send. */
    public void dispatchBatch() {
        for (int i = 0; i < 10 && !Thread.currentThread().isInterrupted(); i++) {
            var next = outbox.claimNext();
            if (next.isEmpty()) return;
            var claim = next.get();
            try {
                var queue = await(sqs.getQueueUrl(r -> r.queueName(AwsMessagingResources.UPLOAD_LAMBDA_QUEUE))).queueUrl();
                await(sqs.sendMessage(r -> r.queueUrl(queue).messageBody(claim.payload())));
            } catch (Exception e) {
                outbox.retryLater(claim);
                log.warn("Processing dispatch deferred for attempt {} ({})", claim.attemptId(), e.getClass().getSimpleName());
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }
            // A crash/DB failure here may resend after lease expiry. Worker retained-result replay makes it safe.
            outbox.delivered(claim);
        }
    }

    private <T> T await(CompletableFuture<T> result) throws Exception {
        try { return result.get(10, TimeUnit.SECONDS); }
        finally { if (!result.isDone()) result.cancel(true); }
    }
}
