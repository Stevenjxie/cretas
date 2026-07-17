# Dispatch 归档 — 2026-07-17

## 单位与 SKU 规格修正

| ID | Base SHA | Owner | Result |
|---|---|---|---|
| UNIT-BOX-CASE-20260717 | `893eaafc830e8c436723f7080c867b2a8613458a` | Codex coordinator | 修正系统单位合并语义，固定 `box=盒`、`case=箱`；创建失败时自动刷新并选择已有单位。目标 Vitest 6/6、`vue-tsc --noEmit`、`git diff --check` 通过；[PR #1401](https://github.com/Stevenjxie/cretas/pull/1401) 合并为 `0a535848b8c509b28f3573f594428d7e40a24557` 并完成 Web 原子部署。LIUSHANMEN 生产库已留备份，修正全局单位字典与 16 个确定性盒装 SKU 规格，补齐 64 条结构化换算；线上接口复核通过。 |
