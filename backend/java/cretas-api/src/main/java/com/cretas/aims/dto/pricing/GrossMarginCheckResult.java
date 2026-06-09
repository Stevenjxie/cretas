package com.cretas.aims.dto.pricing;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SP5 毛利红线检查结果 DTO.
 *
 * <p><strong>安全约束 (price-sensitive 数据泄漏防护)</strong>:
 * 此 DTO <strong>绝对禁止</strong>包含以下字段:
 * <ul>
 *   <li>{@code minPrice} — 最低报价推导自标准成本，属价格敏感信息</li>
 *   <li>{@code standardCost} — 成本数据，仅限有权限角色查看</li>
 *   <li>{@code targetGrossMargin} — 目标毛利率，属内部经营配置</li>
 * </ul>
 * 前端仅获知"是否低于红线"及"提示文案"，不能推算具体成本结构。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GrossMarginCheckResult {

    /**
     * 是否低于毛利红线.
     * <ul>
     *   <li>{@code true}  — 报价低于最低可接受价，建议调整</li>
     *   <li>{@code false} — 报价满足毛利要求</li>
     *   <li>{@code null}  — 未配置毛利红线（跳过检查）</li>
     * </ul>
     */
    private Boolean belowRedline;

    /**
     * 给用户的提示文案.
     * <ul>
     *   <li>belowRedline=true  → "报价低于毛利红线，建议调整"</li>
     *   <li>belowRedline=false → "报价满足毛利要求"</li>
     *   <li>belowRedline=null  → "未配置毛利红线，已跳过检查"</li>
     * </ul>
     */
    private String warningMessage;

    // =========================================================
    // ⛔ 以下字段永远不可添加:
    //    private BigDecimal minPrice;
    //    private BigDecimal standardCost;
    //    private BigDecimal targetGrossMargin;
    // 如需添加，必须先经过 Opus organizer 安全审查。
    // =========================================================

    public static GrossMarginCheckResult pass() {
        return new GrossMarginCheckResult(false, "报价满足毛利要求");
    }

    public static GrossMarginCheckResult warn() {
        return new GrossMarginCheckResult(true, "报价低于毛利红线，建议调整");
    }

    public static GrossMarginCheckResult skipped() {
        return new GrossMarginCheckResult(null, "未配置毛利红线，已跳过检查");
    }
}
