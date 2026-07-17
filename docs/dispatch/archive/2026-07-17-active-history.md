# Dispatch 归档 — 2026-07-17

## F006 数量、计价、采购与 AI 契约闭环

| ID | Base SHA | Owner | Result |
|---|---|---|---|
| CFX-20260717-BE | `2233797dc8d6417885b628e45eab4a687f7ce0ee` | Codex coordinator | 统一 BOM、采购单、收货与采购建议的数量/计价单位契约及换算因子；修复付款方式、供应商历史、AI 半成品/日期/单位/模式门禁，并补齐 SKU 复制与自动采购的价格契约。目标测试、真实 JPA Context、编码检查及 `git diff --check` 通过；[PR #1413](https://github.com/Stevenjxie/cretas/pull/1413) 合并为 `57150296ad0a681e871e4a8dc2275f91bbc0d77d`。 |
| CFX-20260717-WEB | `2233797dc8d6417885b628e45eab4a687f7ce0ee` | Codex coordinator | 修复 BOM、采购草稿、AI 创建门禁、枚举回显和成本汇总单位显示；旧成本接口继续按盒口径兼容，无权威元/kg 时不猜测。目标 Vitest、类型检查、生产构建、编码检查及 `git diff --check` 通过；[PR #1414](https://github.com/Stevenjxie/cretas/pull/1414) 合并为 `9baff829c175f1f0b2fbaa36b74b95baa587ce0e`。 |

## 生产 E2E 缺口收口

| ID | Base SHA | Owner | Result |
|---|---|---|---|
| CRETAS-E2E-20260717-FE | `3ce98d4d9d3a7edca79606844c0ac34c0be7b4fb` | Newton / coordinator | 对齐供应商供货历史聚合字段，补齐销售转采购的供应商、交期、包装及数量/计价单位契约；计价单位变化强制清空旧数值，避免把 `30元/kg` 误当 `30元/g`；并合并 permission 初始化并发请求。 |
| CRETAS-E2E-20260717-AI | `3ce98d4d9d3a7edca79606844c0ac34c0be7b4fb` | Archimedes / coordinator | 工序 AI 三种模式分离：首次请求均不写入，自动/计划模式必须显式确认，建议模式无执行入口；生产计划 AI 原样保留完整 SKU 名并按真实实体 ID 门禁。 |
| CRETAS-E2E-20260717-AUDIT | `3ce98d4d9d3a7edca79606844c0ac34c0be7b4fb` | Codex coordinator | BOM 变更日志记录 JWT 请求操作者；Web 目标测试 17/17、`vue-tsc`、单 Maven 生命周期目标测试 12/12、JAR manifest、编码与 diff 检查通过；[PR #1417](https://github.com/Stevenjxie/cretas/pull/1417) squash 合并为 `9b6607982005dd5b87f4de8432aae75a5933a212`。 |

## 单位与 SKU 规格修正

| ID | Base SHA | Owner | Result |
|---|---|---|---|
| UNIT-BOX-CASE-20260717 | `893eaafc830e8c436723f7080c867b2a8613458a` | Codex coordinator | 修正系统单位合并语义，固定 `box=盒`、`case=箱`；创建失败时自动刷新并选择已有单位。目标 Vitest 6/6、`vue-tsc --noEmit`、`git diff --check` 通过；[PR #1401](https://github.com/Stevenjxie/cretas/pull/1401) 合并为 `0a535848b8c509b28f3573f594428d7e40a24557` 并完成 Web 原子部署。LIUSHANMEN 生产库已留备份，修正全局单位字典与 16 个确定性盒装 SKU 规格，补齐 64 条结构化换算；线上接口复核通过。 |

## Workflow 发布引导与原料筛选

| ID | Base SHA | Owner | Result |
|---|---|---|---|
| WF-VALIDATION-RAW-FILTER-20260717 | `0a535848b8c509b28f3573f594428d7e40a24557` | Codex coordinator | 发布前未绑定 SKU 时自动定位首个问题 Cell，全部问题 Cell 保持红框，用户开始处理后停止闪烁、绑定成功后逐项清除；顶部保留未完成数量与再次定位入口。原料 Cell 只显示原料/主材，保留 BOM 优先，新增 L1/L2/L3 与文字/拼音组合筛选。目标 Vitest 35/35、`vue-tsc --noEmit`、编码检查、`git diff --check` 通过；[PR #1402](https://github.com/Stevenjxie/cretas/pull/1402)。 |
| WF-OWNER-FILTER-20260717 | `94a80f73b29ed959a422b292f66426096aab001a` | Codex coordinator | Workflow 顶部归属选择器仅保留成品与真正的原料/主材，排除半成品、调料、辅料和包材；半成品深链路同样不会被选为 Workflow 归属，但仍可作为画布中间 Cell。目标 Vitest 4/4、`vue-tsc --noEmit`、编码检查、`git diff --check` 通过；[PR #1403](https://github.com/Stevenjxie/cretas/pull/1403)。 |
