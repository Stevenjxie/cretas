# Dispatch 台账 — ACTIVE

**最后更新**：2026-07-20

**当前真值**：`origin/main` 为代码与合并真值；本文件为当前任务状态真值。完成记录见 [2026-07-20-active-history.md](archive/2026-07-20-active-history.md)，此前历史见 [2026-07-19-active-history.md](archive/2026-07-19-active-history.md)。

## 在飞任务

- `SEC-CREDENTIAL-ROTATION-20260719` — `in-progress` — Owner: Codex coordinator — Base SHA: `1ba9a241a77144a80851051efbac584abf4db69d` — 清除 tracked/服务器配置中的硬编码凭证，建立 secret regression gate，按消费者依赖顺序轮换已暴露的 Aliyun/API、数据库、JWT 与内部服务凭证，并完成生产重启、认证/Agent/零业务写入验收及历史暴露评估。
- `BOM-UNIT-DISPLAY-20260720` — `review` — Owner: Codex coordinator — Base SHA: `1f94a0c4772ab54e33649fc6310f7aab8072f11a` — 修复 BOM 原料/辅料/包材数量、单位及自动单价中的 canonical unit 本地化显示，保留 API/持久化 `box/case/slice`，完成目标测试、合入 main、Web 生产部署与 F006 原记录续测。

## Scope 锁地图

- `SEC-CREDENTIAL-ROTATION-20260719`：`scripts/systemd/` 中遗留明文启动脚本、现有/新增 secret 扫描配置与测试、`.gitignore` / 凭证模板、`docs/dispatch/ACTIVE.md`、`docs/dispatch/archive/2026-07-19-active-history.md`；外部状态仅限已授权的 Cretas 47/139 服务器配置、PostgreSQL 角色密码、相关阿里云/API 凭证与必要服务重启。验收：tracked tree 与完整 Git 历史脱敏盘点、scanner gate、exact-main 发布门禁、Java/Python/网关健康、登录与 Restaurant Agent 只读 smoke、核心 ERP 零写入。
- `BOM-UNIT-DISPLAY-20260720`：`web-admin/src/views/production/bom/**`、对应 Web 单元测试、`docs/dispatch/ACTIVE.md` 与 `docs/dispatch/archive/2026-07-20-active-history.md`；验收：`g/kg` 保持原显示，`box/case/slice` 显示为盒/箱/片，quantity/unit/自动单价及同页详情一致，目标测试、唯一 Web release manifest、生产四方哈希与 F006 Playwright 原记录复测通过。

## 阻塞项

- 无。

## 维护规则

- 只记录当前在飞、阻塞和下一动作；已完成事项移入带日期的 `archive/`。
- 创建 worktree、派发或合并前，先核对本表与 `git worktree list`。
- 本文件只有当前协调者可修改；子代理只能按 `AGENTS.md` 的结构化回执格式报告状态。
- 状态只允许：`queued`、`claimed`、`in-progress`、`review`、`merged`、`blocked`。
- 合并完成时必须同时归档任务、释放 scope 锁，不能留下已经结束的在飞行。
- 同一仓库默认 WIP 上限：2 个代码执行任务 + 1 个只读/测试任务。
