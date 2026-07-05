package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.FactorySchedulingConfig;
import com.cretas.aims.entity.FactorySettings;
import com.cretas.aims.entity.FactoryTempWorker;
import com.cretas.aims.entity.WagePolicy;
import com.cretas.aims.entity.config.EncodingRule;
import com.cretas.aims.entity.enums.WageMode;
import com.cretas.aims.entity.hr.HrInsuranceConfig;
import com.cretas.aims.repository.EncodingRuleRepository;
import com.cretas.aims.repository.FactorySchedulingConfigRepository;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.repository.FactoryTempWorkerRepository;
import com.cretas.aims.repository.WagePolicyRepository;
import com.cretas.aims.repository.hr.HrInsuranceConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Smoke tests for {@link CanvasFactoryConfigController}.
 *
 * <p>Coverage focus (Canvas Phase B):
 * <ul>
 *   <li>6 sub-modules basic CRUD reach (scheduling / temp-workers / insurance / wage / encoding / settings)</li>
 *   <li>AUD-4 version conflict path</li>
 *   <li>Cross-factory 404 (security)</li>
 *   <li>4位一体 error response (code + actionHint)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CanvasFactoryConfigController smoke tests")
class CanvasFactoryConfigControllerTest {

    @Mock FactorySchedulingConfigRepository schedulingRepo;
    @Mock FactoryTempWorkerRepository tempWorkerRepo;
    @Mock HrInsuranceConfigRepository insuranceRepo;
    @Mock WagePolicyRepository wagePolicyRepo;
    @Mock EncodingRuleRepository encodingRuleRepo;
    @Mock FactorySettingsRepository factorySettingsRepo;

    private CanvasFactoryConfigController controller;

    @BeforeEach
    void setUp() {
        controller = new CanvasFactoryConfigController(
                schedulingRepo, tempWorkerRepo, insuranceRepo,
                wagePolicyRepo, encodingRuleRepo, factorySettingsRepo);
    }

    // ==================== Overview ====================

    @Test
    @DisplayName("overview 返回 6 子模块统计")
    void overview_returnsAggregateStats() {
        when(schedulingRepo.existsByFactoryId("F006")).thenReturn(true);
        when(tempWorkerRepo.countActiveTempWorkers("F006")).thenReturn(5L);
        when(insuranceRepo.findFirstByFactoryIdAndStatusOrderByEffectiveFromDesc("F006", "ACTIVE"))
                .thenReturn(Optional.empty());
        when(wagePolicyRepo.findByFactoryIdOrderByEmployeeIdAscIdDesc("F006"))
                .thenReturn(List.of());
        when(encodingRuleRepo.countByFactoryIdAndEnabledTrue("F006")).thenReturn(3L);
        when(factorySettingsRepo.existsByFactoryId("F006")).thenReturn(true);

        ApiResponse<Map<String, Object>> resp = controller.overview("F006");

        assertTrue(resp.getSuccess());
        assertEquals(true, resp.getData().get("schedulingConfigured"));
        assertEquals(5L, resp.getData().get("tempWorkerCount"));
        assertEquals(false, resp.getData().get("insuranceActive"));
        assertEquals(3L, resp.getData().get("encodingRuleCount"));
    }

    // ==================== Scheduling ====================

    @Test
    @DisplayName("getScheduling 未配置时返回默认值")
    void getScheduling_unconfigured_returnsDefault() {
        when(schedulingRepo.findByFactoryId("F006")).thenReturn(Optional.empty());

        ApiResponse<Map<String, Object>> resp = controller.getScheduling("F006");

        assertTrue(resp.getSuccess());
        assertEquals("F006", resp.getData().get("factoryId"));
        assertNotNull(resp.getData().get("linucbWeight"));
    }

    @Test
    @DisplayName("updateScheduling new (id=null) skips version check, saves new row")
    void updateScheduling_newConfig_succeeds() {
        when(schedulingRepo.findByFactoryId("F006")).thenReturn(Optional.empty());
        when(schedulingRepo.saveAndFlush(any(FactorySchedulingConfig.class)))
                .thenAnswer(inv -> {
                    FactorySchedulingConfig c = inv.getArgument(0);
                    c.setId(1L);
                    c.setVersion(0L);
                    return c;
                });
        Map<String, Object> body = new HashMap<>();
        body.put("linucbWeight", 0.65);

        ApiResponse<Map<String, Object>> resp = controller.updateScheduling("F006", body);

        assertTrue(resp.getSuccess());
        assertEquals("保存成功", resp.getMessage());
    }

