package com.cretas.aims.ai.tool.impl.restaurant.diagnostic;

import com.cretas.aims.client.PythonSmartBIClient;
import com.cretas.aims.dto.python.PythonRestaurantSectionRequest;
import com.cretas.aims.dto.python.PythonRestaurantSectionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StoreKpiDashboardTool} (single-store 店长 6-KPI MVP).
 *
 * Verifies: metadata, role injection into Python params (X-User-Role equiv for
 * RBAC strip), readable message assembly from 6-KPI cards, honest empty path,
 * and follow-up chips. The role resolver is overridden in the test subclass to
 * avoid depending on a live servlet request context.
 */
@ExtendWith(MockitoExtension.class)
class StoreKpiDashboardToolTest {

    private static final String FACTORY = "RES_3101_009";

    @Mock
    private PythonSmartBIClient pythonClient;

    private StoreKpiDashboardTool tool;
    private String stubbedRole;

    @BeforeEach
    void setUp() {
        stubbedRole = "factory_super_admin";
        tool = new StoreKpiDashboardTool() {
            @Override
            protected String resolveUserRole() {
                return stubbedRole;  // bypass servlet context in unit test
            }
        };
        tool.pythonClient = pythonClient;
    }

    private PythonRestaurantSectionResponse okResponse(Map<String, Object> data) {
        PythonRestaurantSectionResponse resp = new PythonRestaurantSectionResponse();
        resp.setSuccess(true);
        resp.setStatus("ok");
        resp.setSectionName("store_kpi_dashboard");
        resp.setData(data);
        resp.setWarnings(List.of());
        return resp;
    }

