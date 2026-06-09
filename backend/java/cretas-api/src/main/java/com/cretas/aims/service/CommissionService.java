package com.cretas.aims.service;

import com.cretas.aims.dto.pricing.CommissionPreviewDTO;
import com.cretas.aims.entity.Commission;
import com.cretas.aims.entity.CommissionRule;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.SalesOpportunity;
import com.cretas.aims.entity.enums.CommissionStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.CommissionRepository;
import com.cretas.aims.repository.CommissionRuleRepository;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.SalesOpportunityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 提成 Service — Sprint 7 wave 2 Track T5 (2026-05-20).
 *
 * <p>核心入口:
 * <ul>
 *   <li>{@link #findApplicableRule} — 按 most-specific 选择适用规则</li>
 *   <li>{@link #calculate} — SalesOpportunity CLOSED_WON 时由 event listener 自动调用</li>
 *   <li>{@link #createRule} / {@link #updateRule} / {@link #deleteRule} — 规则 CRUD</li>
 *   <li>{@link #markPaid} — 标记发放</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommissionService {

    private final CommissionRepository commissionRepository;
    private final CommissionRuleRepository ruleRepository;
    private final SalesOpportunityRepository opportunityRepository;
    private final CustomerRepository customerRepository;

    // ==================== Rule CRUD ====================

    @Transactional
    public CommissionRule createRule(String factoryId, Long salesId, String customerType,
                                       BigDecimal percentage, LocalDate effectiveFrom,
                                       LocalDate effectiveTo, Long createdBy) {
        validateRuleInputs(factoryId, percentage, effectiveFrom, effectiveTo, createdBy);

        CommissionRule rule = CommissionRule.builder()
                .factoryId(factoryId)
                .salesId(salesId)  // nullable
                .customerType(customerType)  // nullable
                .percentage(percentage)
                .effectiveFrom(effectiveFrom)
                .effectiveTo(effectiveTo)
                .active(Boolean.TRUE)
                .createdBy(createdBy)
                .build();
        CommissionRule saved = ruleRepository.save(rule);
        log.info("Created commission rule {} factory={} salesId={} customerType={} pct={}",
                saved.getId(), factoryId, salesId, customerType, percentage);
        return saved;
    }

    @Transactional
    public CommissionRule updateRule(String factoryId, String ruleId, BigDecimal percentage,
                                       LocalDate effectiveFrom, LocalDate effectiveTo,
                                       Boolean active) {
        CommissionRule rule = ruleRepository.findByIdAndFactoryIdAndDeletedAtIsNull(ruleId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "提成规则不存在: " + ruleId));
        if (percentage != null) {
            if (percentage.signum() < 0 || percentage.compareTo(new BigDecimal("100")) > 0) {
                throw new BusinessException(400, "percentage 必须 0-100");
            }
            rule.setPercentage(percentage);
        }
        if (effectiveFrom != null) rule.setEffectiveFrom(effectiveFrom);
        if (effectiveTo != null) rule.setEffectiveTo(effectiveTo);
        if (active != null) rule.setActive(active);
        if (rule.getEffectiveTo() != null && rule.getEffectiveTo().isBefore(rule.getEffectiveFrom())) {
            throw new BusinessException(400, "effectiveTo 不能早于 effectiveFrom");
        }
        return ruleRepository.save(rule);
    }

    @Transactional
    public void deleteRule(String factoryId, String ruleId) {
        CommissionRule rule = ruleRepository.findByIdAndFactoryIdAndDeletedAtIsNull(ruleId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "提成规则不存在: " + ruleId));
        ruleRepository.delete(rule);
    }

    public List<CommissionRule> listRules(String factoryId) {
        return ruleRepository.findByFactoryIdAndDeletedAtIsNullOrderByCreatedAtDesc(factoryId);
    }

    // ==================== Most-specific rule selection ====================

    /**
     * 选择最适合的提成规则 (most-specific wins).
     *
     * <p>specificity 评分:
     * <ul>
     *   <li>salesId match + customerType match → 3 (最具体)</li>
     *   <li>salesId match only → 2</li>
     *   <li>customerType match only → 1</li>
     *   <li>generic (两个都 NULL) → 0</li>
     * </ul>
     *
     * <p>同 specificity 多条规则 → 取 createdAt 最新者 (隐式覆盖).
     *
     * @return 适用规则, 无匹配返 empty.
     */
    public Optional<CommissionRule> findApplicableRule(String factoryId, Long salesId,
                                                         String customerType, LocalDate date) {
        if (factoryId == null || salesId == null || date == null) {
            return Optional.empty();
        }

        // 取所有候选 (含 salesId match / NULL, customerType match / NULL).
        // CommissionRule.findCandidates 限制 active + effectiveFrom/To 范围.
        List<CommissionRule> candidates = ruleRepository.findCandidates(
                factoryId, salesId, customerType, date);

        // 按 specificity score 降序, 同分按 createdAt 倒序.
        return candidates.stream()
                .max(Comparator
                        .comparingInt((CommissionRule r) -> specificity(r, salesId, customerType))
                        .thenComparing(CommissionRule::getCreatedAt,
                                Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    private int specificity(CommissionRule rule, Long salesId, String customerType) {
        int score = 0;
        if (rule.getSalesId() != null && rule.getSalesId().equals(salesId)) {
            score += 2;
        }
        if (rule.getCustomerType() != null && rule.getCustomerType().equals(customerType)) {
            score += 1;
        }
        return score;
    }

    // ==================== SP5: Commission preview for sales order ====================

    /**
     * SP5: 根据订单金额 + 销售员 + 客户类型预览提成金额.
     *
     * <p>用于销售订单确认时向销售人员实时展示预计提成，不创建提成记录。
     * 若有 tierConfig 配置，通过 {@link #resolveTier} 匹配阶梯费率；
     * 否则使用 flat {@code percentage}（0-1 小数, e.g. 0.05 = 5%）。
     *
     * <p>commissionAmount = orderAmount × rate (HALF_UP scale 2).
     *
     * @param factoryId     工厂 ID (租户隔离)
     * @param orderAmount   订单金额 (计算基数)
     * @param salespersonId 销售人员 ID
     * @param customerType  客户类型
     * @param date          订单日期 (用于规则有效期过滤)
     * @return 提成预览 DTO；若无适用规则 hasRule=false
     */
    @Transactional(readOnly = true)
    public CommissionPreviewDTO previewByGrossMargin(String factoryId, BigDecimal orderAmount,
                                                      Long salespersonId, String customerType,
                                                      LocalDate date) {
        Optional<CommissionRule> ruleOpt = findApplicableRule(factoryId, salespersonId, customerType, date);
        if (ruleOpt.isEmpty()) {
            return CommissionPreviewDTO.builder()
                    .hasRule(false)
                    .orderAmount(orderAmount)
                    .build();
        }

        CommissionRule rule = ruleOpt.get();
        BigDecimal effectiveAmount = orderAmount != null ? orderAmount : BigDecimal.ZERO;

        // Resolve rate via tier or flat percentage
        TierResolution resolved = resolveTier(effectiveAmount, rule.getTierConfig(), rule.getPercentage());
        BigDecimal rate = resolved.rate();

        // commissionAmount = orderAmount × rate (rate is 0-1 decimal, e.g. 0.05 = 5%)
        BigDecimal commissionAmount = effectiveAmount
                .multiply(rate)
                .setScale(2, RoundingMode.HALF_UP);

        // Build tier description if tier was matched
        String tierDescription = null;
        if (resolved.tierIndex() != null && rule.getTierConfig() != null
                && resolved.tierIndex() < rule.getTierConfig().size()) {
            java.util.Map<String, Object> tier = rule.getTierConfig().get(resolved.tierIndex());
            Object minAmt = tier.get("minAmount");
            tierDescription = String.format("满 %s 元适用 %.0f%% 阶梯",
                    minAmt, rate.multiply(new BigDecimal("100")).doubleValue());
        }

        return CommissionPreviewDTO.builder()
                .hasRule(true)
                .commissionAmount(commissionAmount)
                .commissionRate(rate)
                .orderAmount(effectiveAmount)
                .tierDescription(tierDescription)
                .build();
    }

    // ==================== Auto-calculate on CLOSED_WON ====================

    /**
     * 为指定 opportunity 计算并保存提成记录. Idempotent — 重复调用同 oppId 不会重复创建.
     *
     * <p>由 {@code CommissionEventListener} 在 OpportunityClosedWonEvent 触发时调用.
     *
     * @return 创建或已存在的 Commission. 若无适用规则 → empty.
     */
    @Transactional
    public Optional<Commission> calculate(String opportunityId) {
        if (opportunityId == null || opportunityId.isBlank()) {
            throw new BusinessException(400, "opportunityId 必填");
        }

        // 1. Idempotent check
        Optional<Commission> existing = commissionRepository
                .findBySalesOpportunityIdAndDeletedAtIsNull(opportunityId);
        if (existing.isPresent()) {
            log.info("Commission already exists for opportunity {}, skip", opportunityId);
            return existing;
        }

        // 2. 取 opportunity (走 JpaRepository, 不限 factory)
        Optional<SalesOpportunity> oppOpt = opportunityRepository.findById(opportunityId);
        if (oppOpt.isEmpty()) {
            log.warn("Opportunity {} not found, skip commission calc", opportunityId);
            return Optional.empty();
        }
        SalesOpportunity opp = oppOpt.get();
        if (opp.getValueAmount() == null || opp.getValueAmount().signum() <= 0) {
            log.info("Opportunity {} has zero/null valueAmount, skip commission", opportunityId);
            return Optional.empty();
        }

        // 3. 找 customerType (用于 rule 匹配)
        String customerType = customerRepository.findById(opp.getCustomerId())
                .map(Customer::getCustomerType)
                .orElse(null);

        // 4. 选规则
        LocalDate closedDate = opp.getClosedAt() != null
                ? opp.getClosedAt().toLocalDate()
                : LocalDate.now();
        Optional<CommissionRule> ruleOpt = findApplicableRule(
                opp.getFactoryId(), opp.getOwnerId(), customerType, closedDate);
        if (ruleOpt.isEmpty()) {
            log.info("No applicable commission rule for opportunity={} sales={} customerType={} date={}",
                    opportunityId, opp.getOwnerId(), customerType, closedDate);
            return Optional.empty();
        }
        CommissionRule rule = ruleOpt.get();

        // 5. 计算金额 = valueAmount × percentage / 100 (HALF_UP scale 2)
        BigDecimal amount = opp.getValueAmount()
                .multiply(rule.getPercentage())
                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);

        Commission commission = Commission.builder()
                .factoryId(opp.getFactoryId())
                .salesOpportunityId(opportunityId)
                .ruleId(rule.getId())
                .salesId(opp.getOwnerId())
                .percentage(rule.getPercentage())
                .amount(amount)
                .status(CommissionStatus.PENDING)
                .build();
        Commission saved = commissionRepository.save(commission);
        log.info("Calculated commission {} for opportunity={} salesId={} amount={} (rule={} pct={})",
                saved.getId(), opportunityId, opp.getOwnerId(), amount, rule.getId(), rule.getPercentage());
        return Optional.of(saved);
    }

    // ==================== Commission CRUD ====================

    public Commission get(String factoryId, String id) {
        return commissionRepository.findByIdAndFactoryIdAndDeletedAtIsNull(id, factoryId)
                .orElseThrow(() -> new BusinessException(404, "提成记录不存在: " + id));
    }

    public Page<Commission> list(String factoryId, CommissionStatus status, Long salesId,
                                   int page, int size) {
        int safeSize = Math.max(1, Math.min(size, 100));
        int safePage = Math.max(0, page);
        var pageReq = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));

        if (salesId != null && status != null) {
            return commissionRepository
                    .findByFactoryIdAndSalesIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(
                            factoryId, salesId, status, pageReq);
        } else if (salesId != null) {
            return commissionRepository
                    .findByFactoryIdAndSalesIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                            factoryId, salesId, pageReq);
        } else if (status != null) {
            return commissionRepository
                    .findByFactoryIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(
                            factoryId, status, pageReq);
        }
        return commissionRepository.findByFactoryIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                factoryId, pageReq);
    }

    @Transactional
    public Commission markPaid(String factoryId, String id) {
        Commission c = get(factoryId, id);
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

    @Transactional
    public Commission cancel(String factoryId, String id) {
        Commission c = get(factoryId, id);
        if (c.getStatus() == CommissionStatus.PAID) {
            throw new BusinessException(400, "已发放的提成不能取消, 请单独处理");
        }
        c.setStatus(CommissionStatus.CANCELLED);
        return commissionRepository.save(c);
    }

    // ==================== Tier resolution (shared single source of truth) ====================

    /**
     * 阶梯档位解析结果 — 给定累计业绩落在哪个档 + 该档费率。
     *
     * <p>{@code tierIndex} 为 0-based 档位 index（NULL = 无 tierConfig，走 flat percentage）；
     * {@code rate} 为套用的费率（百分比，0-100）。</p>
     */
    public record TierResolution(Integer tierIndex, BigDecimal rate) {}

    /**
     * 按累计业绩在 {@code tierConfig} 中解析所处档位与费率（#59 Phase 2 餐饮月度累计阶梯提成的核心）。
     *
     * <p>这是阶梯费率解析的<b>单一事实源</b>：餐饮提成引擎结算、web-admin 区间预览均调用此方法，
     * 保证 UI 预览与实际结算一致。<b>不改</b> {@link #calculate(String)} 工厂商机路径（其仍用 flat
     * {@code percentage}），契合 CLAUDE 规则"不改既有 calculate()"。</p>
     *
     * <p>档位匹配规则：{@code minAmount <= cumulative}（含下界）且
     * {@code (maxAmount IS NULL OR cumulative < maxAmount)}（不含上界，避免边界 double-count）。
     * 多档命中取 <b>下界最大</b>者（落在更高档）。无 tierConfig → 返 {@code (null, flatPercentage)}；
     * 有 tierConfig 但无命中（累计额低于所有档下界）→ {@code (null, flatPercentage)} 兜底。</p>
     *
     * @param cumulativeRevenue 累计业绩（月度累计），不为 null
     * @param tierConfig        JSONB 档位配置（{minAmount, maxAmount, rate}），可空
     * @param flatPercentage    无 tierConfig / 无命中时的兜底 flat 费率（可空 → 0）
     */
    public TierResolution resolveTier(BigDecimal cumulativeRevenue,
                                       List<java.util.Map<String, Object>> tierConfig,
                                       BigDecimal flatPercentage) {
        BigDecimal cumulative = cumulativeRevenue != null ? cumulativeRevenue : BigDecimal.ZERO;
        BigDecimal flat = flatPercentage != null ? flatPercentage : BigDecimal.ZERO;

        if (tierConfig == null || tierConfig.isEmpty()) {
            return new TierResolution(null, flat);
        }

        Integer matchedIdx = null;
        BigDecimal matchedRate = null;
        BigDecimal matchedMin = null;
        for (int i = 0; i < tierConfig.size(); i++) {
            java.util.Map<String, Object> t = tierConfig.get(i);
            BigDecimal min = toDecimal(t.get("minAmount"));
            BigDecimal max = toDecimal(t.get("maxAmount"));
            BigDecimal rate = toDecimal(t.get("rate"));
            if (min == null || rate == null) {
                continue;  // 跳过非法档（UI 校验本应拦下）
            }
            boolean aboveMin = cumulative.compareTo(min) >= 0;          // min <= cumulative
            boolean belowMax = (max == null) || cumulative.compareTo(max) < 0;  // cumulative < max
            if (aboveMin && belowMax) {
                // 下界最大者落更高档
                if (matchedMin == null || min.compareTo(matchedMin) > 0) {
                    matchedIdx = i;
                    matchedRate = rate;
                    matchedMin = min;
                }
            }
        }

        if (matchedIdx == null) {
            // 有 tier 但无命中（累计额低于所有档下界）→ flat 兜底
            return new TierResolution(null, flat);
        }
        return new TierResolution(matchedIdx, matchedRate);
    }

    /** 宽松转 BigDecimal（容忍 Number / String / null），用于 JSONB 档位字段解析。 */
    private static BigDecimal toDecimal(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        try {
            return new BigDecimal(v.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ==================== Helpers ====================

    private void validateRuleInputs(String factoryId, BigDecimal percentage,
                                      LocalDate effectiveFrom, LocalDate effectiveTo, Long createdBy) {
        if (factoryId == null || factoryId.isBlank()) {
            throw new BusinessException(400, "factoryId 必填");
        }
        if (createdBy == null) {
            throw new BusinessException(400, "createdBy 必填");
        }
        if (percentage == null) {
            throw new BusinessException(400, "percentage 必填");
        }
        if (percentage.signum() < 0 || percentage.compareTo(new BigDecimal("100")) > 0) {
            throw new BusinessException(400, "percentage 必须 0-100");
        }
        if (effectiveFrom == null) {
            throw new BusinessException(400, "effectiveFrom 必填");
        }
        if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
            throw new BusinessException(400, "effectiveTo 不能早于 effectiveFrom");
        }
    }
}