    @Test
    @DisplayName("updateScheduling 已存在记录无 version 返 400 VERSION_MISSING")
    void updateScheduling_existingWithoutVersion_returns400() {
        FactorySchedulingConfig existing = new FactorySchedulingConfig();
        existing.setId(1L);
        existing.setFactoryId("F006");
        existing.setVersion(3L);
        when(schedulingRepo.findByFactoryId("F006")).thenReturn(Optional.of(existing));

        Map<String, Object> body = new HashMap<>();
        body.put("linucbWeight", 0.65);

        ApiResponse<Map<String, Object>> resp = controller.updateScheduling("F006", body);

        assertFalse(resp.getSuccess());
        assertEquals(400, resp.getCode());
        assertEquals("VERSION_MISSING", resp.getErrorCode());
        assertNotNull(resp.getActionHint());
    }

    @Test
    @DisplayName("updateScheduling version mismatch 返 409 VERSION_CONFLICT")
    void updateScheduling_versionConflict_returns409() {
        FactorySchedulingConfig existing = new FactorySchedulingConfig();
        existing.setId(1L);
        existing.setFactoryId("F006");
        existing.setVersion(5L);
        when(schedulingRepo.findByFactoryId("F006")).thenReturn(Optional.of(existing));

        Map<String, Object> body = new HashMap<>();
        body.put("version", 2);
        body.put("linucbWeight", 0.65);

        ApiResponse<Map<String, Object>> resp = controller.updateScheduling("F006", body);

        assertFalse(resp.getSuccess());
        assertEquals(409, resp.getCode());
        assertEquals("VERSION_CONFLICT", resp.getErrorCode());
    }

    // ==================== Temp Workers ====================

    @Test
    @DisplayName("createTempWorker 缺 workerId 返 400")
    void createTempWorker_missingWorkerId_returns400() {
        Map<String, Object> body = new HashMap<>();
        body.put("hireDate", "2026-05-01");

        ApiResponse<Map<String, Object>> resp = controller.createTempWorker("F006", body);

        assertFalse(resp.getSuccess());
        assertEquals(400, resp.getCode());
        assertTrue(resp.getMessage().contains("workerId"));
    }

    @Test
    @DisplayName("createTempWorker duplicate workerId 返 409")
    void createTempWorker_duplicateWorker_returns409() {
        FactoryTempWorker existing = new FactoryTempWorker();
        existing.setId(99L);
        when(tempWorkerRepo.findByFactoryIdAndWorkerId("F006", 1L))
                .thenReturn(Optional.of(existing));

        Map<String, Object> body = new HashMap<>();
        body.put("workerId", 1);
        body.put("hireDate", "2026-05-01");

        ApiResponse<Map<String, Object>> resp = controller.createTempWorker("F006", body);

        assertFalse(resp.getSuccess());
        assertEquals(409, resp.getCode());
    }

    @Test
    @DisplayName("updateTempWorker cross-factory 返 404")
    void updateTempWorker_crossFactory_returns404() {
        FactoryTempWorker w = new FactoryTempWorker();
        w.setId(1L);
        w.setFactoryId("F999");
        w.setVersion(0L);
        when(tempWorkerRepo.findById(1L)).thenReturn(Optional.of(w));

        Map<String, Object> body = new HashMap<>();
        body.put("version", 0);

        ApiResponse<Map<String, Object>> resp = controller.updateTempWorker("F006", 1L, body);

        assertFalse(resp.getSuccess());
        assertEquals(404, resp.getCode());
    }

    // ==================== Insurance ====================

