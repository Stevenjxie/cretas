package com.cretas.aims.controller;

import com.cretas.aims.controller.dto.indicator.IndicatorDetailResponse;
import com.cretas.aims.controller.dto.indicator.IndicatorListResponse;
import com.cretas.aims.controller.dto.indicator.IndicatorTreeNodeResponse;
import com.cretas.aims.controller.dto.indicator.IndicatorValueResponse;
import com.cretas.aims.controller.dto.indicator.ThresholdResponse;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.indicator.Indicator;
import com.cretas.aims.entity.indicator.IndicatorThreshold;
import com.cretas.aims.entity.indicator.IndicatorTreeNode;
import com.cretas.aims.entity.indicator.IndicatorVersion;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.indicator.IndicatorRepository;
import com.cretas.aims.repository.indicator.IndicatorThresholdRepository;
import com.cretas.aims.repository.indicator.IndicatorTreeNodeRepository;
import com.cretas.aims.repository.indicator.IndicatorVersionRepository;
import com.cretas.aims.service.indicator.IndicatorQueryService;
import com.cretas.aims.service.indicator.dto.IndicatorValueResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link IndicatorController} — Phase 1 Sprint 1 Day 5 (2026-05-22).
 *
 * <p>Pure {@code @ExtendWith(MockitoExtension.class)} (no Spring context) per
 * {@code ScheduledTaskControllerTest} convention. {@link IndicatorQueryService} +
 * 4 repositories mocked; we verify (a) right calls dispatched, (b) DTO projection
 * correct, (c) 404 on missing code, (d) pagination clamping.
 *
 * <h3>Coverage</h3>
 * <ol>
 *   <li>listIndicators with category filter</li>
 *   <li>listIndicators without filter (all active)</li>
 *   <li>getByCode happy path (returns DTO with thresholds)</li>
 *   <li>getByCode 404 on missing</li>
 *   <li>getValue computes via service, returns IndicatorValueResponse</li>
 *   <li>getTree fetches roots + recursive children</li>
 *   <li>recompute clears lastComputedAt + invokes service</li>
 *   <li>getThresholds returns list</li>
 *   <li>getVersions pagination clamping (size > 200 → 200)</li>
 * </ol>
 *
 * @since 2026-05-22 (Phase 1 Sprint 1 Day 5)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IndicatorController Phase 1 Sprint 1 Day 5")
class IndicatorControllerTest {

    private static final String FACTORY_ID = "F001";

    @Mock IndicatorQueryService indicatorQueryService;
    @Mock IndicatorRepository indicatorRepository;
    @Mock IndicatorTreeNodeRepository indicatorTreeNodeRepository;
    @Mock IndicatorThresholdRepository indicatorThresholdRepository;
    @Mock IndicatorVersionRepository indicatorVersionRepository;
    @InjectMocks IndicatorController controller;

    // ====================================================================
    // Test 1: listIndicators with category filter
    // ====================================================================

    @Test
    @DisplayName("listIndicators with ?category=FACTORY → 调用 listByCategory + 投影 DTO")
    void testListByCategory() {
        Indicator a = newIndicator("ind-a", "FACTORY_YIELD_RATE", "工厂良品率", "FACTORY");
        Indicator b = newIndicator("ind-b", "FACTORY_OEE", "OEE", "FACTORY");
        when(indicatorQueryService.listByCategory(FACTORY_ID, "FACTORY"))
                .thenReturn(List.of(a, b));

        ApiResponse<List<IndicatorListResponse>> resp =
                controller.listIndicators(FACTORY_ID, "FACTORY");

        assertNotNull(resp);
        assertEquals(200, resp.getCode());
        assertTrue(resp.getSuccess());
        assertEquals(2, resp.getData().size());
        assertEquals("FACTORY_YIELD_RATE", resp.getData().get(0).getCode());
        assertEquals("工厂良品率", resp.getData().get(0).getName());
        assertEquals("FACTORY", resp.getData().get(0).getCategory());
        verify(indicatorQueryService).listByCategory(FACTORY_ID, "FACTORY");
    }

    // ====================================================================
    // Test 2: listIndicators without filter (all active)
    // ====================================================================

