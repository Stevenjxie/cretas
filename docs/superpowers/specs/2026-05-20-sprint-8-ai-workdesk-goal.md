# Sprint 8 Goal — AI Workdesk 转型

**触发**: 3 份独立 audit 一致结论 (本 session 我的预测 + Other chat audit + Round 14 demo + HJ 量化对比)
**日期**: 2026-05-20
**状态**: Goal 草案, 等 /goal 设置后写详细 spec

---

## 战略定位 (核心)

> **不追平 HJ — 那是 20 年的债。要绕过 HJ — 用 AI + 食品垂直 6 个月做出 HJ 永远做不出的东西。**

3 份 audit 一致量化数据:
- Round 14 demo 4 场景实测: Cretas 16 win / HJ 17 win / 15 平 — **几乎平手** (差距比想象小)
- Cretas 量级: 2,288 endpoint / 387 Vue / 416 entity / 476 Tool — 跟 HJ 同数量级
- 真正差距: **20+ 年客户磨过的细节沉淀** (P2, 不该追) + **3 个 P0 深水区** (现在补)
- Cretas 独有 (HJ 0): SmartBI / AI Tool-Skill (476/18) / 餐饮子域 / 食品溯源

**Sprint 8 策略**: 不补 HJ 缺口, 而是放大 Cretas 独有优势 — **AI Workdesk 化** + **食品垂直深化**。

---

## 主要成功标准 (单一)

> Sprint 5+6+7 ship 的所有 customer business entity (微信/通话/商机/业绩/期间/科目/工资/请购/凭证/3 报表) **全部可被 AI 自然语言调用**。
>
> 真 AI 化评分: **3/10 → 8/10**。

衡量方法:
- 卤味老板说"今天该跟谁?" → AI 输出排序客户清单 (端到端通)
- 财务主管说"5 月经营怎么样?" → AI 输出经营摘要 + 三表链接 (端到端通)
- 质量主管说"启动召回" → AI 编排批次溯源 + 客户通知 + 库存冻结 (端到端通)

---

## 5 Phase 节奏 (3-4.5 周)

### P0 修信任 + audit cleanup (1-2d, BLOCKING)

不修不能进 P1:
- 修 Sprint 7 T3 路由 (`finance/reports/list.vue` 占位 vs `finance/report/index.vue` 真页路径冲突)
- 清 11 占位页 (删除或加 feature flag)
- grep 160+ null tool 分类 (真未实现 / 测试桩 / 故意 fail-fast)
- audit 102 @Deprecated 分类 (废弃在用 / 真死代码)
- 输出 `docs/audits/2026-05-XX-pre-sprint-8-cleanup.md`

### P1 卤味老板 Workdesk V1 (3-5d) — F006 真场景

8 Tool + 1 Skill + Preview demo + E2E 录屏:
- customer_priority_query / wechat_record_recent_query / wechat_record_create (Preview) / call_record_followup_pending / opportunity_stage_alert / opportunity_transition_stage (Preview) / customer_revenue_trend / processing_capacity_today
- Skill: `daily-customer-followup` 串 5-6 Tool 输出排序客户清单
- 客户输入 "今天该跟谁" → AI 输出 (5min E2E 录屏 demo)

### P2 财务主管 Workdesk (3-5d)

14-15 Tool + 1 Skill + 顺手验证 P0 T3 修复:
- account_query / account_tree_lookup / period_status_query / period_request_close / period_confirm_close / balance_sheet_query / income_statement_query / cashflow_statement_query / wage_cost_summary / wage_policy_query / opportunity_funnel_stats / commission_pending_total / commission_calculate_preview / accounts_receivable_aging / voucher_count_by_type
- Skill: `monthly-financial-close` 串 8-10 Tool
- 客户输入 "5 月经营怎么样" → AI 输出经营摘要 + 三表链接

### P3 食品安全召回 Workdesk (5d) — HJ vs Cretas 差异化护城河

法定 P0 + Cretas vs HJ 永久差异化:
- 3 新 entity: `HaccpCheckpoint` / `AdditiveLimit` (GB 2760 seed) / `RecallEvent` + `RecallAction`
- 4 Flyway: V20260801_01~04
- 3 Skill: `haccp-checkpoint-management` / `food-additive-compliance` / `food-safety-recall`
- Tool: batch_trace_by_customer_date / batch_full_trace / haccp_checkpoint_review / additive_compliance_check / inventory_freeze (Preview) / customer_notify_batch (Preview) / regulatory_report_generate / recall_loss_estimate

### P4 仓管员+采购员+质量主管 Workdesk + LLM router tuning (5d)

3 Workdesk 一周齐发 + 收尾:
- **仓管员** (per 防呆 rule "告诉他要收多少就行"): material_today_receiving_query / receive_with_limit (Rule 1 max)
- **采购员**: stock_alert / sales_forecast_7day / supplier_delivery_eta / requisition_create (Preview)
- **质量主管**: quality_check_summary / haccp_status_query / additive_compliance / release_decision (Preview)
- LLM router tuning: 286 intent 去重 + 绑 tool_name + 51→80+ test intent
- 3 demo 视频汇总 (Boss 演示弹药矩阵)

---

## 不做的 (Sprint 9+ 推迟)

