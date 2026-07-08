CREATE TABLE IF NOT EXISTS agg_daily_cost (
  factory_id     VARCHAR(50) NOT NULL,
  date           DATE        NOT NULL,
  store_id       BIGINT      NOT NULL,
  material_cost  NUMERIC(18,2),
  labor_cost     NUMERIC(18,2),
  overhead_cost  NUMERIC(18,2),
  computed_at    TIMESTAMP   NOT NULL DEFAULT NOW(),
  PRIMARY KEY (factory_id, date, store_id)
);

ALTER TABLE agg_daily_cost ENABLE ROW LEVEL SECURITY;
ALTER TABLE agg_daily_cost FORCE  ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON agg_daily_cost;
CREATE POLICY tenant_isolation ON agg_daily_cost FOR ALL
    USING (factory_id = current_setting('app.factory_id', true))
    WITH CHECK (factory_id = current_setting('app.factory_id', true));

GRANT SELECT, INSERT, UPDATE, DELETE ON agg_daily_cost TO smartbi_user;
