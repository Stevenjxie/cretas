-- D-6 入库异常字段补迁移 (test env schema drift 修复)
-- purchase_receive_records 表在 SP6 D-6 实现时新增了 3 列，
-- 但当时只手动 apply 到 prod，未写入 Flyway，导致 test env 缺列。
-- 此迁移补齐 test env（prod 已有则 IF NOT EXISTS 幂等）。
--
-- 影响: PurchaseReceiveRecord JPA 查询时报 "column rr1_0.decision_status does not exist"，
-- 导致 PO submit 路径中任何加载 PurchaseReceiveRecord 的 JPA query 报错，
-- 整个 PO submit 事务被 PostgreSQL 标记 aborted，随后 writeExecutionLog 也失败 => 500。
--
-- 修复: 补全 3 列，保持与 prod schema 一致。

ALTER TABLE purchase_receive_records
    ADD COLUMN IF NOT EXISTS exception_type    VARCHAR(32),
    ADD COLUMN IF NOT EXISTS exception_qty     NUMERIC(15, 4),
    ADD COLUMN IF NOT EXISTS decision_status   VARCHAR(32);

COMMENT ON COLUMN purchase_receive_records.exception_type  IS 'SP6 D-6 — 收货异常类型：OVER_RECEIVE / SHORT_RECEIVE / null（正常）';
COMMENT ON COLUMN purchase_receive_records.exception_qty   IS 'SP6 D-6 — 异常数量（超收正值，少收正绝对值）';
COMMENT ON COLUMN purchase_receive_records.decision_status IS 'SP6 D-6 — 异常决策状态：PENDING_DECISION / ACCEPTED / RETURNED / null（无异常）';
