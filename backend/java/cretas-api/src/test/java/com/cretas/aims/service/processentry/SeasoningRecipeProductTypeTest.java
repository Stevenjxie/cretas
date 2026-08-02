package com.cretas.aims.service.processentry;

import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.service.processentry.impl.ProcessSheetServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BOM / 调料配方只挂<b>成品</b> —— 中间道报工必须拿所属计划的成品 SKU 去查, 不能拿该道
 * 产出的半成品。
 *
 * <p>缺陷现场 (LIUSHANMEN 酱鸭腿, 2026-08-02): 熟制道产出「酱制鸭腿(半成品)」, 报工时
 * 用它自己的 productTypeId 查 BOM → 永远查不到 → 每次报工都提示「产品 2df4c600… 未设置
 * 当前 BOM 调料配方, 调料成本暂记 0」。而 {@code BomWorkflowRevisionService} 按设计只允许
 * <b>Workflow 终端产出</b>拥有 BOM, 中间产出永远不满足 —— 那条提示把用户指向一个系统
 * 根本不允许配置的位置。
 *
 * <p>本测试直接打纯解析函数, 不起 Spring。
 */
class SeasoningRecipeProductTypeTest {

    private static final String PLAN_ID = "PLAN-DUCK-001";
    /** 成品「酱鸭腿」—— BOM 挂在它身上。 */
    private static final String FINISHED_SKU = "c57c36e0-finished-duck";
    /** 中间产出「酱制鸭腿(半成品)」—— 按设计不该有 BOM。 */
    private static final String SEMI_SKU = "2df4c600-semi-braised";

    private final ProductionPlanRepository planRepository = mock(ProductionPlanRepository.class);

    private String resolve(String planId, String fallback) {
        try {
            ProcessSheetServiceImpl target = newTarget();
            Method m = ProcessSheetServiceImpl.class.getDeclaredMethod(
                    "resolveRecipeProductTypeId", String.class, String.class);
            m.setAccessible(true);
            return (String) m.invoke(target, planId, fallback);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 只注入本函数用到的那一个依赖, 其余构造参数传 null —— 该函数不碰它们。
     *
     * <p>按<b>参数类型</b>定位注入位置, 不依赖参数顺序; 依赖表将来增删也不会让本测试
     * 悄悄注错对象(注错会 NPE 而不是静默通过)。
     */
    private ProcessSheetServiceImpl newTarget() throws ReflectiveOperationException {
        java.lang.reflect.Constructor<?> ctor =
                ProcessSheetServiceImpl.class.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Class<?>[] types = ctor.getParameterTypes();
        Object[] args = new Object[types.length];
        int injected = 0;
        for (int i = 0; i < types.length; i++) {
            if (types[i] == ProductionPlanRepository.class) {
                args[i] = planRepository;
                injected++;
            }
        }
        if (injected != 1) {
            throw new IllegalStateException(
                    "期望构造函数恰好有 1 个 ProductionPlanRepository 参数, 实际 " + injected);
        }
        return (ProcessSheetServiceImpl) ctor.newInstance(args);
    }

    private ProductionPlan planWith(String productTypeId) {
        ProductionPlan plan = new ProductionPlan();
        plan.setProductTypeId(productTypeId);
        return plan;
    }

    @Test
    @DisplayName("回归: 中间道传半成品 SKU, 也必须解析成计划的成品 SKU")
    void semiFinishedStepResolvesToFinishedSku() {
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(planWith(FINISHED_SKU)));

        // 熟制道自己的产出是半成品 —— 缺陷版本会原样返回它, 导致 BOM 查不到。
        assertEquals(FINISHED_SKU, resolve(PLAN_ID, SEMI_SKU));
    }

    @Test
    @DisplayName("成品道: 计划成品与该道产出相同, 结果不变 (零回归)")
    void finishedStepIsUnchanged() {
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(planWith(FINISHED_SKU)));

        assertEquals(FINISHED_SKU, resolve(PLAN_ID, FINISHED_SKU));
    }

    @Test
    @DisplayName("无 planId: 回落该道自己的 SKU, 且不查库")
    void nullPlanIdFallsBackWithoutQuery() {
        assertEquals(SEMI_SKU, resolve(null, SEMI_SKU));
        assertEquals(SEMI_SKU, resolve("   ", SEMI_SKU));
        verify(planRepository, never()).findById(anyString());
    }

    @Test
    @DisplayName("计划不存在: 回落该道自己的 SKU, 不抛")
    void missingPlanFallsBack() {
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.empty());

        assertEquals(SEMI_SKU, resolve(PLAN_ID, SEMI_SKU));
    }

    @Test
    @DisplayName("计划的成品为空/空串: 回落该道自己的 SKU, 不返回空值")
    void blankPlanProductFallsBack() {
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(planWith(null)));
        assertEquals(SEMI_SKU, resolve(PLAN_ID, SEMI_SKU));

        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(planWith("  ")));
        assertEquals(SEMI_SKU, resolve(PLAN_ID, SEMI_SKU));
    }
}
