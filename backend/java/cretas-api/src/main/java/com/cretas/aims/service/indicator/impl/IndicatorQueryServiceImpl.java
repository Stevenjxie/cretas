package com.cretas.aims.service.indicator.impl;

import com.cretas.aims.entity.indicator.Indicator;
import com.cretas.aims.repository.indicator.IndicatorRepository;
import com.cretas.aims.service.indicator.IndicatorQueryService;
import com.cretas.aims.service.indicator.dto.IndicatorValueResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 指标查询服务实现 — STUB version.
 *
 * <p>Stub introduced 2026-05-22 to unblock {@code main} compilation after PR #200
 * cherry-picked {@code IndicatorController} + DTOs without the Day 4 service layer
 * (per PR #200 commit message: "UI 依赖未 merge"). Behavior:
 *
 * <ul>
 *   <li>{@link #listByCategory(String, String)} — delegates to the existing
 *       {@code findByFactoryIdAndCategoryAndIsActiveTrueOrderByDisplayOrderAscNameAsc}
 *       finder so the list endpoint returns real rows (no behavioral regression).</li>
 *   <li>{@link #computeForCode(String, String, LocalDate, LocalDate)} — returns a
 *       sentinel result ({@code value = 0, source = "stub"}) and warns once per
 *       call so the {@code /indicators/{code}/value} endpoint stays reachable
 *       without crashing 500 while the real compute lands.</li>
 * </ul>
 *
 * <p>Sister chat's Day 4 deliverable (per spec
 * {@code docs/superpowers/dispatch/2026-05-22-phase1-day4-indicator-query-service-dispatch.md})
 * will supersede this with the real implementation
 * ({@code PythonSmartBIClient.fetchIndicatorValue()} + caching).
 *
 * @author Cretas Team
 * @since 2026-05-22
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndicatorQueryServiceImpl implements IndicatorQueryService {

    private final IndicatorRepository indicatorRepository;

    @Override
    public List<Indicator> listByCategory(String factoryId, String category) {
        return indicatorRepository
                .findByFactoryIdAndCategoryAndIsActiveTrueOrderByDisplayOrderAscNameAsc(
                        factoryId, category);
    }

    @Override
    public IndicatorValueResult computeForCode(String code, String factoryId,
                                               LocalDate periodStart, LocalDate periodEnd) {
        log.warn("IndicatorQueryService.computeForCode is a STUB — sister chat's Day 4 "
                        + "implementation has not landed yet. Returning sentinel value for "
                        + "code={}, factoryId={}, periodStart={}, periodEnd={}.",
                code, factoryId, periodStart, periodEnd);
        return new IndicatorValueResult(
                BigDecimal.ZERO,
                LocalDateTime.now(),
                Boolean.FALSE,
                "stub");
    }
}
