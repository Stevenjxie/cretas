# Dispatch 台账 — ACTIVE

**最后更新**：2026-07-20

**当前真值**：`origin/main` 为代码与合并真值；本文件为当前任务状态真值。完成记录见 [2026-07-20-active-history.md](archive/2026-07-20-active-history.md)，此前历史见 [2026-07-19-active-history.md](archive/2026-07-19-active-history.md)。

## 在飞任务

- `F006-M08-FG-TRANSFER-20260720` — `in-progress` — Owner: Codex coordinator — Base SHA: `33025f28f3dbb9724e9c488f233f3eb5cefbdc06` — 修复手动仓库间调拨“成品/菜品”选择器错误复用通用物料契约的问题，覆盖成品可用库存、canonical/display unit、调拨生命周期与幂等；修复期间 F006 现有生产/库存记录只读。
- `SEC-CREDENTIAL-ROTATION-20260719` — `blocked` — Owner: Codex coordinator — Base SHA: `1ba9a241a77144a80851051efbac584abf4db69d` — tracked tree、47/139 非受控副本、数据库/JWT/internal/BaoTa 轮换和生产验收已完成；仍需各供应商控制台吊销无法由当前 API 权限完成的旧长期凭证，详情见 [凭证轮换收尾记录](../security/credential-rotation-closeout-2026-07-20.md)。

## Scope 锁地图

- `F006-M08-FG-TRANSFER-20260720`：Web 调拨单手动新建页及其 API/types/目标测试；Java 成品可调拨库存查询与调拨 DTO/service/controller/repository 及目标测试；`docs/dispatch/ACTIVE.md`、`docs/dispatch/archive/2026-07-20-active-history.md`。验收：成品选择器按调出仓库读取可用成品库存并显示盒/上限 5，原料/包材路径不回归；调拨 create/apply/approve/ship/receive/inbound 只扣转一次且同厂总量不变；exact-main 构建、发布与同一 F006 FG 只读验真。
- `SEC-CREDENTIAL-ROTATION-20260719`：`scripts/systemd/` 中遗留明文启动脚本、现有/新增 secret 扫描配置与测试、`.gitignore` / 凭证模板、`docs/dispatch/ACTIVE.md`、`docs/dispatch/archive/2026-07-19-active-history.md`；外部状态仅限已授权的 Cretas 47/139 服务器配置、PostgreSQL 角色密码、相关阿里云/API 凭证与必要服务重启。验收：tracked tree 与完整 Git 历史脱敏盘点、scanner gate、exact-main 发布门禁、Java/Python/网关健康、登录与 Restaurant Agent 只读 smoke、核心 ERP 零写入。
## 阻塞项

- Aliyun 主账号旧 AccessKey：官方 RAM API 返回 `Forbidden` 并提示泄露风险，旧 key 的 STS 仍有效；必须由阿里云控制台主账号删除。
- 旧 Model Studio/DashScope、Zhipu、DeepSeek key：生产消费者已切换或禁用旧值，但需要在各供应商控制台执行禁用/删除；当前 replacement RAM key 无法通过已安装 CLI 调用 ModelStudio key-management OpenAPI。
- Mall 微信长期凭证：已从 tracked/0644 YAML 外置到 `0600` root-only EnvironmentFile，运行态验证通过；MP secret/token/AES key、Mini App secret、商户 key 仍需在微信公众平台/商户平台协调重置后同步更新受控环境文件。

## 维护规则

- 只记录当前在飞、阻塞和下一动作；已完成事项移入带日期的 `archive/`。
- 创建 worktree、派发或合并前，先核对本表与 `git worktree list`。
- 本文件只有当前协调者可修改；子代理只能按 `AGENTS.md` 的结构化回执格式报告状态。
- 状态只允许：`queued`、`claimed`、`in-progress`、`review`、`merged`、`blocked`。
- 合并完成时必须同时归档任务、释放 scope 锁，不能留下已经结束的在飞行。
- 同一仓库默认 WIP 上限：2 个代码执行任务 + 1 个只读/测试任务。
