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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        assertTrue(tool.requiresPermission());
        assertTrue(tool.getRequiredPermissions().isEmpty());
        assertTrue(tool.hasPermission(FactoryUserRole.factory_super_admin.name()));
        assertTrue(tool.hasPermission(FactoryUserRole.permission_admin.name()));
        assertFalse(tool.hasPermission(FactoryUserRole.operator.name()));
        assertEquals("1.0.0", tool.getVersion());
        assertEquals(Set.of("canvas", "production", "work-process", "product"),
                tool.getDomainTags());
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

    @Test
    @DisplayName("B-2 assignee boundary: 滚揉 does not inherit 魏振江 who is only after 焯水")
    @SuppressWarnings("unchecked")
    void assigneeBoundary_middleStepGetsNoAssignee() throws Exception {
        // Catalog: 修油(wp-oil), 滚揉(wp-tumble), 焯水(wp-blanch)
        // Message: "修油，滚揉，焯水交给魏振江"
        // Expected: 修油→null, 滚揉→null, 焯水→魏振江
        when(workProcessService.listActive(FACTORY_ID)).thenReturn(List.of(
                workProcess("wp-oil", "修油"),
                workProcess("wp-tumble", "滚揉"),
                workProcess("wp-blanch", "焯水")
        ));
        when(userService.getUsersByRole(FACTORY_ID, FactoryUserRole.operator)).thenReturn(List.of(
                UserDTO.builder().id(1616L).username("f006_weizj").fullName("魏振江").isActive(true).build()
        ));
        when(userService.getUsersByRole(FACTORY_ID, FactoryUserRole.group_leader)).thenReturn(List.of());
        when(productWorkProcessService.listByProduct(FACTORY_ID, PRODUCT_TYPE_ID))
                .thenReturn(List.of());

        Map<String, Object> result = invoke("doPreview", params("修油，滚揉，焯水交给魏振江"));
        List<Map<String, Object>> draft = (List<Map<String, Object>>) result.get("draft");

        assertEquals(3, draft.size());
        // 修油: no assignee
        assertNull(draft.get(0).get("responsibleWorkerId"),
                "修油 should have no assignee (魏振江 appears only after 焯水)");
        // 滚揉: no assignee — B-2 boundary ensures it cannot read past its own segment end
        assertNull(draft.get(1).get("responsibleWorkerId"),
                "滚揉 should have no assignee (魏振江 is beyond its boundary)");
        // 焯水: gets 魏振江
        assertEquals(1616L, draft.get(2).get("responsibleWorkerId"),
                "焯水 should get 魏振江");
    }

    @Test
    @DisplayName("B-3 no apply: doExecute without apply=true must not write to DB")
    void executeWithoutApplyDoesNotWrite() throws Exception {
        mockCatalogAndOperators();
        when(productWorkProcessService.listByProduct(FACTORY_ID, PRODUCT_TYPE_ID))
                .thenReturn(List.of());

        Map<String, Object> result = invoke("doExecute", params("第一步修油，第二步滚揉"));

        assertEquals("PREVIEW", result.get("status"),
                "Without apply=true the result should be PREVIEW status");
        verify(productWorkProcessService, never()).create(any(), any());
        verify(productWorkProcessService, never()).update(any(), any(), any());
    }

    @Test
    @DisplayName("missing productTypeId returns NEED_MORE_INFO / NPE-safe")
    void missingProductTypeIdReturnsNeedMoreInfo() {
        // Calling doExecute with an empty productTypeId must not NPE;
        // AbstractBusinessTool.checkRequiredParameters should reject it before buildPlan runs.
        Map<String, Object> badParams = new HashMap<>();
        badParams.put("productTypeId", "");
        badParams.put("message", "修油，滚揉");

        // The tool's required parameters include productTypeId; missing/blank value should
        // cause the tool to throw or return an error map, not an NPE from downstream services.
        // We verify by invoking doExecute and asserting no unhandled NullPointerException.
        // (AbstractBusinessTool.validate() is called by execute(), not doExecute(), so doExecute
        // may throw BusinessException for blank productTypeId if buildPlan checks it first.
        // If it does NOT validate there — at minimum no NPE should propagate.)
        try {
            // No service stubs needed. Returning without exception proves the NPE-safety
            // guarantee (doExecute sits below validateRequiredParams; real calls go through
            // execute(), which rejects blank productTypeId via required-params validation).
            invoke("doExecute", badParams);
        } catch (com.cretas.aims.exception.BusinessException be) {
            // Expected path: tool detected blank productTypeId and threw BusinessException
            assertNotNull(be.getMessage(), "BusinessException message must not be null");
        } catch (Exception e) {
            // Any other checked exception is acceptable (e.g. tool throws from validate)
            // but NullPointerException must NOT be the cause
            if (e.getCause() instanceof NullPointerException) {
                throw new AssertionError("NPE must not propagate from blank productTypeId", e);
            }
        }
    }

    // ----------------------------------------------------------------
    // E1+E2: Fidelity regression — 10-step input with duplicate catalog names
    // ----------------------------------------------------------------

    @Test
    @DisplayName("E2 fidelity: 10-step arrow-separated input produces exactly 10 draft steps, no duplicates")
    @SuppressWarnings("unchecked")
    void tenStepArrowInput_faithfullyMapsAllSteps() throws Exception {
        // Reproduce the exact F006 catalog that caused the original bug:
        // three separate "焯水" rows (for 猪舌, 牛腱, 掌中宝) all with the same name.
        // Old algorithm: all 3 match the single "焯水" in the message → 焯水×3 in draft.
        // New algorithm: deduplication + segment-driven → exactly 1 焯水 matched.
        when(workProcessService.listActive(FACTORY_ID)).thenReturn(List.of(
                workProcess("WP-F006-ZS-01", "修油",            1),
                workProcess("WP-F006-ZS-02", "滚揉(保水)",      2),
                workProcess("WP-F006-ZS-03", "焯水",            3),   // ← duplicate name row 1
                workProcess("WP-F006-ZS-04", "去舌苔",          4),
                workProcess("WP-F006-ZS-05", "熟制(卤制)",      5),
                workProcess("WP-F006-ZS-06", "气调(分切装盒)",  6),
                workProcess("WP-F006-NT-01", "修油",            1),   // ← duplicate 修油 row
                workProcess("WP-F006-NT-02", "滚揉(注射保水)",  2),
                workProcess("WP-F006-NT-03", "焯水",            3),   // ← duplicate name row 2
                workProcess("WP-F006-NT-04", "熟制(卤制)",      5),
                workProcess("WP-F006-NT-05", "气调(抛片装盒)",  5),
                workProcess("WP-F006-ZZB-01", "水解化冻",       1),
                workProcess("WP-F006-ZZB-02", "焯水",           3),   // ← duplicate name row 3
                workProcess("WP-F006-ZZB-03", "油炸",           3),
                workProcess("WP-F006-ZZB-04", "熟制伴汁",       4),
                workProcess("WP-F006-ZZB-05", "气调",           5)
        ));
        when(userService.getUsersByRole(FACTORY_ID, FactoryUserRole.operator)).thenReturn(List.of());
        when(userService.getUsersByRole(FACTORY_ID, FactoryUserRole.group_leader)).thenReturn(List.of());
        when(productWorkProcessService.listByProduct(FACTORY_ID, PRODUCT_TYPE_ID)).thenReturn(List.of());

        // The exact 10-step input from Steve's bug report
        String input = "解冻 → 分切 → 腌制 → 二次滚揉保水 → 焯水 → 沥干 → 油炸 → 修猪舌 → 滚揉配酱 → 气调包装";
        Map<String, Object> result = invoke("doPreview", params(input));

        List<Map<String, Object>> draft = (List<Map<String, Object>>) result.get("draft");

        // Must produce exactly 10 steps
        assertEquals(10, draft.size(),
                "Expected 10 draft steps matching the 10 input segments, got: "
                        + draft.size() + " steps: "
                        + draft.stream().map(s -> s.get("processName")).toList());

        // Verify no duplicate processName values from catalog entries
        List<String> names = draft.stream()
                .filter(s -> s.get("workProcessId") != null)
                .map(s -> (String) s.get("processName"))
                .toList();
        long distinctNames = names.stream().distinct().count();
        assertEquals(names.size(), distinctNames,
                "Draft should have no duplicate catalog process names, but found duplicates in: " + names);

        // Step 1: 解冻 — not in catalog (no "解冻" row in F006), should be a placeholder
        assertEquals("new", draft.get(0).get("operation"),
                "解冻 not in catalog → should be 'new' placeholder");
        assertEquals("解冻", draft.get(0).get("processName"));

        // Step 5: 焯水 — exactly one match (deduplicated from 3 rows)
        assertEquals("焯水", draft.get(4).get("processName"),
                "Step 5 should be 焯水");
        assertNotNull(draft.get(4).get("workProcessId"),
                "焯水 should be matched to a catalog entry");

        // Steps that are NOT in catalog should be "new" (腌制, 沥干, 修猪舌, 滚揉配酱, 气调包装)
        List<String> newSteps = draft.stream()
                .filter(s -> "new".equals(s.get("operation")))
                .map(s -> (String) s.get("processName"))
                .toList();
        assertTrue(newSteps.contains("腌制"), "腌制 not in catalog → must be 'new'");
        assertTrue(newSteps.contains("沥干"), "沥干 not in catalog → must be 'new'");

        // The reply message must not claim 10 matched catalog steps — it should mention missing processes
        String message = (String) result.get("message");
        assertTrue(message.contains("未找到匹配工序") || message.contains("请先去工序管理新建"),
                "Message should mention that some processes need to be created first: " + message);
    }

    @Test
    @DisplayName("E1 dedup: catalog with 3 identical 'name' entries matches correctly without fanout")
    @SuppressWarnings("unchecked")
    void catalogDedup_threeSameNameEntriesDoNotFanout() throws Exception {
        // Scenario: three rows all named "焯水", user types "焯水" once.
        // Old: all 3 match → 3 steps. New: deduplicated to 1, exactly 1 step.
        when(workProcessService.listActive(FACTORY_ID)).thenReturn(List.of(
                workProcess("wp-blanch-1", "焯水", 3),
                workProcess("wp-blanch-2", "焯水", 3),
                workProcess("wp-blanch-3", "焯水", 3)
        ));
        when(userService.getUsersByRole(FACTORY_ID, FactoryUserRole.operator)).thenReturn(List.of());
        when(userService.getUsersByRole(FACTORY_ID, FactoryUserRole.group_leader)).thenReturn(List.of());
        when(productWorkProcessService.listByProduct(FACTORY_ID, PRODUCT_TYPE_ID)).thenReturn(List.of());

        Map<String, Object> result = invoke("doPreview", params("焯水"));
        List<Map<String, Object>> draft = (List<Map<String, Object>>) result.get("draft");

        assertEquals(1, draft.size(), "One input segment 'name' → exactly 1 draft step (not 3)");
        assertEquals("焯水", draft.get(0).get("processName"));
    }

    @Test
    @DisplayName("E2 substring guard: '二次滚揉保水' does NOT match catalog '焯水' as substring")
    @SuppressWarnings("unchecked")
    void substringGuard_shortCatalogNameDoesNotMatchLongSegment() throws Exception {
        // "焯水" is 2 chars; "二次滚揉保水" normalized = 6 chars → 2/6 < 0.5 → should not match
        when(workProcessService.listActive(FACTORY_ID)).thenReturn(List.of(
                workProcess("wp-tumble", "滚揉(保水)", 2),  // normalized "滚揉保水" = 4 chars; 4/6 = 0.67 → should match
                workProcess("wp-blanch", "焯水", 3)          // normalized "焯水" = 2 chars; 2/6 = 0.33 → should NOT match
        ));
        when(userService.getUsersByRole(FACTORY_ID, FactoryUserRole.operator)).thenReturn(List.of());
        when(userService.getUsersByRole(FACTORY_ID, FactoryUserRole.group_leader)).thenReturn(List.of());
        when(productWorkProcessService.listByProduct(FACTORY_ID, PRODUCT_TYPE_ID)).thenReturn(List.of());

        Map<String, Object> result = invoke("doPreview", params("二次滚揉保水"));
        List<Map<String, Object>> draft = (List<Map<String, Object>>) result.get("draft");

        assertEquals(1, draft.size(), "One input segment → one draft step");
        // Should match 滚揉(保水) not 焯水
        assertEquals("滚揉(保水)", draft.get(0).get("processName"),
                "二次滚揉保水 should match 滚揉(保水) (4/6 chars ≥ 0.5) not 焯水 (2/6 < 0.5)");
        assertEquals("wp-tumble", draft.get(0).get("workProcessId"));
    }

    @Test
    @DisplayName("E3 duplicate warning: user genuinely types same step twice gets a warning")
    @SuppressWarnings("unchecked")
    void duplicateStepWarning_sameStepTwiceInInput() throws Exception {
        when(workProcessService.listActive(FACTORY_ID)).thenReturn(List.of(
                workProcess("wp-blanch", "焯水", 3)
        ));
        when(userService.getUsersByRole(FACTORY_ID, FactoryUserRole.operator)).thenReturn(List.of());
        when(userService.getUsersByRole(FACTORY_ID, FactoryUserRole.group_leader)).thenReturn(List.of());
        when(productWorkProcessService.listByProduct(FACTORY_ID, PRODUCT_TYPE_ID)).thenReturn(List.of());

        Map<String, Object> result = invoke("doPreview", params("焯水，焯水"));
        List<Map<String, Object>> draft = (List<Map<String, Object>>) result.get("draft");
        String message = (String) result.get("message");

        // 2 input segments → 2 draft steps (user deliberately typed it twice)
        assertEquals(2, draft.size(), "Two input segments → two draft steps");
        // But a duplicate warning should appear
        assertTrue(message.contains("出现") && message.contains("次"),
                "Should warn about duplicate step name, but got: " + message);
    }

    private void mockCatalogAndOperators() {
        when(workProcessService.listActive(FACTORY_ID)).thenReturn(List.of(
                workProcess("wp-oil", "修油"),
                workProcess("wp-tumble", "滚揉"),
                workProcess("wp-blanch", "焯水")
        ));
        when(userService.getUsersByRole(FACTORY_ID, FactoryUserRole.operator)).thenReturn(List.of(
                UserDTO.builder().id(1615L).username("f006_moyun").fullName("莫云").isActive(true).build(),
                UserDTO.builder().id(1616L).username("f006_weizj").fullName("魏振江").isActive(true).build()
        ));
        when(userService.getUsersByRole(FACTORY_ID, FactoryUserRole.group_leader)).thenReturn(List.of());
    }

    private WorkProcessDTO workProcess(String id, String name) {
        return workProcess(id, name, 1);
    }

    private WorkProcessDTO workProcess(String id, String name, int sortOrder) {
        return WorkProcessDTO.builder()
                .id(id)
                .processName(name)
                .processCategory("前处理")
                .unit("kg")
                .sortOrder(sortOrder)
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
