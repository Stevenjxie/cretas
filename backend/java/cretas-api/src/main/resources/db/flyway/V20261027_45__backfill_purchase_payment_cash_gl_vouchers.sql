-- V20261027_45__backfill_purchase_payment_cash_gl_vouchers.sql
--
-- 背景: #1269 (2026-07-06 上线) 修复 PaymentRequestServiceImpl.markPaidPurchase 补发
--   CASH_PAYMENT 现金 GL 事件 (借 2202 应付账款 / 贷 1002 银行存款), 但只向前生效。
--   #1262 起所有采购付款 UI 导流到 markPaidPurchase, 该路径此前只写 AP_PAYMENT 子账、
--   零现金事件 → 历史采购付款在总账/试算平衡/现金流量表漏记现金流出 (子账正确, GL 死账)。
--
-- 本迁移: 幂等补录缺失的 CASH_PAYMENT GL 凭证, 逐字镜像
--   CashMovementVoucherListener.onCashMovement / VoucherServiceImpl.createCashMovementVoucher:
--     - 凭证: type=CASH_PAYMENT, status=DRAFT (业务凭证惯例, 财务手工过账),
--             voucher_date=付款交易日, created_by=operated_by, description='付款 {供应商名}',
--             source_business_type='CASH_PAYMENT', source_business_id=AP_PAYMENT 子账 id,
--             total_debit=total_credit=付款额。
--     - 分录 (借贷必平 chk_voucher_balanced):
--         line 1  借 2202 应付账款  debit=amt  [SUPPLIER 辅助核算=供应商 id]  '冲减应付-{名}'
--         line 2  贷 1002 银行存款  credit=amt (payment_method=CASH → 1001 库存现金) '付款-{名}'
--     - 凭证号: 'V-YYYY-NNNN', YYYY=付款日年份, NNNN=该 (factory, 年) 现有最大序号+序 (镜像
--             generateVoucherNumber, 但基数取 MAX 现有序号 (含软删) 防 uk_voucher_factory_number 冲突)。
--
-- 幂等: NOT EXISTS 守卫 (source_business_type, source_business_id) — 覆盖所有行(含软删),
--   因 uk_voucher_source_business 为非部分唯一索引。再跑不重复插入。
--
-- 供应商名来源: 子账 counterparty_name 在 markPaidPurchase 路径为空; listener 用 supplier.getName(),
--   故 JOIN suppliers 取真实名 (COALESCE fallback 到子账名再到空串, honest-null)。
-- 金额: markPaidPurchase 存正数、recordApPayment 存负数 → 一律 ABS() 取正 (与 listener 一致)。
-- 范围: transaction_type=AP_PAYMENT, approval_status=APPROVED, transaction_date < 2026-07-06,
--   全工厂 (Steve 授权含真客户 F006 六膳门)。
--
-- 只读量化 (prod cretas_prod_db, 2026-07-06):
--   F006 (六膳门食品科技, 真客户): 14 笔, ¥28,447.72  (2026-06-10 ~ 06-24, 北京飞熊×13 + ZTESTD×1)
--   F001 (测试工厂):               3 笔, ¥197,000.00 (2025-02, BANK_TRANSFER)
--   合计 17 笔。

WITH missing AS (
    SELECT
        t.id                                AS tx_id,
        t.factory_id                        AS factory_id,
        t.counterparty_id                   AS counterparty_id,
        COALESCE(NULLIF(TRIM(s.name), ''), NULLIF(TRIM(t.counterparty_name), ''), '') AS cp_name,
        ABS(t.amount)                       AS amt,
        t.payment_method                    AS payment_method,
        t.transaction_date                  AS transaction_date,
        t.operated_by                       AS operated_by,
        to_char(t.transaction_date, 'YYYY') AS yr
    FROM ar_ap_transactions t
    LEFT JOIN suppliers s ON s.id = t.counterparty_id
    WHERE t.transaction_type = 'AP_PAYMENT'
      AND t.deleted_at IS NULL
      AND t.approval_status = 'APPROVED'
      AND t.transaction_date < DATE '2026-07-06'
      AND ABS(t.amount) > 0
      AND NOT EXISTS (
          SELECT 1 FROM vouchers v
          WHERE v.source_business_type = 'CASH_PAYMENT'
            AND v.source_business_id = t.id
      )
),
base AS (
    -- 每 (factory, 年) 现有最大凭证序号 (含软删行, 防 uk_voucher_factory_number 唯一冲突)
    SELECT
        d.factory_id AS factory_id,
        d.yr         AS yr,
        COALESCE(MAX((substring(v.voucher_number FROM 'V-[0-9]{4}-([0-9]+)'))::int), 0) AS base_suffix
    FROM (SELECT DISTINCT factory_id, yr FROM missing) d
    LEFT JOIN vouchers v
           ON v.factory_id = d.factory_id
          AND v.voucher_number LIKE 'V-' || d.yr || '-%'
    GROUP BY d.factory_id, d.yr
),
numbered AS (
    SELECT
        m.*,
        b.base_suffix + ROW_NUMBER() OVER (
            PARTITION BY m.factory_id, m.yr
            ORDER BY m.transaction_date, m.tx_id
        ) AS seq
    FROM missing m
    JOIN base b ON b.factory_id = m.factory_id AND b.yr = m.yr
),
ins_voucher AS (
    INSERT INTO vouchers (
        id, factory_id, voucher_number, voucher_type, voucher_date,
        source_business_type, source_business_id, total_debit, total_credit,
        status, created_by, description, created_at, updated_at
    )
    SELECT
        gen_random_uuid()::text,
        n.factory_id,
        'V-' || n.yr || '-' || lpad(n.seq::text, 4, '0'),
        'CASH_PAYMENT',
        n.transaction_date,
        'CASH_PAYMENT',
        n.tx_id,
        n.amt,
        n.amt,
        'DRAFT',
        n.operated_by,
        '付款 ' || n.cp_name,
        now(), now()
    FROM numbered n
    RETURNING id AS voucher_id, source_business_id AS tx_id
)
INSERT INTO voucher_entries (
    id, voucher_id, line_no, subject_code, subject_name, description,
    debit, credit, auxiliary_type, auxiliary_entity_id, created_at, updated_at
)
SELECT
    gen_random_uuid()::text,
    iv.voucher_id,
    e.line_no, e.subject_code, e.subject_name, e.description,
    e.debit, e.credit, e.auxiliary_type, e.auxiliary_entity_id,
    now(), now()
FROM ins_voucher iv
JOIN numbered n ON n.tx_id = iv.tx_id
CROSS JOIN LATERAL (
    VALUES
        (1, '2202', '应付账款',
            '冲减应付-' || n.cp_name,
            n.amt, CAST(0 AS numeric(15,2)),
            CAST('SUPPLIER' AS varchar), CAST(n.counterparty_id AS varchar)),
        (2,
            CASE WHEN n.payment_method = 'CASH' THEN '1001' ELSE '1002' END,
            CASE WHEN n.payment_method = 'CASH' THEN '库存现金' ELSE '银行存款' END,
            '付款-' || n.cp_name,
            CAST(0 AS numeric(15,2)), n.amt,
            CAST(NULL AS varchar), CAST(NULL AS varchar))
) AS e(line_no, subject_code, subject_name, description, debit, credit, auxiliary_type, auxiliary_entity_id);
