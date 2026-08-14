# Dispatch 归档 — 2026-08-14

## `AUDIT-CRLF-DIFFCHECK-20260814` — `review`

- Owner: `/root`
- Base SHA: `b04774325a882cbd5135fea01768897e68363e0e`
- Scope: `.gitattributes`、`docs/dispatch/ACTIVE.md`、本归档。
- 结论：`MaterialBatchController.java`、`MaterialBatchServiceImpl.java` 与 RN `types/navigation.ts` 的 tracked blob 仍保留 CRLF；默认当日全范围 `git diff --check` 因 CR-at-EOL 产生假阳性。新增三个精确 whitespace 属性，仍严格检查真实尾随空格，未修改运行时代码。
- 删除条件：在独立、无行为改动的提交中把这三个完整文件统一为 LF，且移除属性后同一范围默认 `git diff --check` 仍通过。
- 验证：三个路径的 `git check-attr whitespace` 均为 `trailing-space,cr-at-eol`；`git diff --check 488851111400949fac3f8005b7685dfffd9b82b3..HEAD` 与 tracked encoding hook 通过。
- 边界：未修改 Java/RN 运行代码、API、数据库、生产数据、部署或 LIUSHANMEN。

## `SOP-RAG-SYNC-20260814` — `merged`

- Owner: `/root`
- Base SHA: `6896d803570a33eb7eccd9652ab29c9383835dbe`
- 合入：PR #2611，squash commit `2c9c060715a0dee6744274b52ee1fc0c82447c50`。
- 工厂：同步 Workflow 画布作为 BOM 唯一写入口、旧 RN/AI BOM 写入口退役、旧投入删除、批次调整增减量/绝对余额与领用审计口径；`/lsmsop/`、工厂 AI Assist、`f006-production-full-chain-sop.md` 和确定性回答合同一致。
- 餐饮：同步实收营收、成本卡覆盖毛利、单菜标价/整单折扣边界、理论估算、异常卡和补卡覆盖率口径；`/cysop/`、餐饮 AI Assist 与三个已注册餐饮 source 一致，`MANUAL_SOURCES` 未改。
- 验证：SOP 合同 pytest 99/99，餐饮目标 pytest 69/69，`py_compile`、内联 JavaScript 语法与 `git diff --check` 通过；PR 六项 CI 全绿。
- 生产：从 clean exact `origin/main` 通过 release preflight；生产 migration dry-run 为 139 skipped / 0 pending；Python 发布与 8083 健康通过。三张静态页经备份、传输哈希和原子替换后与仓库 SHA-256 一致。
- RAG：只原子重建四个 changed canonical source；工厂 71 块，餐饮全链路 85 块、产品手册 244 块、指标字典 176 块，`.NEW=0`，source 无跨线污染。
- 回答验收：工厂固定 BOM/Workflow 顺序及画布/批次调整共 5 个真实问法通过；餐饮单菜毛利 3 个等价问法与导览助手边界通过，引用均限制在本业务线 registered source。
- 边界：生产 ERP 业务写入 0；未修改或覆盖任何 `.env`；发布备份时间点 `20260814_094145`，保留 worktree 与分支。
