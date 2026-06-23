package com.cretas.aims.service.finance.impl;

import com.cretas.aims.entity.finance.PaymentRecord;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.PaymentRecordRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 跨租户隔离单测 — PaymentRecordServiceImpl.verifyPayment / rejectPayment (#7 测试补强, 2026-06-22).
 *
 * <p>R2 (#1042) 给 {@code getPayment} 加了跨租户校验: 收款记录 factoryId 与调用方
 * factoryId 不符 → {@code BusinessException(403)}。本测试锁定该行为, 防回归:
 * F002 调用方操作 F001 的收款记录必被拒, 且不执行任何副作用 (never save)。
 *
 * <p>用 {@code @InjectMocks} + Mockito (无 Spring context) — 只需 PaymentRecordRepository
 * mock 即可触达 getPayment 的跨租户分支 (status / arAp 联动在 throw 之前不会执行)。
 */
@ExtendWith(MockitoExtension.class)
class PaymentRecordServiceImplCrossTenantTest {

    @Mock private PaymentRecordRepository paymentRecordRepository;
    @Mock private com.cretas.aims.repository.inventory.SalesOrderRepository salesOrderRepository;
    @Mock private com.cretas.aims.repository.CustomerRepository customerRepository;
    @Mock private com.cretas.aims.service.finance.ArApService arApService;
    @Mock private org.springframework.context.ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private PaymentRecordServiceImpl service;

    @Test
    void verifyPayment_crossTenant_throws403_andDoesNotSave() {
        // 收款记录属于 F001, 调用方为 F002 → getPayment 跨租户分支拒绝。
        PaymentRecord record = new PaymentRecord();
        record.setId("pay-1");
        record.setFactoryId("F001");
        when(paymentRecordRepository.findById("pay-1")).thenReturn(Optional.of(record));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.verifyPayment("F002", "pay-1", 42L));
        assertEquals(403, ex.getCode());
        verify(paymentRecordRepository, never()).save(any());
    }

    @Test
    void rejectPayment_crossTenant_throws403_andDoesNotSave() {
        PaymentRecord record = new PaymentRecord();
        record.setId("pay-1");
        record.setFactoryId("F001");
        when(paymentRecordRepository.findById("pay-1")).thenReturn(Optional.of(record));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.rejectPayment("F002", "pay-1", 42L, "越权驳回"));
        assertEquals(403, ex.getCode());
        verify(paymentRecordRepository, never()).save(any());
    }

    /**
     * 防呆 R4 (edge-case 审计 2026-06-24): 5min 窗口内对同 SO/同金额 重复录入收款 → 409, 不再 save。
     * 防资金双计入 SO paidAmount。
     */
    @Test
    void recordPayment_recentDuplicate_throws409_andDoesNotSave() {
        com.cretas.aims.entity.inventory.SalesOrder so = new com.cretas.aims.entity.inventory.SalesOrder();
        so.setId("so-1");
        so.setFactoryId("F001");
        so.setStatus(com.cretas.aims.entity.enums.SalesOrderStatus.FINANCE_APPROVED);
        when(salesOrderRepository.findById("so-1")).thenReturn(Optional.of(so));

        PaymentRecord existing = new PaymentRecord();
        existing.setId("pay-existing");
        existing.setPaymentNumber("PR-EXIST");
        existing.setAmount(new java.math.BigDecimal("100.00"));
        existing.setStatus(com.cretas.aims.entity.enums.PaymentRecordStatus.PENDING);
        when(paymentRecordRepository.findRecentDuplicatePayments(
                org.mockito.ArgumentMatchers.eq("F001"), org.mockito.ArgumentMatchers.eq("so-1"),
                org.mockito.ArgumentMatchers.eq(new java.math.BigDecimal("100.00")),
                org.mockito.ArgumentMatchers.any(java.time.LocalDateTime.class)))
                .thenReturn(java.util.List.of(existing));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.recordPayment("F001", "so-1", new java.math.BigDecimal("100.00"),
                        com.cretas.aims.entity.enums.PaymentMethod.BANK_TRANSFER, null,
                        "REF", null, 42L, null));
        assertEquals(409, ex.getCode());
        verify(paymentRecordRepository, never()).save(any());
    }
}
