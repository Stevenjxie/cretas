# 2026-07-31 每日集成审计：缺料错误契约债务

- 任务：`DEBT-PR-AUDIT-STOCK-SHORTAGE-CONTRACT-20260731`
- 状态：`review`
- Base SHA：`ce0ce86afd343d559edb385e9c93a045b63ee955`
- 分支：`codex/pr-audit-stock-shortage-contract-20260731`
- 范围：Web `ApiError`、请求拦截器、生产报工缺料展示与目标测试；未修改 Java/Python、JPA、生产数据或部署状态。
- 结论：后端原本已在失败响应的 `data` 中提供结构化缺料 DTO，但 Web 请求拦截器创建 `ApiError` 时丢弃该字段，导致前端依赖中文错误文案正则。现已保留结构化 payload，并由缺料 UI 直接消费 DTO；文案解析已删除。
- 保留项：旧网关若不传 `data`，UI 仅展示后端原文、不猜物料或数量。删除条件已写在 `processStockShortage.ts`：所有受支持网关均确认保留非 2xx `ApiResponse.data` 后可移除该原文兜底。
- 验证：
  - `npx vitest run src/api/__tests__/request.workflowConflict.spec.ts src/views/production/components/processSheet/__tests__/processStockShortage.spec.ts src/views/production/components/processSheet/__tests__/ProcessDataTable.autoAllocation.spec.ts` — 3 文件、11 项通过。
  - `npx vue-tsc -b --force` — 通过。
  - `npm run build` — 通过，Vite 4530 modules。
  - `git diff --check` — 通过。
- 依赖审计：`npm ci --ignore-scripts` 报告既有 18 项 advisory（3 moderate、14 high、1 critical）；本任务未修改依赖或 lockfile。
- 发布边界：仅进入 daily integration review 候选；未合并、未部署、生产业务写入为 0。
