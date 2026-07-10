-- CORRECTIVE (additive) seed fix for DEMO_REST 渠道/时段 revenue basis.
--
-- Defect: V20260710_01 Step A derived agg_daily_order_type_meal.actual_receive
-- from agg_daily.actual_receive (实收 ≈ net × 0.975). The 渠道/时段 synthesis
-- dimension reads actual_receive and the FactBook labels it 应收营业额, but the
-- FINANCE dimension's 总营业额(应收) uses agg_daily.net_amount. Result: on the
-- same answer Σ(channel revenue) = 118.6M while finance 应收 = 121.6M — the
-- channels didn't sum to the total (a 2.5% grounding-consistency gap).
--
-- Fix: set actual_receive = agg_daily.net_amount × (order_type_ratio ×
-- meal_period_ratio), using the SAME ratios as V20260710_01 Step A. After this,
-- Σ(actual_receive over all 9 order_type×meal_period combos) == agg_daily
-- net_amount per (date, store) — exactly reconciling with the finance 应收 total
-- (the ratios sum to 1.0 on each axis, so the combined ratios sum to 1.0).
--
-- Scope: DEMO_REST only, additive, RLS session context set. An UPDATE keyed to
-- DEMO_REST (recomputing a deterministic value) is safe to re-run.
--
-- ⚠️ POINT-IN-TIME COUPLING (same as V20260710_01): actual_receive is DERIVED
-- from the current agg_daily.net_amount. If DEMO_REST agg_daily is later
-- re-materialized with new dates, re-run V20260710_01 (Step A) AND this fix so
-- new rows also carry the net-basis actual_receive.

SELECT set_config('app.factory_id', 'DEMO_REST', false);

UPDATE agg_daily_order_type_meal AS m
   SET actual_receive = ROUND(ad.net_amount * ot.ratio * mp.ratio, 2),
       computed_at    = NOW()
  FROM agg_daily ad,
       (VALUES
           ('堂食', 0.55::numeric),
           ('外卖', 0.35::numeric),
           ('自提', 0.10::numeric)
       ) AS ot(order_type, ratio),
       (VALUES
           ('午市', 0.40::numeric),
           ('晚市', 0.50::numeric),
           ('夜宵', 0.10::numeric)
       ) AS mp(meal_period, ratio)
 WHERE m.factory_id  = 'DEMO_REST'
   AND ad.factory_id = m.factory_id
   AND ad.date       = m.date
   AND ad.store_id   = m.store_id
   AND m.order_type  = ot.order_type
   AND m.meal_period = mp.meal_period;
