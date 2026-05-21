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

/** Unit tests for {@link SupplierCanOrderCheckTool} (Sprint 9 P2.C Phase B PO gate). */
@ExtendWith(MockitoExtension.class)
class SupplierCanOrderCheckToolTest {

    private static final String FACTORY_ID = "F006";
    private static final String SUPPLIER_ID = "sup-uuid-123";

    @InjectMocks
    private SupplierCanOrderCheckTool tool;

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
    @DisplayName("UT-SCO-01: metadata + READ default")
    void metadata() {
        assertEquals("supplier_can_order_check", tool.getToolName());
        // 默认 READ (没有 _create / _delete / _update 后缀)
        assertEquals(com.cretas.aims.ai.tool.ToolExecutor.ActionType.READ, tool.getActionType());
    }

    @Test
    @DisplayName("UT-SCO-02: supplier missing → canOrder=false BLOCKING")
    @SuppressWarnings("unchecked")
    void supplierMissing() throws Exception {
        when(supplierRepository.findByIdAndFactoryId(anyString(), anyString()))
                .thenReturn(Optional.empty());
        Map<String, Object> result = invoke("doExecute", FACTORY_ID,
                Map.of("supplierId", "missing"), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(false, data.get("canOrder"));
        assertEquals("BLOCKING", data.get("severity"));
        assertEquals("SUPPLIER_NOT_FOUND", data.get("reason"));
    }

    @Test
    @DisplayName("UT-SCO-03: all mandatory VALID + no EXPIRING → canOrder=true OK")
    @SuppressWarnings("unchecked")
    void allValid() throws Exception {
        Supplier sup = mkSupplier();
        when(supplierRepository.findByIdAndFactoryId(SUPPLIER_ID, FACTORY_ID))
                .thenReturn(Optional.of(sup));

        when(qualificationRepository.findByFactoryIdAndSupplierIdAndStatusIn(
                anyString(), anyString(), anyList()))
                .thenReturn(List.of(
                        mkQual("SC_LICENSE", "VALID", LocalDate.now().plusYears(1)),
                        mkQual("BUSINESS_LICENSE", "VALID", LocalDate.now().plusYears(2))
                ));

        Map<String, Object> result = invoke("doExecute", FACTORY_ID,
                Map.of("supplierId", SUPPLIER_ID), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals(true, data.get("canOrder"));
        assertEquals("OK", data.get("severity"));
        List<Map<String, Object>> blockingIssues = (List<Map<String, Object>>) data.get("blockingIssues");
        assertTrue(blockingIssues.isEmpty());
    }

    @Test
    @DisplayName("UT-SCO-04: SC_LICENSE EXPIRED → canOrder=false BLOCKING")
    @SuppressWarnings("unchecked")
    void scLicenseExpired() throws Exception {
        Supplier sup = mkSupplier();
        when(supplierRepository.findByIdAndFactoryId(SUPPLIER_ID, FACTORY_ID))
                .thenReturn(Optional.of(sup));

        when(qualificationRepository.findByFactoryIdAndSupplierIdAndStatusIn(
                anyString(), anyString(), anyList()))
                .thenReturn(List.of(
                        mkQual("SC_LICENSE", "EXPIRED", LocalDate.now().minusDays(5)),
                        mkQual("BUSINESS_LICENSE", "VALID", LocalDate.now().plusYears(1))
                ));

        Map<String, Object> result = invoke("doExecute", FACTORY_ID,
                Map.of("supplierId", SUPPLIER_ID), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals(false, data.get("canOrder"));
        assertEquals("BLOCKING", data.get("severity"));
        List<Map<String, Object>> blockingIssues = (List<Map<String, Object>>) data.get("blockingIssues");
        assertEquals(1, blockingIssues.size());
        assertEquals("SC_LICENSE", blockingIssues.get(0).get("qualificationType"));
    }

    @Test
    @DisplayName("UT-SCO-05: BUSINESS_LICENSE MISSING → canOrder=false BLOCKING with MISSING reason")
    @SuppressWarnings("unchecked")
    void businessLicenseMissing() throws Exception {
        Supplier sup = mkSupplier();
        when(supplierRepository.findByIdAndFactoryId(SUPPLIER_ID, FACTORY_ID))
                .thenReturn(Optional.of(sup));

        when(qualificationRepository.findByFactoryIdAndSupplierIdAndStatusIn(
                anyString(), anyString(), anyList()))
                .thenReturn(List.of(
                        mkQual("SC_LICENSE", "VALID", LocalDate.now().plusYears(1))
                        // BUSINESS_LICENSE 缺
                ));

        Map<String, Object> result = invoke("doExecute", FACTORY_ID,
                Map.of("supplierId", SUPPLIER_ID), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals(false, data.get("canOrder"));
        List<Map<String, Object>> blockingIssues = (List<Map<String, Object>>) data.get("blockingIssues");
        assertEquals(1, blockingIssues.size());
        assertEquals("MISSING", blockingIssues.get(0).get("reason"));
    }

    @Test
    @DisplayName("UT-SCO-06: VALID mandatory + EXPIRING within 7 days → canOrder=true WARNING")
    @SuppressWarnings("unchecked")
    void warningExpiringSoon() throws Exception {
        Supplier sup = mkSupplier();
        when(supplierRepository.findByIdAndFactoryId(SUPPLIER_ID, FACTORY_ID))
                .thenReturn(Optional.of(sup));

        when(qualificationRepository.findByFactoryIdAndSupplierIdAndStatusIn(
                anyString(), anyString(), anyList()))
                .thenReturn(List.of(
                        // SC_LICENSE VALID but only 3 days
                        mkQual("SC_LICENSE", "EXPIRING", LocalDate.now().plusDays(3)),
                        mkQual("BUSINESS_LICENSE", "VALID", LocalDate.now().plusYears(1))
                ));

        Map<String, Object> result = invoke("doExecute", FACTORY_ID,
                Map.of("supplierId", SUPPLIER_ID), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals(true, data.get("canOrder"));
        assertEquals("WARNING", data.get("severity"));
        List<Map<String, Object>> warnings = (List<Map<String, Object>>) data.get("warnings");
        assertEquals(1, warnings.size());
    }

    @Test
    @DisplayName("UT-SCO-07: REVOKED mandatory → canOrder=false BLOCKING")
    @SuppressWarnings("unchecked")
    void revokedBlocks() throws Exception {
        Supplier sup = mkSupplier();
        when(supplierRepository.findByIdAndFactoryId(SUPPLIER_ID, FACTORY_ID))
                .thenReturn(Optional.of(sup));

        when(qualificationRepository.findByFactoryIdAndSupplierIdAndStatusIn(
                anyString(), anyString(), anyList()))
                .thenReturn(List.of(
                        mkQual("SC_LICENSE", "REVOKED", LocalDate.now().plusYears(1)),
                        mkQual("BUSINESS_LICENSE", "VALID", LocalDate.now().plusYears(1))
                ));

        Map<String, Object> result = invoke("doExecute", FACTORY_ID,
                Map.of("supplierId", SUPPLIER_ID), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals(false, data.get("canOrder"));
    }

    // ── helpers ──
    private Supplier mkSupplier() {
        Supplier s = new Supplier();
        s.setId(SUPPLIER_ID);
        s.setName("张三食材公司");
        s.setFactoryId(FACTORY_ID);
        return s;
    }

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
