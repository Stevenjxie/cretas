# F006 客户 Canvas Phase 2-5 上线通知

**日期**: 2026-05-19
**接收**: F006 六腾门 admin + ops
**通知方**: Cretas 团队

## 🎉 5 个新 Canvas 模块今天 LIVE prod

打开 https://admin.cretaceousfuture.com → 登录 `f006_admin` → Canvas 编辑器 → PhaseB Tab 区:

| Tab | 用途 |
|---|---|
| 🚨 **预警规则** | 配 8 类业务告警 (低库存/过期/质量异常/PO 超额/SO 异常/销售下滑/客户逾期/供应商应付) |
| 🔔 **通知模板** | 配 5 渠道通知模板 (微信/钉钉/邮件/短信/站内) + 测试发送 + 审计日志 |
| 📜 **业务规则** | SpEL 条件 + 4 action (LOG/REJECT/MODIFY/TRIGGER_WORKFLOW) — 库存创建时触发 |
| 💰 **价格策略** | 5 种 (阶梯/促销/会员/套餐/跨周期返点) + Simulator 模拟 |
| ⚡ **Canvas Cron** | DB-driven 定时任务 + ShedLock 防多实例 + 手动 runNow |

## ⚠️ 当前限制 (sister chat 后续完善)

1. **预警规则**: 4 个事件型告警 (库存低/质量异常/PO/SO) 暂未连业务事件 — 配置可保存, 触发待后续 (issue #33). 4 个 cron 型告警 (过期/销售下滑/逾期/应付) DB 查询 logic 待补 (issue #36).
2. **通知模板**: 当前只"站内消息"渠道真发, 微信/钉钉/邮件/短信留 SDK wire 后续 (issue #41).
3. **业务规则**: ORDER scope 暂仅 INVENTORY 触发 (物料批次创建). 采购单/销售单 ORDER 规则待补 — 已 file #38 #45.
4. **价格策略**: 阶梯/促销/会员/套餐 4 种 in-line OK. CYCLE 跨周期返点需 month-end batch 路径 (issue #42).
5. **定时任务**: Canvas Cron 真功能 OK. 现有 24 个硬编码 @Scheduled 后续迁移到 DynamicScheduler (issue #34).

## 操作建议

1. **本周**: F006 ops 自行进 Canvas 配 5-10 条 alert rule (库存/质量类型先, 触发机制不完整但 UI 配置体验先验证)
2. **下周**: 等 sister chat ship business event publish (#33) → alert rules 开始真触发
3. **下下周**: 4 channel SDK + CYCLE batch 等高级功能逐步 LIVE

## 联系反馈

任何 Canvas 操作问题 → 群里 @Steve / @Cretas team. Bug 报告填 SR-FORM (内部链接).
