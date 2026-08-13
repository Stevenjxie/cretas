# Dispatch 归档 — 2026-08-14

## `AUDIT-CRLF-DIFFCHECK-20260814` — `review`

- Owner: `/root`
- Base SHA: `b04774325a882cbd5135fea01768897e68363e0e`
- Scope: `.gitattributes`、`docs/dispatch/ACTIVE.md`、本归档。
- 结论：`MaterialBatchController.java`、`MaterialBatchServiceImpl.java` 与 RN `types/navigation.ts` 的 tracked blob 仍保留 CRLF；默认当日全范围 `git diff --check` 因 CR-at-EOL 产生假阳性。新增三个精确 whitespace 属性，仍严格检查真实尾随空格，未修改运行时代码。
- 删除条件：在独立、无行为改动的提交中把这三个完整文件统一为 LF，且移除属性后同一范围默认 `git diff --check` 仍通过。
- 验证：三个路径的 `git check-attr whitespace` 均为 `trailing-space,cr-at-eol`；`git diff --check 488851111400949fac3f8005b7685dfffd9b82b3..HEAD` 与 tracked encoding hook 通过。
- 边界：未修改 Java/RN 运行代码、API、数据库、生产数据、部署或 LIUSHANMEN。
