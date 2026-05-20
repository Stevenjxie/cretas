package com.cretas.aims.entity.enums;

/**
 * 会计科目余额方向 (中国 GAAP 标准).
 *
 * <p>用于报表三表 (Sprint 7 T3) 计算: 资产/成本/费用 科目按 DEBIT_NORMAL 取
 * SUM(debit) - SUM(credit); 负债/权益/收入 按 CREDIT_NORMAL 取
 * SUM(credit) - SUM(debit). 跟 Account 实体绑定, voucher 不可推翻.
 */
public enum AccountBalanceType {
    /** 借方余额 — 资产 / 成本 / 费用类科目 (借增贷减) */
    DEBIT_NORMAL,
    /** 贷方余额 — 负债 / 所有者权益 / 收入类科目 (贷增借减) */
    CREDIT_NORMAL
}
