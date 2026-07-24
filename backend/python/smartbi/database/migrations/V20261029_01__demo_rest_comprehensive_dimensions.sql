-- DEMO_REST comprehensive restaurant-analysis seed.
--
-- Purpose:
--   Give the public /demo experience a coherent 13-month evidence window for
--   trend, YoY/MoM, store comparison and internal/external dimension analysis.
--
-- Safety:
--   * DEMO_REST only; no real-customer rows are selected or mutated.
--   * deterministic and idempotent.
--   * every external observation uses compliance_level=internal_seed, which the
--     synthesis layer exposes as SIMULATED.
--   * fixed 2025-07-01..2026-07-31 window, so reruns do not silently shift facts.

SELECT set_config('app.factory_id', 'DEMO_REST', false);

-- ---------------------------------------------------------------------------
-- 1. Stores + 13 months of POS / guest count / cost
-- ---------------------------------------------------------------------------
INSERT INTO dim_store (factory_id, name, brand, city, province, region, created_at, updated_at)
VALUES
    ('DEMO_REST', '青花椒上海示范店', '青花椒', '上海', '上海', '静安商圈', NOW(), NOW()),
    ('DEMO_REST', '青花椒大融城店', '青花椒', '上海', '上海', '大融城商圈', NOW(), NOW()),
    ('DEMO_REST', '青花椒陆家嘴店', '青花椒', '上海', '上海', '陆家嘴商圈', NOW(), NOW())
ON CONFLICT (factory_id, name) DO UPDATE SET
    brand = EXCLUDED.brand,
    city = EXCLUDED.city,
    province = EXCLUDED.province,
    region = EXCLUDED.region,
    updated_at = NOW();

WITH stores AS (
    SELECT store_id, name,
           CASE name
               WHEN '青花椒上海示范店' THEN 1.18
               WHEN '青花椒大融城店' THEN 1.00
               ELSE 0.86
           END::numeric AS store_factor
      FROM dim_store
     WHERE factory_id = 'DEMO_REST'
       AND name IN ('青花椒上海示范店', '青花椒大融城店', '青花椒陆家嘴店')
),
days AS (
    SELECT d::date AS d,
           (d::date - DATE '2025-07-01')::int AS day_no,
           EXTRACT(ISODOW FROM d)::int AS dow
      FROM generate_series(DATE '2025-07-01', DATE '2026-07-31', INTERVAL '1 day') d
),
base AS (
    SELECT
        s.store_id,
        d.d,
        s.store_factor,
        (
            22000
            * s.store_factor
            * (1 + CASE WHEN d.dow IN (6, 7) THEN 0.22 ELSE 0 END)
            * (1 + ((d.day_no % 31) - 15) * 0.002)
            * (1 + CASE WHEN d.d >= DATE '2026-01-01' THEN 0.075 ELSE 0 END)
        )::numeric(18,2) AS net_amount
      FROM stores s
      CROSS JOIN days d
),
daily AS (
    SELECT
        store_id,
        d,
        net_amount,
        ROUND(net_amount * (0.045 + (store_id % 3) * 0.004), 2) AS discount_amount,
        GREATEST(ROUND(net_amount / (88 + (store_id % 5) * 3))::int, 1) AS bill_count
      FROM base
)
INSERT INTO agg_daily (
    factory_id, date, store_id, gross_amount, discount_amount, net_amount,
    actual_receive, bill_count, customer_count, item_count, version, computed_at
)
SELECT
    'DEMO_REST',
    d,
    store_id,
    net_amount + discount_amount,
    discount_amount,
    net_amount,
    net_amount,
    bill_count,
    GREATEST(ROUND(bill_count * (1.72 + (store_id % 3) * 0.08))::int, 1),
    GREATEST(ROUND(bill_count * 3.1)::int, 1),
    2,
    NOW()
FROM daily
ON CONFLICT (factory_id, date, store_id) DO UPDATE SET
    gross_amount = EXCLUDED.gross_amount,
    discount_amount = EXCLUDED.discount_amount,
    net_amount = EXCLUDED.net_amount,
    actual_receive = EXCLUDED.actual_receive,
    bill_count = EXCLUDED.bill_count,
    customer_count = EXCLUDED.customer_count,
    item_count = EXCLUDED.item_count,
    version = EXCLUDED.version,
    computed_at = NOW();

