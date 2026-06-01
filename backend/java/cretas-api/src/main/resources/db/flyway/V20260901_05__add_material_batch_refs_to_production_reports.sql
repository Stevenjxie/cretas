-- V20260901_05__add_material_batch_refs_to_production_reports.sql
--
-- A2b: 领料关联原料批次列表 (material_batch_refs jsonb)
-- 格式: [{"materialBatchId": Long, "quantity": Number, "unit": String|null}]
-- 支持 1 或 N 批次 (1 个批次 = 1 元素数组); 镜像 source_batch_refs 模式。
--
-- GIN 索引支持 @> 包含查询 (查找包含特定 materialBatchId 的报工记录)。
-- Phase A 范围: 只记录关联, 不进 WIP 库存 (Phase B 才升级)。

ALTER TABLE production_reports
    ADD COLUMN material_batch_refs jsonb;

-- GIN 索引支持 @> 包含查询 (查找包含特定 materialBatchId 的报工)
CREATE INDEX idx_pr_material_batch_refs ON production_reports
    USING gin (material_batch_refs jsonb_path_ops)
    WHERE material_batch_refs IS NOT NULL;

COMMENT ON COLUMN production_reports.material_batch_refs
    IS 'A2b: 领料关联原料批次列表 (jsonb); 格式 [{"materialBatchId":Long,"quantity":Number,"unit":String}]; 支持 1 或 N 批次';
