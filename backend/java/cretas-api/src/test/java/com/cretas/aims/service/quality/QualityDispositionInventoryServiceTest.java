package com.cretas.aims.service.quality;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.service.QualityDispositionRuleService.DispositionAction;
import com.cretas.aims.service.quality.QualityDispositionInventoryService.FlipDecision;
import com.cretas.aims.service.quality.QualityDispositionInventoryService.InventoryDispositionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link QualityDispositionInventoryService} — the 质检处置→库存 bridge.
 *
 * <p>Verifies a 放行 (RELEASE) actually returns the inspection's quarantined batches to
 * sellable stock, SCRAP does not, HOLD leaves them quarantined, idempotent re-release does
 * not double-flip, and only THIS inspection's batches are touched (no over-release).
 */
@ExtendWith(MockitoExtension.class)
class QualityDispositionInventoryServiceTest {

    private static final String FACTORY_ID = "F006";
    private static final Long PRODUCTION_BATCH_ID = 100L;
    private static final String PLAN_ID = "P-1";
    private static final String MATERIAL_BATCH_ID = "mb-1";

    @InjectMocks
    private QualityDispositionInventoryService service;

    @Mock
    private MaterialBatchRepository materialBatchRepository;
    @Mock
    private ProductionBatchRepository productionBatchRepository;
    @Mock
    private FinishedGoodsBatchRepository finishedGoodsBatchRepository;

    private QualityInspection inspection;

    @BeforeEach
    void setUp() {
        inspection = new QualityInspection();
        inspection.setId("qi-1");
        inspection.setFactoryId(FACTORY_ID);
        inspection.setProductionBatchId(PRODUCTION_BATCH_ID);
    }

    private int fgSeq = 0;

    private FinishedGoodsBatch fg(String status) {
        FinishedGoodsBatch b = new FinishedGoodsBatch();
        // distinct id/batchNumber so Lombok @Data equals() distinguishes batches in Mockito verify()
        b.setId("fg-" + (++fgSeq));
        b.setBatchNumber("FG-B-" + fgSeq);
        b.setStatus(status);
        return b;
    }

    private void wirePlan() {
        ProductionBatch pb = new ProductionBatch();
        pb.setProductionPlanId(PLAN_ID);
        when(productionBatchRepository.findById(PRODUCTION_BATCH_ID)).thenReturn(Optional.of(pb));
    }

    // ===================== 静态纯函数守卫 =====================

    @Test
    @DisplayName("UT-DIB-00: evaluateMaterialFlip — idempotent / terminal / flip")
    void evaluateMaterialFlipGuard() {
        // 幂等: 已是目标
        assertEquals(FlipDecision.ALREADY_IN_TARGET,
                QualityDispositionInventoryService.evaluateMaterialFlip(
                        MaterialBatchStatus.AVAILABLE, MaterialBatchStatus.AVAILABLE));
        // 终态: 不可翻
        assertEquals(FlipDecision.INVALID_TERMINAL,
                QualityDispositionInventoryService.evaluateMaterialFlip(
                        MaterialBatchStatus.USED_UP, MaterialBatchStatus.AVAILABLE));
        // DEFECTIVE → AVAILABLE 允许 (放行隔离原料)
        assertEquals(FlipDecision.FLIP,
                QualityDispositionInventoryService.evaluateMaterialFlip(
                        MaterialBatchStatus.DEFECTIVE, MaterialBatchStatus.AVAILABLE));
    }

    // ===================== RELEASE — 成品 =====================

