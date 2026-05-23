package com.cretas.aims.service.indicator;

import com.cretas.aims.entity.indicator.Indicator;
import com.cretas.aims.service.indicator.dto.IndicatorValueResult;

import java.time.LocalDate;
import java.util.List;

/**
 * 指标查询服务 — 工厂指标列表 + 单指标计算的入口.
 *
 * <p>Stub introduced 2026-05-22 to unblock {@code main} compilation after PR #200
 * cherry-picked {@code IndicatorController} + DTOs without the Day 4 service layer
 * (per PR #200 commit message: "UI 依赖未 merge"). The contract below mirrors the
 * call sites in {@code IndicatorController} so the controller compiles; the
 * implementation in {@code IndicatorQueryServiceImpl} returns minimum-viable
 * responses (empty list / runtime error on compute) until the sister chat's Day 4
 * deliverable lands.
 *
 * @see com.cretas.aims.controller.IndicatorController
 */
public interface IndicatorQueryService {

    /**
     * List indicators filtered by category.
     *
     * @param factoryId factory scope (multi-tenant isolation)
     * @param category  one of {@code FACTORY | RESTAURANT | QUALITY}
     * @return matching indicators (display-order ascending)
     */
    List<Indicator> listByCategory(String factoryId, String category);

    /**
     * Compute the current value of an indicator over a date window.
     *
     * @param code         indicator code (e.g. {@code FACTORY_DAILY_YIELD})
     * @param factoryId    factory scope
     * @param periodStart  business window start
     * @param periodEnd    business window end
     * @return computed value + provenance tag
     */
    IndicatorValueResult computeForCode(String code, String factoryId,
                                        LocalDate periodStart, LocalDate periodEnd);
}
