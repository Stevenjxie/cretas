-- V20260821_04__customer_quality_standards.sql
--
-- Sprint 9 P1.1 Fix 1 — CustomerQualityStandard entity 独立化.
--
-- 历史背景: Sprint 8 P4c CustomerQualityStandardTool 受限于 Customer entity 无
-- qualityStandards 字段, 仅能 fallback notes 关键词 + 工厂默认 5 项. Sprint 9 P1.1
-- 建独立 entity, Tool 优先查 RDB, 0 result 时仍 fallback (backwards-compat).
--
-- 关联:
--   customer_id → customers.id (UUID String length 36, 不强制 PG FK constraint)
--   factory_id → factories.id (工厂隔离)
--
-- BaseEntity audit 字段: created_at / updated_at / deleted_at — DEFAULT NOW().

CREATE TABLE IF NOT EXISTS customer_quality_standards (
    id              BIGSERIAL       PRIMARY KEY,
    factory_id      VARCHAR(64)     NOT NULL,
    customer_id     VARCHAR(36)     NOT NULL,
    standard_code   VARCHAR(50)     NOT NULL,
    standard_name   VARCHAR(200)    NOT NULL,
    category        VARCHAR(30)     NOT NULL
        CHECK (category IN ('BIOLOGICAL', 'CHEMICAL', 'PHYSICAL', 'SENSORY', 'PACKAGING')),
    description     TEXT,
    limit_value     DECIMAL(19,4),
    unit            VARCHAR(30),
    active          BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    deleted_at      TIMESTAMP,
    CONSTRAINT uk_customer_quality_standard_code
        UNIQUE (factory_id, customer_id, standard_code)
);

-- Tool 主路径查询: findByFactoryIdAndCustomerIdAndActiveTrue
CREATE INDEX IF NOT EXISTS idx_customer_quality_standards_lookup
    ON customer_quality_standards (factory_id, customer_id, active)
    WHERE deleted_at IS NULL;

-- 注: updated_at 自动更新由 BaseEntity @PreUpdate 维护 (per BaseEntity.java line 71-74),
-- 不需要 DB-level trigger. created_at/updated_at DEFAULT NOW() 用于 raw INSERT 兜底.

COMMENT ON TABLE  customer_quality_standards IS '客户质量标准 — Sprint 9 P1.1 替代 Sprint 8 P4c CustomerQualityStandardTool notes 关键词 fallback';
COMMENT ON COLUMN customer_quality_standards.customer_id IS 'UUID String, references customers.id (no FK, app-level cascade)';
COMMENT ON COLUMN customer_quality_standards.category IS 'BIOLOGICAL/CHEMICAL/PHYSICAL/SENSORY/PACKAGING';
COMMENT ON COLUMN customer_quality_standards.limit_value IS '限量值 (NULL for qualitative standards like 感官)';
