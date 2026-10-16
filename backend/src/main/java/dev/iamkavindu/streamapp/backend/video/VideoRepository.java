package dev.iamkavindu.streamapp.backend.video;

import dev.iamkavindu.streamapp.backend.jooq.tables.records.VideosRecord;
import dev.iamkavindu.streamapp.backend.video.model.VideoRecord;
import dev.iamkavindu.streamapp.backend.video.model.VideoStatus;
import dev.iamkavindu.streamapp.backend.video.model.ProcessingAttemptRecord;
import dev.iamkavindu.streamapp.backend.video.model.PlaybackArtifactRecord;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Duration;

import static dev.iamkavindu.streamapp.backend.jooq.tables.ProcessingOutbox.PROCESSING_OUTBOX;
import static dev.iamkavindu.streamapp.backend.jooq.tables.Videos.VIDEOS;
import static dev.iamkavindu.streamapp.backend.jooq.tables.ProcessingAttempts.PROCESSING_ATTEMPTS;

@Repository
public class VideoRepository {

    private final DSLContext dsl;

    public VideoRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Transactional
    public void createPendingUploadEntry(UUID uploadId, String fileName, byte[] sha256Hex) {
        var now = OffsetDateTime.now(ZoneId.systemDefault());
        dsl.insertInto(VIDEOS)
                .set(VIDEOS.UPLOAD_ID, uploadId)
                .set(VIDEOS.ACTIVE_ATTEMPT_ID, uploadId)
                .set(VIDEOS.STATUS, VideoStatus.AWAITING_UPLOAD.name())
                .set(VIDEOS.FILE_NAME, fileName)
                .set(VIDEOS.SHA256_HASH, sha256Hex)
                .set(VIDEOS.CREATED_AT, now)
                .set(VIDEOS.UPDATED_AT, now)
                .execute();
        dsl.insertInto(PROCESSING_ATTEMPTS)
                .set(PROCESSING_ATTEMPTS.ATTEMPT_ID, uploadId)
                .set(PROCESSING_ATTEMPTS.UPLOAD_ID, uploadId)
                .set(PROCESSING_ATTEMPTS.STAGE, "AWAITING_UPLOAD")
                .set(PROCESSING_ATTEMPTS.CREATED_AT, now)
                .execute();
    }

    /** Caller locks the video first; this transaction also defends against a changed terminal state. */
    @Transactional
    public void createProcessingRetry(UUID uploadId, UUID attemptId, String payload) {
        var now = OffsetDateTime.now();
        if (dsl.update(VIDEOS).set(VIDEOS.ACTIVE_ATTEMPT_ID, attemptId)
                .set(VIDEOS.STATUS, VideoStatus.TRANSCODING_IN_PROGRESS.name()).set(VIDEOS.UPDATED_AT, now)
                .where(VIDEOS.UPLOAD_ID.eq(uploadId)).and(VIDEOS.STATUS.eq(VideoStatus.FAILED.name())).execute() != 1) {
            throw new IllegalStateException("Processing retry requires a failed video");
        }
        dsl.insertInto(PROCESSING_ATTEMPTS).set(PROCESSING_ATTEMPTS.ATTEMPT_ID, attemptId)
                .set(PROCESSING_ATTEMPTS.UPLOAD_ID, uploadId).set(PROCESSING_ATTEMPTS.STAGE, "QUEUED")
                .set(PROCESSING_ATTEMPTS.CREATED_AT, now).set(PROCESSING_ATTEMPTS.QUEUED_AT, now).execute();
        dsl.insertInto(PROCESSING_OUTBOX).set(PROCESSING_OUTBOX.ATTEMPT_ID, attemptId)
                .set(PROCESSING_OUTBOX.UPLOAD_ID, uploadId).set(PROCESSING_OUTBOX.PAYLOAD, payload).execute();
    }

    public List<ProcessingAttemptRecord> findAttempts(UUID uploadId) {
        return dsl.select(PROCESSING_ATTEMPTS.fields()).select(VIDEOS.ACTIVE_ATTEMPT_ID)
                .from(PROCESSING_ATTEMPTS).join(VIDEOS)
                .on(PROCESSING_ATTEMPTS.UPLOAD_ID.eq(VIDEOS.UPLOAD_ID))
                .where(PROCESSING_ATTEMPTS.UPLOAD_ID.eq(uploadId))
                .orderBy(PROCESSING_ATTEMPTS.CREATED_AT.asc(), PROCESSING_ATTEMPTS.ATTEMPT_ID.asc())
                .fetch(r -> new ProcessingAttemptRecord(
                        r.get(PROCESSING_ATTEMPTS.ATTEMPT_ID), r.get(PROCESSING_ATTEMPTS.UPLOAD_ID),
                        r.get(PROCESSING_ATTEMPTS.ATTEMPT_ID).equals(r.get(VIDEOS.ACTIVE_ATTEMPT_ID)),
                        r.get(PROCESSING_ATTEMPTS.STAGE), r.get(PROCESSING_ATTEMPTS.CREATED_AT),
                        r.get(PROCESSING_ATTEMPTS.UPLOAD_OBSERVED_AT), r.get(PROCESSING_ATTEMPTS.COMPLETED_AT),
                        r.get(PROCESSING_ATTEMPTS.FAILURE_CODE), r.get(PROCESSING_ATTEMPTS.FAILURE_DETAIL),
                        r.get(PROCESSING_ATTEMPTS.IMPORTED), r.get(PROCESSING_ATTEMPTS.QUEUED_AT)));
    }

