package com.cretas.aims.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Hibernate startup/query parsing gate for immutable Workflow settlement output rows. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class ProductionSettlementOutputLineRepositoryQueryValidationTest {

    @Autowired
    ProductionSettlementOutputLineRepository repository;

    @Test
    @Transactional
    void derivedFinderAndPessimisticLockQueryBootAgainstRealJpaContext() {
        assertThat(repository
                .findByFactoryIdAndSettlementIdOrderByProductTypeIdAscReportedBatchNumberAsc(
                        "F-JPA-OUTPUT", "SETTLEMENT-NOT-FOUND"))
                .isEmpty();
        assertThat(repository.lockByFactoryIdAndSettlementId(
                "F-JPA-OUTPUT", "SETTLEMENT-NOT-FOUND"))
                .isEmpty();
    }
}
