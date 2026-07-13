package com.cretas.aims.logistics.entity.enums;

/**
 * 运力诊断结论 — {@link com.cretas.aims.logistics.dto.plan.CapacityDiagnosisDto#getVerdict()}。
 * 纯计算字段（不持久化到 DB），生成/查看计划时按 {@link com.cretas.aims.logistics.util.CapacityDiagnosis}
 * 现算现返，序列化同 {@link PlanStatus}/{@link PlanDistanceSource} 惯例（大写 enum name，不做小写转换）。
 */
public enum CapacityVerdict {
    /** 车队单轮运力（在册活跃车辆容量之和）覆盖本批全部需求，且没有车需要跑第二趟。 */
    SUFFICIENT,
    /** 全部门店均已排入车次（无未分配），但至少一辆车需回仓补货再出发（单轮运力不足）。 */
    INSUFFICIENT,
    /** 仍有门店订单未能排入任何车次（无车覆盖该区域，或单件体积/重量超最大车）。 */
    UNSERVABLE
}
