ALTER TABLE external_benchmark_source
  DROP CONSTRAINT IF EXISTS chk_external_benchmark_source_type;
ALTER TABLE external_benchmark_source
  ADD CONSTRAINT chk_external_benchmark_source_type CHECK (
    source_type IN ('official_stat', 'public_poi', 'industry_report', 'authorized_export', 'third_party', 'weather')
  );

ALTER TABLE external_benchmark_source
  DROP CONSTRAINT IF EXISTS chk_external_benchmark_access_mode;
ALTER TABLE external_benchmark_source
  ADD CONSTRAINT chk_external_benchmark_access_mode CHECK (
    access_mode IN ('open_web', 'official_api', 'manual_upload', 'licensed_api', 'authorized_export', 'seed')
  );

INSERT INTO external_benchmark_source (source_code, source_name, source_type, access_mode, compliance_level)
VALUES ('internal_seed_weather','内部模拟天气','weather','seed','internal_seed')
ON CONFLICT (source_code) DO NOTHING;

SET app.factory_id = 'DEMO_REST';

INSERT INTO external_benchmark_observation
  (factory_id, source_code, benchmark_domain, metric_code, metric_name, metric_value, metric_unit, geo_scope, period_start, period_end)
SELECT 'DEMO_REST','internal_seed_weather','restaurant','rain_mm','日降水量',
  CASE WHEN (extract(doy from d)::int * 7 + 3) % 10 < 6 THEN 0
       WHEN (extract(doy from d)::int * 7 + 3) % 10 < 9 THEN 5 + ((extract(doy from d)::int)%20)
       ELSE 30 + ((extract(doy from d)::int)%50) END,
  'mm','DEMO_REST-city', d, d
FROM (SELECT DISTINCT date AS d FROM agg_daily WHERE factory_id='DEMO_REST') s
ON CONFLICT DO NOTHING;

INSERT INTO external_benchmark_observation
  (factory_id, source_code, benchmark_domain, metric_code, metric_name, metric_value, metric_unit, geo_scope, period_start, period_end)
SELECT 'DEMO_REST','internal_seed_weather','restaurant','temp_c','日均气温',
  18 + ((extract(doy from d)::int * 5 + 11) % 16),
  'celsius','DEMO_REST-city', d, d
FROM (SELECT DISTINCT date AS d FROM agg_daily WHERE factory_id='DEMO_REST') s
ON CONFLICT DO NOTHING;
