package dev.iamkavindu.streamapp.backend.video;

import dev.iamkavindu.streamapp.backend.support.IntegrationTest;
import dev.iamkavindu.streamapp.backend.video.model.VideoStatus;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class AttemptIdentityMigrationIntegrationTest {
    @Autowired DataSource dataSource;
    @Autowired DSLContext dsl;

    @Test
    void upgradesExistingRowsWithoutChangingVideoIdentityOrState() {
        var schema = "attempt_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        var table = DSL.table(DSL.name(schema, "videos"));
        var uploadId = DSL.field(DSL.name("upload_id"), UUID.class);
        var attemptId = DSL.field(DSL.name("active_attempt_id"), UUID.class);
        var status = DSL.field(DSL.name("status"), String.class);
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").target("1").load().migrate();
            for (var state : VideoStatus.values()) {
                dsl.insertInto(table).columns(uploadId, status,
                                DSL.field(DSL.name("created_at")), DSL.field(DSL.name("updated_at")))
                        .values(UUID.randomUUID(), state.name(), DSL.currentOffsetDateTime(), DSL.currentOffsetDateTime())
                        .execute();
            }
            var before = dsl.select(uploadId, status).from(table).orderBy(uploadId).fetch();
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").load().migrate();
            assertThat(dsl.select(uploadId, status).from(table).orderBy(uploadId).fetch()).isEqualTo(before);
            assertThat(dsl.select(uploadId, attemptId).from(table).fetch())
                    .allSatisfy(row -> assertThat(row.get(attemptId)).isEqualTo(row.get(uploadId)));
        } finally {
            dsl.dropSchemaIfExists(DSL.name(schema)).cascade().execute();
        }
    }
}
