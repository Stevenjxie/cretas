-- #29 销售方向付款审批：付款申请表加 sourceType / sales_order_id / customer_id
-- 转录[40:10] "采购订单和销售订单两个方式都可以"
--
-- 双向付款：
--   PURCHASE（采购，SP6/D-9 原有）：对供应商应付，purchase_order_id/supplier_id 必填
--   SALES（销售，#29 新增）：对客户 outbound 退款/返利/销售费用，sales_order_id/customer_id
--
-- 向后兼容：
--   1. source_type 新列默认 'PURCHASE'，回填所有存量行 → 行为不变
--   2. purchase_order_id / supplier_id 放宽 NOT NULL（销售方向时为 NULL，XOR 在 Service 层校验）
--   3. ar_ap_transactions 已有 sales_order_id / counterparty_type=CUSTOMER 能力，无需改动

-- ── Step 1: 新增 source_type 列（先 nullable，回填后再加 NOT NULL）──────────────
ALTER TABLE payment_requests
    ADD COLUMN IF NOT EXISTS source_type VARCHAR(16);

-- 回填存量行为 PURCHASE（行为不变）
UPDATE payment_requests
    SET source_type = 'PURCHASE'
    WHERE source_type IS NULL;

-- 加默认值 + NOT NULL（新建若未显式设置则默认 PURCHASE，与实体默认一致）
ALTER TABLE payment_requests
    ALTER COLUMN source_type SET DEFAULT 'PURCHASE';
ALTER TABLE payment_requests
    ALTER COLUMN source_type SET NOT NULL;

-- ── Step 2: 销售方向关联列 ──────────────────────────────────────────────────
ALTER TABLE payment_requests
    ADD COLUMN IF NOT EXISTS sales_order_id VARCHAR(191);
ALTER TABLE payment_requests
    ADD COLUMN IF NOT EXISTS customer_id    VARCHAR(191);

-- ── Step 3: 放宽采购列 NOT NULL（销售方向时为 NULL）──────────────────────────
ALTER TABLE payment_requests
    ALTER COLUMN purchase_order_id DROP NOT NULL;
ALTER TABLE payment_requests
    ALTER COLUMN supplier_id DROP NOT NULL;

-- ── Step 4: 销售方向查询索引 ────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS idx_payment_requests_so
    ON payment_requests (sales_order_id)
    WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_payment_requests_customer
    ON payment_requests (customer_id)
    WHERE deleted_at IS NULL;

-- ── Step 5: XOR 软约束（DB 层兜底，Service 层为主校验）──────────────────────
-- 采购：purchase_order_id 非空 + sales_order_id 空；销售：customer_id 非空 + purchase_order_id 空。
-- 用 CHECK 防脏数据双向串台。存量数据均为 PURCHASE，purchase_order_id 历史 NOT NULL 必非空 → 通过。
ALTER TABLE payment_requests
    DROP CONSTRAINT IF EXISTS ck_pr_source_direction;
ALTER TABLE payment_requests
    ADD CONSTRAINT ck_pr_source_direction CHECK (
        (source_type = 'PURCHASE' AND purchase_order_id IS NOT NULL AND sales_order_id IS NULL)
        OR
        (source_type = 'SALES' AND customer_id IS NOT NULL AND purchase_order_id IS NULL AND supplier_id IS NULL)
    );

COMMENT ON COLUMN payment_requests.source_type    IS '#29 付款方向：PURCHASE（对供应商应付）/ SALES（对客户 outbound 退款/返利/销售费用）';
COMMENT ON COLUMN payment_requests.sales_order_id IS '#29 销售方向关联销售订单（SALES 时；PURCHASE 时为 NULL）';
COMMENT ON COLUMN payment_requests.customer_id    IS '#29 销售方向交易对手客户（SALES 时必填；PURCHASE 时为 NULL）';
