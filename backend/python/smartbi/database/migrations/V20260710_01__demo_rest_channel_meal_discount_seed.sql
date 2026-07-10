-- DEMO_REST seed for 3 new restaurant-analytics synthesis dimensions:
-- 渠道(点餐方式 堂食/外卖/自提) + 时段(午市/晚市/夜宵) + 折扣(总额/占营收比/构成).
--
-- Scope:
--   - demo tenant only (DEMO_REST)
--   - additive/idempotent (ON CONFLICT ... DO UPDATE/DO NOTHING)
--   - no real-customer rows touched
--   - RLS session context set before writes (FORCE ROW LEVEL SECURITY on all
--     three target tables: agg_daily_order_type_meal / dim_discount / agg_discount)
--
-- Consistency contract (so the per-combo splits reconcile back to the
-- existing agg_daily totals seeded by V20260706_01__demo_rest_gold_sales_seed.sql):
--   Every agg_daily_order_type_meal row is derived FROM an agg_daily row by
--   multiplying gross_amount/actual_receive/bill_count by a combined ratio
--   (order_type_ratio * meal_period_ratio), so summing all 9 combinations
--   for a given (date, store_id) reproduces that agg_daily row's totals
--   (up to INT rounding drift on bill_count/customer_count — expected and
--   small, see python-java-port.md byte-shape notes on rounding).
--
-- 口径 (ratios, sum to 1.0 within each dimension):
--   order_type  : 堂食 55% / 外卖 35% / 自提 10%
--   meal_period : 午市 40% / 晚市 50% / 夜宵 10%
--   combined_ratio(order_type, meal_period) = order_ratio * meal_ratio (9 combos)
--   customer_count ≈ bill_count(split) * 1.5 (assumed party size)
--
-- 折扣 seed derives from the SAME agg_daily monthly revenue/bill totals:
--   discount_total = monthly_revenue * 6%           (DISCOUNT_PCT_OF_REVENUE)
--   split shares    : 满减 40% / 会员折扣 35% / 团购券 25%
--   discount bill participation = monthly_bills * 20% * share (per type)

SELECT set_config('app.factory_id', 'DEMO_REST', false);

-- ── Step A: 渠道(order_type) × 时段(meal_period) cross-split from agg_daily ──
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
    ROUND(a.gross_amount * ot.ratio * mp.ratio, 2)        AS gross_amount,
    ROUND(a.actual_receive * ot.ratio * mp.ratio, 2)      AS actual_receive,
    ROUND(a.bill_count * ot.ratio * mp.ratio)::int        AS bill_count,
    ROUND(a.bill_count * ot.ratio * mp.ratio * 1.5)::int  AS customer_count,
    1,
    NOW()
  FROM agg_daily a
 CROSS JOIN (VALUES
        ('堂食', 0.55::numeric),
        ('外卖', 0.35::numeric),
        ('自提', 0.10::numeric)
    ) AS ot(order_type, ratio)
 CROSS JOIN (VALUES
        ('午市', 0.40::numeric),
        ('晚市', 0.50::numeric),
        ('夜宵', 0.10::numeric)
    ) AS mp(meal_period, ratio)
 WHERE a.factory_id = 'DEMO_REST'
ON CONFLICT (factory_id, date, store_id, order_type, meal_period) DO UPDATE SET
    gross_amount   = EXCLUDED.gross_amount,
    actual_receive = EXCLUDED.actual_receive,
    bill_count     = EXCLUDED.bill_count,
    customer_count = EXCLUDED.customer_count,
    computed_at    = NOW();

-- ── Step B: 折扣类型字典 (idempotent — dim_discount has UNIQUE(factory_id, name)) ──
INSERT INTO dim_discount (factory_id, name, discount_type, platform, parsed_ok, created_at, updated_at)
VALUES
    ('DEMO_REST', '满减',    '满减',   NULL,     true, NOW(), NOW()),
    ('DEMO_REST', '会员折扣', '会员折扣', NULL,     true, NOW(), NOW()),
    ('DEMO_REST', '团购券',  '团购券', '美团',   true, NOW(), NOW())
ON CONFLICT (factory_id, name) DO NOTHING;

-- ── Step C: agg_discount monthly rollup derived from agg_daily monthly totals ──
WITH monthly AS (
    SELECT date_trunc('month', date)::date AS month,
           SUM(net_amount)  AS revenue,
           SUM(bill_count)  AS bills
      FROM agg_daily
     WHERE factory_id = 'DEMO_REST'
     GROUP BY date_trunc('month', date)
),
discount_dim AS (
    SELECT discount_id, name
      FROM dim_discount
     WHERE factory_id = 'DEMO_REST'
       AND name IN ('满减', '会员折扣', '团购券')
),
shares AS (
    SELECT * FROM (VALUES
        ('满减',    0.40::numeric),
        ('会员折扣', 0.35::numeric),
        ('团购券',  0.25::numeric)
    ) AS s(name, share)
)
INSERT INTO agg_discount (factory_id, discount_id, month, amount, bill_count, version, computed_at)
SELECT
    'DEMO_REST',
    d.discount_id,
    m.month,
    ROUND(m.revenue * 0.06 * s.share, 2)      AS amount,
    ROUND(m.bills * 0.20 * s.share)::int      AS bill_count,
    1,
    NOW()
  FROM monthly m
 CROSS JOIN shares s
  JOIN discount_dim d ON d.name = s.name
ON CONFLICT (factory_id, discount_id, month) DO UPDATE SET
    amount      = EXCLUDED.amount,
    bill_count  = EXCLUDED.bill_count,
    computed_at = NOW();
