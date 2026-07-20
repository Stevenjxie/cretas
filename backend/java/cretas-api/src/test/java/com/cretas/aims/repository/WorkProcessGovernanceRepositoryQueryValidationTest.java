package com.cretas.aims.repository;

import com.cretas.aims.dto.WorkProcessDTO;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.WorkProcessGovernanceAudit;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.entity.enums.WorkProcessOutputMaterialKind;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Hibernate/JPA startup gate for duplicate-governance locks and selectors. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class WorkProcessGovernanceRepositoryQueryValidationTest {

    @Autowired TestEntityManager entityManager;
    @Autowired WorkProcessRepository workProcessRepository;
    @Autowired WorkProcessGovernanceAuditRepository auditRepository;

    @Test
    void repositoriesBootAndFutureSelectorExcludesMergedProcesses() {
        Factory factory = new Factory();
        factory.setId("F-WP-JPA");
        factory.setName("Work-process governance JPA gate");
        factory.setType(FactoryType.FACTORY);
        factory.setLevel(0);
        factory.setIsActive(true);
        factory.setManuallyVerified(false);
        factory.setAiWeeklyQuota(20);
        entityManager.persist(factory);

        WorkProcess master = process("wp-master", factory.getId(), "包装", null);
        WorkProcess merged = process("wp-merged", factory.getId(), "包装", master.getId());
        merged.setIsActive(false);
        entityManager.persist(master);
        entityManager.persist(merged);

        WorkProcessGovernanceAudit audit = WorkProcessGovernanceAudit.builder()
                .id("audit-1")
                .factoryId(factory.getId())
                .idempotencyKey("request-1")
                .mode(WorkProcessDTO.GovernanceMode.MERGE)
                .masterProcessId(master.getId())
                .governedProcessIds(merged.getId())
                .operator("jpa-test")
                .build();
        entityManager.persist(audit);
        entityManager.flush();
        entityManager.clear();

        assertThat(workProcessRepository
                .findByFactoryIdAndIsActiveTrueAndMergedIntoIdIsNullOrderByProcessNameAsc(factory.getId()))
                .extracting(WorkProcess::getId)
                .containsExactly(master.getId());
        assertThat(workProcessRepository.lockByFactoryIdAndIdIn(
                factory.getId(), List.of(master.getId(), merged.getId())))
                .extracting(WorkProcess::getId)
                .containsExactlyInAnyOrder(master.getId(), merged.getId());
        assertThat(workProcessRepository.existsByFactoryIdAndMergedIntoId(factory.getId(), master.getId()))
                .isTrue();
        assertThat(workProcessRepository.findDistinctProcessCategories(factory.getId()))
                .containsExactly("包装");
        assertThat(auditRepository.findByFactoryIdAndIdempotencyKey(factory.getId(), "request-1"))
                .get()
                .extracting(WorkProcessGovernanceAudit::getMasterProcessId)
                .isEqualTo(master.getId());
    }

    private WorkProcess process(String id, String factoryId, String name, String mergedIntoId) {
        return WorkProcess.builder()
                .id(id)
                .factoryId(factoryId)
                .processName(name)
                .processCategory("包装")
                .unit("unitless")
                .sortOrder(0)
                .isActive(true)
                .defaultOutputMaterialKind(WorkProcessOutputMaterialKind.SEMI_FINISHED)
                .mergedIntoId(mergedIntoId)
                .build();
    }
}
