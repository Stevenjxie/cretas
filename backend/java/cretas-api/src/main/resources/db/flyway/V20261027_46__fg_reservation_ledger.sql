-- ============================================================================
-- FG 预留台账 (fg_reservation_ledger) — per-SO/delivery 成品预留台账
-- ----------------------------------------------------------------------------
-- 背景 (2026-07-06):
--   finished_goods_batches.reserved_quantity 原本是一个"匿名聚合"计数器 —— reserveStock
--   (财审触发) 按 FEFO 把预留量摊到批次上, 但没有任何 SO/批次映射; 唯一的释放点是发货
--   (applyShipment Pass2)。这导致两类根因 bug:
--     1) SO 取消 (cancelOrder) 不释放预留 → 永久孤儿 (F001 的 1500 就是这类残留)。
--     2) 发货 Pass1 从"另一批"扣可用量 (而预留在另一批) → 预留批永不被释放 → 孤儿。
--
-- 本表把每一笔预留变成可归属、可精确释放的台账行。不变式:
--     finished_goods_batches.reserved_quantity == Σ(该批次所有 status='ACTIVE' 台账行 reserved_qty)
--   reserved_qty 与 batch.reserved_quantity 同单位 (批次原生单位), 台账层不做单位换算 —— 它
--   记录的就是"写进 batch.reserved 的那个数", 保证不变式逐值相等。
-- ============================================================================

CREATE TABLE IF NOT EXISTS fg_reservation_ledger (
    id                       VARCHAR(191)  NOT NULL,
    factory_id               VARCHAR(64)   NOT NULL,
    sales_order_id           VARCHAR(191)  NOT NULL,
    sales_order_item_id      VARCHAR(191),
    delivery_id              VARCHAR(191),
    delivery_item_id         VARCHAR(191),
    finished_goods_batch_id  VARCHAR(191)  NOT NULL,
    product_type_id          VARCHAR(191)  NOT NULL,
    -- 预留量 (批次原生单位, 与 finished_goods_batches.reserved_quantity 同口径)
    reserved_qty             NUMERIC(15,4) NOT NULL,
    -- ACTIVE = 生效中 (计入 batch.reserved); RELEASED = 已释放 (取消/发货/完成, 不再计入)
    status                   VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',
    -- BaseEntity 审计字段
    created_at               TIMESTAMP     DEFAULT NOW(),
    updated_at               TIMESTAMP     DEFAULT NOW(),
    deleted_at               TIMESTAMP     NULL,
    CONSTRAINT pk_fg_reservation_ledger PRIMARY KEY (id),
    CONSTRAINT ck_fgrl_status CHECK (status IN ('ACTIVE', 'RELEASED')),
    CONSTRAINT ck_fgrl_qty_nonneg CHECK (reserved_qty >= 0)
);

CREATE INDEX IF NOT EXISTS idx_fgrl_factory_so
    ON fg_reservation_ledger (factory_id, sales_order_id);
CREATE INDEX IF NOT EXISTS idx_fgrl_batch_status
    ON fg_reservation_ledger (finished_goods_batch_id, status);

-- 自动维护 updated_at (与项目既有触发器约定一致, 见 database-entity-sync.md)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_proc WHERE proname = 'update_updated_at'
    ) THEN
        CREATE FUNCTION update_updated_at() RETURNS TRIGGER AS $fn$
        BEGIN
            NEW.updated_at = NOW();
            RETURN NEW;
        END;
        $fn$ LANGUAGE plpgsql;
    END IF;
END $$;

DROP TRIGGER IF EXISTS trg_fgrl_updated_at ON fg_reservation_ledger;
CREATE TRIGGER trg_fgrl_updated_at
    BEFORE UPDATE ON fg_reservation_ledger
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

-- ============================================================================
-- Backfill / 对账 (demand-aware, conservative)
-- ----------------------------------------------------------------------------
-- 只读调查 (2026-07-06 prod) 结论:
--   • F001 (测试工厂): 2 批 reserved=1500, product=PT-001/PT-002; 但活跃 (财审/处理/部分发货)
--     SO 的需求都在 *其它* 产品 (PT-F001-001/003 等) → 这些预留产品 **零活跃需求** = 真孤儿。
--   • F006 (六膳门·真实客户): 1 批 reserved=76, product=c2974690...; 同产品有活跃 FINANCE_APPROVED
--     需求 2281 (远大于 76) → 76 与活跃需求吻合, **不是**孤儿。盲目释放会让真实客户"少预留"→ 可能
--     超卖。故对 F006 不释放, 只补台账行归属到最早活跃 SO (发货/完成时自然精确释放, 且 76 ≤ 2281
--     绝不会超卖)。
--
-- 因此 backfill 分两步 (对所有工厂通用, 未来新客户同规则):
--   Step A: 释放"零活跃需求"孤儿 (batch.reserved := 0)。仅动 physical reserved, 不建台账。
--   Step B: 对剩余 reserved>0 的批次补 ACTIVE 台账行, 归属到该产品最早的活跃 SO。不动 reserved。
-- 活跃 (holding) 状态 = FINANCE_APPROVED / PROCESSING / PARTIAL_DELIVERED。
-- ============================================================================

-- Step A — 释放零活跃需求孤儿 (F001 的 1500)。
UPDATE finished_goods_batches b
SET reserved_quantity = 0,
    updated_at = NOW()
WHERE b.deleted_at IS NULL
  AND b.reserved_quantity > 0
  AND NOT EXISTS (
        SELECT 1
        FROM sales_orders so
        JOIN sales_order_items soi ON soi.sales_order_id = so.id
        WHERE so.factory_id = b.factory_id
          AND soi.product_type_id = b.product_type_id
          AND so.status IN ('FINANCE_APPROVED', 'PROCESSING', 'PARTIAL_DELIVERED')
          AND (soi.quantity - COALESCE(soi.delivered_quantity, 0)) > 0
      );

-- Step B — 为剩余 reserved>0 批次补台账行, 归属到最早活跃 SO (F006 的 76)。
INSERT INTO fg_reservation_ledger (
    id, factory_id, sales_order_id, sales_order_item_id,
    finished_goods_batch_id, product_type_id, reserved_qty, status,
    created_at, updated_at
)
SELECT
    gen_random_uuid()::text,
    b.factory_id,
    live.so_id,
    live.soi_id::text,
    b.id,
    b.product_type_id,
    b.reserved_quantity,
    'ACTIVE',
    NOW(), NOW()
FROM finished_goods_batches b
CROSS JOIN LATERAL (
    SELECT so.id AS so_id, soi.id AS soi_id
    FROM sales_orders so
    JOIN sales_order_items soi ON soi.sales_order_id = so.id
    WHERE so.factory_id = b.factory_id
      AND soi.product_type_id = b.product_type_id
      AND so.status IN ('FINANCE_APPROVED', 'PROCESSING', 'PARTIAL_DELIVERED')
      AND (soi.quantity - COALESCE(soi.delivered_quantity, 0)) > 0
    ORDER BY so.created_at ASC
    LIMIT 1
) live
WHERE b.deleted_at IS NULL
  AND b.reserved_quantity > 0
  -- 幂等: 该批次尚无 ACTIVE 台账行时才补 (防重跑重复插)
  AND NOT EXISTS (
        SELECT 1 FROM fg_reservation_ledger l
        WHERE l.finished_goods_batch_id = b.id AND l.status = 'ACTIVE'
      );
