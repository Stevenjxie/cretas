# Round 14 — Cretas Demo Benchmark Screenshots

> **Track**: Sprint 7 wave 1 §T7 (2026-05-20)
> **Env**: Cretas prod `https://admin.cretaceousfuture.com/` (per `server-operations.md` deployed via 139 nginx → 47 backend)
> **Account**: `f006_admin / 123456` (factory_super_admin, 工厂总监) per `reference_f006_liutengmen_prod_accounts.md`
> **Test data**: Read-only navigation. No write ops on F006 prod (per spec constraint).

---

## 截图清单 (10 个)

| # | 文件 | 场景 | 用途 (Boss 演示) |
|---|---|---|---|
| 0 | `01-cretas-dashboard.jpeg` (154 KB) | 首页 dashboard | Vue + Element Plus + 13 模块侧边栏 (vs HJ 12 模块顶部 nav) |
| 1 | `10-cretas-scene1-sales-orders.jpeg` (170 KB) | §1 销售订单 list | 单域简洁 + 防呆 column display + Element Plus 现代 UI |
| 2 | `12-cretas-scene1-customers.jpeg` (139 KB) | §1 客户管理 | Customer entity (PR #53 F ship) — 21 主 tabs 已 13/21 (Round 11 §A.2) |
| 3 | `20-cretas-scene2-procurement-orders.jpeg` (191 KB) | §2 采购订单 list | 单域 + 防呆 (Rule 1 max + Rule 4 幂等 已 ship) |
| 4 | `21-cretas-scene2-procurement-requisitions.jpeg` (94 KB) | §2 请购单 list | Sprint 6 W2-A MaterialRequisition ship — HJ 独立子菜单 vs Cretas 单域 |
| 5 | `30-cretas-scene3-hr-attendance.jpeg` (115 KB) | §3 HR 考勤管理 | ⚠ 后端有 AttendanceMonthlyTool, 前端 6 周矩阵 view Sprint 7+ 完善 |
| 6 | `40-cretas-scene4-finance-reports.jpeg` (119 KB) | §4 财务报表 | Cretas 财务报表入口 |
| 7 | `41-cretas-scene4-smartbi-finance.jpeg` (180 KB) ⭐⭐ | §4 SmartBI 财务分析 | **Boss 演示 highlight 2** — Vue + ECharts 现代 BI, HJ 无此功能 |
| 8 | `42-cretas-scene4-smartbi-dashboard.jpeg` (348 KB) ⭐⭐⭐ | §4 SmartBI 经营驾驶舱 | **Boss 演示 highlight 2** — 多 KPI 卡片 + 趋势图 + AI 洞察, HJ 仅 JSP 老报表 |
| 9 | `50-cretas-workflow-designer.jpeg` (175 KB) | 工作流设计器 | VueFlow editor (Sprint 3 Track-I PR #758 758-line ship) — vs HJ jsPlumb 老 editor |

---

## 补 audit 截图来源 (PR 历史)

以下 Cretas Sprint 5+6 ship 关键 UI 截图在原 PR description 里有详细图:
- PR #758 — VueFlow editor + ApprovalWorkflowService + SpEL conditions
- PR #862 — Canvas-Workflow Phase 1 (F006 审批可视化)
- PR #717/#726 — 出库 dialog max 边界 (防呆 Rule 1)
- PR #704/#710 — Sprint 4 W1/W2 物料 dialog + Tree
- PR #69 W4-A — 辅助核算 7 类 entity

---

## Boss 演示日补录 (suggested)

T7 90-min MVP 截图仅 list/dashboard. Boss 演示日 (1-2h Steve 现场录制) 建议补:
- **防呆 dialog 实拍** (Rule 1-5): 销售出库 dialog 显 max + 采购收货 dialog 含 context + 工作流跳配置 dead-end
- **AI Chat Tool 实拍**: 用 f006_admin 演示语音/文本 "查询库存" → Tool 调用 → 结果 + AI 洞察
- **Sprint 7 T1/T2/T3 placeholder dead-end 改导航 演示** (Rule 5): 当前进入"功能开发中"页 → 自动跳 SmartBI 替代方案
- **F006 prod 真实数据 dashboard**: SmartBI 6 个分析模块 (procurement / sales / receivable / payable / cost / profit) 真数据

---

## 文件大小说明

`42-cretas-scene4-smartbi-dashboard.jpeg` (348 KB) 超出 spec 200KB cap 因 dashboard 多 KPI 卡片 + 多图表 fullpage. Boss 演示日实时演示更直观, 截图主要做 doc 引用.
