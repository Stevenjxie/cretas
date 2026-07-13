-- 调料明细加工序归属 (nullable, 迁移期兼容)
ALTER TABLE bom_seasoning_items ADD COLUMN IF NOT EXISTS work_process_id VARCHAR(50);
CREATE INDEX IF NOT EXISTS idx_bsi_recipe_wp ON bom_seasoning_items(recipe_id, work_process_id);

-- per-(recipe × 工序) 锅序/注射参数
CREATE TABLE IF NOT EXISTS bom_process_seasoning (
  id                   VARCHAR(50) PRIMARY KEY,
  factory_id           VARCHAR(50)  NOT NULL,
  recipe_id            VARCHAR(191) NOT NULL,
  work_process_id      VARCHAR(50)  NOT NULL,
  subsequent_pot_ratio NUMERIC(8,4),
  injection_amount_kg  NUMERIC(12,3),
  notes                VARCHAR(500),
  created_at           TIMESTAMP DEFAULT NOW(),
  updated_at           TIMESTAMP DEFAULT NOW(),
  deleted_at           TIMESTAMP NULL,
  CONSTRAINT uq_bps_recipe_wp UNIQUE (recipe_id, work_process_id)
);
CREATE INDEX IF NOT EXISTS idx_bps_factory_recipe ON bom_process_seasoning(factory_id, recipe_id);
