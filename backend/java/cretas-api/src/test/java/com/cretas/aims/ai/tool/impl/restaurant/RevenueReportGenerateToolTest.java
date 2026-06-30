package com.cretas.aims.ai.tool.impl.restaurant;

import com.cretas.aims.client.PythonSmartBIClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * POJO-style unit test for RevenueReportGenerateTool (no Spring boot context).
 * Mockito injects PythonSmartBIClient via reflection so we can verify the
 * exact request payload + response handling without a running Python service.
 */
class RevenueReportGenerateToolTest {

    private RevenueReportGenerateTool tool;
    private PythonSmartBIClient mockClient;

    @BeforeEach
    void setUp() throws Exception {
        tool = new RevenueReportGenerateTool();
        mockClient = mock(PythonSmartBIClient.class);
        Field field = RevenueReportGenerateTool.class.getDeclaredField("pythonClient");
        field.setAccessible(true);
        field.set(tool, mockClient);
    }

    private static Map<String, Object> context() {
        return Map.of("userRole", "restaurant_manager");
    }

    // ─── Static metadata ────────────────────────────────────────────────

    @Test
    void toolNameMatchesIntentConfig() {
        // Must match V20260513_02__revenue_report_intent.sql `tool_name`.
        assertEquals("revenue_report_generate", tool.getToolName());
    }

    @Test
    void descriptionMentionsKeyAffordances() {
        String desc = tool.getDescription();
        assertTrue(desc.contains("收入管理报表"));
        assertTrue(desc.contains("YYYY-MM-DD"));
        assertTrue(desc.contains("午市") || desc.contains("晚市"));
    }