    public Optional<PlaybackArtifactRecord> findPlaybackArtifact(UUID uploadId) {
        return dsl.select(PROCESSING_ATTEMPTS.ATTEMPT_ID, PROCESSING_ATTEMPTS.PLAYLIST_KEY)
                .from(PROCESSING_ATTEMPTS).join(VIDEOS)
                .on(PROCESSING_ATTEMPTS.UPLOAD_ID.eq(VIDEOS.UPLOAD_ID)
                        .and(PROCESSING_ATTEMPTS.ATTEMPT_ID.eq(VIDEOS.ACTIVE_ATTEMPT_ID)))
                .where(VIDEOS.UPLOAD_ID.eq(uploadId)).and(PROCESSING_ATTEMPTS.STAGE.eq("SUCCEEDED"))
                .and(PROCESSING_ATTEMPTS.PLAYLIST_KEY.isNotNull())
                .fetchOptional(r -> new PlaybackArtifactRecord(r.get(PROCESSING_ATTEMPTS.ATTEMPT_ID),
                        r.get(PROCESSING_ATTEMPTS.PLAYLIST_KEY)));
    }

    public List<VideoRecord> findAll() {
        return dsl.selectFrom(VIDEOS)
                .orderBy(VIDEOS.CREATED_AT.desc())
                .fetch(this::toVideoRecord);
    }

    public Optional<VideoRecord> findByUploadId(UUID uploadId) {
        return dsl.selectFrom(VIDEOS)
                .where(VIDEOS.UPLOAD_ID.eq(uploadId))
                .fetchOptional(this::toVideoRecord);
    }

    public Optional<VideoRecord> findByUploadIdForUpdate(UUID uploadId) {
        return dsl.selectFrom(VIDEOS)
                .where(VIDEOS.UPLOAD_ID.eq(uploadId))
                .forUpdate()
                .fetchOptional(this::toVideoRecord);
    }

    /** Refresh activity without changing the upload's identity, hash, or status. */
    public void refreshAwaitingUpload(UUID uploadId) {
        dsl.update(VIDEOS)
                .set(VIDEOS.UPDATED_AT, OffsetDateTime.now(ZoneId.systemDefault()))
                .where(VIDEOS.UPLOAD_ID.eq(uploadId))
                .and(VIDEOS.STATUS.eq(VideoStatus.AWAITING_UPLOAD.name()))
                .execute();
    }

    /**
     * @return {@code true} when a row matched {@code uploadId} and {@code expectedStatus}
     */
    @Transactional
    public boolean updateStatus(UUID uploadId, VideoStatus expectedStatus, VideoStatus newStatus) {
        var now = OffsetDateTime.now(ZoneId.systemDefault());
        var updated = dsl.update(VIDEOS)
                .set(VIDEOS.STATUS, newStatus.name())
                .set(VIDEOS.UPDATED_AT, now)
                .where(VIDEOS.UPLOAD_ID.eq(uploadId))
                .and(VIDEOS.STATUS.eq(expectedStatus.name()))
                .returning(VIDEOS.ACTIVE_ATTEMPT_ID).fetchOne();
        if (updated == null) {
            return false;
        }
        var attemptId = updated.getActiveAttemptId();
        if (newStatus == VideoStatus.TRANSCODING_IN_PROGRESS) {
            requireAttemptUpdate(dsl.update(PROCESSING_ATTEMPTS)
                    .set(PROCESSING_ATTEMPTS.STAGE, "UPLOAD_OBSERVED")
                    .set(PROCESSING_ATTEMPTS.UPLOAD_OBSERVED_AT, now)
                    .where(PROCESSING_ATTEMPTS.ATTEMPT_ID.eq(attemptId))
                    .and(PROCESSING_ATTEMPTS.UPLOAD_ID.eq(uploadId)).execute());
        } else if (newStatus == VideoStatus.PLAY_READY || newStatus == VideoStatus.FAILED) {
            settleAttempt(uploadId, attemptId, newStatus, now, null, null);
        }
        return true;
    }

    /**
     * Legacy results may settle only the initial attempt, whose identity equals uploadId.
     * Terminal rows are excluded atomically, so duplicate/conflicting results cannot overwrite them.
     * @return {@code true} when an active row was settled
     */
    @Transactional
    public boolean completeTranscode(UUID uploadId, VideoStatus terminalStatus) {
        return completeTranscode(uploadId, uploadId, terminalStatus);
    }

    /** Settles only the matching active attempt; stale results cannot mutate status or activity. */
    @Transactional
    public boolean completeTranscode(UUID uploadId, UUID attemptId, VideoStatus terminalStatus) {
        return completeTranscode(uploadId, attemptId, terminalStatus, null, null);
    }

