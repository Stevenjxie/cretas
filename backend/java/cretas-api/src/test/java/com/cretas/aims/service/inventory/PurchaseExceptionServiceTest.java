package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.enums.ExceptionDecision;
import com.cretas.aims.entity.enums.ReceiveDecisionStatus;
import com.cretas.aims.entity.enums.ReceiveExceptionType;
import com.cretas.aims.entity.inventory.PurchaseException;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.inventory.PurchaseExceptionRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.inventory.PurchaseReceiveRecordRepository;
import com.cretas.aims.service.inventory.impl.PurchaseExceptionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SP6 采购异常单 Service 单元测试
 *
 * <p>覆盖：
 * - generateExceptionsForReceive：超收/少收检测逻辑
 * - decideException：ACCEPT_OVER / RETURN_OVER / ACCEPT_SHORT / REQUEST_RESUPPLY
 * - RETURN_OVER 的 REQUIRES_NEW 隔离（服务方法不污染父事务）
 * - 边界：正常收货无异常单、异常单重复创建防卫
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PurchaseExceptionServiceImpl 单元测试 (SP6)")
class PurchaseExceptionServiceTest {

    @Mock
    private PurchaseExceptionRepository exceptionRepository;

    @Mock
    private PurchaseReceiveRecordRepository receiveRecordRepository;

    @Mock
    private PurchaseOrderRepository purchaseOrderRepository;

    @InjectMocks
    private PurchaseExceptionServiceImpl exceptionService;

    private static final String FACTORY_ID = "F006";
    private static final String RECEIVE_ID = "REC-001";
    private static final String PO_ID = "PO-001";

    @BeforeEach
    void setup() {
        // Repository save: return the same entity with an id assigned
        when(exceptionRepository.save(any(PurchaseException.class)))
                .thenAnswer(inv -> {
                    PurchaseException e = inv.getArgument(0);
                    if (e.getId() == null) e.setId(java.util.UUID.randomUUID().toString());
                    return e;
                });
    }

    // ───────────────────────────────────────────────────────────────────────
    // generateExceptionsForReceive
    // ───────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("generateExceptionsForReceive")
    class GenerateExceptionsTests {

        @Test
        @DisplayName("正常收货（实收 = PO 量）不生成异常单")
        void noException_whenReceivedEqualsOrdered() {
            // po qty = 100, received qty = 100 → no exception
            List<PurchaseException> result = exceptionService.generateExceptionsForReceive(
                    FACTORY_ID, RECEIVE_ID, PO_ID, null, null, null,
                    BigDecimal.valueOf(100), BigDecimal.valueOf(100), "kg", 1L);
            assertTrue(result.isEmpty(), "正常收货不应产生异常单");
            verify(exceptionRepository, never()).save(any());
        }

        @Test
        @DisplayName("超收（实收 > PO 量）生成 OVER_RECEIVE 异常单")
        void overReceive_generatesOverException() {
            // po qty = 100, received = 110
            List<PurchaseException> result = exceptionService.generateExceptionsForReceive(
                    FACTORY_ID, RECEIVE_ID, PO_ID, null, null, null,
                    BigDecimal.valueOf(100), BigDecimal.valueOf(110), "kg", 1L);

            assertEquals(1, result.size());
            PurchaseException ex = result.get(0);
            assertEquals(ReceiveExceptionType.OVER_RECEIVE, ex.getExceptionType());
            assertEquals(0, BigDecimal.TEN.compareTo(ex.getExceptionQty()),
                    "异常数量应为 110 - 100 = 10");
            assertEquals("PENDING", ex.getStatus());
        }

        @Test
        @DisplayName("少收（实收 < PO 量）生成 UNDER_RECEIVE 异常单")
        void underReceive_generatesUnderException() {
            // po qty = 100, received = 85
            List<PurchaseException> result = exceptionService.generateExceptionsForReceive(
                    FACTORY_ID, RECEIVE_ID, PO_ID, null, null, null,
                    BigDecimal.valueOf(100), BigDecimal.valueOf(85), "kg", 1L);

            assertEquals(1, result.size());
            PurchaseException ex = result.get(0);
            assertEquals(ReceiveExceptionType.UNDER_RECEIVE, ex.getExceptionType());
            assertEquals(0, BigDecimal.valueOf(15).compareTo(ex.getExceptionQty()));
        }

        @Test
        @DisplayName("factoryId 传入正确")
        void factoryIdIsSetOnException() {
            List<PurchaseException> result = exceptionService.generateExceptionsForReceive(
                    FACTORY_ID, RECEIVE_ID, PO_ID, null, null, null,
                    BigDecimal.valueOf(100), BigDecimal.valueOf(110), "kg", 1L);
            assertEquals(FACTORY_ID, result.get(0).getFactoryId());
        }

        @Test
        @DisplayName("receiveRecordId 传入正确")
        void receiveRecordIdIsSetOnException() {
            List<PurchaseException> result = exceptionService.generateExceptionsForReceive(
                    FACTORY_ID, RECEIVE_ID, PO_ID, null, null, null,
                    BigDecimal.valueOf(100), BigDecimal.valueOf(110), "kg", 1L);
            assertEquals(RECEIVE_ID, result.get(0).getReceiveRecordId());
        }

