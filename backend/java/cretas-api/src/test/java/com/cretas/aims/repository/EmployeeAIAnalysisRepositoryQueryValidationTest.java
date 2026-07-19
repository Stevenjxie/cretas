package com.cretas.aims.repository;

import com.cretas.aims.entity.BatchWorkSession;
import com.cretas.aims.entity.EmployeeWorkSession;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.WorkType;
import com.cretas.aims.entity.enums.FactoryType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Hibernate startup and cross-tenant isolation gate for employee AI fact queries. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class EmployeeAIAnalysisRepositoryQueryValidationTest {

    private static final String FACTORY_A = "F-JPA-EMP-A";
    private static final String FACTORY_B = "F-JPA-EMP-B";

    @Autowired TestEntityManager entityManager;
    @Autowired EmployeeWorkSessionRepository employeeWorkSessionRepository;
    @Autowired BatchWorkSessionRepository batchWorkSessionRepository;
    @Autowired QualityInspectionRepository qualityInspectionRepository;

    @Test
    void employeeFactQueriesBootAndKeepEveryCountInsideItsFactory() {
        persistFactory(FACTORY_A, "Employee AI fact gate A");
        persistFactory(FACTORY_B, "Employee AI fact gate B");
        User employee = persistUser(FACTORY_A, "employee-ai-fact-owner");
        persistWorkType("1", FACTORY_A, "WORK-A");
        persistWorkType("2", FACTORY_B, "WORK-B");
        entityManager.flush();

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime start = now.minusDays(2);
        LocalDateTime end = now.plusDays(1);

        persistEmployeeWorkSession(FACTORY_A, employee.getId(), 1, now, 120);
        // Deliberately reuse tenant A's global user id in tenant B facts. The factory predicate must isolate it.
        persistEmployeeWorkSession(FACTORY_B, employee.getId(), 2, now, 900);

        ProductionBatch batchA1 = persistBatch(FACTORY_A, "JPA-EMP-A-1", "P-A");
        ProductionBatch batchA2 = persistBatch(FACTORY_A, "JPA-EMP-A-2", "P-A");
        ProductionBatch batchB = persistBatch(FACTORY_B, "JPA-EMP-B-1", "P-B");
        entityManager.flush();

        persistBatchWorkSession(batchA1.getId(), employee.getId(), now, 60, "completed");
        persistBatchWorkSession(batchA1.getId(), employee.getId(), now, 40, "working");
        persistBatchWorkSession(batchA2.getId(), employee.getId(), now, 30, "completed");
        persistBatchWorkSession(batchB.getId(), employee.getId(), now, 700, "completed");

        persistInspection("QI-A-PASS", FACTORY_A, batchA1.getId(), employee.getId(), "passed");
        persistInspection("QI-A-FAIL", FACTORY_A, batchA2.getId(), employee.getId(), "failed");
        persistInspection("QI-B-PASS", FACTORY_B, batchB.getId(), employee.getId(), "passed");
        entityManager.flush();
        entityManager.clear();

        assertThat(employeeWorkSessionRepository.countByFactoryIdAndUserIdAndTimeRange(
                FACTORY_A, employee.getId(), start, end)).isEqualTo(1);
        assertThat(employeeWorkSessionRepository.sumActualWorkMinutesByFactoryIdAndUserIdAndTimeRange(
                FACTORY_A, employee.getId(), start, end)).isEqualTo(120);
        assertThat(employeeWorkSessionRepository.countByFactoryIdAndUserIdAndTimeRange(
                FACTORY_B, employee.getId(), start, end)).isEqualTo(1);

        assertThat(batchWorkSessionRepository.countDistinctBatchesByFactoryIdAndEmployeeAndTimeRange(
                FACTORY_A, employee.getId(), start, end)).isEqualTo(2);
        assertThat(batchWorkSessionRepository.countByFactoryIdAndEmployeeAndTimeRange(
                FACTORY_A, employee.getId(), start, end)).isEqualTo(3);
        assertThat(batchWorkSessionRepository.sumWorkMinutesByFactoryIdAndEmployeeAndTimeRange(
                FACTORY_A, employee.getId(), start, end)).isEqualTo(130);
        assertThat(batchWorkSessionRepository.countCompletedByFactoryIdAndEmployeeAndTimeRange(
                FACTORY_A, employee.getId(), start, end)).isEqualTo(2);
        assertThat(batchWorkSessionRepository.countByFactoryIdAndEmployeeAndTimeRange(
                FACTORY_B, employee.getId(), start, end)).isEqualTo(1);

        LocalDate startDate = start.toLocalDate();
        LocalDate endDate = end.toLocalDate();
        assertThat(qualityInspectionRepository.countByFactoryIdAndInspectorIdAndDateRange(
                FACTORY_A, employee.getId(), startDate, endDate)).isEqualTo(2);
        assertThat(qualityInspectionRepository.countPassedByFactoryIdAndInspectorIdAndDateRange(
                FACTORY_A, employee.getId(), startDate, endDate)).isEqualTo(1);
        assertThat(qualityInspectionRepository.countByFactoryIdAndInspectorIdAndDateRange(
                FACTORY_B, employee.getId(), startDate, endDate)).isEqualTo(1);
    }

    private void persistFactory(String id, String name) {
        Factory factory = new Factory();
        factory.setId(id);
        factory.setName(name);
        factory.setType(FactoryType.FACTORY);
        factory.setLevel(0);
        factory.setIsActive(true);
        factory.setManuallyVerified(false);
        factory.setAiWeeklyQuota(20);
        entityManager.persist(factory);
    }

    private User persistUser(String factoryId, String username) {
        User user = new User();
        user.setFactoryId(factoryId);
        user.setUsername(username);
        user.setPasswordHash("test-only");
        user.setIsActive(true);
        entityManager.persist(user);
        return user;
    }

    private void persistWorkType(String id, String factoryId, String code) {
        WorkType workType = new WorkType();
        workType.setId(id);
        workType.setFactoryId(factoryId);
        workType.setName(code);
        workType.setCode(code);
        workType.setTypeCode(code);
        workType.setTypeName(code);
        workType.setHazardLevel(0);
        workType.setIsActive(true);
        workType.setIsDefault(false);
        workType.setDisplayOrder(0);
        entityManager.persist(workType);
    }

    private void persistEmployeeWorkSession(String factoryId, Long userId, Integer workTypeId,
                                            LocalDateTime startTime, Integer minutes) {
        EmployeeWorkSession session = new EmployeeWorkSession();
        session.setFactoryId(factoryId);
        session.setUserId(userId);
        session.setWorkTypeId(workTypeId);
        session.setStartTime(startTime);
        session.setEndTime(startTime.plusMinutes(minutes));
        session.setActualWorkMinutes(minutes);
        session.setStatus("completed");
        entityManager.persist(session);
    }

    private ProductionBatch persistBatch(String factoryId, String batchNumber, String productTypeId) {
        ProductionBatch batch = new ProductionBatch();
        batch.setFactoryId(factoryId);
        batch.setBatchNumber(batchNumber);
        batch.setProductTypeId(productTypeId);
        batch.setQuantity(BigDecimal.ONE);
        batch.setUnit("kg");
        batch.setBatchType("REGULAR");
        batch.setIsTrial(false);
        entityManager.persist(batch);
        return batch;
    }

    private void persistBatchWorkSession(Long batchId, Long employeeId, LocalDateTime createdAt,
                                         Integer minutes, String status) {
        BatchWorkSession session = new BatchWorkSession();
        session.setBatchId(batchId);
        session.setEmployeeId(employeeId);
        session.setWorkMinutes(minutes);
        session.setStatus(status);
        session.setCreatedAt(createdAt);
        session.setUpdatedAt(createdAt);
        entityManager.persist(session);
    }

    private void persistInspection(String id, String factoryId, Long batchId, Long inspectorId,
                                   String result) {
        QualityInspection inspection = new QualityInspection();
        inspection.setId(id);
        inspection.setFactoryId(factoryId);
        inspection.setProductionBatchId(batchId);
        inspection.setInspectorId(inspectorId);
        inspection.setInspectionDate(LocalDate.now());
        inspection.setSampleSize(BigDecimal.TEN);
        inspection.setPassCount("passed".equals(result) ? BigDecimal.TEN : BigDecimal.ZERO);
        inspection.setFailCount("passed".equals(result) ? BigDecimal.ZERO : BigDecimal.TEN);
        inspection.setResult(result);
        entityManager.persist(inspection);
    }
}