INSERT INTO agg_daily_cost (
    factory_id, date, store_id, material_cost, labor_cost, overhead_cost
)
SELECT
    a.factory_id,
    a.date,
    a.store_id,
    ROUND(a.net_amount * (0.305 + (a.store_id % 4) * 0.006), 2),
    ROUND(a.net_amount * (0.205 + (a.store_id % 3) * 0.005), 2),
    ROUND(a.net_amount * (0.135 + (a.store_id % 2) * 0.004), 2)
FROM agg_daily a
WHERE a.factory_id = 'DEMO_REST'
  AND a.date BETWEEN DATE '2025-07-01' AND DATE '2026-07-31'
ON CONFLICT (factory_id, date, store_id) DO UPDATE SET
    material_cost = EXCLUDED.material_cost,
    labor_cost = EXCLUDED.labor_cost,
    overhead_cost = EXCLUDED.overhead_cost;

-- ---------------------------------------------------------------------------
-- 2. Channel x meal-period splits and monthly discount composition
-- ---------------------------------------------------------------------------
INSERT INTO agg_daily_order_type_meal (
    factory_id, date, store_id, order_type, meal_period,
    gross_amount, actual_receive, bill_count, customer_count, version, computed_at
)
SELECT
    a.factory_id,
    a.date,
    a.store_id,
    ot.order_type,
    mp.meal_period,
    ROUND(a.gross_amount * ot.ratio * mp.ratio, 2),
    ROUND(a.net_amount * ot.ratio * mp.ratio, 2),
    ROUND(a.bill_count * ot.ratio * mp.ratio)::int,
    ROUND(a.customer_count * ot.ratio * mp.ratio)::int,
    2,
    NOW()
FROM agg_daily a
CROSS JOIN (VALUES
    ('堂食', 0.58::numeric),
    ('外卖', 0.32::numeric),
    ('自提', 0.10::numeric)
) ot(order_type, ratio)
CROSS JOIN (VALUES
    ('午市', 0.38::numeric),
    ('晚市', 0.52::numeric),
    ('夜宵', 0.10::numeric)
) mp(meal_period, ratio)
WHERE a.factory_id = 'DEMO_REST'
  AND a.date BETWEEN DATE '2025-07-01' AND DATE '2026-07-31'
ON CONFLICT (factory_id, date, store_id, order_type, meal_period) DO UPDATE SET
    gross_amount = EXCLUDED.gross_amount,
    actual_receive = EXCLUDED.actual_receive,
    bill_count = EXCLUDED.bill_count,
    customer_count = EXCLUDED.customer_count,
    version = EXCLUDED.version,
    computed_at = NOW();

INSERT INTO dim_discount (
    factory_id, name, discount_type, platform, parsed_ok, created_at, updated_at
)
VALUES
    ('DEMO_REST', '满减', '满减', NULL, true, NOW(), NOW()),
    ('DEMO_REST', '会员折扣', '会员折扣', NULL, true, NOW(), NOW()),
    ('DEMO_REST', '团购券', '团购券', '美团', true, NOW(), NOW())
ON CONFLICT (factory_id, name) DO NOTHING;

WITH monthly AS (
    SELECT date_trunc('month', date)::date AS month,
           SUM(discount_amount) AS discount,
           SUM(bill_count) AS bills
      FROM agg_daily
     WHERE factory_id = 'DEMO_REST'
       AND date BETWEEN DATE '2025-07-01' AND DATE '2026-07-31'
     GROUP BY date_trunc('month', date)
),
shares AS (
    SELECT * FROM (VALUES
        ('满减', 0.40::numeric),
        ('会员折扣', 0.35::numeric),
        ('团购券', 0.25::numeric)
    ) s(name, share)
)
INSERT INTO agg_discount (
    factory_id, discount_id, month, amount, bill_count, version, computed_at
)
SELECT
    'DEMO_REST',
    d.discount_id,
    m.month,
    ROUND(m.discount * s.share, 2),
    ROUND(m.bills * 0.20 * s.share)::int,
    2,
    NOW()
FROM monthly m
CROSS JOIN shares s
JOIN dim_discount d
  ON d.factory_id = 'DEMO_REST' AND d.name = s.name
