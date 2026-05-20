package com.cretas.aims.entity.enums;

/**
 * 凭证分录辅助核算类型 (Sprint 5 Track F F-2).
 *
 * <p>来源: R-HJ Round 13 §2 实测 — 宏见 ERP 凭证 detail 模块 "辅助类型" select 7 类:
 * 客户 / 供应商 / 部门 / 职员 / 项目 / 存货 / 委外商.
 *
 * <p>每条凭证分录 ({@link com.cretas.aims.entity.finance.VoucherEntry}) 可附 0 或 1 个辅助核算:
 * 当 {@code auxiliaryType} 非空时, {@code auxiliaryEntityId} 必须指向对应类型的实体 PK
 * (polymorphic FK by convention, not enforced by DB FK 因 7 类目标表不同).
 *
 * <p>报表 (R-HJ Round 11 §G.1 凭证明细 + 余额表) 按 auxiliaryType+entityId 聚合, 实现
 * "客户应收明细账" / "供应商应付明细账" / "部门费用账" / "项目核算账" 等多维度账簿.
 */
public enum AuxiliaryType {
    /** 客户 — 应收/收入科目按客户分账 (e.g. 1122 应收账款) */
    CUSTOMER,
    /** 供应商 — 应付/采购科目按供应商分账 (e.g. 2202 应付账款) */
    SUPPLIER,
    /** 部门 — 费用科目按部门分账 (e.g. 5601 销售费用) */
    DEPT,
    /** 职员 — 工资/差旅按职员分账 (e.g. 2211 应付职工薪酬) */
    EMPLOYEE,
    /** 项目 — 项目核算 (e.g. R&D 项目成本归集) */
    PROJECT,
    /** 存货 — 存货科目按 SKU/批次分账 (e.g. 1405 库存商品) */
    INVENTORY,
    /** 委外商 — 委外加工 (e.g. 委托代销/代加工往来) */
    OUTSOURCER
}
