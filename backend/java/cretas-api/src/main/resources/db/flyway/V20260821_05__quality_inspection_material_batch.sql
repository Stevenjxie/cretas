-- V20260821_05__quality_inspection_material_batch.sql
--
-- Sprint 9 P1.1 Fix 2 — QualityInspection ↔ MaterialBatch 关联补强.
--
-- 历史背景: Sprint 8 P4c QualityCheckSummaryTool 受限于 QualityInspection
-- 只关联 production_batch_id (Long → ProductionBatch.id), 无法直接通过
-- batch_number → MaterialBatch.id (UUID String length 191) 查质检记录.
-- 该 Tool ship 时返 NO_INSPECTION fallback + R5 actionHint.
--
-- 此 migration 加 material_batch_id (VARCHAR(191), nullable) 字段 + 索引,
-- 允许 quality_inspections 行可选地直接关联 MaterialBatch.
-- Nullable for backwards-compat — 历史记录仅 production_batch_id, 新记录两者可同时存在.

ALTER TABLE quality_inspections
    ADD COLUMN IF NOT EXISTS material_batch_id VARCHAR(191);

-- 部分索引: 仅 indexed non-null rows. Avoid bloat for legacy data with NULL material_batch_id.
CREATE INDEX IF NOT EXISTS idx_inspection_material_batch
    ON quality_inspections (material_batch_id)
    WHERE material_batch_id IS NOT NULL;

COMMENT ON COLUMN quality_inspections.material_batch_id IS
    'Sprint 9 P1.1: optional MaterialBatch.id (UUID String) 关联. Nullable for backwards-compat — '
    || '历史记录仅 production_batch_id, 新记录可直接关联 MaterialBatch 供按 batch_number 直查';
