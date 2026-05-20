package com.cretas.aims.service;

import com.cretas.aims.entity.SalesTarget;
import com.cretas.aims.entity.enums.TargetPeriod;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.SalesOpportunityRepository;
import com.cretas.aims.repository.SalesTargetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 销售业绩目标 Service — Sprint 7 wave 2 Track T5 (2026-05-20).
 *
 * <p>HJ Round 13 §15 业绩 6 项:
 * <ol>
 *   <li>月/季/年 target — {@link #setTarget}</li>
 *   <li>实际 vs target gap — {@link #getProgress}</li>
 *   <li>销售排名 — {@link #getLeaderboard}</li>
 *   <li>提成 commission — {@code CommissionService}</li>
 *   <li>团队 quota — sum of individual targets in {@link #getLeaderboard}</li>
 *   <li>完成率 — {@link #getProgress} returns completionRate</li>
 * </ol>
 *
 * <p>R1 防呆: target 上限 ¥10M (DB constraint + service 层 double-check).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SalesTargetService {

    public static final BigDecimal MAX_TARGET_AMOUNT = new BigDecimal("10000000");

    private final SalesTargetRepository repository;
    private final SalesOpportunityRepository opportunityRepository;

    // ==================== Write ====================

    /**
     * 创建或更新一个销售员某周期的目标 (upsert).
     */
    @Transactional
    public SalesTarget setTarget(String factoryId, Long ownerId, TargetPeriod period,
                                   int year, int periodNum, BigDecimal targetAmount, Long createdBy) {
        validateInputs(factoryId, ownerId, period, year, periodNum, targetAmount, createdBy);

        var existing = repository
                .findByFactoryIdAndOwnerIdAndPeriodAndYearAndPeriodNumAndDeletedAtIsNull(
                        factoryId, ownerId, period, year, periodNum);

        SalesTarget target;
        if (existing.isPresent()) {
            target = existing.get();
            target.setTargetAmount(targetAmount);
        } else {
            target = SalesTarget.builder()
                    .factoryId(factoryId)
                    .ownerId(ownerId)
                    .period(period)
                    .year(year)
                    .periodNum(periodNum)
                    .targetAmount(targetAmount)
                    .createdBy(createdBy)
                    .build();
        }
        SalesTarget saved = repository.save(target);
        log.info("Set sales target factory={} owner={} period={}-{}-{} amount={}",
                factoryId, ownerId, period, year, periodNum, targetAmount);
        return saved;
    }

    @Transactional
    public void delete(String factoryId, String id) {
        SalesTarget t = repository.findByIdAndFactoryIdAndDeletedAtIsNull(id, factoryId)
                .orElseThrow(() -> new BusinessException(404, "销售目标不存在: " + id));
        repository.delete(t);
    }

    // ==================== Read ====================

    /**
     * 计算指定销售员某周期的实际完成金额. 不缓存, 每次实时查 SalesOpportunity 表.
     */
    public BigDecimal computeActual(String factoryId, Long ownerId, TargetPeriod period,
                                      int year, int periodNum) {
        if (factoryId == null || ownerId == null || period == null) {
            throw new BusinessException(400, "factoryId / ownerId / period 必填");
        }
        if (!period.isValidPeriodNum(periodNum)) {
            throw new BusinessException(400, "periodNum " + periodNum + " 超出 " + period + " 范围");
        }

        var range = computePeriodRange(period, year, periodNum);
        BigDecimal actual = opportunityRepository.sumClosedWonValueByOwnerAndPeriod(
                factoryId, ownerId, range[0], range[1]);
        return actual != null ? actual : BigDecimal.ZERO;
    }

    /**
     * 获取销售员某周期的进度: target / actual / completionRate / gap.
     * R2 防呆: 返回 ownerId + period 全 context 信息.
     */
    public Map<String, Object> getProgress(String factoryId, Long ownerId, TargetPeriod period,
                                             int year, int periodNum) {
        BigDecimal actual = computeActual(factoryId, ownerId, period, year, periodNum);
        var existing = repository
                .findByFactoryIdAndOwnerIdAndPeriodAndYearAndPeriodNumAndDeletedAtIsNull(
                        factoryId, ownerId, period, year, periodNum);
        BigDecimal target = existing.map(SalesTarget::getTargetAmount).orElse(BigDecimal.ZERO);

        BigDecimal completionRate = computeCompletionRate(actual, target);
        BigDecimal gap = target.subtract(actual);

        // 排名 (按全 factory 排名)
        Integer rank = computeRank(factoryId, ownerId, period, year, periodNum);

        Map<String, Object> result = new HashMap<>();
        result.put("ownerId", ownerId);
        result.put("period", period.name());
        result.put("periodDisplay", period.getDisplayName());
        result.put("year", year);
        result.put("periodNum", periodNum);
        result.put("target", target);
        result.put("actual", actual);
        result.put("gap", gap);
        result.put("completionRate", completionRate);
        result.put("rank", rank);
        return result;
    }

    /**
     * Leaderboard: 按 factory + 周期, 按 actual 降序排名所有销售员.
     * 包含 team quota (所有人 target 之和).
     */
    public Map<String, Object> getLeaderboard(String factoryId, TargetPeriod period,
                                                int year, int periodNum, int limit) {
        if (factoryId == null || factoryId.isBlank()) {
            throw new BusinessException(400, "factoryId 必填");
        }
        if (period == null || !period.isValidPeriodNum(periodNum)) {
            throw new BusinessException(400, "period / periodNum 非法");
        }
        int safeLimit = Math.max(1, Math.min(limit, 100));

        var range = computePeriodRange(period, year, periodNum);
        var stats = opportunityRepository.aggregateClosedWonByOwner(
                factoryId, range[0], range[1]);

        // 取所有该 period 的目标 (用于 join target / completion rate)
        var allTargets = repository.findByFactoryIdAndPeriodAndYearAndPeriodNumAndDeletedAtIsNull(
                factoryId, period, year, periodNum);
        Map<Long, BigDecimal> ownerToTarget = new HashMap<>();
        for (SalesTarget t : allTargets) {
            ownerToTarget.put(t.getOwnerId(), t.getTargetAmount());
        }

        List<Map<String, Object>> entries = new ArrayList<>();
        int rank = 1;
        for (var s : stats) {
            BigDecimal actual = s.getTotal() != null ? s.getTotal() : BigDecimal.ZERO;
            BigDecimal target = ownerToTarget.getOrDefault(s.getOwnerId(), BigDecimal.ZERO);
            Map<String, Object> entry = new HashMap<>();
            entry.put("rank", rank++);
            entry.put("ownerId", s.getOwnerId());
            entry.put("actual", actual);
            entry.put("target", target);
            entry.put("completionRate", computeCompletionRate(actual, target));
            entries.add(entry);
            if (rank > safeLimit) break;
        }

        // Team quota: sum of all targets (即使该销售员还没有 CLOSED_WON)
        BigDecimal teamTarget = ownerToTarget.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal teamActual = entries.stream()
                .map(e -> (BigDecimal) e.get("actual"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal teamCompletionRate = computeCompletionRate(teamActual, teamTarget);

        Map<String, Object> result = new HashMap<>();
        result.put("period", period.name());
        result.put("periodDisplay", period.getDisplayName());
        result.put("year", year);
        result.put("periodNum", periodNum);
        result.put("entries", entries);
        result.put("teamTarget", teamTarget);
        result.put("teamActual", teamActual);
        result.put("teamCompletionRate", teamCompletionRate);
        return result;
    }

    /**
     * 我的所有目标 (按年份 + 周期号倒序).
     */
    public List<SalesTarget> listMyTargets(String factoryId, Long ownerId) {
        return repository
                .findByFactoryIdAndOwnerIdAndDeletedAtIsNullOrderByYearDescPeriodNumDesc(
                        factoryId, ownerId);
    }

    // ==================== Helpers ====================

    private void validateInputs(String factoryId, Long ownerId, TargetPeriod period,
                                  int year, int periodNum, BigDecimal targetAmount, Long createdBy) {
        if (factoryId == null || factoryId.isBlank()) {
            throw new BusinessException(400, "factoryId 必填");
        }
        if (ownerId == null) {
            throw new BusinessException(400, "ownerId 必填 (销售员)");
        }
        if (createdBy == null) {
            throw new BusinessException(400, "createdBy 必填");
        }
        if (period == null) {
            throw new BusinessException(400, "period 必填 (MONTH/QUARTER/YEAR)");
        }
        if (year < 2000 || year > 2099) {
            throw new BusinessException(400, "year " + year + " 不合法 (2000-2099)");
        }
        if (!period.isValidPeriodNum(periodNum)) {
            throw new BusinessException(400, "periodNum " + periodNum + " 超出 "
                    + period + " 范围 (1-" + period.getMaxPeriodNum() + ")");
        }
        if (targetAmount == null || targetAmount.signum() <= 0) {
            throw new BusinessException(400, "targetAmount 必须 > 0");
        }
        if (targetAmount.compareTo(MAX_TARGET_AMOUNT) > 0) {
            throw new BusinessException(400, "targetAmount 不能超过 " + MAX_TARGET_AMOUNT + " (¥10M 上限, 防误输)");
        }
    }

    /**
     * 计算周期的时间范围 [start, end) — end 用 exclusive 上界, 避免 timestamp 边界精度问题.
     */
    static LocalDateTime[] computePeriodRange(TargetPeriod period, int year, int periodNum) {
        switch (period) {
            case YEAR -> {
                LocalDateTime start = LocalDateTime.of(year, 1, 1, 0, 0, 0);
                LocalDateTime end = LocalDateTime.of(year + 1, 1, 1, 0, 0, 0);
                return new LocalDateTime[]{start, end};
            }
            case QUARTER -> {
                int startMonth = (periodNum - 1) * 3 + 1;
                LocalDateTime start = LocalDateTime.of(year, startMonth, 1, 0, 0, 0);
                YearMonth lastMonth = YearMonth.of(year, startMonth + 2);
                LocalDateTime end = lastMonth.atEndOfMonth().atTime(23, 59, 59).plusSeconds(1);
                return new LocalDateTime[]{start, end};
            }
            case MONTH -> {
                YearMonth ym = YearMonth.of(year, periodNum);
                LocalDateTime start = ym.atDay(1).atStartOfDay();
                LocalDateTime end = ym.atEndOfMonth().atTime(23, 59, 59).plusSeconds(1);
                return new LocalDateTime[]{start, end};
            }
            default -> throw new IllegalArgumentException("Unknown period: " + period);
        }
    }

    /**
     * 计算完成率: actual/target × 100, 保留 2 位 (HALF_UP).
     * target = 0 → 返回 0 (规避除零).
     */
    static BigDecimal computeCompletionRate(BigDecimal actual, BigDecimal target) {
        if (target == null || target.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal a = actual != null ? actual : BigDecimal.ZERO;
        return a.divide(target, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 计算 ownerId 在 factory 的排名 (按 actual 降序).
     * 未上榜返 null.
     */
    private Integer computeRank(String factoryId, Long ownerId, TargetPeriod period,
                                  int year, int periodNum) {
        var range = computePeriodRange(period, year, periodNum);
        var stats = opportunityRepository.aggregateClosedWonByOwner(
                factoryId, range[0], range[1]);
        int rank = 1;
        for (var s : stats) {
            if (ownerId.equals(s.getOwnerId())) {
                return rank;
            }
            rank++;
        }
        return null;
    }
}
