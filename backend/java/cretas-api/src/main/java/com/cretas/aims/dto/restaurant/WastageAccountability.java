package com.cretas.aims.dto.restaurant;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 损耗责任制汇总 DTO (Wave2 损耗按人/档口责任制)。
 *
 * <p>兑现邓总诉求：按人/按档口透明化损耗，"同样 1 万营业额哪个档口哪个人成本涨了"。
 * 仅统计 APPROVED 损耗记录。金额字段的可见性由<b>调用方</b>门控，不在 service 层脱敏：
 * /accountability 端点经 {@code @RequirePermission} 限价权/财务角色 (无权直接 403)；
 * AI 工具 {@code RestaurantWastageSummaryTool} 内显式校验价权后再决定是否输出金额。
 * 本 DTO 的金额字段始终由 service 层按实计算填充。</p>
 *
 * @author Cretas Team
 * @since 2026-06-04
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class WastageAccountability {

    /** 起始日期 (YYYY-MM-DD) */
    private String startDate;

    /** 结束日期 (YYYY-MM-DD) */
    private String endDate;

    /** 时段内总损耗成本（元）；可见性由调用方价权门控，service 层不置 null */
    private BigDecimal totalCost;

    /** 时段内总损耗记录数 */
    private Long totalCount;

    /** 按责任人聚合排行（损耗成本降序） */
    private List<ByOperator> byOperator;

    /** 按档口聚合排行（损耗成本降序） */
    private List<BySection> bySection;

    /**
     * 按责任人聚合明细
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ByOperator {

        /** 责任人用户 ID；null 表示未指定责任人 */
        private Long operatorId;

        /** 责任人姓名（回填）；未指定时显示「未指定」 */
        private String operatorName;

        /** 该责任人损耗记录数 */
        private Long count;

        /** 该责任人损耗总数量 */
        private BigDecimal totalQuantity;

        /** 该责任人损耗成本（元）；可见性由调用方价权门控，service 层不置 null */
        private BigDecimal totalCost;
    }

    /**
     * 按档口聚合明细
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class BySection {

        /** 档口编码 SEAFOOD/COLD_DISH/HOT_DISH/FRONT_HOUSE/OTHER；null 表示未指定 */
        private String sectionCode;

        /** 档口中文名（回填）；未指定时显示「未指定」 */
        private String sectionName;

        /** 该档口损耗记录数 */
        private Long count;

        /** 该档口损耗总数量 */
        private BigDecimal totalQuantity;

        /** 该档口损耗成本（元）；可见性由调用方价权门控，service 层不置 null */
        private BigDecimal totalCost;
    }
}