    @Test
    @DisplayName("listInsurance 返回历史 + active")
    void listInsurance_returnsHistoryAndActive() {
        HrInsuranceConfig active = HrInsuranceConfig.builder()
                .id("ins-1")
                .factoryId("F006")
                .status("ACTIVE")
                .effectiveFrom(LocalDate.of(2026, 1, 1))
                .employeePensionRate(new BigDecimal("0.0800"))
                .employerPensionRate(new BigDecimal("0.1600"))
                .employeeMedicalRate(new BigDecimal("0.0200"))
                .employerMedicalRate(new BigDecimal("0.0800"))
                .employeeUnemploymentRate(new BigDecimal("0.0050"))
                .employerUnemploymentRate(new BigDecimal("0.0050"))
                .employeeProvidentFundRate(new BigDecimal("0.0800"))
                .employerProvidentFundRate(new BigDecimal("0.0800"))
                .build();
        when(insuranceRepo.findByFactoryIdOrderByEffectiveFromDesc("F006"))
                .thenReturn(List.of(active));
        when(insuranceRepo.findFirstByFactoryIdAndStatusOrderByEffectiveFromDesc("F006", "ACTIVE"))
                .thenReturn(Optional.of(active));

        ApiResponse<Map<String, Object>> resp = controller.listInsurance("F006");

        assertTrue(resp.getSuccess());
        assertNotNull(resp.getData().get("history"));
        assertNotNull(resp.getData().get("active"));
    }

    @Test
    @DisplayName("createInsurance 缺 effectiveFrom 返 400")
    void createInsurance_missingEffectiveFrom_returns400() {
        Map<String, Object> body = new HashMap<>();

        ApiResponse<Map<String, Object>> resp = controller.createInsurance("F006", body);

        assertFalse(resp.getSuccess());
        assertEquals(400, resp.getCode());
        assertTrue(resp.getMessage().contains("effectiveFrom"));
    }

    // ==================== Wage Policies ====================

    @Test
    @DisplayName("createWagePolicy 非法 mode 返 400")
    void createWagePolicy_invalidMode_returns400() {
        Map<String, Object> body = new HashMap<>();
        body.put("mode", "BOGUS_MODE");

        ApiResponse<Map<String, Object>> resp = controller.createWagePolicy("F006", body);

        assertFalse(resp.getSuccess());
        assertEquals(400, resp.getCode());
        assertTrue(resp.getMessage().contains("mode"));
    }

    @Test
    @DisplayName("createWagePolicy mode=HOURLY 成功")
    void createWagePolicy_hourlyMode_succeeds() {
        when(wagePolicyRepo.saveAndFlush(any(WagePolicy.class))).thenAnswer(inv -> {
            WagePolicy p = inv.getArgument(0);
            p.setId(1L);
            return p;
        });

        Map<String, Object> body = new HashMap<>();
        body.put("mode", "HOURLY");
        body.put("employeeId", 42);

        ApiResponse<Map<String, Object>> resp = controller.createWagePolicy("F006", body);

        assertTrue(resp.getSuccess());
        assertEquals("HOURLY", resp.getData().get("mode"));
        assertEquals(42L, resp.getData().get("employeeId"));
    }

    @Test
    @DisplayName("updateWagePolicy version conflict 返 409")
    void updateWagePolicy_versionConflict_returns409() {
        WagePolicy p = WagePolicy.builder()
                .factoryId("F006")
                .mode(WageMode.PIECE_RATE)
                .version(3L)
                .isActive(true)
                .build();
        p.setId(1L);
        when(wagePolicyRepo.findById(1L)).thenReturn(Optional.of(p));

        Map<String, Object> body = new HashMap<>();
        body.put("version", 1);
        body.put("mode", "HOURLY");

        ApiResponse<Map<String, Object>> resp = controller.updateWagePolicy("F006", 1L, body);

        assertFalse(resp.getSuccess());
        assertEquals(409, resp.getCode());
        assertEquals("VERSION_CONFLICT", resp.getErrorCode());
    }

    // ==================== Encoding Rules ====================

    @Test
    @DisplayName("createEncodingRule 缺必填字段返 400")
    void createEncodingRule_missingRequired_returns400() {
        Map<String, Object> body = new HashMap<>();
        body.put("entityType", "MATERIAL_BATCH");

        ApiResponse<Map<String, Object>> resp = controller.createEncodingRule("F006", body);

        assertFalse(resp.getSuccess());
        assertEquals(400, resp.getCode());
    }

