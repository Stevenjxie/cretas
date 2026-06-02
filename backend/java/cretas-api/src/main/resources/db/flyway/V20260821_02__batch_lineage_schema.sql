-- =====================================================================
-- Batch-Level Lineage Schema (Phase 1 Sprint 1 Day 1)
-- Spec: docs/superpowers/specs/2026-05-20-food-industry-indicator-center-design.md § Lineage
--
-- 2 tables + closure trigger function:
--   batch_lineage_edges     — 有向边 (源 → 目标), 多态 (type+id VARCHAR)
--   batch_lineage_closure   — 物化传递闭包, 由触发器自动维护
--   fn_maintain_lineage_closure  + trg_lineage_edge_insert
--
-- 多态设计原因 (spec § Critical Type Resolution):
--   ProductionBatch.id 是 Long (BIGSERIAL)，MaterialBatch / FinishedGoodsBatch.id 是 String UUID。
--   避免 dual nullable FK，统一以 (type, id VARCHAR(191)) 表达，Long 类型批次以字符串形式存入。
--
-- Forensic 记录: 这两张表的 deleted_at 列保留以兼容 BaseEntity，
-- 但 lineage 永不物理或软删除，查询不带 deleted_at IS NULL 过滤。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. batch_lineage_edges — 有向边
-- ---------------------------------------------------------------------
CREATE TABLE batch_lineage_edges (
    id              VARCHAR(191)   PRIMARY KEY,
    factory_id      VARCHAR(50)    NOT NULL,
    edge_type       VARCHAR(30)    NOT NULL,                                   -- RAW_TO_PRODUCTION / PRODUCTION_TO_FINISHED / FINISHED_TO_SHIPMENT / REWORK / BLEND / WIP_CONSUME (G7 WIP 同批工序间领用流转, 见 YieldReportServiceImpl.recordWipLineageEdge)
    source_type     VARCHAR(30)    NOT NULL,                                   -- MATERIAL_BATCH / PRODUCTION_BATCH / FINISHED_BATCH / SHIPMENT_RECORD
    source_id       VARCHAR(191)   NOT NULL,
    target_type     VARCHAR(30)    NOT NULL,
    target_id       VARCHAR(191)   NOT NULL,
    quantity_used   NUMERIC(15,4),
    unit            VARCHAR(20),
    event_time      TIMESTAMP,
    operator_id     BIGINT,
    meta            JSONB                   DEFAULT '{}'::jsonb,
    created_at      TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP      NOT NULL DEFAULT NOW(),
    deleted_at      TIMESTAMP                                                  -- 保留列以维持 BaseEntity 契约 (永不写入)
);

CREATE INDEX idx_ble_source     ON batch_lineage_edges (factory_id, source_id, source_type);
CREATE INDEX idx_ble_target     ON batch_lineage_edges (factory_id, target_id, target_type);
CREATE INDEX idx_ble_event_time ON batch_lineage_edges (factory_id, event_time);

-- ---------------------------------------------------------------------
-- 2. batch_lineage_closure — 物化传递闭包
-- ---------------------------------------------------------------------
CREATE TABLE batch_lineage_closure (
    id              VARCHAR(191)   PRIMARY KEY,
    factory_id      VARCHAR(50)    NOT NULL,
    ancestor_type   VARCHAR(30)    NOT NULL,
    ancestor_id     VARCHAR(191)   NOT NULL,
    descendant_type VARCHAR(30)    NOT NULL,
    descendant_id   VARCHAR(191)   NOT NULL,
    depth           INTEGER        NOT NULL DEFAULT 0,
    created_at      TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP      NOT NULL DEFAULT NOW(),
    deleted_at      TIMESTAMP,                                                 -- 保留列以维持 BaseEntity 契约
    CONSTRAINT uq_closure UNIQUE (factory_id, ancestor_id, ancestor_type, descendant_id, descendant_type)
);

CREATE INDEX idx_blc_anc  ON batch_lineage_closure (factory_id, ancestor_id, ancestor_type);
CREATE INDEX idx_blc_desc ON batch_lineage_closure (factory_id, descendant_id, descendant_type);

