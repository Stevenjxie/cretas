# Dispatch 归档 — 2026-07-17

## 单位与 SKU 规格修正

| ID | Base SHA | Owner | Result |
|---|---|---|---|
| UNIT-BOX-CASE-20260717 | `893eaafc830e8c436723f7080c867b2a8613458a` | Codex coordinator | 修正系统单位合并语义，固定 `box=盒`、`case=箱`；创建失败时自动刷新并选择已有单位。目标 Vitest 6/6、`vue-tsc --noEmit`、`git diff --check` 通过；[PR #1401](https://github.com/Stevenjxie/cretas/pull/1401) 合并为 `0a535848b8c509b28f3573f594428d7e40a24557` 并完成 Web 原子部署。LIUSHANMEN 生产库已留备份，修正全局单位字典与 16 个确定性盒装 SKU 规格，补齐 64 条结构化换算；线上接口复核通过。 |

## Workflow 发布引导与原料筛选

| ID | Base SHA | Owner | Result |
|---|---|---|---|
| WF-VALIDATION-RAW-FILTER-20260717 | `0a535848b8c509b28f3573f594428d7e40a24557` | Codex coordinator | 发布前未绑定 SKU 时自动定位首个问题 Cell，全部问题 Cell 保持红框，用户开始处理后停止闪烁、绑定成功后逐项清除；顶部保留未完成数量与再次定位入口。原料 Cell 只显示原料/主材，保留 BOM 优先，新增 L1/L2/L3 与文字/拼音组合筛选。目标 Vitest 35/35、`vue-tsc --noEmit`、编码检查、`git diff --check` 通过；[PR #1402](https://github.com/Stevenjxie/cretas/pull/1402)。 |