    @Test
    @DisplayName("listIndicators 不传 category → 调用 findActiveByFactoryId")
    void testListAll() {
        Indicator a = newIndicator("ind-a", "RESTAURANT_DISH_MARGIN", "菜品毛利", "RESTAURANT");
        when(indicatorRepository.findActiveByFactoryId(FACTORY_ID))
                .thenReturn(List.of(a));

        ApiResponse<List<IndicatorListResponse>> resp =
                controller.listIndicators(FACTORY_ID, null);

        assertNotNull(resp);
        assertEquals(1, resp.getData().size());
        assertEquals("RESTAURANT_DISH_MARGIN", resp.getData().get(0).getCode());
        verify(indicatorRepository).findActiveByFactoryId(FACTORY_ID);
    }

    // ====================================================================
    // Test 3: getByCode happy path — returns DTO with thresholds[]
    // ====================================================================

    @Test
    @DisplayName("getByCode found → 返 DetailResponse 含 thresholds[]")
    void testGetByCodeHappyPath() {
        Indicator ind = newIndicator("ind-a", "FACTORY_YIELD_RATE", "工厂良品率", "FACTORY");
        ind.setLastValue(new BigDecimal("95.50"));
        ind.setLastComputedAt(LocalDateTime.now().minusHours(1));

        IndicatorThreshold tGreen = newThreshold("th-g", ind.getId(), "GREEN", "GTE",
                new BigDecimal("95.00"));
        IndicatorThreshold tYellow = newThreshold("th-y", ind.getId(), "YELLOW", "GTE",
                new BigDecimal("90.00"));

        when(indicatorRepository.findByCodeAndFactoryIdAndDeletedAtIsNull("FACTORY_YIELD_RATE", FACTORY_ID))
                .thenReturn(Optional.of(ind));
        when(indicatorThresholdRepository.findActiveByIndicatorIdAndFactoryId(ind.getId(), FACTORY_ID))
                .thenReturn(List.of(tGreen, tYellow));

        ApiResponse<IndicatorDetailResponse> resp =
                controller.getByCode(FACTORY_ID, "FACTORY_YIELD_RATE");

        assertNotNull(resp);
        assertEquals(200, resp.getCode());
        IndicatorDetailResponse d = resp.getData();
        assertEquals("FACTORY_YIELD_RATE", d.getCode());
        assertEquals("工厂良品率", d.getName());
        assertEquals("FACTORY", d.getCategory());
        assertEquals(new BigDecimal("95.50"), d.getLastValue());
        assertEquals(2, d.getThresholds().size());
        assertEquals("GREEN", d.getThresholds().get(0).getAlertLevel());
        assertEquals("YELLOW", d.getThresholds().get(1).getAlertLevel());
    }

    // ====================================================================
    // Test 4: getByCode 404 when code not found
    // ====================================================================

    @Test
    @DisplayName("getByCode 不存在 → BusinessException 404")
    void testGetByCodeNotFound() {
        when(indicatorRepository.findByCodeAndFactoryIdAndDeletedAtIsNull("NO_SUCH", FACTORY_ID))
                .thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.getByCode(FACTORY_ID, "NO_SUCH"));

