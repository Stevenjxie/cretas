-- Canvas-P3 Module 2 — Encoding Rule UI wrap (2026-05-22).
--
-- 背景: EncodingRule entity 已存 (entity/config/EncodingRule.java), 表由 JPA ddl-auto 创建.
-- 任务: 给 Canvas-style controller 加 AUD-4 P1 乐观锁支持.
--
-- 注意: entity 里的 `version` Integer 字段是业务版本号 (用于配置版本管理), 不是 JPA 乐观锁.
-- 因此本次新增 `opt_lock_version` BIGINT 字段作为 @Version 乐观锁.
--
-- API: /api/mobile/{factoryId}/canvas-encoding-rule
-- Vue: web-admin/src/views/platform/encoding-rule-editor/index.vue

-- 增加 JPA @Version 乐观锁字段 (与业务 version 字段共存)
ALTER TABLE encoding_rules
    ADD COLUMN IF NOT EXISTS opt_lock_version BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN encoding_rules.opt_lock_version IS
    'Canvas-P3: JPA @Version 乐观锁字段, 与业务 version (Integer 配置版本号) 互不干扰';

-- Partial unique index: 同 (factory_id, entity_type) 不能有 2 条 非软删 行
-- (老 unique constraint 不考虑 deleted_at, 会让软删/恢复操作冲突)
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uk_encoding_rules'
    ) THEN
        ALTER TABLE encoding_rules DROP CONSTRAINT uk_encoding_rules;
    END IF;
END
$$;

CREATE UNIQUE INDEX IF NOT EXISTS idx_encoding_rules_unique
    ON encoding_rules (factory_id, entity_type)
    WHERE deleted_at IS NULL;
