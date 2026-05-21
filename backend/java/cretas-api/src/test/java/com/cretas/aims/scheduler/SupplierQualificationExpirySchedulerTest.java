package com.cretas.aims.scheduler;

import com.cretas.aims.entity.foodsafety.SupplierQualification;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.foodsafety.SupplierQualificationRepository;
import com.cretas.aims.service.notification.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link SupplierQualificationExpiryScheduler} (Sprint 9 P2.C Phase B). */
@ExtendWith(MockitoExtension.class)
class SupplierQualificationExpirySchedulerTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private SupplierQualificationExpiryScheduler scheduler;

    @Mock
    private SupplierQualificationRepository qualificationRepository;

    @Mock
    private FactoryRepository factoryRepository;

    @Mock
    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        // 由 @Value 注入默认 true (test 显式 set)
        ReflectionTestUtils.setField(scheduler, "enabled", true);
    }

    @Test
    @DisplayName("UT-SQS-01: enabled=false → 直接 return, 不查 DB, 不发通知")
    void disabledShortCircuits() {
        ReflectionTestUtils.setField(scheduler, "enabled", false);
        scheduler.dailyExpiryCheck();
        verify(qualificationRepository, never()).findAllExpiredByStatusIn(anyList(), any());
        verify(notificationService, never()).notifyRole(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("UT-SQS-02: EXPIRED 状态迁移 — VALID 已过期 → EXPIRED + save + notify")
    void expiredMigratesAndNotifies() {
        LocalDate today = LocalDate.now();

        SupplierQualification q = mkQual(1L, "SC_LICENSE", "VALID", today.minusDays(5));

        when(qualificationRepository.findAllExpiredByStatusIn(anyList(), any()))
                .thenReturn(List.of(q));
        when(qualificationRepository.findAllValidExpiringBetween(any(), any()))
                .thenReturn(List.of());
        when(qualificationRepository.save(any(SupplierQualification.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        scheduler.dailyExpiryCheck();

        // q.status 应该被改成 EXPIRED
        assertEquals("EXPIRED", q.getStatus());
        verify(qualificationRepository, times(1)).save(any(SupplierQualification.class));
        // 应该发了通知 (notifyExpired)
        verify(notificationService, times(1)).notifyRole(
                anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("UT-SQS-03: URGENT — VALID 7 天内将过期 → EXPIRING + notify")
    void urgentMigratesAndNotifies() {
        LocalDate today = LocalDate.now();

        SupplierQualification q = mkQual(1L, "SC_LICENSE", "VALID", today.plusDays(3));

        when(qualificationRepository.findAllExpiredByStatusIn(anyList(), any()))
                .thenReturn(List.of());
        // urgent: first call (today, today+7) returns q; second call (today+8, today+30) returns []
        AtomicLong call = new AtomicLong(0);
        when(qualificationRepository.findAllValidExpiringBetween(any(), any()))
                .thenAnswer(inv -> {
                    long n = call.incrementAndGet();
                    if (n == 1) return List.of(q);   // urgent window
                    return List.of();                 // warning window
                });
        when(qualificationRepository.save(any(SupplierQualification.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        scheduler.dailyExpiryCheck();
        assertEquals("EXPIRING", q.getStatus());
        verify(notificationService, atLeastOnce()).notifyRole(
                anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("UT-SQS-04: WARNING — VALID 8-30天将过期 → EXPIRING + warning notify (1 次)")
    void warningMigratesAndNotifiesOnce() {
        LocalDate today = LocalDate.now();
        SupplierQualification q = mkQual(1L, "SC_LICENSE", "VALID", today.plusDays(20));

        when(qualificationRepository.findAllExpiredByStatusIn(anyList(), any()))
                .thenReturn(List.of());
        AtomicLong call = new AtomicLong(0);
        when(qualificationRepository.findAllValidExpiringBetween(any(), any()))
                .thenAnswer(inv -> {
                    long n = call.incrementAndGet();
                    if (n == 1) return List.of();          // urgent window 空
                    return List.of(q);                     // warning window 命中
                });
        when(qualificationRepository.save(any(SupplierQualification.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        scheduler.dailyExpiryCheck();
        assertEquals("EXPIRING", q.getStatus());
        // 1 notification (warning only, not urgent)
        verify(notificationService, times(1)).notifyRole(
                anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("UT-SQS-05: dedup — 同 q 既在 urgent 也在 warning window 时, 只触发 urgent 通知")
    void urgentDedupsWarning() {
        LocalDate today = LocalDate.now();
        SupplierQualification q = mkQual(1L, "SC_LICENSE", "VALID", today.plusDays(5));

        when(qualificationRepository.findAllExpiredByStatusIn(anyList(), any()))
                .thenReturn(List.of());
        // 同 q 出现在 urgent 和 warning 两个 list (id 一致, 应被 dedup)
        AtomicLong call = new AtomicLong(0);
        when(qualificationRepository.findAllValidExpiringBetween(any(), any()))
                .thenAnswer(inv -> {
                    long n = call.incrementAndGet();
                    if (n == 1) return List.of(q);   // urgent window
                    return List.of(q);                // warning window 也命中, 但 id 已 in alreadyUrgent → skip
                });
        when(qualificationRepository.save(any(SupplierQualification.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        scheduler.dailyExpiryCheck();
        assertEquals("EXPIRING", q.getStatus());
        // 仅 1 次 notify (urgent), warning 路径 skip
        verify(notificationService, times(1)).notifyRole(
                anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("UT-SQS-06: 通知失败不影响下一条 scan")
    void notificationFailureDoesNotStopScan() {
        LocalDate today = LocalDate.now();

        SupplierQualification q1 = mkQual(1L, "SC_LICENSE", "VALID", today.minusDays(5));
        SupplierQualification q2 = mkQual(2L, "BUSINESS_LICENSE", "VALID", today.minusDays(3));

        when(qualificationRepository.findAllExpiredByStatusIn(anyList(), any()))
                .thenReturn(List.of(q1, q2));
        when(qualificationRepository.findAllValidExpiringBetween(any(), any()))
                .thenReturn(List.of());
        when(qualificationRepository.save(any(SupplierQualification.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // 第 1 次 notify 抛, 第 2 次正常
        AtomicLong notifyCalls = new AtomicLong(0);
        org.mockito.Mockito.doAnswer(inv -> {
            long n = notifyCalls.incrementAndGet();
            if (n == 1) throw new RuntimeException("simulated SMS gateway down");
            return null;
        }).when(notificationService).notifyRole(anyString(), anyString(), anyString(), anyString());

        // 不应该抛 — scheduler 内部 try/catch 应吞
        assertDoesNotThrow(() -> scheduler.dailyExpiryCheck());
        // 两条都 save 了
        verify(qualificationRepository, times(2)).save(any(SupplierQualification.class));
        // 两次 notify 都尝试 (第 1 次 throw, 第 2 次 succeed)
        verify(notificationService, atLeast(2)).notifyRole(
                anyString(), anyString(), anyString(), anyString());
    }

    private SupplierQualification mkQual(Long id, String type, String status, LocalDate expiry) {
        SupplierQualification q = new SupplierQualification();
        q.setId(id);
        q.setFactoryId(FACTORY_ID);
        q.setSupplierId("sup-" + id);
        q.setQualificationType(type);
        q.setCertificateNumber(type + "-" + id);
        q.setStatus(status);
        q.setExpiryDate(expiry);
        q.setIssueDate(LocalDate.now().minusYears(1));
        return q;
    }
}
