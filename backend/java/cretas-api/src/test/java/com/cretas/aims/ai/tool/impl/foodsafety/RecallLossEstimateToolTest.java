package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ShipmentRecord;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.foodsafety.RecallEvent;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ShipmentRecordRepository;
import com.cretas.aims.repository.foodsafety.RecallEventRepository;
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
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link RecallLossEstimateTool} (Sprint 8 P3 Phase B). */
@ExtendWith(MockitoExtension.class)
class RecallLossEstimateToolTest {

    private static final String FACTORY_ID = "F006";
    private static final Long EVENT_ID = 100L;

    @InjectMocks
    private RecallLossEstimateTool tool;

    @Mock
    private RecallEventRepository recallEventRepository;

    @Mock
    private MaterialBatchRepository materialBatchRepository;

    @Mock
    private ShipmentRecordRepository shipmentRecordRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-RLE-01: metadata")
    void metadata() {
        assertEquals("recall_loss_estimate", tool.getToolName());
    }

    @Test
    @DisplayName("UT-RLE-02: doExecute — sums inventory + returns + admin + 20% brand loss, updates event")
    @SuppressWarnings("unchecked")
    void aggregatesAllLoss() throws Exception {
        RecallEvent event = RecallEvent.builder()
                .id(EVENT_ID).factoryId(FACTORY_ID).eventCode("RECALL-1")
                .triggerReason("X").triggerTime(LocalDateTime.now())
                .triggeredByUserId(1L).affectedProductCategory("Y")
                .build();
        when(recallEventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));

        // 冻结库存批次 — totalCost = unitPrice * receiptQuantity = 100 * 50 = 5000
        MaterialBatch mb = new MaterialBatch();
        mb.setBatchNumber("B-1");
        mb.setStatus(MaterialBatchStatus.FROZEN);
        mb.setReceiptQuantity(new BigDecimal("50"));
        mb.setUnitPrice(new BigDecimal("100"));
        when(materialBatchRepository.findByFactoryIdAndBatchNumber(FACTORY_ID, "B-1"))
                .thenReturn(Optional.of(mb));

        // 客户退货预估 — 1 笔 ¥2000
        ShipmentRecord s = new ShipmentRecord();
        s.setTotalAmount(new BigDecimal("2000"));
        when(shipmentRecordRepository.findByFactoryIdAndBatchNumber(FACTORY_ID, "B-1"))
                .thenReturn(List.of(s));

        Map<String, Object> params = Map.of(
                "recallEventId", EVENT_ID,
                "batchNumbers", List.of("B-1"));
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        // 冻结库存: 5000, 退货: 2000, 行政: 5000 = 12000 subtotal
        // 品牌损失: 12000 * 0.20 = 2400
        // 总: 12000 + 2400 = 14400
        BigDecimal totalLoss = (BigDecimal) data.get("totalEstimatedLoss");
        assertEquals(0, totalLoss.compareTo(new BigDecimal("14400.00")));

        BigDecimal frozenVal = (BigDecimal) data.get("frozenInventoryValue");
        assertEquals(0, frozenVal.compareTo(new BigDecimal("5000.00")));
        BigDecimal retVal = (BigDecimal) data.get("customerReturnEstimate");
        assertEquals(0, retVal.compareTo(new BigDecimal("2000.00")));
        BigDecimal brandLoss = (BigDecimal) data.get("brandLoss");
        assertEquals(0, brandLoss.compareTo(new BigDecimal("2400.00")));

        verify(recallEventRepository, times(1)).save(any(RecallEvent.class));
        // event.estimatedLoss 被更新
        assertEquals(0, event.getEstimatedLoss().compareTo(new BigDecimal("14400.00")));
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
