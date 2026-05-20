# Round 14 — HJ Demo Benchmark Screenshots

> **Track**: Sprint 7 wave 1 §T7 (2026-05-20)
> **Account**: lyh01 / admin / Aa123456 (per `reference_hongjian_test_account.md`)
> **Test data**: No write ops performed (read-only navigation only). `TEST_R14_*` prefix reserved if future write ops needed.
> **Account safety**: ⛔ admin password / factory / OAuth / 充值 untouched.

---

## 截图清单 (8 个)

| # | 文件 | 场景 | 用途 (Boss 演示) |
|---|---|---|---|
| 0 | `00-hj-login.jpeg` (89 KB) | 登录页 | 演示开场 — HJ 老 JSP 登录 vs Cretas Vue 现代化 |
| 1 | `01-hj-main-dashboard.jpeg` (208 KB) | main 模块导航 | HJ 12 模块 (顶部横向 nav) |
| 2 | `02-hj-main-modules-nav-fullpage.jpeg` (207 KB) | main 全页 | 含流程图入口图 |
| 3 | `10-hj-scene1-sales-list.jpeg` (493 KB) ⭐ | §1 销售订单 list | 8 字段 + 37 查询条件 + 14 支付 + 32 币种 + 7 颜色标记 + 行末 11 操作 ▼ — **HJ 字段密度极致体现** |
| 4 | `11-hj-scene1-sales-create-workflow-form.jpeg` (97 KB) | §1 销售单创建工作流 form | 工作流节点 "销售订单创建" 演示 |
| 5 | `20-hj-scene2-procurement-list.jpeg` (619 KB) ⭐ | §2 采购订单 list | 8 种关联类型 (linklistarray) + 14 支付 + 采购类型 (正常/进口) — **大企业广度体现** |
| 6 | `30-hj-scene3-hr-attendance-monthly.jpeg` (108 KB) | §3 HR 月考勤 | 6 周 × 7 天 矩阵 + 3 时长维度 + 7 部门快捷切换 |
| 7 | `40-hj-scene4-finance-voucher-create.jpeg` (57 KB) | §4 财务凭证 | 复式记账 8 列 (摘要/科目/数量/币别/辅助核算/借/贷) + 凭证模板复用 |

---

## 复用 baseline 截图 (93 张)

详细 deep audit screenshots in `06-宏见测试账号深度审计/screenshots/`:
- `nav-02-销售管理-flowchart-fullpage.png` — 销售业务流程图
- `nav-03-采购管理-fullpage.png` — 采购 11 节点流程图
- `nav-05-财务管理-fullpage.png` — 财务 14 节点流程图
- `nav-10-人力资源-fullpage.png` — HR 9 节点流程图
- `销售-01-list.png`, `销售-02-操作下拉.png` — 销售订单 11 项操作菜单
- `财务-01-会计凭证.png` — 凭证创建详细
- `人力-01-考勤.png` — HR 月考勤矩阵

---

## Boss 演示日补录 (suggested)

T7 90-min MVP 限于 budget 只录关键入口. Boss 演示日 (1-2h Steve 现场) 建议补录:
- **审批工作流**: 我创建/我参与/待处理 3 个 sub
- **核价单 + 采购底稿** (Cretas 缺): 演示 HJ 大企业中间审批单据
- **结账管理 + 报表三表** (Cretas Sprint 7 待 ship): 演示 HJ 完整财务月结流程
- **客户档案 21 主 tabs**: 演示 HJ 客户深度

---

## 文件大小说明

部分文件 > 200 KB 因 list page 字段密度极高 (37 查询 + 大量数据行). 演示压缩可后置 (Boss 演示日实时演示更直观, 截图主要做 doc 引用).
