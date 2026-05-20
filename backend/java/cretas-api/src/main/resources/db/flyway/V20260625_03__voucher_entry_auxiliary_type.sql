-- Sprint 5 Track F F-2: 凭证分录辅助核算 7 类
-- Source: R-HJ Round 13 §2 — 宏见 ERP 凭证 detail 模块 "辅助类型" select 实测 7 类:
--   客户 / 供应商 / 部门 / 职员 / 项目 / 存货 / 委外商
--
-- Design:
-- - auxiliary_type enum (7 values via VARCHAR + CHECK), nullable (并非所有分录都辅助核算)
-- - auxiliary_entity_id polymorphic FK (无 DB FK 约束 — 7 类目标表不同, 应用层校验)
-- - 复合索引 (auxiliary_type, auxiliary_entity_id) 支持 "按客户/供应商/职员... 聚合明细账"
-- - 一致约束: auxiliary_type 与 auxiliary_entity_id 必须成对出现
--
-- 报表场景 (R-HJ Round 11 §G.1): 凭证明细账 + 余额表按 auxiliary 维度聚合.

ALTER TABLE voucher_entries
    ADD COLUMN IF NOT EXISTS auxiliary_type      VARCHAR(16),
    ADD COLUMN IF NOT EXISTS auxiliary_entity_id VARCHAR(191);

-- 7 类 enum CHECK (与 AuxiliaryType.java 同步)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_ve_auxiliary_type'
    ) THEN
        ALTER TABLE voucher_entries
            ADD CONSTRAINT chk_ve_auxiliary_type CHECK (
                auxiliary_type IS NULL OR auxiliary_type IN (
                    'CUSTOMER','SUPPLIER','DEPT','EMPLOYEE','PROJECT','INVENTORY','OUTSOURCER'
                )
            );
    END IF;
END $$;

-- 成对约束: type 与 id 同生同死
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_ve_auxiliary_paired'
    ) THEN
        ALTER TABLE voucher_entries
            ADD CONSTRAINT chk_ve_auxiliary_paired CHECK (
                (auxiliary_type IS NULL AND auxiliary_entity_id IS NULL) OR
                (auxiliary_type IS NOT NULL AND auxiliary_entity_id IS NOT NULL)
            );
    END IF;
END $$;

-- 复合索引 (按客户/供应商/职员... 查明细账)
CREATE INDEX IF NOT EXISTS idx_ve_auxiliary
    ON voucher_entries(auxiliary_type, auxiliary_entity_id)
    WHERE auxiliary_type IS NOT NULL;

COMMENT ON COLUMN voucher_entries.auxiliary_type IS
    'Sprint5-F F-2 辅助核算类型 7 类 (R-HJ Round 13 §2). 客户/供应商/部门/职员/项目/存货/委外商';

COMMENT ON COLUMN voucher_entries.auxiliary_entity_id IS
    'Sprint5-F F-2 辅助核算实体 PK (polymorphic by auxiliary_type). 无 DB FK 约束, 应用层校验';
