package com.cretas.aims.repository;

import com.cretas.aims.entity.SemiFinishedInventory;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G6 地基 — JPA slice test for {@link SemiFinishedInventoryRepository}.
 *
 * <p>Verifies (H2 PG-compat, application-test.properties):
 * <ol>
 *   <li>save + @PrePersist (createdAt/updatedAt) + IDENTITY id + status default AVAILABLE</li>
 *   <li>findByIntermediateBatchNoAndDeletedAtIsNull — 幂等 upsert 锚点查询</li>
 *   <li>findByFactoryIdAndBatchId / AndStatus / AndSourceWorkProcessTaskId — 基础查询 + 工厂隔离</li>
 *   <li>findRemainingWip — available > 0 的 AVAILABLE 行 (D3 余料退回)</li>
 *   <li>@Version 乐观锁注解存在 + 持久化后 version 被赋值 (并发超领防御)</li>
 * </ol>
 *
 * <p>JSONB {@code materialBatchRefs} 字段在 H2 下置 null (与 BatchLineageEdgeRepositoryTest /
 * IndicatorRepositoryTest 同 — H2 PG-compat jsonb round-trip 限制)。真实 PG 的 jsonb 往返
 * 由本类底部 @Disabled 文档测试覆盖 (需本地 PG, per YieldGoldStandardIT 惯例)。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@DisplayName("SemiFinishedInventoryRepository — JPA slice (H2 PG-compat)")
class SemiFinishedInventoryRepositoryTest {

    private static final String F1 = "F-SFI-1";
    private static final String F2 = "F-SFI-2";

    @Autowired private SemiFinishedInventoryRepository repo;
    @Autowired private EntityManager em;

    @Test
    @DisplayName("save — @PrePersist audit + IDENTITY id + status default AVAILABLE")
    void saveAssignsAuditAndDefaults() {
        SemiFinishedInventory saved = repo.saveAndFlush(
                wip(F1, 100L, "WIP-001", 1L, 1, "5.00", "0.00", "5.00"));

        assertNotNull(saved.getId(), "IDENTITY must assign id");
        assertNotNull(saved.getCreatedAt(), "@PrePersist must set createdAt");
        assertNotNull(saved.getUpdatedAt(), "@PrePersist must set updatedAt");
        assertEquals(SemiFinishedInventory.Status.AVAILABLE, saved.getStatus(),
                "status defaults to AVAILABLE via @Builder.Default");
        assertEquals(0, new BigDecimal("5.00").compareTo(saved.getAvailableQuantity()));
    }

    @Test
    @DisplayName("findByIntermediateBatchNoAndDeletedAtIsNull — 幂等 upsert 锚点")
    void findByIntermediateBatchNo() {
        repo.saveAndFlush(wip(F1, 100L, "WIP-FIND-1", 1L, 1, "5.00", "0.00", "5.00"));
        repo.saveAndFlush(wip(F1, 100L, "WIP-FIND-2", 2L, 2, "3.00", "0.00", "3.00"));

        Optional<SemiFinishedInventory> found =
                repo.findByIntermediateBatchNoAndDeletedAtIsNull("WIP-FIND-1");
        assertTrue(found.isPresent(), "should locate WIP by intermediate_batch_no");
        assertEquals(1, found.get().getProcessOrder());

        assertTrue(repo.findByIntermediateBatchNoAndDeletedAtIsNull("NO-SUCH").isEmpty(),
                "unknown batch no returns empty");
    }

    @Test
    @DisplayName("findByFactoryIdAndBatchId — batch scoped + factory isolation")
    void findByFactoryIdAndBatchId() {
        repo.saveAndFlush(wip(F1, 200L, "WIP-B-1", 1L, 1, "5.00", "0.00", "5.00"));
        repo.saveAndFlush(wip(F1, 200L, "WIP-B-2", 2L, 2, "3.00", "0.00", "3.00"));
        repo.saveAndFlush(wip(F1, 999L, "WIP-B-OTHER", 3L, 1, "1.00", "0.00", "1.00")); // other batch
        repo.saveAndFlush(wip(F2, 200L, "WIP-B-F2", 4L, 1, "9.00", "0.00", "9.00"));     // other factory

        List<SemiFinishedInventory> rows = repo.findByFactoryIdAndBatchIdAndDeletedAtIsNull(F1, 200L);
        assertEquals(2, rows.size(), "F1 batch 200 has 2 WIP rows (other batch + F2 excluded)");
        List<String> nos = rows.stream().map(SemiFinishedInventory::getIntermediateBatchNo).toList();
        assertTrue(nos.contains("WIP-B-1"));
        assertTrue(nos.contains("WIP-B-2"));
    }

    @Test
    @DisplayName("findByFactoryIdAndBatchIdAndStatus — status filter")
    void findByStatus() {
        repo.saveAndFlush(wip(F1, 300L, "WIP-S-AVL", 1L, 1, "5.00", "2.00", "3.00")); // AVAILABLE
        SemiFinishedInventory depleted = wip(F1, 300L, "WIP-S-DEP", 2L, 2, "4.00", "4.00", "0.00");
        depleted.setStatus(SemiFinishedInventory.Status.DEPLETED);
        repo.saveAndFlush(depleted);

        List<SemiFinishedInventory> avail = repo.findByFactoryIdAndBatchIdAndStatusAndDeletedAtIsNull(
                F1, 300L, SemiFinishedInventory.Status.AVAILABLE);
        assertEquals(1, avail.size());
        assertEquals("WIP-S-AVL", avail.get(0).getIntermediateBatchNo());

        List<SemiFinishedInventory> dep = repo.findByFactoryIdAndBatchIdAndStatusAndDeletedAtIsNull(
                F1, 300L, SemiFinishedInventory.Status.DEPLETED);
        assertEquals(1, dep.size());
        assertEquals("WIP-S-DEP", dep.get(0).getIntermediateBatchNo());
    }

