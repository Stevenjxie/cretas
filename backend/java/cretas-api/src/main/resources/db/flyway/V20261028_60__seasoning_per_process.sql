-- 调料明细加工序归属 (nullable, 迁移期兼容)
ALTER TABLE bom_seasoning_items ADD COLUMN IF NOT EXISTS work_process_id VARCHAR(50);
CREATE INDEX IF NOT EXISTS idx_bsi_recipe_wp ON bom_seasoning_items(recipe_id, work_process_id);

-- per-(recipe × 工序) 锅序/注射参数
-- id 用 BIGSERIAL (对齐 bom 包内其它 config 实体 Long/IDENTITY 惯例, 如
-- product_cost_variance_configs / bom_overhead_cost_configs), 不是 UUID 字符串.
CREATE TABLE IF NOT EXISTS bom_process_seasoning (
  id                   BIGSERIAL    PRIMARY KEY,
  factory_id           VARCHAR(50)  NOT NULL,
  recipe_id            VARCHAR(191) NOT NULL,
  work_process_id      VARCHAR(50)  NOT NULL,
  subsequent_pot_ratio NUMERIC(8,4),
  injection_amount_kg  NUMERIC(12,3),
  notes                VARCHAR(500),
  created_at           TIMESTAMP    NOT NULL DEFAULT NOW(),
  updated_at           TIMESTAMP    NOT NULL DEFAULT NOW(),
  deleted_at           TIMESTAMP    NULL
);
-- 唯一性用 PARTIAL index (WHERE deleted_at IS NULL), 对齐 bom_seasoning_items 惯例。
-- ⚠️ 不能用普通 UNIQUE(recipe_id, work_process_id): saveSeasoning 走"软删旧行+插新行"全量替换,
--    软删只置 deleted_at 不清 recipe_id/work_process_id → 普通唯一约束下第二次保存必撞 duplicate key
--    (软删的旧行仍占用键) → 409 回滚。partial index 只约束未删行, 软删行不参与 → 反复保存 OK。
CREATE UNIQUE INDEX IF NOT EXISTS uq_bps_recipe_wp
    ON bom_process_seasoning(recipe_id, work_process_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_bps_factory_recipe ON bom_process_seasoning(factory_id, recipe_id);
