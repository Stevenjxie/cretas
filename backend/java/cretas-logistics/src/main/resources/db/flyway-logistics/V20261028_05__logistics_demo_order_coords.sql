-- DEMO_LOGISTICS 演示订单补录经纬度 + RESOLVED
-- V20261028_02 种订单时无真实经纬度 (mock 只有 SVG 像素坐标, 不伪造 lat/lng) → location_status=UNRESOLVED。
-- 排线工作台 fool-proof 门: 有未定位门店则拦截生成路线。为让 demo 可端到端走通闭环 (生成/确认/导出),
-- 给 13 个演示门店补一组苏州真实范围内 (bbox lon 120.30~120.85 / lat 31.15~31.55, 见 mapProjection.ts)
-- 按区域分布的合成坐标, 置 RESOLVED。仅 DEMO_LOGISTICS 演示租户, 真实租户订单仍需真实定位。
-- 每店坐标互不相同 (避免地图撒点重叠)。幂等 (纯 UPDATE, 可重复执行)。
UPDATE logistics_delivery_orders SET longitude = CASE store_code
    WHEN 'S-001' THEN 120.630 WHEN 'S-002' THEN 120.560 WHEN 'S-003' THEN 120.628
    WHEN 'S-004' THEN 120.612 WHEN 'S-005' THEN 120.660 WHEN 'S-006' THEN 120.720
    WHEN 'S-007' THEN 120.742 WHEN 'S-008' THEN 120.635 WHEN 'S-009' THEN 120.540
    WHEN 'S-010' THEN 120.552 WHEN 'S-011' THEN 120.602 WHEN 'S-012' THEN 120.640
    WHEN 'S-013' THEN 120.615 ELSE longitude END,
  latitude = CASE store_code
    WHEN 'S-001' THEN 31.430 WHEN 'S-002' THEN 31.332 WHEN 'S-003' THEN 31.310
    WHEN 'S-004' THEN 31.292 WHEN 'S-005' THEN 31.242 WHEN 'S-006' THEN 31.322
    WHEN 'S-007' THEN 31.302 WHEN 'S-008' THEN 31.232 WHEN 'S-009' THEN 31.302
    WHEN 'S-010' THEN 31.282 WHEN 'S-011' THEN 31.222 WHEN 'S-012' THEN 31.162
    WHEN 'S-013' THEN 31.252 ELSE latitude END,
  location_status = 'RESOLVED',
  updated_at = NOW()
WHERE factory_id = 'DEMO_LOGISTICS' AND store_code IN
  ('S-001','S-002','S-003','S-004','S-005','S-006','S-007','S-008','S-009','S-010','S-011','S-012','S-013');
