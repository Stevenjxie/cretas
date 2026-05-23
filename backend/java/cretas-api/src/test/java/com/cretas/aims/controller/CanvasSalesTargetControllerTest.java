package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.CommissionRule;
import com.cretas.aims.repository.CommissionRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
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
 * Smoke tests for {@link CanvasSalesTargetController}.
 *
 * <p>Coverage focus (Canvas Phase B Sales Target Hub):
 * <ul>
 *   <li>List + detail + overview</li>
 *   <li>Create with flat percentage</li>
 *   <li>Create with tier ladder (validation: overlap / out-of-range / size cap)</li>
 *   <li>Update version conflict (AUD-4)</li>
 *   <li>Preview commission (flat + tier branches)</li>
 *   <li>Cross-factory 404 security</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CanvasSalesTargetController smoke tests")
class CanvasSalesTargetControllerTest {

    @Mock CommissionRuleRepository commissionRuleRepo;

    private CanvasSalesTargetController controller;

    @BeforeEach
    void setUp() {
        controller = new CanvasSalesTargetController(commissionRuleRepo);
    }

    // Helper to build a sample flat rule
    private CommissionRule flatRule(String id, String factoryId, BigDecimal pct, Long version) {
        CommissionRule r = new CommissionRule();
        r.setId(id);
        r.setFactoryId(factoryId);
        r.setPercentage(pct);
        r.setEffectiveFrom(LocalDate.now());
        r.setActive(true);
        r.setCreatedBy(1L);
        r.setPeriodType("MONTHLY");
        r.setVersion(version);
        return r;
    }

    private CommissionRule tieredRule(String id, String factoryId, Long version) {
        CommissionRule r = flatRule(id, factoryId, BigDecimal.ZERO, version);
        List<Map<String, Object>> tiers = new ArrayList<>();
        tiers.add(Map.of("minAmount", 0, "maxAmount", 100000, "rate", 5.0));
        tiers.add(Map.of("minAmount", 100000, "maxAmount", 500000, "rate", 7.0));
        tiers.add(Map.of("minAmount", 500000, "rate", 10.0));
        r.setTierConfig(tiers);
        return r;
    }

    // ==================== Overview ====================

    @Test
    @DisplayName("overview 汇总规则统计")
    void overview_returnsStats() {
        List<CommissionRule> rules = new ArrayList<>();
        rules.add(flatRule("r1", "F006", new BigDecimal("5"), 0L));
        rules.add(tieredRule("r2", "F006", 0L));
        when(commissionRuleRepo.findByFactoryIdAndDeletedAtIsNullOrderByCreatedAtDesc("F006"))
                .thenReturn(rules);

        ApiResponse<Map<String, Object>> resp = controller.overview("F006");

        assertTrue(resp.getSuccess());
        assertEquals(2, resp.getData().get("totalRules"));
        assertEquals(2L, resp.getData().get("activeRules"));
        assertEquals(1L, resp.getData().get("tieredRules"));
    }

    // ==================== List + Detail ====================

    @Test
    @DisplayName("listRules 返回工厂全部活跃规则")
    void listRules_returnsAll() {
        when(commissionRuleRepo.findByFactoryIdAndDeletedAtIsNullOrderByCreatedAtDesc("F006"))
                .thenReturn(List.of(flatRule("r1", "F006", new BigDecimal("5"), 0L)));

        ApiResponse<List<Map<String, Object>>> resp = controller.listRules("F006");

        assertTrue(resp.getSuccess());
        assertEquals(1, resp.getData().size());
        assertEquals("r1", resp.getData().get(0).get("id"));
    }

    @Test
    @DisplayName("getRule cross-factory 返 404")
    void getRule_crossFactory_returns404() {
        when(commissionRuleRepo.findByIdAndFactoryIdAndDeletedAtIsNull("r1", "F006"))
                .thenReturn(Optional.empty());

        ApiResponse<Map<String, Object>> resp = controller.getRule("F006", "r1");

        assertFalse(resp.getSuccess());
        assertEquals(404, resp.getCode());
    }

    // ==================== Create ====================

    @Test
    @DisplayName("createRule 缺 percentage 与 tierConfig 返 400")
    void createRule_missingBoth_returns400() {
        Map<String, Object> body = new HashMap<>();

        ApiResponse<Map<String, Object>> resp = controller.createRule("F006", body);

        assertFalse(resp.getSuccess());
        assertEquals(400, resp.getCode());
        assertNotNull(resp.getActionHint());
    }

