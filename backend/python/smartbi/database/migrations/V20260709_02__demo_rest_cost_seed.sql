SET app.factory_id = 'DEMO_REST';

INSERT INTO agg_daily_cost (factory_id, date, store_id, material_cost, labor_cost, overhead_cost)
SELECT a.factory_id, a.date, a.store_id,
  ROUND(a.net_amount * (0.30 + (a.store_id % 9) * 0.01), 2),
  ROUND(a.net_amount * (0.22 + (a.store_id % 9) * 0.01), 2),
  ROUND(a.net_amount * (0.14 + (a.store_id % 7) * 0.01), 2)
FROM agg_daily a
WHERE a.factory_id = 'DEMO_REST' AND a.net_amount > 0
ON CONFLICT (factory_id, date, store_id) DO NOTHING;
