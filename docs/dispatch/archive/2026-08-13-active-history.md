# Dispatch 归档 — 2026-08-13

## `AUDIT-CRLF-DIFFCHECK-20260813` — `review`

- Owner: `/root`
- Base SHA: `48b03e7f32f9a68c46b17508179631ac26955aa5`
- Scope: `.gitattributes`、`docs/dispatch/ACTIVE.md`、本归档。
- 结论：`FormAssistantController.java` 与 `ProductTypeController.java` 的 tracked blob 仍保留 CRLF；默认当日全范围 `git diff --check` 因 CR-at-EOL 产生假阳性，而 `core.whitespace=trailing-space,cr-at-eol` 复核为 0。新增两个精确 whitespace 属性，仍严格检查真实尾随空格。
- 删除条件：在独立、无行为改动的提交中把这两个完整文件统一为 LF，且移除属性后同一范围默认 `git diff --check` 仍通过。
- 验证：`git check-attr whitespace` 两个路径均为 `trailing-space,cr-at-eol`；`git diff --check 4e7eb9c4490c9286a2170a578d24494545789358` 通过。
- 边界：未修改 Java 运行代码、API、数据库、生产数据、部署或 LIUSHANMEN。
