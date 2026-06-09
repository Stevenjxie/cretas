-- SP4 T1: 一物一码补缺 — 生产厂家编号 + 产地
-- 两列均可空，存量批次不受影响，仓管入库时按需填写。
ALTER TABLE material_batches
    ADD COLUMN IF NOT EXISTS factory_number VARCHAR(100),
    ADD COLUMN IF NOT EXISTS origin_place   VARCHAR(200);

COMMENT ON COLUMN material_batches.factory_number IS 'SP4-A4: 生产厂家编号，用于一物一码标签 (可空)';
COMMENT ON COLUMN material_batches.origin_place   IS 'SP4-A4: 产地，用于一物一码标签 (可空)';
