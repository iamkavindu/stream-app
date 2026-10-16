package dev.iamkavindu.streamapp.backend.video.dispatch;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import static dev.iamkavindu.streamapp.backend.jooq.tables.ProcessingOutbox.PROCESSING_OUTBOX;

@Repository
public class ProcessingOutboxRepository {
    private final DSLContext dsl;
    public ProcessingOutboxRepository(DSLContext dsl) { this.dsl = dsl; }

    public record Claim(UUID attemptId, UUID claimId, String payload, int deliveryAttempts) {}

    /** Short DB transaction only; no network work while row locks are held. */
    @Transactional
    public Optional<Claim> claimNext() {
        var row = dsl.selectFrom(PROCESSING_OUTBOX)
                .where(PROCESSING_OUTBOX.DISPATCHED_AT.isNull())
                .and(PROCESSING_OUTBOX.NEXT_ATTEMPT_AT.le(DSL.currentOffsetDateTime()))
                .and(PROCESSING_OUTBOX.CLAIM_UNTIL.isNull()
                        .or(PROCESSING_OUTBOX.CLAIM_UNTIL.le(DSL.currentOffsetDateTime())))
                .orderBy(PROCESSING_OUTBOX.CREATED_AT, PROCESSING_OUTBOX.ATTEMPT_ID)
                .limit(1).forUpdate().skipLocked().fetchOne();
        if (row == null) return Optional.empty();
        var token = UUID.randomUUID();
        dsl.update(PROCESSING_OUTBOX)
                .set(PROCESSING_OUTBOX.CLAIM_ID, token)
                .set(PROCESSING_OUTBOX.CLAIM_UNTIL, deadline(60))
                .set(PROCESSING_OUTBOX.DELIVERY_ATTEMPTS, PROCESSING_OUTBOX.DELIVERY_ATTEMPTS.add(1))
                .where(PROCESSING_OUTBOX.ATTEMPT_ID.eq(row.getAttemptId())).execute();
        return Optional.of(new Claim(row.getAttemptId(), token, row.getPayload(), row.getDeliveryAttempts() + 1));
    }

    @Transactional
    public boolean delivered(Claim claim) {
        return dsl.update(PROCESSING_OUTBOX)
                .set(PROCESSING_OUTBOX.DISPATCHED_AT, DSL.currentOffsetDateTime())
                .set(PROCESSING_OUTBOX.CLAIM_ID, (UUID) null)
                .set(PROCESSING_OUTBOX.CLAIM_UNTIL, (OffsetDateTime) null)
                .set(PROCESSING_OUTBOX.LAST_ERROR, (String) null)
                .where(owned(claim)).execute() == 1;
    }

    @Transactional
    public boolean retryLater(Claim claim) {
        long delay = Math.min(300L, 5L << Math.min(Math.max(claim.deliveryAttempts() - 1, 0), 6));
        return dsl.update(PROCESSING_OUTBOX)
                .set(PROCESSING_OUTBOX.NEXT_ATTEMPT_AT, deadline(delay))
                .set(PROCESSING_OUTBOX.CLAIM_ID, (UUID) null)
                .set(PROCESSING_OUTBOX.CLAIM_UNTIL, (OffsetDateTime) null)
                .set(PROCESSING_OUTBOX.LAST_ERROR, "SQS_DISPATCH_FAILED")
                .where(owned(claim)).execute() == 1;
    }

    private org.jooq.Condition owned(Claim claim) {
        return PROCESSING_OUTBOX.ATTEMPT_ID.eq(claim.attemptId())
                .and(PROCESSING_OUTBOX.CLAIM_ID.eq(claim.claimId()))
                .and(PROCESSING_OUTBOX.CLAIM_UNTIL.gt(DSL.currentOffsetDateTime()))
                .and(PROCESSING_OUTBOX.DISPATCHED_AT.isNull());
    }

    private Field<OffsetDateTime> deadline(long seconds) {
        return DSL.field("current_timestamp + ({0} * interval '1 second')", OffsetDateTime.class, DSL.val(seconds));
    }
}
