package com.cretas.aims.service.pricing;

import com.cretas.aims.dto.pricing.EstimatePriceCheckResult;

import java.math.BigDecimal;

/**
 * P1 #33 销售价 ≥ 研发预估价 跨流校验服务 (G流 SP10 → E流销售订单).
 *
 * <p>转录需求: 销售订单价不应低于研发预估价 (SP10 报价)。当前缺此跨流校验 →
 * 销售可低于研发建议售价下单。
 *
 * <p><strong>决策对齐 (#693 毛利红线防呆)</strong>: 低于预估价 = <strong>警告放行</strong>
 * (200 + 警告字段), <strong>不</strong> 409 硬拦 (防呆 Rule 1: 提交前看到边界, 不提交后被拒)。
 *
 * <p>研发预估价 = SP10 {@code QuotationTask} 的建议销售价:
 * {@code finalPrice} (FINAL 最终确认) 优先, 无则 {@code suggestedPrice} (PRE 预报价建议售价)。
 * 二者均为 R&D 给出的建议 <strong>销售价</strong> (非成本), 与销售订单 unitPrice 同口径。
 *
 * <p><strong>安全约束</strong>: 返回的 {@link EstimatePriceCheckResult} 不含 estimatePrice /
 * suggestedPrice / finalPrice 等价格敏感字段, 避免研发预估价绝对值泄露给销售角色。
 *
 * <p>无研发预估价 (研发未报价) → {@code skipped} (诚实跳过, 不伪造警告)。
 */
public interface EstimatePriceCheckService {

    /**
     * 检查销售报价是否低于研发预估价.
     *
     * @param factoryId     工厂 ID (租户隔离)
     * @param productTypeId 产品类型 ID
     * @param sellingPrice  销售单价 (per-unit, 已含定价策略折扣后的最终单价)
     * @return 检查结果 (pass / belowEstimate / skipped)
     */
    EstimatePriceCheckResult checkAgainstEstimate(String factoryId, String productTypeId,
                                                  BigDecimal sellingPrice);
}
