INSERT INTO external_benchmark_source (
    source_code, source_name, source_type, access_mode, compliance_level,
    base_url, requires_api_key, raw_review_allowed, robots_respected,
    enabled, refresh_interval_hours, notes, updated_at
) VALUES
('tencent_map_place_search', 'Tencent Location Service place search API',
 'public_poi', 'official_api', 'public_aggregate', 'https://apis.map.qq.com/',
 true, false, true, true, 24,
 'Official place search API used for Tencent cross-check of competitor density and public POI signals.', now()),
('tencent_map_weather', 'Tencent Location Service weather API',
 'public_poi', 'official_api', 'public_aggregate', 'https://apis.map.qq.com/',
 true, false, true, true, 6,
 'Official weather API used as a second weather context source.', now())
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
