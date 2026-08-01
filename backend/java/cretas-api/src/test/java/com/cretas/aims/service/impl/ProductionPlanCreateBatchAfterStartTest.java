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

    /**
     * ⛔ 反向断言: IN_PROGRESS 且<b>没有</b>批次时必须<b>仍然拦住</b>。
     *
     * <p>放行看起来能解开「开工后报不了工」的死锁, 但会重新打开 R6 并发洞
     * ({@code ProductionPlanCancelConcurrencyTest}: 并发第二个 create-batch 看到
     * IN_PROGRESS 必须 409, 不许建出第二个批次)——第一笔事务尚未提交批次时,
     * 第二笔就会看到「IN_PROGRESS + 无批次」而建出第二个。
     *
     * <p>拿 UX 死锁换并发重复批次是亏的。真正的根在 {@code startProduction}:
     * 它让 workflow 计划合法地进入「开工了但没有批次」这个状态, 那里才是要修的地方。
     */
    @Test
    @DisplayName("⛔ IN_PROGRESS 且无批次 → 仍然拦住 (保住 R6 并发守卫, 不拿它换 UX)")
    void stillRefusesStartedPlanWithoutBatch() throws Exception {
        ProductionPlanRepository planRepo = mock(ProductionPlanRepository.class);
        ProductionBatchRepository batchRepo = mock(ProductionBatchRepository.class);
        when(planRepo.findByIdForUpdate(PLAN_ID))
                .thenReturn(Optional.of(plan(ProductionPlanStatus.IN_PROGRESS)));
        when(batchRepo.findByFactoryIdAndProductionPlanId(FACTORY, PLAN_ID))
                .thenReturn(List.of());

        Throwable thrown = catchThrowable(() ->
                newService(planRepo, batchRepo).createBatchFromPlan(FACTORY, PLAN_ID));

        assertThat(thrown)
                .as("放行会让并发第二个请求建出第二个批次 (R6)")
                .isInstanceOf(BusinessException.class);
        // 拦住的同时要把处境说清楚 —— 原文案「只有待处理的计划可以转为批次」
        // 对「开工了但没批次」这个处境毫无帮助, 用户不知道下一步该干什么。
        BusinessException be = (BusinessException) thrown;
        assertThat(be.getMessage())
                .as("要说清楚是「开工了但没有批次」, 而不是泛泛的状态不对")
                .contains("已开工")
                .contains("没有生产批次");
        assertThat(be.getActionHint())
                .as("必须给出下一步 —— 否则用户只知道被拦住了")
                .contains("APP");
    }
}