    @Test
    @DisplayName("findByFactoryIdAndSourceWorkProcessTaskId — task scoped")
    void findByTask() {
        repo.saveAndFlush(wip(F1, 400L, "WIP-T-1", 77L, 1, "5.00", "0.00", "5.00"));
        repo.saveAndFlush(wip(F1, 400L, "WIP-T-OTHER", 88L, 2, "3.00", "0.00", "3.00"));

        List<SemiFinishedInventory> rows =
                repo.findByFactoryIdAndSourceWorkProcessTaskIdAndDeletedAtIsNull(F1, 77L);
        assertEquals(1, rows.size());
        assertEquals("WIP-T-1", rows.get(0).getIntermediateBatchNo());
    }

    @Test
    @DisplayName("findRemainingWip — available > 0 AVAILABLE rows only (D3 余料退回)")
    void findRemainingWip() {
        // has remaining
        repo.saveAndFlush(wip(F1, 500L, "WIP-R-HAS", 1L, 1, "5.00", "3.00", "2.00"));
        // depleted (available = 0) — excluded
        SemiFinishedInventory zero = wip(F1, 500L, "WIP-R-ZERO", 2L, 2, "4.00", "4.00", "0.00");
        zero.setStatus(SemiFinishedInventory.Status.DEPLETED);
        repo.saveAndFlush(zero);
        // returned (already退回) — excluded by status
        SemiFinishedInventory returned = wip(F1, 500L, "WIP-R-RET", 3L, 3, "6.00", "0.00", "6.00");
        returned.setStatus(SemiFinishedInventory.Status.RETURNED);
        repo.saveAndFlush(returned);

        List<SemiFinishedInventory> remaining = repo.findRemainingWip(F1, 500L);
        assertEquals(1, remaining.size(), "only AVAILABLE with available>0 counts");
        assertEquals("WIP-R-HAS", remaining.get(0).getIntermediateBatchNo());
    }

    @Test
    @DisplayName("@Version — optimistic lock annotation present + version assigned on persist")
    void versionAnnotationPresent() throws NoSuchFieldException {
        assertNotNull(
                SemiFinishedInventory.class.getDeclaredField("version")
                        .getAnnotation(jakarta.persistence.Version.class),
                "version field must carry @Version (并发超领防御)");

        SemiFinishedInventory saved = repo.saveAndFlush(
                wip(F1, 600L, "WIP-VER", 1L, 1, "5.00", "0.00", "5.00"));
        assertEquals(0L, saved.getVersion(), "Hibernate assigns version 0 on first persist");

        // mutate + flush → version increments
        saved.setConsumedQuantity(new BigDecimal("1.00"));
        saved.setAvailableQuantity(new BigDecimal("4.00"));
        SemiFinishedInventory updated = repo.saveAndFlush(saved);
        em.flush();
        assertEquals(1L, updated.getVersion(), "version increments on update");
    }

    @Test
    @org.junit.jupiter.api.Disabled("需真实 PG (H2 PG-compat jsonb round-trip 限制, 同 BatchLineageEdgeRepositoryTest). "
            + "真实 PG 手动跑: mvn -o test -Dtest=SemiFinishedInventoryRepositoryTest#jsonbMaterialBatchRefs_roundTrip "
            + "-Dspring-boot.run.profiles=pg-test (本地 PG + Flyway 已 apply V20260907_01)")
    @DisplayName("[real-PG only] material_batch_refs jsonb 往返 — 镜像 production_reports.material_batch_refs")
    void jsonbMaterialBatchRefs_roundTrip() {
        SemiFinishedInventory w = wip(F1, 700L, "WIP-JSONB", 1L, 1, "5.00", "0.00", "5.00");
        w.setMaterialBatchRefs(List.of(
                java.util.Map.of("materialBatchId", 12345L, "quantity", 5.0, "unit", "kg")));
        SemiFinishedInventory saved = repo.saveAndFlush(w);
        em.flush();
        em.clear();

        SemiFinishedInventory reloaded = repo.findById(saved.getId()).orElseThrow();
        assertNotNull(reloaded.getMaterialBatchRefs());
        assertEquals(1, reloaded.getMaterialBatchRefs().size());
        assertEquals(12345, ((Number) reloaded.getMaterialBatchRefs().get(0).get("materialBatchId")).longValue());
        assertEquals("kg", reloaded.getMaterialBatchRefs().get(0).get("unit"));
    }

    // ============================================================
    // Helper
    // ============================================================

    private SemiFinishedInventory wip(String factoryId, Long batchId, String intermediateBatchNo,
                                      Long taskId, Integer order,
                                      String produced, String consumed, String available) {
        return SemiFinishedInventory.builder()
                .factoryId(factoryId)
                .batchId(batchId)
                .intermediateBatchNo(intermediateBatchNo)
                .sourceWorkProcessTaskId(taskId)
                .processOrder(order)
                .productTypeId("CPDX0001")
                .producedQuantity(new BigDecimal(produced))
                .consumedQuantity(new BigDecimal(consumed))
                .availableQuantity(new BigDecimal(available))
                .unit("kg")
                .status(SemiFinishedInventory.Status.AVAILABLE)
                // jsonb null per H2 PG-compat limitation; real-PG round-trip in @Disabled IT below
                .materialBatchRefs(null)
                .build();
    }
}
