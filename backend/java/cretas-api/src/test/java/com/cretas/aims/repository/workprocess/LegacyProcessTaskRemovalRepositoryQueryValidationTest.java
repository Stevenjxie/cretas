package com.cretas.aims.repository.workprocess;

import com.cretas.aims.repository.ProductionReportRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Real Hibernate startup validation after removal of the legacy ProcessTask mapping. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@DisplayName("Canonical work-process task repositories JPA startup validation")
class LegacyProcessTaskRemovalRepositoryQueryValidationTest {

    @Autowired
    private WorkProcessTaskRepository workProcessTaskRepository;

    @Autowired
    private ProductionReportRepository productionReportRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("canonical task and report repositories start without ProcessTask")
    void canonicalRepositoriesBootWithoutLegacyProcessTask() {
        assertNotNull(workProcessTaskRepository);
        assertNotNull(productionReportRepository);

        var entityNames = entityManager.getMetamodel().getEntities().stream()
                .map(entityType -> entityType.getJavaType().getSimpleName())
                .toList();
        assertThat(entityNames).contains("WorkProcessTask", "ProductionReport");
        assertThat(entityNames).doesNotContain("ProcessTask");
    }
}
