# Dispatch 归档 — 2026-08-13

## `LLM-AISTORE-ROUTER-20260813-001` — `review`

- Owner: `/root`
- Base SHA: `7d041d6d6c247acc2c781eb600543e321f60898e`
- PR / commit: #2598，`9f9a90e57ff1459293cf28563b2c1c482a2289b1`
- 结论：上海电信 AI Store `DeepSeek-V4-Flash-A` 成为餐饮 CHAT/INSIGHTS/CHART/MAPPER/REVIEW 主链，腾讯 DeepSeek 保持跨供应商后备；`Qwen3-32B` 隔离在 `simple_text`，`Qwen3-235B-A22B` 因 7 道枚举题出现 2 处越界，仅进入 CHART JSON 后备。三模型均显式 allowlist，并在 2026-09-13 硬停止；2xx error body 与无正文流式响应均 fail closed 并继续 fallback。
- 验证：AI Store 项目真实 T3 契约 DeepSeek A 6/6（p50 2.97s）、Qwen 235B 6/6（p50 3.51s）；枚举合法性 DeepSeek 0/7、Qwen 2/7；router 109/109，餐饮/语义/洞察/图表相关 2041/2041（另 2 xfail）；compileall、Ruff、diff check、tracked secret scan 通过；PR 首轮两条 Python Gate 与 secret gate 全通过。
- 凭证与生产：API key 未进入 tracked 文件、Git、CI、测试输出或发布回执；本归档提交时尚未合并或部署，后续仅从 clean exact `origin/main` 注入生产受控环境变量并发布 Python；生产 ERP 业务写入为 0。

## `UI-LIUSHANMEN-REVIEW-OVERLAY-20260813` — `review`

- Owner: `/root`
- Base SHA: `9dd0bb89e4431f5fa00cbe397b392f74a5ea7d69`
- PR / commit: #2572，`33d648c7aef463736cb7235a9c3184db6664f014`
- 结论：Label QC AI 初筛参考层已在照片中常显“托盘 / 白标 / 彩标”框及左上角类别标签；三层默认显示且可独立隐藏，未知 label type 不再误画成彩标，模型结果继续只作人工审核 proposal。
- 验证：`npm.cmd run build:check` 通过；本地模拟审核 Playwright 1/1 通过，覆盖三层框、常显文字、托盘图层开关、审核提交与回读；PR 首轮 tracked-secret-scan、两条 Web vitest 和 web-dist 全部通过；用户已确认视觉方案。
- 边界：未修改 ROI、模型、队列、原图、生产数据或部署配置；严格 `NOT_DEPLOYED`。

## `AUDIT-CRLF-DIFFCHECK-20260813` — `review`

- Owner: `/root`
- Base SHA: `48b03e7f32f9a68c46b17508179631ac26955aa5`
- Scope: `.gitattributes`、`docs/dispatch/ACTIVE.md`、本归档。
- 结论：`FormAssistantController.java` 与 `ProductTypeController.java` 的 tracked blob 仍保留 CRLF；默认当日全范围 `git diff --check` 因 CR-at-EOL 产生假阳性，而 `core.whitespace=trailing-space,cr-at-eol` 复核为 0。新增两个精确 whitespace 属性，仍严格检查真实尾随空格。
- 删除条件：在独立、无行为改动的提交中把这两个完整文件统一为 LF，且移除属性后同一范围默认 `git diff --check` 仍通过。
- 验证：`git check-attr whitespace` 两个路径均为 `trailing-space,cr-at-eol`；`git diff --check 4e7eb9c4490c9286a2170a578d24494545789358` 通过。
- 边界：未修改 Java 运行代码、API、数据库、生产数据、部署或 LIUSHANMEN。

## `SOP-RAG-SYNC-20260813` — `merged`

- Owner: `/root`
- Base SHA: `488851111400949fac3f8005b7685dfffd9b82b3`
- PR / main: #2570，`fe505d4eddb26a502d356c602a83ed6501d1f39e`
- 工厂：AI Assist、F006 在线 SOP、canonical KB 与确定性回答已同步 Workflow 维护、物料销售/发货、单位/小数和表单助手真实成功边界；生产问答 4/4 仅引用 `f006-production-full-chain-sop.md`。
- 餐饮：AI Assist、在线 SOP 与三个注册 source 已同步默认时间、实测/估算出处、补数据提示和打烊经营摘要；生产问答 3/3 仅引用餐饮注册 source，单菜毛利固定红线通过。
- 验证：目标 pytest 96/96、Python compile、HTML 解析、CI Python/Web/secret scan 全通过；三页线上 SHA 与 exact main 一致；RAG 正式块 71 / 84 / 244 / 175，`.NEW=0`；Python、Embedding、PostgreSQL 健康。
- 生产：Python 从 clean exact main 发布；三页和三份餐饮 docs 均先备份、校验 SHA 后原子替换；四个 changed canonical source 原子重建。生产 ERP 业务写入为 0。
