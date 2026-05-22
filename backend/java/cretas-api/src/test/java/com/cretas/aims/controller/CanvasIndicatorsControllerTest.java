package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.indicator.Indicator;
import com.cretas.aims.repository.indicator.IndicatorComputationRepository;
import com.cretas.aims.repository.indicator.IndicatorRepository;
import com.cretas.aims.repository.indicator.IndicatorThresholdRepository;
import com.cretas.aims.repository.indicator.IndicatorTreeNodeRepository;
import com.cretas.aims.repository.indicator.IndicatorVersionRepository;
import com.cretas.aims.repository.lineage.BatchLineageClosureRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.service.workflow.SandboxedSpelEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Smoke tests for {@link CanvasIndicatorsController}.
 *
 * <p>Coverage focus (Canvas Phase A subagent #3):
 * <ul>
 *   <li>List + factory-scoped filter</li>
 *   <li>Detail merges thresholds + computations + latest version</li>
 *   <li>Create write-time validation (code/name/category)</li>
 *   <li>Cross-factory 404 (security)</li>
 *   <li>Test-evaluate sandbox path (success + RCE rejection)</li>
 * </ul>
 *
 * <p>Uses real {@link SandboxedSpelEvaluator} — the sandbox itself has
 * dedicated tests; here we verify Controller wires it correctly.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CanvasIndicatorsController smoke tests")
class CanvasIndicatorsControllerTest {

    @Mock IndicatorRepository indicatorRepository;
    @Mock IndicatorVersionRepository versionRepository;
    @Mock IndicatorThresholdRepository thresholdRepository;
    @Mock IndicatorComputationRepository computationRepository;
    @Mock IndicatorTreeNodeRepository treeNodeRepository;
    @Mock BatchLineageEdgeRepository edgeRepository;
    @Mock BatchLineageClosureRepository closureRepository;

    // Real evaluator — sandbox behavior verified by SandboxedSpelEvaluatorTest.
    private final SandboxedSpelEvaluator spelEvaluator = new SandboxedSpelEvaluator();

    private CanvasIndicatorsController controller;

    @BeforeEach
    void setUp() {
        controller = new CanvasIndicatorsController(
                indicatorRepository, versionRepository, thresholdRepository,
                computationRepository, treeNodeRepository, edgeRepository,
                closureRepository, spelEvaluator);
    }

    private Indicator sampleIndicator() {
        Indicator i = new Indicator();
        i.setId("ind-test-1");
        i.setFactoryId("F006");
        i.setCode("TEST_YIELD");
        i.setName("测试良品率");
        i.setCategory("FACTORY");
        i.setUnit("%");
        i.setIsActive(true);
        i.setComputeStrategy("CACHED");
        i.setCacheTtlSeconds(300);
        i.setLastValue(new BigDecimal("95.5000"));
        i.setConfig(new HashMap<>());
        return i;
    }

    // ==================== list ====================

    @Test
    @DisplayName("list 无 category 时返回工厂全部活跃指标")
    void listIndicators_noCategory() {
        when(indicatorRepository
                .findByFactoryIdAndIsActiveTrueOrderByDisplayOrderAscNameAsc("F006"))
                .thenReturn(List.of(sampleIndicator()));

        ApiResponse<List<Map<String, Object>>> resp = controller.listIndicators("F006", null);

        assertTrue(resp.getSuccess());
        assertEquals(1, resp.getData().size());
        assertEquals("TEST_YIELD", resp.getData().get(0).get("code"));
    }

    @Test
    @DisplayName("list 带 category 时走 category 分支")
    void listIndicators_withCategory() {
        when(indicatorRepository
                .findByFactoryIdAndCategoryAndIsActiveTrueOrderByDisplayOrderAscNameAsc("F006", "FACTORY"))
                .thenReturn(List.of(sampleIndicator()));

        ApiResponse<List<Map<String, Object>>> resp = controller.listIndicators("F006", "factory");

        assertTrue(resp.getSuccess());
        assertEquals(1, resp.getData().size());
    }

    // ==================== detail ====================

    @Test
    @DisplayName("getIndicator 跨工厂访问返 404")
    void getIndicator_crossFactory404() {
        Indicator i = sampleIndicator();
        i.setFactoryId("F999"); // different factory
        when(indicatorRepository.findById("ind-test-1")).thenReturn(Optional.of(i));

        ApiResponse<Map<String, Object>> resp = controller.getIndicator("F006", "ind-test-1");

        assertFalse(resp.getSuccess());
        assertEquals(404, resp.getCode());
    }

    @Test
    @DisplayName("getIndicator 返回 thresholds + computations + latest version")
    void getIndicator_mergesRelated() {
        when(indicatorRepository.findById("ind-test-1")).thenReturn(Optional.of(sampleIndicator()));
        when(thresholdRepository.findByIndicatorIdAndIsActiveTrue("ind-test-1"))
                .thenReturn(Collections.emptyList());
        when(computationRepository
                .findByIndicatorIdAndIsActiveTrueOrderByPriorityAsc("ind-test-1"))
                .thenReturn(Collections.emptyList());
        when(versionRepository.findFirstByIndicatorIdOrderByComputedAtDesc("ind-test-1"))
                .thenReturn(Optional.empty());

        ApiResponse<Map<String, Object>> resp = controller.getIndicator("F006", "ind-test-1");

        assertTrue(resp.getSuccess());
        assertNotNull(resp.getData().get("thresholds"));
        assertNotNull(resp.getData().get("computations"));
    }

    // ==================== create ====================

    @Test
    @DisplayName("create 缺 code 返 400")
    void createIndicator_missingCode() {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "测试");
        body.put("category", "FACTORY");

        ApiResponse<Map<String, Object>> resp = controller.createIndicator("F006", body);

        assertFalse(resp.getSuccess());
        assertEquals(400, resp.getCode());
        assertTrue(resp.getMessage().contains("code"));
    }

    @Test
    @DisplayName("create 非法 category 返 400 且含允许值")
    void createIndicator_invalidCategory() {
        Map<String, Object> body = new HashMap<>();
        body.put("code", "X");
        body.put("name", "Y");
        body.put("category", "BOGUS_CAT");

        ApiResponse<Map<String, Object>> resp = controller.createIndicator("F006", body);

        assertFalse(resp.getSuccess());
        assertEquals(400, resp.getCode());
        assertTrue(resp.getActionHint() != null && resp.getActionHint().contains("RESTAURANT"));
    }

    @Test
    @DisplayName("create code 重复返 409")
    void createIndicator_duplicateCode() {
        Map<String, Object> body = new HashMap<>();
        body.put("code", "TEST_YIELD");
        body.put("name", "Y");
        body.put("category", "FACTORY");
        when(indicatorRepository.findByFactoryIdAndCode("F006", "TEST_YIELD"))
                .thenReturn(Optional.of(sampleIndicator()));

        ApiResponse<Map<String, Object>> resp = controller.createIndicator("F006", body);

        assertFalse(resp.getSuccess());
        assertEquals(409, resp.getCode());
    }

    // ==================== test-evaluate ====================

    @Test
    @DisplayName("test-evaluate 拒绝 SpEL RCE (T(java.lang.Runtime)...)")
    void testEvaluate_rejectsRce() {
        when(indicatorRepository.findById("ind-test-1")).thenReturn(Optional.of(sampleIndicator()));
        Map<String, Object> body = new HashMap<>();
        body.put("formula", "T(java.lang.Runtime).getRuntime().exec('rm')");
        body.put("variables", new HashMap<>());

        ApiResponse<Map<String, Object>> resp = controller.testEvaluate("F006", "ind-test-1", body);

        assertFalse(resp.getSuccess());
        assertEquals(400, resp.getCode());
    }

    @Test
    @DisplayName("test-evaluate 合法公式返 OK 与结果")
    void testEvaluate_validFormula() {
        when(indicatorRepository.findById("ind-test-1")).thenReturn(Optional.of(sampleIndicator()));
        Map<String, Object> body = new HashMap<>();
        body.put("formula", "#yield * 100 / #total");
        body.put("variables", Map.of("yield", 950, "total", 1000));

        ApiResponse<Map<String, Object>> resp = controller.testEvaluate("F006", "ind-test-1", body);

        assertTrue(resp.getSuccess());
        assertEquals("OK", resp.getData().get("status"));
        assertNotNull(resp.getData().get("result"));
    }

    @Test
    @DisplayName("test-evaluate 空 formula 返 400")
    void testEvaluate_missingFormula() {
        when(indicatorRepository.findById("ind-test-1")).thenReturn(Optional.of(sampleIndicator()));
        Map<String, Object> body = new HashMap<>();

        ApiResponse<Map<String, Object>> resp = controller.testEvaluate("F006", "ind-test-1", body);

        assertFalse(resp.getSuccess());
        assertEquals(400, resp.getCode());
    }
}
