# Dispatch 归档 — 2026-07-18

## 生产只读 Playwright 统一框架

| ID | Base SHA | Owner | Result |
|---|---|---|---|
| E2E-PROD-READONLY-HARNESS-20260717 | `3ce98d4d9d3a7edca79606844c0ac34c0be7b4fb` | Codex `/root` | 建立共享 `scripts/e2e/production-readonly/` MCP/CLI/fixture 框架与 before-send mutation guard；迁移 BOM/Workflow 契约，删除两个旧 runner 与重复依赖包，把生产写业务链改为显式非生产入口；同步 `AGENTS.md`、Web/Playwright E2E skills 和 drift 门禁。Node 单元 12/12、Playwright fixture 3/3，生产业务写请求 0；[PR #1419](https://github.com/Stevenjxie/cretas/pull/1419) squash 合并为 `8e35d2a81acac5298d40320aa2ff75ef4f8bec2a`。 |
