-- V20261020_01__f006_warehouse_type_constraint_and_variance_seed.sql
--
-- 修复两个生产配置缺口 (Fable 生产审计 2026-06-11, 六扇门 Friday 演示就绪)。
--
-- ============================================================================
-- 缺口 1 (🔴): factory_warehouses 的 CHECK 约束过期 → 生产仓库类型守卫空转。
-- ============================================================================
-- 背景:
--   FactoryWarehouse.WarehouseType 枚举在 Sprint 4 W1 (W-CLASS-1) 扩到 14 个阶段
--   语义类型 (RAW/WIP/FINISHED/LINESIDE/RETURNS/SCRAP/TEMP/QC/OUTSOURCE/TRANSFER/SALTED
--   + 旧 LOGISTICS/WORKSHOP/OTHER). 但**生产 DB 的 CHECK 约束从未随之迁移** ——
--   prod/test 上 factory_warehouses_type_check 仍只允许 LOGISTICS/WORKSHOP/OTHER
--   (Hibernate 早期 ddl-auto=update 按 3 值枚举生成, 之后从未 DROP)。
--   V20260606_05 注释误判 "W-CLASS-1 不需 DDL" — 漏看了这个遗留 CHECK 约束。
--
-- 后果 (Fable 审计):
--   F006 (六扇门) prod 仓库全是 LOGISTICS/WORKSHOP 旧类型 →
--   WarehouseInventoryGuardService.assertCanReceive 的 switch 落 default 分支 (无约束)
--   → 生产库类型隔离守卫 (RAW 仓只收原料 / WIP 仓只收半成品 / FINISHED 仓只收成品)
--   对 F006 **实际不生效 (空转)**。且任何工厂都无法新建 RAW/WIP/FINISHED 仓 (CHECK 拦)。
--
-- 修复:
--   DROP 过期 CHECK → ADD 覆盖全部 14 个枚举值的新 CHECK。
--   不动现有行 (LOGISTICS/WORKSHOP 仍在新约束允许集内, 零回归)。
--
-- 幂等: DROP CONSTRAINT IF EXISTS + ADD (新名 chk_factory_warehouse_type 避免与遗留同名冲突)。

ALTER TABLE factory_warehouses
    DROP CONSTRAINT IF EXISTS factory_warehouses_type_check;

ALTER TABLE factory_warehouses
    DROP CONSTRAINT IF EXISTS chk_factory_warehouse_type;

ALTER TABLE factory_warehouses
    ADD CONSTRAINT chk_factory_warehouse_type
    CHECK (type::text = ANY (ARRAY[
        'LOGISTICS', 'WORKSHOP', 'OTHER',
        'RAW', 'WIP', 'FINISHED', 'LINESIDE', 'RETURNS', 'SCRAP',
        'TEMP', 'QC', 'OUTSOURCE', 'TRANSFER', 'SALTED'
    ]::text[]));

COMMENT ON CONSTRAINT chk_factory_warehouse_type ON factory_warehouses IS
    'W-CLASS-1: 覆盖全部 14 个 WarehouseType 枚举值. 替代过期的 factory_warehouses_type_check (仅 3 值, Hibernate 早期 ddl-auto 遗留). V20261020_01 修复.';

-- ----------------------------------------------------------------------------
-- F006 (六扇门) seed 真实 RAW/WIP/FINISHED 仓库, 让生产库隔离守卫真生效。
-- ----------------------------------------------------------------------------
-- 不动 F006 现有 legacy 仓 (WH-LOG/LOGISTICS 持 11 个料批, WH-WKS/WORKSHOP 持 20 个成品批)
-- 避免库存错乱 —— 改为**新建** RAW/WIP/FINISHED 三仓。
-- 幂等: ON CONFLICT (factory_id, code) DO NOTHING (uk_fw_factory_code)。
-- id 用 gen_random_uuid()::text (varchar(64) 兼容)。

INSERT INTO factory_warehouses (id, factory_id, code, name, type, is_active, remarks, created_at, updated_at)
VALUES
    (gen_random_uuid()::text, 'F006', 'WH-RAW', '原料仓', 'RAW', true,
     'Fable 审计 V20261020_01: 生产库隔离守卫真生效 — 只收原料入库', NOW(), NOW()),
    (gen_random_uuid()::text, 'F006', 'WH-WIP', '半成品仓', 'WIP', true,
     'Fable 审计 V20261020_01: 生产库隔离守卫真生效 — 只收半成品入库', NOW(), NOW()),
    (gen_random_uuid()::text, 'F006', 'WH-FG', '成品仓', 'FINISHED', true,
     'Fable 审计 V20261020_01: 生产库隔离守卫真生效 — 只收成品入库', NOW(), NOW())
ON CONFLICT (factory_id, code) DO NOTHING;

-- ============================================================================
-- 缺口 2: product_cost_variance_configs 0 条 → 成本超支/达成率告警演示哑炮 (C-058)。
-- ============================================================================
-- 背景:
--   CostVarianceServiceImpl.resolveThreshold 在无配置时落系统默认 10% (DEFAULT_THRESHOLD),
--   故告警并非完全失灵; 但无任何工厂级显式配置 → 配置 UI / 演示无法展示 "config 驱动" 行为,
--   也无法按工厂调阈值。Friday 演示需要可见的、可调的阈值配置。
--
-- 修复:
--   为 F006 seed 一条工厂级默认配置 (product_type_id = NULL → 工厂全局阈值),
--   阈值 = 10.00% (对齐 #735 / 系统默认), 让配置 UI 与告警链路有显式 config 可演示。
--   uk_variance_config_factory_product 是 (factory_id, product_type_id) UNIQUE,
--   但 product_type_id 可空 → 用条件 INSERT...WHERE NOT EXISTS 保证幂等 (NULL 列上的
--   UNIQUE 在 PG 里不约束多行 NULL, 故不能依赖 ON CONFLICT)。

INSERT INTO product_cost_variance_configs
    (factory_id, product_type_id, variance_threshold, is_active, remark, created_at, updated_at)
SELECT 'F006', NULL, 10.00, true,
       'Fable 审计 V20261020_01: 六扇门工厂级成本超支告警阈值 (10% = 系统默认, 显式配置便于演示与调优)', NOW(), NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM product_cost_variance_configs
    WHERE factory_id = 'F006' AND product_type_id IS NULL AND deleted_at IS NULL
);
