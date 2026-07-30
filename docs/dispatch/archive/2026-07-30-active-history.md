# Dispatch 完成记录 — 2026-07-30

## `DOC-DUAL-LINE-SOP-SYNC-20260730`

- 状态：`merged`
- Owner：`/root`
- Base SHA：`45906506c1fe9c0c91b580788e4c7d90158810a5`
- 检查区间：`d7a4ac3a2ed9b18bce103bb0bd825bb1b92429c5..45906506c1fe9c0c91b580788e4c7d90158810a5`（52 个 commit）
- 功能 PR：[PR #2000](https://github.com/Stevenjxie/cretas/pull/2000)，squash merge `aa2b68f1e539539aa8e2a7b1eeff13730666476a`
- 工厂：AI Assist、在线 SOP、F006 canonical KB 与确定性回答合同已同步报工单位/跨单位成品率、标签三层人工复核、受控调拨自审批；真实回答 4/4，只引用 `f006-production-full-chain-sop.md`。
- 餐饮：AI Assist、在线 SOP、full-chain/product 两个 changed source 与确定性回答合同已同步 5 节月报、计划化预警、客如云 connector、Gold 时效和 mock 边界；真实回答 5/5，只引用餐饮注册 source。`restaurant-metrics-glossary.html` 无语义变化，严格 no-op。
- 验证：`py_compile`、目标 pytest 39/39、四份 HTML 内联 JS syntax、`git diff --check`、release-preflight 全部通过；production migration dry-run 后仅应用关联的 `V20261101_02__pos_stats_target_for_tenant_skew.sql`。
- 生产：clean exact main 发布 Python，8083/PostgreSQL/Embedding 健康；三页仓库、服务器与公网 SHA-256 一致。RAG 正式块为 F006 60、restaurant full 71、restaurant product 235、metrics glossary 169，`.NEW=0`。
- 页面 SHA-256：AI Assist `8e9dd2c0454e05c5d4e8e239a3893018c9381b60ea7ab90bf2a91af6acbdfda7`；工厂 SOP `3df50fb1aee3950e334be749a8ae684fac4ab2e4051cb869c1c1aa756b4c6296`；餐饮 SOP `99d3ade40da4441cd365169897ab31d050d3eb43e21799145c1d3d7dd518f7d4`。
- 回滚点：139 上三页 `.bak-sop-20260730T0932-aa2b68f1e`；47 上餐饮 full/product source `.bak-sop-20260730T0935-aa2b68f1e`。
- 生产 ERP 业务写入：0。
- Scope 锁：已释放。

## `AUDIT-PROCESS-SHEET-DATE-COMPAT-20260730`

- 状态：`review`（候选完成，待每日集成 PR）
- Owner：`/root`
- Base SHA：`45906506c1fe9c0c91b580788e4c7d90158810a5`
- 当天 Process Sheet 前端拆分继续复用既有 `ProcessSheetInventoryItem` / `LocalDate` 后端契约，未发现静默后端缺口或孤儿 API。
- 保留 `YieldCardTable` 对旧/异常 datetime 日期串的截断兼容，并在最相关代码处注明删除条件；未改变运行行为。
- 验证：`npx vitest run src/views/production/components/processSheet/__tests__/YieldCardTable.spec.ts`（1 file / 6 tests）与 `git diff --check` 通过。
- `NOT_DEPLOYED`、生产零写。
