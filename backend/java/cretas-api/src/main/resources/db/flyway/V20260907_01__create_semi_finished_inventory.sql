-- V20260907_01__create_semi_finished_inventory.sql
--
-- 半成品库存 (WIP) — G6 地基 (Phase B 方案1)。
--
-- Background:
--   现状 (Phase A) 只有 production_reports.carryover_quantity 单批记录值 + intermediate_batch_no
--   工序批次号, 都不是"有余额的库存账"。本表补这个缺口:
--   每道工序产出 = 一笔有余额的半成品存量行; 下道领用它, 未领的留作结余 WIP (张权 G7)。
--   WIP 余额 / 领用 / 退回 / 防呆 :max 全靠本表 (库存权威账)。
--
--   spec: docs/superpowers/specs/2026-06-02-f006-wip-yield-G6G7G8-design.md 第二章方案1。
--   entity: com.cretas.aims.entity.SemiFinishedInventory
--
-- 本 Wave 范围: 纯 CREATE TABLE, 无 ALTER 现有表 → 零 prod 风险 (设计章四迁移)。
-- 不改报工/出成率行为 (Wave2/3 才接入 submitReport / calculateBatchYield)。
--
-- 列精度对齐 production_reports (NUMERIC(12,2))。intermediate_batch_no 复用 Phase A 工序批次号
-- (天然幂等键, 工厂内唯一)。jsonb material_batch_refs 镜像 production_reports.material_batch_refs。
--
-- ⚠️ 表存在守卫 + IF NOT EXISTS 保证幂等 (per feedback_e2e_pr_gate_freshdb_flyway_startup_debt):
--   全新 CI DB 上 Flyway 先于 Hibernate ddl-auto 跑; CREATE TABLE IF NOT EXISTS 已存在则 no-op。
--   to_regclass 守卫索引创建 (表存在才建索引)。validate-on-migrate=false → 编辑本文件不破 prod checksum。
--
-- ⚠️ cretas_db (非 smartbi_db): 表归应用 DB 用户所有, 无需 GRANT (Phase A V20260901_xx 同样无 GRANT)。

CREATE TABLE IF NOT EXISTS semi_finished_inventory (
    id                          BIGSERIAL PRIMARY KEY,
    version                     BIGINT,
    factory_id                  VARCHAR(50)   NOT NULL,
    batch_id                    BIGINT,
    intermediate_batch_no       VARCHAR(64)   NOT NULL,
    source_work_process_task_id BIGINT,
    process_order               INTEGER,
    product_type_id             VARCHAR(100),
    produced_quantity           NUMERIC(12,2),
    consumed_quantity           NUMERIC(12,2),
    available_quantity          NUMERIC(12,2),
    unit                        VARCHAR(16),
    status                      VARCHAR(20)   DEFAULT 'AVAILABLE',
    material_batch_refs         JSONB,
    created_at                  TIMESTAMP,
    updated_at                  TIMESTAMP,
    deleted_at                  TIMESTAMP
);

DO $$
BEGIN
    IF to_regclass('public.semi_finished_inventory') IS NOT NULL THEN
        -- 按批次查 WIP 余额
        CREATE INDEX IF NOT EXISTS idx_sfi_factory_batch
            ON semi_finished_inventory (factory_id, batch_id);

        -- 按工序批次号查 (幂等 upsert 锚点, Wave 2)
        CREATE INDEX IF NOT EXISTS idx_sfi_intermediate_batch_no
            ON semi_finished_inventory (intermediate_batch_no);

        -- 按状态过滤 (AVAILABLE 可领用)
        CREATE INDEX IF NOT EXISTS idx_sfi_factory_status
            ON semi_finished_inventory (factory_id, status);

        -- 工序批次号唯一 (幂等键防重), 仅对非空 + 未软删值生效 (partial unique)
        CREATE UNIQUE INDEX IF NOT EXISTS uq_sfi_intermediate_batch_no
            ON semi_finished_inventory (factory_id, intermediate_batch_no)
            WHERE deleted_at IS NULL;

        -- GIN 索引支持 @> 溯源包含查询 (查找含特定 materialBatchId 的 WIP)
        CREATE INDEX IF NOT EXISTS idx_sfi_material_batch_refs
            ON semi_finished_inventory
            USING gin (material_batch_refs jsonb_path_ops)
            WHERE material_batch_refs IS NOT NULL;
    END IF;
END $$;

COMMENT ON TABLE semi_finished_inventory
    IS 'G6 半成品库存(WIP)权威账: 每道工序产出一笔有余额存量行, 下道领用扣减, 结余留作 WIP; 防呆 :max + D3 余料退回靠本表';
COMMENT ON COLUMN semi_finished_inventory.intermediate_batch_no
    IS '工序批次号(复用 Phase A 已生成, 工厂内唯一); 幂等 upsert 锚点 (跨天多次报工累加同行)';
COMMENT ON COLUMN semi_finished_inventory.available_quantity
    IS '余额 = produced_quantity − consumed_quantity (G7 结余; 下道领用 :max)';
COMMENT ON COLUMN semi_finished_inventory.material_batch_refs
    IS '溯源(从产出报工继承); 格式 [{"materialBatchId":Long,"quantity":Number,"unit":String}]; 镜像 production_reports.material_batch_refs';
