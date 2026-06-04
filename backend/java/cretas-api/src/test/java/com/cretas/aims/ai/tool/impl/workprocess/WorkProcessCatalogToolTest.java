package com.cretas.aims.ai.tool.impl.workprocess;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.dto.WorkProcessDTO;
import com.cretas.aims.service.WorkProcessService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("WorkProcessCatalogTool 单元测试")
@ExtendWith(MockitoExtension.class)
class WorkProcessCatalogToolTest {

    private static final String FACTORY_ID = "F006";

    @Mock
    private WorkProcessService workProcessService;

    @InjectMocks
    private WorkProcessCatalogTool tool;

    @Captor
    private ArgumentCaptor<WorkProcessDTO> dtoCaptor;

    @Test
    @DisplayName("metadata: canvas 工具名 + 写操作风险")
    void metadata() {
        assertEquals("canvas_work_process_catalog", tool.getToolName());
        assertTrue(tool.getDescription().contains("工序"));
        assertEquals(ToolExecutor.ActionType.UPDATE, tool.getActionType());
        assertEquals(ToolExecutor.RiskLevel.MEDIUM, tool.getRiskLevel());
        assertTrue(tool.getParametersSchema().containsKey("properties"));
        assertEquals(List.of("action"), tool.getRequiredParameters());
    }

    @Test
    @DisplayName("create: 新建前查重，未命中时映射全字段并调用 WorkProcessService.create")
    void createMapsFieldsAndCallsService() throws Exception {
        when(workProcessService.listActive(FACTORY_ID)).thenReturn(List.of());
        when(workProcessService.create(eq(FACTORY_ID), any(WorkProcessDTO.class)))
                .thenAnswer(inv -> {
                    WorkProcessDTO dto = inv.getArgument(1);
                    dto.setId("wp-new");
                    return dto;
                });

        Map<String, Object> result = invokeDoExecute(Map.ofEntries(
                Map.entry("action", "create"),
                Map.entry("processName", "焯水"),
                Map.entry("processCategory", "加工"),
                Map.entry("unit", "kg"),
                Map.entry("estimatedMinutes", 15),
                Map.entry("standardYieldMin", "0.30"),
                Map.entry("standardYieldMax", "0.60"),
                Map.entry("needsInput", true),
                Map.entry("outputUnit", "kg"),
                Map.entry("standardHourlyRate", "25.00"),
                Map.entry("sortOrder", 7)
        ));

        verify(workProcessService).listActive(FACTORY_ID);
        verify(workProcessService).create(eq(FACTORY_ID), dtoCaptor.capture());
        WorkProcessDTO dto = dtoCaptor.getValue();
        assertEquals("焯水", dto.getProcessName());
        assertEquals("加工", dto.getProcessCategory());
        assertEquals("kg", dto.getUnit());
        assertEquals(15, dto.getEstimatedMinutes());
        assertEquals(0, new BigDecimal("0.30").compareTo(dto.getStandardYieldMin()));
        assertEquals(0, new BigDecimal("0.60").compareTo(dto.getStandardYieldMax()));
        assertTrue(dto.getNeedsInput());
        assertEquals("kg", dto.getOutputUnit());
        assertEquals(0, new BigDecimal("25.00").compareTo(dto.getStandardHourlyRate()));
        assertEquals(7, dto.getSortOrder());
        assertTrue(result.get("message").toString().contains("已创建工序"));
    }

    @Test
    @DisplayName("create: 名称+类别+单位已存在时返回已有工序，不调用 create")
    void createReturnsExistingWhenTripletDuplicate() throws Exception {
        WorkProcessDTO existing = WorkProcessDTO.builder()
                .id("wp-existing")
                .processName("焯水")
                .processCategory("加工")
                .unit("kg")
                .isActive(true)
                .build();
        when(workProcessService.listActive(FACTORY_ID)).thenReturn(List.of(existing));

        Map<String, Object> result = invokeDoExecute(Map.of(
                "action", "create",
                "processName", "焯水",
                "processCategory", "加工",
                "unit", "kg",
                "standardHourlyRate", "25.00"
        ));

        verify(workProcessService).listActive(FACTORY_ID);
        verify(workProcessService, never()).create(anyString(), any());
        assertEquals("DUPLICATE", result.get("status"));
        assertTrue(result.get("message").toString().contains("已存在相同名称+类别+单位的工序"));
        assertSame(existing, result.get("data"));
    }

    @Test
    @DisplayName("update: 根据 id 映射可改字段并调用 WorkProcessService.update")
    void updateMapsFieldsAndCallsService() throws Exception {
        WorkProcessDTO updated = WorkProcessDTO.builder()
                .id("wp-1")
                .processName("焯水")
                .processCategory("加工")
                .unit("kg")
                .standardHourlyRate(new BigDecimal("28.00"))
                .build();
        when(workProcessService.update(eq(FACTORY_ID), eq("wp-1"), any(WorkProcessDTO.class)))
                .thenReturn(updated);

        Map<String, Object> result = invokeDoExecute(Map.of(
                "action", "update",
                "id", "wp-1",
                "processName", "焯水",
                "processCategory", "加工",
                "unit", "kg",
                "standardHourlyRate", "28.00"
        ));

        verify(workProcessService).update(eq(FACTORY_ID), eq("wp-1"), dtoCaptor.capture());
        WorkProcessDTO dto = dtoCaptor.getValue();
        assertEquals("焯水", dto.getProcessName());
        assertEquals("加工", dto.getProcessCategory());
        assertEquals("kg", dto.getUnit());
        assertEquals(0, new BigDecimal("28.00").compareTo(dto.getStandardHourlyRate()));
        assertTrue(result.get("message").toString().contains("已更新工序"));
        assertSame(updated, result.get("data"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeDoExecute(Map<String, Object> params) throws Exception {
        var method = WorkProcessCatalogTool.class.getDeclaredMethod(
                "doExecute", String.class, Map.class, Map.class);
        method.setAccessible(true);
        return (Map<String, Object>) method.invoke(tool, FACTORY_ID, params, ctx());
    }

    private Map<String, Object> ctx() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("factoryId", FACTORY_ID);
        ctx.put("userId", 1L);
        ctx.put("userRole", "factory_super_admin");
        return ctx;
    }
}
