-- Wave2 价格异常威慑引擎 — 供应商进价异常"解释/确认"记录表 (smartbi_db)
--
-- 邓总差异化护城河: 洗洁精 110→150、青菜 2.5→2 等相邻采购单价异常,
-- 系统检测 (price_anomaly.detect_price_anomalies) 后, 管理者对每条异常要求供应商录入
-- "解释" (原因 dropdown + 备注), 不自动处罚; 连续同向异常累计 → 标高风险供应商 (威慑)。
--
-- 检测本身无需新表 (直接扫 agg_supplier_price 历史)。本表只记 ack/解释,
-- 用于 (a) 防止同一异常重复报警 (幂等), (b) 留痕谁在何时给了什么解释,
-- (c) 累计 consecutive_anomaly_count 标高风险。
--
-- 版本: origin/main 最高 Python smartbi 迁移 V20260916_02, 故 V20260917_01 安全。
-- (Java flyway 已有 V20260917_01/02, 但本表属 smartbi_db, 两套迁移目录独立编号互不影响。)

CREATE TABLE IF NOT EXISTS price_anomaly_ack (
    id                  BIGSERIAL       PRIMARY KEY,
    factory_id          VARCHAR(50)     NOT NULL,
    normalized_name     VARCHAR(200)    NOT NULL,               -- 食材归一名 (agg_supplier_price.normalized_name 一致)
    ingredient_name     VARCHAR(200),                           -- 展示用原名 (报警时快照)
    supplier_id         VARCHAR(191),
    supplier_name       VARCHAR(200),
    -- 被确认的那次异常的 "指纹": 同一 (food, supplier, 新价, 送货日) 只确认一次 (幂等键)。
    anomaly_delivery_date DATE          NOT NULL,
    old_price           NUMERIC(12,4),                          -- 报警时快照: trailing 上一次价
    new_price           NUMERIC(12,4)   NOT NULL,               -- 报警时快照: 最近一次异常价
    delta_pct           NUMERIC(8,4),                           -- 报警时快照: (new-trailing_avg)/trailing_avg*100
    reason_code         VARCHAR(40)     NOT NULL,               -- SEASONAL / MARKET_RISE / SPEC_CHANGE / OTHER
    reason_note         TEXT,                                   -- reason_code=OTHER 必填补充, 其它选填
    acknowledged_by     VARCHAR(100),                           -- 录入解释的用户 (username)
    created_at          TIMESTAMP       NOT NULL DEFAULT NOW(),
    -- 幂等: 一条异常 (factory, food, supplier, 异常送货日) 只能 ack 一次。
    -- supplier_id 可能为 NULL (历史进价无供应商), 用 COALESCE 折叠为空串保证唯一约束生效。
    CONSTRAINT uq_price_anomaly_ack UNIQUE (factory_id, normalized_name, anomaly_delivery_date, supplier_id)
);

ALTER TABLE price_anomaly_ack ENABLE ROW LEVEL SECURITY;
ALTER TABLE price_anomaly_ack FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON price_anomaly_ack;
CREATE POLICY tenant_isolation ON price_anomaly_ack FOR ALL
    USING  (factory_id = current_setting('app.factory_id', true))
    WITH CHECK (factory_id = current_setting('app.factory_id', true));

-- 查询: 某食材/供应商的已确认异常 (报警去重) + 累计高风险标记。
CREATE INDEX IF NOT EXISTS idx_pa_ack_factory_food   ON price_anomaly_ack (factory_id, normalized_name, anomaly_delivery_date DESC);
CREATE INDEX IF NOT EXISTS idx_pa_ack_factory_supplier ON price_anomaly_ack (factory_id, supplier_id, anomaly_delivery_date DESC);

-- GRANT — 历史复发陷阱 (#367 / #390 / #390 entity_resolution), 新表必须显式给 smartbi_user DML + sequence。
GRANT SELECT, INSERT, UPDATE, DELETE ON price_anomaly_ack TO smartbi_user;
GRANT USAGE, SELECT ON SEQUENCE price_anomaly_ack_id_seq TO smartbi_user;

COMMENT ON TABLE price_anomaly_ack IS
  'Wave2 价格异常威慑: 供应商进价异常的解释/确认记录 (append-only-ish, 幂等唯一约束). '
  '检测扫 agg_supplier_price 历史无需本表; 本表记 ack/解释用于报警去重 + 高风险累计. '
  'Added 2026-06-04 (Wave2 价格异常威慑引擎, 邓总护城河).';
