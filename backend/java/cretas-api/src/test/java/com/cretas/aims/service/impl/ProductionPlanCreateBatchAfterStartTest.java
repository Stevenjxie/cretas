package com.cretas.aims.service.impl;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 「开始生产」之后必须还能补建批次 —— 否则 workflow 计划在 PC 端彻底死锁。
 *
 * <h2>2026-08-01 prod 实测的死锁（全程正常 UI 操作）</h2>
 *
 * <ol>
 *   <li>点「开始生产」→ {@code /start} 把状态置成 {@code IN_PROGRESS}，<b>但不建批次</b>；</li>
 *   <li>点「逐道录入」→ 前端只在 {@code PENDING} 时自动 create-batch（注释写着
 *       「已转批次(IN_PROGRESS)计划跳过」——<b>把状态当成了有批次的证据</b>），于是跳过 →
 *       抽屉读 workflow-config 拿到 null → <b>一片空白，没有任何提示</b>；</li>
 *   <li>点「核对结单」→ 409「workflow 计划必须先完成并正式提交逐道报工」。</li>
 * </ol>
 *
 * <p>三条路全堵死，而 {@code createBatchFromPlan} 自己也只放行 {@code PENDING} ——
 * 计划一旦开工就<b>再也补不回批次</b>，报不了工也结不了单。
 *
 * <p>修法两条：
 * <ul>
 *   <li>{@code IN_PROGRESS} 且<b>尚未有批次</b>时允许补建（解开死锁）；</li>
 *   <li>已有批次时<b>幂等返回既有批次</b>而不是 409 —— 逐道录入抽屉每次打开都会调一次，
 *       报错只会变成一个每次都弹的无用提示。</li>
 * </ul>
 */
@DisplayName("createBatchFromPlan — 开工后补建批次 + 已有批次幂等")
class ProductionPlanCreateBatchAfterStartTest {

    private static final String FACTORY = "LIUSHANMEN";
    private static final String PLAN_ID = "PLAN-DEADLOCK-1";

    private ProductionPlanServiceImpl newService(
            ProductionPlanRepository planRepo, ProductionBatchRepository batchRepo) throws Exception {
        Constructor<?> ctor = ProductionPlanServiceImpl.class.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Object[] args = new Object[ctor.getParameterCount()];
        Class<?>[] types = ctor.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (types[i] == ProductionPlanRepository.class) {
                args[i] = planRepo;
            } else if (types[i] == ProductionBatchRepository.class) {
                args[i] = batchRepo;
            } else {
                args[i] = null;
            }
        }
        return (ProductionPlanServiceImpl) ctor.newInstance(args);
    }

    private ProductionPlan plan(ProductionPlanStatus status) {
        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(FACTORY);
        plan.setStatus(status);
        return plan;
    }

    @Test
    @DisplayName("已有批次 → 幂等返回既有批次, 不抛 409 (抽屉每次打开都会调)")
    void returnsExistingBatchInsteadOfThrowing() throws Exception {
        ProductionPlanRepository planRepo = mock(ProductionPlanRepository.class);
        ProductionBatchRepository batchRepo = mock(ProductionBatchRepository.class);
        when(planRepo.findByIdForUpdate(PLAN_ID))
                .thenReturn(Optional.of(plan(ProductionPlanStatus.IN_PROGRESS)));
        ProductionBatch existing = new ProductionBatch();
        existing.setId(90210L);
        when(batchRepo.findByFactoryIdAndProductionPlanId(FACTORY, PLAN_ID))
                .thenReturn(List.of(existing));

        ProductionBatch result =
                newService(planRepo, batchRepo).createBatchFromPlan(FACTORY, PLAN_ID);

        assertThat(result.getId())
                .as("已有批次就还回去 —— 抛 409 会让逐道录入每次打开都弹一个无用错误")
                .isEqualTo(90210L);
    }

    @Test
    @DisplayName("终态计划(已完成/已取消)仍然拦住 —— 不能给结束了的计划补批次")
    void stillRefusesTerminalPlans() throws Exception {
        for (ProductionPlanStatus terminal : List.of(
                ProductionPlanStatus.COMPLETED, ProductionPlanStatus.CANCELLED)) {
            ProductionPlanRepository planRepo = mock(ProductionPlanRepository.class);
            ProductionBatchRepository batchRepo = mock(ProductionBatchRepository.class);
            when(planRepo.findByIdForUpdate(PLAN_ID)).thenReturn(Optional.of(plan(terminal)));
            when(batchRepo.findByFactoryIdAndProductionPlanId(FACTORY, PLAN_ID))
                    .thenReturn(List.of());

            Throwable thrown = catchThrowable(() ->
                    newService(planRepo, batchRepo).createBatchFromPlan(FACTORY, PLAN_ID));

            assertThat(thrown)
                    .as("status=%s 时不该放行", terminal)
                    .isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) thrown).getMessage()).contains("转为批次");
        }
    }

    @Test
    @DisplayName("🔴 IN_PROGRESS 且没有批次 → 必须放行 (这正是死锁的那条)")
    void allowsBatchCreationForStartedPlanWithoutBatch() throws Exception {
        ProductionPlanRepository planRepo = mock(ProductionPlanRepository.class);
        ProductionBatchRepository batchRepo = mock(ProductionBatchRepository.class);
        when(planRepo.findByIdForUpdate(PLAN_ID))
                .thenReturn(Optional.of(plan(ProductionPlanStatus.IN_PROGRESS)));
        when(batchRepo.findByFactoryIdAndProductionPlanId(FACTORY, PLAN_ID))
                .thenReturn(List.of());

        Throwable thrown = catchThrowable(() ->
                newService(planRepo, batchRepo).createBatchFromPlan(FACTORY, PLAN_ID));

        // 反射构造的 service 后续依赖(产品仓库等)为 null, 会在状态守卫**之后**炸 ——
        // 本用例只断言「不是被状态守卫拦下的」: 拦下时消息含「转为批次」。
        if (thrown instanceof BusinessException businessException) {
            assertThat(businessException.getMessage())
                    .as("IN_PROGRESS 且无批次时被状态守卫拦下 = 死锁没修好")
                    .doesNotContain("转为批次");
        }
    }
}