| 类别 | 项 |
|---|---|
| **P1 大客户期望** (Sprint 9) | 核价单+采购底稿 / 8 种 PO 关联类型 / 14 支付方式+多币种 / 客户档案剩 4 tab (短信/邮件/售后/财务往来) / 工作流剩 23 类 DecisionType 接入 / 6×7 月考勤矩阵 UI / 银行批量转账 |
| **P0/P1 已知 BLOCKED** | 数电票真集成 (provider 待选) / RN App (priority 不明) |
| **P2 永远不追** | 印章/签名/金税接口 / 国际化 32 币种跨境 / 千级业务模板库 / 微服务化 38 子域拆分 |
| **Sprint 9+ Backlog** | 序列号 sub-tracking / 设备 lifecycle / 招聘宿舍 / docs 子域自动生成 / TV 大屏 / HJ APK 实测 (Round 9) |

---

## 强制约束 (per audit 教训)

1. **P0 必须先完成** — 修信任为后续 Phase 验收前提
2. **所有 Tool 必须 grep main 确认 entity 实际状态** (audit 揭过假 entity 假设, 例如 VoucherDetail/BalanceSheet entity 不存在)
3. **每 Phase 必 Steve smoke test** (而非仅 mvn test PASS) — agent 报 ship 后必须 F006 真账号端到端验证
4. **每 Phase 后必更新 audit 报告** (AI 化评分追踪 3 → 4 → 5 → 6 → 7 → 8)
5. **dispatch agent brief 必含 worktree CRITICAL section** (per `feedback_agent_worktree_isolation_cwd_drift` HARD)
6. **数据库 schema 变更走 V20260801+ 命名** (前面 V20260720 已占用)
7. **每个 Workdesk demo 必录 5min 端到端 mp4** (给 Boss 演示 + 销售弹药)

---

## 长期路径 (Sprint 8-13, 6 个月)

| Sprint | 时间 | 工作 | 效果 |
|---|---|---|---|
| **Sprint 8** | 5 周 | AI Workdesk V1 + 食品 P0 (本 spec) | 客户演示不翻车 + 食品差异化建立 |
| Sprint 9 | 5 周 | 补 P1 客户档案剩 4 tab + 工作流接入 + 银行转账 | 中型客户合同能签 |
| Sprint 10 | 5 周 | 仓管员+质量主管 Workdesk 深化 + 食品召回 Skill 实战 | 3 Workdesk 形成销售弹药矩阵 |
| Sprint 11 | 5 周 | 大客户审计 (印章/签名/电汇) | 大客户 POC 通过 |
| Sprint 12-13 | 10 周 | 食品 SaaS 模板库 (F006 卤味 → 烘焙/乳制品/调味料) | 打开横向扩张通道 |

**6 月后**: HJ 优势 (20 年模板沉淀, 仍 win) / Cretas 优势 (AI Workdesk + 食品垂直 + 餐饮, HJ 0)。
**Cretas 不是"便宜版 HJ", 是"AI 时代的食品行业 ERP"**。

---

## /goal 命令用文本 (短版)

```
Sprint 8 (3-4.5 周): 把 Cretas 从"传统菜单 ERP + AI 套壳"升级为"真 AI Workdesk"。

战略: 不追 HJ (Round 14 demo 已 17:16 几乎平手, 追是 20 年的债), 用 AI + 食品垂直绕过。
Workdesk-Driven 节奏 — P0 修信任先行 + 4 Phase 各 1 Workdesk demo。

✅ 主成功标准 (单一): Sprint 5+6+7 ship 的所有 customer business entity 
   (微信/通话/商机/业绩/期间/科目/工资/请购/凭证/3 报表) 全部可被 AI 自然语言调用。
   真 AI 化评分 3/10 → 8/10。

📅 5 Phase:
P0 (1-2d BLOCKING): 修 Sprint 7 T3 路由 + 清 11 占位 + audit 160 null tool + 102 @Deprecated
P1 (3-5d): 卤味老板 Workdesk V1 — 8 Tool + daily-customer-followup Skill + E2E 录屏
P2 (3-5d): 财务主管 Workdesk — 14-15 Tool + monthly-financial-close Skill
P3 (5d): 食品安全召回 Workdesk — HACCP + GB 2760 + 召回闭环 3 Skill (HJ vs Cretas 护城河)
P4 (5d): 仓管员+采购员+质量主管 Workdesk + LLM router tuning

⛔ 不做 (推 Sprint 9+): 核价单+底稿 / 8 PO 类型 / 14 支付 / 客户档案剩 4 tab / 工作流 23 类 / 银行转账 / 序列号 / 设备 lifecycle / 招聘宿舍 / docs 子域 / TV 大屏 / 微服务化

⛔ 永远不追 (HJ 20 年沉淀): 印章/签名/金税 / 32 币种跨境 / 千级业务模板库

📋 强制约束:
- P0 必须先完成 (修信任为后续 Phase 验收前提)
- 所有 Tool 必须 grep main 确认 entity 实际状态 (audit 揭假 entity)
- 每 Phase 必 Steve smoke test (非仅 mvn test PASS)
- 每 Phase 后必更新 audit 评分 (3→4→5→6→7→8)
- agent brief 必含 worktree CRITICAL section
- 每 Workdesk 必录 5min E2E mp4 (Boss 演示弹药)
```

---

**下一步**: 你用 /goal 命令把上面短版设到 chat, 然后 ping 我 — 我接着用 brainstorming skill 写详细 implementation spec doc, 然后 writing-plans skill 出 Sprint 8 P0-P4 detailed task breakdown。