    private Map<String, Object> sixKpiData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("store_name", "青花椒店");
        data.put("overall_health", "WARNING");
        data.put("kpis", List.of(
                kpi("daily_revenue", "日营收", "¥10,000.00", "GOOD"),
                kpi("avg_ticket", "客单价", "¥200.00", "GOOD"),
                kpi("order_count", "订单数", "5,000", "GOOD"),
                kpi("gross_margin", "毛利率", "45.0%", "WARNING"),
                kpi("food_cost_rate", "食材成本率", "35.0%", "GOOD"),
                kpi("target_completion", "目标完成率", "88.0%", "WARNING")));
        return data;
    }

    private Map<String, Object> kpi(String key, String label, String value, String status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("label", label);
        m.put("value", value);
        m.put("status", status);
        return m;
    }

    @Test
    @DisplayName("metadata — toolName / sectionName / description")
    void metadata() {
        assertThat(tool.getToolName()).isEqualTo("restaurant_store_kpi_dashboard");
        assertThat(tool.getSectionName()).isEqualTo("store_kpi_dashboard");
        assertThat(tool.getDescription()).contains("店长经营 KPI 看板");
        assertThat(tool.getDescription()).contains("经营看板");
    }

    @Test
    @DisplayName("happy path — role injected into params, 6-KPI message assembled")
    void happyPath_injectsRole_buildsMessage() throws Exception {
        when(pythonClient.callRestaurantSection(eq("store_kpi_dashboard"), any()))
                .thenReturn(Optional.of(okResponse(sixKpiData())));

        Map<String, Object> result = tool.doExecute(FACTORY,
                Map.of("sub_sector", "川菜"), new HashMap<>());

        assertThat(result).containsEntry("success", true);

        // role forwarded into Python section params (for RBAC money-strip)
        ArgumentCaptor<PythonRestaurantSectionRequest> reqCaptor =
                ArgumentCaptor.forClass(PythonRestaurantSectionRequest.class);
        verify(pythonClient).callRestaurantSection(eq("store_kpi_dashboard"), reqCaptor.capture());
        assertThat(reqCaptor.getValue().getParams()).containsEntry("role", "factory_super_admin");
        // framework keys stripped from params (live on envelope)
        assertThat(reqCaptor.getValue().getParams()).doesNotContainKey("sub_sector");

        // readable message references KPI labels + values + overall health
        String msg = (String) result.get("message");
        assertThat(msg).contains("日营收");
        assertThat(msg).contains("毛利率");
        assertThat(msg).contains("45.0%");
        assertThat(msg).contains("青花椒店");
        assertThat(msg).contains("需关注");  // WARNING overall

        // cards passed through + follow-up chips present
        assertThat(result).containsKey("data");
        assertThat((List<?>) result.get("followUpChips")).isNotEmpty();
    }

    @Test
    @DisplayName("no role context — params omit role (Python fail-closed strips money)")
    void noRoleContext_omitsRoleParam() throws Exception {
        stubbedRole = null;  // simulate scheduled job / no request
        when(pythonClient.callRestaurantSection(eq("store_kpi_dashboard"), any()))
                .thenReturn(Optional.of(okResponse(sixKpiData())));

        tool.doExecute(FACTORY, Map.of("sub_sector", "川菜"), new HashMap<>());

        ArgumentCaptor<PythonRestaurantSectionRequest> reqCaptor =
                ArgumentCaptor.forClass(PythonRestaurantSectionRequest.class);
        verify(pythonClient).callRestaurantSection(eq("store_kpi_dashboard"), reqCaptor.capture());
        assertThat(reqCaptor.getValue().getParams()).doesNotContainKey("role");
    }

    @Test
    @DisplayName("empty data — honest message (never blank, UI shows it instead of 操作已完成)")
    void emptyData_honestMessage() throws Exception {
        when(pythonClient.callRestaurantSection(eq("store_kpi_dashboard"), any()))
                .thenReturn(Optional.of(okResponse(new LinkedHashMap<>())));

        Map<String, Object> result = tool.doExecute(FACTORY,
                Map.of("sub_sector", "川菜"), new HashMap<>());

        assertThat(result).containsEntry("success", true);
        assertThat((String) result.get("message")).contains("上传");
    }

    @Test
    @DisplayName("section SKIPPED — base class buildError surfaces warnings")
    void sectionSkipped_buildError() throws Exception {
        PythonRestaurantSectionResponse skip = new PythonRestaurantSectionResponse();
        skip.setSuccess(false);
        skip.setStatus("skipped");
        skip.setSectionName("store_kpi_dashboard");
        skip.setWarnings(List.of("未检测到经营数据，请先上传 POS 销售/财务数据"));
        when(pythonClient.callRestaurantSection(eq("store_kpi_dashboard"), any()))
                .thenReturn(Optional.of(skip));

        Map<String, Object> result = tool.doExecute(FACTORY,
                Map.of("sub_sector", "川菜"), new HashMap<>());

        assertThat(result).containsEntry("success", false);
        assertThat((String) result.get("message")).contains("经营数据");
    }

    @Test
    @DisplayName("Python unavailable — buildError path")
    void pythonUnavailable_buildError() throws Exception {
        when(pythonClient.callRestaurantSection(eq("store_kpi_dashboard"), any()))
                .thenReturn(Optional.empty());

        Map<String, Object> result = tool.doExecute(FACTORY,
                Map.of("sub_sector", "川菜"), new HashMap<>());

        assertThat(result).containsEntry("success", false);
        assertThat((String) result.get("message")).containsIgnoringCase("数据分析服务");
    }

    @Test
    @DisplayName("camelCase data shape also handled (overallHealth / storeName)")
    void camelCaseShape_handled() throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("storeName", "驼峰店");
        data.put("overallHealth", "CRITICAL");
        data.put("kpis", List.of(kpi("daily_revenue", "日营收", "—", "GOOD")));
        when(pythonClient.callRestaurantSection(eq("store_kpi_dashboard"), any()))
                .thenReturn(Optional.of(okResponse(data)));

        Map<String, Object> result = tool.doExecute(FACTORY,
                Map.of("sub_sector", "川菜"), new HashMap<>());

        String msg = (String) result.get("message");
        assertThat(msg).contains("驼峰店");
        assertThat(msg).contains("严重");
    }
}
