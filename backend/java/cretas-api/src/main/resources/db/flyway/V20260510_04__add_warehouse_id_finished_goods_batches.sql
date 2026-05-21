-- D1 双仓流转: 给 finished_goods_batches 加 warehouse_id 字段
-- Spec: docs/superpowers/specs/2026-05-10-d1-dual-warehouse-spec-amendment.md
--
-- 默认仓: WH-WKS (车间仓) — 成品诞生于生产, 反向调拨前在车间仓.
--          反向调拨后才到 WH-LOG, 销售从 WH-LOG 出.
-- FK target: factory_warehouses.id (PK, VARCHAR(64)).

-- Pre-flight: factory_warehouses 表 + WH-WKS seed 检查
DO $$
DECLARE
    factories_without_wh_wks INT;
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = current_schema() AND table_name = 'factory_warehouses'
    ) THEN
        RAISE EXCEPTION 'Pre-flight failed: factory_warehouses table missing. V20260411_03 must run first.';
    END IF;

    SELECT COUNT(DISTINCT fgb.factory_id)
    INTO factories_without_wh_wks
    FROM finished_goods_batches fgb
    WHERE NOT EXISTS (
        SELECT 1 FROM factory_warehouses fw
        WHERE fw.factory_id = fgb.factory_id
          AND fw.code = 'WH-WKS'
          AND fw.deleted_at IS NULL
    );

    IF factories_without_wh_wks > 0 THEN
        RAISE EXCEPTION 'Pre-flight failed: % factories with finished_goods_batches lack WH-WKS seed in factory_warehouses', factories_without_wh_wks;
    END IF;
END $$;

-- Step 1: Add column nullable
ALTER TABLE finished_goods_batches ADD COLUMN IF NOT EXISTS warehouse_id VARCHAR(64);

-- Step 2: Backfill — 成品默认归 WH-WKS (车间仓)
UPDATE finished_goods_batches fgb
   SET warehouse_id = (
       SELECT fw.id FROM factory_warehouses fw
        WHERE fw.factory_id = fgb.factory_id
          AND fw.code = 'WH-WKS'
          AND fw.deleted_at IS NULL
        LIMIT 1
   )
 WHERE fgb.warehouse_id IS NULL;

-- Step 3: 验证全部 backfilled
DO $$
DECLARE
    missing_count INT;
BEGIN
    SELECT COUNT(*) INTO missing_count FROM finished_goods_batches WHERE warehouse_id IS NULL;
    IF missing_count > 0 THEN
        RAISE EXCEPTION 'Migration aborted: % finished_goods_batches rows have NULL warehouse_id after backfill (factory missing WH-WKS seed)', missing_count;
    END IF;
END $$;

-- Step 4: NOT NULL + FK constraint + index
ALTER TABLE finished_goods_batches ALTER COLUMN warehouse_id SET NOT NULL;

ALTER TABLE finished_goods_batches ADD CONSTRAINT fk_finished_batch_warehouse
    FOREIGN KEY (warehouse_id) REFERENCES factory_warehouses(id);

CREATE INDEX idx_finished_batch_warehouse ON finished_goods_batches(factory_id, warehouse_id);

COMMENT ON COLUMN finished_goods_batches.warehouse_id IS
    'FK to factory_warehouses.id. 客户 D1 双仓流转 (2026-05-10 spec). 默认 WH-WKS.';

-- Post-verify
DO $$
DECLARE
    total_batches INT;
    backfilled_batches INT;
BEGIN
    SELECT COUNT(*) INTO total_batches FROM finished_goods_batches;
    SELECT COUNT(*) INTO backfilled_batches FROM finished_goods_batches WHERE warehouse_id IS NOT NULL;
    RAISE NOTICE 'V20260510_04: % finished_goods_batches rows; % backfilled with warehouse_id', total_batches, backfilled_batches;
END $$;
