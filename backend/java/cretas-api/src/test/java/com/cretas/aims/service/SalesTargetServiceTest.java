package com.cretas.aims.service;

import com.cretas.aims.entity.SalesTarget;
import com.cretas.aims.entity.enums.TargetPeriod;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.SalesOpportunityRepository;
import com.cretas.aims.repository.SalesTargetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Sprint 7 wave 2 T5 — SalesTargetService 单元测试.
 *
 * <p>覆盖:
 * <ul>
 *   <li>setTarget 上限校验 (¥10M)</li>
 *   <li>computeActual 调 SalesOpportunityRepository.sumClosedWonValueByOwnerAndPeriod</li>
 *   <li>getProgress 含 completionRate / rank / gap</li>
 *   <li>getLeaderboard 排名 + team quota</li>
 *   <li>computePeriodRange MONTH/QUARTER/YEAR boundaries</li>
 *   <li>completionRate 0 target → 0</li>
 * </ul>
 */
@DisplayName("SalesTargetService unit tests (Sprint 7 wave 2 T5)")
@ExtendWith(MockitoExtension.class)
class SalesTargetServiceTest {

    @Mock
    private SalesTargetRepository repository;

    @Mock
    private SalesOpportunityRepository opportunityRepository;

    private SalesTargetService service;

    private static final String FACTORY = "F006";
    private static final Long OWNER = 100L;
    private static final Long CREATED_BY = 200L;

    @BeforeEach
    void setUp() {
        service = new SalesTargetService(repository, opportunityRepository);
    }

    // ==================== setTarget ====================

    @Test
    @DisplayName("setTarget rejects amount exceeding ¥10M cap")
    void setTarget_rejectsOverMax() {
        BigDecimal over = new BigDecimal("10000001");
        BusinessException ex = assertThrows(BusinessException.class, () ->
                service.setTarget(FACTORY, OWNER, TargetPeriod.MONTH, 2026, 5, over, CREATED_BY));
        assertTrue(ex.getMessage().contains("10M") || ex.getMessage().contains("10000000"));
    }

    @Test
    @DisplayName("setTarget rejects invalid periodNum for period")
    void setTarget_rejectsInvalidPeriodNum() {
        // QUARTER periodNum must be 1..4
        BigDecimal amount = new BigDecimal("50000");
        BusinessException ex = assertThrows(BusinessException.class, () ->
                service.setTarget(FACTORY, OWNER, TargetPeriod.QUARTER, 2026, 5, amount, CREATED_BY));
        assertTrue(ex.getMessage().contains("periodNum"));
    }

    @Test
    @DisplayName("setTarget upserts existing target on same key")
    void setTarget_upsertsExisting() {
        SalesTarget existing = SalesTarget.builder()
                .id("t-1").factoryId(FACTORY).ownerId(OWNER)
                .period(TargetPeriod.MONTH).year(2026).periodNum(5)
                .targetAmount(new BigDecimal("50000"))
                .createdBy(CREATED_BY).build();
        when(repository.findByFactoryIdAndOwnerIdAndPeriodAndYearAndPeriodNumAndDeletedAtIsNull(
                FACTORY, OWNER, TargetPeriod.MONTH, 2026, 5))
                .thenReturn(Optional.of(existing));
        when(repository.save(any(SalesTarget.class))).thenAnswer(inv -> inv.getArgument(0));

        SalesTarget saved = service.setTarget(FACTORY, OWNER, TargetPeriod.MONTH, 2026, 5,
                new BigDecimal("100000"), CREATED_BY);

        assertEquals(new BigDecimal("100000"), saved.getTargetAmount());
        assertEquals("t-1", saved.getId());
    }

    // ==================== computeActual ====================

