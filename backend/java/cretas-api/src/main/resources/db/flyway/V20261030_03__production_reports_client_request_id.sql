-- 报工幂等键: 客户端请求号
--
-- 触发 (2026-08-17 生产实测): 对 F006 提交两次【完全相同】的领用报工(间隔 37ms),
-- 两次都 200、产生两条报工、库存 consumed 0.00 → 1.00 —— 领 0.5 扣了 1.0。防护只在前端
-- (disabled={submitting}), 网络重试/离线重发/进程重开/API 直调全都绕得过去。
--
-- 报工改成【提交即入账】之后, 重复提交 = 库存立刻被多扣, 风险等级变了。
--
-- ⛔ 不用时间窗去重(legacy WorkReportingServiceImpl 那套 5 分钟窗):
--    报工是合法高频动作(分段报工/领两批料/多人分报), 时间窗会误拦正当操作,
--    而会误拦的闸最后一定被绕开或关掉。请求号能区分「重试」和「真的第二笔」。
--
-- 设计卡 docs/decisions/2026-08-17-报工幂等用客户端请求号而非时间窗.md
-- 模式与 production_plans.client_request_id 一致(仓里已有先例)。

ALTER TABLE production_reports
    ADD COLUMN IF NOT EXISTS client_request_id VARCHAR(128);

COMMENT ON COLUMN production_reports.client_request_id IS
    '客户端提交幂等键。同一次点击的重试带同一个号; 用户真的再报一笔时前端重新生成。NULL = 旧版 App, 向后兼容不拦。';

-- 部分唯一索引: 只约束带号的行, NULL 不参与 (旧版 App 照常工作)
CREATE UNIQUE INDEX IF NOT EXISTS uk_production_report_client_request
    ON production_reports (factory_id, client_request_id)
    WHERE client_request_id IS NOT NULL AND deleted_at IS NULL;
