package com.cretas.aims.service.indicator.strategy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 真业务 strategy 注册中心 — Sprint 12 Phase B.
 *
 * <p>Spring 启动时收集所有 {@link IndicatorComputationStrategy} 实现, 按
 * {@link IndicatorComputationStrategy#getIndicatorCode()} 索引. {@link IndicatorQueryServiceImpl}
 * 看到 {@code indicator_computations.compute_type = 'JPA_AGGREGATE'} 时调 {@link #find(String)}
 * 取对应实现. 找不到返 {@link Optional#empty()} — caller 负责 log + fallback to ZERO.
 *
 * @author Cretas Team
 * @since 2026-05-29 (Sprint 12 Phase B)
 */
@Slf4j
@Component
public class IndicatorComputationStrategyRegistry {

    private final Map<String, IndicatorComputationStrategy> strategiesByCode = new ConcurrentHashMap<>();
    private final List<IndicatorComputationStrategy> allStrategies;

    public IndicatorComputationStrategyRegistry(List<IndicatorComputationStrategy> allStrategies) {
        this.allStrategies = allStrategies;
    }

    @PostConstruct
    void register() {
        for (IndicatorComputationStrategy s : allStrategies) {
            String code = s.getIndicatorCode();
            if (code == null || code.isBlank()) {
                log.warn("IndicatorComputationStrategy {} returned null/blank code — skipping",
                        s.getClass().getSimpleName());
                continue;
            }
            IndicatorComputationStrategy prev = strategiesByCode.put(code, s);
            if (prev != null) {
                log.error("IndicatorComputationStrategy code collision: {} → {} replaced by {}",
                        code, prev.getClass().getSimpleName(), s.getClass().getSimpleName());
            }
        }
        log.info("IndicatorComputationStrategyRegistry: registered {} strategies — codes: {}",
                strategiesByCode.size(),
                strategiesByCode.keySet().stream().sorted().collect(Collectors.joining(", ")));
    }

    /**
     * 按 indicator code 查找策略.
     * @return 找到返 {@link Optional#of}, 未注册返 {@link Optional#empty()}.
     */
    public Optional<IndicatorComputationStrategy> find(String indicatorCode) {
        if (indicatorCode == null) return Optional.empty();
        return Optional.ofNullable(strategiesByCode.get(indicatorCode));
    }

    /** 所有已注册的 code (用于 scheduler 遍历重算). */
    public List<String> allCodes() {
        return strategiesByCode.keySet().stream().sorted().collect(Collectors.toList());
    }
}
