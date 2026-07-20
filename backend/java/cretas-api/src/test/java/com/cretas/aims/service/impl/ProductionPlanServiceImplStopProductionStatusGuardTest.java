package com.cretas.aims.service.impl;

import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.PlanSourceType;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionInterimSettlementRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 停产 (stopProduction) plan-status 守卫回归测试.
 *
 * <p>缺此守卫时对已 COMPLETED/CANCELLED 终态计划重复停产: 状态置 COMPLETED 是 no-op, 但方法体内
 * 无条件 {@code plan.setEndTime(now())} 仍会执行 → 覆盖原完工时间戳 (审计腐蚀, 喂生产时长/人效报表)。
 * live 复现: plan f96df71e-... 完工 00:27, 调 stop → end_time 被覆盖成调用时间 16:20, 原时间戳丢失。
 *
 * <p>允许集镜像前端 web-admin/src/views/production/plans/list.vue:1946-1948
 * {@code isUnfinishedStatus()} 白名单 (PENDING, IN_PROGRESS) —— 该函数决定"停产"按钮的渲染条件,
 * 是本仓库对"哪些状态允许停产"的既有权威判断。PENDING 允许的理由: {@code InterimSettleServiceImpl}
 * 从不检查 plan.status, 存货生产 (SAFETY_STOCK) 计划可能全程停留 PENDING (从未调用
 * startProduction) 就直接靠「小结」逐批扣料入库, 最终直接停产关闭 —— 无需先转 IN_PROGRESS。
 */
@DisplayName("stopProduction — plan-status 守卫 (PENDING/IN_PROGRESS 允许; 其余 409 不覆盖 endTime)")
class ProductionPlanServiceImplStopProductionStatusGuardTest {

    private static final String FACTORY = "F006";
    private static final String PLAN_ID = "PLAN-STOP-1";