    @Test
    @DisplayName("createEncodingRule duplicate entityType 返 409")
    void createEncodingRule_duplicate_returns409() {
        when(encodingRuleRepo.existsByFactoryIdAndEntityType("F006", "SHIPMENT"))
                .thenReturn(true);

        Map<String, Object> body = new HashMap<>();
        body.put("entityType", "SHIPMENT");
        body.put("ruleName", "出货编号");
        body.put("encodingPattern", "SH-{FACTORY}-{YYYYMMDD}-{SEQ:4}");

        ApiResponse<Map<String, Object>> resp = controller.createEncodingRule("F006", body);

        assertFalse(resp.getSuccess());
        assertEquals(409, resp.getCode());
    }

    @Test
    @DisplayName("createEncodingRule 非法 resetCycle 返 400")
    void createEncodingRule_invalidResetCycle_returns400() {
        when(encodingRuleRepo.existsByFactoryIdAndEntityType("F006", "X"))
                .thenReturn(false);

        Map<String, Object> body = new HashMap<>();
        body.put("entityType", "X");
        body.put("ruleName", "X");
        body.put("encodingPattern", "X");
        body.put("resetCycle", "BOGUS_CYCLE");

        ApiResponse<Map<String, Object>> resp = controller.createEncodingRule("F006", body);

        assertFalse(resp.getSuccess());
        assertEquals(400, resp.getCode());
    }

    @Test
    @DisplayName("updateEncodingRule cross-factory 返 404")
    void updateEncodingRule_crossFactory_returns404() {
        EncodingRule r = EncodingRule.builder()
                .id("rule-x")
                .factoryId("F999")
                .optLockVersion(0L)
                .build();
        when(encodingRuleRepo.findById("rule-x")).thenReturn(Optional.of(r));

        ApiResponse<Map<String, Object>> resp = controller.updateEncodingRule("F006", "rule-x",
                new HashMap<>());

        assertFalse(resp.getSuccess());
        assertEquals(404, resp.getCode());
    }

    // ==================== Factory Settings ====================

    @Test
    @DisplayName("getSettings 未配置时返默认 stub")
    void getSettings_unconfigured_returnsStub() {
        when(factorySettingsRepo.findByFactoryId("F006")).thenReturn(Optional.empty());

        ApiResponse<Map<String, Object>> resp = controller.getSettings("F006");

        assertTrue(resp.getSuccess());
        assertEquals("F006", resp.getData().get("factoryId"));
        assertEquals("zh-CN", resp.getData().get("language"));
        assertEquals(false, resp.getData().get("skipProcessReportingDefault"));
        assertEquals(false, resp.getData().get("requireRequisitionBeforeReport"));
    }

    @Test
    @DisplayName("updateSettings new 不需 version")
    void updateSettings_new_succeedsWithoutVersion() {
        when(factorySettingsRepo.findByFactoryId("F006")).thenReturn(Optional.empty());
        when(factorySettingsRepo.saveAndFlush(any(FactorySettings.class))).thenAnswer(inv -> {
            FactorySettings s = inv.getArgument(0);
            s.setId(1);
            s.setVersion(0L);
            return s;
        });

        Map<String, Object> body = new HashMap<>();
        body.put("factoryName", "测试工厂");
        body.put("skipProcessReportingDefault", true);

        ApiResponse<Map<String, Object>> resp = controller.updateSettings("F006", body);

        assertTrue(resp.getSuccess());
        assertEquals(true, resp.getData().get("skipProcessReportingDefault"));
    }

