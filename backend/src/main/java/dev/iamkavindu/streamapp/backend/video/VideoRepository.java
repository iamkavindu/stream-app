package dev.iamkavindu.streamapp.backend.video;

import dev.iamkavindu.streamapp.backend.jooq.tables.records.VideosRecord;
import dev.iamkavindu.streamapp.backend.video.model.VideoRecord;
import dev.iamkavindu.streamapp.backend.video.model.VideoStatus;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Duration;

import static dev.iamkavindu.streamapp.backend.jooq.tables.Videos.VIDEOS;

@Repository
public class VideoRepository {

    private final DSLContext dsl;

    public VideoRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void createPendingUploadEntry(UUID uploadId, String fileName, byte[] sha256Hex) {
        var now = OffsetDateTime.now(ZoneId.systemDefault());
        dsl.insertInto(VIDEOS)
                .set(VIDEOS.UPLOAD_ID, uploadId)
                .set(VIDEOS.STATUS, VideoStatus.AWAITING_UPLOAD.name())
                .set(VIDEOS.FILE_NAME, fileName)
                .set(VIDEOS.SHA256_HASH, sha256Hex)
                .set(VIDEOS.CREATED_AT, now)
                .set(VIDEOS.UPDATED_AT, now)
                .execute();
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

    /**
     * @return {@code true} when a row matched {@code uploadId} and {@code expectedStatus}
     */
    public boolean updateStatus(UUID uploadId, VideoStatus expectedStatus, VideoStatus newStatus) {
        var now = OffsetDateTime.now(ZoneId.systemDefault());
        var updated = dsl.update(VIDEOS)
                .set(VIDEOS.STATUS, newStatus.name())
                .set(VIDEOS.UPDATED_AT, now)
                .where(VIDEOS.UPLOAD_ID.eq(uploadId))
                .and(VIDEOS.STATUS.eq(expectedStatus.name()))
                .execute();
        return updated > 0;
    }

    /**
     * @return number of rows transitioned to {@code FAILED}
     */
    public int markStaleAwaitingUploadsFailed(Duration ttl) {
        var cutoff = OffsetDateTime.now(ZoneId.systemDefault()).minus(ttl);
        var now = OffsetDateTime.now(ZoneId.systemDefault());
        return dsl.update(VIDEOS)
                .set(VIDEOS.STATUS, VideoStatus.FAILED.name())
                .set(VIDEOS.UPDATED_AT, now)
                .where(VIDEOS.STATUS.eq(VideoStatus.AWAITING_UPLOAD.name()))
                .and(VIDEOS.CREATED_AT.lt(cutoff))
                .execute();
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
