-- 让通知防重键装得下**日粒度**，供打烊日结复用同一条通知链。
--
-- ## 为什么改这一列而不是新建一张表
--
-- 打烊日结要的三样（角色路由 / Java 通道 / 幂等防重）`value_notifier` 全都有，
-- 唯一挡路的是 `period_month varchar(7)` —— 装得下 `2026-08`，装不下 `2026-08-13`。
-- 新建一张日粒度通知表 = 同一件事两份存储（形态 D：两份一定会漂，
-- 而漂的表现是「有一条链路重复推送」，店长每天收到两遍）。
--
-- ## 为什么是安全的
--
--   · **加宽**，不是缩窄 —— 现存 `2026-08` 这种值一个都不受影响
--   · 唯一键 `(factory_id, period_month, recipient_role)` 形状不变
--   · 没有默认值变化、没有 NOT NULL 变化、没有数据迁移
--   · 回滚方向：只要没写入过 >7 字符的行，`varchar(7)` 可以直接改回去
--
-- ⚠️ 列名保留 `period_month` 不改 —— 改名要同时改 4 处 SQL 和调用方，
--    而这次的收益只是「名字更准」。⛔ 顺手改名是本轮反复讲的那个时间漏斗。
--    在注释里写明它现在承载的是「通知周期键」，比改名便宜且不引入风险。

ALTER TABLE restaurant_value_notifications_log
    ALTER COLUMN period_month TYPE VARCHAR(16);

COMMENT ON COLUMN restaurant_value_notifications_log.period_month IS
    '通知周期键。月度通知写 YYYY-MM；打烊日结写 YYYY-MM-DD。'
    '⛔ 列名沿用历史，实际语义是「周期键」不限于月。';
