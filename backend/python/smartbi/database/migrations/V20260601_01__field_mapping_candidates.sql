-- 字段映射毕业候选: 每个工厂每次 embedding/LLM 解决一个非规则列时 upsert 一行,
-- 累计 occurrence。Promote 工具据此判断"跨>=2工厂复现"。
CREATE TABLE IF NOT EXISTS smart_bi_field_mapping_candidates (
    id              BIGSERIAL PRIMARY KEY,
    column_name     TEXT        NOT NULL,
    standard_field  TEXT        NOT NULL,
    factory_id      VARCHAR(50),
    method          TEXT        NOT NULL,
    confidence      NUMERIC(4,3) NOT NULL,
    occurrences     INTEGER     NOT NULL DEFAULT 1,
    first_seen      TIMESTAMP   NOT NULL DEFAULT now(),
    last_seen       TIMESTAMP   NOT NULL DEFAULT now(),
    UNIQUE (column_name, standard_field, factory_id)
);
CREATE INDEX IF NOT EXISTS idx_fmc_colstd ON smart_bi_field_mapping_candidates (column_name, standard_field);

ALTER TABLE smart_bi_field_mapping_candidates ENABLE ROW LEVEL SECURITY;
ALTER TABLE smart_bi_field_mapping_candidates FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_insert ON smart_bi_field_mapping_candidates FOR INSERT
  WITH CHECK (current_setting('app.factory_id', true) IS NULL
              OR current_setting('app.factory_id', true) = ''
              OR current_setting('app.factory_id', true) = '__internal__'
              OR factory_id = current_setting('app.factory_id', true));
CREATE POLICY tenant_select ON smart_bi_field_mapping_candidates FOR SELECT
  USING (current_setting('app.factory_id', true) IS NULL
         OR current_setting('app.factory_id', true) = ''
         OR current_setting('app.factory_id', true) = '__internal__'
         OR factory_id = current_setting('app.factory_id', true));
CREATE POLICY tenant_update ON smart_bi_field_mapping_candidates FOR UPDATE
  USING (current_setting('app.factory_id', true) IS NULL
         OR current_setting('app.factory_id', true) = ''
         OR current_setting('app.factory_id', true) = '__internal__'
         OR factory_id = current_setting('app.factory_id', true));