    @Test
    void schemaDeclaresDateFields_asOptional() {
        // Tool resolves Chinese date phrases ('本月'/'上月') internally + defaults
        // to current month when args omitted, so date_from/date_to are NOT in
        // `required`. The properties are still declared so LLM knows the args
        // exist; just not required to provide them.
        Map<String, Object> schema = tool.getParametersSchema();
        assertEquals("object", schema.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertNotNull(props.get("date_from"));
        assertNotNull(props.get("date_to"));
        assertNotNull(props.get("store_names"));
        assertNotNull(props.get("meal_periods"));
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");
        assertFalse(required.contains("date_from"),
            "date_from must NOT be required (Tool resolves Chinese phrases / defaults)");
        assertFalse(required.contains("date_to"),
            "date_to must NOT be required (Tool resolves Chinese phrases / defaults)");
    }

    // ─── Happy path ─────────────────────────────────────────────────────

    @Test
    void successPathReturnsMessageAndDownloadUrl() throws Exception {
        when(mockClient.callRevenueReport(anyString(), anyMap(), anyString()))
            .thenReturn(Map.of(
                "success", true,
                "data", Map.of(
                    "download_url",
                    "/api/smartbi/R_QHJ/revenue-report/download/abc",
                    "summary", Map.of(
                        "store_count", 3,
                        "file_size_bytes", 28456,
                        "cache_hit", false
                    )
                )
            ));

        Map<String, Object> result = tool.doExecute(
            "R_QINGHUAJIAO_REAL",
            Map.of("date_from", "2025-10-01", "date_to", "2025-10-07"),
            context()
        );

        String message = (String) result.get("message");
        assertTrue(message.contains("已生成"));
        assertTrue(message.contains("2025-10-01"));
        assertTrue(message.contains("2025-10-07"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertNotNull(data);
        assertEquals(
            "/api/smartbi/R_QHJ/revenue-report/download/abc",
            data.get("download_url")
        );
    }

    @Test
    void successWithCacheHitMentionsCacheInMessage() throws Exception {
        when(mockClient.callRevenueReport(anyString(), anyMap(), anyString()))
            .thenReturn(Map.of(
                "success", true,
                "data", Map.of(
                    "download_url", "/x",
                    "summary", Map.of(
                        "store_count", 1, "file_size_bytes", 1024, "cache_hit", true
                    )
                )
            ));

        Map<String, Object> result = tool.doExecute(
            "R_QINGHUAJIAO_REAL",
            Map.of("date_from", "2025-10-01", "date_to", "2025-10-07"),
            context()
        );
        assertTrue(((String) result.get("message")).contains("缓存命中"));
    }

    // ─── Endpoint / payload shape ──────────────────────────────────────

    @Test
    void buildsFactoryScopedEndpointPath() throws Exception {
        when(mockClient.callRevenueReport(anyString(), anyMap(), anyString()))
            .thenReturn(Map.of(
                "success", true,
                "data", Map.of(
                    "download_url", "/x",
                    "summary", Map.of("store_count", 1, "file_size_bytes", 1, "cache_hit", false)
                )
            ));

        tool.doExecute(
            "R_QINGHUAJIAO_REAL",
            Map.of("date_from", "2025-10-01", "date_to", "2025-10-07"),
            context()
        );

        ArgumentCaptor<String> endpoint = ArgumentCaptor.forClass(String.class);
        verify(mockClient).callRevenueReport(endpoint.capture(), anyMap(), anyString());
        assertEquals(
            "/api/smartbi/R_QINGHUAJIAO_REAL/revenue-report/prepare",
            endpoint.getValue()
        );
    }

    @Test
    void forwardsUserRoleToPythonClient() throws Exception {
        when(mockClient.callRevenueReport(anyString(), anyMap(), anyString()))
            .thenReturn(Map.of(
                "success", true,
                "data", Map.of(
                    "download_url", "/x",
                    "summary", Map.of("store_count", 1, "file_size_bytes", 1, "cache_hit", false)
                )
            ));

        tool.doExecute(
            "R_QINGHUAJIAO_REAL",
            Map.of("date_from", "2025-10-01", "date_to", "2025-10-07"),
            Map.of("userRole", "restaurant_manager")
        );

        verify(mockClient).callRevenueReport(anyString(), anyMap(), eq("restaurant_manager"));
    }

    @Test
    void normalizesMealPeriodsBeforeForwarding() throws Exception {
        when(mockClient.callRevenueReport(anyString(), anyMap(), anyString()))
            .thenReturn(Map.of(
                "success", true,
                "data", Map.of(
                    "download_url", "/x",
                    "summary", Map.of("store_count", 1, "file_size_bytes", 1, "cache_hit", false)
                )
            ));

        tool.doExecute(
            "R_QINGHUAJIAO_REAL",
            Map.of(
                "date_from", "2025-10-01",
                "date_to", "2025-10-07",
                "meal_periods", List.of("下午茶", "夜宵")
            ),
            context()
        );

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(mockClient).callRevenueReport(anyString(), payload.capture(), anyString());
        @SuppressWarnings("unchecked")
        List<String> sent = (List<String>) payload.getValue().get("meal_periods");
        // 下午茶 → 午市, 夜宵 → 晚市
        assertEquals(List.of("午市", "晚市"), sent);
    }

    @Test
    void emptyStoreNamesPassedThrough() throws Exception {
        when(mockClient.callRevenueReport(anyString(), anyMap(), anyString()))
            .thenReturn(Map.of(
                "success", true,
                "data", Map.of(
                    "download_url", "/x",
                    "summary", Map.of("store_count", 0, "file_size_bytes", 0, "cache_hit", false)
                )
            ));

        tool.doExecute(
            "R_QINGHUAJIAO_REAL",
            Map.of("date_from", "2025-10-01", "date_to", "2025-10-07"),
            context()
        );

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(mockClient).callRevenueReport(anyString(), payload.capture(), anyString());
        assertEquals(Collections.emptyList(), payload.getValue().get("store_names"));
    }

    // ─── Error paths ────────────────────────────────────────────────────

    @Test
    void invalidMealPeriodReturnsParamError() throws Exception {
        Map<String, Object> result = tool.doExecute(
            "R_QINGHUAJIAO_REAL",
            Map.of(
                "date_from", "2025-10-01",
                "date_to", "2025-10-07",
                "meal_periods", List.of("夜市") // not in MealPeriodNormalizer MAP
            ),
            context()
        );
        assertTrue(((String) result.get("message")).contains("班次参数错误"));
        // No Python call when normalization fails up front.
        verify(mockClient, never()).callRevenueReport(anyString(), anyMap(), anyString());
    }

    @Test
    void pythonUnavailableReturnsServiceError() throws Exception {
        when(mockClient.callRevenueReport(anyString(), anyMap(), anyString())).thenReturn(null);

        Map<String, Object> result = tool.doExecute(
            "R_QINGHUAJIAO_REAL",
            Map.of("date_from", "2025-10-01", "date_to", "2025-10-07"),
            context()
        );
        assertTrue(((String) result.get("message")).contains("Python 服务不可用"));
    }

    @Test
    void pythonSuccessFalseReturnsErrorMessage() throws Exception {
        when(mockClient.callRevenueReport(anyString(), anyMap(), anyString()))
            .thenReturn(Map.of("success", false, "error", "factory 不存在"));

        Map<String, Object> result = tool.doExecute(
            "R_QINGHUAJIAO_REAL",
            Map.of("date_from", "2025-10-01", "date_to", "2025-10-07"),
            context()
        );
        String msg = (String) result.get("message");
        assertTrue(msg.contains("生成失败"));
        assertTrue(msg.contains("factory 不存在"));
    }
}
