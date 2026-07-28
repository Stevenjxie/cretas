package com.cretas.aims.repository;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Contract gate for the PostgreSQL-only partial index and revision FK migration. */
class WorkflowPublishIdempotencyMigrationTest {

    @Test
    void migrationDefinesDurableIdentityPartialUniqueIndexAndRevisionForeignKey()
            throws IOException {
        String resource =
                "db/flyway/V20261029_29__workflow_publish_idempotency.sql";
        try (var stream = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(resource)) {
            assertThat(stream).as(resource).isNotNull();
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(sql)
                    .contains("last_publish_idempotency_key VARCHAR(128)")
                    .contains("last_publish_revision_id BIGINT")
                    .contains("last_publish_revision_hash VARCHAR(64)")
                    .contains("last_publish_definition_version INTEGER")
                    .contains("CREATE UNIQUE INDEX IF NOT EXISTS "
                            + "uk_ppw_publish_idempotency_factory_key")
                    .contains("ON product_process_workflows"
                            + "(factory_id, last_publish_idempotency_key)")
                    .contains("WHERE deleted_at IS NULL")
                    .contains("FOREIGN KEY (last_publish_revision_id)")
                    .contains("REFERENCES product_process_workflow_revisions(id)");
        }
    }
}