ON CONFLICT (factory_id, discount_id, month) DO UPDATE SET
    amount = EXCLUDED.amount,
    bill_count = EXCLUDED.bill_count,
    version = EXCLUDED.version,
    computed_at = NOW();

-- ---------------------------------------------------------------------------
-- 3. Thirteen monthly dish-sales buckets (main dishes only; no rice/disposables)
-- ---------------------------------------------------------------------------
INSERT INTO dim_product (
    factory_id, name, normalized_name, category, sub_category, sku_code, created_at, updated_at
)
VALUES
    ('DEMO_REST', '招牌青花椒鱼', '招牌青花椒鱼', '招牌主菜', '鱼类', 'DEMO-DISH-001', NOW(), NOW()),
    ('DEMO_REST', '藤椒牛肉煲', '藤椒牛肉煲', '招牌主菜', '牛肉', 'DEMO-DISH-002', NOW(), NOW()),
    ('DEMO_REST', '脆皮乳鸽', '脆皮乳鸽', '招牌主菜', '禽类', 'DEMO-DISH-003', NOW(), NOW()),
    ('DEMO_REST', '手工虾滑', '手工虾滑', '小吃', '虾滑', 'DEMO-DISH-004', NOW(), NOW()),
    ('DEMO_REST', '老坛酸菜鱼小份', '老坛酸菜鱼小份', '主菜', '鱼类', 'DEMO-DISH-005', NOW(), NOW()),
    ('DEMO_REST', '酸辣土豆丝', '酸辣土豆丝', '素菜', '热菜', 'DEMO-DISH-006', NOW(), NOW()),
    ('DEMO_REST', '招牌凉拌牛肉', '招牌凉拌牛肉', '凉菜', '牛肉', 'DEMO-DISH-007', NOW(), NOW())
ON CONFLICT (factory_id, normalized_name) DO UPDATE SET
    category = EXCLUDED.category,
    sub_category = EXCLUDED.sub_category,
    sku_code = EXCLUDED.sku_code,
    updated_at = NOW();

WITH months AS (
    SELECT m::date AS month,
           ROW_NUMBER() OVER (ORDER BY m)::int AS month_no
      FROM generate_series(DATE '2025-07-01', DATE '2026-07-01', INTERVAL '1 month') m
),
dishes AS (
    SELECT product_id, name,
           CASE name
               WHEN '招牌青花椒鱼' THEN 1.00
               WHEN '手工虾滑' THEN 0.82
               WHEN '老坛酸菜鱼小份' THEN 0.76
               WHEN '藤椒牛肉煲' THEN 0.58
               WHEN '酸辣土豆丝' THEN 0.55
               WHEN '招牌凉拌牛肉' THEN 0.42
               ELSE 0.30
           END::numeric AS mix,
           CASE name
               WHEN '招牌青花椒鱼' THEN 88
               WHEN '藤椒牛肉煲' THEN 98
               WHEN '脆皮乳鸽' THEN 128
               WHEN '手工虾滑' THEN 38
               WHEN '老坛酸菜鱼小份' THEN 58
               WHEN '酸辣土豆丝' THEN 18
               ELSE 48
           END::numeric AS price
      FROM dim_product
     WHERE factory_id = 'DEMO_REST'
       AND normalized_name IN (
           '招牌青花椒鱼', '藤椒牛肉煲', '脆皮乳鸽', '手工虾滑',
           '老坛酸菜鱼小份', '酸辣土豆丝', '招牌凉拌牛肉'
       )
)
INSERT INTO agg_product (
    factory_id, product_id, month, qty_sold, revenue, bill_count, version, computed_at
)
SELECT
    'DEMO_REST',
    d.product_id,
    m.month,
    ROUND((620 + m.month_no * 12) * d.mix, 0),
    ROUND((620 + m.month_no * 12) * d.mix * d.price, 2),
    ROUND((420 + m.month_no * 7) * d.mix)::int,
    2,
    NOW()
FROM months m
CROSS JOIN dishes d
ON CONFLICT (factory_id, product_id, month) DO UPDATE SET
    qty_sold = EXCLUDED.qty_sold,
    revenue = EXCLUDED.revenue,
    bill_count = EXCLUDED.bill_count,
    version = EXCLUDED.version,
    computed_at = NOW();

