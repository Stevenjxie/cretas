INSERT INTO external_benchmark_source (
    source_code, source_name, source_type, access_mode, compliance_level,
    base_url, requires_api_key, raw_review_allowed, robots_respected,
    enabled, refresh_interval_hours, notes, updated_at
) VALUES
('moa_wholesale_price_daily', 'MOA daily agricultural wholesale price monitor',
 'official_stat', 'open_web', 'public_aggregate', 'https://scs.moa.gov.cn/',
 false, false, true, true, 24,
 'Daily wholesale price index and ingredient prices published by MOA market monitor.', now()),
('amap_weather', 'Amap official weather API',
 'public_poi', 'official_api', 'public_aggregate', 'https://restapi.amap.com/',
 true, false, true, true, 6,
 'Official weather API for trade-area operating context; no personal data.', now())
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

ALTER TABLE external_benchmark_segment_profile
    DROP CONSTRAINT IF EXISTS chk_external_segment_profile_type;

ALTER TABLE external_benchmark_segment_profile
    ADD CONSTRAINT chk_external_segment_profile_type CHECK (
        profile_type IN ('category', 'channel', 'trade_area')
    );

INSERT INTO external_benchmark_segment_profile (
    profile_code, profile_type, display_name, description,
    dimension_weights, external_signal_plan, analysis_questions,
    action_templates, source_code, updated_at
) VALUES
('office_district', 'trade_area', 'Office district',
 'Workday lunch/dinner, speed, repeat and price-band discipline driven.',
 '{"weekday_lunch_peak":0.20,"speed_requirement":0.18,"repeat_frequency":0.18,"price_band_fit":0.16,"delivery_capture":0.14,"competitor_density":0.14}'::jsonb,
 '["Amap office-building and same-category POI density","Internal weekday lunch peak, repeat and delivery metrics"]'::jsonb,
 '["Is workday lunch limited by speed, price band, or nearby category density?","Does dinner demand exist or should labor/menu focus on lunch and delivery?"]'::jsonb,
 '["Use fast stable lunch SKUs before adding broad menu variety.","Benchmark office stores by weekday seat-hour or delivery-hour gross profit."]'::jsonb,
 'internal_methodology_seed', now()),
('community', 'trade_area', 'Community / residential',
 'Neighborhood repeat, family dinner, private-domain retention and stable weekday demand driven.',
 '{"neighborhood_repeat":0.22,"family_dinner_fit":0.16,"private_domain":0.18,"weekday_stability":0.16,"delivery_capture":0.12,"price_trust":0.16}'::jsonb,
 '["Amap residential/community POI context and local competitor density","Internal repeat, member and dinner-period metrics"]'::jsonb,
 '["Is the store building repeatable neighborhood demand or relying on one-time platform traffic?","Do family dinner SKUs protect gross margin and review stability?"]'::jsonb,
 '["Prioritize retention and family bundles over aggressive new-customer discounts.","Use weekday dinner stability as the health baseline."]'::jsonb,
 'internal_methodology_seed', now()),
('shopping_mall', 'trade_area', 'Shopping mall',
 'Mall traffic conversion, rent pressure, weekend peak and same-floor competition driven.',
 '{"rent_to_sales":0.20,"weekend_peak_capture":0.18,"mall_category_cluster":0.18,"ticket_band_fit":0.14,"queue_conversion":0.14,"brand_exposure":0.16}'::jsonb,
 '["Amap mall and same-category POI density","Internal rent-to-sales, weekend peak and queue conversion"]'::jsonb,
 '["Does mall traffic convert for this category and ticket band?","Is rent pressure offset by weekend peak and brand exposure?"]'::jsonb,
 '["Evaluate rent-to-sales and peak capture together.","Use same-mall category density before increasing promotion spend."]'::jsonb,
 'internal_methodology_seed', now()),
('nightlife', 'trade_area', 'Nightlife / late-night district',
 'Late-night traffic, alcohol attachment, environment and labor-hour ROI driven.',
 '{"late_night_traffic":0.22,"alcohol_attach":0.16,"labor_hour_roi":0.18,"environment_risk":0.16,"safety_compliance":0.12,"competitor_density":0.16}'::jsonb,
 '["Amap nightlife/bar/BBQ cluster density","Internal late-night revenue, labor and review/environment signals"]'::jsonb,
 '["Does late-night operation create incremental gross profit after labor and spoilage?","Are environment/noise/safety issues becoming repeat-risk indicators?"]'::jsonb,
 '["Keep late-night hours only where marginal gross profit by hour is positive.","Treat environment complaints as commercial risk signals."]'::jsonb,
 'internal_methodology_seed', now()),
('tourist_transport', 'trade_area', 'Tourist / transport hub',
 'Transient traffic, speed, review-risk and price-trust driven.',
 '{"transient_traffic":0.18,"speed_requirement":0.18,"review_risk":0.18,"price_trust":0.16,"standardization":0.16,"refund_complaint":0.14}'::jsonb,
 '["Amap tourist/transport hub POI context","Internal complaint, refund, speed and review trend metrics"]'::jsonb,
 '["Is one-time traffic hiding weak reputation or operational inconsistency?","Does the price band trigger complaint risk for transient customers?"]'::jsonb,
 '["Prioritize standardization and transparent pricing over complex upsell.","Use complaint rate as a primary risk KPI."]'::jsonb,
 'internal_methodology_seed', now()),
('campus', 'trade_area', 'Campus',
 'Price sensitivity, speed, delivery and package value driven.',
 '{"price_sensitivity":0.22,"speed_requirement":0.18,"delivery_capture":0.18,"package_value":0.16,"repeat_frequency":0.14,"peak_concentration":0.12}'::jsonb,
 '["Amap campus and nearby food POI density","Internal price-band, delivery and peak concentration metrics"]'::jsonb,
 '["Is the price band fit for student demand without destroying margin?","Can peak concentration be handled with fewer, faster SKUs?"]'::jsonb,
 '["Use controlled-value packages instead of broad discounts.","Design campus menus around speed and repeat."]'::jsonb,
 'internal_methodology_seed', now())
ON CONFLICT (profile_code) DO UPDATE SET
    profile_type = EXCLUDED.profile_type,
    display_name = EXCLUDED.display_name,
    description = EXCLUDED.description,
    dimension_weights = EXCLUDED.dimension_weights,
    external_signal_plan = EXCLUDED.external_signal_plan,
    analysis_questions = EXCLUDED.analysis_questions,
    action_templates = EXCLUDED.action_templates,
    source_code = EXCLUDED.source_code,
    updated_at = now();
