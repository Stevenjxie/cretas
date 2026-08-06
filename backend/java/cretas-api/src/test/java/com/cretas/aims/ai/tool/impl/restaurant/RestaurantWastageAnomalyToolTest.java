package com.cretas.aims.ai.tool.impl.restaurant;

import com.cretas.aims.service.finding.Finding;
import com.cretas.aims.service.finding.FindingService;
import com.cretas.aims.service.finding.FindingTextRenderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link RestaurantWastageAnomalyTool} 的发现层接入。 */
@ExtendWith(MockitoExtension.class)
class RestaurantWastageAnomalyToolTest {

    private static final String FACTORY_ID = "MOCK_REST";

    @InjectMocks
    private RestaurantWastageAnomalyTool tool;

    @Mock
    private FindingService findingService;

    @Mock
    private FindingTextRenderer findingTextRenderer;

    @SuppressWarnings("unchecked")
    private Map<String, Object> execute() throws Exception {
        Method m = RestaurantWastageAnomalyTool.class.getDeclaredMethod(
                "doExecute", String.class, Map.class, Map.class);
        m.setAccessible(true);
        return (Map<String, Object>) m.invoke(tool, FACTORY_ID, Map.of(), Map.of());
    }

    private static FindingService.Result result(List<Finding> findings, List<String> checked,
                                                List<String> failed,
                                                List<FindingService.SkippedRule> skipped) {
        return new FindingService.Result(findings, checked, findings.size(), Map.of(),
                failed, skipped);
    }

    @Test
    @DisplayName("UT-RWA-01: 🔴 不再持有 MaterialBatchRepository —— 那张表对餐饮租户恒 0 行")
    void noLongerReadsMainDbMaterialBatches() {
        for (Field f : RestaurantWastageAnomalyTool.class.getDeclaredFields()) {
            assertFalse(f.getType().getSimpleName().contains("MaterialBatch"),
                    "主库 material_batches 对 MOCK_REST 是 0 行, 读它只会产出假的全清信号: "
                            + f.getName());
        }
    }

    @Test
    @DisplayName("UT-RWA-02: 用 restaurant 这个 domain 调发现层")
    void usesRestaurantDomain() throws Exception {
        when(findingService.detectInline(FACTORY_ID, "restaurant"))
                .thenReturn(result(List.of(), List.of("损耗类型集中度"), List.of(), List.of()));
        when(findingTextRenderer.renderInline(any())).thenReturn("✅ 已检查 损耗类型集中度，均正常。");

        execute();

        verify(findingService).detectInline(FACTORY_ID, "restaurant");
    }

    @Test
    @DisplayName("UT-RWA-03: 🔴 全部规则失败时不得说「良好」「正常」「建设中」")
    void failureNeverRendersAsHealthy() throws Exception {
        when(findingService.detectInline(FACTORY_ID, "restaurant"))
                .thenReturn(result(List.of(), List.of(), List.of("损耗类型集中度"), List.of()));
        when(findingTextRenderer.renderInline(any())).thenReturn("");

        String message = (String) execute().get("message");

        assertFalse(message.contains("良好"), message);
        assertFalse(message.contains("正常"), message);
        assertFalse(message.contains("建设中"), message);
        assertTrue(message.contains("失败"), message);
        assertTrue(message.contains("损耗类型集中度"), message);
    }

    @Test
    @DisplayName("UT-RWA-04: message 带上 findingsText，并暴露三个桶")
    void exposesAllThreeBuckets() throws Exception {
        FindingService.Result r = result(
                List.of(), List.of("损耗类型集中度"), List.of(),
                List.of(new FindingService.SkippedRule("食材损耗离群", "基线历史不足")));
        when(findingService.detectInline(FACTORY_ID, "restaurant")).thenReturn(r);
        when(findingTextRenderer.renderInline(any()))
                .thenReturn("✅ 已检查 损耗类型集中度，均正常。\nℹ️ 食材损耗离群：基线历史不足，暂不判断。");

        Map<String, Object> out = execute();

        assertEquals("✅ 已检查 损耗类型集中度，均正常。\nℹ️ 食材损耗离群：基线历史不足，暂不判断。",
                out.get("message"));
        assertEquals(List.of("损耗类型集中度"), out.get("checkedRules"));
        assertEquals(1, ((List<?>) out.get("skippedRules")).size());
        assertTrue(((List<?>) out.get("failedRules")).isEmpty());
        assertEquals(true, out.get("complete"));
    }
}