    private ProductionPlanServiceImpl newService(ProductionPlanRepository planRepo) throws Exception {
        Constructor<?> ctor = ProductionPlanServiceImpl.class.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Object[] args = new Object[ctor.getParameterCount()];
        Class<?>[] types = ctor.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (types[i] == ProductionPlanRepository.class) {
                args[i] = planRepo;
            } else {
                args[i] = null;
            }
        }
        ProductionPlanServiceImpl service = (ProductionPlanServiceImpl) ctor.newInstance(args);
        org.springframework.test.util.ReflectionTestUtils.setField(service,
                "productionInterimSettlementRepository", mock(ProductionInterimSettlementRepository.class));
        return service;
    }

    /** processSheetRowRepository is field-injected @Autowired(required=false); default null via
     *  reflection ctor keeps findUnsettledPlanConsumptions / hasUnsettledStockFeedOrOutputRows
     *  short-circuited (both null-guard on processSheetRowRepository == null), isolating this test
     *  to the plan-status guard under test. No explicit injection needed. stopProduction is a public
     *  override — call directly, no reflection needed for the method itself. */

    private ProductionPlan planWithStatus(ProductionPlanStatus status) {
        ProductionPlan p = new ProductionPlan();
        p.setId(PLAN_ID);
        p.setFactoryId(FACTORY);
        p.setSourceType(PlanSourceType.SAFETY_STOCK);
        p.setStatus(status);
        p.setPlannedQuantity(BigDecimal.ZERO);
        return p;
    }

    @Test
    @DisplayName("COMPLETED 计划再停产 → 409 拒绝, 不覆盖已有的 endTime (原完工时间戳保持不变)")
    void completedPlan_stopProduction_rejectedWithoutOverwritingEndTime() throws Throwable {
        ProductionPlan plan = planWithStatus(ProductionPlanStatus.COMPLETED);
        LocalDateTime originalEndTime = LocalDateTime.of(2026, 7, 6, 0, 27, 0);
        plan.setEndTime(originalEndTime);

        ProductionPlanRepository planRepo = mock(ProductionPlanRepository.class);
        when(planRepo.findByIdAndFactoryId(PLAN_ID, FACTORY)).thenReturn(Optional.of(plan));

        ProductionPlanServiceImpl svc = newService(planRepo);

        Throwable t = catchThrowable(() -> svc.stopProduction(FACTORY, PLAN_ID));

        assertThat(t).as("已完成计划重复停产必须 409 loud-fail").isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) t).getCode()).isEqualTo(409);
        assertThat(t.getMessage()).contains("只能停产");
        assertThat(plan.getEndTime())
                .as("守卫必须在 setEndTime(now()) 之前拦截, 原完工时间戳不能被覆盖")
                .isEqualTo(originalEndTime);
        verify(planRepo, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("CANCELLED 计划停产 → 409 拒绝")
    void cancelledPlan_stopProduction_rejected() throws Throwable {
        ProductionPlan plan = planWithStatus(ProductionPlanStatus.CANCELLED);

        ProductionPlanRepository planRepo = mock(ProductionPlanRepository.class);
        when(planRepo.findByIdAndFactoryId(PLAN_ID, FACTORY)).thenReturn(Optional.of(plan));

        ProductionPlanServiceImpl svc = newService(planRepo);

        Throwable t = catchThrowable(() -> svc.stopProduction(FACTORY, PLAN_ID));

        assertThat(t).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) t).getCode()).isEqualTo(409);
    }

    @Test
    @DisplayName("PAUSED 计划停产 → 409 拒绝 (镜像前端 isUnfinishedStatus 白名单, 不含 PAUSED)")
    void pausedPlan_stopProduction_rejected() throws Throwable {
        ProductionPlan plan = planWithStatus(ProductionPlanStatus.PAUSED);

        ProductionPlanRepository planRepo = mock(ProductionPlanRepository.class);
        when(planRepo.findByIdAndFactoryId(PLAN_ID, FACTORY)).thenReturn(Optional.of(plan));

        ProductionPlanServiceImpl svc = newService(planRepo);

        Throwable t = catchThrowable(() -> svc.stopProduction(FACTORY, PLAN_ID));

        assertThat(t).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) t).getCode()).isEqualTo(409);
    }

    @Test
    @DisplayName("IN_PROGRESS 计划停产 → 正常放行, 状态→COMPLETED 且写入 endTime")
    void inProgressPlan_stopProduction_succeeds() throws Throwable {
        ProductionPlan plan = planWithStatus(ProductionPlanStatus.IN_PROGRESS);
        plan.setStartTime(LocalDateTime.now().minusHours(2));

        ProductionPlanRepository planRepo = mock(ProductionPlanRepository.class);
        when(planRepo.findByIdAndFactoryId(PLAN_ID, FACTORY)).thenReturn(Optional.of(plan));
        when(planRepo.save(org.mockito.ArgumentMatchers.any(ProductionPlan.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ProductionPlanServiceImpl svc = newService(planRepo);

        Throwable t = catchThrowable(() -> svc.stopProduction(FACTORY, PLAN_ID));

        assertThat(t).as("IN_PROGRESS 是合法停产起点, 不应抛异常").isNull();
        assertThat(plan.getStatus()).isEqualTo(ProductionPlanStatus.COMPLETED);
        assertThat(plan.getEndTime()).isNotNull();
        verify(planRepo).save(plan);
    }

    @Test
    @DisplayName("PENDING 计划停产 → 正常放行 (存货生产计划可全程停留 PENDING 直接小结+停产, 无需先开工)")
    void pendingPlan_stopProduction_succeeds() throws Throwable {
        ProductionPlan plan = planWithStatus(ProductionPlanStatus.PENDING);

        ProductionPlanRepository planRepo = mock(ProductionPlanRepository.class);
        when(planRepo.findByIdAndFactoryId(PLAN_ID, FACTORY)).thenReturn(Optional.of(plan));
        when(planRepo.save(org.mockito.ArgumentMatchers.any(ProductionPlan.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ProductionPlanServiceImpl svc = newService(planRepo);

        Throwable t = catchThrowable(() -> svc.stopProduction(FACTORY, PLAN_ID));

        assertThat(t).as("PENDING 是合法停产起点 (镜像前端 isUnfinishedStatus 白名单)").isNull();
        assertThat(plan.getStatus()).isEqualTo(ProductionPlanStatus.COMPLETED);
        verify(planRepo).save(plan);
    }
}