-- ---------------------------------------------------------------------
-- 3. fn_maintain_lineage_closure — 闭包维护函数
-- ---------------------------------------------------------------------
-- AFTER INSERT ON batch_lineage_edges 触发，按 5 步写入派生记录:
--   1. self-ref source (depth 0)
--   2. self-ref target (depth 0)
--   3. direct edge source→target (depth 1)
--   4. source 的所有祖先 → target，depth+1
--   5. source → target 的所有后代，depth+1
-- 使用 ON CONFLICT DO NOTHING 保证幂等。
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_maintain_lineage_closure()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    -- 1. Self-ref source (depth 0)
    INSERT INTO batch_lineage_closure
        (id, factory_id, ancestor_type, ancestor_id, descendant_type, descendant_id, depth, created_at, updated_at)
    VALUES
        (gen_random_uuid()::VARCHAR, NEW.factory_id, NEW.source_type, NEW.source_id,
         NEW.source_type, NEW.source_id, 0, NOW(), NOW())
    ON CONFLICT (factory_id, ancestor_id, ancestor_type, descendant_id, descendant_type) DO NOTHING;

    -- 2. Self-ref target (depth 0)
    INSERT INTO batch_lineage_closure
        (id, factory_id, ancestor_type, ancestor_id, descendant_type, descendant_id, depth, created_at, updated_at)
    VALUES
        (gen_random_uuid()::VARCHAR, NEW.factory_id, NEW.target_type, NEW.target_id,
         NEW.target_type, NEW.target_id, 0, NOW(), NOW())
    ON CONFLICT (factory_id, ancestor_id, ancestor_type, descendant_id, descendant_type) DO NOTHING;

    -- 3. Direct edge (depth 1)
    INSERT INTO batch_lineage_closure
        (id, factory_id, ancestor_type, ancestor_id, descendant_type, descendant_id, depth, created_at, updated_at)
    VALUES
        (gen_random_uuid()::VARCHAR, NEW.factory_id, NEW.source_type, NEW.source_id,
         NEW.target_type, NEW.target_id, 1, NOW(), NOW())
    ON CONFLICT (factory_id, ancestor_id, ancestor_type, descendant_id, descendant_type) DO NOTHING;

    -- 4. All ancestors of source become ancestors of target at depth+1
    INSERT INTO batch_lineage_closure
        (id, factory_id, ancestor_type, ancestor_id, descendant_type, descendant_id, depth, created_at, updated_at)
    SELECT gen_random_uuid()::VARCHAR, NEW.factory_id, anc.ancestor_type, anc.ancestor_id,
           NEW.target_type, NEW.target_id, anc.depth + 1, NOW(), NOW()
    FROM batch_lineage_closure anc
    WHERE anc.factory_id = NEW.factory_id
      AND anc.descendant_id = NEW.source_id
      AND anc.descendant_type = NEW.source_type
      AND anc.depth > 0
    ON CONFLICT (factory_id, ancestor_id, ancestor_type, descendant_id, descendant_type) DO NOTHING;

    -- 5. Source becomes ancestor of all existing descendants of target at depth+1
    INSERT INTO batch_lineage_closure
        (id, factory_id, ancestor_type, ancestor_id, descendant_type, descendant_id, depth, created_at, updated_at)
    SELECT gen_random_uuid()::VARCHAR, NEW.factory_id, NEW.source_type, NEW.source_id,
           desc_.descendant_type, desc_.descendant_id, desc_.depth + 1, NOW(), NOW()
    FROM batch_lineage_closure desc_
    WHERE desc_.factory_id = NEW.factory_id
      AND desc_.ancestor_id = NEW.target_id
      AND desc_.ancestor_type = NEW.target_type
      AND desc_.depth > 0
    ON CONFLICT (factory_id, ancestor_id, ancestor_type, descendant_id, descendant_type) DO NOTHING;

    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------
-- 4. trg_lineage_edge_insert — AFTER INSERT 触发器
-- ---------------------------------------------------------------------
CREATE TRIGGER trg_lineage_edge_insert
AFTER INSERT ON batch_lineage_edges
FOR EACH ROW EXECUTE FUNCTION fn_maintain_lineage_closure();
