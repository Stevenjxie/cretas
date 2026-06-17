package com.cretas.aims.dto.customer;

import com.cretas.aims.entity.enums.CreditStatus;
import com.cretas.aims.security.PriceSensitive;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 客户信用状态 DTO (P1 #23 S-CREDIT-1, 2026-05-17).
 *
 * <p>由 {@link com.cretas.aims.service.crm.CustomerCreditCheckService#checkCreditAvailable}
 * 返回, 供 SO 创建前预检 + 客户信用面板展示.
 *
 * <p>防呆 R1 — UI 必须 prefer 此 DTO 的 {@code available} / {@code used} 显示边界,
 * 不要让用户 "提交后才知道超额".
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "客户信用状态 (P1 #23 S-CREDIT-1)")
public class CreditStatusDTO {

    @Schema(description = "客户 ID")
    private String customerId;

    @Schema(description = "客户名称 (R2 context)")
    private String customerName;

    @Schema(description = "信用额度 (null = 无限制)")
    private BigDecimal creditLimit;

    @Schema(description = "已使用信用 (= currentBalance, 未结清应收)")
    private BigDecimal used;

    @Schema(description = "可用额度 = creditLimit - used (null = 无限制)")
    private BigDecimal available;

    @PriceSensitive
    @Schema(description = "本次请求金额 (即将占用)")
    private BigDecimal requestedAmount;

    @Schema(description = "本次请求是否超出可用额度")
    private boolean exceeds;

    @Schema(description = "使用率 0~1 (used / creditLimit, 无限制时为 0)")
    private BigDecimal utilizationRate;

    @Schema(description = "信用状态 (NORMAL/WARNING/SUSPENDED)")
    private CreditStatus creditStatus;

    @Schema(description = "信用账期天数")
    private Integer creditPeriodDays;

    @Schema(description = "前端 R5 提示文案 / 操作引导. e.g. 'SUSPENDED 状态, 请联系销售管理员解除'")
    private String suggestedAction;

    @Schema(description = "颜色等级 (green/yellow/red) 用于 R1 边界显示")
    private String severity;
}