-- ---------------------------------------------------------------------------
-- 4. Continuous supplier-price, waste, stocktaking, inventory and staffing facts
-- ---------------------------------------------------------------------------
WITH months AS (
    SELECT m::date AS delivery_date,
           ROW_NUMBER() OVER (ORDER BY m)::int AS month_no
      FROM generate_series(DATE '2025-07-01', DATE '2026-07-01', INTERVAL '1 month') m
),
items AS (
    SELECT * FROM (VALUES
        ('SUP-DEMO-A', '浦东水产供应商', '黑鱼片', '黑鱼片', 48.0::numeric, 'kg'),
        ('SUP-DEMO-B', '上海香料供应商', '青花椒', '青花椒', 118.0::numeric, 'kg'),
        ('SUP-DEMO-C', '净菜配送中心', '土豆', '土豆', 5.2::numeric, 'kg')
    ) v(supplier_id, supplier_name, ingredient_name, normalized_name, base_price, unit)
)
INSERT INTO agg_supplier_price (
    factory_id, source_note_id, supplier_id, supplier_name, ingredient_name,
    normalized_name, delivery_date, unit_price, quantity, unit, line_amount, created_at
)
SELECT
    'DEMO_REST',
    'DEMO-' || i.supplier_id || '-' || to_char(m.delivery_date, 'YYYYMM'),
    i.supplier_id,
    i.supplier_name,
    i.ingredient_name,
    i.normalized_name,
    m.delivery_date,
    ROUND(
        i.base_price
        * CASE
            WHEN i.ingredient_name = '青花椒' AND m.month_no = 13 THEN 1.35
            ELSE 1 + (m.month_no - 1) * CASE WHEN i.ingredient_name = '青花椒' THEN 0.012 ELSE 0.006 END
          END,
        4
    ),
    100,
    i.unit,
    ROUND(i.base_price * 100, 2),
    NOW()
FROM months m
CROSS JOIN items i
WHERE NOT EXISTS (
    SELECT 1
      FROM agg_supplier_price p
     WHERE p.factory_id = 'DEMO_REST'
       AND p.source_note_id = 'DEMO-' || i.supplier_id || '-' || to_char(m.delivery_date, 'YYYYMM')
);

INSERT INTO agg_restaurant_daily_totals (
    factory_id, date, requisition_count, requisition_qty_total, requisition_cost_total,
    wastage_count, wastage_qty_total, wastage_cost_total,
    stocktaking_count, stocktaking_shortage_total, stocktaking_surplus_total,
    version, computed_at
)
SELECT
    'DEMO_REST',
    d::date,
    8 + (EXTRACT(DOY FROM d)::int % 5),
    180 + (EXTRACT(DOY FROM d)::int % 40),
    5200 + (EXTRACT(DOY FROM d)::int % 17) * 80,
    CASE WHEN EXTRACT(ISODOW FROM d)::int IN (6, 7) THEN 4 ELSE 2 END,
    5 + (EXTRACT(DOY FROM d)::int % 8),
    180 + (EXTRACT(DOY FROM d)::int % 11) * 18,
    CASE WHEN EXTRACT(DAY FROM d)::int IN (1, 15) THEN 1 ELSE 0 END,
    CASE WHEN EXTRACT(DAY FROM d)::int IN (1, 15) THEN 3 + (EXTRACT(MONTH FROM d)::int % 4) ELSE 0 END,
    CASE WHEN EXTRACT(DAY FROM d)::int IN (1, 15) THEN 1 + (EXTRACT(MONTH FROM d)::int % 2) ELSE 0 END,
    2,
    NOW()
FROM generate_series(DATE '2025-07-01', DATE '2026-07-31', INTERVAL '1 day') d
ON CONFLICT (factory_id, date) DO UPDATE SET
    requisition_count = EXCLUDED.requisition_count,
    requisition_qty_total = EXCLUDED.requisition_qty_total,
    requisition_cost_total = EXCLUDED.requisition_cost_total,
    wastage_count = EXCLUDED.wastage_count,
    wastage_qty_total = EXCLUDED.wastage_qty_total,
    wastage_cost_total = EXCLUDED.wastage_cost_total,
    stocktaking_count = EXCLUDED.stocktaking_count,
    stocktaking_shortage_total = EXCLUDED.stocktaking_shortage_total,
    stocktaking_surplus_total = EXCLUDED.stocktaking_surplus_total,
    version = EXCLUDED.version,
    computed_at = NOW();