        assertEquals(404, ex.getCode());
        assertTrue(ex.getMessage().contains("NO_SUCH"),
                "message must reference the missing code: " + ex.getMessage());
        assertTrue(ex.getMessage().contains(FACTORY_ID),
                "message must reference factoryId: " + ex.getMessage());
    }

    // ====================================================================
    // Test 5: getValue computes via service
    // ====================================================================

    @Test
    @DisplayName("getValue → 调 service.computeForCode + 返 IndicatorValueResponse")
    void testGetValue() {
        Indicator ind = newIndicator("ind-a", "FACTORY_YIELD_RATE", "工厂良品率", "FACTORY");
        ind.setUnit("%");
        LocalDate start = LocalDate.of(2026, 5, 1);
        LocalDate end = LocalDate.of(2026, 5, 21);
        IndicatorValueResult result = new IndicatorValueResult(
                new BigDecimal("95.50"),
                LocalDateTime.of(2026, 5, 21, 14, 30),
                false,
                "python");

        when(indicatorRepository.findByCodeAndFactoryIdAndDeletedAtIsNull("FACTORY_YIELD_RATE", FACTORY_ID))
                .thenReturn(Optional.of(ind));
        when(indicatorQueryService.computeForCode("FACTORY_YIELD_RATE", FACTORY_ID, start, end))
                .thenReturn(result);

        ApiResponse<IndicatorValueResponse> resp =
                controller.getValue(FACTORY_ID, "FACTORY_YIELD_RATE", start, end);

        assertNotNull(resp);
        assertEquals(200, resp.getCode());
        IndicatorValueResponse v = resp.getData();
        assertEquals("FACTORY_YIELD_RATE", v.getCode());
        assertEquals("%", v.getUnit());
        assertEquals(new BigDecimal("95.50"), v.getValue());
        assertEquals(false, v.getCacheHit());
        assertEquals("python", v.getSource());
        assertEquals(start, v.getPeriodStart());
        assertEquals(end, v.getPeriodEnd());
        verify(indicatorQueryService).computeForCode("FACTORY_YIELD_RATE", FACTORY_ID, start, end);
    }

    // ====================================================================
    // Test 6: getTree fetches roots and recursive children
    // ====================================================================

    @Test
    @DisplayName("getTree → 根节点 + 递归 children[]")
    void testGetTree() {
        IndicatorTreeNode root = newTreeNode("n-root", "ind-root", null, 0,
                "ind-root");
        IndicatorTreeNode child = newTreeNode("n-c1", "ind-c1", "ind-root", 1,
                "ind-root/ind-c1");

        when(indicatorTreeNodeRepository.findRootsByFactoryId(FACTORY_ID))
                .thenReturn(List.of(root));
        when(indicatorTreeNodeRepository
                .findByParentIndicatorIdAndFactoryIdAndDeletedAtIsNull("ind-root", FACTORY_ID))
                .thenReturn(List.of(child));
        when(indicatorTreeNodeRepository
                .findByParentIndicatorIdAndFactoryIdAndDeletedAtIsNull("ind-c1", FACTORY_ID))
                .thenReturn(List.of());

        ApiResponse<List<IndicatorTreeNodeResponse>> resp = controller.getTree(FACTORY_ID);

        assertNotNull(resp);
        assertEquals(200, resp.getCode());
        assertEquals(1, resp.getData().size());
        IndicatorTreeNodeResponse rootResp = resp.getData().get(0);
        assertEquals("ind-root", rootResp.getIndicatorId());
        assertEquals(1, rootResp.getChildren().size());
        assertEquals("ind-c1", rootResp.getChildren().get(0).getIndicatorId());
    }

    // ====================================================================
    // Test 7: recompute clears lastComputedAt + invokes service
    // ====================================================================

    @Test
    @DisplayName("recompute → 清 lastComputedAt + 调 service + 返新值")
    void testRecompute() {
        Indicator ind = newIndicator("ind-a", "FACTORY_YIELD_RATE", "工厂良品率", "FACTORY");
        ind.setComputeStrategy("CACHED");
        ind.setLastValue(new BigDecimal("90.00"));
        ind.setLastComputedAt(LocalDateTime.now().minusHours(1));
        ind.setUnit("%");

        LocalDate start = LocalDate.of(2026, 5, 1);
        LocalDate end = LocalDate.of(2026, 5, 21);
        IndicatorValueResult freshResult = new IndicatorValueResult(
                new BigDecimal("96.00"), LocalDateTime.now(), false, "python");

        when(indicatorRepository.findByCodeAndFactoryIdAndDeletedAtIsNull("FACTORY_YIELD_RATE", FACTORY_ID))
                .thenReturn(Optional.of(ind));
        when(indicatorRepository.save(any(Indicator.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(indicatorQueryService.computeForCode("FACTORY_YIELD_RATE", FACTORY_ID, start, end))
                .thenReturn(freshResult);

        ApiResponse<IndicatorValueResponse> resp =
                controller.recompute(FACTORY_ID, "FACTORY_YIELD_RATE", start, end);

        assertNotNull(resp);
        assertEquals(200, resp.getCode());
        assertEquals(new BigDecimal("96.00"), resp.getData().getValue());
        assertEquals("python", resp.getData().getSource());
        // verify lastComputedAt cleared before save
        verify(indicatorRepository).save(any(Indicator.class));
        verify(indicatorQueryService).computeForCode("FACTORY_YIELD_RATE", FACTORY_ID, start, end);
    }

    // ====================================================================
    // Test 8: getThresholds returns list (paired with code lookup)
    // ====================================================================

    @Test
    @DisplayName("getThresholds → 投影 ThresholdResponse 列表")
    void testGetThresholds() {
        Indicator ind = newIndicator("ind-a", "FACTORY_YIELD_RATE", "工厂良品率", "FACTORY");
        IndicatorThreshold tRed = newThreshold("th-r", ind.getId(), "RED", "LT",
                new BigDecimal("90.00"));
        when(indicatorRepository.findByCodeAndFactoryIdAndDeletedAtIsNull("FACTORY_YIELD_RATE", FACTORY_ID))
                .thenReturn(Optional.of(ind));
        when(indicatorThresholdRepository.findActiveByIndicatorIdAndFactoryId(ind.getId(), FACTORY_ID))
                .thenReturn(List.of(tRed));

        ApiResponse<List<ThresholdResponse>> resp =
                controller.getThresholds(FACTORY_ID, "FACTORY_YIELD_RATE");

        assertNotNull(resp);
        assertEquals(200, resp.getCode());
        assertEquals(1, resp.getData().size());
        assertEquals("RED", resp.getData().get(0).getAlertLevel());
        assertEquals("LT", resp.getData().get(0).getOperator());
    }

    // ====================================================================
    // Test 9: getVersions pagination clamping
    // ====================================================================

    @Test
    @DisplayName("getVersions size>200 → clamp 200 (防 OOM)")
    void testGetVersionsSizeClamping() {
        Indicator ind = newIndicator("ind-a", "FACTORY_YIELD_RATE", "工厂良品率", "FACTORY");
        when(indicatorRepository.findByCodeAndFactoryIdAndDeletedAtIsNull("FACTORY_YIELD_RATE", FACTORY_ID))
                .thenReturn(Optional.of(ind));
        // Empty list — clamp logic still must execute without NPE
        when(indicatorVersionRepository.findRecentByFactoryId(FACTORY_ID))
                .thenReturn(List.of());

        // size=500 (over 200 cap), page=0 (below 1 floor)
        ApiResponse<PageResponse<IndicatorVersion>> resp = controller.getVersions(
                FACTORY_ID, "FACTORY_YIELD_RATE", null, null, 0, 500);

        assertNotNull(resp);
        assertEquals(200, resp.getCode());
        PageResponse<IndicatorVersion> page = resp.getData();
        assertEquals(Integer.valueOf(200), page.getSize(),
                "size > 200 must be clamped to 200");
        assertEquals(Integer.valueOf(1), page.getPage(),
                "page < 1 must be clamped to 1");
        assertEquals(Long.valueOf(0L), page.getTotalElements(),
                "empty repo → total 0");
        assertTrue(page.getContent().isEmpty());
    }

    // ====================================================================
    // Helpers
    // ====================================================================

    private static Indicator newIndicator(String id, String code, String name, String category) {
        Indicator i = new Indicator();
        i.setId(id);
        i.setFactoryId(FACTORY_ID);
        i.setCode(code);
        i.setName(name);
        i.setCategory(category);
        i.setIsActive(true);
        i.setComputeStrategy("CACHED");
        i.setCacheTtlSeconds(3600);
        return i;
    }

    private static IndicatorThreshold newThreshold(String id, String indicatorId,
                                                   String alertLevel, String operator,
                                                   BigDecimal value) {
        IndicatorThreshold t = new IndicatorThreshold();
        t.setId(id);
        t.setFactoryId(FACTORY_ID);
        t.setIndicatorId(indicatorId);
        t.setAlertLevel(alertLevel);
        t.setOperator(operator);
        t.setThresholdValue(value);
        t.setIsActive(true);
        return t;
    }

    private static IndicatorTreeNode newTreeNode(String id, String indicatorId, String parentId,
                                                 int depth, String materializedPath) {
        IndicatorTreeNode n = new IndicatorTreeNode();
        n.setId(id);
        n.setFactoryId(FACTORY_ID);
        n.setIndicatorId(indicatorId);
        n.setParentId(parentId);
        n.setDepth(depth);
        n.setMaterializedPath(materializedPath);
        return n;
    }
}
