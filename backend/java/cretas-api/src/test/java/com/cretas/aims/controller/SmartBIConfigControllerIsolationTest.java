package com.cretas.aims.controller;

import com.cretas.aims.dto.smartbi.ConfigOperationResult;
import com.cretas.aims.dto.smartbi.CreateIncentiveRuleRequest;
import com.cretas.aims.entity.smartbi.AiIntentConfig;
import com.cretas.aims.entity.smartbi.SmartBiIncentiveRule;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.smartbi.AiIntentConfigRepository;
import com.cretas.aims.repository.smartbi.SmartBiAlertThresholdRepository;
import com.cretas.aims.repository.smartbi.SmartBiChartTemplateRepository;
import com.cretas.aims.repository.smartbi.SmartBiDictionaryRepository;
import com.cretas.aims.repository.smartbi.SmartBiIncentiveRuleRepository;
import com.cretas.aims.repository.smartbi.SmartBiMetricFormulaRepository;
import com.cretas.aims.service.smartbi.DataSourceRegistryService;
import com.cretas.aims.service.smartbi.SmartBIConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import com.cretas.aims.dto.common.ApiResponse;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 🔒 多租户隔离守卫单测 (2026-06-17) — 镜像 PR #982 (ScheduledTaskController) 跨厂拒绝用例。
 *
 * <p>背景: {@link SmartBIConfigController} 路径被 {@code JwtAuthInterceptor} 的
 * path-factoryId gate 排除 (path 第一段 = smartbi-config 不是 factoryId)。修复前:
 * <ul>
 *   <li>list 端点不按 caller 工厂过滤 → 读泄露别家工厂配置 + id 泄露;</li>
 *   <li>by-id 写端点 {@code findById(id)} 不校验 {@code existing.factoryId == caller工厂}
 *       → 跨租户篡改 AI 意图路由 / 告警阈值 / 激励规则(提成/薪酬) / 字段映射 / 指标公式 / 图表模板。</li>
 * </ul>
 *
 * <p>策略: 平台角色 (platform_admin/super_admin) 跨工厂 + 可改 global, by design;
 * 工厂级角色只能读/改本厂 或 global(null) 的配置, 改 global → 平台专属 403, 改别家 → 403。
 */
@DisplayName("🔒 SmartBIConfig 多租户隔离守卫")
class SmartBIConfigControllerIsolationTest {

    private SmartBIConfigService configService;
    private DataSourceRegistryService dataSourceService;
    private AiIntentConfigRepository intentConfigRepository;
    private SmartBiAlertThresholdRepository alertThresholdRepository;
    private SmartBiIncentiveRuleRepository incentiveRuleRepository;
    private SmartBiDictionaryRepository dictionaryRepository;
    private SmartBiMetricFormulaRepository metricFormulaRepository;
    private SmartBiChartTemplateRepository chartTemplateRepository;

    private SmartBIConfigController controller;

    @BeforeEach
    void setUp() {
        configService = mock(SmartBIConfigService.class);
        dataSourceService = mock(DataSourceRegistryService.class);
        intentConfigRepository = mock(AiIntentConfigRepository.class);
        alertThresholdRepository = mock(SmartBiAlertThresholdRepository.class);
        incentiveRuleRepository = mock(SmartBiIncentiveRuleRepository.class);
        dictionaryRepository = mock(SmartBiDictionaryRepository.class);
        metricFormulaRepository = mock(SmartBiMetricFormulaRepository.class);
        chartTemplateRepository = mock(SmartBiChartTemplateRepository.class);

        controller = new SmartBIConfigController(
                configService, dataSourceService,
                intentConfigRepository, alertThresholdRepository, incentiveRuleRepository,
                dictionaryRepository, metricFormulaRepository, chartTemplateRepository);
    }

