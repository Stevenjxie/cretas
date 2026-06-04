package com.cretas.aims.ai.tool.impl.workprocess;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.dto.ProductWorkProcessDTO;
import com.cretas.aims.dto.WorkProcessDTO;
import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.service.ProductWorkProcessService;
import com.cretas.aims.service.UserService;
import com.cretas.aims.service.WorkProcessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductWorkProcessConfigToolTest {

    private static final String FACTORY_ID = "F006";
    private static final String PRODUCT_TYPE_ID = "PT-ZHUSHE";

    @InjectMocks
    private ProductWorkProcessConfigTool tool;

    @Mock
    private ProductWorkProcessService productWorkProcessService;

    @Mock
    private WorkProcessService workProcessService;

    @Mock
    private UserService userService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("D1 metadata: canvas tool supports preview and has write semantics")
    void metadata() {
        assertEquals("canvas_product_work_process_config", tool.getToolName());
        assertTrue(tool.supportsPreview());
        assertEquals(ToolExecutor.ActionType.WRITE, tool.getActionType());
        assertEquals(ToolExecutor.RiskLevel.MEDIUM, tool.getRiskLevel());
        assertTrue(tool.getRequiredParameters().contains("productTypeId"));
        assertTrue(tool.getRequiredParameters().contains("message"));
    }

    @Test
    @DisplayName("D1 preview: natural language maps catalog processes and assignee without writing")
    @SuppressWarnings("unchecked")
    void previewBuildsDraftWithoutWriting() throws Exception {
        mockCatalogAndOperators();
        when(productWorkProcessService.listByProduct(FACTORY_ID, PRODUCT_TYPE_ID))
                .thenReturn(List.of(existingAssociation(10L, "wp-oil", null)));

        Map<String, Object> result = invoke("doPreview", params(
                "第一步修油，滚揉交给莫云，第三步焯水"));

        assertEquals("PREVIEW", result.get("status"));
        assertEquals(false, result.get("applied"));
        assertTrue(result.get("message").toString().contains("3 道工序草稿"));

        List<Map<String, Object>> draft = (List<Map<String, Object>>) result.get("draft");
        assertEquals(3, draft.size());
        assertEquals("update", draft.get(0).get("operation"));
        assertEquals(10L, draft.get(0).get("productWorkProcessId"));
        assertEquals("修油", draft.get(0).get("processName"));
        assertEquals("create", draft.get(1).get("operation"));
        assertEquals("滚揉", draft.get(1).get("processName"));
        assertEquals(1615L, draft.get(1).get("responsibleWorkerId"));
        assertEquals("莫云", draft.get(1).get("responsibleWorkerName"));
        assertEquals("焯水", draft.get(2).get("processName"));

        verify(productWorkProcessService, never()).create(any(), any());
        verify(productWorkProcessService, never()).update(any(), any(), any());
    }

    @Test
    @DisplayName("D1 execute apply=true: creates missing associations and updates existing rows with full DTO")
    void executeAppliesFullRows() throws Exception {
        mockCatalogAndOperators();
        when(productWorkProcessService.listByProduct(FACTORY_ID, PRODUCT_TYPE_ID))
                .thenReturn(List.of(existingAssociation(10L, "wp-oil", null)));

        Map<String, Object> result = invoke("doExecute", paramsWithApply(
                "修油交给莫云，第二步滚揉"));

        assertEquals(true, result.get("applied"));
        assertTrue(result.get("message").toString().contains("已应用"));

        ArgumentCaptor<ProductWorkProcessDTO> updateCaptor =
                ArgumentCaptor.forClass(ProductWorkProcessDTO.class);
        verify(productWorkProcessService).update(eq(FACTORY_ID), eq(10L), updateCaptor.capture());
        ProductWorkProcessDTO updateDto = updateCaptor.getValue();
        assertEquals(PRODUCT_TYPE_ID, updateDto.getProductTypeId());
        assertEquals("wp-oil", updateDto.getWorkProcessId());
        assertEquals(1615L, updateDto.getResponsibleWorkerId());

        ArgumentCaptor<ProductWorkProcessDTO> createCaptor =
                ArgumentCaptor.forClass(ProductWorkProcessDTO.class);
        verify(productWorkProcessService).create(eq(FACTORY_ID), createCaptor.capture());
        ProductWorkProcessDTO createDto = createCaptor.getValue();
        assertEquals(PRODUCT_TYPE_ID, createDto.getProductTypeId());
        assertEquals("wp-tumble", createDto.getWorkProcessId());
        assertEquals(2, createDto.getProcessOrder());
    }

    @Test
    @DisplayName("D1 preview: unknown process is surfaced as missing catalog item")
    @SuppressWarnings("unchecked")
    void previewReportsMissingProcess() throws Exception {
        mockCatalogAndOperators();
        when(productWorkProcessService.listByProduct(FACTORY_ID, PRODUCT_TYPE_ID))
                .thenReturn(List.of());

        Map<String, Object> result = invoke("doPreview", params("第一步修油，第二步真空冷却"));

        List<Map<String, Object>> missing = (List<Map<String, Object>>) result.get("missingProcesses");
        assertFalse(missing.isEmpty());
        assertTrue(missing.get(0).get("name").toString().contains("真空冷却"));
        assertTrue(result.get("message").toString().contains("请先去工序管理新建"));
    }

    private void mockCatalogAndOperators() {
        when(workProcessService.listActive(FACTORY_ID)).thenReturn(List.of(
                workProcess("wp-oil", "修油"),
                workProcess("wp-tumble", "滚揉"),
                workProcess("wp-blanch", "焯水")
        ));
        when(userService.getUsersByRole(FACTORY_ID, FactoryUserRole.operator)).thenReturn(List.of(
                UserDTO.builder().id(1615L).username("f006_moyun").fullName("莫云").build(),
                UserDTO.builder().id(1616L).username("f006_weizj").fullName("魏振江").build()
        ));
        when(userService.getUsersByRole(FACTORY_ID, FactoryUserRole.group_leader)).thenReturn(List.of());
    }

    private WorkProcessDTO workProcess(String id, String name) {
        return WorkProcessDTO.builder()
                .id(id)
                .processName(name)
                .processCategory("前处理")
                .unit("kg")
                .isActive(true)
                .build();
    }

    private ProductWorkProcessDTO existingAssociation(Long id, String workProcessId, Long workerId) {
        return ProductWorkProcessDTO.builder()
                .id(id)
                .productTypeId(PRODUCT_TYPE_ID)
                .workProcessId(workProcessId)
                .processOrder(1)
                .responsibleWorkerId(workerId)
                .build();
    }

    private Map<String, Object> params(String message) {
        Map<String, Object> params = new HashMap<>();
        params.put("productTypeId", PRODUCT_TYPE_ID);
        params.put("message", message);
        return params;
    }

    private Map<String, Object> paramsWithApply(String message) {
        Map<String, Object> params = params(message);
        params.put("apply", true);
        return params;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invoke(String methodName, Map<String, Object> params) throws Exception {
        var method = findMethod(tool.getClass(), methodName);
        method.setAccessible(true);
        try {
            return (Map<String, Object>) method.invoke(tool, FACTORY_ID, params, ctx());
        } catch (java.lang.reflect.InvocationTargetException ite) {
            if (ite.getCause() instanceof RuntimeException re) {
                throw re;
            }
            if (ite.getCause() instanceof Exception ee) {
                throw ee;
            }
            throw ite;
        }
    }

    private Map<String, Object> ctx() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("factoryId", FACTORY_ID);
        ctx.put("userId", 1309L);
        return ctx;
    }

    private java.lang.reflect.Method findMethod(Class<?> clazz, String name) {
        while (clazz != null) {
            for (var m : clazz.getDeclaredMethods()) {
                if (m.getName().equals(name)) {
                    return m;
                }
            }
            clazz = clazz.getSuperclass();
        }
        throw new IllegalArgumentException("Method not found: " + name);
    }

    private void injectField(Object target, String name, Object value) throws Exception {
        Field f = findField(target.getClass(), name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private Field findField(Class<?> clazz, String name) {
        while (clazz != null) {
            try {
                return clazz.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new IllegalArgumentException("Field not found: " + name);
    }
}