-- Existing ingredient/threshold demo seed is reused. Add a fresh demo snapshot.
INSERT INTO fact_inventory_snapshot (
    factory_id, upload_id, ingredient_id, store_id, snapshot_date,
    stock_qty, unit, safe_stock_qty, reorder_point, created_at
)
SELECT
    'DEMO_REST',
    NULL,
    di.ingredient_id,
    NULL,
    DATE '2026-07-25',
    CASE
        WHEN ROW_NUMBER() OVER (ORDER BY di.ingredient_id) % 4 = 0 THEN 5
        WHEN ROW_NUMBER() OVER (ORDER BY di.ingredient_id) % 3 = 0 THEN 18
        ELSE 45
    END,
    COALESCE(t.unit, di.unit, 'kg'),
    COALESCE(t.safe_stock_qty, 30),
    COALESCE(t.reorder_point, 12),
    NOW()
FROM dim_ingredient di
LEFT JOIN dim_ingredient_threshold t
  ON t.factory_id = di.factory_id
 AND t.ingredient_id = di.ingredient_id
 AND t.store_id IS NULL
WHERE di.factory_id = 'DEMO_REST'
  AND di.normalized_name IN (
      '活鱼', '青花椒底料', '黄豆芽', '嫩豆花', '毛肚', '鸭血',
      '宽粉', '藕片', '木耳', '香菜', '花椒油', '干辣椒'
  )
  AND NOT EXISTS (
      SELECT 1
        FROM fact_inventory_snapshot existing
       WHERE existing.factory_id = 'DEMO_REST'
         AND existing.ingredient_id = di.ingredient_id
         AND existing.store_id IS NULL
         AND existing.snapshot_date = DATE '2026-07-25'
  );

INSERT INTO fact_staffing_daypart (
    id, factory_id, store_id, daypart, weekday_type,
    avg_orders, staff_on_duty, target_orders_per_staff
)
VALUES
    (500001, 'DEMO_REST', NULL, '午市',   'weekday', 180, 6, 25),
    (500002, 'DEMO_REST', NULL, '晚市',   'weekday', 200, 8, 25),
    (500003, 'DEMO_REST', NULL, '下午茶', 'weekday', 40,  5, 25),
    (500004, 'DEMO_REST', NULL, '夜宵',   'weekday', 45,  3, 20),
    (500005, 'DEMO_REST', NULL, '午市',   'weekend', 260, 10, 25),
    (500006, 'DEMO_REST', NULL, '晚市',   'weekend', 300, 12, 25),
    (500007, 'DEMO_REST', NULL, '下午茶', 'weekend', 70,  5, 25),
    (500008, 'DEMO_REST', NULL, '夜宵',   'weekend', 90,  4, 20)
ON CONFLICT (id) DO UPDATE SET
    avg_orders = EXCLUDED.avg_orders,
    staff_on_duty = EXCLUDED.staff_on_duty,
    target_orders_per_staff = EXCLUDED.target_orders_per_staff;

-- ---------------------------------------------------------------------------
-- 5. Explicit SIMULATED external dimensions on the same date axis
-- ---------------------------------------------------------------------------
INSERT INTO external_benchmark_source (
    source_code, source_name, source_type, access_mode, compliance_level,
    requires_api_key, enabled, refresh_interval_hours, notes
)
VALUES
    ('internal_seed_weather', 'Demo模拟天气', 'weather', 'seed', 'internal_seed', false, true, 24, 'DEMO_REST only'),
    ('internal_seed_traffic', 'Demo模拟商场及门前客流', 'third_party', 'seed', 'internal_seed', false, true, 24, 'DEMO_REST only'),
    ('internal_seed_activity', 'Demo模拟商场及周边活动', 'third_party', 'seed', 'internal_seed', false, true, 24, 'DEMO_REST only'),
    ('internal_seed_competitor', 'Demo模拟竞品监测', 'public_poi', 'seed', 'internal_seed', false, true, 720, 'DEMO_REST only'),
    ('internal_seed_campaign', 'Demo模拟营销活动', 'authorized_export', 'seed', 'internal_seed', false, true, 720, 'DEMO_REST only'),
    ('internal_seed_calendar', 'Demo节假日标签', 'official_stat', 'seed', 'internal_seed', false, true, 8760, 'DEMO_REST only')
