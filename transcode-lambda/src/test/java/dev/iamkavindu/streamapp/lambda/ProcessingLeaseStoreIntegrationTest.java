package dev.iamkavindu.streamapp.lambda;

import dev.iamkavindu.streamapp.lambda.coordination.ProcessingLeaseStore;
import dev.iamkavindu.streamapp.lambda.coordination.ProcessingLeaseStore.LeaseUnavailableException;
import dev.iamkavindu.streamapp.lambda.support.LambdaIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@LambdaIntegrationTest
class ProcessingLeaseStoreIntegrationTest {
    private static final String BUCKET = "streamapp-streams";
    @Autowired S3Client s3;
    @Autowired ObjectMapper mapper;

    @Test
    void competingExecutionsHaveExactlyOneOwner() throws Exception {
        var id = UUID.randomUUID();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var task = (java.util.concurrent.Callable<Boolean>) () -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start barrier timed out");
                try {
                    store(Clock.systemUTC()).acquire(id, id);
                    return true;
                } catch (LeaseUnavailableException e) {
                    return false;
                }
            };
            var first = executor.submit(task);
            var second = executor.submit(task);
            start.countDown();
            assertThat(java.util.List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
    }

    @Test
    void expiredClaimCanBeReplacedAndOldEtagCannotCommit() {
        var id = UUID.randomUUID();
        var clock = Clock.fixed(java.time.Instant.now(), java.time.ZoneOffset.UTC);
        var originalStore = store(clock);
        var first = originalStore.acquire(id, id);
        var replacementStore = store(Clock.offset(clock, Duration.ofMinutes(11)));
        var replacement = replacementStore.acquire(id, id);
        assertThat(replacement.executionId()).isNotEqualTo(first.executionId());
        // Original clock remains within the old lease, exercising the S3 If-Match fence.
        assertThatThrownBy(() -> originalStore.complete(first, "stale-result"))
                .isInstanceOf(LeaseUnavailableException.class);
        replacementStore.complete(replacement, "winning-result");
        assertThat(originalStore.acquire(id, id).completedResult()).isEqualTo("winning-result");
    }

    @Test
    void durableResultSurvivesRestartAndCannotBeOverwritten() {
        var id = UUID.randomUUID();
        var owner = store(Clock.systemUTC());
        var lease = owner.acquire(id, id);
        owner.complete(lease, "result-to-republish");
        assertThatThrownBy(() -> owner.complete(lease, "conflicting-result"))
                .isInstanceOf(LeaseUnavailableException.class);
        var restarted = store(Clock.offset(Clock.systemUTC(), Duration.ofDays(1)));
        assertThat(restarted.acquire(id, id).completedResult()).isEqualTo("result-to-republish");
    }

    @Test
    void expiryWithoutReplacementStillPreventsCommit() {
        var id = UUID.randomUUID();
        var clock = Clock.systemUTC();
        var lease = store(clock).acquire(id, id);
        assertThatThrownBy(() -> store(Clock.offset(clock, Duration.ofMinutes(11))).complete(lease, "late"))
                .isInstanceOf(LeaseUnavailableException.class);
    }

    @Test
    void releaseAllowsNewExecutionButCannotEraseCommittedResult() {
        var id = UUID.randomUUID();
        var store = store(Clock.systemUTC());
        var first = store.acquire(id, id);
        store.release(first);
        var second = store.acquire(id, id);
        assertThat(second.executionId()).isNotEqualTo(first.executionId());
        store.release(first);
        store.complete(second, "committed");
        store.release(second);
        store.release(store.acquire(id, id));
        assertThat(store.acquire(id, id).completedResult()).isEqualTo("committed");
    }

    @Test
    void malformedCoordinationIsNotTreatedAsAnAbsentClaim() {
        var id = UUID.randomUUID();
        s3.putObject(r -> r.bucket(BUCKET).key(ProcessingLeaseStore.key(id, id)), RequestBody.fromString("{}"));
        assertThatThrownBy(() -> store(Clock.systemUTC()).acquire(id, id))
                .isInstanceOf(IllegalStateException.class);
    }

    private ProcessingLeaseStore store(Clock clock) {
        return new ProcessingLeaseStore(s3, mapper, BUCKET, clock, Duration.ofMinutes(10));
    }
}
