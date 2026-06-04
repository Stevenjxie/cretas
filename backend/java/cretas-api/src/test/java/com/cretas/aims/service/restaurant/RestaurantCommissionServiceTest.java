package com.cretas.aims.service.restaurant;

import com.cretas.aims.entity.CommissionRule;
import com.cretas.aims.entity.enums.CommissionStatus;
import com.cretas.aims.entity.restaurant.RestaurantCommission;
import com.cretas.aims.entity.restaurant.RestaurantRepCommissionSummary;
import com.cretas.aims.repository.restaurant.RestaurantCommissionRepository;
import com.cretas.aims.repository.restaurant.RestaurantRepCommissionSummaryRepository;
import com.cretas.aims.service.CommissionService;
import com.cretas.aims.service.restaurant.impl.RestaurantCommissionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * #59 Phase 2 — RestaurantCommissionService 单元测试。
 *
 * <p>覆盖（镜像 CommissionServiceTest 模式）:
 * <ul>
 *   <li>settleForVisit: 月度累计跨档 (3 档边界) → 正确 tier + amount (ROUND_HALF_UP)</li>
 *   <li>settleForVisit: idempotent re-fire (同 visitId 不重复建)</li>
 *   <li>settleForVisit: no-rule graceful skip (返 empty, 但仍累计汇总)</li>
 *   <li>settleForVisit: repId=null (散客) graceful skip</li>
 *   <li>settleForVisit: zero/negative revenue skip</li>
 *   <li>settleForVisit: monthly period_key 按 visitAt 归月</li>
 *   <li>getRepSummary / markPaid</li>
 * </ul>
 *
 * <p>注: 真实 {@link CommissionService} 注入 (非 mock), 因 resolveTier 是纯函数单一事实源,
 * 直接走真实档位解析逻辑; 仅 mock 其 repository 依赖让 findApplicableRule 可控。
 */
@DisplayName("RestaurantCommissionService unit tests (#59 Phase 2)")
@ExtendWith(MockitoExtension.class)
class RestaurantCommissionServiceTest {

    @Mock private RestaurantCommissionRepository commissionRepository;
    @Mock private RestaurantRepCommissionSummaryRepository summaryRepository;
    @Mock private com.cretas.aims.repository.CommissionRepository factoryCommissionRepo;
    @Mock private com.cretas.aims.repository.CommissionRuleRepository ruleRepository;
    @Mock private com.cretas.aims.repository.SalesOpportunityRepository opportunityRepository;
    @Mock private com.cretas.aims.repository.CustomerRepository customerRepository;

    private CommissionService commissionService;
    private RestaurantCommissionServiceImpl service;

    private static final String FACTORY = "F006";
    private static final Long REP_ID = 200L;
    private static final String VISIT_ID = "visit-1";
    // 2026-05-20 → period 2026-05
    private static final LocalDateTime VISIT_AT = LocalDateTime.of(2026, 5, 20, 19, 30);

    @BeforeEach
    void setUp() {
        commissionService = new CommissionService(
                factoryCommissionRepo, ruleRepository, opportunityRepository, customerRepository);
        service = new RestaurantCommissionServiceImpl(
                commissionRepository, summaryRepository, commissionService);
    }

    // ==================== 阶梯档位 settle ====================

