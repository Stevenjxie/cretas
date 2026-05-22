package com.cretas.aims.service.crm.impl;

import com.cretas.aims.dto.customer.CreditStatusDTO;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.enums.CreditStatus;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.service.canvas.ThresholdKeys;
import com.cretas.aims.service.canvas.ThresholdResolverService;
import com.cretas.aims.service.crm.CustomerCreditCheckService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * P1 #23 S-CREDIT-1 — 信用检查实现 (2026-05-17).
 *
 * <p>已用信用 = Customer.currentBalance (由 ArApService.recordReceivable/recordArPayment 维护).
 * 阈值与状态映射:
 * <ul>
 *   <li>SUSPENDED → 直接 RED + suggestedAction "联系销售管理员解除冻结"</li>
 *   <li>requestedAmount + used > limit → RED + exceeds=true + suggestedAction 提示金额</li>
 *   <li>used / limit ≥ 0.8 → YELLOW + suggestedAction 提示接近限额</li>
 *   <li>others → GREEN</li>
 * </ul>
 *
 * <p>无信用额度 (creditLimit IS NULL) 视为无限制, available = null, exceeds = false (永远通过).
 */
@Service
public class CustomerCreditCheckServiceImpl implements CustomerCreditCheckService {

    private static final Logger log = LoggerFactory.getLogger(CustomerCreditCheckServiceImpl.class);

    /** 接近预警阈值 80% (used / limit) — fallback default; 实际值通过 thresholdResolver 读取. */
    private static final BigDecimal FALLBACK_WARNING_RATIO = new BigDecimal("0.80");

    private final CustomerRepository customerRepository;
    private final ThresholdResolverService thresholdResolver;

    public CustomerCreditCheckServiceImpl(CustomerRepository customerRepository,
                                          ThresholdResolverService thresholdResolver) {
        this.customerRepository = customerRepository;
        this.thresholdResolver = thresholdResolver;
    }

    private BigDecimal warningRatio(String factoryId) {
        return thresholdResolver.getBigDecimal(factoryId, ThresholdKeys.CREDIT_WARNING_RATIO, FALLBACK_WARNING_RATIO);
    }

    @Override
    public CreditStatusDTO checkCreditAvailable(String factoryId, String customerId, BigDecimal requestedAmount) {
        Customer customer = customerRepository.findByIdAndFactoryId(customerId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("客户不存在或不属于当前工厂: " + customerId));

        BigDecimal limit = customer.getCreditLimit();
        BigDecimal used = customer.getCurrentBalance() != null ? customer.getCurrentBalance() : BigDecimal.ZERO;
        BigDecimal requested = requestedAmount != null ? requestedAmount : BigDecimal.ZERO;
        CreditStatus status = customer.getCreditStatus() != null
                ? customer.getCreditStatus() : CreditStatus.NORMAL;

        // SUSPENDED 优先级最高 — 不管额度多少都视为不可用
        if (status == CreditStatus.SUSPENDED) {
            return CreditStatusDTO.builder()
                    .customerId(customer.getId())
                    .customerName(customer.getName())
                    .creditLimit(limit)
                    .used(used)
                    .available(BigDecimal.ZERO)
                    .requestedAmount(requested)
                    .exceeds(true)
                    .utilizationRate(computeUtilizationRate(limit, used))
                    .creditStatus(status)
                    .creditPeriodDays(customer.getCreditPeriodDays())
                    .suggestedAction("客户 " + customer.getName() + " 信用已被冻结, 请联系销售管理员解除")
                    .severity("red")
                    .build();
        }

        // 无信用额度 = 无限制
        if (limit == null) {
            return CreditStatusDTO.builder()
                    .customerId(customer.getId())
                    .customerName(customer.getName())
                    .creditLimit(null)
                    .used(used)
                    .available(null)
                    .requestedAmount(requested)
                    .exceeds(false)
                    .utilizationRate(BigDecimal.ZERO)
                    .creditStatus(status)
                    .creditPeriodDays(customer.getCreditPeriodDays())
                    .suggestedAction("此客户未设置信用额度上限, 建议在客户详情中配置")
                    .severity("green")
                    .build();
        }

        BigDecimal available = limit.subtract(used);
        if (available.signum() < 0) {
            available = BigDecimal.ZERO;
        }
        BigDecimal afterRequest = used.add(requested);
        boolean exceeds = afterRequest.compareTo(limit) > 0;
        BigDecimal utilization = computeUtilizationRate(limit, used);

        String severity;
        String suggestedAction;
        if (exceeds) {
            severity = "red";
            BigDecimal overBy = afterRequest.subtract(limit).setScale(2, RoundingMode.HALF_UP);
            suggestedAction = "客户 " + customer.getName() + " 本次下单将超出信用额度 ¥" + overBy
                    + " (限额 ¥" + limit + ", 已用 ¥" + used + "). 建议先收款或调高信用额度.";
        } else if (utilization.compareTo(warningRatio(factoryId)) >= 0) {
            severity = "yellow";
            suggestedAction = "客户 " + customer.getName() + " 信用已使用 "
                    + utilization.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP)
                    + "%, 接近上限. 建议关注回款进度.";
        } else {
            severity = "green";
            suggestedAction = "信用状态正常, 可用额度 ¥" + available.setScale(2, RoundingMode.HALF_UP);
        }

        return CreditStatusDTO.builder()
                .customerId(customer.getId())
                .customerName(customer.getName())
                .creditLimit(limit)
                .used(used)
                .available(available)
                .requestedAmount(requested)
                .exceeds(exceeds)
                .utilizationRate(utilization)
                .creditStatus(status)
                .creditPeriodDays(customer.getCreditPeriodDays())
                .suggestedAction(suggestedAction)
                .severity(severity)
                .build();
    }

    /**
     * 使用率 = used / creditLimit, 4 位精度. limit null/0 时返 0.
     */
    private BigDecimal computeUtilizationRate(BigDecimal limit, BigDecimal used) {
        if (limit == null || limit.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return used.divide(limit, 4, RoundingMode.HALF_UP);
    }
}
