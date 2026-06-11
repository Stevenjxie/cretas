package com.cretas.aims.dto.pricing;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * P1 #33 销售价 ≥ 研发预估价 跨流校验结果 DTO (G流 SP10 报价 → E流销售订单).
 *
 * <p>研发预估价来源 (SP10 QuotationTask, 三价链路 预报价→中报价→最终):
 * {@code finalPrice} (FINAL 最终确认售价) 优先, 无则 {@code suggestedPrice} (PRE 预报价建议售价).
 * 二者均为 R&D 给出的 <strong>建议销售价</strong> (非成本), 故与销售订单 unitPrice 同口径可比。
 *
 * <p><strong>安全约束 (price-sensitive 数据泄漏防护)</strong>:
 * 此 DTO <strong>绝对禁止</strong>包含以下字段:
 * <ul>
 *   <li>{@code estimatePrice} — 研发预估售价绝对值, {@code QuotationTask.suggestedPrice /
 *       finalPrice} 均标 {@code @PriceSensitive}, 营销/销售角色不应获知绝对值</li>
 *   <li>{@code suggestedPrice} / {@code finalPrice} / {@code totalCost} — 同理</li>
 * </ul>
 * 销售员仅获知"是否低于研发预估价"及"提示文案", 不能推算具体预估价数值或成本结构。
 *
 * <p>结构镜像 {@link GrossMarginCheckResult} (#693 毛利红线), 复用其非阻断 (warn-not-block) 范式。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EstimatePriceCheckResult {

    /**
     * 是否低于研发预估价.
     * <ul>
     *   <li>{@code true}  — 销售报价低于研发预估售价, 建议复核</li>
     *   <li>{@code false} — 销售报价 ≥ 研发预估售价</li>
     *   <li>{@code null}  — 研发未报价 / 无预估价数据 (跳过检查, 诚实空值不伪造)</li>
     * </ul>
     */
    private Boolean belowEstimate;

    /**
     * 给用户的提示文案 (不含任何预估价/成本数值).
     * <ul>
     *   <li>belowEstimate=true  → "报价低于研发预估价，建议复核"</li>
     *   <li>belowEstimate=false → "报价不低于研发预估价"</li>
     *   <li>belowEstimate=null  → "无研发预估价数据，已跳过检查"</li>
     * </ul>
     */
    private String warningMessage;

    // =========================================================
    // ⛔ 以下字段永远不可添加:
    //    private BigDecimal estimatePrice;
    //    private BigDecimal suggestedPrice;
    //    private BigDecimal finalPrice;
    //    private BigDecimal totalCost;
    // 如需添加，必须先经过 Opus organizer 安全审查。
    // =========================================================

    public static EstimatePriceCheckResult pass() {
        return new EstimatePriceCheckResult(false, "报价不低于研发预估价");
    }

    public static EstimatePriceCheckResult belowEstimate() {
        return new EstimatePriceCheckResult(true, "报价低于研发预估价，建议复核");
    }

    public static EstimatePriceCheckResult skipped() {
        return new EstimatePriceCheckResult(null, "无研发预估价数据，已跳过检查");
    }
}
