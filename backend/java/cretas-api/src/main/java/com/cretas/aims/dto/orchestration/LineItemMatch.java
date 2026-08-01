package com.cretas.aims.dto.orchestration;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 销售订单行项目库存匹配结果
 * 表示订单中单个产品品类的库存满足情况
 *
 * <p>⚠️ 本类会被整体存进 {@code sales_order_shortage_report.available}（jsonb）。
 * 往这里加<b>计算属性</b>（有 getter 但没有 backing field）会让 Hibernate 在 flush
 * 前的 deep-copy 里抛异常 —— 而那发生在 <b>SQL 发出之前</b>，整笔事务回滚，调用方
 * 只看到一条 "fan-out failed"。真出过这个事故，见 {@link #isFullySatisfied()}。
 * 契约测试：{@code JsonbPayloadRoundTripContractTest}。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class LineItemMatch {

    /** 销售订单行 ID (预留台账归属, 2026-07-06)。 */
    private String salesOrderItemId;

    /** 产品类型ID */
    private String productTypeId;

    /** 产品类型名称 */
    private String productTypeName;

    /** 订单需求数量 */
    private BigDecimal requiredQuantity;

    /** 当前可用成品库存数量 */
    private BigDecimal availableQuantity;

    /**
     * 缺口数量。
     * 若 availableQuantity >= requiredQuantity，则缺口为负数（表示有富余）；
     * 若 availableQuantity < requiredQuantity，则缺口为正数（表示不足）。
     */
    private BigDecimal shortfallQuantity;

    /**
     * 判断该行项目是否库存充足（无缺口）
     *
     * <p>🔴 {@code @JsonIgnore} 不可去掉。这是从 {@link #shortfallQuantity} 算出来的，
     * 不是状态。没有它时 Jackson 会把 {@code "fullySatisfied":false} 写进 jsonb，
     * 回读时找不到落点（本类没有 {@code setFullySatisfied}）而抛
     * {@code UnrecognizedPropertyException}。
     *
     * <p>后果不是"少存一个字段"，而是 <b>flush 阶段抛异常 → 整笔事务回滚 → SQL 根本没发出去</b>：
     * {@code sales_order_shortage_report} 建表至今 0 行、{@code PP-AUTO-*} 停在 2026-04-15，
     * 77 次 fan-out 丢写里的 76 次是这个成因。业务上表现为「销售订单财审通过后
     * 不再自动产出缺料报告 / 采购建议 / 生产计划」，且**不报错**。
     *
     * @return true 表示可用量满足需求量
     */
    @JsonIgnore
    public boolean isFullySatisfied() {
        return shortfallQuantity.compareTo(BigDecimal.ZERO) <= 0;
    }
}
