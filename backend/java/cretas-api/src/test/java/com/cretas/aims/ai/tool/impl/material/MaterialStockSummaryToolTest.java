package com.cretas.aims.ai.tool.impl.material;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.finding.Finding;
import com.cretas.aims.service.finding.FindingService;
import com.cretas.aims.service.finding.FindingTextRenderer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Unit tests for {@link MaterialStockSummaryTool} 的 Finding 接入。 */
@ExtendWith(MockitoExtension.class)
class MaterialStockSummaryToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private MaterialStockSummaryTool tool;

    @Mock
    private MaterialBatchService materialBatchService;

    @Mock
    private FindingService findingService;

    @Mock
    private FindingTextRenderer findingTextRenderer;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        PageResponse<MaterialBatchDTO> page = mock(PageResponse.class);
        lenient().when(page.getContent()).thenReturn(List.of());
        lenient().when(page.getTotalElements()).thenReturn(42L);
        lenient().when(materialBatchService.getMaterialBatchList(anyString(), any())).thenReturn(page);
    }

    private static Finding lowStock(String name) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("currentStock", new BigDecimal("12"));
        facts.put("safetyStock", new BigDecimal("50"));
        facts.put("gap", new BigDecimal("38"));
        facts.put("unit", "kg");
        facts.put("stockRatio", 24L);
        return new Finding("LOW_STOCK", "inventory", Finding.Severity.WARNING, 50,
                "M-" + name, name, facts);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> execute() throws Exception {
        Method m = MaterialStockSummaryTool.class.getDeclaredMethod(
                "doExecute", String.class, Map.class, Map.class);
        m.setAccessible(true);
        return (Map<String, Object>) m.invoke(tool, FACTORY_ID, Map.of(), Map.of());
    }

    @Test
    @DisplayName("UT-MSS-01: 保留既有 key —— totalBatches / lowStockCount / batches / message")
    void keepsExistingKeys() throws Exception {
        when(findingService.detectInline(FACTORY_ID, "inventory")).thenReturn(
                new FindingService.Result(List.of(), List.of("低库存"), 0, Map.of()));
        when(findingTextRenderer.renderInline(any())).thenReturn("✅ 已检查 低库存，均正常。");

        Map<String, Object> result = execute();

        assertEquals(42L, ((Number) result.get("totalBatches")).longValue());
        assertNotNull(result.get("lowStockCount"));
        assertNotNull(result.get("batches"));
        assertNotNull(result.get("message"));
    }

    @Test
    @DisplayName("UT-MSS-02: 新增 findings / findingsText 两个 key")
    @SuppressWarnings("unchecked")
    void addsFindingKeys() throws Exception {
        when(findingService.detectInline(FACTORY_ID, "inventory")).thenReturn(
                new FindingService.Result(List.of(lowStock("鲈鱼")), List.of("低库存"), 1,
                        Map.of("LOW_STOCK", 1)));
        when(findingTextRenderer.renderInline(any())).thenReturn("⚠️ 顺带 1 件事：\n · 鲈鱼 ...");

        Map<String, Object> result = execute();

        List<Finding> findings = (List<Finding>) result.get("findings");
        assertEquals(1, findings.size());
        assertEquals("鲈鱼", findings.get(0).subjectName());
        assertTrue(((String) result.get("findingsText")).contains("鲈鱼"));
    }

    @Test
    @DisplayName("UT-MSS-03: lowStockCount 取自 countsByCode 的截断前计数")
    void lowStockCountComesFromCountsByCode() throws Exception {
        when(findingService.detectInline(FACTORY_ID, "inventory")).thenReturn(
                new FindingService.Result(List.of(lowStock("A"), lowStock("B")),
                        List.of("低库存"), 7, Map.of("LOW_STOCK", 7)));
        when(findingTextRenderer.renderInline(any())).thenReturn("...");

        Map<String, Object> result = execute();

        assertEquals(7, ((Number) result.get("lowStockCount")).intValue(),
                "应取截断前的 7，不是 findings 列表长度 2");
    }

    @Test
    @DisplayName("UT-MSS-04: countsByCode 无 LOW_STOCK 时 lowStockCount 为 0")
    void lowStockCountDefaultsToZero() throws Exception {
        when(findingService.detectInline(FACTORY_ID, "inventory")).thenReturn(
                new FindingService.Result(List.of(), List.of("低库存"), 0, Map.of()));
        when(findingTextRenderer.renderInline(any())).thenReturn("✅ 已检查 低库存，均正常。");

        Map<String, Object> result = execute();

        assertEquals(0, ((Number) result.get("lowStockCount")).intValue());
    }

    @Test
    @DisplayName("UT-MSS-05: 🔴 Tool 不得自己再调 getLowStockWarnings —— 否则变成 2 次查询")
    void doesNotQueryLowStockWarningsDirectly() throws Exception {
        when(findingService.detectInline(FACTORY_ID, "inventory")).thenReturn(
                new FindingService.Result(List.of(), List.of("低库存"), 0, Map.of()));
        when(findingTextRenderer.renderInline(any())).thenReturn("...");

        execute();

        verify(materialBatchService, never()).getLowStockWarnings(anyString());
    }

    @Test
    @DisplayName("UT-MSS-06: message 里带上 findingsText")
    void messageIncludesFindingsText() throws Exception {
        when(findingService.detectInline(FACTORY_ID, "inventory")).thenReturn(
                new FindingService.Result(List.of(lowStock("鲈鱼")), List.of("低库存"), 1,
                        Map.of("LOW_STOCK", 1)));
        when(findingTextRenderer.renderInline(any())).thenReturn("⚠️ 顺带 1 件事：\n · 鲈鱼 剩 12kg");

        String message = (String) execute().get("message");

        assertTrue(message.contains("库存汇总"), message);
        assertTrue(message.contains("鲈鱼"), message);
    }

    @Test
    @DisplayName("UT-MSS-07: findingsText 为空串时不往 message 里拼空行")
    void emptyFindingsTextDoesNotPolluteMessage() throws Exception {
        when(findingService.detectInline(FACTORY_ID, "inventory")).thenReturn(
                new FindingService.Result(List.of(), List.of(), 0, Map.of()));
        when(findingTextRenderer.renderInline(any())).thenReturn("");

        String message = (String) execute().get("message");

        assertFalse(message.endsWith("\n"), "空 findingsText 不应留下尾随换行: [" + message + "]");
    }

    @Test
    @DisplayName("UT-MSS-08: 用 inventory 这个 domain 调用发现层")
    void usesInventoryDomain() throws Exception {
        when(findingService.detectInline(anyString(), anyString())).thenReturn(
                new FindingService.Result(List.of(), List.of("低库存"), 0, Map.of()));
        when(findingTextRenderer.renderInline(any())).thenReturn("...");

        execute();

        verify(findingService).detectInline(FACTORY_ID, "inventory");
    }
}
