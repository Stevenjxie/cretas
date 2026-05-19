package com.cretas.aims.service.pricing;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

/**
 * 价格策略计算请求 — Canvas-Pricing Phase 4b.
 *
 * <p>1:1 字段对齐 SalesService.createOrderLine 上下文.
 * Sister chat 在 {@code SalesServiceImpl} 替换 1 行 hardcoded price 时构造此对象.
 *
 * <p>Optional fields (NULL allowed):
 * <ul>
 *   <li>{@code customerId} — null = anonymous quote (无客户上下文 MEMBER strategy 不适用)</li>
 *   <li>{@code productCategory} / {@code customerGroup} / {@code region} —
 *       null = scope_filter_json 中对应 key 不参与过滤 (匹配所有)</li>
 *   <li>{@code costEstimate} — per-unit cost (商品 master {@code standard_cost} 或 RMMA 取).
 *       NULL = skip fool-proof "below cost" warning (spec §4 + §10 Q1).
 *       Caller (SalesServiceImpl) 决定 cost source.</li>
 * </ul>
 */
@Value
@Builder
public class PricingRequest {
    String factoryId;
    String productId;
    Integer quantity;
    BigDecimal unitPriceList;
    Long customerId;
    String customerGroup;     // optional, e.g. "VIP"
    String productCategory;   // optional, e.g. "frozen"
    String region;            // optional, e.g. "east"
    String businessEntityType;
    String businessEntityId;
    /** Per-unit cost estimate for fool-proof warning (spec §4). NULL = skip cost check. */
    BigDecimal costEstimate;
}
