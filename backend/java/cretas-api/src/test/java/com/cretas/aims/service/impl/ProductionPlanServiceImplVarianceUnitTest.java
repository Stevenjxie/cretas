package com.cretas.aims.service.impl;

import com.cretas.aims.dto.production.ProductionSettlementPrefillResponse;
import com.cretas.aims.dto.production.ProductionSettlementRequest;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.exception.BusinessException;
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
            else args[i] = null;
        }
        ProductionPlanServiceImpl svc = (ProductionPlanServiceImpl) ctor.newInstance(args);
        inject(svc, "productionReportRepository", reportRepo);
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
