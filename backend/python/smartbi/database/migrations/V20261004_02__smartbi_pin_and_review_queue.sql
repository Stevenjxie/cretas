CREATE TABLE IF NOT EXISTS smart_bi_pin_mappings (
    id            SERIAL PRIMARY KEY,
    factory_id    VARCHAR(50)  NOT NULL,
    template_key  VARCHAR(16)  NOT NULL,        -- hash(sorted(columns))[:16]
    column_name   VARCHAR(255) NOT NULL,        -- normalized original column name(lower/strip)
    standard_name VARCHAR(255) NOT NULL,        -- canonical field
    confidence    FLOAT        NOT NULL DEFAULT 1.0,
    method        VARCHAR(32)  NOT NULL DEFAULT 'user_pinned',  -- user_pinned/auto_promoted
    pinned_by     VARCHAR(100),
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    UNIQUE (factory_id, template_key, column_name)
);

CREATE TABLE IF NOT EXISTS smart_bi_mapping_review_queue (
    id                  SERIAL PRIMARY KEY,
    upload_id           INT          NOT NULL,
    factory_id          VARCHAR(50)  NOT NULL,
    template_key        VARCHAR(16)  NOT NULL,
    column_name         VARCHAR(255) NOT NULL,
    detected_standard   VARCHAR(255),
    detected_confidence FLOAT        NOT NULL DEFAULT 0.0,
    detected_method     VARCHAR(32),
    user_confirmed_standard VARCHAR(255),
    review_status       VARCHAR(20)  NOT NULL DEFAULT 'PENDING',  -- PENDING/CONFIRMED/REJECTED
    reviewed_by         VARCHAR(100),
    reviewed_at         TIMESTAMP,
    created_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
    UNIQUE (upload_id, column_name)
);

CREATE INDEX IF NOT EXISTS idx_pin_factory_template
    ON smart_bi_pin_mappings (factory_id, template_key);

CREATE INDEX IF NOT EXISTS idx_review_queue_factory_pending
    ON smart_bi_mapping_review_queue (factory_id, review_status);

-- ── 多租户隔离: FORCE RLS(对照 smart_bi_pg_excel_uploads / agg_* / tenant_rls_smoke 惯例)──
-- 这两张表按 factory_id 隔离。所有运行时查询必须在事务内
--   set_config('app.factory_id', <factory_id>, true)
-- 否则 FORCE RLS 下返回 0 行(current_setting(name, true) 未设时返 NULL,不报错)。
ALTER TABLE smart_bi_pin_mappings ENABLE ROW LEVEL SECURITY;
ALTER TABLE smart_bi_pin_mappings FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON smart_bi_pin_mappings
    USING (factory_id = current_setting('app.factory_id', true))
    WITH CHECK (factory_id = current_setting('app.factory_id', true));

ALTER TABLE smart_bi_mapping_review_queue ENABLE ROW LEVEL SECURITY;
ALTER TABLE smart_bi_mapping_review_queue FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON smart_bi_mapping_review_queue
    USING (factory_id = current_setting('app.factory_id', true))
    WITH CHECK (factory_id = current_setting('app.factory_id', true));
