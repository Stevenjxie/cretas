-- D1 双仓流转: 给 material_batches 加 warehouse_id 字段
-- Spec: docs/superpowers/specs/2026-05-10-d1-dual-warehouse-spec-amendment.md
-- 推翻 V3 P0-5 B2 "Rejected" ADR (PR #309 A1=A Steve sign-off)
--
-- 默认仓: WH-LOG (物流仓) — 原料默认在物流仓持久库存, 调拨进车间才到 WH-WKS.
-- FK target: factory_warehouses.id (PK, VARCHAR(64)) — 不是 (factory_id, code) 复合 UK.

-- Pre-flight: 检查 factory_warehouses 表存在 + 每个 factory 都有 WH-LOG seed
DO $$
DECLARE
    factories_without_wh_log INT;
BEGIN
    -- 确认 factory_warehouses 表存在 (V20260411_03 已创建)
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = current_schema() AND table_name = 'factory_warehouses'
    ) THEN
        RAISE EXCEPTION 'Pre-flight failed: factory_warehouses table missing. V20260411_03 must run first.';
    END IF;

    -- 检查有 material_batches 的 factory 都有 WH-LOG seed
    SELECT COUNT(DISTINCT mb.factory_id)
    INTO factories_without_wh_log
    FROM material_batches mb
    WHERE NOT EXISTS (
        SELECT 1 FROM factory_warehouses fw
        WHERE fw.factory_id = mb.factory_id
          AND fw.code = 'WH-LOG'
          AND fw.deleted_at IS NULL
    );

    IF factories_without_wh_log > 0 THEN
        RAISE EXCEPTION 'Pre-flight failed: % factories with material_batches lack WH-LOG seed in factory_warehouses', factories_without_wh_log;
    END IF;
END $$;

-- Step 1: Add column nullable
ALTER TABLE material_batches ADD COLUMN IF NOT EXISTS warehouse_id VARCHAR(64);

-- Step 2: Backfill — 所有现有 batch 默认归 WH-LOG (物流仓)
UPDATE material_batches mb
   SET warehouse_id = (
       SELECT fw.id FROM factory_warehouses fw
        WHERE fw.factory_id = mb.factory_id
          AND fw.code = 'WH-LOG'
          AND fw.deleted_at IS NULL
        LIMIT 1
   )
 WHERE mb.warehouse_id IS NULL;

-- Step 3: 验证全部 backfilled
DO $$
DECLARE
    missing_count INT;
BEGIN
    SELECT COUNT(*) INTO missing_count FROM material_batches WHERE warehouse_id IS NULL;
    IF missing_count > 0 THEN
        RAISE EXCEPTION 'Migration aborted: % material_batches rows have NULL warehouse_id after backfill (factory missing WH-LOG seed)', missing_count;
    END IF;
END $$;

-- Step 4: NOT NULL + FK constraint + index
ALTER TABLE material_batches ALTER COLUMN warehouse_id SET NOT NULL;

ALTER TABLE material_batches ADD CONSTRAINT fk_material_batch_warehouse
    FOREIGN KEY (warehouse_id) REFERENCES factory_warehouses(id);

CREATE INDEX idx_material_batch_warehouse ON material_batches(factory_id, warehouse_id);

COMMENT ON COLUMN material_batches.warehouse_id IS
    'FK to factory_warehouses.id. 客户 D1 双仓流转 (2026-05-10 spec). 默认 WH-LOG.';

-- Post-verify
DO $$
DECLARE
    total_batches INT;
    backfilled_batches INT;
BEGIN
    SELECT COUNT(*) INTO total_batches FROM material_batches;
    SELECT COUNT(*) INTO backfilled_batches FROM material_batches WHERE warehouse_id IS NOT NULL;
    RAISE NOTICE 'V20260510_03: % material_batches rows; % backfilled with warehouse_id', total_batches, backfilled_batches;
END $$;