ON CONFLICT (source_code) DO UPDATE SET
    source_name = EXCLUDED.source_name,
    source_type = EXCLUDED.source_type,
    access_mode = EXCLUDED.access_mode,
    compliance_level = EXCLUDED.compliance_level,
    notes = EXCLUDED.notes,
    updated_at = NOW();

WITH days AS (
    SELECT d::date AS d, EXTRACT(DOY FROM d)::int AS doy
      FROM generate_series(DATE '2025-07-01', DATE '2026-07-31', INTERVAL '1 day') d
),
metrics AS (
    SELECT d, 'rain_mm'::varchar AS metric_code, '日降水量'::varchar AS metric_name,
           CASE WHEN (doy * 7 + 3) % 10 < 6 THEN 0
                WHEN (doy * 7 + 3) % 10 < 9 THEN 5 + (doy % 20)
                ELSE 30 + (doy % 50) END::numeric AS metric_value,
           'mm'::varchar AS metric_unit
      FROM days
    UNION ALL
    SELECT d, 'temp_c', '日均气温',
           (18 + ((doy * 5 + 11) % 16))::numeric, 'celsius'
      FROM days
)
INSERT INTO external_benchmark_observation (
    factory_id, source_code, benchmark_domain, metric_code, metric_name,
    metric_value, metric_unit, dimension, dimension_hash, geo_scope,
    category_scope, period_start, period_end, confidence_score,
    confidence_label, collected_at, raw_payload
)
SELECT
    'DEMO_REST', 'internal_seed_weather', 'restaurant', metric_code, metric_name,
    metric_value, metric_unit,
    jsonb_build_object('dimension_code', 'weather'),
    'demo-weather', 'DEMO_REST-city', 'catering', d, d, 1.0,
    'estimated', NOW(), '{"demo":true}'::jsonb
FROM metrics
ON CONFLICT (
    source_code, metric_code, geo_scope, category_scope,
    period_start, period_end, dimension_hash
) DO UPDATE SET
    metric_value = EXCLUDED.metric_value,
    metric_name = EXCLUDED.metric_name,
    dimension = EXCLUDED.dimension,
    raw_payload = EXCLUDED.raw_payload,
    updated_at = NOW();

WITH chain_daily AS (
    SELECT date AS d,
           SUM(customer_count)::numeric AS visits,
           SUM(bill_count)::numeric AS bills
      FROM agg_daily
     WHERE factory_id = 'DEMO_REST'
       AND date BETWEEN DATE '2025-07-01' AND DATE '2026-07-31'
     GROUP BY date
),
traffic AS (
    SELECT d, 'mall_footfall'::varchar AS metric_code, '商场客流'::varchar AS metric_name,
           ROUND(visits * 115, 0) AS metric_value, '人次'::varchar AS metric_unit
      FROM chain_daily
    UNION ALL
    SELECT d, 'floor_footfall', '楼层客流', ROUND(visits * 18, 0), '人次' FROM chain_daily
    UNION ALL
    SELECT d, 'storefront_passersby', '门前经过人数', ROUND(visits * 6.4, 0), '人次' FROM chain_daily
    UNION ALL
    SELECT d, 'store_visits', '进店人数', visits, '人次' FROM chain_daily
    UNION ALL
    SELECT d, 'capture_rate_pct', '进店捕获率',
           ROUND(visits / NULLIF(visits * 6.4, 0) * 100, 2), '%' FROM chain_daily
)
INSERT INTO external_benchmark_observation (
    factory_id, source_code, benchmark_domain, metric_code, metric_name,
    metric_value, metric_unit, dimension, dimension_hash, geo_scope,
    category_scope, period_start, period_end, confidence_score,
    confidence_label, collected_at, raw_payload
)
SELECT
    'DEMO_REST', 'internal_seed_traffic', 'restaurant', metric_code, metric_name,
    metric_value, metric_unit,
    jsonb_build_object('dimension_code', 'physical_traffic'),
    'demo-physical-traffic', 'DEMO_REST-chain', 'catering',
    d, d, 1.0, 'estimated', NOW(), '{"demo":true}'::jsonb
