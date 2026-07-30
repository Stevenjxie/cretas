-- 2026-06-04: 设备中心 (GET /api/mobile/{factoryId}/scale-devices) 报 500
-- "Could not extract column [12] ... Cannot cast to boolean: \x00"
-- (ScaleDeviceController.enrichDeviceDTO -> brandModelRepository.findById).
--
-- 根因: scale_brand_models 的 has_bluetooth/has_ethernet/has_serial_port/
-- has_usb/has_wifi 5 列在库里是 text 类型 (存的是历史 bytea->text 的字面量
-- '\x00'/'\x01'/''), 但 ScaleBrandModel 实体把它们映射为 Boolean -> JDBC 读取
-- 时无法把 text 转 boolean -> 500.
--
-- 修复: 转成 boolean, 保留原值 (末位字符 '1' => true, 其余/空 => false).
-- 幂等: 仅当还是 text 时才转, 故手动预先执行过也安全, flyway 重跑无副作用.
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_name = 'scale_brand_models'
      AND column_name = 'has_bluetooth'
      AND data_type = 'text'
  ) THEN
    ALTER TABLE scale_brand_models
      ALTER COLUMN has_bluetooth   TYPE boolean USING (RIGHT(COALESCE(has_bluetooth,   '')::text, 1) = '1'),
      ALTER COLUMN has_ethernet    TYPE boolean USING (RIGHT(COALESCE(has_ethernet,    '')::text, 1) = '1'),
      ALTER COLUMN has_serial_port TYPE boolean USING (RIGHT(COALESCE(has_serial_port, '')::text, 1) = '1'),
      ALTER COLUMN has_usb         TYPE boolean USING (RIGHT(COALESCE(has_usb,         '')::text, 1) = '1'),
      ALTER COLUMN has_wifi        TYPE boolean USING (RIGHT(COALESCE(has_wifi,        '')::text, 1) = '1');
  END IF;
END $$;
