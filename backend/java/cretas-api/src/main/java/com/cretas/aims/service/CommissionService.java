package com.cretas.aims.service;

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
