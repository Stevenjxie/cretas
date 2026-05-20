package com.cretas.aims.service;

import com.cretas.aims.entity.HourlyRateRule;
import com.cretas.aims.entity.WagePolicy;
import com.cretas.aims.entity.enums.WageMode;
import com.cretas.aims.repository.HourlyRateRuleRepository;
import com.cretas.aims.repository.WagePolicyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Sprint 6 Track W4-B: 工资策略 (WagePolicy) + 时薪规则 (HourlyRateRule) 管理服务.
 *
 * <p>查询 wage mode 顺序:
 * <ol>
 *   <li>per-employee policy (employee_id=N, is_active=true)</li>
 *   <li>factory-level default policy (employee_id IS NULL, is_active=true)</li>
 *   <li>系统兜底 PIECE_RATE (向后兼容 PR #57 E flow)</li>
 * </ol>
 *
 * <p>防呆 (per fool-proof-design.md):
 * <ul>
 *   <li>Rule 1 max: HourlyRateRule.baseHourlyRate ≤ ¥500/h (server-side guard)</li>
 *   <li>Rule 3 dropdown: mode 必为 enum WageMode (DB constraint chk_wage_policy_mode)</li>
 * </ul>
 *
 * @since 2026-05-19
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WagePolicyService {

    /** 时薪上限 (元/小时), 防呆 per fool-proof-design.md Rule 1. */
    public static final BigDecimal MAX_HOURLY_RATE = new BigDecimal("500");

    private final WagePolicyRepository wagePolicyRepository;
    private final HourlyRateRuleRepository hourlyRateRuleRepository;

    // ==================== WagePolicy 查询 ====================

    /**
     * 解析员工实际 wage mode (per-employee → factory default → PIECE_RATE 兜底).
     */
    public WageMode resolveModeForEmployee(String factoryId, Long employeeId) {
        // 1. per-employee policy
        Optional<WagePolicy> empPolicy = wagePolicyRepository
                .findByFactoryIdAndEmployeeIdAndIsActiveTrue(factoryId, employeeId);
        if (empPolicy.isPresent()) {
            return empPolicy.get().getMode();
        }
        // 2. factory-level default
        Optional<WagePolicy> defaultPolicy = wagePolicyRepository.findFactoryDefault(factoryId);
        if (defaultPolicy.isPresent()) {
            return defaultPolicy.get().getMode();
        }
        // 3. 系统兜底 (PR #57 E 向后兼容)
        return WageMode.PIECE_RATE;
    }

    /**
     * 解析 effective policy (含完整 metadata, 不只 mode).
     */
    public Optional<WagePolicy> resolveEffectivePolicy(String factoryId, Long employeeId) {
        Optional<WagePolicy> empPolicy = wagePolicyRepository
                .findByFactoryIdAndEmployeeIdAndIsActiveTrue(factoryId, employeeId);
        if (empPolicy.isPresent()) {
            return empPolicy;
        }
        return wagePolicyRepository.findFactoryDefault(factoryId);
    }

    /**
     * 列工厂所有 policy.
     */
    public List<WagePolicy> listPolicies(String factoryId) {
        return wagePolicyRepository.findByFactoryIdOrderByEmployeeIdAscIdDesc(factoryId);
    }

    // ==================== WagePolicy CRUD ====================

    /**
     * 创建或更新 policy. 同 (factoryId, employeeId) 只允许一条 active.
     */
    @Transactional
    public WagePolicy savePolicy(String factoryId, WagePolicy policy) {
        policy.setFactoryId(factoryId);
        if (policy.getMode() == null) {
            policy.setMode(WageMode.PIECE_RATE);  // 兜底
        }
        if (policy.getIsActive() == null) {
            policy.setIsActive(true);
        }
        return wagePolicyRepository.save(policy);
    }

    @Transactional
    public void deletePolicy(Long policyId) {
        WagePolicy p = wagePolicyRepository.findById(policyId)
                .orElseThrow(() -> new RuntimeException("WagePolicy 不存在: " + policyId));
        wagePolicyRepository.delete(p);  // soft delete via @Where + audit
    }

    // ==================== HourlyRateRule 查询 ====================

    /**
     * 取员工指定日期 effective 的时薪规则.
     *
     * <p>用 {@link HourlyRateRuleRepository#findEffectiveRules} 而非 {@code findEffectiveOn} default 方法 —
     * Mockito 不能正确 stub 接口 default 方法.
     */
    public Optional<HourlyRateRule> findEffectiveHourlyRate(String factoryId, Long employeeId, LocalDate date) {
        List<HourlyRateRule> rules = hourlyRateRuleRepository.findEffectiveRules(factoryId, employeeId, date);
        return rules.isEmpty() ? Optional.empty() : Optional.of(rules.get(0));
    }

    /**
     * 列工厂所有 HourlyRateRule (admin UI).
     */
    public List<HourlyRateRule> listHourlyRateRules(String factoryId) {
        return hourlyRateRuleRepository.findByFactoryIdOrderByEmployeeIdAscEffectiveFromDesc(factoryId);
    }

    /**
     * 列员工历史 HourlyRateRule (改 effective_from / effective_to 历史 audit).
     */
    public List<HourlyRateRule> listEmployeeHourlyRateRules(String factoryId, Long employeeId) {
        return hourlyRateRuleRepository.findByFactoryIdAndEmployeeIdOrderByEffectiveFromDesc(factoryId, employeeId);
    }

    // ==================== HourlyRateRule CRUD ====================

    /**
     * 创建或更新 HourlyRateRule.
     *
     * <p>防呆 (per fool-proof-design.md Rule 1): baseHourlyRate ≤ MAX_HOURLY_RATE (¥500/h),
     * server-side guard 即使 UI 绕过 也阻断.
     */
    @Transactional
    public HourlyRateRule saveHourlyRateRule(String factoryId, HourlyRateRule rule) {
        rule.setFactoryId(factoryId);
        if (rule.getOvertimeMultiplier() == null) {
            rule.setOvertimeMultiplier(new BigDecimal("1.5"));
        }
        if (rule.getIsActive() == null) {
            rule.setIsActive(true);
        }
        // Rule 1 server-side guard
        if (rule.getBaseHourlyRate() == null || rule.getBaseHourlyRate().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("时薪必须 > 0");
        }
        if (rule.getBaseHourlyRate().compareTo(MAX_HOURLY_RATE) > 0) {
            throw new IllegalArgumentException(
                    "时薪超过上限 ¥" + MAX_HOURLY_RATE + "/h, 请重新核对 (防呆 Rule 1)");
        }
        if (rule.getOvertimeMultiplier().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("加班倍率必须 > 0");
        }
        if (rule.getEffectiveTo() != null && rule.getEffectiveFrom() != null
                && rule.getEffectiveTo().isBefore(rule.getEffectiveFrom())) {
            throw new IllegalArgumentException("失效日期不能早于生效日期");
        }
        return hourlyRateRuleRepository.save(rule);
    }

    @Transactional
    public void deleteHourlyRateRule(Long ruleId) {
        HourlyRateRule r = hourlyRateRuleRepository.findById(ruleId)
                .orElseThrow(() -> new RuntimeException("HourlyRateRule 不存在: " + ruleId));
        hourlyRateRuleRepository.delete(r);  // soft delete
    }
}
