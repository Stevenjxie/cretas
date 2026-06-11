package com.cretas.aims.service.production;

import com.cretas.aims.dto.production.CreateProductionPlanRequest;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.mapper.ProductionPlanMapper;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Fable 审计修复 (2026-06-11 — 问题1, 多租户安全红线) 单元测试.
 *
 * <p>验证: createProductionPlan 的 skipProcessReporting=null 解析为"该工厂的默认值",
 * 而非旧的"全系统 null→true"过度泛化:
 * <ul>
 *   <li>其他工厂 (settings 默认 false) → 逐道 (false), 不被静默改两点。</li>
 *   <li>F006 (settings seed true) → 两点 (true)。</li>
 *   <li>无 settings 行 / repo 未注入 → 兜底 false (安全默认逐道)。</li>
 *   <li>显式 true/false → 一律尊重调用方。</li>
 *   <li>mapper 自身兜底 (绕过 service 直调) = false, 不再 null→true 全系统泛化。</li>
 * </ul>
 */
@DisplayName("免工序报工默认值 — 工厂级 (Fable 审计修复 问题1)")
class ProductionPlanReportModeDefaultTest {

    // ==================== mapper 层: null→FALSE 安全兜底 ====================

    private CreateProductionPlanRequest req(Boolean skip) {
        CreateProductionPlanRequest r = new CreateProductionPlanRequest();
        r.setProductTypeId("PT-1");
        r.setPlannedQuantity(new BigDecimal("100"));
        r.setSkipProcessReporting(skip);
        return r;
    }

    @Test
    @DisplayName("mapper: skipProcessReporting=null → FALSE (安全兜底, 不再 null→true 全系统泛化)")
    void mapper_null_defaultsFalse() {
        ProductionPlanMapper mapper = new ProductionPlanMapper();
        ProductionPlan plan = mapper.toEntity(req(null), "F999", 1L);
        assertEquals(Boolean.FALSE, plan.getSkipProcessReporting(),
                "mapper 直调 null → false (逐道); 工厂级默认由 service 前置解析");
    }

    @Test
    @DisplayName("mapper: 显式 true → 采纳 true")
    void mapper_explicitTrue_honored() {
        ProductionPlanMapper mapper = new ProductionPlanMapper();
        ProductionPlan plan = mapper.toEntity(req(Boolean.TRUE), "F999", 1L);
        assertEquals(Boolean.TRUE, plan.getSkipProcessReporting());
    }

    @Test
    @DisplayName("mapper: 显式 false → 采纳 false")
    void mapper_explicitFalse_honored() {
        ProductionPlanMapper mapper = new ProductionPlanMapper();
        ProductionPlan plan = mapper.toEntity(req(Boolean.FALSE), "F999", 1L);
        assertEquals(Boolean.FALSE, plan.getSkipProcessReporting());
    }

    // ==================== service 层: resolveSkipProcessReportingDefault 工厂级解析 ====================

    private ProductionPlanServiceImpl newService(FactorySettingsRepository settingsRepo) throws Exception {
        Constructor<?> ctor = ProductionPlanServiceImpl.class.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Object[] args = new Object[ctor.getParameterCount()];
        // 所有构造器参数置 null (本测试只走 resolve 逻辑, 不触达其他 repo)
        ProductionPlanServiceImpl svc = (ProductionPlanServiceImpl) ctor.newInstance(args);
        if (settingsRepo != null) {
            Field f = ProductionPlanServiceImpl.class.getDeclaredField("factorySettingsRepository");
            f.setAccessible(true);
            f.set(svc, settingsRepo);
        }
        return svc;
    }

    private boolean resolve(ProductionPlanServiceImpl svc, String factoryId) throws Exception {
        Method m = ProductionPlanServiceImpl.class.getDeclaredMethod(
                "resolveSkipProcessReportingDefault", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(svc, factoryId);
    }

    @Test
    @DisplayName("service: 其他工厂 settings 默认 false → 解析逐道 (false, 不被误伤)")
    void resolve_otherFactory_false() throws Exception {
        FactorySettingsRepository settingsRepo = mock(FactorySettingsRepository.class);
        when(settingsRepo.findSkipProcessReportingDefaultByFactoryId("F001")).thenReturn(false);
        ProductionPlanServiceImpl svc = newService(settingsRepo);
        assertFalse(resolve(svc, "F001"), "其他工厂默认逐道 → 保留溯源/成本/出成率/自学习/人效");
    }

    @Test
    @DisplayName("service: F006 settings seed true → 解析两点 (true)")
    void resolve_f006_true() throws Exception {
        FactorySettingsRepository settingsRepo = mock(FactorySettingsRepository.class);
        when(settingsRepo.findSkipProcessReportingDefaultByFactoryId("F006")).thenReturn(true);
        ProductionPlanServiceImpl svc = newService(settingsRepo);
        assertTrue(resolve(svc, "F006"), "F006 默认两点 (六扇门 want)");
    }

    @Test
    @DisplayName("service: 无 settings 行 (repo 返 null) → 兜底逐道 (false, 安全默认)")
    void resolve_noSettingsRow_false() throws Exception {
        FactorySettingsRepository settingsRepo = mock(FactorySettingsRepository.class);
        when(settingsRepo.findSkipProcessReportingDefaultByFactoryId("F777")).thenReturn(null);
        ProductionPlanServiceImpl svc = newService(settingsRepo);
        assertFalse(resolve(svc, "F777"), "无 settings 行 → 兜底逐道 (安全)");
    }

    @Test
    @DisplayName("service: repo 未注入 (单测/降级) → 兜底逐道 (false)")
    void resolve_repoNotInjected_false() throws Exception {
        ProductionPlanServiceImpl svc = newService(null);
        assertFalse(resolve(svc, "F006"), "repo 未注入 → 兜底逐道 (安全, 绝不全系统两点)");
    }
}
