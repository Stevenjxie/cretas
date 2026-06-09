package com.cretas.aims.service;

import com.cretas.aims.dto.pricing.CommissionPreviewDTO;
import com.cretas.aims.entity.CommissionRule;
import com.cretas.aims.repository.CommissionRepository;
import com.cretas.aims.repository.CommissionRuleRepository;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.SalesOpportunityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SP5: CommissionService.previewByGrossMargin() 单元测试.
 *
 * <p>验证根据订单金额 + 销售员 + 客户类型预览提成金额:
 * <ul>
 *   <li>无适用规则 → hasRule=false, commissionAmount=null</li>
 *   <li>flat percentage 规则 → commissionAmount=orderAmount × percentage</li>
 *   <li>tierConfig 规则 → 按阶梯计算金额</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class CommissionServicePreviewTest {

    @Mock
    private CommissionRepository commissionRepository;
    @Mock
    private CommissionRuleRepository ruleRepository;
    @Mock
    private SalesOpportunityRepository opportunityRepository;
    @Mock
    private CustomerRepository customerRepository;

    private CommissionService service;

    private static final String FACTORY_ID = "F006";
    private static final Long SALESPERSON_ID = 42L;
    private static final String CUSTOMER_TYPE = "直营客户";
    private static final LocalDate ORDER_DATE = LocalDate.of(2026, 6, 1);

    @BeforeEach
    void setUp() {
        service = new CommissionService(
                commissionRepository, ruleRepository, opportunityRepository, customerRepository);
    }

    @Test
    @DisplayName("SP5-CP-01: 无适用规则 → hasRule=false, commissionAmount=null")
    void preview_noApplicableRule_returnsNoRule() {
        when(ruleRepository.findCandidates(eq(FACTORY_ID), eq(SALESPERSON_ID),
                eq(CUSTOMER_TYPE), eq(ORDER_DATE)))
                .thenReturn(List.of());

        CommissionPreviewDTO result = service.previewByGrossMargin(
                FACTORY_ID, new BigDecimal("10000.00"), SALESPERSON_ID, CUSTOMER_TYPE, ORDER_DATE);

        assertThat(result.isHasRule()).isFalse();
        assertThat(result.getCommissionAmount()).isNull();
    }

    @Test
    @DisplayName("SP5-CP-02: flat percentage 规则 5% → orderAmount 10000 → commission 500")
    void preview_flatPercentage_calculatesCorrectly() {
        CommissionRule rule = stubFlatRule(new BigDecimal("0.0500"), null);
        when(ruleRepository.findCandidates(any(), any(), any(), any()))
                .thenReturn(List.of(rule));

        CommissionPreviewDTO result = service.previewByGrossMargin(
                FACTORY_ID, new BigDecimal("10000.00"), SALESPERSON_ID, CUSTOMER_TYPE, ORDER_DATE);

        assertThat(result.isHasRule()).isTrue();
        assertThat(result.getCommissionAmount()).isEqualByComparingTo(new BigDecimal("500.00"));
        assertThat(result.getCommissionRate()).isEqualByComparingTo(new BigDecimal("0.0500"));
    }

    @Test
    @DisplayName("SP5-CP-03: tierConfig 规则, 金额落第二阶梯 → 使用对应阶梯费率")
    void preview_tierConfig_usesMatchingTier() {
        // tiers: 0-5000 → 3%, 5001-20000 → 5%, 20001+ → 7%
        List<java.util.Map<String, Object>> tiers = List.of(
                java.util.Map.of("minAmount", 0, "maxAmount", 5000, "rate", 0.03),
                java.util.Map.of("minAmount", 5001, "maxAmount", 20000, "rate", 0.05),
                java.util.Map.of("minAmount", 20001, "maxAmount", 999999, "rate", 0.07)
        );
        CommissionRule rule = stubFlatRule(new BigDecimal("0.03"), tiers);
        when(ruleRepository.findCandidates(any(), any(), any(), any()))
                .thenReturn(List.of(rule));

        // orderAmount=10000 → tier 2 (5001-20000, rate=5%)
        CommissionPreviewDTO result = service.previewByGrossMargin(
                FACTORY_ID, new BigDecimal("10000.00"), SALESPERSON_ID, CUSTOMER_TYPE, ORDER_DATE);

        assertThat(result.isHasRule()).isTrue();
        assertThat(result.getCommissionRate())
                .as("应使用第二阶梯费率 5%")
                .isEqualByComparingTo(new BigDecimal("0.05"));
        assertThat(result.getCommissionAmount())
                .as("10000 × 5% = 500")
                .isEqualByComparingTo(new BigDecimal("500.00"));
    }

    @Test
    @DisplayName("SP5-CP-04: 金额低于所有阶梯下界 → 回退 flat percentage 兜底")
    void preview_tierConfig_belowAllTiers_fallsBackToFlat() {
        // tier 下界 5000, 订单 2000 → 无命中 → flat 3%
        List<java.util.Map<String, Object>> tiers = List.of(
                java.util.Map.of("minAmount", 5000, "maxAmount", 20000, "rate", 0.05)
        );
        CommissionRule rule = stubFlatRule(new BigDecimal("0.0300"), tiers);
        when(ruleRepository.findCandidates(any(), any(), any(), any()))
                .thenReturn(List.of(rule));

        CommissionPreviewDTO result = service.previewByGrossMargin(
                FACTORY_ID, new BigDecimal("2000.00"), SALESPERSON_ID, CUSTOMER_TYPE, ORDER_DATE);

        assertThat(result.getCommissionRate())
                .as("无匹配阶梯 → 回退 flat 3%")
                .isEqualByComparingTo(new BigDecimal("0.0300"));
    }

    @Test
    @DisplayName("SP5-CP-05: orderAmount=0 → commission=0")
    void preview_zeroOrderAmount_returnsZeroCommission() {
        CommissionRule rule = stubFlatRule(new BigDecimal("0.0500"), null);
        when(ruleRepository.findCandidates(any(), any(), any(), any()))
                .thenReturn(List.of(rule));

        CommissionPreviewDTO result = service.previewByGrossMargin(
                FACTORY_ID, BigDecimal.ZERO, SALESPERSON_ID, CUSTOMER_TYPE, ORDER_DATE);

        assertThat(result.getCommissionAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ========================================================
    // Helpers
    // ========================================================

    private CommissionRule stubFlatRule(BigDecimal percentage,
                                        List<java.util.Map<String, Object>> tierConfig) {
        CommissionRule rule = CommissionRule.builder()
                .factoryId(FACTORY_ID)
                .salesId(SALESPERSON_ID)
                .customerType(CUSTOMER_TYPE)
                .percentage(percentage)
                .tierConfig(tierConfig)
                .effectiveFrom(LocalDate.of(2026, 1, 1))
                .effectiveTo(LocalDate.of(2099, 12, 31))
                .active(true)
                .createdBy(1L)
                .periodType("MONTHLY")
                .version(1L)
                .build();
        rule.setId("RULE-001");
        return rule;
    }
}