    @Test
    @DisplayName("createRule percentage 越界 (>100) 返 400")
    void createRule_percentageOutOfRange_returns400() {
        Map<String, Object> body = new HashMap<>();
        body.put("percentage", 150);

        ApiResponse<Map<String, Object>> resp = controller.createRule("F006", body);

        assertFalse(resp.getSuccess());
        assertEquals(400, resp.getCode());
        assertTrue(resp.getMessage().contains("percentage"));
    }

    @Test
    @DisplayName("createRule flat percentage 成功")
    void createRule_flatPercentage_succeeds() {
        when(commissionRuleRepo.saveAndFlush(any(CommissionRule.class))).thenAnswer(inv -> {
            CommissionRule r = inv.getArgument(0);
            if (r.getId() == null) r.setId("new-id");
            if (r.getVersion() == null) r.setVersion(0L);
            return r;
        });

        Map<String, Object> body = new HashMap<>();
        body.put("percentage", 5.5);
        body.put("effectiveFrom", "2026-06-01");
        body.put("periodType", "MONTHLY");

        ApiResponse<Map<String, Object>> resp = controller.createRule("F006", body);

        assertTrue(resp.getSuccess());
        assertEquals("new-id", resp.getData().get("id"));
    }

    @Test
    @DisplayName("createRule tier ladder 成功")
    void createRule_tierLadder_succeeds() {
        when(commissionRuleRepo.saveAndFlush(any(CommissionRule.class))).thenAnswer(inv -> {
            CommissionRule r = inv.getArgument(0);
            if (r.getId() == null) r.setId("new-id");
            if (r.getVersion() == null) r.setVersion(0L);
            return r;
        });

        List<Map<String, Object>> tiers = new ArrayList<>();
        tiers.add(Map.of("minAmount", 0, "maxAmount", 100000, "rate", 5.0));
        tiers.add(Map.of("minAmount", 100000, "maxAmount", 500000, "rate", 7.0));

        Map<String, Object> body = new HashMap<>();
        body.put("tierConfig", tiers);
        body.put("effectiveFrom", "2026-06-01");

        ApiResponse<Map<String, Object>> resp = controller.createRule("F006", body);

        assertTrue(resp.getSuccess());
        assertNotNull(resp.getData().get("tierConfig"));
    }

    @Test
    @DisplayName("createRule tier 区间重叠返 400")
    void createRule_tierOverlap_returns400() {
        List<Map<String, Object>> tiers = new ArrayList<>();
        tiers.add(Map.of("minAmount", 0, "maxAmount", 200000, "rate", 5.0));
        tiers.add(Map.of("minAmount", 100000, "maxAmount", 500000, "rate", 7.0));

        Map<String, Object> body = new HashMap<>();
        body.put("tierConfig", tiers);

        ApiResponse<Map<String, Object>> resp = controller.createRule("F006", body);

        assertFalse(resp.getSuccess());
        assertEquals(400, resp.getCode());
        assertTrue(resp.getMessage().contains("tierConfig"));
    }

    @Test
    @DisplayName("createRule 非法 periodType 返 400")
    void createRule_invalidPeriod_returns400() {
        Map<String, Object> body = new HashMap<>();
        body.put("percentage", 5);
        body.put("periodType", "WEEKLY");

        ApiResponse<Map<String, Object>> resp = controller.createRule("F006", body);

        assertFalse(resp.getSuccess());
        assertEquals(400, resp.getCode());
        assertTrue(resp.getMessage().contains("periodType"));
    }

    // ==================== Update ====================

    @Test
    @DisplayName("updateRule 缺 version 返 400 VERSION_MISSING")
    void updateRule_missingVersion_returns400() {
        CommissionRule r = flatRule("r1", "F006", new BigDecimal("5"), 2L);
        when(commissionRuleRepo.findByIdAndFactoryIdAndDeletedAtIsNull("r1", "F006"))
                .thenReturn(Optional.of(r));

        ApiResponse<Map<String, Object>> resp =
                controller.updateRule("F006", "r1", new HashMap<>());

        assertFalse(resp.getSuccess());
        assertEquals(400, resp.getCode());
        assertEquals("VERSION_MISSING", resp.getErrorCode());
    }

