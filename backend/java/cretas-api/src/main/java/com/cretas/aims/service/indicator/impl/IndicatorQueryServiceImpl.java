package com.cretas.aims.service.indicator.impl;

import com.cretas.aims.client.PythonSmartBIClient;
import com.cretas.aims.entity.indicator.Indicator;
import com.cretas.aims.entity.indicator.IndicatorComputation;
import com.cretas.aims.entity.indicator.IndicatorVersion;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.indicator.IndicatorComputationRepository;
import com.cretas.aims.repository.indicator.IndicatorRepository;
import com.cretas.aims.repository.indicator.IndicatorVersionRepository;
import com.cretas.aims.service.indicator.IndicatorQueryService;
import com.cretas.aims.service.indicator.dto.IndicatorValueResult;
import com.cretas.aims.service.indicator.strategy.IndicatorComputationStrategy;
import com.cretas.aims.service.indicator.strategy.IndicatorComputationStrategyRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 指标查询服务实现 — Phase 1 Sprint 1 Day 4 (2026-05-22).
 *
 * <p>核心职责: 按 {@link Indicator#getComputeStrategy()} 字段分支选择计算路径:
 * REALTIME / PRECOMPUTED / CACHED. 见 {@link #computeForCode}.
 *
 * <p>Phase 2B Python 端点真实接入前，{@link PythonSmartBIClient#fetchIndicatorValue} 为 stub
 * 返 {@link BigDecimal#ZERO}; 本 Service 不感知 stub vs 真实 — 调用方约定后续 Phase 2B 替换 stub
 * 时本 Service 不需改动。
 *
 * <p>每次 fetch Python (cached 过期 / realtime) 会落 {@link IndicatorVersion} 快照，
 * 用于历史趋势分析。precomputed 不落 version (由 scheduler 维护已经写过).
 *
 * @author Cretas Team
 * @since 2026-05-22 (Phase 1 Sprint 1 Day 4)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndicatorQueryServiceImpl implements IndicatorQueryService {

    private final IndicatorRepository indicatorRepository;
    private final IndicatorVersionRepository indicatorVersionRepository;
    private final IndicatorComputationRepository indicatorComputationRepository;
    private final PythonSmartBIClient pythonSmartBIClient;
    private final IndicatorComputationStrategyRegistry strategyRegistry;

    /** {@inheritDoc} */
    @Override
    @Transactional
    public IndicatorValueResult computeForCode(String code, String factoryId,
                                               LocalDate periodStart, LocalDate periodEnd) {
        if (code == null || factoryId == null) {
            throw new BusinessException("code 和 factoryId 不能为空");
        }
        Indicator ind = indicatorRepository
                .findByCodeAndFactoryIdAndDeletedAtIsNull(code, factoryId)
                .orElseThrow(() -> new BusinessException(
                        "Indicator not found: code=" + code + ", factoryId=" + factoryId));

        String strategy = ind.getComputeStrategy();
        log.debug("computeForCode: code={}, factoryId={}, strategy={}", code, factoryId, strategy);

        return switch (strategy == null ? "" : strategy) {
            case "REALTIME"    -> computeRealtime(ind, factoryId, periodStart, periodEnd);
            case "PRECOMPUTED" -> computePrecomputed(ind);
            case "CACHED"      -> computeCached(ind, factoryId, periodStart, periodEnd);
            default            -> throw new BusinessException(
                    "Unknown computeStrategy: " + strategy + " (indicator code=" + code + ")");
        };
    }

    /** {@inheritDoc} */
    @Override
    public List<Indicator> listByCategory(String factoryId, String category) {
        if (factoryId == null || category == null) {
            throw new BusinessException("factoryId 和 category 不能为空");
        }
        return indicatorRepository.findByFactoryIdAndCategoryAndIsActiveTrueAndDeletedAtIsNull(
                factoryId, category);
    }

    /** {@inheritDoc} */
    @Override
    public Indicator getById(String id, String factoryId) {
        if (id == null || factoryId == null) {
            throw new BusinessException("id 和 factoryId 不能为空");
        }
        return indicatorRepository.findByIdAndFactoryIdAndDeletedAtIsNull(id, factoryId)
                .orElseThrow(() -> new BusinessException(
                        "Indicator not found: id=" + id + ", factoryId=" + factoryId));
    }

    // ====================================================================
    // 策略分支实现
    // ====================================================================

    /**
     * REALTIME — 每次都调 Python, 不读 cache 也不写 lastValue (保留 lastValue 给 scheduler).
     * 仍落 version 快照, 用于审计 / 趋势.
     */
    IndicatorValueResult computeRealtime(Indicator ind, String factoryId,
                                          LocalDate start, LocalDate end) {
        FetchOutcome outcome = fetchFromPython(ind, factoryId, start, end);
        LocalDateTime computedAt = LocalDateTime.now();
        saveVersion(ind, factoryId, outcome.value(), start, end, computedAt, outcome.sourceLabel());
        return new IndicatorValueResult(outcome.value(), computedAt, false, "python");
    }

    /**
     * PRECOMPUTED — 直接返 lastValue (Phase 2C scheduler 维护). 不调 Python 不落 version.
     * 如果 lastValue 为 null (scheduler 还没跑过), 返 ZERO 而不是抛错 — 让前端展示 "—".
     */
    IndicatorValueResult computePrecomputed(Indicator ind) {
        BigDecimal value = ind.getLastValue() != null ? ind.getLastValue() : BigDecimal.ZERO;
        LocalDateTime computedAt = ind.getLastComputedAt() != null
                ? ind.getLastComputedAt()
                : LocalDateTime.now();
        return new IndicatorValueResult(value, computedAt, true, "precomputed");
    }

    /**
     * CACHED — TTL 内复用 lastValue, 过期 fetch Python + 落 version + 更新 lastValue.
     * 默认 TTL = ind.cacheTtlSeconds (Indicator entity field, default 3600s).
     * 若该字段为 null, 视为永久缓存 (不重算).
     */
    IndicatorValueResult computeCached(Indicator ind, String factoryId,
                                        LocalDate start, LocalDate end) {
        Integer ttl = ind.getCacheTtlSeconds();
        LocalDateTime lastComputed = ind.getLastComputedAt();
        BigDecimal lastValue = ind.getLastValue();

        // Cache hit: lastValue 存在 + 在 TTL 内 (lastComputed null 时 ttl 视为永久缓存 if value 存在)
        boolean withinTtl = lastComputed != null && ttl != null &&
                lastComputed.isAfter(LocalDateTime.now().minusSeconds(ttl));
        if (lastValue != null && withinTtl) {
            return new IndicatorValueResult(lastValue, lastComputed, true, "cache");
        }

        // Cache miss / 过期: fetch Python + 更新 lastValue + 落 version
        FetchOutcome outcome = fetchFromPython(ind, factoryId, start, end);
        LocalDateTime computedAt = LocalDateTime.now();
        ind.setLastValue(outcome.value());
        ind.setLastComputedAt(computedAt);
        indicatorRepository.save(ind);
        saveVersion(ind, factoryId, outcome.value(), start, end, computedAt, outcome.sourceLabel());
        return new IndicatorValueResult(outcome.value(), computedAt, false, "python");
    }

    /**
     * 单次 fetch 返回结果 — value + compute_source label (避免 saveVersion 重复查 repo).
     */
    record FetchOutcome(BigDecimal value, String sourceLabel) {}

    /**
     * 调 Python 端点取值 — 走 IndicatorComputation 主策略 (priority 最低的启用条目).
     *
     * <p>Sprint 12 Phase B: 新增 {@code JPA_AGGREGATE} 分支, 走
     * {@link IndicatorComputationStrategyRegistry} 找 Java strategy 真接业务表 SQL aggregate.
     * 跟 PYTHON_ENDPOINT 平级 — Registry 找不到 strategy 返 ZERO stub 不抛错.
     *
     * <p>若没有启用策略 / 主策略类型未识别, 返 ZERO 并 log warn — 不抛错.
     * (避免一个指标配置错误 cascade 影响其他指标.)
     */
    FetchOutcome fetchFromPython(Indicator ind, String factoryId,
                                  LocalDate start, LocalDate end) {
        List<IndicatorComputation> strategies = indicatorComputationRepository
                .findByIndicatorIdAndIsActiveTrueAndDeletedAtIsNull(ind.getId());
        if (strategies.isEmpty()) {
            log.warn("fetchFromPython: no active computation for indicator id={}, code={}",
                    ind.getId(), ind.getCode());
            return new FetchOutcome(BigDecimal.ZERO, "UNKNOWN:" + ind.getCode());
        }
        IndicatorComputation primary = strategies.get(0);
        String type = primary.getComputeType();

        // Sprint 12 Phase B: JPA_AGGREGATE 走 Java strategy 真接业务表
        if ("JPA_AGGREGATE".equals(type)) {
            Optional<IndicatorComputationStrategy> strategyOpt = strategyRegistry.find(ind.getCode());
            if (strategyOpt.isEmpty()) {
                log.warn("fetchFromPython: JPA_AGGREGATE configured but no strategy registered for code={} — returning ZERO stub",
                        ind.getCode());
                return new FetchOutcome(BigDecimal.ZERO, "REAL_BUSINESS_MISSING:" + ind.getCode());
            }
            BigDecimal value = strategyOpt.get().compute(factoryId, start, end);
            return new FetchOutcome(value, "REAL_BUSINESS:" + ind.getCode());
        }

        if ("PYTHON_ENDPOINT".equals(type)) {
            BigDecimal value = pythonSmartBIClient.fetchIndicatorValue(
                    factoryId, primary.getComputeSource(), start, end);
            return new FetchOutcome(value, "PYTHON_ENDPOINT:" + ind.getCode());
        }

        log.warn("fetchFromPython: unrecognized computeType={} for code={} — returning ZERO stub",
                type, ind.getCode());
        return new FetchOutcome(BigDecimal.ZERO, "UNKNOWN:" + ind.getCode());
    }

    /**
     * 持久化历史快照 — append-only.
     *
     * <p>Sprint 12 Phase B: 调用方从 {@link FetchOutcome#sourceLabel()} 传 compute_source,
     * 按主策略类型 (JPA_AGGREGATE / PYTHON_ENDPOINT / UNKNOWN) 区分, 满足 Rule 21 mock-vs-real 隔离.
     */
    void saveVersion(Indicator ind, String factoryId, BigDecimal value,
                     LocalDate start, LocalDate end, LocalDateTime computedAt,
                     String computeSourceLabel) {
        IndicatorVersion version = new IndicatorVersion();
        version.setFactoryId(factoryId);
        version.setIndicatorId(ind.getId());
        version.setPeriodStart(start);
        version.setPeriodEnd(end);
        version.setValue(value);
        version.setComputedAt(computedAt);
        version.setComputeSource(computeSourceLabel);
        indicatorVersionRepository.save(version);
    }
}
