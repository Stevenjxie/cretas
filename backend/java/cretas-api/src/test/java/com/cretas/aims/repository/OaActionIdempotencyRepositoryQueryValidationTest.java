package com.cretas.aims.repository;

import com.cretas.aims.entity.workflow.OaActionIdempotencyLedger;
import com.cretas.aims.repository.workflow.OaActionIdempotencyLedgerRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class OaActionIdempotencyRepositoryQueryValidationTest {

    @Autowired private OaActionIdempotencyLedgerRepository repository;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void ledger_mapping_and_scoped_lookup_boot_in_real_jpa_context() {
        var entity = entityManager.getMetamodel().entity(OaActionIdempotencyLedger.class);
        assertThat(entity.getAttribute("requestFingerprint")).isNotNull();
        assertThat(entity.getAttribute("resultJson")).isNotNull();
        assertThat(repository.findByFactoryIdAndInstanceIdAndIdempotencyKey(
                "F-JPA-OA", "missing-instance", "missing-key")).isEmpty();
        Integer scopedUniqueColumns = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM information_schema.key_column_usage
                 WHERE constraint_schema = current_schema()
                   AND table_name = 'oa_action_idempotency_ledger'
                   AND constraint_name = 'uk_oa_action_idempotency_scope'
                   AND column_name IN ('factory_id', 'instance_id', 'idempotency_key')
                """, Integer.class);
        assertThat(scopedUniqueColumns).isEqualTo(3);
    }
}
