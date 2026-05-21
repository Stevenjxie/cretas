package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.foodsafety.SupplierQualification;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.foodsafety.SupplierQualificationRepository;
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
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** Unit tests for {@link SupplierQualificationQueryExpiringTool} (Sprint 9 P2.C). */
@ExtendWith(MockitoExtension.class)
class SupplierQualificationQueryExpiringToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private SupplierQualificationQueryExpiringTool tool;

    @Mock
    private SupplierQualificationRepository qualificationRepository;

    @Mock
    private SupplierRepository supplierRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-SQE-01: metadata + READ + no required params")
    void metadata() {
        assertEquals("supplier_qualification_query_expiring", tool.getToolName());
        assertEquals(com.cretas.aims.ai.tool.ToolExecutor.ActionType.READ, tool.getActionType());
    }

    @Test
    @DisplayName("UT-SQE-02: 3 buckets — EXPIRED ⛔, URGENT 🔴 (≤7d), WARNING 🟡 (8-30d)")
    @SuppressWarnings("unchecked")
    void threeBuckets() throws Exception {
        LocalDate today = LocalDate.now();

        // EXPIRED row
        SupplierQualification expired = mkQual("sup-1", "SC_LICENSE", "EXPIRED", today.minusDays(5));
        // URGENT (3 days)
        SupplierQualification urgent = mkQual("sup-2", "BUSINESS_LICENSE", "VALID", today.plusDays(3));
        // WARNING (20 days)
        SupplierQualification warning = mkQual("sup-3", "HACCP_CERT", "VALID", today.plusDays(20));

        when(qualificationRepository.findByFactoryIdAndExpiryDateBeforeAndStatusIn(
                anyString(), any(), anyList()))
                .thenReturn(List.of(expired));
        when(qualificationRepository.findExpiringWithin(anyString(), any(), any()))
                .thenReturn(List.of(urgent, warning));

        Supplier sup = new Supplier();
        sup.setName("Test Supplier");
        when(supplierRepository.findByIdAndFactoryId(anyString(), anyString()))
                .thenReturn(Optional.of(sup));

        Map<String, Object> params = new HashMap<>();
        params.put("daysAhead", 30);
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals(1, data.get("expiredCount"));
        assertEquals(1, data.get("urgentCount"));
        assertEquals(1, data.get("warningCount"));
        assertEquals(3, data.get("totalCount"));
    }

    @Test
    @DisplayName("UT-SQE-03: empty result → totalCount=0, friendly message")
    @SuppressWarnings("unchecked")
    void empty() throws Exception {
        when(qualificationRepository.findByFactoryIdAndExpiryDateBeforeAndStatusIn(
                anyString(), any(), anyList()))
                .thenReturn(List.of());
        when(qualificationRepository.findExpiringWithin(anyString(), any(), any()))
                .thenReturn(List.of());

        Map<String, Object> params = new HashMap<>();
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(0, data.get("totalCount"));
    }

    @Test
    @DisplayName("UT-SQE-04: negative daysAhead → 默认 30")
    @SuppressWarnings("unchecked")
    void negativeDaysAheadDefaults() throws Exception {
        when(qualificationRepository.findByFactoryIdAndExpiryDateBeforeAndStatusIn(
                anyString(), any(), anyList()))
                .thenReturn(List.of());
        when(qualificationRepository.findExpiringWithin(anyString(), any(), any()))
                .thenReturn(List.of());

        Map<String, Object> params = new HashMap<>();
        params.put("daysAhead", -5);
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(30, data.get("daysAhead"));
    }

    // ── helpers ──
    private SupplierQualification mkQual(String supplierId, String type, String status,
            LocalDate expiry) {
        SupplierQualification q = new SupplierQualification();
        q.setFactoryId(FACTORY_ID);
        q.setSupplierId(supplierId);
        q.setQualificationType(type);
        q.setCertificateNumber(type + "-X");
        q.setStatus(status);
        q.setExpiryDate(expiry);
        return q;
    }

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
