INSERT INTO external_benchmark_source (
    source_code, source_name, source_type, access_mode, compliance_level,
    base_url, requires_api_key, raw_review_allowed, robots_respected,
    enabled, refresh_interval_hours, notes, updated_at
) VALUES
('baidu_map_place_search', 'Baidu Maps official Place API',
 'public_poi', 'official_api', 'public_aggregate', 'https://api.map.baidu.com/',
 true, false, true, true, 24,
 'Official Baidu Place API used as a third-party cross-check for trade-area competitor density and public POI detail signals.', now()),
('baidu_map_weather', 'Baidu Maps official weather API',
 'public_poi', 'official_api', 'public_aggregate', 'https://api.map.baidu.com/',
 true, false, true, true, 6,
 'Official Baidu weather API used as an additional operating-context source for trade-area analysis.', now())
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
