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
}
