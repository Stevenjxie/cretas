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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** Unit tests for {@link SupplierQualificationLookupTool} (Sprint 9 P2.C). */
@ExtendWith(MockitoExtension.class)
class SupplierQualificationLookupToolTest {

    private static final String FACTORY_ID = "F006";
    private static final String SUPPLIER_ID = "sup-uuid-123";

    @InjectMocks
    private SupplierQualificationLookupTool tool;

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
    @DisplayName("UT-SQL-01: metadata + READ")
    void metadata() {
        assertEquals("supplier_qualification_lookup", tool.getToolName());
        assertEquals(com.cretas.aims.ai.tool.ToolExecutor.ActionType.READ, tool.getActionType());
    }

    @Test
    @DisplayName("UT-SQL-02: mixed-status quals → status counts correct, type filter optional")
    @SuppressWarnings("unchecked")
    void mixedStatuses() throws Exception {
        Supplier sup = new Supplier();
        sup.setId(SUPPLIER_ID);
        sup.setName("张三");
        when(supplierRepository.findByIdAndFactoryId(SUPPLIER_ID, FACTORY_ID))
                .thenReturn(Optional.of(sup));

        when(qualificationRepository.findByFactoryIdAndSupplierIdAndStatusIn(
                anyString(), anyString(), anyList()))
                .thenReturn(List.of(
                        mkQual("SC_LICENSE", "VALID", LocalDate.now().plusYears(1)),
                        mkQual("BUSINESS_LICENSE", "EXPIRING", LocalDate.now().plusDays(20)),
                        mkQual("HACCP_CERT", "EXPIRED", LocalDate.now().minusDays(5)),
                        mkQual("ISO22000", "REVOKED", LocalDate.now().plusYears(2))
                ));

        Map<String, Object> result = invoke("doExecute", FACTORY_ID,
                Map.of("supplierId", SUPPLIER_ID), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals(4, data.get("totalCount"));
        assertEquals(1, data.get("validCount"));
        assertEquals(1, data.get("expiringCount"));
        assertEquals(1, data.get("expiredCount"));
        assertEquals(1, data.get("revokedCount"));
        assertEquals("张三", data.get("supplierName"));
    }

    @Test
    @DisplayName("UT-SQL-03: filter by qualificationType reduces result set")
    @SuppressWarnings("unchecked")
    void filterByType() throws Exception {
        Supplier sup = new Supplier();
        sup.setId(SUPPLIER_ID);
        sup.setName("张三");
        when(supplierRepository.findByIdAndFactoryId(SUPPLIER_ID, FACTORY_ID))
                .thenReturn(Optional.of(sup));

        when(qualificationRepository.findByFactoryIdAndSupplierIdAndStatusIn(
                anyString(), anyString(), anyList()))
                .thenReturn(List.of(
                        mkQual("SC_LICENSE", "VALID", LocalDate.now().plusYears(1)),
                        mkQual("BUSINESS_LICENSE", "VALID", LocalDate.now().plusYears(1)),
                        mkQual("HACCP_CERT", "VALID", LocalDate.now().plusYears(1))
                ));

        Map<String, Object> result = invoke("doExecute", FACTORY_ID,
                Map.of("supplierId", SUPPLIER_ID, "qualificationType", "SC_LICENSE"), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(1, data.get("totalCount"));
        List<Map<String, Object>> quals = (List<Map<String, Object>>) data.get("qualifications");
        assertEquals("SC_LICENSE", quals.get(0).get("qualificationType"));
    }

    @Test
    @DisplayName("UT-SQL-04: empty result → friendly message")
    @SuppressWarnings("unchecked")
    void empty() throws Exception {
        Supplier sup = new Supplier();
        sup.setId(SUPPLIER_ID);
        sup.setName("空供应商");
        when(supplierRepository.findByIdAndFactoryId(SUPPLIER_ID, FACTORY_ID))
                .thenReturn(Optional.of(sup));
        when(qualificationRepository.findByFactoryIdAndSupplierIdAndStatusIn(
                anyString(), anyString(), anyList()))
                .thenReturn(List.of());

        Map<String, Object> result = invoke("doExecute", FACTORY_ID,
                Map.of("supplierId", SUPPLIER_ID), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(0, data.get("totalCount"));
    }

    // ── helpers ──
    private SupplierQualification mkQual(String type, String status, LocalDate expiry) {
        SupplierQualification q = new SupplierQualification();
        q.setFactoryId(FACTORY_ID);
        q.setSupplierId(SUPPLIER_ID);
        q.setQualificationType(type);
        q.setCertificateNumber(type + "-001");
        q.setStatus(status);
        q.setExpiryDate(expiry);
        q.setIssueDate(LocalDate.now().minusYears(1));
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