    @Test
    @DisplayName("updateSettings existing without version 返 400")
    void updateSettings_skipProcessReportingDefaultExplicitNull_setsFalse() {
        FactorySettings s = new FactorySettings();
        s.setId(1);
        s.setFactoryId("F006");
        s.setVersion(2L);
        s.setSkipProcessReportingDefault(true);
        when(factorySettingsRepo.findByFactoryId("F006")).thenReturn(Optional.of(s));
        when(factorySettingsRepo.saveAndFlush(any(FactorySettings.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> body = new HashMap<>();
        body.put("version", 2L);
        body.put("skipProcessReportingDefault", null);

        ApiResponse<Map<String, Object>> resp = controller.updateSettings("F006", body);

        assertTrue(resp.getSuccess());
        assertEquals(false, resp.getData().get("skipProcessReportingDefault"));
    }

    @Test
    @DisplayName("updateSettings existing without version returns 400")
    void updateSettings_existingWithoutVersion_returns400() {
        FactorySettings s = new FactorySettings();
        s.setId(1);
        s.setFactoryId("F006");
        s.setVersion(2L);
        when(factorySettingsRepo.findByFactoryId("F006")).thenReturn(Optional.of(s));

        ApiResponse<Map<String, Object>> resp = controller.updateSettings("F006", new HashMap<>());

        assertFalse(resp.getSuccess());
        assertEquals(400, resp.getCode());
        assertEquals("VERSION_MISSING", resp.getErrorCode());
    }

    // ==================== requireRequisitionBeforeReport 🔴 silent-drop regression ====================
    // Confirmed live on F006 + prod DB: PUT returned 200 + bumped version but the toggle was
    // never written back (missing from updateSettings()) nor read back (missing from
    // serializeFactorySettings()), so 张权's 仓管员领料红线 (报工前必须领料确认) could never
    // actually be turned on via the Canvas Factory Config Hub settings panel.

    @Test
    @DisplayName("updateSettings requireRequisitionBeforeReport=true 新建时持久化且可读回")
    void updateSettings_requireRequisitionBeforeReport_persistsAndIsReadable() {
        when(factorySettingsRepo.findByFactoryId("F006")).thenReturn(Optional.empty());
        when(factorySettingsRepo.saveAndFlush(any(FactorySettings.class))).thenAnswer(inv -> {
            FactorySettings s = inv.getArgument(0);
            s.setId(1);
            s.setVersion(0L);
            return s;
        });

        Map<String, Object> body = new HashMap<>();
        body.put("factoryName", "测试工厂");
        body.put("requireRequisitionBeforeReport", true);

        ApiResponse<Map<String, Object>> resp = controller.updateSettings("F006", body);

        assertTrue(resp.getSuccess());
        // Read-back must reflect the persisted value (was previously absent from the response
        // entirely, which meant the frontend's Object.assign(form, res.data) never overwrote the
        // optimistic local toggle state — masking the silent drop with a false "saved" UI state).
        assertEquals(true, resp.getData().get("requireRequisitionBeforeReport"));
    }

    @Test
    @DisplayName("updateSettings 部分PUT不携带requireRequisitionBeforeReport时不清空已有值")
    void updateSettings_partialPut_doesNotWipeExistingRequireRequisitionBeforeReport() {
        FactorySettings s = new FactorySettings();
        s.setId(1);
        s.setFactoryId("F006");
        s.setVersion(2L);
        s.setRequireRequisitionBeforeReport(true);
        when(factorySettingsRepo.findByFactoryId("F006")).thenReturn(Optional.of(s));
        when(factorySettingsRepo.saveAndFlush(any(FactorySettings.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> body = new HashMap<>();
        body.put("version", 2L);
        body.put("factoryName", "改个名字"); // unrelated field only — key absent for the gate toggle

        ApiResponse<Map<String, Object>> resp = controller.updateSettings("F006", body);

        assertTrue(resp.getSuccess());
        assertEquals(true, resp.getData().get("requireRequisitionBeforeReport"));
        assertEquals("改个名字", resp.getData().get("factoryName"));
    }

    @Test
    @DisplayName("updateSettings requireRequisitionBeforeReport 显式null重置为false")
    void updateSettings_requireRequisitionBeforeReportExplicitNull_setsFalse() {
        FactorySettings s = new FactorySettings();
        s.setId(1);
        s.setFactoryId("F006");
        s.setVersion(2L);
        s.setRequireRequisitionBeforeReport(true);
        when(factorySettingsRepo.findByFactoryId("F006")).thenReturn(Optional.of(s));
        when(factorySettingsRepo.saveAndFlush(any(FactorySettings.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> body = new HashMap<>();
        body.put("version", 2L);
        body.put("requireRequisitionBeforeReport", null);

        ApiResponse<Map<String, Object>> resp = controller.updateSettings("F006", body);

        assertTrue(resp.getSuccess());
        assertEquals(false, resp.getData().get("requireRequisitionBeforeReport"));
    }
}
