package com.cretas.aims.service.finance;

import com.cretas.aims.entity.finance.AccountingPeriod;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.PeriodClosedException;
import com.cretas.aims.repository.finance.AccountingPeriodRepository;
import com.cretas.aims.service.finance.impl.AccountingPeriodServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Sprint 7 T2 F-PERIOD: AccountingPeriodService unit tests.
 *
 * <p>覆盖:
 * <ul>
 *   <li>5 个 state transition (open / requestClose / confirmClose / reopen / getStatus)</li>
 *   <li>Backwards compat: no row → OPEN default</li>
 *   <li>Idempotency: 重复 open OPEN → 返已有, 重复 confirm CLOSED → 返已有</li>
 *   <li>state machine validation: CLOSED 不能再 confirmClose / OPEN 不能直接 reopen</li>
 *   <li>{@link #assertOpen}: CLOSED 抛 PeriodClosedException, OPEN/无 row → pass</li>
 *   <li>反结账 reason 必填</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AccountingPeriodServiceTest {

    @Mock
    private AccountingPeriodRepository repo;

    @InjectMocks
    private AccountingPeriodServiceImpl service;

    private static final String FACTORY = "F006";
    private static final Integer Y = 2026;
    private static final Integer M = 5;

    private AccountingPeriod openPeriod;
    private AccountingPeriod closedPeriod;

    @BeforeEach
    void setUp() {
        openPeriod = AccountingPeriod.builder()
                .id("p-1")
                .factoryId(FACTORY)
                .year(Y).month(M)
                .status(AccountingPeriod.Status.OPEN)
                .openedAt(LocalDateTime.now())
                .openedBy(100L)
                .build();

        closedPeriod = AccountingPeriod.builder()
                .id("p-2")
                .factoryId(FACTORY)
                .year(Y).month(M)
                .status(AccountingPeriod.Status.CLOSED)
                .openedAt(LocalDateTime.now().minusDays(30))
                .closedAt(LocalDateTime.now())
                .closedBy(200L)
                .build();
    }

    // ==================== Phase 1: getStatus / backwards compat ====================

    @Test
    void getStatus_noRow_returnsOpenForBackwardsCompat() {
        when(repo.findByFactoryIdAndYearAndMonthAndDeletedAtIsNull(FACTORY, Y, M))
                .thenReturn(Optional.empty());

        AccountingPeriod.Status status = service.getStatus(FACTORY, Y, M);
        assertEquals(AccountingPeriod.Status.OPEN, status,
                "no row 必须默认 OPEN — legacy factory backwards compat");
    }

    @Test
    void getStatus_closedRow_returnsClosed() {
        when(repo.findByFactoryIdAndYearAndMonthAndDeletedAtIsNull(FACTORY, Y, M))
                .thenReturn(Optional.of(closedPeriod));

        assertEquals(AccountingPeriod.Status.CLOSED, service.getStatus(FACTORY, Y, M));
    }

    // ==================== Phase 2: openPeriod transitions ====================

    @Test
    void openPeriod_noPriorRow_createsOpen() {
        when(repo.findByFactoryIdAndYearAndMonthAndDeletedAtIsNull(FACTORY, Y, M))
                .thenReturn(Optional.empty());
        when(repo.save(any(AccountingPeriod.class))).thenAnswer(inv -> inv.getArgument(0));

        AccountingPeriod p = service.openPeriod(FACTORY, Y, M, 100L);

        assertEquals(AccountingPeriod.Status.OPEN, p.getStatus());
        assertEquals(FACTORY, p.getFactoryId());
        assertEquals(Y, p.getYear());
        assertEquals(M, p.getMonth());
        assertEquals(100L, p.getOpenedBy());
        assertNotNull(p.getOpenedAt());
        verify(repo).save(any(AccountingPeriod.class));
    }

    @Test
    void openPeriod_alreadyOpen_idempotentReturn() {
        when(repo.findByFactoryIdAndYearAndMonthAndDeletedAtIsNull(FACTORY, Y, M))
                .thenReturn(Optional.of(openPeriod));

        AccountingPeriod p = service.openPeriod(FACTORY, Y, M, 100L);
        assertSame(openPeriod, p);
        verify(repo, never()).save(any());
    }

    @Test
    void openPeriod_alreadyClosed_throwsConflict() {
        when(repo.findByFactoryIdAndYearAndMonthAndDeletedAtIsNull(FACTORY, Y, M))
                .thenReturn(Optional.of(closedPeriod));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.openPeriod(FACTORY, Y, M, 100L));
        assertEquals(Integer.valueOf(409), ex.getCode());
        assertNotNull(ex.getActionHint(), "Rule 5 dead-end nav 必须含 actionHint");
        verify(repo, never()).save(any());
    }

    // ==================== Phase 3: requestClose ====================

    @Test
    void requestClose_openPeriod_transitionsToPendingClose() {
        when(repo.findByFactoryIdAndYearAndMonthAndDeletedAtIsNull(FACTORY, Y, M))
                .thenReturn(Optional.of(openPeriod));
        when(repo.save(any(AccountingPeriod.class))).thenAnswer(inv -> inv.getArgument(0));

        AccountingPeriod p = service.requestClose(FACTORY, Y, M, 200L);

        assertEquals(AccountingPeriod.Status.PENDING_CLOSE, p.getStatus());
        // workflowEngineService 未注入 → 直接 PENDING_CLOSE, 不抛
        verify(repo).save(any(AccountingPeriod.class));
    }

    @Test
    void requestClose_alreadyPendingClose_throws() {
        AccountingPeriod pending = AccountingPeriod.builder()
                .id("p-3")
                .factoryId(FACTORY).year(Y).month(M)
                .status(AccountingPeriod.Status.PENDING_CLOSE)
                .build();
        when(repo.findByFactoryIdAndYearAndMonthAndDeletedAtIsNull(FACTORY, Y, M))
                .thenReturn(Optional.of(pending));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.requestClose(FACTORY, Y, M, 200L));
        assertEquals(Integer.valueOf(400), ex.getCode());
        assertTrue(ex.getMessage().contains("OPEN"));
    }

    // ==================== Phase 4: confirmClose ====================

    @Test
    void confirmClose_pendingClose_transitionsToClosedWithAudit() {
        AccountingPeriod pending = AccountingPeriod.builder()
                .id("p-4")
                .factoryId(FACTORY).year(Y).month(M)
                .status(AccountingPeriod.Status.PENDING_CLOSE)
                .build();
        when(repo.findByFactoryIdAndYearAndMonthAndDeletedAtIsNull(FACTORY, Y, M))
                .thenReturn(Optional.of(pending));
        when(repo.save(any(AccountingPeriod.class))).thenAnswer(inv -> inv.getArgument(0));

        AccountingPeriod p = service.confirmClose(FACTORY, Y, M, 300L);

        assertEquals(AccountingPeriod.Status.CLOSED, p.getStatus());
        assertEquals(300L, p.getClosedBy());
        assertNotNull(p.getClosedAt());
    }

    @Test
    void confirmClose_alreadyClosed_idempotent() {
        when(repo.findByFactoryIdAndYearAndMonthAndDeletedAtIsNull(FACTORY, Y, M))
                .thenReturn(Optional.of(closedPeriod));

        AccountingPeriod p = service.confirmClose(FACTORY, Y, M, 300L);
        assertSame(closedPeriod, p);
        verify(repo, never()).save(any());
    }

    @Test
    void confirmClose_noPeriodRow_throws404() {
        when(repo.findByFactoryIdAndYearAndMonthAndDeletedAtIsNull(FACTORY, Y, M))
                .thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.confirmClose(FACTORY, Y, M, 300L));
        assertEquals(Integer.valueOf(404), ex.getCode());
    }

    // ==================== Phase 5: reopenPeriod ====================

    @Test
    void reopen_closedPeriod_transitionsToOpenWithAudit() {
        when(repo.findByFactoryIdAndYearAndMonthAndDeletedAtIsNull(FACTORY, Y, M))
                .thenReturn(Optional.of(closedPeriod));
        when(repo.save(any(AccountingPeriod.class))).thenAnswer(inv -> inv.getArgument(0));

        AccountingPeriod p = service.reopenPeriod(FACTORY, Y, M, "5 月底发现少入一笔费用", 400L);

        assertEquals(AccountingPeriod.Status.OPEN, p.getStatus());
        assertEquals(400L, p.getReopenedBy());
        assertNotNull(p.getReopenedAt());
        assertEquals("5 月底发现少入一笔费用", p.getReopenReason());
    }

    @Test
    void reopen_emptyReason_throws400() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reopenPeriod(FACTORY, Y, M, "", 400L));
        assertEquals(Integer.valueOf(400), ex.getCode());
        assertTrue(ex.getMessage().contains("原因"));
    }

    @Test
    void reopen_nullReason_throws400() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reopenPeriod(FACTORY, Y, M, null, 400L));
        assertEquals(Integer.valueOf(400), ex.getCode());
    }

    @Test
    void reopen_openPeriod_throws() {
        when(repo.findByFactoryIdAndYearAndMonthAndDeletedAtIsNull(FACTORY, Y, M))
                .thenReturn(Optional.of(openPeriod));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reopenPeriod(FACTORY, Y, M, "试图反结账已 OPEN 期间", 400L));
        assertEquals(Integer.valueOf(400), ex.getCode());
    }

    // ==================== Phase 6: assertOpen (Voucher gate) ====================

    @Test
    void assertOpen_noRow_silentlyPasses() {
        when(repo.findByFactoryIdAndYearAndMonthAndDeletedAtIsNull(FACTORY, Y, M))
                .thenReturn(Optional.empty());

        // 应该 silent pass — backwards compat (legacy factory 无 period row 默认 OPEN)
        assertDoesNotThrow(() -> service.assertOpen(FACTORY, Y, M));
    }

    @Test
    void assertOpen_openPeriod_silentlyPasses() {
        when(repo.findByFactoryIdAndYearAndMonthAndDeletedAtIsNull(FACTORY, Y, M))
                .thenReturn(Optional.of(openPeriod));

        assertDoesNotThrow(() -> service.assertOpen(FACTORY, Y, M));
    }

    @Test
    void assertOpen_pendingClosePeriod_silentlyPasses() {
        // PENDING_CLOSE 仍允许 voucher 写 — 审批未通过时可回到 OPEN, voucher 编辑需求仍在
        AccountingPeriod pending = AccountingPeriod.builder()
                .id("p-5")
                .factoryId(FACTORY).year(Y).month(M)
                .status(AccountingPeriod.Status.PENDING_CLOSE)
                .build();
        when(repo.findByFactoryIdAndYearAndMonthAndDeletedAtIsNull(FACTORY, Y, M))
                .thenReturn(Optional.of(pending));

        assertDoesNotThrow(() -> service.assertOpen(FACTORY, Y, M));
    }

    @Test
    void assertOpen_closedPeriod_throwsPeriodClosedException() {
        when(repo.findByFactoryIdAndYearAndMonthAndDeletedAtIsNull(FACTORY, Y, M))
                .thenReturn(Optional.of(closedPeriod));

        PeriodClosedException ex = assertThrows(PeriodClosedException.class,
                () -> service.assertOpen(FACTORY, Y, M));
        assertEquals(Integer.valueOf(400), ex.getCode());
        assertEquals(FACTORY, ex.getFactoryId());
        assertEquals(Y, ex.getYear());
        assertEquals(M, ex.getMonth());
        // 防呆 Rule 5: 必须给前端导航 hint
        assertNotNull(ex.getActionHint(), "PeriodClosedException 必须含 actionHint 导航 to 反结账 UI");
        assertTrue(ex.getActionHint().contains("accounting-period"));
        assertEquals("BLOCKING", ex.getSeverity());
        // 错误 message 必须含 year-month 防呆 Rule 2 (context)
        assertTrue(ex.getMessage().contains("2026-05"));
    }

    @Test
    void assertOpen_nullParams_silentlyPasses() {
        // 防御性: 调用方未传完整 (factory, year, month) 时 silent pass
        assertDoesNotThrow(() -> service.assertOpen(null, Y, M));
        assertDoesNotThrow(() -> service.assertOpen(FACTORY, null, M));
        assertDoesNotThrow(() -> service.assertOpen(FACTORY, Y, null));
    }

    // ==================== Phase 6b: Wave2 调整窗口 (邓总20天窗口) ====================

    @Test
    void assertOpen_closedWithinAdjustWindow_silentlyPasses() {
        // Wave2: CLOSED 但在 adjust_deadline 内 → 调整窗口, voucher 仍可写 (邓总20天窗口)
        AccountingPeriod closedInWindow = AccountingPeriod.builder()
                .id("p-win")
                .factoryId(FACTORY).year(Y).month(M)
                .status(AccountingPeriod.Status.CLOSED)
                .closedAt(LocalDateTime.now().minusDays(3))
                .adjustDeadline(LocalDateTime.now().plusDays(17))  // 20天窗口还剩17天
                .build();
        when(repo.findByFactoryIdAndYearAndMonthAndDeletedAtIsNull(FACTORY, Y, M))
                .thenReturn(Optional.of(closedInWindow));

        assertDoesNotThrow(() -> service.assertOpen(FACTORY, Y, M),
                "CLOSED 但调整窗口未过期 → voucher 可调整, 不抛");
    }

    @Test
    void assertOpen_closedAdjustWindowExpired_throwsPeriodClosed() {
        // Wave2: CLOSED 且 adjust_deadline 已过 → 硬锁, 抛 PeriodClosedException
        AccountingPeriod expired = AccountingPeriod.builder()
                .id("p-exp")
                .factoryId(FACTORY).year(Y).month(M)
                .status(AccountingPeriod.Status.CLOSED)
                .closedAt(LocalDateTime.now().minusDays(25))
                .adjustDeadline(LocalDateTime.now().minusDays(5))  // 窗口5天前已过
                .build();
        when(repo.findByFactoryIdAndYearAndMonthAndDeletedAtIsNull(FACTORY, Y, M))
                .thenReturn(Optional.of(expired));

        assertThrows(PeriodClosedException.class,
                () -> service.assertOpen(FACTORY, Y, M),
                "调整窗口已过 → 硬锁");
    }

    @Test
    void assertOpen_closedNullAdjustDeadline_throwsPeriodClosed() {
        // Wave2 backwards compat: 旧 CLOSED 行 (无 adjust_deadline) → 立即硬锁, 行为不变
        // closedPeriod (setUp) adjustDeadline == null
        when(repo.findByFactoryIdAndYearAndMonthAndDeletedAtIsNull(FACTORY, Y, M))
                .thenReturn(Optional.of(closedPeriod));

        assertThrows(PeriodClosedException.class,
                () -> service.assertOpen(FACTORY, Y, M),
                "无 adjust_deadline 的旧 CLOSED 行立即硬锁 (backwards compat)");
    }

    // ==================== Phase 7: input validation ====================

    @Test
    void openPeriod_invalidMonth_throws() {
        assertThrows(BusinessException.class,
                () -> service.openPeriod(FACTORY, Y, 13, 100L));
        assertThrows(BusinessException.class,
                () -> service.openPeriod(FACTORY, Y, 0, 100L));
    }

    @Test
    void openPeriod_nullFactoryId_throws() {
        assertThrows(BusinessException.class,
                () -> service.openPeriod(null, Y, M, 100L));
    }

    @Test
    void openPeriod_invalidYear_throws() {
        assertThrows(BusinessException.class,
                () -> service.openPeriod(FACTORY, 1999, M, 100L));
        assertThrows(BusinessException.class,
                () -> service.openPeriod(FACTORY, 2201, M, 100L));
    }
}
