package com.cretas.aims.service.impl;

import com.cretas.aims.dto.production.ProductionSettlementPrefillResponse;
import com.cretas.aims.dto.production.ProductionSettlementRequest;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A修 — 核对结单 variance 跨单位 guard。
 *
 * <p>根因: 末道报工产出单位 (份/盒) 与 plan.plannedQuantity 单位 (kg) 裸比 → 误报超产
 * (实测 F006 plan 24a0954c: 4618 份 vs 1912 kg)。复用 {@link ProductionBatch#getPlannedUnit()}
 * 跨单位判别, 两处守卫: prefill ({@code getSettlementPrefill}) + settle 校验
 * ({@code validateSettlementRequest})。</p>
 */
@DisplayName("A修: 核对结单 variance 跨单位 guard (份/盒 vs kg 不误报超产)")
class ProductionPlanServiceImplVarianceUnitTest {

    private static final String FACTORY = "F006";
    private static final String PLAN = "PLAN-1";
    private static final Long BATCH = 1L;
    private static final String OVER_PLAN_CODE = "PRODUCTION_OVER_PLAN_REASON_REQUIRED";
    private static final String OVER_PLAN_ISSUE = "QUANTITY_VARIANCE_OVER_PLAN";

    private ProductionPlanServiceImpl newService(ProductionPlanRepository planRepo,
                                                 ProductionBatchRepository batchRepo,
                                                 ProductionReportRepository reportRepo) throws Exception {
        return newService(planRepo, batchRepo, reportRepo, null, null);
    }

    private ProductionPlanServiceImpl newService(ProductionPlanRepository planRepo,
                                                 ProductionBatchRepository batchRepo,
                                                 ProductionReportRepository reportRepo,
                                                 MaterialBatchRepository materialRepo,
                                                 ProcessSheetRowRepository sheetRowRepo) throws Exception {
        return newService(planRepo, batchRepo, reportRepo, materialRepo, sheetRowRepo, null);
    }

    private ProductionPlanServiceImpl newService(ProductionPlanRepository planRepo,
                                                 ProductionBatchRepository batchRepo,
                                                 ProductionReportRepository reportRepo,
                                                 MaterialBatchRepository materialRepo,
                                                 ProcessSheetRowRepository sheetRowRepo,
                                                 MaterialConsumptionRepository consumptionRepo) throws Exception {
        Constructor<?> ctor = null;
        for (Constructor<?> c : ProductionPlanServiceImpl.class.getDeclaredConstructors()) {
            if (ctor == null || c.getParameterCount() > ctor.getParameterCount()) ctor = c;
        }
        ctor.setAccessible(true);
        Object[] args = new Object[ctor.getParameterCount()];
        Class<?>[] types = ctor.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (types[i] == ProductionPlanRepository.class) args[i] = planRepo;
            else if (types[i] == ProductionBatchRepository.class) args[i] = batchRepo;
            else if (types[i] == MaterialBatchRepository.class) args[i] = materialRepo;
            else if (types[i] == MaterialConsumptionRepository.class) args[i] = consumptionRepo;
            else args[i] = null;
        }
        ProductionPlanServiceImpl svc = (ProductionPlanServiceImpl) ctor.newInstance(args);
        inject(svc, "productionReportRepository", reportRepo);
        if (sheetRowRepo != null) {
            inject(svc, "processSheetRowRepository", sheetRowRepo);
        }
        return svc;
    }

    private static void inject(Object target, String field, Object value) throws Exception {
        Field f = ProductionPlanServiceImpl.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private ProductionPlan plan(BigDecimal plannedQty) {
        ProductionPlan p = new ProductionPlan();
        p.setId(PLAN);
        p.setFactoryId(FACTORY);
        p.setPlannedQuantity(plannedQty);
        p.setStatus(ProductionPlanStatus.IN_PROGRESS);
        return p;
    }

    /** plannedUnit != unit → 跨单位; plannedUnit==null → 同单位。 */
    private ProductionBatch batch(String plannedUnit, String unit) {
        ProductionBatch b = new ProductionBatch();
        b.setId(BATCH);
        b.setFactoryId(FACTORY);
        b.setProductionPlanId(PLAN);
        b.setStatus(ProductionBatchStatus.COMPLETED);
        b.setPlannedUnit(plannedUnit);
        b.setUnit(unit);
        return b;
    }

    private ProductionReport report(int order, BigDecimal output, String outUnit) {
        ProductionReport r = new ProductionReport();
        r.setBatchId(BATCH);
        r.setFactoryId(FACTORY);
        r.setProcessOrder(order);
        r.setOutputQuantity(output);
        r.setOutputUnit(outUnit);
        return r;
    }

    private MaterialBatch materialBatch(String id, String batchNumber, String qty) {
        MaterialBatch b = new MaterialBatch();
        b.setId(id);
        b.setFactoryId(FACTORY);
        b.setBatchNumber(batchNumber);
        b.setMaterialTypeId("MAT-" + id);
        b.setWarehouseId("WH-LOG");
        b.setReceiptQuantity(new BigDecimal(qty));
        b.setUsedQuantity(BigDecimal.ZERO);
        b.setQuantityUnit("kg");
        return b;
    }

    private ProcessSheetRow sheetRow(long id, int order, String batchNumber, String payload) {
        ProcessSheetRow row = new ProcessSheetRow();
        row.setId(id);
        row.setFactoryId(FACTORY);
        row.setPlanId(PLAN);
        row.setProcessCode("P" + order);
        row.setProcessOrder(order);
        row.setClientRowId("R" + id);
        row.setBatchNumber(batchNumber);
        row.setRowStatus("SAVED");
        row.setRowPayload(payload);
        row.setCreatedAt(LocalDateTime.of(2026, 6, 27, 8, 0).plusMinutes(id));
        return row;
    }

    private MaterialConsumption pendingConsumption(int id, long productionBatchId, String materialBatchId,
                                                   String quantity) {
        MaterialConsumption consumption = new MaterialConsumption();
        consumption.setId(id);
        consumption.setFactoryId(FACTORY);
        consumption.setProductionBatchId(productionBatchId);
        consumption.setBatchId(materialBatchId);
        consumption.setQuantity(new BigDecimal(quantity));
        return consumption;
    }

    private boolean hasIssue(ProductionSettlementPrefillResponse resp, String code) {
        return resp.getAudit().getIssues().stream().anyMatch(i -> code.equals(i.getCode()));
    }

    private Throwable callValidate(ProductionPlanServiceImpl svc, ProductionPlan p,
                                   ProductionSettlementRequest req) {
        try {
            Method m = ProductionPlanServiceImpl.class.getDeclaredMethod(
                    "validateSettlementRequest", ProductionPlan.class, ProductionSettlementRequest.class);
            m.setAccessible(true);
            m.invoke(svc, p, req);
            return null;
        } catch (InvocationTargetException e) {
            return e.getCause();
        } catch (Exception e) {
            return e;
        }
    }

    private boolean callHasYieldReports(ProductionPlanServiceImpl svc, ProductionPlan p) throws Exception {
        Method m = ProductionPlanServiceImpl.class.getDeclaredMethod(
                "hasYieldReports", String.class, ProductionPlan.class);
        m.setAccessible(true);
        return (boolean) m.invoke(svc, FACTORY, p);
    }

    private ProductionSettlementRequest req(BigDecimal finished) {
        return ProductionSettlementRequest.builder()
                .actualFinishedQuantity(finished)
                .actualSemiFinishedQuantity(BigDecimal.ZERO)
                .build();
    }

    // ===================== PREFILL =====================

    @Test
    @DisplayName("跨单位(4618份 vs 1912kg): prefill 不报超产 BLOCKER")
    void prefill_crossUnit_noOverPlanBlocker() throws Exception {
        ProductionPlanRepository planRepo = mock(ProductionPlanRepository.class);
        ProductionBatchRepository batchRepo = mock(ProductionBatchRepository.class);
        ProductionReportRepository reportRepo = mock(ProductionReportRepository.class);
        when(planRepo.findByIdAndFactoryId(PLAN, FACTORY)).thenReturn(Optional.of(plan(new BigDecimal("1912"))));
        when(batchRepo.findByFactoryIdAndProductionPlanId(FACTORY, PLAN)).thenReturn(List.of(batch("kg", "份")));
        when(reportRepo.findYieldReportsByBatch(FACTORY, BATCH))
                .thenReturn(List.of(report(7, new BigDecimal("4618"), "份")));
        ProductionPlanServiceImpl svc = newService(planRepo, batchRepo, reportRepo);

        ProductionSettlementPrefillResponse resp = svc.getSettlementPrefill(FACTORY, PLAN);

        assertThat(hasIssue(resp, OVER_PLAN_ISSUE)).as("跨单位不应误报超产").isFalse();
    }

    @Test
    @DisplayName("同单位(200kg vs 100kg)超产: prefill 仍报超产 (零回归)")
    void prefill_sameUnit_overPlan_stillBlocks() throws Exception {
        ProductionPlanRepository planRepo = mock(ProductionPlanRepository.class);
        ProductionBatchRepository batchRepo = mock(ProductionBatchRepository.class);
        ProductionReportRepository reportRepo = mock(ProductionReportRepository.class);
        when(planRepo.findByIdAndFactoryId(PLAN, FACTORY)).thenReturn(Optional.of(plan(new BigDecimal("100"))));
        when(batchRepo.findByFactoryIdAndProductionPlanId(FACTORY, PLAN)).thenReturn(List.of(batch(null, "kg")));
        when(reportRepo.findYieldReportsByBatch(FACTORY, BATCH))
                .thenReturn(List.of(report(7, new BigDecimal("200"), "kg")));
        ProductionPlanServiceImpl svc = newService(planRepo, batchRepo, reportRepo);

        ProductionSettlementPrefillResponse resp = svc.getSettlementPrefill(FACTORY, PLAN);

        assertThat(hasIssue(resp, OVER_PLAN_ISSUE)).as("同单位真超产应报").isTrue();
    }

    @Test
    @DisplayName("process sheet rows fallback: prefill reads terminal output and raw inputs")
    void prefill_processSheetRowsFallback_readsOutputAndMaterials() throws Exception {
        ProductionPlanRepository planRepo = mock(ProductionPlanRepository.class);
        ProductionBatchRepository batchRepo = mock(ProductionBatchRepository.class);
        ProductionReportRepository reportRepo = mock(ProductionReportRepository.class);
        MaterialBatchRepository materialRepo = mock(MaterialBatchRepository.class);
        ProcessSheetRowRepository sheetRepo = mock(ProcessSheetRowRepository.class);

        when(planRepo.findByIdAndFactoryId(PLAN, FACTORY)).thenReturn(Optional.of(plan(new BigDecimal("1.10"))));
        when(batchRepo.findByFactoryIdAndProductionPlanId(FACTORY, PLAN)).thenReturn(List.of());
        when(sheetRepo.findByFactoryIdAndPlanId(FACTORY, PLAN)).thenReturn(List.of(
                sheetRow(1, 1, "WIP-A", """
                        {"clientRowId":"R1","processCode":"xiuyou","processOrder":1,"processName":"修油","productTypeId":"SKU-A","batchNumber":"WIP-A","outputQuantity":0.80,"unit":"kg","rawMaterialInputs":[{"materialBatchId":"RAW-1","quantity":1.00}],"laborSegments":[{"startTime":"08:00","endTime":"09:00","workerCount":2}]}
                        """),
                sheetRow(2, 1, "WIP-B", """
                        {"clientRowId":"R2","processCode":"xiuyou","processOrder":1,"processName":"修油","productTypeId":"SKU-B","batchNumber":"WIP-B","outputQuantity":0.50,"unit":"kg","rawMaterialInputs":[{"materialBatchId":"RAW-2","quantity":0.60}]}
                        """),
                sheetRow(3, 2, "WIP-C", """
                        {"clientRowId":"R3","processCode":"shuzhi","processOrder":2,"processName":"熟制","productTypeId":"SKU-A","batchNumber":"WIP-C","outputQuantity":1.10,"unit":"kg","upstreamSources":[{"sourceBatchNumber":"WIP-A","feedQuantityKg":0.70},{"sourceBatchNumber":"WIP-B","feedQuantityKg":0.40}]}
                        """)
        ));
        when(materialRepo.findByIdAndFactoryId("RAW-1", FACTORY))
                .thenReturn(Optional.of(materialBatch("RAW-1", "MB-1", "10")));
        when(materialRepo.findByIdAndFactoryId("RAW-2", FACTORY))
                .thenReturn(Optional.of(materialBatch("RAW-2", "MB-2", "10")));

        ProductionPlanServiceImpl svc = newService(planRepo, batchRepo, reportRepo, materialRepo, sheetRepo);

        ProductionSettlementPrefillResponse resp = svc.getSettlementPrefill(FACTORY, PLAN);

        assertThat(resp.getAudit().isClean()).isTrue();
        assertThat(hasIssue(resp, "NO_YIELD_REPORTS")).isFalse();
        assertThat(resp.getPrefill().getActualFinishedQuantity()).isEqualByComparingTo("1.10");
        assertThat(resp.getPrefill().getRawMaterialConsumptions()).hasSize(2);
        assertThat(resp.getPrefill().getRawMaterialConsumptions())
                .extracting(ProductionSettlementRequest.ConsumptionLine::getMaterialBatchId)
                .containsExactly("RAW-1", "RAW-2");
        assertThat(resp.getPrefill().getRawMaterialConsumptions().get(0).getQuantity()).isEqualByComparingTo("1.00");
        assertThat(resp.getPrefill().getRawMaterialConsumptions().get(1).getQuantity()).isEqualByComparingTo("0.60");
        assertThat(resp.getPrefill().getLaborSegments()).hasSize(1);
        assertThat(resp.getPrefill().getLaborSegments().get(0).getMinutes()).isEqualTo(60);
        assertThat(resp.getPrefill().getLaborSegments().get(0).getHeadcount()).isEqualTo(2);
    }

    @Test
    @DisplayName("process sheet rows are settlement source of truth even when legacy yield reports exist")
    void prefill_processSheetRowsTakePriorityOverLegacyYieldReports() throws Exception {
        ProductionPlanRepository planRepo = mock(ProductionPlanRepository.class);
        ProductionBatchRepository batchRepo = mock(ProductionBatchRepository.class);
        ProductionReportRepository reportRepo = mock(ProductionReportRepository.class);
        MaterialBatchRepository materialRepo = mock(MaterialBatchRepository.class);
        ProcessSheetRowRepository sheetRepo = mock(ProcessSheetRowRepository.class);

        when(planRepo.findByIdAndFactoryId(PLAN, FACTORY)).thenReturn(Optional.of(plan(new BigDecimal("10"))));
        when(batchRepo.findByFactoryIdAndProductionPlanId(FACTORY, PLAN)).thenReturn(List.of(batch(null, "box")));
        when(reportRepo.findYieldReportsByBatch(FACTORY, BATCH))
                .thenReturn(List.of(report(2, new BigDecimal("10"), "box")));
        when(sheetRepo.findByFactoryIdAndPlanId(FACTORY, PLAN)).thenReturn(List.of(
                sheetRow(21, 1, "WIP-SEASONED", """
                        {"clientRowId":"R21","processCode":"seasoning","processOrder":1,"processName":"撒料","productTypeId":"SKU-STEAK","batchNumber":"WIP-SEASONED","outputQuantity":100,"unit":"kg","rawMaterialInputs":[{"materialBatchId":"RAW-LAMB","quantity":100}],"laborSegments":[{"startTime":"10:30","endTime":"11:30","workerCount":5}]}
                        """),
                sheetRow(22, 2, "FG-FROZEN", """
                        {"clientRowId":"R22","processCode":"freezing","processOrder":2,"processName":"冷冻","productTypeId":"SKU-STEAK","batchNumber":"FG-FROZEN","outputQuantity":10,"unit":"box","upstreamSources":[{"sourceBatchNumber":"WIP-SEASONED","feedQuantityKg":100}],"laborSegments":[{"startTime":"10:30","endTime":"11:30","workerCount":5}]}
                        """)
        ));
        MaterialBatch gramBatch = materialBatch("RAW-LAMB", "MT-LAMB", "100000");
        gramBatch.setQuantityUnit("g");
        when(materialRepo.findByIdAndFactoryId("RAW-LAMB", FACTORY)).thenReturn(Optional.of(gramBatch));

        ProductionPlanServiceImpl svc = newService(planRepo, batchRepo, reportRepo, materialRepo, sheetRepo);

        ProductionSettlementPrefillResponse resp = svc.getSettlementPrefill(FACTORY, PLAN);

        assertThat(resp.getPrefill().getActualFinishedQuantity()).isEqualByComparingTo("10");
        assertThat(resp.getPrefill().getRawMaterialConsumptions())
                .singleElement()
                .satisfies(line -> {
                    assertThat(line.getMaterialBatchId()).isEqualTo("RAW-LAMB");
                    assertThat(line.getQuantity()).isEqualByComparingTo("100000");
                    assertThat(line.getUnit()).isEqualTo("g");
                });
        assertThat(resp.getPrefill().getLaborSegments())
                .extracting(ProductionSettlementRequest.LaborSegment::getMinutes)
                .containsExactly(60, 60);
        assertThat(hasIssue(resp, "MATERIAL_CONSUMPTION_EMPTY")).isFalse();
        assertThat(hasIssue(resp, "LABOR_MISSING")).isFalse();
    }

    @Test
    @DisplayName("prefill blocks a material batch reserved by another unfinished plan")
    void prefill_blocksCrossPlanPendingConsumptionConflict() throws Exception {
        ProductionPlanRepository planRepo = mock(ProductionPlanRepository.class);
        ProductionBatchRepository batchRepo = mock(ProductionBatchRepository.class);
        ProductionReportRepository reportRepo = mock(ProductionReportRepository.class);
        MaterialBatchRepository materialRepo = mock(MaterialBatchRepository.class);
        ProcessSheetRowRepository sheetRepo = mock(ProcessSheetRowRepository.class);
        MaterialConsumptionRepository consumptionRepo = mock(MaterialConsumptionRepository.class);

        when(planRepo.findByIdAndFactoryId(PLAN, FACTORY)).thenReturn(Optional.of(plan(new BigDecimal("10"))));
        when(batchRepo.findByFactoryIdAndProductionPlanId(FACTORY, PLAN)).thenReturn(List.of());
        ProcessSheetRow currentRow = sheetRow(31, 1, "FG-CURRENT", """
                {"clientRowId":"R31","processCode":"seasoning","processOrder":1,"processName":"撒料","productTypeId":"SKU-STEAK","batchNumber":"FG-CURRENT","outputQuantity":10,"unit":"kg","rawMaterialInputs":[{"materialBatchId":"RAW-SHARED","quantity":100}],"laborSegments":[{"startTime":"10:30","endTime":"11:30","workerCount":5}]}
                """);
        currentRow.setBatchId(2002L);
        ProcessSheetRow competingRow = sheetRow(32, 1, "FG-OTHER", "{}\n");
        competingRow.setPlanId("PLAN-OTHER");
        competingRow.setBatchId(2001L);
        when(sheetRepo.findByFactoryIdAndPlanId(FACTORY, PLAN)).thenReturn(List.of(currentRow));
        when(sheetRepo.findByFactoryIdAndBatchId(FACTORY, 2001L)).thenReturn(List.of(competingRow));

        MaterialBatch batch = materialBatch("RAW-SHARED", "MT-SHARED", "100000");
        batch.setQuantityUnit("g");
        when(materialRepo.findByIdAndFactoryId("RAW-SHARED", FACTORY)).thenReturn(Optional.of(batch));
        when(consumptionRepo.findByFactoryIdAndBatchId(FACTORY, "RAW-SHARED")).thenReturn(List.of(
                pendingConsumption(1, 2001L, "RAW-SHARED", "100000"),
                pendingConsumption(2, 2002L, "RAW-SHARED", "100000")
        ));
        ProductionPlan otherPlan = plan(new BigDecimal("10"));
        otherPlan.setId("PLAN-OTHER");
        otherPlan.setPlanNumber("PLAN-OTHER-NO");
        when(planRepo.findByIdAndFactoryId("PLAN-OTHER", FACTORY)).thenReturn(Optional.of(otherPlan));

        ProductionPlanServiceImpl svc = newService(
                planRepo, batchRepo, reportRepo, materialRepo, sheetRepo, consumptionRepo);

        ProductionSettlementPrefillResponse resp = svc.getSettlementPrefill(FACTORY, PLAN);

        assertThat(resp.getAudit().isClean()).isFalse();
        assertThat(hasIssue(resp, "RAW_BATCH_CROSS_PLAN_CONFLICT")).isTrue();
        assertThat(resp.getAudit().getIssues())
                .anySatisfy(issue -> assertThat(issue.getMessage()).contains("PLAN-OTHER-NO", "100000g"));
        assertThat(resp.getPrefill().getRawMaterialConsumptions()).isEmpty();
    }

    @Test
    @DisplayName("legacy process rows already recorded in grams are not multiplied by 1000 again")
    void prefill_legacyGramRawInputKeepsGramQuantity() throws Exception {
        ProductionPlanRepository planRepo = mock(ProductionPlanRepository.class);
        ProductionBatchRepository batchRepo = mock(ProductionBatchRepository.class);
        ProductionReportRepository reportRepo = mock(ProductionReportRepository.class);
        MaterialBatchRepository materialRepo = mock(MaterialBatchRepository.class);
        ProcessSheetRowRepository sheetRepo = mock(ProcessSheetRowRepository.class);

        when(planRepo.findByIdAndFactoryId(PLAN, FACTORY)).thenReturn(Optional.of(plan(new BigDecimal("100000"))));
        when(batchRepo.findByFactoryIdAndProductionPlanId(FACTORY, PLAN)).thenReturn(List.of());
        when(sheetRepo.findByFactoryIdAndPlanId(FACTORY, PLAN)).thenReturn(List.of(
                sheetRow(41, 1, "FG-GRAM", """
                        {"clientRowId":"R41","processCode":"legacy","processOrder":1,"processName":"旧版修油","productTypeId":"SKU-OLD","batchNumber":"FG-GRAM","outputQuantity":100000,"unit":"g","rawMaterialInputs":[{"materialBatchId":"RAW-GRAM","quantity":100000}],"laborSegments":[{"startTime":"08:00","endTime":"09:00","workerCount":1}]}
                        """)
        ));
        MaterialBatch gramBatch = materialBatch("RAW-GRAM", "MT-GRAM", "100000");
        gramBatch.setQuantityUnit("g");
        when(materialRepo.findByIdAndFactoryId("RAW-GRAM", FACTORY)).thenReturn(Optional.of(gramBatch));

        ProductionPlanServiceImpl svc = newService(planRepo, batchRepo, reportRepo, materialRepo, sheetRepo);

        ProductionSettlementPrefillResponse resp = svc.getSettlementPrefill(FACTORY, PLAN);

        assertThat(resp.getPrefill().getRawMaterialConsumptions())
                .singleElement()
                .satisfies(line -> assertThat(line.getQuantity()).isEqualByComparingTo("100000"));
    }

    @Test
    @DisplayName("process sheet rows count as production data for cancel guard")
    void hasYieldReports_processSheetRowsFallback_blocksEmptyCancelPath() throws Exception {
        ProductionPlanRepository planRepo = mock(ProductionPlanRepository.class);
        ProductionBatchRepository batchRepo = mock(ProductionBatchRepository.class);
        ProductionReportRepository reportRepo = mock(ProductionReportRepository.class);
        ProcessSheetRowRepository sheetRepo = mock(ProcessSheetRowRepository.class);
        ProductionBatch productionBatch = batch(null, "kg");

        when(batchRepo.findByFactoryIdAndProductionPlanId(FACTORY, PLAN)).thenReturn(List.of(productionBatch));
        when(reportRepo.findYieldReportsByBatch(FACTORY, BATCH)).thenReturn(List.of());
        when(sheetRepo.findByFactoryIdAndPlanId(FACTORY, PLAN)).thenReturn(List.of(
                sheetRow(11, 1, "WIP-CANCEL-GUARD", """
                        {"clientRowId":"R11","processCode":"xiuyou","processOrder":1,"productTypeId":"SKU-A","batchNumber":"WIP-CANCEL-GUARD","outputQuantity":0.80,"unit":"kg"}
                        """)
        ));
        ProductionPlanServiceImpl svc = newService(planRepo, batchRepo, reportRepo, null, sheetRepo);

        assertThat(callHasYieldReports(svc, plan(new BigDecimal("1.00")))).isTrue();
    }

    // ===================== SETTLE validate =====================

    @Test
    @DisplayName("跨单位: settle 校验不因超产被拦")
    void settle_crossUnit_noOverPlanThrow() throws Exception {
        ProductionBatchRepository batchRepo = mock(ProductionBatchRepository.class);
        when(batchRepo.findByFactoryIdAndProductionPlanId(FACTORY, PLAN)).thenReturn(List.of(batch("kg", "份")));
        ProductionPlanServiceImpl svc = newService(
                mock(ProductionPlanRepository.class), batchRepo, mock(ProductionReportRepository.class));

        Throwable t = callValidate(svc, plan(new BigDecimal("1912")), req(new BigDecimal("4618")));

        if (t instanceof BusinessException) {
            assertThat(((BusinessException) t).getErrorCode())
                    .as("跨单位不应因超产被拦 (后续领用校验可抛别的)").isNotEqualTo(OVER_PLAN_CODE);
        }
    }

    @Test
    @DisplayName("同单位超产无原因: settle 校验抛超产 (零回归)")
    void settle_sameUnit_overPlan_throws() throws Exception {
        ProductionBatchRepository batchRepo = mock(ProductionBatchRepository.class);
        when(batchRepo.findByFactoryIdAndProductionPlanId(FACTORY, PLAN)).thenReturn(List.of(batch(null, "kg")));
        ProductionPlanServiceImpl svc = newService(
                mock(ProductionPlanRepository.class), batchRepo, mock(ProductionReportRepository.class));

        Throwable t = callValidate(svc, plan(new BigDecimal("100")), req(new BigDecimal("200")));

        assertThat(t).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) t).getErrorCode()).isEqualTo(OVER_PLAN_CODE);
    }
}