    @Test
    @DisplayName("UT-DIB-01: RELEASE → 本计划 DEFECTIVE 成品翻回 AVAILABLE, AVAILABLE 的不动")
    void releaseFlipsDefectiveFinishedGoods() {
        wirePlan();
        FinishedGoodsBatch defective = fg(FinishedGoodsBatch.Status.DEFECTIVE);
        FinishedGoodsBatch alreadyAvailable = fg(FinishedGoodsBatch.Status.AVAILABLE);
        when(finishedGoodsBatchRepository
                .findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(FACTORY_ID, PLAN_ID))
                .thenReturn(List.of(defective, alreadyAvailable));

        InventoryDispositionResult r = service.applyDisposition(inspection, DispositionAction.RELEASE);

        assertEquals(1, r.getFinishedGoodsReleased());
        assertEquals(FinishedGoodsBatch.Status.AVAILABLE, defective.getStatus());
        assertEquals(FinishedGoodsBatch.Status.AVAILABLE, alreadyAvailable.getStatus());
        verify(finishedGoodsBatchRepository, times(1)).save(defective);
        verify(finishedGoodsBatchRepository, never()).save(alreadyAvailable);
    }

    @Test
    @DisplayName("UT-DIB-02: CONDITIONAL_RELEASE 同样把 DEFECTIVE 成品翻回 AVAILABLE")
    void conditionalReleaseFlipsFinishedGoods() {
        wirePlan();
        FinishedGoodsBatch defective = fg(FinishedGoodsBatch.Status.DEFECTIVE);
        when(finishedGoodsBatchRepository
                .findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(FACTORY_ID, PLAN_ID))
                .thenReturn(List.of(defective));

        InventoryDispositionResult r = service.applyDisposition(inspection, DispositionAction.CONDITIONAL_RELEASE);

        assertEquals(1, r.getFinishedGoodsReleased());
        assertEquals(FinishedGoodsBatch.Status.AVAILABLE, defective.getStatus());
    }

    // ===================== RELEASE — 原料 (material-scoped) =====================

    @Test
    @DisplayName("UT-DIB-03: material-scoped RELEASE → 原料 DEFECTIVE→AVAILABLE")
    void releaseFlipsMaterialBatch() {
        inspection.setMaterialBatchId(MATERIAL_BATCH_ID);
        MaterialBatch mb = new MaterialBatch();
        mb.setId(MATERIAL_BATCH_ID);
        mb.setFactoryId(FACTORY_ID);
        mb.setStatus(MaterialBatchStatus.DEFECTIVE);
        when(materialBatchRepository.findByIdAndFactoryId(MATERIAL_BATCH_ID, FACTORY_ID))
                .thenReturn(Optional.of(mb));
        // production batch not set path: no FG resolution
        when(productionBatchRepository.findById(PRODUCTION_BATCH_ID)).thenReturn(Optional.empty());

        InventoryDispositionResult r = service.applyDisposition(inspection, DispositionAction.RELEASE);

        assertEquals(FlipDecision.FLIP, r.getMaterialFlip());
        assertEquals(MaterialBatchStatus.AVAILABLE, mb.getStatus());
        verify(materialBatchRepository).save(mb);
    }

    // ===================== SCRAP =====================

    @Test
    @DisplayName("UT-DIB-04: SCRAP → 原料 DEFECTIVE→SCRAPPED, 成品保持 DEFECTIVE (不放回, 不翻)")
    void scrapMaterialToScrappedFinishedGoodsStaysDefective() {
        inspection.setMaterialBatchId(MATERIAL_BATCH_ID);
        MaterialBatch mb = new MaterialBatch();
        mb.setId(MATERIAL_BATCH_ID);
        mb.setFactoryId(FACTORY_ID);
        mb.setStatus(MaterialBatchStatus.DEFECTIVE);
        when(materialBatchRepository.findByIdAndFactoryId(MATERIAL_BATCH_ID, FACTORY_ID))
                .thenReturn(Optional.of(mb));

        InventoryDispositionResult r = service.applyDisposition(inspection, DispositionAction.SCRAP);

        assertEquals(FlipDecision.FLIP, r.getMaterialFlip());
        assertEquals(MaterialBatchStatus.SCRAPPED, mb.getStatus());
        assertEquals(0, r.getFinishedGoodsReleased());
        // SCRAP 不解析/不翻成品 (物理报废走处置 UI)
        verify(finishedGoodsBatchRepository, never()).save(any());
        verify(finishedGoodsBatchRepository, never())
                .findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(any(), any());
    }

