package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.QualityInspectionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/** Unit tests for {@link QualityCheckSummaryTool} (Sprint 8 P4c + Sprint 9 P1.1). */
@ExtendWith(MockitoExtension.class)
class QualityCheckSummaryToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private QualityCheckSummaryTool tool;

    @Mock
    private MaterialBatchRepository materialBatchRepository;

    @Mock
    private QualityInspectionRepository qualityInspectionRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-QCS-01: metadata — name + required + description")
    void metadata() {
        assertEquals("quality_check_summary", tool.getToolName());
        assertTrue(tool.getDescription().contains("质检"));
        assertTrue(tool.getDescription().contains("LLM 触发"));
        assertEquals(java.util.List.of("batchNumber"), tool.getRequiredParameters());
    }

    @Test
    @DisplayName("UT-QCS-02: batch not found — NOT_FOUND status + actionHint")
    @SuppressWarnings("unchecked")
    void batchNotFound() throws Exception {
        when(materialBatchRepository.findByFactoryIdAndBatchNumber(FACTORY_ID, "B-X"))
                .thenReturn(Optional.empty());

        Map<String, Object> result = invoke("doExecute", FACTORY_ID,
                Map.of("batchNumber", "B-X"), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals("NOT_FOUND", data.get("overallStatus"));
        assertEquals(false, data.get("batchFound"));
        assertNotNull(data.get("actionHint"));
    }

    @Test
    @DisplayName("UT-QCS-03: batch found but no inspections — NO_INSPECTION + actionHint")
    @SuppressWarnings("unchecked")
    void batchFoundNoInspection() throws Exception {
        MaterialBatch batch = new MaterialBatch();
        batch.setId("mb-1");
        batch.setFactoryId(FACTORY_ID);
        batch.setBatchNumber("B-Y");
        batch.setStatus(MaterialBatchStatus.INSPECTING);
        when(materialBatchRepository.findByFactoryIdAndBatchNumber(FACTORY_ID, "B-Y"))
                .thenReturn(Optional.of(batch));
        // Sprint 9 P1.1: 显式 mock empty material_batch_id 查询
        when(qualityInspectionRepository.findByFactoryIdAndMaterialBatchId(FACTORY_ID, "mb-1"))
                .thenReturn(List.of());

        Map<String, Object> result = invoke("doExecute", FACTORY_ID,
                Map.of("batchNumber", "B-Y"), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(true, data.get("batchFound"));
        assertEquals("NO_INSPECTION", data.get("overallStatus"));
        assertEquals(0, data.get("inspectionCount"));
        assertEquals("NO_INSPECTION", data.get("inspectionSource"));
        assertNotNull(data.get("actionHint"));
        assertEquals("INSPECTING", data.get("currentStatus"));
        // actionHint 应含 material_batch_id 引导
        assertTrue(((String) data.get("actionHint")).contains("mb-1"));
    }

    @Test
    @DisplayName("UT-QCS-04: Sprint 9 — found inspections via material_batch_id, all PASS")
    @SuppressWarnings("unchecked")
    void inspectionsFoundAllPass() throws Exception {
        MaterialBatch batch = new MaterialBatch();
        batch.setId("mb-pass");
        batch.setFactoryId(FACTORY_ID);
        batch.setBatchNumber("B-PASS");
        batch.setStatus(MaterialBatchStatus.INSPECTING);
        when(materialBatchRepository.findByFactoryIdAndBatchNumber(FACTORY_ID, "B-PASS"))
                .thenReturn(Optional.of(batch));

        QualityInspection insp1 = new QualityInspection();
        insp1.setId("insp-1");
        insp1.setFactoryId(FACTORY_ID);
        insp1.setMaterialBatchId("mb-pass");
        insp1.setProductionBatchId(101L);
        insp1.setInspectorId(7L);
        insp1.setInspectionDate(LocalDate.of(2026, 5, 21));
        insp1.setSampleSize(new BigDecimal("100"));
        insp1.setPassCount(new BigDecimal("98"));
        insp1.setFailCount(new BigDecimal("2"));
        insp1.setPassRate(new BigDecimal("98.00"));
        insp1.setResult("PASS");
        insp1.setNotes("符合 GB 4789.2");

        when(qualityInspectionRepository
                .findByFactoryIdAndMaterialBatchId(FACTORY_ID, "mb-pass"))
                .thenReturn(List.of(insp1));

        Map<String, Object> result = invoke("doExecute", FACTORY_ID,
                Map.of("batchNumber", "B-PASS"), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(true, data.get("batchFound"));
        assertEquals("PASSED", data.get("overallStatus"));
        assertEquals("MATERIAL_BATCH", data.get("inspectionSource"));
        assertEquals(1, data.get("inspectionCount"));
        assertEquals(1, data.get("passCount"));
        assertEquals(0, data.get("failCount"));
        assertEquals(new BigDecimal("100"), data.get("totalSample"));
    }

    @Test
    @DisplayName("UT-QCS-05: Sprint 9 — found inspections via material_batch_id, FAIL detected")
    @SuppressWarnings("unchecked")
    void inspectionsFoundWithFail() throws Exception {
        MaterialBatch batch = new MaterialBatch();
        batch.setId("mb-fail");
        batch.setFactoryId(FACTORY_ID);
        batch.setBatchNumber("B-FAIL");
        batch.setStatus(MaterialBatchStatus.INSPECTING);
        when(materialBatchRepository.findByFactoryIdAndBatchNumber(FACTORY_ID, "B-FAIL"))
                .thenReturn(Optional.of(batch));

        QualityInspection insp1 = new QualityInspection();
        insp1.setId("insp-2a");
        insp1.setFactoryId(FACTORY_ID);
        insp1.setMaterialBatchId("mb-fail");
        insp1.setProductionBatchId(102L);
        insp1.setInspectorId(7L);
        insp1.setInspectionDate(LocalDate.of(2026, 5, 21));
        insp1.setSampleSize(new BigDecimal("50"));
        insp1.setPassCount(new BigDecimal("40"));
        insp1.setFailCount(new BigDecimal("10"));
        insp1.setPassRate(new BigDecimal("80.00"));
        insp1.setResult("FAIL");

        QualityInspection insp2 = new QualityInspection();
        insp2.setId("insp-2b");
        insp2.setFactoryId(FACTORY_ID);
        insp2.setMaterialBatchId("mb-fail");
        insp2.setProductionBatchId(102L);
        insp2.setInspectorId(7L);
        insp2.setInspectionDate(LocalDate.of(2026, 5, 21));
        insp2.setSampleSize(new BigDecimal("50"));
        insp2.setPassCount(new BigDecimal("50"));
        insp2.setFailCount(new BigDecimal("0"));
        insp2.setPassRate(new BigDecimal("100.00"));
        insp2.setResult("PASS");

        when(qualityInspectionRepository
                .findByFactoryIdAndMaterialBatchId(FACTORY_ID, "mb-fail"))
                .thenReturn(List.of(insp1, insp2));

        Map<String, Object> result = invoke("doExecute", FACTORY_ID,
                Map.of("batchNumber", "B-FAIL"), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(true, data.get("batchFound"));
        // 含 FAIL → overallStatus = FAILED
        assertEquals("FAILED", data.get("overallStatus"));
        assertEquals(2, data.get("inspectionCount"));
        assertEquals(1, data.get("passCount"));
        assertEquals(1, data.get("failCount"));
        assertEquals("MATERIAL_BATCH", data.get("inspectionSource"));
    }

    // ── helpers ──
    private Map<String, Object> ctx() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("factoryId", FACTORY_ID);
        ctx.put("userId", 1L);
        return ctx;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invoke(String name, String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        var method = findMethod(tool.getClass(), name);
        method.setAccessible(true);
        try {
            return (Map<String, Object>) method.invoke(tool, factoryId, params, context);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            if (ite.getCause() instanceof RuntimeException re) throw re;
            if (ite.getCause() instanceof Exception ee) throw ee;
            throw ite;
        }
    }

    private java.lang.reflect.Method findMethod(Class<?> clazz, String name) {
        while (clazz != null) {
            for (var m : clazz.getDeclaredMethods()) {
                if (m.getName().equals(name)) return m;
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