    @Transactional
    public boolean completeTranscode(UUID uploadId, UUID attemptId, VideoStatus terminalStatus,
            String failureCode, String failureDetail) {
        return completeTranscode(uploadId, attemptId, terminalStatus, failureCode, failureDetail, null);
    }

    @Transactional
    public boolean completeTranscode(UUID uploadId, UUID attemptId, VideoStatus terminalStatus,
            String failureCode, String failureDetail, String playlistKey) {
        if (attemptId == null) {
            throw new IllegalArgumentException("Transcode result must identify an attempt");
        }
        if (terminalStatus != VideoStatus.PLAY_READY && terminalStatus != VideoStatus.FAILED) {
            throw new IllegalArgumentException("Transcode result must be PLAY_READY or FAILED");
        }
        var now = OffsetDateTime.now(ZoneId.systemDefault());
        var updated = dsl.update(VIDEOS)
                .set(VIDEOS.STATUS, terminalStatus.name())
                .set(VIDEOS.UPDATED_AT, now)
                .where(VIDEOS.UPLOAD_ID.eq(uploadId))
                .and(VIDEOS.ACTIVE_ATTEMPT_ID.eq(attemptId))
                .and(VIDEOS.STATUS.in(
                        VideoStatus.AWAITING_UPLOAD.name(), VideoStatus.TRANSCODING_IN_PROGRESS.name()))
                .execute();
        if (updated == 0) {
            return false;
        }
        settleAttempt(uploadId, attemptId, terminalStatus, now, failureCode, failureDetail, playlistKey);
        return true;
    }

    private void settleAttempt(UUID uploadId, UUID attemptId, VideoStatus status, OffsetDateTime now,
            String failureCode, String failureDetail) {
        settleAttempt(uploadId, attemptId, status, now, failureCode, failureDetail, null);
    }

    private void settleAttempt(UUID uploadId, UUID attemptId, VideoStatus status, OffsetDateTime now,
            String failureCode, String failureDetail, String playlistKey) {
        requireAttemptUpdate(dsl.update(PROCESSING_ATTEMPTS)
                .set(PROCESSING_ATTEMPTS.STAGE, status == VideoStatus.PLAY_READY ? "SUCCEEDED" : "FAILED")
                .set(PROCESSING_ATTEMPTS.COMPLETED_AT, now)
                .set(PROCESSING_ATTEMPTS.FAILURE_CODE, status == VideoStatus.FAILED
                        ? (failureCode == null ? "TRANSCODE_FAILED" : failureCode) : null)
                .set(PROCESSING_ATTEMPTS.FAILURE_DETAIL, status == VideoStatus.FAILED ? failureDetail : null)
                .set(PROCESSING_ATTEMPTS.PLAYLIST_KEY, status == VideoStatus.PLAY_READY ? playlistKey : null)
                .where(PROCESSING_ATTEMPTS.ATTEMPT_ID.eq(attemptId))
                .and(PROCESSING_ATTEMPTS.UPLOAD_ID.eq(uploadId)).execute());
    }

    private void requireAttemptUpdate(int updated) {
        if (updated != 1) {
            throw new IllegalStateException("Active processing attempt history is missing");
        }
    }

    /**
     * @return number of rows transitioned to {@code FAILED}
     */
    @Transactional
    public int markStaleAwaitingUploadsFailed(Duration ttl) {
        var cutoff = OffsetDateTime.now(ZoneId.systemDefault()).minus(ttl);
        var now = OffsetDateTime.now(ZoneId.systemDefault());
        var expired = dsl.update(VIDEOS)
                .set(VIDEOS.STATUS, VideoStatus.FAILED.name())
                .set(VIDEOS.UPDATED_AT, now)
                .where(VIDEOS.STATUS.eq(VideoStatus.AWAITING_UPLOAD.name()))
                .and(VIDEOS.UPDATED_AT.lt(cutoff))
                .returning(VIDEOS.ACTIVE_ATTEMPT_ID, VIDEOS.UPLOAD_ID).fetch();
        for (var video : expired) {
            requireAttemptUpdate(dsl.update(PROCESSING_ATTEMPTS)
                    .set(PROCESSING_ATTEMPTS.STAGE, "UPLOAD_EXPIRED")
                    .set(PROCESSING_ATTEMPTS.COMPLETED_AT, now)
                    .set(PROCESSING_ATTEMPTS.FAILURE_CODE, "UPLOAD_EXPIRED")
                    .set(PROCESSING_ATTEMPTS.FAILURE_DETAIL, "Upload session exceeded its inactivity limit.")
                    .where(PROCESSING_ATTEMPTS.ATTEMPT_ID.eq(video.getActiveAttemptId()))
                    .and(PROCESSING_ATTEMPTS.UPLOAD_ID.eq(video.getUploadId())).execute());
        }
        return expired.size();
    }

    private VideoRecord toVideoRecord(VideosRecord record) {
        return new VideoRecord(
                record.getUploadId(),
                VideoStatus.valueOf(record.getStatus()),
                record.getFileName(),
                record.getCreatedAt(),
                record.getUpdatedAt()
        );
    }
}