    // ===================== HOLD / REWORK — 不翻 =====================

    @Test
    @DisplayName("UT-DIB-05: HOLD → 无任何库存翻转 (保持隔离)")
    void holdNoFlip() {
        inspection.setMaterialBatchId(MATERIAL_BATCH_ID);

        InventoryDispositionResult r = service.applyDisposition(inspection, DispositionAction.HOLD);

        assertEquals(0, r.getFinishedGoodsReleased());
        assertNull(r.getMaterialFlip());
        verifyNoInteractions(materialBatchRepository, finishedGoodsBatchRepository, productionBatchRepository);
    }

    @Test
    @DisplayName("UT-DIB-06: REWORK → 无库存翻转 (不自动返回)")
    void reworkNoFlip() {
        InventoryDispositionResult r = service.applyDisposition(inspection, DispositionAction.REWORK);
        assertEquals(0, r.getFinishedGoodsReleased());
        assertNull(r.getMaterialFlip());
        verifyNoInteractions(materialBatchRepository, finishedGoodsBatchRepository, productionBatchRepository);
    }

    // ===================== 幂等 =====================

    @Test
    @DisplayName("UT-DIB-07: 重复放行不双翻 — 成品已 AVAILABLE, 原料已 AVAILABLE → 0 翻转")
    void idempotentReReleaseNoDoubleFlip() {
        wirePlan();
        inspection.setMaterialBatchId(MATERIAL_BATCH_ID);
        // 成品已经全部 AVAILABLE (上一次放行的结果)
        when(finishedGoodsBatchRepository
                .findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(FACTORY_ID, PLAN_ID))
                .thenReturn(List.of(fg(FinishedGoodsBatch.Status.AVAILABLE)));
        MaterialBatch mb = new MaterialBatch();
        mb.setId(MATERIAL_BATCH_ID);
        mb.setFactoryId(FACTORY_ID);
        mb.setStatus(MaterialBatchStatus.AVAILABLE);
        when(materialBatchRepository.findByIdAndFactoryId(MATERIAL_BATCH_ID, FACTORY_ID))
                .thenReturn(Optional.of(mb));

        InventoryDispositionResult r = service.applyDisposition(inspection, DispositionAction.RELEASE);

        assertEquals(0, r.getFinishedGoodsReleased());
        assertEquals(FlipDecision.ALREADY_IN_TARGET, r.getMaterialFlip());
        verify(finishedGoodsBatchRepository, never()).save(any());
        verify(materialBatchRepository, never()).save(any());
    }

    // ===================== 食安: 只动本次质检关联的批次 =====================

    @Test
    @DisplayName("UT-DIB-08: RELEASE 只查询本次质检 plan 的成品 (不越权释放兄弟计划)")
    void onlyThisInspectionsPlanQueried() {
        wirePlan();
        when(finishedGoodsBatchRepository
                .findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(FACTORY_ID, PLAN_ID))
                .thenReturn(List.of());

        service.applyDisposition(inspection, DispositionAction.RELEASE);

        // 仅按本 inspection 的 planId 解析, 不用别的 planId
        verify(finishedGoodsBatchRepository)
                .findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(FACTORY_ID, PLAN_ID);
        verify(finishedGoodsBatchRepository, never())
                .findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(eq(FACTORY_ID), eq("OTHER-PLAN"));
    }

    @Test
    @DisplayName("UT-DIB-09: 非 material-scoped (materialBatchId 空) → 不碰原料")
    void nonMaterialScopedSkipsMaterial() {
        wirePlan();
        when(finishedGoodsBatchRepository
                .findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(FACTORY_ID, PLAN_ID))
                .thenReturn(List.of(fg(FinishedGoodsBatch.Status.DEFECTIVE)));

        InventoryDispositionResult r = service.applyDisposition(inspection, DispositionAction.RELEASE);

        assertNull(r.getMaterialFlip());
        verify(materialBatchRepository, never()).findByIdAndFactoryId(any(), any());
    }
}