    @Test
    @DisplayName("updateRule version conflict 返 409 VERSION_CONFLICT")
    void updateRule_versionConflict_returns409() {
        CommissionRule r = flatRule("r1", "F006", new BigDecimal("5"), 5L);
        when(commissionRuleRepo.findByIdAndFactoryIdAndDeletedAtIsNull("r1", "F006"))
                .thenReturn(Optional.of(r));

        Map<String, Object> body = new HashMap<>();
        body.put("version", 2);
        body.put("percentage", 7);

        ApiResponse<Map<String, Object>> resp = controller.updateRule("F006", "r1", body);

        assertFalse(resp.getSuccess());
        assertEquals(409, resp.getCode());
        assertEquals("VERSION_CONFLICT", resp.getErrorCode());
    }

    @Test
    @DisplayName("updateRule 成功更新")
    void updateRule_succeeds() {
        CommissionRule r = flatRule("r1", "F006", new BigDecimal("5"), 0L);
        when(commissionRuleRepo.findByIdAndFactoryIdAndDeletedAtIsNull("r1", "F006"))
                .thenReturn(Optional.of(r));
        when(commissionRuleRepo.saveAndFlush(any(CommissionRule.class))).thenAnswer(inv -> {
            CommissionRule s = inv.getArgument(0);
            s.setVersion(1L);
            return s;
        });

        Map<String, Object> body = new HashMap<>();
        body.put("version", 0);
        body.put("percentage", 8.5);

        ApiResponse<Map<String, Object>> resp = controller.updateRule("F006", "r1", body);

        assertTrue(resp.getSuccess());
    }

    // ==================== Preview Commission ====================

    @Test
    @DisplayName("previewCommission flat mode 计算正确")
    void preview_flatMode_correct() {
        CommissionRule r = flatRule("r1", "F006", new BigDecimal("10"), 0L);
        when(commissionRuleRepo.findByIdAndFactoryIdAndDeletedAtIsNull("r1", "F006"))
                .thenReturn(Optional.of(r));

        Map<String, Object> body = new HashMap<>();
        body.put("orderAmount", 50000);

        ApiResponse<Map<String, Object>> resp = controller.previewCommission("F006", "r1", body);

        assertTrue(resp.getSuccess());
        assertEquals("FLAT", resp.getData().get("mode"));
        // 50000 * 10% = 5000.00
        BigDecimal commission = (BigDecimal) resp.getData().get("commission");
        assertEquals(0, new BigDecimal("5000.00").compareTo(commission));
    }

    @Test
    @DisplayName("previewCommission tier mode 计算正确")
    void preview_tierMode_correct() {
        CommissionRule r = tieredRule("r2", "F006", 0L);
        when(commissionRuleRepo.findByIdAndFactoryIdAndDeletedAtIsNull("r2", "F006"))
                .thenReturn(Optional.of(r));

        Map<String, Object> body = new HashMap<>();
        body.put("orderAmount", 300000);  // 落在 100k-500k → 7%

        ApiResponse<Map<String, Object>> resp = controller.previewCommission("F006", "r2", body);

        assertTrue(resp.getSuccess());
        assertEquals("TIER", resp.getData().get("mode"));
        // 300000 * 7% = 21000.00
        BigDecimal commission = (BigDecimal) resp.getData().get("commission");
        assertEquals(0, new BigDecimal("21000.00").compareTo(commission));
    }

    @Test
    @DisplayName("previewCommission negative orderAmount 返 400")
    void preview_negativeAmount_returns400() {
        CommissionRule r = flatRule("r1", "F006", new BigDecimal("5"), 0L);
        when(commissionRuleRepo.findByIdAndFactoryIdAndDeletedAtIsNull("r1", "F006"))
                .thenReturn(Optional.of(r));

        Map<String, Object> body = new HashMap<>();
        body.put("orderAmount", -100);

        ApiResponse<Map<String, Object>> resp = controller.previewCommission("F006", "r1", body);

        assertFalse(resp.getSuccess());
        assertEquals(400, resp.getCode());
    }

    // ==================== Delete ====================

    @Test
    @DisplayName("deleteRule cross-factory 返 404")
    void deleteRule_crossFactory_returns404() {
        when(commissionRuleRepo.findByIdAndFactoryIdAndDeletedAtIsNull("rx", "F006"))
                .thenReturn(Optional.empty());

        ApiResponse<Map<String, Object>> resp = controller.deleteRule("F006", "rx");

        assertFalse(resp.getSuccess());
        assertEquals(404, resp.getCode());
    }
}
