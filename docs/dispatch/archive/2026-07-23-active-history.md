# Dispatch 完成归档 — 2026-07-23

## BUG-F006-PROD-PLAN-ACTION-REGRESSION-001 — `merged`

- **Base SHA**：`9ef4cde8a3e5e6859f3530c2bef5bd43b3f106af`
- **Owner**：`/root`
- **范围**：生产计划列表操作区、目标测试、F006 复盘。
- **结果**：恢复未完成普通计划的核对结单、存货生产小结及其余状态驱动生产操作；完成计划保持只读；核算入口补充结单门禁说明，计划汇总保持随时可达。
- **验证**：Vitest 6/6；`vue-tsc -b` + Vite production build（4457 modules）；`git diff --check`。
- **实现/合入**：实现 commit `88733170c51eb42f95f6e758e14c83122bccff05`；PR [#1654](https://github.com/Stevenjxie/cretas/pull/1654)；exact main `91040bc57b01bccd12ed9183d72bc55b720acef5`。
- **部署**：`NOT_DEPLOYED`。
- **安全**：生产业务写入 0；未修改计划、报工、库存或 LIUSHANMEN 数据。
