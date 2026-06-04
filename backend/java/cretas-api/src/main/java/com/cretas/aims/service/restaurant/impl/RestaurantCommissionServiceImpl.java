package com.cretas.aims.service.restaurant.impl;

import com.cretas.aims.entity.CommissionRule;
import com.cretas.aims.entity.enums.CommissionStatus;
import com.cretas.aims.entity.restaurant.RestaurantCommission;
import com.cretas.aims.entity.restaurant.RestaurantRepCommissionSummary;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.restaurant.RestaurantCommissionRepository;
import com.cretas.aims.repository.restaurant.RestaurantRepCommissionSummaryRepository;
import com.cretas.aims.service.CommissionService;
import com.cretas.aims.service.restaurant.RestaurantCommissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * 餐饮营销员月度阶梯提成服务实现（#59 Phase 2）。
 *
 * <p>结算入口 {@link #settleForVisit} 由 {@code RestaurantCommissionEventListener}
 * 在事务 AFTER_COMMIT 触发；本方法用 {@code REQUIRES_NEW} 独立事务结算，避免与监听器外层
 * （已提交的到访事务）耦合，且失败不影响到访（监听器 fail-soft）。</p>
 *
 * @author Cretas Team
 * @since 2026-06-04 (feature #59 Phase 2)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RestaurantCommissionServiceImpl implements RestaurantCommissionService {

    private static final DateTimeFormatter PERIOD_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final RestaurantCommissionRepository commissionRepository;
    private final RestaurantRepCommissionSummaryRepository summaryRepository;
    private final CommissionService commissionService;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<RestaurantCommission> settleForVisit(String factoryId, String visitId, Long repId,
                                                         BigDecimal visitRevenue, LocalDateTime visitAt) {
        // 0. 入参守卫
        if (factoryId == null || factoryId.isBlank() || visitId == null || visitId.isBlank()) {
            log.warn("餐饮提成结算缺 factoryId/visitId, 跳过: factoryId={}, visitId={}", factoryId, visitId);
            return Optional.empty();
        }
        if (repId == null) {
            // 散客无营销员维护 → 业绩无人归属, 优雅跳过 (P1 已 log 过 repWarning)
            log.info("到访 {} 无绑定营销员 (repId=null), 跳过提成结算", visitId);
            return Optional.empty();
        }
        BigDecimal revenue = visitRevenue != null ? visitRevenue : BigDecimal.ZERO;
        if (revenue.signum() <= 0) {
            log.info("到访 {} 营收非正 ({}), 跳过提成结算", visitId, revenue);
            return Optional.empty();
        }

        // 1. 幂等: 同一次到访已结算过 → 返已存在, 不重复建
        Optional<RestaurantCommission> existing = commissionRepository.findByVisitIdAndDeletedAtIsNull(visitId);
        if (existing.isPresent()) {
            log.info("到访 {} 提成已结算 (id={}), 跳过", visitId, existing.get().getId());
            return existing;
        }

        LocalDateTime when = visitAt != null ? visitAt : LocalDateTime.now();
        LocalDate visitDate = when.toLocalDate();
        String periodKey = when.format(PERIOD_FMT);

        // 2. 找适用规则 (餐饮无 customerType 维度 → 传 null). 无规则 → 优雅跳过.
        Optional<CommissionRule> ruleOpt = commissionService.findApplicableRule(
                factoryId, repId, null, visitDate);
        if (ruleOpt.isEmpty()) {
            log.info("营销员 {} 无适用提成规则 (factory={} date={}), 跳过结算 (业绩仍累计于汇总)",
                    repId, factoryId, visitDate);
            // 即便无规则, 也累计业绩汇总 (邓总日后配规则可见历史业绩) — 但不建提成记录.
            upsertSummary(factoryId, repId, periodKey, revenue, null);
            return Optional.empty();
        }
        CommissionRule rule = ruleOpt.get();

        // 3. upsert 营销员当月累计汇总 (cumulative += revenue, 重算 tier). 先累加再解析当前档.
        RestaurantRepCommissionSummary summary = upsertSummary(
                factoryId, repId, periodKey, revenue, rule);

        // 4. 按累计额解析所处档位 + 费率 (单一事实源)
        CommissionService.TierResolution resolution = commissionService.resolveTier(
                summary.getCumulativeRevenue(), rule.getTierConfig(), rule.getPercentage());
        BigDecimal rate = resolution.rate() != null ? resolution.rate() : BigDecimal.ZERO;

        // 5. 本次提成 = 本次营收 × rate / 100 (ROUND_HALF_UP scale 2)
        BigDecimal amount = revenue
                .multiply(rate)
                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);

        RestaurantCommission commission = RestaurantCommission.builder()
                .factoryId(factoryId)
                .visitId(visitId)
                .repId(repId)
                .ruleId(rule.getId())
                .tierSnapshot(resolution.tierIndex())
                .rateSnapshot(rate)
                .visitRevenue(revenue)
                .commissionAmount(amount)
                .cumulativeRevenueAtCalc(summary.getCumulativeRevenue())
                .status(CommissionStatus.PENDING)
                .build();
        RestaurantCommission saved = commissionRepository.save(commission);
        log.info("餐饮提成结算: visit={} rep={} period={} revenue={} cumulative={} tier={} rate={}% amount={} (rule={})",
                visitId, repId, periodKey, revenue, summary.getCumulativeRevenue(),
                resolution.tierIndex(), rate, amount, rule.getId());
        return Optional.of(saved);
    }

    /**
     * upsert 营销员当月汇总: cumulativeRevenue += revenue, attributedVisitCount += 1,
     * 重算 currentTier (按新累计额 + rule.tierConfig). rule 为 null 时 currentTier 置 null.
     */
    private RestaurantRepCommissionSummary upsertSummary(String factoryId, Long repId, String periodKey,
                                                         BigDecimal revenue, CommissionRule rule) {
        RestaurantRepCommissionSummary summary = summaryRepository
                .findByFactoryIdAndRepIdAndPeriodKey(factoryId, repId, periodKey)
                .orElseGet(() -> RestaurantRepCommissionSummary.builder()
                        .factoryId(factoryId)
                        .repId(repId)
                        .periodKey(periodKey)
                        .cumulativeRevenue(BigDecimal.ZERO)
                        .attributedVisitCount(0)
                        .build());

        BigDecimal newCumulative = (summary.getCumulativeRevenue() != null
                ? summary.getCumulativeRevenue() : BigDecimal.ZERO).add(revenue);
        summary.setCumulativeRevenue(newCumulative);
        summary.setAttributedVisitCount(
                (summary.getAttributedVisitCount() != null ? summary.getAttributedVisitCount() : 0) + 1);

        if (rule != null) {
            CommissionService.TierResolution res = commissionService.resolveTier(
                    newCumulative, rule.getTierConfig(), rule.getPercentage());
            summary.setCurrentTier(res.tierIndex());
        } else {
            summary.setCurrentTier(null);
        }
        return summaryRepository.save(summary);
    }

    @Override
    public Optional<RestaurantRepCommissionSummary> getRepSummary(String factoryId, Long repId, String periodKey) {
        if (factoryId == null || repId == null || periodKey == null) {
            return Optional.empty();
        }
        return summaryRepository.findByFactoryIdAndRepIdAndPeriodKey(factoryId, repId, periodKey);
    }

    @Override
    @Transactional
    public RestaurantCommission markPaid(String factoryId, String id) {
        RestaurantCommission c = commissionRepository.findByIdAndFactoryIdAndDeletedAtIsNull(id, factoryId)
                .orElseThrow(() -> new BusinessException(404, "餐饮提成记录不存在: " + id));
        if (c.getStatus() == CommissionStatus.CANCELLED) {
            throw new BusinessException(400, "已取消的提成不能标记发放");
        }
        if (c.getStatus() == CommissionStatus.PAID) {
            return c;  // idempotent
        }
        c.setStatus(CommissionStatus.PAID);
        c.setPaidAt(LocalDateTime.now());
        return commissionRepository.save(c);
    }
}
