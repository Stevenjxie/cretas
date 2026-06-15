CREATE TABLE IF NOT EXISTS smart_bi_timeseries (
    id BIGSERIAL PRIMARY KEY,
    factory_id VARCHAR(50) NOT NULL,
    template_key VARCHAR(16) NOT NULL,
    domain VARCHAR(32),
    period VARCHAR(32) NOT NULL,
    canonical_field VARCHAR(255) NOT NULL,
    value_num DOUBLE PRECISION,
    value_text VARCHAR(255),
    dims JSONB NOT NULL DEFAULT '{}'::jsonb,
    dims_hash VARCHAR(64) NOT NULL DEFAULT '',
    source_upload_id BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_ts UNIQUE (factory_id, template_key, period, canonical_field, dims_hash)
);
CREATE INDEX IF NOT EXISTS idx_ts_factory_domain_period ON smart_bi_timeseries
(factory_id, domain, period);
CREATE INDEX IF NOT EXISTS idx_ts_factory_template ON smart_bi_timeseries
(factory_id, template_key);
ALTER TABLE smart_bi_timeseries ENABLE ROW LEVEL SECURITY;
ALTER TABLE smart_bi_timeseries FORCE ROW LEVEL SECURITY;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename='smart_bi_timeseries' AND policyname='tenant_isolation') THEN
    CREATE POLICY tenant_isolation ON smart_bi_timeseries
      USING ((factory_id)::text = current_setting('app.factory_id', true))
      WITH CHECK ((factory_id)::text = current_setting('app.factory_id', true));
  END IF;
END $$;
GRANT SELECT, INSERT, UPDATE, DELETE ON smart_bi_timeseries TO smartbi_user;
GRANT USAGE, SELECT ON SEQUENCE smart_bi_timeseries_id_seq TO smartbi_user;
