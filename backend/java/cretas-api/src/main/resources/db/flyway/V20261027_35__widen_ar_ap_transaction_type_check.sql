-- 期初建账 AP 冲正修正 (POST /{factoryId}/finance/opening/correct-misrouted-ap) 500 根因:
-- ar_ap_transactions 的 ck_aat_type CHECK 只允许 6 类
-- ('AR_INVOICE','AR_PAYMENT','AR_ADJUSTMENT','AP_INVOICE','AP_PAYMENT','AP_ADJUSTMENT')
-- (来源: legacy database/p4_pos_arap_pg.sql line 32, 非 Flyway 迁移, 手工建于 prod)。
-- ArApTransactionType.java 早已定义 AP_CREDIT_NOTE + AR_CREDIT_NOTE (被
-- SupplierMonthlyReconciliationServiceImpl / PaymentRequestServiceImpl 使用), 但 DB CHECK
-- 未同步加宽 → 插入 AP_CREDIT_NOTE 行时触发 ck_aat_type 违反, 阻断六膳门 ¥436k AP 冲正。
-- 同 V20261027_15/29/34 (枚举加了但 DB CHECK 未加宽) 的教训 — 第 3 次同一模式。
-- 单测 mock repo / H2 均照不到该 PG-only 具名约束, 仅 prod/test PG 才暴 (已在 prod+test 两库确认缺失)。
--
-- 修法: DROP + 重建 ck_aat_type, 含 ArApTransactionType 枚举全部 8 个值。幂等 (DROP IF EXISTS + ADD)。
-- 顺带 drop 可能存在的 Hibernate 自动命名约束 (防某些环境用了默认名)。

ALTER TABLE ar_ap_transactions DROP CONSTRAINT IF EXISTS ck_aat_type;
ALTER TABLE ar_ap_transactions DROP CONSTRAINT IF EXISTS ar_ap_transactions_transaction_type_check;

ALTER TABLE ar_ap_transactions
    ADD CONSTRAINT ck_aat_type CHECK (transaction_type IN (
        'AR_INVOICE',
        'AR_PAYMENT',
        'AR_ADJUSTMENT',
        'AR_CREDIT_NOTE',
        'AP_INVOICE',
        'AP_PAYMENT',
        'AP_ADJUSTMENT',
        'AP_CREDIT_NOTE'
    ));
