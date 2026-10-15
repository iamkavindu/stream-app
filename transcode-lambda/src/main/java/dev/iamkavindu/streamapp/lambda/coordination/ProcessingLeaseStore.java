package dev.iamkavindu.streamapp.lambda.coordination;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Conditional S3 writes fence each execution and retain an immutable result for redelivery. */
public final class ProcessingLeaseStore {
    private final S3Client s3;
    private final ObjectMapper mapper;
    private final String bucket;
    private final Clock clock;
    private final Duration duration;

    public ProcessingLeaseStore(S3Client s3, ObjectMapper mapper, String bucket, Clock clock, Duration duration) {
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("Lease duration must be positive");
        }
        this.s3 = s3;
        this.mapper = mapper;
        this.bucket = bucket;
        this.clock = clock;
        this.duration = duration;
    }

    public Lease acquire(UUID uploadId, UUID attemptId) {
        Objects.requireNonNull(uploadId);
        Objects.requireNonNull(attemptId);
        String previousEtag = null;
        try {
            var object = s3.getObjectAsBytes(r -> r.bucket(bucket).key(key(uploadId, attemptId)));
            LeaseDocument document;
            try {
                document = mapper.readValue(object.asByteArray(), LeaseDocument.class);
            } catch (tools.jackson.core.JacksonException e) {
                throw new IllegalStateException("Invalid processing coordination document", e);
            }
            validate(document, uploadId, attemptId);
            previousEtag = requireEtag(object.response().eTag());
            if (document.completedResult() != null) {
                return lease(document, previousEtag);
            }
            if (clock.instant().isBefore(document.expiresAt())) {
                throw new LeaseUnavailableException();
            }
        } catch (S3Exception e) {
            if (e.statusCode() != 404) {
                throw e;
            }
            // A missing bucket is infrastructure failure, not an absent claim.
            s3.headBucket(r -> r.bucket(bucket));
        }

        var document = new LeaseDocument(1, uploadId, attemptId, UUID.randomUUID(),
                clock.instant().plus(duration), null);
        var request = PutObjectRequest.builder().bucket(bucket).key(key(uploadId, attemptId))
                .contentType("application/json");
        if (previousEtag == null) {
            request.ifNoneMatch("*");
        } else {
            request.ifMatch(previousEtag);
        }
        try {
            var result = s3.putObject(request.build(), RequestBody.fromString(mapper.writeValueAsString(document)));
            return lease(document, requireEtag(result.eTag()));
        } catch (S3Exception e) {
            if (e.statusCode() == 409 || e.statusCode() == 412) {
                throw new LeaseUnavailableException();
            }
            throw e;
        }
    }

    public String complete(Lease lease, String result) {
        if (lease.completedResult() != null || result == null || result.isBlank()
                || !clock.instant().isBefore(lease.expiresAt())) {
            throw new LeaseUnavailableException();
        }
        var document = new LeaseDocument(1, lease.uploadId(), lease.attemptId(), lease.executionId(),
                lease.expiresAt(), result);
        try {
            s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key(lease.uploadId(), lease.attemptId()))
                            .contentType("application/json").ifMatch(lease.etag()).build(),
                    RequestBody.fromString(mapper.writeValueAsString(document)));
            return result;
        } catch (S3Exception e) {
            if (e.statusCode() == 404 || e.statusCode() == 409 || e.statusCode() == 412) {
                throw new LeaseUnavailableException();
            }
            throw e;
        }
    }

    /** Release unfinished work conditionally; an already committed/replaced document is untouched. */
    public void release(Lease lease) {
        if (lease.completedResult() != null) {
            return;
        }
        var document = new LeaseDocument(1, lease.uploadId(), lease.attemptId(), lease.executionId(),
                clock.instant(), null);
        try {
            s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key(lease.uploadId(), lease.attemptId()))
                            .contentType("application/json").ifMatch(lease.etag()).build(),
                    RequestBody.fromString(mapper.writeValueAsString(document)));
        } catch (S3Exception e) {
            if (e.statusCode() != 404 && e.statusCode() != 409 && e.statusCode() != 412) {
                throw e;
            }
        }
    }

    private void validate(LeaseDocument document, UUID uploadId, UUID attemptId) {
        if (document == null || document.schemaVersion() != 1 || !uploadId.equals(document.uploadId())
                || !attemptId.equals(document.attemptId()) || document.executionId() == null
                || document.expiresAt() == null
                || (document.completedResult() != null && document.completedResult().isBlank())) {
            throw new IllegalStateException("Invalid processing coordination document");
        }
    }

    private Lease lease(LeaseDocument d, String etag) {
        return new Lease(d.uploadId(), d.attemptId(), d.executionId(), etag, d.expiresAt(), d.completedResult());
    }

    private String requireEtag(String etag) {
        if (etag == null || etag.isBlank()) {
            throw new IllegalStateException("Coordination write/read did not provide an ETag");
        }
        return etag;
    }

    public static String key(UUID uploadId, UUID attemptId) {
        return "_processing/" + uploadId + "/" + attemptId + ".json";
    }

    public record Lease(UUID uploadId, UUID attemptId, UUID executionId, String etag,
                        Instant expiresAt, String completedResult) {}

    public record LeaseDocument(int schemaVersion, UUID uploadId, UUID attemptId, UUID executionId,
                                Instant expiresAt, String completedResult) {}

    /** The invocation must fail and allow queue redelivery, never acknowledge unfinished work. */
    public static final class LeaseUnavailableException extends RuntimeException {
        public LeaseUnavailableException() {
            super("Processing lease is busy, expired, or no longer owned by this execution");
        }
    }
}
