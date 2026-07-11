-- DEMO_LOGISTICS 演示订单真实门店名称 + 地址 (客户要求地图显示真实门店名称)
-- V20261028_02 种订单时用了通用名 "配送门店 01"，客户希望显示真实门店名。
-- 回填原始 mockData 的真实苏州门店名 + 地址。幂等 UPDATE。DEMO 演示租户 only。
UPDATE logistics_delivery_orders SET store_name = CASE store_code
    WHEN 'S-001' THEN '渝八两苏州相城东桥店' WHEN 'S-002' THEN 'No.1606江苏苏州虎丘浅湾商业中心店'
    WHEN 'S-003' THEN '渝八两苏州繁花中心店' WHEN 'S-004' THEN 'No.1439江苏苏州姑苏区印巷美食城店'
    WHEN 'S-005' THEN '苏州市吴中区浦田打工楼店' WHEN 'S-006' THEN '苏州唯亭店'
    WHEN 'S-007' THEN 'No.5554苏州新光天地店' WHEN 'S-008' THEN 'No.5518江苏苏州双湖广场店'
    WHEN 'S-009' THEN '苏州高新区港龙城商场店' WHEN 'S-010' THEN '苏州高新区金鹰商业广场店'
    WHEN 'S-011' THEN 'No.4108苏州吴中浦街店' WHEN 'S-012' THEN 'No.4219江苏苏州同里古镇店'
    WHEN 'S-013' THEN 'No.4477苏州吴中越溪街道店' ELSE store_name END,
  address = CASE store_code
    WHEN 'S-001' THEN '相城区东桥镇长平路' WHEN 'S-002' THEN '虎丘区浅湾商业中心'
    WHEN 'S-003' THEN '姑苏区人民路繁花中心' WHEN 'S-004' THEN '姑苏区印巷美食城'
    WHEN 'S-005' THEN '吴中区浦田路' WHEN 'S-006' THEN '工业园区唯亭镇'
    WHEN 'S-007' THEN '工业园区新光天地' WHEN 'S-008' THEN '吴中区双湖广场'
    WHEN 'S-009' THEN '高新区港龙城' WHEN 'S-010' THEN '高新区金鹰商业广场'
    WHEN 'S-011' THEN '吴中区浦街' WHEN 'S-012' THEN '吴江区同里古镇'
    WHEN 'S-013' THEN '吴中区越溪街道' ELSE address END,
  updated_at = NOW()
WHERE factory_id = 'DEMO_LOGISTICS' AND store_code LIKE 'S-%';
