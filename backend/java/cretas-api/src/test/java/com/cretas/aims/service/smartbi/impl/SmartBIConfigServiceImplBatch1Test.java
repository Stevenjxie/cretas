package com.cretas.aims.service.smartbi.impl;

import com.cretas.aims.dto.smartbi.ConfigOperationResult;
import com.cretas.aims.dto.smartbi.CreateAlertThresholdRequest;
import com.cretas.aims.dto.smartbi.CreateIncentiveRuleRequest;
import com.cretas.aims.dto.smartbi.UpdateIncentiveRuleRequest;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.smartbi.SmartBiAlertThreshold;
import com.cretas.aims.entity.smartbi.SmartBiIncentiveRule;
import com.cretas.aims.repository.config.AIIntentConfigRepository;
import com.cretas.aims.repository.smartbi.SmartBiAlertThresholdRepository;
import com.cretas.aims.repository.smartbi.SmartBiChartTemplateRepository;
import com.cretas.aims.repository.smartbi.SmartBiDictionaryRepository;
import com.cretas.aims.repository.smartbi.SmartBiIncentiveRuleRepository;
import com.cretas.aims.repository.smartbi.SmartBiMetricFormulaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Rule 17.1 anti-pattern fix verification tests for Batch 1
 * (Issue #320 / PR #334 §6 Batch 1):
 * <ul>
 *   <li>{@code POST /smartbi-config/thresholds} (createThreshold)</li>
 *   <li>{@code POST /smartbi-config/incentive-rules} (createIncentiveRule)</li>
 *   <li>{@code PUT /smartbi-config/incentive-rules/{id}} (updateIncentiveRule)</li>
 * </ul>
 *
 * <p>Each scenario asserts that the saved entity reflects either the explicit caller
 * intent (when provided) or the documented business default (when omitted) — and never
 * the {@code @Builder.Default} field-initializer value silently leaking through Jackson's
 * {@code @NoArgsConstructor} path.
 *
 * <p>Roundtrip semantics tested via {@link ArgumentCaptor} on {@code repository.save(...)}.
 */
@ExtendWith(MockitoExtension.class)
class SmartBIConfigServiceImplBatch1Test {

    @Mock private AIIntentConfigRepository intentConfigRepository;
    @Mock private SmartBiAlertThresholdRepository alertThresholdRepository;
    @Mock private SmartBiIncentiveRuleRepository incentiveRuleRepository;
    @Mock private SmartBiDictionaryRepository dictionaryRepository;
    @Mock private SmartBiMetricFormulaRepository metricFormulaRepository;
    @Mock private SmartBiChartTemplateRepository chartTemplateRepository;
    @Mock private CacheManager cacheManager;

    @InjectMocks
    private SmartBIConfigServiceImpl service;

    @Test
    void listIntents_readsOnlyCanonicalRepositoryWithCanonicalPriorityOrder() {
        AIIntentConfig intent = new AIIntentConfig();
        intent.setIntentCode("SMARTBI_READ");
        when(intentConfigRepository
                .findByIntentCategoryAndIsActiveTrueAndDeletedAtIsNullOrderByPriorityDesc("SMARTBI"))
                .thenReturn(List.of(intent));

        assertThat(service.listIntents("SMARTBI")).containsExactly(intent);

        org.mockito.Mockito.verify(intentConfigRepository)
                .findByIntentCategoryAndIsActiveTrueAndDeletedAtIsNullOrderByPriorityDesc("SMARTBI");
    }

    // -----------------------------------------------------------------
    // POST /thresholds — createThreshold
    // -----------------------------------------------------------------

    @Test
    void createThreshold_omitComparisonOperator_appliesGtBusinessDefault() {
        // Caller omits comparisonOperator; service must apply documented "GT" default.
        CreateAlertThresholdRequest req = new CreateAlertThresholdRequest();
        req.setThresholdType("SALES");
        req.setMetricCode("SALES_AMOUNT");
        req.setWarningValue(new BigDecimal("100.00"));
        req.setCriticalValue(new BigDecimal("50.00"));
        // comparisonOperator intentionally null
        // isActive intentionally null

        when(alertThresholdRepository
                .existsByThresholdTypeAndMetricCodeAndFactoryId(anyString(), anyString(), any()))
                .thenReturn(false);
        when(alertThresholdRepository.save(any(SmartBiAlertThreshold.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(alertThresholdRepository.findAll()).thenReturn(List.of());

        ConfigOperationResult result = service.createThreshold(req);

        assertThat(result.isSuccess()).isTrue();
        ArgumentCaptor<SmartBiAlertThreshold> captor =
                ArgumentCaptor.forClass(SmartBiAlertThreshold.class);
        org.mockito.Mockito.verify(alertThresholdRepository).save(captor.capture());
        SmartBiAlertThreshold saved = captor.getValue();
        assertThat(saved.getComparisonOperator()).isEqualTo("GT");
        assertThat(saved.getIsActive()).isTrue();
        assertThat(saved.getWarningValue()).isEqualByComparingTo("100.00");
        assertThat(saved.getCriticalValue()).isEqualByComparingTo("50.00");
    }

    @Test
    void createThreshold_explicitComparisonOperator_isPreservedNotSilentlyOverwritten() {
        // Rule 17.1 root cause: previously @RequestBody Entity bind +
        // @Builder.Default would have set comparisonOperator="GT" via field-initializer
        // even when caller intent was "LT". DTO route preserves the explicit value.
        CreateAlertThresholdRequest req = new CreateAlertThresholdRequest();
        req.setThresholdType("FINANCE");
        req.setMetricCode("PROFIT_RATE");
        req.setComparisonOperator("LT");
        req.setIsActive(true);

        when(alertThresholdRepository
                .existsByThresholdTypeAndMetricCodeAndFactoryId(anyString(), anyString(), any()))
                .thenReturn(false);
        when(alertThresholdRepository.save(any(SmartBiAlertThreshold.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(alertThresholdRepository.findAll()).thenReturn(List.of());

        service.createThreshold(req);

        ArgumentCaptor<SmartBiAlertThreshold> captor =
                ArgumentCaptor.forClass(SmartBiAlertThreshold.class);
        org.mockito.Mockito.verify(alertThresholdRepository).save(captor.capture());
        assertThat(captor.getValue().getComparisonOperator()).isEqualTo("LT");
    }

    @Test
    void createThreshold_duplicateTypeAndMetric_returnsErrorWithoutSaving() {
        CreateAlertThresholdRequest req = new CreateAlertThresholdRequest();
        req.setThresholdType("SALES");
        req.setMetricCode("SALES_AMOUNT");

        when(alertThresholdRepository
                .existsByThresholdTypeAndMetricCodeAndFactoryId(anyString(), anyString(), any()))
                .thenReturn(true);

        ConfigOperationResult result = service.createThreshold(req);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).contains("阈值配置已存在");
        org.mockito.Mockito.verify(alertThresholdRepository, org.mockito.Mockito.never())
                .save(any());
    }

    // -----------------------------------------------------------------
    // POST /incentive-rules — createIncentiveRule
    // -----------------------------------------------------------------

    @Test
    void createIncentiveRule_omitSortOrder_appliesZeroBusinessDefault() {
        CreateIncentiveRuleRequest req = new CreateIncentiveRuleRequest();
        req.setRuleCode("SALES_TARGET");
        req.setRuleName("销售目标奖金");
        req.setLevelName("Bronze");
        req.setMinValue(new BigDecimal("10000.0000"));
        req.setMaxValue(new BigDecimal("50000.0000"));
        req.setRewardRate(new BigDecimal("0.0100"));
        // sortOrder + isActive intentionally null

        when(incentiveRuleRepository.save(any(SmartBiIncentiveRule.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(incentiveRuleRepository.findByIsActiveTrueOrderByRuleCodeAscSortOrderAsc())
                .thenReturn(List.of());

        ConfigOperationResult result = service.createIncentiveRule(req);

        assertThat(result.isSuccess()).isTrue();
        ArgumentCaptor<SmartBiIncentiveRule> captor =
                ArgumentCaptor.forClass(SmartBiIncentiveRule.class);
        org.mockito.Mockito.verify(incentiveRuleRepository).save(captor.capture());
        SmartBiIncentiveRule saved = captor.getValue();
        assertThat(saved.getSortOrder()).isZero();
        assertThat(saved.getIsActive()).isTrue();
        assertThat(saved.getMinValue()).isEqualByComparingTo("10000.0000");
        assertThat(saved.getRewardRate()).isEqualByComparingTo("0.0100");
    }

    @Test
    void createIncentiveRule_explicitSortOrder_isPreserved() {
        CreateIncentiveRuleRequest req = new CreateIncentiveRuleRequest();
        req.setRuleCode("QUALITY_SCORE");
        req.setRuleName("质量评分奖金");
        req.setLevelName("Gold");
        req.setMinValue(new BigDecimal("90.0000"));
        req.setSortOrder(5);

        when(incentiveRuleRepository.save(any(SmartBiIncentiveRule.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(incentiveRuleRepository.findByIsActiveTrueOrderByRuleCodeAscSortOrderAsc())
                .thenReturn(List.of());

        service.createIncentiveRule(req);

        ArgumentCaptor<SmartBiIncentiveRule> captor =
                ArgumentCaptor.forClass(SmartBiIncentiveRule.class);
        org.mockito.Mockito.verify(incentiveRuleRepository).save(captor.capture());
        assertThat(captor.getValue().getSortOrder()).isEqualTo(5);
    }

    // -----------------------------------------------------------------
    // PUT /incentive-rules/{id} — updateIncentiveRule (Rule 17.1 fix focus)
    // -----------------------------------------------------------------

    @Test
    void updateIncentiveRule_partialUpdate_preservesUntouchedFields() {
        // Pre-existing entity carries non-default values for isActive=false and sortOrder=99.
        // Client PUTs only ruleName. Expectation: isActive and sortOrder untouched.
        // (Pre-fix behavior: @Builder.Default + Jackson @NoArgsConstructor would
        //  reset isActive→true and sortOrder→0 silently.)
        SmartBiIncentiveRule existing = new SmartBiIncentiveRule();
        existing.setId(42L);
        existing.setRuleCode("ATTENDANCE_RATE");
        existing.setRuleName("出勤率原始名称");
        existing.setLevelName("Silver");
        existing.setMinValue(new BigDecimal("0.9000"));
        existing.setSortOrder(99);
        existing.setIsActive(false);

        UpdateIncentiveRuleRequest req = new UpdateIncentiveRuleRequest();
        req.setRuleName("出勤率新名称");
        // sortOrder + isActive + everything else intentionally null

        when(incentiveRuleRepository.findById(42L)).thenReturn(Optional.of(existing));
        when(incentiveRuleRepository.save(any(SmartBiIncentiveRule.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(incentiveRuleRepository.findByIsActiveTrueOrderByRuleCodeAscSortOrderAsc())
                .thenReturn(List.of());

        ConfigOperationResult result = service.updateIncentiveRule(42L, req);

        assertThat(result.isSuccess()).isTrue();
        ArgumentCaptor<SmartBiIncentiveRule> captor =
                ArgumentCaptor.forClass(SmartBiIncentiveRule.class);
        org.mockito.Mockito.verify(incentiveRuleRepository).save(captor.capture());
        SmartBiIncentiveRule saved = captor.getValue();
        assertThat(saved.getRuleName()).isEqualTo("出勤率新名称");
        assertThat(saved.getSortOrder()).isEqualTo(99);
        assertThat(saved.getIsActive()).isFalse();
        assertThat(saved.getRuleCode()).isEqualTo("ATTENDANCE_RATE");  // immutable on PUT
        assertThat(saved.getMinValue()).isEqualByComparingTo("0.9000");
    }

    @Test
    void updateIncentiveRule_explicitFieldChanges_areApplied() {
        SmartBiIncentiveRule existing = new SmartBiIncentiveRule();
        existing.setId(7L);
        existing.setRuleCode("SALES_TARGET");
        existing.setRuleName("旧奖金名");
        existing.setLevelName("Bronze");
        existing.setMinValue(new BigDecimal("100.0000"));
        existing.setSortOrder(1);
        existing.setIsActive(true);

        UpdateIncentiveRuleRequest req = new UpdateIncentiveRuleRequest();
        req.setLevelName("Gold");
        req.setMinValue(new BigDecimal("500.0000"));
        req.setMaxValue(new BigDecimal("1000.0000"));
        req.setRewardRate(new BigDecimal("0.0500"));
        req.setSortOrder(10);
        req.setIsActive(false);

        when(incentiveRuleRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(incentiveRuleRepository.save(any(SmartBiIncentiveRule.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(incentiveRuleRepository.findByIsActiveTrueOrderByRuleCodeAscSortOrderAsc())
                .thenReturn(List.of());

        service.updateIncentiveRule(7L, req);

        ArgumentCaptor<SmartBiIncentiveRule> captor =
                ArgumentCaptor.forClass(SmartBiIncentiveRule.class);
        org.mockito.Mockito.verify(incentiveRuleRepository).save(captor.capture());
        SmartBiIncentiveRule saved = captor.getValue();
        assertThat(saved.getLevelName()).isEqualTo("Gold");
        assertThat(saved.getMinValue()).isEqualByComparingTo("500.0000");
        assertThat(saved.getMaxValue()).isEqualByComparingTo("1000.0000");
        assertThat(saved.getRewardRate()).isEqualByComparingTo("0.0500");
        assertThat(saved.getSortOrder()).isEqualTo(10);
        assertThat(saved.getIsActive()).isFalse();
        assertThat(saved.getRuleName()).isEqualTo("旧奖金名");  // not in request → preserved
    }

    @Test
    void updateIncentiveRule_missingId_returnsErrorWithoutSaving() {
        when(incentiveRuleRepository.findById(999L)).thenReturn(Optional.empty());

        UpdateIncentiveRuleRequest req = new UpdateIncentiveRuleRequest();
        req.setRuleName("无效 PUT");

        ConfigOperationResult result = service.updateIncentiveRule(999L, req);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).contains("激励规则不存在");
        org.mockito.Mockito.verify(incentiveRuleRepository, org.mockito.Mockito.never())
                .save(any());
    }
}