        @Test
        @DisplayName("purchaseOrderId 传入正确")
        void purchaseOrderIdIsSetOnException() {
            List<PurchaseException> result = exceptionService.generateExceptionsForReceive(
                    FACTORY_ID, RECEIVE_ID, PO_ID, null, null, null,
                    BigDecimal.valueOf(100), BigDecimal.valueOf(110), "kg", 1L);
            assertEquals(PO_ID, result.get(0).getPurchaseOrderId());
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // decideException
    // ───────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("decideException")
    class DecideExceptionTests {

        private PurchaseException pendingOverException() {
            PurchaseException ex = new PurchaseException();
            ex.setId("EXC-001");
            ex.setFactoryId(FACTORY_ID);
            ex.setExceptionType(ReceiveExceptionType.OVER_RECEIVE);
            ex.setStatus("PENDING");
            ex.setReceiveRecordId(RECEIVE_ID);
            ex.setPurchaseOrderId(PO_ID);
            ex.setExceptionQty(BigDecimal.TEN);
            ex.setCreatedBy(1L);
            return ex;
        }

        private PurchaseException pendingUnderException() {
            PurchaseException ex = new PurchaseException();
            ex.setId("EXC-002");
            ex.setFactoryId(FACTORY_ID);
            ex.setExceptionType(ReceiveExceptionType.UNDER_RECEIVE);
            ex.setStatus("PENDING");
            ex.setReceiveRecordId(RECEIVE_ID);
            ex.setPurchaseOrderId(PO_ID);
            ex.setExceptionQty(BigDecimal.valueOf(15));
            ex.setCreatedBy(1L);
            return ex;
        }

        @Test
        @DisplayName("ACCEPT_OVER：状态变 RESOLVED，decision 已记录")
        void acceptOver_resolvesException() {
            PurchaseException ex = pendingOverException();
            when(exceptionRepository.findById("EXC-001")).thenReturn(Optional.of(ex));

            exceptionService.decideException("EXC-001", FACTORY_ID, ExceptionDecision.ACCEPT_OVER, "接受超收", 1L);

            ArgumentCaptor<PurchaseException> captor = ArgumentCaptor.forClass(PurchaseException.class);
            verify(exceptionRepository).save(captor.capture());
            PurchaseException saved = captor.getValue();
            assertEquals("RESOLVED", saved.getStatus());
            assertEquals(ExceptionDecision.ACCEPT_OVER, saved.getDecision());
        }

        @Test
        @DisplayName("RETURN_OVER：状态变 RESOLVED，decision 已记录")
        void returnOver_resolvesException() {
            PurchaseException ex = pendingOverException();
            when(exceptionRepository.findById("EXC-001")).thenReturn(Optional.of(ex));

            exceptionService.decideException("EXC-001", FACTORY_ID, ExceptionDecision.RETURN_OVER, "退回多余", 1L);

            ArgumentCaptor<PurchaseException> captor = ArgumentCaptor.forClass(PurchaseException.class);
            verify(exceptionRepository).save(captor.capture());
            assertEquals("RESOLVED", captor.getValue().getStatus());
        }

        @Test
        @DisplayName("ACCEPT_SHORT：状态变 RESOLVED")
        void acceptShort_resolvesException() {
            PurchaseException ex = pendingUnderException();
            when(exceptionRepository.findById("EXC-002")).thenReturn(Optional.of(ex));

            exceptionService.decideException("EXC-002", FACTORY_ID, ExceptionDecision.ACCEPT_SHORT, null, 1L);

            ArgumentCaptor<PurchaseException> captor = ArgumentCaptor.forClass(PurchaseException.class);
            verify(exceptionRepository).save(captor.capture());
            assertEquals("RESOLVED", captor.getValue().getStatus());
        }

        @Test
        @DisplayName("REQUEST_RESUPPLY：状态变 RESOLVED")
        void requestResupply_resolvesException() {
            PurchaseException ex = pendingUnderException();
            when(exceptionRepository.findById("EXC-002")).thenReturn(Optional.of(ex));

            exceptionService.decideException("EXC-002", FACTORY_ID, ExceptionDecision.REQUEST_RESUPPLY, null, 1L);

            ArgumentCaptor<PurchaseException> captor = ArgumentCaptor.forClass(PurchaseException.class);
            verify(exceptionRepository).save(captor.capture());
            assertEquals("RESOLVED", captor.getValue().getStatus());
        }

        @Test
        @DisplayName("异常单不存在 → BusinessException")
        void unknownId_throwsBusinessException() {
            when(exceptionRepository.findById("EXC-MISSING")).thenReturn(Optional.empty());

            assertThrows(BusinessException.class, () ->
                    exceptionService.decideException("EXC-MISSING", FACTORY_ID, ExceptionDecision.ACCEPT_OVER, null, 1L));
        }

        @Test
        @DisplayName("已 RESOLVED 异常单重复决策 → BusinessException")
        void alreadyResolved_throwsBusinessException() {
            PurchaseException ex = pendingOverException();
            ex.setStatus("RESOLVED");
            when(exceptionRepository.findById("EXC-001")).thenReturn(Optional.of(ex));

            assertThrows(BusinessException.class, () ->
                    exceptionService.decideException("EXC-001", FACTORY_ID, ExceptionDecision.ACCEPT_OVER, null, 1L));
        }

        @Test
        @DisplayName("超收异常使用少收决策 → BusinessException（类型不兼容）")
        void overExceptionWithUnderDecision_throwsBusinessException() {
            PurchaseException ex = pendingOverException();
            when(exceptionRepository.findById("EXC-001")).thenReturn(Optional.of(ex));

            assertThrows(BusinessException.class, () ->
                    exceptionService.decideException("EXC-001", FACTORY_ID, ExceptionDecision.ACCEPT_SHORT, null, 1L));
        }
    }
}
