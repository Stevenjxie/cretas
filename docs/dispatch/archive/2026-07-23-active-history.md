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

## UX-F006-PROD-PLAN-ACTION-IA-002 — `merged`

- **Base SHA**：`16c4edfc3dbfc7a85167f1c754fd1f64e848f5d4`
- **Owner**：`/root`
- **范围**：生产计划列表行操作、统一档案与核算抽屉、目标测试与 F006 复盘。
- **结果**：计划编号承担详情入口；逐道录入/继续录入与核对结单/生产小结直接可见；生产单据、计划追溯、汇总和完工核算收敛至“档案与核算”；异常和低频写操作归入“更多”，完成态保持只读。
- **验证**：Vitest 7/7；`vue-tsc -b --pretty false`；Vite production `dist/index.html` 与 `assets/list-HhM0R-vi.js` 目标文案读回；`git diff --check`。
- **实现/合入**：实现 commit `6c162973ad06aca435fe5e6592dc70203b139d8f`；PR [#1662](https://github.com/Stevenjxie/cretas/pull/1662)；exact main `ddfe3ab226f0cd2bbb154ef6355454b8a4decdd6`。
- **部署**：`NOT_DEPLOYED`。
- **安全**：生产业务写入 0；未修改后端 API、计划、批次、报工、结算、库存或 LIUSHANMEN 数据。