FROM traffic
ON CONFLICT (
    source_code, metric_code, geo_scope, category_scope,
    period_start, period_end, dimension_hash
) DO UPDATE SET
    metric_value = EXCLUDED.metric_value,
    dimension = EXCLUDED.dimension,
    raw_payload = EXCLUDED.raw_payload,
    updated_at = NOW();

WITH days AS (
    SELECT d::date AS d
      FROM generate_series(DATE '2025-07-01', DATE '2026-07-31', INTERVAL '1 day') d
)
INSERT INTO external_benchmark_observation (
    factory_id, source_code, benchmark_domain, metric_code, metric_name,
    metric_value, metric_unit, dimension, dimension_hash, geo_scope,
    category_scope, period_start, period_end, confidence_score,
    confidence_label, collected_at, raw_payload
)
SELECT
    'DEMO_REST', 'internal_seed_calendar', 'restaurant',
    'holiday_index', '日期类型指数',
    CASE
        WHEN d IN (
            DATE '2025-10-01', DATE '2025-10-02', DATE '2025-10-03',
            DATE '2026-01-01', DATE '2026-02-17', DATE '2026-05-01'
        ) THEN 2
        WHEN EXTRACT(ISODOW FROM d)::int IN (6, 7) THEN 1
        ELSE 0
    END,
    'index',
    jsonb_build_object(
        'dimension_code', 'holiday',
        'day_type', CASE
            WHEN d IN (
                DATE '2025-10-01', DATE '2025-10-02', DATE '2025-10-03',
                DATE '2026-01-01', DATE '2026-02-17', DATE '2026-05-01'
            ) THEN 'holiday'
            WHEN EXTRACT(ISODOW FROM d)::int IN (6, 7) THEN 'weekend'
            ELSE 'weekday'
        END
    ),
    'demo-calendar', 'China', 'catering', d, d, 1.0,
    'estimated', NOW(), '{"demo":true}'::jsonb
FROM days
ON CONFLICT (
    source_code, metric_code, geo_scope, category_scope,
    period_start, period_end, dimension_hash
) DO UPDATE SET
    metric_value = EXCLUDED.metric_value,
    dimension = EXCLUDED.dimension,
    raw_payload = EXCLUDED.raw_payload,
    updated_at = NOW();

WITH mall_events AS (
    SELECT d::date AS d,
           ROW_NUMBER() OVER (ORDER BY d)::int AS event_no
      FROM generate_series(DATE '2025-07-05', DATE '2026-07-25', INTERVAL '14 day') d
),
nearby_events AS (
    SELECT d::date AS d,
           ROW_NUMBER() OVER (ORDER BY d)::int AS event_no
      FROM generate_series(DATE '2025-07-12', DATE '2026-07-23', INTERVAL '21 day') d
),
events AS (
    SELECT d, 'mall_activity_intensity'::varchar AS metric_code,
           '商场活动强度'::varchar AS metric_name,
           (55 + event_no % 40)::numeric AS metric_value,
           'index'::varchar AS metric_unit,
           jsonb_build_object(
               'dimension_code', 'mall_activity',
               'title', 'Demo商场会员日/主题市集-' || event_no
           ) AS dimension,
           'demo-mall-activity'::varchar AS dimension_hash
      FROM mall_events
    UNION ALL
    SELECT d, 'nearby_event_attendance', '周边活动预计人数',
           (3500 + event_no * 230)::numeric, '人次',
           jsonb_build_object(
               'dimension_code', 'nearby_event',
               'title', 'Demo周边演出/赛事-' || event_no
           ),
           'demo-nearby-event'
      FROM nearby_events
)
INSERT INTO external_benchmark_observation (
    factory_id, source_code, benchmark_domain, metric_code, metric_name,
    metric_value, metric_unit, dimension, dimension_hash, geo_scope,
    category_scope, period_start, period_end, confidence_score,
    confidence_label, collected_at, raw_payload
)
SELECT
    'DEMO_REST', 'internal_seed_activity', 'restaurant', metric_code, metric_name,
    metric_value, metric_unit, dimension, dimension_hash,
    'DEMO_REST-business-district', 'catering', d, d, 1.0,
    'estimated', NOW(), '{"demo":true}'::jsonb
