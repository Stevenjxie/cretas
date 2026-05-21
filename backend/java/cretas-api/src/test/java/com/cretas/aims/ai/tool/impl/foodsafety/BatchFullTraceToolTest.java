package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.dto.traceability.TraceabilityDTO;
import com.cretas.aims.entity.ShipmentRecord;
import com.cretas.aims.repository.ShipmentRecordRepository;
import com.cretas.aims.service.TraceabilityService;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** Unit tests for {@link BatchFullTraceTool} (Sprint 8 P3 Phase B). */
@ExtendWith(MockitoExtension.class)
class BatchFullTraceToolTest {

    private static final String FACTORY_ID = "F006";
    private static final String BATCH = "B-20260518-A03";

    @InjectMocks
    private BatchFullTraceTool tool;

    @Mock
    private TraceabilityService traceabilityService;

    @Mock
    private ShipmentRecordRepository shipmentRecordRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-BFT-01: metadata")
    void metadata() {
        assertEquals("batch_full_trace", tool.getToolName());
    }

    @Test
    @DisplayName("UT-BFT-02: gathers affected customers from ShipmentRecord")
    @SuppressWarnings("unchecked")
    void gathersAffectedCustomers() throws Exception {
        when(traceabilityService.getFullTrace(anyString(), anyString()))
                .thenReturn(new TraceabilityDTO.FullTraceResponse());

        ShipmentRecord s1 = new ShipmentRecord();
        s1.setShipmentNumber("SH-1");
        s1.setCustomerId("c1");
        s1.setProductName("卤猪蹄");
        s1.setQuantity(new BigDecimal("10"));
        s1.setShipmentDate(LocalDate.now());
        ShipmentRecord s2 = new ShipmentRecord();
        s2.setShipmentNumber("SH-2");
        s2.setCustomerId("c2");
        s2.setQuantity(new BigDecimal("20"));
        s2.setShipmentDate(LocalDate.now());
        ShipmentRecord s3 = new ShipmentRecord();
        s3.setShipmentNumber("SH-3");
        s3.setCustomerId("c1");  // 重复 customer, 应去重
        s3.setQuantity(new BigDecimal("15"));
        s3.setShipmentDate(LocalDate.now());

        when(shipmentRecordRepository.findByFactoryIdAndBatchNumber(FACTORY_ID, BATCH))
                .thenReturn(List.of(s1, s2, s3));

        Map<String, Object> result = invoke("doExecute", FACTORY_ID, Map.of("batchNumber", BATCH), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals(2, data.get("affectedCustomerCount"));  // c1, c2 去重
        assertEquals(3, data.get("affectedShipmentCount"));
        List<String> custIds = (List<String>) data.get("affectedCustomerIds");
        assertTrue(custIds.contains("c1"));
        assertTrue(custIds.contains("c2"));
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
