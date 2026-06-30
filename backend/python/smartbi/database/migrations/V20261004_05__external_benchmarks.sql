CREATE TABLE IF NOT EXISTS external_benchmark_source (
    source_code VARCHAR(80) PRIMARY KEY,
    source_name VARCHAR(255) NOT NULL,
    source_type VARCHAR(40) NOT NULL,
    access_mode VARCHAR(40) NOT NULL,
    compliance_level VARCHAR(40) NOT NULL,
    base_url TEXT,
    requires_api_key BOOLEAN NOT NULL DEFAULT false,
    raw_review_allowed BOOLEAN NOT NULL DEFAULT false,
    robots_respected BOOLEAN NOT NULL DEFAULT true,
    enabled BOOLEAN NOT NULL DEFAULT true,
    refresh_interval_hours INTEGER NOT NULL DEFAULT 24,
    notes TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT chk_external_benchmark_source_type CHECK (
        source_type IN ('official_stat', 'public_poi', 'industry_report', 'authorized_export', 'third_party')
    ),
    CONSTRAINT chk_external_benchmark_access_mode CHECK (
        access_mode IN ('open_web', 'official_api', 'manual_upload', 'licensed_api', 'authorized_export')
    ),
    CONSTRAINT chk_external_benchmark_compliance CHECK (
        compliance_level IN ('public_aggregate', 'authorized', 'licensed', 'internal_seed')
    )
);

CREATE TABLE IF NOT EXISTS external_benchmark_observation (
    id BIGSERIAL PRIMARY KEY,
    factory_id VARCHAR(50) NOT NULL DEFAULT 'GLOBAL',
    source_code VARCHAR(80) NOT NULL REFERENCES external_benchmark_source(source_code),
    benchmark_domain VARCHAR(40) NOT NULL DEFAULT 'restaurant',
    metric_code VARCHAR(120) NOT NULL,
    metric_name VARCHAR(255) NOT NULL,
    metric_value NUMERIC(18, 4) NOT NULL,
    metric_unit VARCHAR(40) NOT NULL,
    dimension JSONB NOT NULL DEFAULT '{}'::jsonb,
    dimension_hash VARCHAR(64) NOT NULL DEFAULT '',
    geo_scope VARCHAR(120) NOT NULL DEFAULT 'China',
    category_scope VARCHAR(120) NOT NULL DEFAULT 'catering',
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    confidence_score NUMERIC(5, 4) NOT NULL DEFAULT 0.75,
    confidence_label VARCHAR(40) NOT NULL DEFAULT 'public_signal',
    source_url TEXT,
    source_title TEXT,
    collected_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    expires_at TIMESTAMP WITH TIME ZONE,
    raw_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT chk_external_benchmark_confidence_label CHECK (
        confidence_label IN ('official', 'authorized', 'licensed', 'public_signal', 'report_excerpt', 'estimated')
    ),
    CONSTRAINT chk_external_benchmark_period CHECK (period_end >= period_start)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_external_benchmark_observation
    ON external_benchmark_observation (
        source_code, metric_code, geo_scope, category_scope,
        period_start, period_end, dimension_hash
    );

CREATE INDEX IF NOT EXISTS idx_external_benchmark_observation_domain
    ON external_benchmark_observation (benchmark_domain, metric_code, period_end DESC);

CREATE INDEX IF NOT EXISTS idx_external_benchmark_observation_factory
    ON external_benchmark_observation (factory_id, benchmark_domain, period_end DESC);

ALTER TABLE external_benchmark_observation ENABLE ROW LEVEL SECURITY;
ALTER TABLE external_benchmark_observation FORCE ROW LEVEL SECURITY;

DO $$ BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
     WHERE tablename='external_benchmark_observation'
       AND policyname='external_benchmark_read_global_or_tenant'
  ) THEN
    CREATE POLICY external_benchmark_read_global_or_tenant
      ON external_benchmark_observation
      USING (
        factory_id = 'GLOBAL'
        OR factory_id = current_setting('app.factory_id', true)
      )
      WITH CHECK (
        factory_id = current_setting('app.factory_id', true)
        OR current_setting('app.factory_id', true) = '__internal__'
      );
  END IF;
