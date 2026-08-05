-- DEMO_FACTORY2 生产批次演示数据
--
-- 背景: 官网「在线演示 → 🏭 生产工厂演示」是免登录公开入口, 但该租户
--       production_batches 为 0 条 —— 任何潜在客户点进去看到的是「暂无数据」。
--       主数据(产品类型 18 / 原料批次 73 / 用户 37)本来就齐全, 只缺批次本身。
--
-- 已于 2026-08-05 手工应用到 cretas_prod_db (不是 Flyway migration:
-- 放 flyway 下会在每次后端发布时重跑, 而这是一次性演示数据, 不该绑上发布流程)。
--   sudo -u postgres psql cretas_prod_db -f <本文件>
--
-- 幂等: 按 batch_number NOT EXISTS 判重, 重复执行插入 0 条。
--
-- ⚠️ quality_status 是枚举字段, 未完成批次必须留 NULL 不能填空字符串 ''。
--    填空串会让「不带 status 过滤」的列表查询整个 400
--    (message: 数据字段值不在允许范围 (枚举/格式越界)) —— 带 status=COMPLETED
--    反而正常, 所以症状是「有时能查有时 400」, 很容易误判成偶发故障。
--    本文件生成时已全部用 NULL。
--
INSERT INTO production_batches (
  batch_number, factory_id, product_type_id, product_name,
  quantity, actual_quantity, good_quantity, defect_quantity, planned_quantity, unit,
  status, batch_type, is_trial, supervisor_id, supervisor_name, equipment_name,
  material_cost, labor_cost, equipment_cost, other_cost, total_cost, unit_cost,
  work_duration_minutes, worker_count, yield_rate, start_time, end_time, quality_status,
  created_at, updated_at
)
SELECT v.* , now(), now() FROM (VALUES
  ('PB-DEMO2-20260701-0001','DEMO_FACTORY2','DF2_pt11','卤猪蹄(去大骨) 200g',517.96,338.23,332.81,5.42,517.96,'kg','IN_PROGRESS','REGULAR',false,500001552,'邓建华','卤制一线',3241.19,1804.98,743.28,308.03,6097.48,18.0276,426,6,98.4,now() - interval '0 day' - interval '426 minute',NULL,''),
  ('PB-DEMO2-20260702-0002','DEMO_FACTORY2','DF2_pt1','轻卤门腔（猪舌）120g',845.72,567.52,556.62,10.9,845.72,'kg','IN_PROGRESS','REGULAR',false,500001555,'黄柳','卤制二线',6009.13,3202.59,1194.17,556.49,10962.38,19.3163,707,5,98.08,now() - interval '1 day' - interval '707 minute',NULL,''),
  ('PB-DEMO2-20260703-0003','DEMO_FACTORY2','DF2_pt11','卤猪蹄(去大骨) 200g',355.43,166.46,161.18,5.28,355.43,'kg','IN_PROGRESS','REGULAR',false,500001552,'邓建华','真空包装线',2005.07,722.29,427.51,148.48,3303.35,19.8447,758,6,96.83,now() - interval '2 day' - interval '758 minute',NULL,''),
  ('PB-DEMO2-20260704-0004','DEMO_FACTORY2','DF2_pt2','纸片牛腱肉 80g',439.95,419.81,406.25,13.56,439.95,'kg','COMPLETED','REGULAR',false,500001554,'罗明','分割台A',3868.99,1882.84,1091.56,321.3,7164.69,17.0665,470,3,96.77,now() - interval '3 day' - interval '470 minute',now() - interval '3 day','QUALIFIED'),
  ('PB-DEMO2-20260705-0005','DEMO_FACTORY2','DF2_pt2','纸片牛腱肉 80g',394.65,406.3,388.47,17.83,394.65,'kg','COMPLETED','REGULAR',false,500001310,'郑春梅','卤制一线',3824.84,2300.85,1081.26,468.71,7675.66,18.8916,553,3,95.61,now() - interval '4 day' - interval '553 minute',now() - interval '4 day','QUALIFIED'),
  ('PB-DEMO2-20260706-0006','DEMO_FACTORY2','DF2_pt11','卤猪蹄(去大骨) 200g',476.44,457.74,450.89,6.85,476.44,'kg','COMPLETED','REGULAR',false,500001554,'罗明','真空包装线',5854.07,2305.9,1483.64,337.58,9981.19,21.8054,410,6,98.5,now() - interval '5 day' - interval '410 minute',now() - interval '5 day','QUALIFIED'),
  ('PB-DEMO2-20260707-0007','DEMO_FACTORY2','DF2_pt10','椒麻掌中宝 120g',665.38,661.36,629.52,31.84,665.38,'kg','COMPLETED','REGULAR',false,500001552,'邓建华','卤制二线',6654.9,3872.6,1998.41,485.08,13010.99,19.6731,738,4,95.19,now() - interval '6 day' - interval '738 minute',now() - interval '6 day','QUALIFIED'),
  ('PB-DEMO2-20260708-0008','DEMO_FACTORY2','DF2_pt11','卤猪蹄(去大骨) 200g',435.97,444.64,428.34,16.3,435.97,'kg','COMPLETED','REGULAR',false,500001555,'黄柳','真空包装线',5121.03,2373.56,1145.33,322.07,8961.99,20.1556,442,2,96.33,now() - interval '7 day' - interval '442 minute',now() - interval '7 day','QUALIFIED'),
  ('PB-DEMO2-20260709-0009','DEMO_FACTORY2','DF2_pt1','轻卤门腔（猪舌）120g',652.91,619.89,605.37,14.52,652.91,'kg','COMPLETED','REGULAR',false,500001554,'罗明','卤制二线',5979.42,2789.55,1294.01,617.67,10680.65,17.2299,763,6,97.66,now() - interval '8 day' - interval '763 minute',now() - interval '8 day','QUALIFIED'),
  ('PB-DEMO2-20260710-0010','DEMO_FACTORY2','DF2_pt2','纸片牛腱肉 80g',464.92,457.12,435.05,22.07,464.92,'kg','COMPLETED','REGULAR',false,500001310,'郑春梅','分割台A',5793.31,1900.12,1047.81,397.75,9138.99,19.9925,452,7,95.17,now() - interval '9 day' - interval '452 minute',now() - interval '9 day','QUALIFIED'),
  ('PB-DEMO2-20260711-0011','DEMO_FACTORY2','DF2_pt2','纸片牛腱肉 80g',808.12,803.56,773.71,29.85,808.12,'kg','COMPLETED','REGULAR',false,500001555,'黄柳','卤制二线',9541.78,3664.63,2573.79,512.73,16292.93,20.2759,688,7,96.29,now() - interval '10 day' - interval '688 minute',now() - interval '10 day','QUALIFIED'),
  ('PB-DEMO2-20260712-0012','DEMO_FACTORY2','DF2_pt2','纸片牛腱肉 80g',776.95,791.72,768.6,23.12,776.95,'kg','COMPLETED','REGULAR',false,500001555,'黄柳','分割台A',8108.25,3566.55,1724.5,588.01,13987.31,17.667,430,3,97.08,now() - interval '11 day' - interval '430 minute',now() - interval '11 day','QUALIFIED'),
  ('PB-DEMO2-20260713-0013','DEMO_FACTORY2','DF2_pt1','轻卤门腔（猪舌）120g',656.4,620.08,604.19,15.89,656.4,'kg','COMPLETED','REGULAR',false,500001555,'黄柳','卤制二线',6517.23,2673.28,2143.63,690.63,12024.77,19.3923,644,6,97.44,now() - interval '12 day' - interval '644 minute',now() - interval '12 day','QUALIFIED'),
  ('PB-DEMO2-20260714-0014','DEMO_FACTORY2','DF2_pt2','纸片牛腱肉 80g',411.35,419.38,406.98,12.4,411.35,'kg','COMPLETED','REGULAR',false,500001552,'邓建华','分割台A',5169.97,2350.68,1442.91,282.41,9245.97,22.0468,423,6,97.04,now() - interval '13 day' - interval '423 minute',now() - interval '13 day','QUALIFIED'),
  ('PB-DEMO2-20260715-0015','DEMO_FACTORY2','DF2_pt1','轻卤门腔（猪舌）120g',815.32,760.28,729.22,31.06,815.32,'kg','COMPLETED','REGULAR',false,500001552,'邓建华','卤制二线',8932.54,3953.14,2393.37,602.27,15881.32,20.8888,404,4,95.91,now() - interval '14 day' - interval '404 minute',now() - interval '14 day','QUALIFIED'),
  ('PB-DEMO2-20260716-0016','DEMO_FACTORY2','DF2_pt2','纸片牛腱肉 80g',369.3,342.88,339.25,3.63,369.3,'kg','COMPLETED','REGULAR',false,500001555,'黄柳','分割台A',4357.38,1798.04,1080.28,369.78,7605.48,22.1812,790,7,98.94,now() - interval '15 day' - interval '790 minute',now() - interval '15 day','QUALIFIED'),
  ('PB-DEMO2-20260717-0017','DEMO_FACTORY2','DF2_pt11','卤猪蹄(去大骨) 200g',507.06,468.36,456.66,11.7,507.06,'kg','COMPLETED','REGULAR',false,500001552,'邓建华','分割台A',5070.68,1910.51,1342.19,316.63,8640.01,18.4474,356,2,97.5,now() - interval '16 day' - interval '356 minute',now() - interval '16 day','QUALIFIED'),
  ('PB-DEMO2-20260718-0018','DEMO_FACTORY2','DF2_pt2','纸片牛腱肉 80g',592.08,574.98,565.69,9.29,592.08,'kg','COMPLETED','REGULAR',false,500001552,'邓建华','卤制二线',6122.78,3422.35,1936.82,569.34,12051.29,20.9595,364,4,98.38,now() - interval '17 day' - interval '364 minute',now() - interval '17 day','QUALIFIED'),
  ('PB-DEMO2-20260719-0019','DEMO_FACTORY2','DF2_pt10','椒麻掌中宝 120g',740.27,706.21,676,30.21,740.27,'kg','COMPLETED','REGULAR',false,500001552,'邓建华','分割台A',7887.77,3404.64,1435.08,654.93,13382.42,18.9496,691,3,95.72,now() - interval '18 day' - interval '691 minute',now() - interval '18 day','QUALIFIED'),
  ('PB-DEMO2-20260720-0020','DEMO_FACTORY2','DF2_pt10','椒麻掌中宝 120g',825.78,765.27,755.23,10.04,825.78,'kg','COMPLETED','REGULAR',false,500001310,'郑春梅','卤制二线',7493.57,3629.95,1648.49,676.34,13448.35,17.5733,395,6,98.69,now() - interval '19 day' - interval '395 minute',now() - interval '19 day','QUALIFIED'),
  ('PB-DEMO2-20260721-0021','DEMO_FACTORY2','DF2_pt2','纸片牛腱肉 80g',774.32,763.35,744.53,18.82,774.32,'kg','COMPLETED','REGULAR',false,500001310,'郑春梅','卤制二线',8376.29,3406.89,2642.45,654.45,15080.08,19.7551,748,6,97.53,now() - interval '20 day' - interval '748 minute',now() - interval '20 day','QUALIFIED'),
  ('PB-DEMO2-20260722-0022','DEMO_FACTORY2','DF2_pt1','轻卤门腔（猪舌）120g',380.57,353.53,344.67,8.86,380.57,'kg','COMPLETED','REGULAR',false,500001552,'邓建华','分割台A',3216.1,1958.44,1054.67,283.74,6512.95,18.4226,376,4,97.49,now() - interval '21 day' - interval '376 minute',now() - interval '21 day','QUALIFIED'),
  ('PB-DEMO2-20260723-0023','DEMO_FACTORY2','DF2_pt2','纸片牛腱肉 80g',580.95,537.92,519.68,18.24,580.95,'kg','COMPLETED','REGULAR',false,500001310,'郑春梅','卤制一线',5379.92,2686.41,1222,640.62,9928.95,18.458,265,6,96.61,now() - interval '22 day' - interval '265 minute',now() - interval '22 day','QUALIFIED'),
  ('PB-DEMO2-20260724-0024','DEMO_FACTORY2','DF2_pt2','纸片牛腱肉 80g',790.26,752.43,729.62,22.81,790.26,'kg','COMPLETED','REGULAR',false,500001555,'黄柳','卤制二线',7680.08,3599.03,2125,884.51,14288.62,18.99,463,4,96.97,now() - interval '23 day' - interval '463 minute',now() - interval '23 day','QUALIFIED'),
  ('PB-DEMO2-20260725-0025','DEMO_FACTORY2','DF2_pt2','纸片牛腱肉 80g',845.78,857.16,821.27,35.89,845.78,'kg','COMPLETED','REGULAR',false,500001554,'罗明','真空包装线',9192.16,3478.23,1782.02,728.3,15180.71,17.7105,471,2,95.81,now() - interval '24 day' - interval '471 minute',now() - interval '24 day','QUALIFIED'),
  ('PB-DEMO2-20260726-0026','DEMO_FACTORY2','DF2_pt1','轻卤门腔（猪舌）120g',484.65,497.01,490.05,6.96,484.65,'kg','COMPLETED','REGULAR',false,500001555,'黄柳','真空包装线',5241.51,2338.65,1129.34,470.07,9179.57,18.4696,583,5,98.6,now() - interval '25 day' - interval '583 minute',now() - interval '25 day','QUALIFIED'),
  ('PB-DEMO2-20260727-0027','DEMO_FACTORY2','DF2_pt10','椒麻掌中宝 120g',535.04,536.92,511.72,25.2,535.04,'kg','COMPLETED','REGULAR',false,500001555,'黄柳','分割台A',5432.5,2307.51,1166.9,429.72,9336.63,17.3892,762,5,95.31,now() - interval '26 day' - interval '762 minute',now() - interval '26 day','QUALIFIED'),
  ('PB-DEMO2-20260728-0028','DEMO_FACTORY2','DF2_pt10','椒麻掌中宝 120g',593.79,604.63,598,6.63,593.79,'kg','COMPLETED','REGULAR',false,500001310,'郑春梅','分割台A',6102.29,2656.18,1626.06,514.22,10898.75,18.0255,476,5,98.9,now() - interval '27 day' - interval '476 minute',now() - interval '27 day','QUALIFIED'),
  ('PB-DEMO2-20260729-0029','DEMO_FACTORY2','DF2_pt1','轻卤门腔（猪舌）120g',481.09,473.14,453.37,19.77,481.09,'kg','COMPLETED','REGULAR',false,500001555,'黄柳','分割台A',4934.72,2078.1,1047.9,397.83,8458.55,17.8775,622,5,95.82,now() - interval '28 day' - interval '622 minute',now() - interval '28 day','QUALIFIED'),
  ('PB-DEMO2-20260730-0030','DEMO_FACTORY2','DF2_pt10','椒麻掌中宝 120g',870.84,820.69,781.91,38.78,870.84,'kg','COMPLETED','REGULAR',false,500001554,'罗明','卤制二线',8010.11,4770.46,1736.69,887.83,15405.09,18.7709,407,5,95.27,now() - interval '29 day' - interval '407 minute',now() - interval '29 day','QUALIFIED')
) AS v(batch_number, factory_id, product_type_id, product_name,
  quantity, actual_quantity, good_quantity, defect_quantity, planned_quantity, unit,
  status, batch_type, is_trial, supervisor_id, supervisor_name, equipment_name,
  material_cost, labor_cost, equipment_cost, other_cost, total_cost, unit_cost,
  work_duration_minutes, worker_count, yield_rate, start_time, end_time, quality_status)
WHERE NOT EXISTS (
  SELECT 1 FROM production_batches p WHERE p.batch_number = v.batch_number
);
