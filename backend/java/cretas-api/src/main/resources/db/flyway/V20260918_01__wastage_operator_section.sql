-- V20260918_01: Wave2 损耗按人/档口责任制 — wastage_records 加 operator_id + section_code
-- Entity: WastageRecord.operatorId / WastageRecord.sectionCode
-- 兑现邓总诉求: "今天损耗明天屌你" / "同样 1 万营业额哪个档口哪个人成本涨了" 按人/档口透明化。
--
-- ⚠️ 防御性幂等迁移 (per feedback_e2e_pr_gate_freshdb_flyway_startup_debt + V20260910_02 同 pattern):
--   to_regclass 守卫: 表存在才 ALTER; ADD COLUMN IF NOT EXISTS no-op 已有列。
--   validate-on-migrate=false → 编辑本文件不破 prod checksum。
DO $$ BEGIN
  IF to_regclass('public.wastage_records') IS NOT NULL THEN
    ALTER TABLE wastage_records ADD COLUMN IF NOT EXISTS operator_id BIGINT;
    ALTER TABLE wastage_records ADD COLUMN IF NOT EXISTS section_code VARCHAR(32);
    COMMENT ON COLUMN wastage_records.operator_id IS '损耗责任人 (users.id); 与 reported_by(录单人) 区分; null=未指定责任人';
    COMMENT ON COLUMN wastage_records.section_code IS '档口编码 SEAFOOD/COLD_DISH/HOT_DISH/FRONT_HOUSE/OTHER; 固定枚举前端 dropdown 录入';
    -- 按人/按档口聚合查询索引
    CREATE INDEX IF NOT EXISTS idx_wastage_factory_operator
        ON wastage_records (factory_id, operator_id);
    CREATE INDEX IF NOT EXISTS idx_wastage_factory_section
        ON wastage_records (factory_id, section_code);
  END IF;
END $$;