    @Test
    @DisplayName("settleForVisit: 第1档 (累计10万<15万) → tier 0 rate 3%, amount = revenue×3%")
    void settle_firstTier() {
        // 已有汇总: 当月累计 7万; 本次营收 3万 → 新累计 10万 落第1档
        RestaurantRepCommissionSummary prior = summaryOf(new BigDecimal("70000"), 1);
        stubCommonNoExisting();
        when(summaryRepository.findByFactoryIdAndRepIdAndPeriodKey(FACTORY, REP_ID, "2026-05"))
                .thenReturn(Optional.of(prior));
        when(summaryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        stubRule(dengTiers());
        when(commissionRepository.save(any())).thenAnswer(inv -> {
            RestaurantCommission c = inv.getArgument(0);
            c.setId("rc-1");
            return c;
        });

        Optional<RestaurantCommission> result =
                service.settleForVisit(FACTORY, VISIT_ID, REP_ID, new BigDecimal("30000"), VISIT_AT);

        assertTrue(result.isPresent());
        ArgumentCaptor<RestaurantCommission> captor = ArgumentCaptor.forClass(RestaurantCommission.class);
        verify(commissionRepository).save(captor.capture());
        RestaurantCommission saved = captor.getValue();
        assertEquals(Integer.valueOf(0), saved.getTierSnapshot());
        assertEquals(0, new BigDecimal("3.0").compareTo(saved.getRateSnapshot()));
        // amount = 30000 × 3.0 / 100 = 900.00
        assertEquals(new BigDecimal("900.00"), saved.getCommissionAmount());
        assertEquals(0, new BigDecimal("100000").compareTo(saved.getCumulativeRevenueAtCalc()));
        assertEquals(CommissionStatus.PENDING, saved.getStatus());
        assertEquals(REP_ID, saved.getRepId());
    }

    @Test
    @DisplayName("settleForVisit: 跨档 (累计14万→17万 越过15万) → 本次按新累计落第2档 rate 5%")
    void settle_crossesBracket() {
        RestaurantRepCommissionSummary prior = summaryOf(new BigDecimal("140000"), 0);
        stubCommonNoExisting();
        when(summaryRepository.findByFactoryIdAndRepIdAndPeriodKey(FACTORY, REP_ID, "2026-05"))
                .thenReturn(Optional.of(prior));
        when(summaryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        stubRule(dengTiers());
        when(commissionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Optional<RestaurantCommission> result =
                service.settleForVisit(FACTORY, VISIT_ID, REP_ID, new BigDecimal("30000"), VISIT_AT);

        assertTrue(result.isPresent());
        ArgumentCaptor<RestaurantCommission> captor = ArgumentCaptor.forClass(RestaurantCommission.class);
        verify(commissionRepository).save(captor.capture());
        RestaurantCommission saved = captor.getValue();
        // 新累计 17万 落第2档 (15万~30万) rate 5%
        assertEquals(Integer.valueOf(1), saved.getTierSnapshot());
        assertEquals(0, new BigDecimal("5.0").compareTo(saved.getRateSnapshot()));
        // amount = 30000 × 5.0 / 100 = 1500.00
        assertEquals(new BigDecimal("1500.00"), saved.getCommissionAmount());
    }

    @Test
    @DisplayName("settleForVisit: 顶档 (累计45万) → tier 2 rate 8%, ROUND_HALF_UP 非整除")
    void settle_topTierRounding() {
        RestaurantRepCommissionSummary prior = summaryOf(new BigDecimal("440000"), 2);
        stubCommonNoExisting();
        when(summaryRepository.findByFactoryIdAndRepIdAndPeriodKey(FACTORY, REP_ID, "2026-05"))
                .thenReturn(Optional.of(prior));
        when(summaryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        stubRule(dengTiers());
        when(commissionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // revenue 12345.67 × 8% = 987.6536 → HALF_UP scale 2 = 987.65
        Optional<RestaurantCommission> result =
                service.settleForVisit(FACTORY, VISIT_ID, REP_ID, new BigDecimal("12345.67"), VISIT_AT);

        assertTrue(result.isPresent());
        ArgumentCaptor<RestaurantCommission> captor = ArgumentCaptor.forClass(RestaurantCommission.class);
        verify(commissionRepository).save(captor.capture());
        RestaurantCommission saved = captor.getValue();
        assertEquals(Integer.valueOf(2), saved.getTierSnapshot());
        assertEquals(new BigDecimal("987.65"), saved.getCommissionAmount());
    }

    // ==================== idempotent ====================

    @Test
    @DisplayName("settleForVisit: idempotent — 同 visitId 已结算 → 返已存在不重复建")
    void settle_idempotent() {
        RestaurantCommission existing = RestaurantCommission.builder()
                .id("rc-existing").factoryId(FACTORY).visitId(VISIT_ID).repId(REP_ID)
                .ruleId("r-1").rateSnapshot(new BigDecimal("3.00"))
                .visitRevenue(new BigDecimal("30000")).commissionAmount(new BigDecimal("900.00"))
                .cumulativeRevenueAtCalc(new BigDecimal("100000"))
                .status(CommissionStatus.PENDING).build();
        when(commissionRepository.findByVisitIdAndDeletedAtIsNull(VISIT_ID))
                .thenReturn(Optional.of(existing));

        Optional<RestaurantCommission> result =
                service.settleForVisit(FACTORY, VISIT_ID, REP_ID, new BigDecimal("30000"), VISIT_AT);

        assertTrue(result.isPresent());
        assertEquals("rc-existing", result.get().getId());
        // 必须 NOT 触碰 summary / rule / save
        verify(summaryRepository, never()).save(any());
        verify(commissionRepository, never()).save(any());
        verify(ruleRepository, never()).findCandidates(any(), any(), any(), any());
    }

    // ==================== graceful skips ====================

    @Test
    @DisplayName("settleForVisit: repId=null (散客无营销员) → graceful skip, 不触碰任何 repo")
    void settle_repIdNullSkip() {
        Optional<RestaurantCommission> result =
                service.settleForVisit(FACTORY, VISIT_ID, null, new BigDecimal("30000"), VISIT_AT);
        assertTrue(result.isEmpty());
        verify(commissionRepository, never()).findByVisitIdAndDeletedAtIsNull(any());
        verify(summaryRepository, never()).save(any());
    }

    @Test
    @DisplayName("settleForVisit: 营收非正 (0) → graceful skip")
    void settle_zeroRevenueSkip() {
        Optional<RestaurantCommission> result =
                service.settleForVisit(FACTORY, VISIT_ID, REP_ID, BigDecimal.ZERO, VISIT_AT);
        assertTrue(result.isEmpty());
        verify(summaryRepository, never()).save(any());
    }

    @Test
    @DisplayName("settleForVisit: 无适用规则 → graceful skip (返 empty), 但仍累计业绩汇总")
    void settle_noRuleSkipButAccumulates() {
        stubCommonNoExisting();
        when(summaryRepository.findByFactoryIdAndRepIdAndPeriodKey(FACTORY, REP_ID, "2026-05"))
                .thenReturn(Optional.empty());
        when(summaryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // 无规则
        when(ruleRepository.findCandidates(eq(FACTORY), eq(REP_ID), any(), any(LocalDate.class)))
                .thenReturn(List.of());

        Optional<RestaurantCommission> result =
                service.settleForVisit(FACTORY, VISIT_ID, REP_ID, new BigDecimal("30000"), VISIT_AT);

        assertTrue(result.isEmpty());
        // 不建提成记录
        verify(commissionRepository, never()).save(any());
        // 但累计汇总 (业绩留底, 日后配规则可见)
        ArgumentCaptor<RestaurantRepCommissionSummary> sc =
                ArgumentCaptor.forClass(RestaurantRepCommissionSummary.class);
        verify(summaryRepository).save(sc.capture());
        assertEquals(0, new BigDecimal("30000").compareTo(sc.getValue().getCumulativeRevenue()));
        assertEquals(Integer.valueOf(1), sc.getValue().getAttributedVisitCount());
        // 无规则 → currentTier null
        assertNull(sc.getValue().getCurrentTier());
    }

    // ==================== monthly period_key ====================

    @Test
    @DisplayName("settleForVisit: period_key 按 visitAt 月份归月 (2026-05-20 → 2026-05)")
    void settle_monthlyPeriodKey() {
        stubCommonNoExisting();
        when(summaryRepository.findByFactoryIdAndRepIdAndPeriodKey(FACTORY, REP_ID, "2026-05"))
                .thenReturn(Optional.empty());
        when(summaryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        stubRule(dengTiers());
        when(commissionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.settleForVisit(FACTORY, VISIT_ID, REP_ID, new BigDecimal("30000"), VISIT_AT);

        ArgumentCaptor<RestaurantRepCommissionSummary> sc =
                ArgumentCaptor.forClass(RestaurantRepCommissionSummary.class);
        verify(summaryRepository).save(sc.capture());
        assertEquals("2026-05", sc.getValue().getPeriodKey());
    }

    @Test
    @DisplayName("settleForVisit: 跨年边界 2025-12-31 → period 2025-12 (calendar month, 非 ISO)")
    void settle_yearBoundaryPeriodKey() {
        LocalDateTime nye = LocalDateTime.of(2025, 12, 31, 23, 59);
        when(commissionRepository.findByVisitIdAndDeletedAtIsNull(VISIT_ID)).thenReturn(Optional.empty());
        when(summaryRepository.findByFactoryIdAndRepIdAndPeriodKey(FACTORY, REP_ID, "2025-12"))
                .thenReturn(Optional.empty());
        when(summaryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        stubRule(dengTiers());
        when(commissionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.settleForVisit(FACTORY, VISIT_ID, REP_ID, new BigDecimal("30000"), nye);

        ArgumentCaptor<RestaurantRepCommissionSummary> sc =
                ArgumentCaptor.forClass(RestaurantRepCommissionSummary.class);
        verify(summaryRepository).save(sc.capture());
        assertEquals("2025-12", sc.getValue().getPeriodKey());
    }

    // ==================== getRepSummary / markPaid ====================

    @Test
    @DisplayName("getRepSummary: 委派 repo, null 参数返 empty")
    void getRepSummary_delegatesAndGuards() {
        assertTrue(service.getRepSummary(null, REP_ID, "2026-05").isEmpty());
        assertTrue(service.getRepSummary(FACTORY, null, "2026-05").isEmpty());

        RestaurantRepCommissionSummary s = summaryOf(new BigDecimal("100000"), 0);
        when(summaryRepository.findByFactoryIdAndRepIdAndPeriodKey(FACTORY, REP_ID, "2026-05"))
                .thenReturn(Optional.of(s));
        assertTrue(service.getRepSummary(FACTORY, REP_ID, "2026-05").isPresent());
    }

    @Test
    @DisplayName("markPaid: PENDING → PAID + paidAt set")
    void markPaid_setsStatusAndPaidAt() {
        RestaurantCommission c = RestaurantCommission.builder()
                .id("rc-1").factoryId(FACTORY).visitId(VISIT_ID).repId(REP_ID)
                .ruleId("r-1").rateSnapshot(new BigDecimal("3.00"))
                .visitRevenue(new BigDecimal("30000")).commissionAmount(new BigDecimal("900.00"))
                .cumulativeRevenueAtCalc(new BigDecimal("100000"))
                .status(CommissionStatus.PENDING).build();
        when(commissionRepository.findByIdAndFactoryIdAndDeletedAtIsNull("rc-1", FACTORY))
                .thenReturn(Optional.of(c));
        when(commissionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RestaurantCommission result = service.markPaid(FACTORY, "rc-1");
        assertEquals(CommissionStatus.PAID, result.getStatus());
        assertNotNull(result.getPaidAt());
    }

    @Test
    @DisplayName("markPaid: CANCELLED → BusinessException")
    void markPaid_rejectsCancelled() {
        RestaurantCommission c = RestaurantCommission.builder()
                .id("rc-1").factoryId(FACTORY).visitId(VISIT_ID).repId(REP_ID)
                .ruleId("r-1").rateSnapshot(new BigDecimal("3.00"))
                .visitRevenue(new BigDecimal("30000")).commissionAmount(new BigDecimal("900.00"))
                .cumulativeRevenueAtCalc(new BigDecimal("100000"))
                .status(CommissionStatus.CANCELLED).build();
        when(commissionRepository.findByIdAndFactoryIdAndDeletedAtIsNull("rc-1", FACTORY))
                .thenReturn(Optional.of(c));

        assertThrows(com.cretas.aims.exception.BusinessException.class,
                () -> service.markPaid(FACTORY, "rc-1"));
    }

    // ==================== helpers ====================

    private void stubCommonNoExisting() {
        when(commissionRepository.findByVisitIdAndDeletedAtIsNull(VISIT_ID)).thenReturn(Optional.empty());
    }

    private void stubRule(List<Map<String, Object>> tierConfig) {
        CommissionRule rule = CommissionRule.builder()
                .id("r-1").factoryId(FACTORY).salesId(REP_ID)
                .percentage(new BigDecimal("0"))
                .effectiveFrom(LocalDate.of(2025, 1, 1)).effectiveTo(null)
                .active(Boolean.TRUE).createdBy(1L)
                .tierConfig(tierConfig).periodType("MONTHLY")
                .build();
        when(ruleRepository.findCandidates(eq(FACTORY), eq(REP_ID), any(), any(LocalDate.class)))
                .thenReturn(List.of(rule));
    }

    private RestaurantRepCommissionSummary summaryOf(BigDecimal cumulative, Integer tier) {
        return RestaurantRepCommissionSummary.builder()
                .id("s-1").factoryId(FACTORY).repId(REP_ID).periodKey("2026-05")
                .cumulativeRevenue(cumulative).currentTier(tier)
                .attributedVisitCount(2).version(0L).build();
    }

    private static List<Map<String, Object>> dengTiers() {
        return List.of(
                Map.of("minAmount", 0,      "maxAmount", 150000, "rate", 3.0),
                Map.of("minAmount", 150000, "maxAmount", 300000, "rate", 5.0),
                Map.of("minAmount", 300000, "maxAmount", 500000, "rate", 8.0)
        );
    }
}