    /** 工厂级角色 (F001) 的请求。 */
    private static MockHttpServletRequest factoryReq(String factoryId) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setAttribute("role", "factory_super_admin");
        r.setAttribute("factoryId", factoryId);
        return r;
    }

    /** 平台角色请求 — 跨工厂 by design。 */
    private static MockHttpServletRequest platformReq() {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setAttribute("role", "platform_admin");
        r.setAttribute("factoryId", "F001");
        return r;
    }

    // ==================== by-id 写: 跨厂 / global 拒绝 ====================

    @Test
    @DisplayName("工厂级管理员改别家工厂的意图配置 → 403")
    void updateIntent_crossFactory_rejected() {
        AiIntentConfig other = new AiIntentConfig();
        other.setId("intent-1");
        other.setFactoryId("F999"); // 别家工厂
        when(intentConfigRepository.findById("intent-1")).thenReturn(Optional.of(other));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.updateIntent(factoryReq("F001"), "intent-1", new AiIntentConfig()),
                "跨工厂改意图必须 403");

        assertEquals(403, ex.getCode());
        verify(configService, never()).updateIntent(anyString(), any());
    }

    @Test
    @DisplayName("工厂级管理员改全局共享(global)意图配置 → 403 (平台专属)")
    void updateIntent_globalConfig_rejectedForFactoryRole() {
        AiIntentConfig global = new AiIntentConfig();
        global.setId("intent-g");
        global.setFactoryId(null); // global 共享默认
        when(intentConfigRepository.findById("intent-g")).thenReturn(Optional.of(global));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.updateIntent(factoryReq("F001"), "intent-g", new AiIntentConfig()),
                "改 global 配置须平台角色, 工厂级 403");

        assertEquals(403, ex.getCode());
        verify(configService, never()).updateIntent(anyString(), any());
    }

    @Test
    @DisplayName("工厂级管理员删别家工厂的激励规则(提成) → 403")
    void deleteIncentiveRule_crossFactory_rejected() {
        SmartBiIncentiveRule other = new SmartBiIncentiveRule();
        other.setId(42L);
        other.setFactoryId("F999");
        when(incentiveRuleRepository.findById(42L)).thenReturn(Optional.of(other));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.deleteIncentiveRule(factoryReq("F001"), 42L),
                "跨工厂删激励规则必须 403");

        assertEquals(403, ex.getCode());
        verify(configService, never()).deleteIncentiveRule(any());
    }

    // ==================== create: 跨厂拒绝 ====================

    @Test
    @DisplayName("工厂级管理员为别家工厂创建激励规则 → 403")
    void createIncentiveRule_crossFactory_rejected() {
        CreateIncentiveRuleRequest req = new CreateIncentiveRuleRequest();
        req.setRuleCode("SALES_TARGET");
        req.setFactoryId("F999"); // 指向别家

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.createIncentiveRule(factoryReq("F001"), req),
                "跨工厂创建激励规则必须 403");

        assertEquals(403, ex.getCode());
        verify(configService, never()).createIncentiveRule(any());
    }

    @Test
    @DisplayName("工厂级管理员创建意图缺省 factoryId → 自动补本厂 (放行)")
    void createIntent_nullFactoryId_defaultsToCallerFactory() {
        AiIntentConfig config = new AiIntentConfig();
        config.setIntentCode("X");
        config.setFactoryId(null); // 缺省
        when(configService.createIntent(any())).thenReturn(
                ConfigOperationResult.success("INTENT", "CREATE", "ok", 1));

        controller.createIntent(factoryReq("F001"), config);

        assertEquals("F001", config.getFactoryId(), "缺省 factoryId 应补成 caller 工厂");
        verify(configService).createIntent(any());
    }

    // ==================== list: 过滤别家 + 保留 global ====================

    @Test
    @DisplayName("工厂级管理员 list 意图 → 只返回本厂 + global(null), 排除别家")
    void listIntents_factoryRole_filtersForeignFactory() {
        AiIntentConfig mine = intent("a", "F001");
        AiIntentConfig global = intent("b", null);
        AiIntentConfig foreign = intent("c", "F999");
        when(configService.listIntents(any())).thenReturn(List.of(mine, global, foreign));

        ResponseEntity<ApiResponse<List<AiIntentConfig>>> resp =
                controller.listIntents(factoryReq("F001"), null);

        List<AiIntentConfig> data = resp.getBody().getData();
        assertEquals(2, data.size(), "只保留本厂 + global");
        assertTrue(data.contains(mine) && data.contains(global));
        assertTrue(!data.contains(foreign), "别家工厂配置必须被过滤掉");
    }

    @Test
    @DisplayName("平台角色 list 意图 → 返回全部 (跨工厂 by design)")
    void listIntents_platformRole_returnsAll() {
        AiIntentConfig mine = intent("a", "F001");
        AiIntentConfig foreign = intent("c", "F999");
        when(configService.listIntents(any())).thenReturn(List.of(mine, foreign));

        ResponseEntity<ApiResponse<List<AiIntentConfig>>> resp =
                controller.listIntents(platformReq(), null);

        assertEquals(2, resp.getBody().getData().size(), "平台角色看全部");
    }

    private static AiIntentConfig intent(String id, String factoryId) {
        AiIntentConfig c = new AiIntentConfig();
        c.setId(id);
        c.setFactoryId(factoryId);
        return c;
    }
}