FROM events
ON CONFLICT (
    source_code, metric_code, geo_scope, category_scope,
    period_start, period_end, dimension_hash
) DO UPDATE SET
    metric_value = EXCLUDED.metric_value,
    dimension = EXCLUDED.dimension,
    raw_payload = EXCLUDED.raw_payload,
    updated_at = NOW();

WITH months AS (
    SELECT m::date AS month,
           ROW_NUMBER() OVER (ORDER BY m)::int AS month_no
      FROM generate_series(DATE '2025-07-01', DATE '2026-07-01', INTERVAL '1 month') m
),
signals AS (
    SELECT month, 'competitor_count'::varchar AS metric_code,
           '三公里同品类竞品数'::varchar AS metric_name,
           (18 + month_no % 4)::numeric AS metric_value, '家'::varchar AS metric_unit,
           'internal_seed_competitor'::varchar AS source_code,
           jsonb_build_object('dimension_code', 'competitor') AS dimension,
           'demo-competitor'::varchar AS dimension_hash
      FROM months
    UNION ALL
    SELECT month, 'competitor_price_index', '竞品价格指数',
           (96 + month_no % 7)::numeric, 'index', 'internal_seed_competitor',
           jsonb_build_object('dimension_code', 'competitor'), 'demo-competitor'
      FROM months
    UNION ALL
    SELECT month, 'competitor_rating', '竞品平均评分',
           ROUND((4.15 + (month_no % 5) * 0.04)::numeric, 2), 'score',
           'internal_seed_competitor',
           jsonb_build_object('dimension_code', 'competitor'), 'demo-competitor'
      FROM months
    UNION ALL
    SELECT month, 'campaign_exposure', '活动曝光',
           (68000 + month_no * 1900)::numeric, '人次', 'internal_seed_campaign',
           jsonb_build_object('dimension_code', 'promotion', 'campaign_name', 'Demo月度会员活动'),
           'demo-campaign'
      FROM months
    UNION ALL
    SELECT month, 'campaign_redemption', '活动核销',
           (2100 + month_no * 75)::numeric, '单', 'internal_seed_campaign',
           jsonb_build_object('dimension_code', 'promotion', 'campaign_name', 'Demo月度会员活动'),
           'demo-campaign'
      FROM months
    UNION ALL
    SELECT month, 'campaign_cost', '活动成本',
           (36000 + month_no * 900)::numeric, '元', 'internal_seed_campaign',
           jsonb_build_object('dimension_code', 'promotion', 'campaign_name', 'Demo月度会员活动'),
           'demo-campaign'
      FROM months
    UNION ALL
    SELECT month, 'campaign_revenue', '活动订单收入',
           (165000 + month_no * 5200)::numeric, '元', 'internal_seed_campaign',
           jsonb_build_object('dimension_code', 'promotion', 'campaign_name', 'Demo月度会员活动'),
           'demo-campaign'
      FROM months
)
INSERT INTO external_benchmark_observation (
    factory_id, source_code, benchmark_domain, metric_code, metric_name,
    metric_value, metric_unit, dimension, dimension_hash, geo_scope,
    category_scope, period_start, period_end, confidence_score,
    confidence_label, collected_at, raw_payload
)
SELECT
    'DEMO_REST', source_code, 'restaurant', metric_code, metric_name,
    metric_value, metric_unit, dimension, dimension_hash,
    'DEMO_REST-business-district', 'catering',
    month, (month + INTERVAL '1 month - 1 day')::date, 1.0,
    'estimated', NOW(), '{"demo":true}'::jsonb
FROM signals
ON CONFLICT (
    source_code, metric_code, geo_scope, category_scope,
    period_start, period_end, dimension_hash
) DO UPDATE SET
    metric_value = EXCLUDED.metric_value,
    dimension = EXCLUDED.dimension,
    raw_payload = EXCLUDED.raw_payload,
    updated_at = NOW();
