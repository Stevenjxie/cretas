package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.ShipmentRecord;
import com.cretas.aims.entity.foodsafety.RecallAction;
import com.cretas.aims.entity.foodsafety.RecallEvent;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ShipmentRecordRepository;
import com.cretas.aims.repository.foodsafety.RecallActionRepository;
import com.cretas.aims.repository.foodsafety.RecallEventRepository;
import com.cretas.aims.service.notification.NotificationService;
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
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link CustomerNotifyBatchTool} (Sprint 8 P3 Phase B). */
@ExtendWith(MockitoExtension.class)
class CustomerNotifyBatchToolTest {

    private static final String FACTORY_ID = "F006";
    private static final String BATCH = "B-20260518-A03";
    private static final Long EVENT_ID = 100L;

    @InjectMocks
    private CustomerNotifyBatchTool tool;

    @Mock
    private ShipmentRecordRepository shipmentRecordRepository;
    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private RecallEventRepository recallEventRepository;
    @Mock
    private RecallActionRepository recallActionRepository;
    @Mock
    private NotificationService notificationService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-CN-01: metadata + supportsPreview + WRITE")
    void metadata() {
        assertEquals("customer_notify_batch", tool.getToolName());
        assertTrue(tool.supportsPreview());
        assertEquals(com.cretas.aims.ai.tool.ToolExecutor.ActionType.WRITE, tool.getActionType());
    }

    @Test
    @DisplayName("UT-CN-02: doPreview — customers found → canDo=true + smsDraft populated")
    @SuppressWarnings("unchecked")
    void previewWithCustomers() throws Exception {
        RecallEvent event = RecallEvent.builder()
                .id(EVENT_ID).factoryId(FACTORY_ID).eventCode("RECALL-20260518-001")
                .triggerReason("客户投诉拉肚子")
                .triggerTime(LocalDateTime.now())
                .build();
        when(recallEventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));
        when(recallActionRepository.findByRecallEventIdOrderByCreatedAtAsc(EVENT_ID))
                .thenReturn(List.of());

        ShipmentRecord s = new ShipmentRecord();
        s.setCustomerId("c1");
        when(shipmentRecordRepository.findByFactoryIdAndBatchNumber(FACTORY_ID, BATCH))
                .thenReturn(List.of(s));

        Customer c = new Customer();
        c.setId("c1");
        c.setName("鲜湘缘餐厅");
        lenient().when(customerRepository.findByIdAndFactoryId("c1", FACTORY_ID))
                .thenReturn(Optional.of(c));

        Map<String, Object> params = Map.of("batchNumber", BATCH, "recallEventId", EVENT_ID);
        Map<String, Object> result = invoke("doPreview", FACTORY_ID, params, ctx());

        assertEquals(true, result.get("canDo"));
        assertEquals(1, result.get("customerCount"));
        assertNotNull(result.get("smsDraft"));
    }

    @Test
    @DisplayName("UT-CN-03: doExecute — sends notification + saves RecallAction + updates event")
    @SuppressWarnings("unchecked")
    void executeSendsAndAudits() throws Exception {
        RecallEvent event = RecallEvent.builder()
                .id(EVENT_ID).factoryId(FACTORY_ID).eventCode("RECALL-20260518-001")
                .triggerReason("拉肚子").triggerTime(LocalDateTime.now())
                .status("INVESTIGATING")
                .build();
        when(recallEventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));
        when(recallActionRepository.findByRecallEventIdOrderByCreatedAtAsc(EVENT_ID))
                .thenReturn(List.of());
        when(recallActionRepository.save(any(RecallAction.class)))
                .thenAnswer(inv -> {
                    RecallAction a = inv.getArgument(0);
                    a.setId(999L);
                    return a;
                });

        ShipmentRecord s = new ShipmentRecord();
        s.setCustomerId("c1");
        when(shipmentRecordRepository.findByFactoryIdAndBatchNumber(FACTORY_ID, BATCH))
                .thenReturn(List.of(s));
        Customer c = new Customer();
        c.setId("c1");
        c.setName("鲜湘缘餐厅");
        lenient().when(customerRepository.findByIdAndFactoryId("c1", FACTORY_ID))
                .thenReturn(Optional.of(c));

        Map<String, Object> params = Map.of("batchNumber", BATCH, "recallEventId", EVENT_ID);
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals(1, data.get("sentCount"));
        verify(notificationService, times(1))
                .broadcastFactory(anyString(), anyString(), anyString());
        verify(recallActionRepository, times(1)).save(any(RecallAction.class));
        verify(recallEventRepository, times(1)).save(any(RecallEvent.class));
        assertEquals("NOTIFYING", event.getStatus());  // INVESTIGATING → NOTIFYING
    }

    @Test
    @DisplayName("UT-CN-04: doExecute — RecallEvent not found → BusinessException 404")
    void executeEventNotFound() {
        when(recallEventRepository.findById(anyLong())).thenReturn(Optional.empty());
        Map<String, Object> params = Map.of("batchNumber", BATCH, "recallEventId", 99999L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> invoke("doExecute", FACTORY_ID, params, ctx()));
        assertEquals(404, ex.getCode());
    }

    @Test
    @DisplayName("UT-CN-05: doExecute — already notified → ALREADY_NOTIFIED (idempotent)")
    @SuppressWarnings("unchecked")
    void executeAlreadyNotified() throws Exception {
        RecallEvent event = RecallEvent.builder()
                .id(EVENT_ID).factoryId(FACTORY_ID)
                .eventCode("RECALL-1").triggerReason("X").triggerTime(LocalDateTime.now())
                .build();
        when(recallEventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));

        RecallAction existing = RecallAction.builder()
                .actionType("NOTIFY_CUSTOMER").status("COMPLETED").build();
        when(recallActionRepository.findByRecallEventIdOrderByCreatedAtAsc(EVENT_ID))
                .thenReturn(List.of(existing));

        Map<String, Object> params = Map.of("batchNumber", BATCH, "recallEventId", EVENT_ID);
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());

        assertEquals("ALREADY_NOTIFIED", result.get("status"));
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