    @Test
    @DisplayName("computeActual delegates to SalesOpportunityRepository with correct period bounds")
    void computeActual_delegatesToRepo() {
        when(opportunityRepository.sumClosedWonValueByOwnerAndPeriod(
                eq(FACTORY), eq(OWNER), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(new BigDecimal("120000"));

        BigDecimal actual = service.computeActual(FACTORY, OWNER, TargetPeriod.MONTH, 2026, 5);
        assertEquals(new BigDecimal("120000"), actual);
        verify(opportunityRepository).sumClosedWonValueByOwnerAndPeriod(
                eq(FACTORY), eq(OWNER), any(LocalDateTime.class), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("computeActual returns ZERO if no closed_won opportunities")
    void computeActual_zeroIfEmpty() {
        when(opportunityRepository.sumClosedWonValueByOwnerAndPeriod(
                any(), any(), any(), any())).thenReturn(null);

        BigDecimal actual = service.computeActual(FACTORY, OWNER, TargetPeriod.MONTH, 2026, 5);
        assertEquals(BigDecimal.ZERO, actual);
    }

    // ==================== getProgress ====================

    @Test
    @DisplayName("getProgress includes target/actual/gap/completionRate/rank")
    void getProgress_returnsAllFields() {
        SalesTarget target = SalesTarget.builder()
                .id("t-1").factoryId(FACTORY).ownerId(OWNER)
                .period(TargetPeriod.MONTH).year(2026).periodNum(5)
                .targetAmount(new BigDecimal("100000"))
                .createdBy(CREATED_BY).build();
        when(repository.findByFactoryIdAndOwnerIdAndPeriodAndYearAndPeriodNumAndDeletedAtIsNull(
                FACTORY, OWNER, TargetPeriod.MONTH, 2026, 5))
                .thenReturn(Optional.of(target));
        when(opportunityRepository.sumClosedWonValueByOwnerAndPeriod(
                eq(FACTORY), eq(OWNER), any(), any()))
                .thenReturn(new BigDecimal("75000"));
        when(opportunityRepository.aggregateClosedWonByOwner(eq(FACTORY), any(), any()))
                .thenReturn(Collections.emptyList());

        var result = service.getProgress(FACTORY, OWNER, TargetPeriod.MONTH, 2026, 5);

        assertEquals(new BigDecimal("100000"), result.get("target"));
        assertEquals(new BigDecimal("75000"), result.get("actual"));
        assertEquals(new BigDecimal("25000"), result.get("gap"));
        // 75/100 * 100 = 75.00
        assertEquals(new BigDecimal("75.00"), result.get("completionRate"));
        assertEquals(TargetPeriod.MONTH.name(), result.get("period"));
        assertEquals(OWNER, result.get("ownerId"));
    }

    @Test
    @DisplayName("getProgress with no target → completionRate = 0")
    void getProgress_noTarget_zeroRate() {
        when(repository.findByFactoryIdAndOwnerIdAndPeriodAndYearAndPeriodNumAndDeletedAtIsNull(
                any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        when(opportunityRepository.sumClosedWonValueByOwnerAndPeriod(any(), any(), any(), any()))
                .thenReturn(new BigDecimal("50000"));
        when(opportunityRepository.aggregateClosedWonByOwner(any(), any(), any()))
                .thenReturn(Collections.emptyList());

        var result = service.getProgress(FACTORY, OWNER, TargetPeriod.MONTH, 2026, 5);
        assertEquals(BigDecimal.ZERO, result.get("target"));
        assertEquals(BigDecimal.ZERO, result.get("completionRate"));
    }

    // ==================== getLeaderboard ====================

    @Test
    @DisplayName("getLeaderboard ranks owners by actual + computes team quota")
    void getLeaderboard_ranksOwners() {
        // Mock stats: 3 owners in DESC order of actual
        SalesOpportunityRepository.OwnerActualStat s1 = stat(100L, new BigDecimal("80000"));
        SalesOpportunityRepository.OwnerActualStat s2 = stat(101L, new BigDecimal("60000"));
        SalesOpportunityRepository.OwnerActualStat s3 = stat(102L, new BigDecimal("40000"));
        when(opportunityRepository.aggregateClosedWonByOwner(eq(FACTORY), any(), any()))
                .thenReturn(List.of(s1, s2, s3));

        // Mock targets for 3 owners
        SalesTarget t1 = targetOf(100L, new BigDecimal("100000"));
        SalesTarget t2 = targetOf(101L, new BigDecimal("100000"));
        when(repository.findByFactoryIdAndPeriodAndYearAndPeriodNumAndDeletedAtIsNull(
                eq(FACTORY), eq(TargetPeriod.MONTH), eq(2026), eq(5)))
                .thenReturn(List.of(t1, t2));

        var result = service.getLeaderboard(FACTORY, TargetPeriod.MONTH, 2026, 5, 10);

        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> entries =
                (List<java.util.Map<String, Object>>) result.get("entries");
        assertEquals(3, entries.size());
        assertEquals(1, entries.get(0).get("rank"));
        assertEquals(100L, entries.get(0).get("ownerId"));
        assertEquals(2, entries.get(1).get("rank"));
        assertEquals(101L, entries.get(1).get("ownerId"));
        // team quota = 100k + 100k = 200k
        assertEquals(new BigDecimal("200000"), result.get("teamTarget"));
        // team actual = 80k + 60k + 40k = 180k
        assertEquals(new BigDecimal("180000"), result.get("teamActual"));
    }

    // ==================== Period range computation ====================

    @Test
    @DisplayName("computePeriodRange MONTH: 2026-5 → [2026-05-01 00:00, 2026-06-01 00:00)")
    void computePeriodRange_month() {
        LocalDateTime[] range = SalesTargetService.computePeriodRange(TargetPeriod.MONTH, 2026, 5);
        assertEquals(LocalDateTime.of(2026, 5, 1, 0, 0, 0), range[0]);
        assertEquals(LocalDateTime.of(2026, 6, 1, 0, 0, 0), range[1]);
    }

    @Test
    @DisplayName("computePeriodRange QUARTER: Q2 2026 → [2026-04-01, 2026-07-01)")
    void computePeriodRange_quarter() {
        LocalDateTime[] range = SalesTargetService.computePeriodRange(TargetPeriod.QUARTER, 2026, 2);
        assertEquals(LocalDateTime.of(2026, 4, 1, 0, 0, 0), range[0]);
        assertEquals(LocalDateTime.of(2026, 7, 1, 0, 0, 0), range[1]);
    }

    @Test
    @DisplayName("computePeriodRange YEAR: 2026 → [2026-01-01, 2027-01-01)")
    void computePeriodRange_year() {
        LocalDateTime[] range = SalesTargetService.computePeriodRange(TargetPeriod.YEAR, 2026, 1);
        assertEquals(LocalDateTime.of(2026, 1, 1, 0, 0, 0), range[0]);
        assertEquals(LocalDateTime.of(2027, 1, 1, 0, 0, 0), range[1]);
    }

    // ==================== completionRate ====================

    @Test
    @DisplayName("completionRate(100k, 100k) = 100.00; (0, 100k) = 0.00; (75k, 100k) = 75.00")
    void completionRate_basics() {
        assertEquals(new BigDecimal("100.00"),
                SalesTargetService.computeCompletionRate(new BigDecimal("100000"), new BigDecimal("100000")));
        assertEquals(new BigDecimal("0.00"),
                SalesTargetService.computeCompletionRate(BigDecimal.ZERO, new BigDecimal("100000")));
        assertEquals(new BigDecimal("75.00"),
                SalesTargetService.computeCompletionRate(new BigDecimal("75000"), new BigDecimal("100000")));
    }

    @Test
    @DisplayName("completionRate(100k, 0) = ZERO (no div by zero)")
    void completionRate_zeroTarget() {
        assertEquals(BigDecimal.ZERO,
                SalesTargetService.computeCompletionRate(new BigDecimal("100000"), BigDecimal.ZERO));
        assertEquals(BigDecimal.ZERO,
                SalesTargetService.computeCompletionRate(new BigDecimal("100000"), null));
    }

    // ==================== Helpers ====================

    private SalesOpportunityRepository.OwnerActualStat stat(Long ownerId, BigDecimal total) {
        return new SalesOpportunityRepository.OwnerActualStat() {
            @Override public Long getOwnerId() { return ownerId; }
            @Override public BigDecimal getTotal() { return total; }
        };
    }

    private SalesTarget targetOf(Long ownerId, BigDecimal amount) {
        return SalesTarget.builder()
                .id("t-" + ownerId).factoryId(FACTORY).ownerId(ownerId)
                .period(TargetPeriod.MONTH).year(2026).periodNum(5)
                .targetAmount(amount).createdBy(CREATED_BY).build();
    }
}
