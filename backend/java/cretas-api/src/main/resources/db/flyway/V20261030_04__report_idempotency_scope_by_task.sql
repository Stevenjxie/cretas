-- 幂等键的作用域要含【工序】—— 否则同号会跨工序互相吞
--
-- 触发 (2026-08-17 对抗审计第 2 轮, 生产实测):
--   用第②道的 clientRequestId 提交【第③道】的报工 → 返回 idempotentReplay=true
--   和第②道的 reportId。第③道的报工【被静默吞掉】: 工人报了, 系统说成功, 其实什么都没记。
--   这比双扣更隐蔽 —— 双扣至少数字不对, 这个是活儿白干了。
--
-- 成因: V20261030_03 的唯一索引是 (factory_id, client_request_id), 不含工序。
--   设计卡里写「用户再报一笔时前端重新生成号」—— 那是【约定】不是【机制】,
--   而约定只有人记得才生效, 这正是它想解决的那个问题。
--
-- 语义: 幂等守的是「同一次点击的重试」, 而一次点击必然属于【某一道工序】。
--   ⇒ 同号 + 同工序 = 重试(拦); 同号 + 不同工序 = 两件事(放行)。
--
-- ⛔ 不改成「号必须全局唯一」: 那会把客户端的号生成规则变成后端约束,
--   旧版 App 和任何复用号的客户端都会被静默拦掉 —— 同一个病换个方向犯。

DROP INDEX IF EXISTS uk_production_report_client_request;

CREATE UNIQUE INDEX IF NOT EXISTS uk_production_report_client_request
    ON production_reports (factory_id, work_process_task_id, client_request_id)
    WHERE client_request_id IS NOT NULL AND deleted_at IS NULL;

COMMENT ON COLUMN production_reports.client_request_id IS
    '客户端提交幂等键。作用域 = (工厂, 工序任务, 号): 同号同工序=重试(拦), 同号不同工序=两件事(放行)。NULL = 旧版 App, 向后兼容不拦。';
