package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.foodsafety.SupplierQualification;
import com.cretas.aims.exception.BusinessException;
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
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link SupplierQualificationCreateTool} (Sprint 9 P2.C Phase B). */
@ExtendWith(MockitoExtension.class)
class SupplierQualificationCreateToolTest {

    private static final String FACTORY_ID = "F006";
    private static final String SUPPLIER_ID = "sup-uuid-123";

    @InjectMocks
    private SupplierQualificationCreateTool tool;

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
    @DisplayName("UT-SQC-01: metadata + supportsPreview=true + WRITE")
    void metadata() {
        assertEquals("supplier_qualification_create", tool.getToolName());
        assertTrue(tool.supportsPreview());
        assertEquals(com.cretas.aims.ai.tool.ToolExecutor.ActionType.WRITE, tool.getActionType());
        assertEquals(com.cretas.aims.ai.tool.ToolExecutor.RiskLevel.MEDIUM, tool.getRiskLevel());
    }

    @Test
    @DisplayName("UT-SQC-02: doPreview — valid input → canDo=true with initialStatus=VALID")
    @SuppressWarnings("unchecked")
    void previewValid() throws Exception {
        Supplier sup = new Supplier();
        sup.setId(SUPPLIER_ID);
        sup.setName("张三食材公司");
        when(supplierRepository.findByIdAndFactoryId(SUPPLIER_ID, FACTORY_ID))
                .thenReturn(Optional.of(sup));
        when(qualificationRepository
                .countByFactoryIdAndSupplierIdAndQualificationTypeAndCertificateNumber(
                        anyString(), anyString(), anyString(), anyString()))
                .thenReturn(0L);

        Map<String, Object> params = Map.of(
                "supplierId", SUPPLIER_ID,
                "qualificationType", "SC_LICENSE",
                "certificateNumber", "SC10532010300001",
                "issuingAuthority", "国家市场监督管理总局",
                "issueDate", "2024-01-01",
                "expiryDate", LocalDate.now().plusYears(2).toString()
        );
        Map<String, Object> result = invoke("doPreview", FACTORY_ID, params, ctx());

        assertEquals("PREVIEW", result.get("status"));
        assertEquals(true, result.get("canDo"));
        assertEquals("VALID", result.get("initialStatus"));
        assertEquals("张三食材公司", result.get("supplierName"));
    }

    @Test
    @DisplayName("UT-SQC-03: doPreview — invalid qualificationType enum → canDo=false")
    @SuppressWarnings("unchecked")
    void previewInvalidEnum() throws Exception {
        Map<String, Object> params = Map.of(
                "supplierId", SUPPLIER_ID,
                "qualificationType", "BOGUS_TYPE",
                "certificateNumber", "X",
                "issuingAuthority", "X",
                "issueDate", "2024-01-01",
                "expiryDate", "2027-01-01"
        );
        Map<String, Object> result = invoke("doPreview", FACTORY_ID, params, ctx());
        assertEquals(false, result.get("canDo"));
    }

    @Test
    @DisplayName("UT-SQC-04: doPreview — supplier not found → canDo=false")
    @SuppressWarnings("unchecked")
    void previewSupplierMissing() throws Exception {
        when(supplierRepository.findByIdAndFactoryId(anyString(), anyString()))
                .thenReturn(Optional.empty());

        Map<String, Object> params = Map.of(
                "supplierId", "missing",
                "qualificationType", "SC_LICENSE",
                "certificateNumber", "X",
                "issuingAuthority", "X",
                "issueDate", "2024-01-01",
                "expiryDate", "2027-01-01"
        );
        Map<String, Object> result = invoke("doPreview", FACTORY_ID, params, ctx());
        assertEquals(false, result.get("canDo"));
    }

