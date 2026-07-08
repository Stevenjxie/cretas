-- Demo restaurant staffing-advice seed (RESTAURANT_OPS_STAFFING_ADVICE).
-- DEMO_REST self-contained factory-level rollup (store_id NULL): 4 dayparts
-- x 2 weekday_type = 8 rows. Explicit `id` values (500001-500008) make
-- ON CONFLICT (id) DO NOTHING a real idempotency guard -- the natural
-- UNIQUE (factory_id, store_id, daypart, weekday_type) constraint would not
-- reliably catch a re-run here because store_id is NULL on every row (NULLs
-- are not considered equal by a plain unique constraint).
--
-- Designed outcomes (see resolve_staffing_advice thresholds: actual_per_staff
-- = avg_orders/staff_on_duty; > target*1.15 = 加人, < target*0.7 = 减人):
--   午市 weekday: 180/6 = 30/人 (> 25*1.15=28.75)         -> 需加人
--   下午茶 weekday: 40/5 = 8/人  (< 25*0.7=17.5)           -> 可减人 (冗余)
--   晚市 weekday: 200/8 = 25/人 (== target)                -> 均衡
--   夜宵 weekday: 45/3 = 15/人 (target 20, 15/20=0.75)     -> 均衡偏低 (未跨阈值)
--   午市/晚市/夜宵 weekend: 加大排班后均衡; 下午茶 weekend: 70/5=14/人 (< 17.5) -> 仍可减人

INSERT INTO fact_staffing_daypart
    (id, factory_id, store_id, daypart, weekday_type, avg_orders, staff_on_duty, target_orders_per_staff)
VALUES
    (500001, 'DEMO_REST', NULL, '午市',   'weekday', 180, 6,  25),
    (500002, 'DEMO_REST', NULL, '晚市',   'weekday', 200, 8,  25),
    (500003, 'DEMO_REST', NULL, '下午茶', 'weekday', 40,  5,  25),
    (500004, 'DEMO_REST', NULL, '夜宵',   'weekday', 45,  3,  20),
    (500005, 'DEMO_REST', NULL, '午市',   'weekend', 260, 10, 25),
    (500006, 'DEMO_REST', NULL, '晚市',   'weekend', 300, 12, 25),
    (500007, 'DEMO_REST', NULL, '下午茶', 'weekend', 70,  5,  25),
    (500008, 'DEMO_REST', NULL, '夜宵',   'weekend', 90,  4,  20)
ON CONFLICT (id) DO NOTHING;
