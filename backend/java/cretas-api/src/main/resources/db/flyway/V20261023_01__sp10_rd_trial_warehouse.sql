-- V20261023_01__sp10_rd_trial_warehouse.sql
--
-- SP10 §RD-1: 研发/中试库 — 试制批次 (is_trial=true) 产出独立库存。
--
-- 背景:
--   ProductionBatch.is_trial 字段由 V20261011_16 加入，标识研发试制批次。
--   但业务逻辑零消费 is_trial: 试制产出与常规成品同入 WH-WKS 车间仓，
--   混入可售库存 → 备货看板/销售库存被试制数量虚增，误导排产决策。
--
-- 修复方案:
--   1. WarehouseType 枚举加 RD 值 (Java 侧已改, 此迁移同步 DB 约束)。
--   2. 扩展 CHECK 约束: chk_factory_warehouse_type 含 'RD'。
--   3. 为有试制需求的工厂 seed WH-RD (研发/中试库):
--      - F006 (六扇门) — SP10 试制场景的核心工厂
--      - F001, F999 — 测试/演示工厂，保证单测/集成环境可用
--   4. WarehouseCodes.WH_RD = "WH-RD", WarehouseResolver.resolveRdId() 已加。
--   5. SupplyChainOrchestrator: is_trial=true 批次完工 → 进 WH-RD，非 WH-WKS。
--   6. RestockBoardService.finishedGoods() → 排除 WH-RD，不污染可售库存汇总。
--
-- 幂等: ON CONFLICT (factory_id, code) DO NOTHING。

-- ============================================================================
-- Step 1: 扩展 WarehouseType CHECK 约束，加入 'RD'
-- ============================================================================
-- DROP 旧约束 (V20261020_01 已建 chk_factory_warehouse_type 覆盖 14 值)
ALTER TABLE factory_warehouses
    DROP CONSTRAINT IF EXISTS chk_factory_warehouse_type;

ALTER TABLE factory_warehouses
    ADD CONSTRAINT chk_factory_warehouse_type
    CHECK (type::text = ANY (ARRAY[
        'LOGISTICS', 'WORKSHOP', 'OTHER',
        'RAW', 'WIP', 'FINISHED', 'LINESIDE', 'RETURNS', 'SCRAP',
        'TEMP', 'QC', 'OUTSOURCE', 'TRANSFER', 'SALTED',
        'RD'
    ]::text[]));

COMMENT ON CONSTRAINT chk_factory_warehouse_type ON factory_warehouses IS
    'SP10 §RD-1: 15 值 (14 + RD). RD = 研发/中试库，试制批次 (is_trial=true) 产出独立库存。V20261023_01 扩展。';

-- ============================================================================
-- Step 2: Seed 研发/中试库 WH-RD
-- ============================================================================
-- F006 (六扇门) — SP10 试制场景核心工厂
INSERT INTO factory_warehouses (id, factory_id, code, name, type, is_active, remarks, created_at, updated_at)
VALUES (gen_random_uuid()::text, 'F006', 'WH-RD', '研发/中试库', 'RD', true,
        'SP10 §RD-1 V20261023_01: 试制批次 (is_trial=true) 产出专属仓库，不混入可售库存', NOW(), NOW())
ON CONFLICT (factory_id, code) DO NOTHING;

-- F001 (测试工厂) — 单元测试 / 集成测试环境使用
INSERT INTO factory_warehouses (id, factory_id, code, name, type, is_active, remarks, created_at, updated_at)
VALUES (gen_random_uuid()::text, 'F001', 'WH-RD', '研发/中试库', 'RD', true,
        'SP10 §RD-1 V20261023_01: 测试工厂研发库 seed', NOW(), NOW())
ON CONFLICT (factory_id, code) DO NOTHING;

-- F999 (演示工厂) — 演示 / 集成测试环境使用
INSERT INTO factory_warehouses (id, factory_id, code, name, type, is_active, remarks, created_at, updated_at)
VALUES (gen_random_uuid()::text, 'F999', 'WH-RD', '研发/中试库', 'RD', true,
        'SP10 §RD-1 V20261023_01: 演示工厂研发库 seed', NOW(), NOW())
ON CONFLICT (factory_id, code) DO NOTHING;
