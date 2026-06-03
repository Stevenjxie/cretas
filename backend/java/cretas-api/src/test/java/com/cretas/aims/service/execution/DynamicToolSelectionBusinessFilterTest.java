package com.cretas.aims.service.execution;

import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.service.ToolRouterService;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DynamicToolSelectionBusinessFilterTest {

    private ToolRouterService.ToolCandidate cand(String tool) {
        return ToolRouterService.ToolCandidate.builder().toolName(tool).similarity(0.8).build();
    }
    private AIIntentConfig intent(String code, String biz, String tool) {
        AIIntentConfig i = new AIIntentConfig();
        i.setIntentCode(code); i.setBusinessType(biz); i.setToolName(tool); return i;
    }
    private DynamicToolSelectionService newSvc(IntentConfigManagementService cfg) {
        // Construct with mocks/nulls for ctor deps not exercised here; inject configService via reflection.
        DynamicToolSelectionService s = new DynamicToolSelectionService(
            mock(ToolRouterService.class), mock(com.cretas.aims.ai.tool.ToolRegistry.class),
            new com.fasterxml.jackson.databind.ObjectMapper());
        ReflectionTestUtils.setField(s, "configService", cfg);
        return s;
    }

    @Test
    void restaurantFactory_dropsManufacturingTool_keepsRestaurantAndOrphan() {
        IntentConfigManagementService cfg = mock(IntentConfigManagementService.class);
        when(cfg.resolveBusinessDomain("RES_1")).thenReturn("RESTAURANT");
        when(cfg.getAllIntents("RES_1")).thenReturn(List.of(
            intent("SKU_GROSS_MARGIN", "MANUFACTURING", "sku_gross_margin_tool"),
            intent("RESTAURANT_ORDER_STATISTICS", "RESTAURANT", "restaurant_order_type_mix_gold")
        ));
        DynamicToolSelectionService svc = newSvc(cfg);
        List<ToolRouterService.ToolCandidate> in = new ArrayList<>(List.of(
            cand("sku_gross_margin_tool"), cand("restaurant_order_type_mix_gold"), cand("some_orphan_tool")));
        List<ToolRouterService.ToolCandidate> out = svc.filterCandidatesByBusinessType(in, "RES_1");
        Set<String> kept = new HashSet<>();
        out.forEach(c -> kept.add(c.getToolName()));
        assertFalse(kept.contains("sku_gross_margin_tool"));
        assertTrue(kept.contains("restaurant_order_type_mix_gold"));
        assertTrue(kept.contains("some_orphan_tool"));  // orphan tool (no owning intent) kept
    }

    @Test
    void resolveFailure_returnsUnfiltered() {
        IntentConfigManagementService cfg = mock(IntentConfigManagementService.class);
        when(cfg.resolveBusinessDomain(any())).thenThrow(new RuntimeException("boom"));
        DynamicToolSelectionService svc = newSvc(cfg);
        List<ToolRouterService.ToolCandidate> in = List.of(cand("any_tool"));
        assertEquals(1, svc.filterCandidatesByBusinessType(in, "F").size());
    }
}