    @Test
    @DisplayName("UT-SQC-05: doPreview — duplicate cert → status=DUPLICATE, canDo=false")
    @SuppressWarnings("unchecked")
    void previewDuplicate() throws Exception {
        Supplier sup = new Supplier();
        sup.setId(SUPPLIER_ID);
        sup.setName("Test");
        when(supplierRepository.findByIdAndFactoryId(SUPPLIER_ID, FACTORY_ID))
                .thenReturn(Optional.of(sup));
        when(qualificationRepository
                .countByFactoryIdAndSupplierIdAndQualificationTypeAndCertificateNumber(
                        anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1L);

        Map<String, Object> params = Map.of(
                "supplierId", SUPPLIER_ID,
                "qualificationType", "SC_LICENSE",
                "certificateNumber", "SC10532010300001",
                "issuingAuthority", "X",
                "issueDate", "2024-01-01",
                "expiryDate", "2027-01-01"
        );
        Map<String, Object> result = invoke("doPreview", FACTORY_ID, params, ctx());
        assertEquals("DUPLICATE", result.get("status"));
        assertEquals(false, result.get("canDo"));
    }

    @Test
    @DisplayName("UT-SQC-06: doExecute — valid input → save called, status=VALID, returned message")
    @SuppressWarnings("unchecked")
    void executeValid() throws Exception {
        Supplier sup = new Supplier();
        sup.setId(SUPPLIER_ID);
        sup.setName("张三食材公司");
        when(supplierRepository.findByIdAndFactoryId(SUPPLIER_ID, FACTORY_ID))
                .thenReturn(Optional.of(sup));
        when(qualificationRepository
                .countByFactoryIdAndSupplierIdAndQualificationTypeAndCertificateNumber(
                        anyString(), anyString(), anyString(), anyString()))
                .thenReturn(0L);
        when(qualificationRepository.save(any(SupplierQualification.class)))
                .thenAnswer(inv -> {
                    SupplierQualification q = inv.getArgument(0);
                    q.setId(42L);
                    return q;
                });

        Map<String, Object> params = Map.of(
                "supplierId", SUPPLIER_ID,
                "qualificationType", "SC_LICENSE",
                "certificateNumber", "SC10532010300001",
                "issuingAuthority", "国家市场监督管理总局",
                "issueDate", "2024-01-01",
                "expiryDate", LocalDate.now().plusYears(2).toString()
        );
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals(42L, data.get("id"));
        assertEquals("VALID", data.get("status"));
        verify(qualificationRepository, times(1)).save(any(SupplierQualification.class));
    }

    @Test
    @DisplayName("UT-SQC-07: doExecute — expiry < issue → BusinessException 400")
    void executeExpiryBeforeIssue() {
        Supplier sup = new Supplier();
        sup.setId(SUPPLIER_ID);
        sup.setName("Test");
        when(supplierRepository.findByIdAndFactoryId(SUPPLIER_ID, FACTORY_ID))
                .thenReturn(Optional.of(sup));
        when(qualificationRepository
                .countByFactoryIdAndSupplierIdAndQualificationTypeAndCertificateNumber(
                        anyString(), anyString(), anyString(), anyString()))
                .thenReturn(0L);

        Map<String, Object> params = Map.of(
                "supplierId", SUPPLIER_ID,
                "qualificationType", "SC_LICENSE",
                "certificateNumber", "SC1",
                "issuingAuthority", "X",
                "issueDate", "2027-01-01",
                "expiryDate", "2024-01-01"
        );
        BusinessException ex = assertThrows(BusinessException.class,
                () -> invoke("doExecute", FACTORY_ID, params, ctx()));
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("UT-SQC-08: doExecute — duplicate → status=DUPLICATE, no save call")
    @SuppressWarnings("unchecked")
    void executeDuplicate() throws Exception {
        Supplier sup = new Supplier();
        sup.setId(SUPPLIER_ID);
        sup.setName("Test");
        when(supplierRepository.findByIdAndFactoryId(SUPPLIER_ID, FACTORY_ID))
                .thenReturn(Optional.of(sup));
        when(qualificationRepository
                .countByFactoryIdAndSupplierIdAndQualificationTypeAndCertificateNumber(
                        anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1L);

        Map<String, Object> params = Map.of(
                "supplierId", SUPPLIER_ID,
                "qualificationType", "SC_LICENSE",
                "certificateNumber", "SC1",
                "issuingAuthority", "X",
                "issueDate", "2024-01-01",
                "expiryDate", "2027-01-01"
        );
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        assertEquals("DUPLICATE", result.get("status"));
        verify(qualificationRepository, never()).save(any());
    }

    @Test
    @DisplayName("UT-SQC-09: computeInitialStatus — expiry day boundary cases")
    void computeInitialStatus() {
        LocalDate today = LocalDate.now();
        // > 30 days → VALID
        assertEquals("VALID", SupplierQualificationCreateTool.computeInitialStatus(today.plusDays(31)));
        // == 30 days → EXPIRING
        assertEquals("EXPIRING", SupplierQualificationCreateTool.computeInitialStatus(today.plusDays(30)));
        // == 7 days → EXPIRING
        assertEquals("EXPIRING", SupplierQualificationCreateTool.computeInitialStatus(today.plusDays(7)));
        // today → EXPIRING (not yet expired)
        assertEquals("EXPIRING", SupplierQualificationCreateTool.computeInitialStatus(today));
        // 1 day ago → EXPIRED
        assertEquals("EXPIRED", SupplierQualificationCreateTool.computeInitialStatus(today.minusDays(1)));
    }

    // ── helpers (mirror InventoryFreezeToolTest pattern) ──
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