END $$;

CREATE TABLE IF NOT EXISTS external_benchmark_job_run (
    id BIGSERIAL PRIMARY KEY,
    source_code VARCHAR(80) NOT NULL,
    status VARCHAR(30) NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    finished_at TIMESTAMP WITH TIME ZONE,
    rows_upserted INTEGER NOT NULL DEFAULT 0,
    error_message TEXT,
    request_url TEXT,
    rate_limit_ms INTEGER,
    raw_payload JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX IF NOT EXISTS idx_external_benchmark_job_run_source
    ON external_benchmark_job_run (source_code, started_at DESC);

GRANT SELECT, INSERT, UPDATE ON external_benchmark_source TO smartbi_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON external_benchmark_observation TO smartbi_user;
GRANT USAGE, SELECT ON SEQUENCE external_benchmark_observation_id_seq TO smartbi_user;
GRANT SELECT, INSERT ON external_benchmark_job_run TO smartbi_user;
GRANT USAGE, SELECT ON SEQUENCE external_benchmark_job_run_id_seq TO smartbi_user;

INSERT INTO external_benchmark_source (
    source_code, source_name, source_type, access_mode, compliance_level,
    base_url, requires_api_key, raw_review_allowed, robots_respected,
    enabled, refresh_interval_hours, notes, updated_at
) VALUES
('nbs_catering_retail', 'National Bureau of Statistics catering retail releases',
 'official_stat', 'open_web', 'public_aggregate', 'https://www.stats.gov.cn/',
 false, false, true, true, 24,
 'Official monthly/annual aggregate catering income and YoY statistics.', now()),
('ccfa_catering_chain_2025', 'CCFA / Meituan China catering chain whitepaper 2025',
 'industry_report', 'open_web', 'public_aggregate', 'https://www.ccfa.org.cn/',
 false, false, true, true, 720,
 'Public report excerpts used as national/category benchmark seeds.', now()),
('meituan_life_service_trends_2025', 'Meituan life service trend report 2025',
 'industry_report', 'open_web', 'public_aggregate', 'https://www.meituan.com/',
 false, false, true, true, 720,
 'Public aggregate trend signals, not raw shop comments.', now()),
('kpmg_fnb_enterprise_2025', 'KPMG China food and beverage enterprise development report 2025',
 'industry_report', 'open_web', 'public_aggregate', 'https://assets.kpmg.com/',
 false, false, true, true, 720,
 'Third-party public report excerpts for macro and chain-operation benchmarks.', now()),
('amap_poi_search', 'Amap official POI Search API',
 'public_poi', 'official_api', 'public_aggregate', 'https://restapi.amap.com/',
 true, false, true, true, 24,
 'Official POI API used for competitor/store-density aggregates only.', now()),
('authorized_platform_export', 'Authorized merchant platform export',
 'authorized_export', 'authorized_export', 'authorized', '',
 false, true, true, true, 24,
 'Merchant-owned exports from Dianping/Meituan or other platforms.', now())
ON CONFLICT (source_code) DO UPDATE SET
    source_name = EXCLUDED.source_name,
    source_type = EXCLUDED.source_type,
    access_mode = EXCLUDED.access_mode,
    compliance_level = EXCLUDED.compliance_level,
    base_url = EXCLUDED.base_url,
    requires_api_key = EXCLUDED.requires_api_key,
    raw_review_allowed = EXCLUDED.raw_review_allowed,
    robots_respected = EXCLUDED.robots_respected,
    enabled = EXCLUDED.enabled,
    refresh_interval_hours = EXCLUDED.refresh_interval_hours,
    notes = EXCLUDED.notes,
    updated_at = now();

SELECT set_config('app.factory_id', '__internal__', false);

INSERT INTO external_benchmark_observation (
    factory_id, source_code, benchmark_domain, metric_code, metric_name,
    metric_value, metric_unit, dimension, dimension_hash,
    geo_scope, category_scope, period_start, period_end,
    confidence_score, confidence_label, source_url, source_title,
    collected_at, raw_payload, updated_at
) VALUES
('GLOBAL', 'nbs_catering_retail', 'restaurant', 'catering_revenue',
 'National catering income 2025', 57982.0, '100m_CNY',
 '{"source_kind":"official_release"}'::jsonb, 'nbs_2025_annual_revenue',
 'China', 'catering', '2025-01-01', '2025-12-31',
 0.98, 'official',
 'https://www.stats.gov.cn/xxgk/sjfb/zxfb2020/202601/t20260119_1962323.html',
 '2025 retail sales of consumer goods release', now(), '{}'::jsonb, now()),
('GLOBAL', 'nbs_catering_retail', 'restaurant', 'catering_revenue_yoy',
 'National catering income YoY 2025', 3.2, 'pct',
 '{"source_kind":"official_release"}'::jsonb, 'nbs_2025_annual_yoy',
 'China', 'catering', '2025-01-01', '2025-12-31',
 0.98, 'official',
 'https://www.stats.gov.cn/xxgk/sjfb/zxfb2020/202601/t20260119_1962323.html',
 '2025 retail sales of consumer goods release', now(), '{}'::jsonb, now()),
('GLOBAL', 'nbs_catering_retail', 'restaurant', 'catering_revenue',
 'National catering income Jan-May 2026', 23488.0, '100m_CNY',
 '{"source_kind":"official_release"}'::jsonb, 'nbs_2026_jan_may_revenue',
 'China', 'catering', '2026-01-01', '2026-05-31',
 0.98, 'official',
 'https://www.stats.gov.cn/sj/zxfb/202606/t20260616_1963949.html',
 'May 2026 retail sales of consumer goods release', now(), '{}'::jsonb, now()),
('GLOBAL', 'nbs_catering_retail', 'restaurant', 'catering_revenue_yoy',
 'National catering income YoY Jan-May 2026', 3.1, 'pct',
 '{"source_kind":"official_release"}'::jsonb, 'nbs_2026_jan_may_yoy',
 'China', 'catering', '2026-01-01', '2026-05-31',
 0.98, 'official',
 'https://www.stats.gov.cn/sj/zxfb/202606/t20260616_1963949.html',
 'May 2026 retail sales of consumer goods release', now(), '{}'::jsonb, now()),
('GLOBAL', 'ccfa_catering_chain_2025', 'restaurant', 'restaurant_chain_rate',
 'China restaurant chain rate', 23.0, 'pct',
 '{"source_kind":"industry_report","benchmark_use":"chain_operation"}'::jsonb, 'ccfa_2025_chain_rate',
 'China', 'catering', '2024-01-01', '2024-12-31',
 0.86, 'report_excerpt',
 'https://www.ccfa.org.cn/portal/cn/xiangxi.jsp?id=446601&sharetype=1&type=33',
 '2025 China catering chain development whitepaper', now(), '{}'::jsonb, now())
ON CONFLICT (
    source_code, metric_code, geo_scope, category_scope,
    period_start, period_end, dimension_hash
) DO UPDATE SET
    metric_name = EXCLUDED.metric_name,
    metric_value = EXCLUDED.metric_value,
    metric_unit = EXCLUDED.metric_unit,
    dimension = EXCLUDED.dimension,
    confidence_score = EXCLUDED.confidence_score,
    confidence_label = EXCLUDED.confidence_label,
    source_url = EXCLUDED.source_url,
    source_title = EXCLUDED.source_title,
    collected_at = EXCLUDED.collected_at,
    raw_payload = EXCLUDED.raw_payload,
    updated_at = now();
