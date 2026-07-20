# Dispatch 台账 — ACTIVE

**最后更新**：2026-07-20

**当前真值**：`origin/main` 为代码与合并真值；本文件为当前任务状态真值。完成记录见 [2026-07-20-active-history.md](archive/2026-07-20-active-history.md)，此前历史见 [2026-07-19-active-history.md](archive/2026-07-19-active-history.md)。

## 在飞任务

- `SEC-CREDENTIAL-ROTATION-20260719` — `blocked` — Owner: Codex coordinator — Base SHA: `1ba9a241a77144a80851051efbac584abf4db69d` — tracked tree、47/139 非受控副本、数据库/JWT/internal/BaoTa 轮换和生产验收已完成；仍需各供应商控制台吊销无法由当前 API 权限完成的旧长期凭证，详情见 [凭证轮换收尾记录](../security/credential-rotation-closeout-2026-07-20.md)。
- `F006-M07-BY-STOCK-SETTLEMENT-20260720` — `in-progress` — Owner: Codex coordinator — Base SHA: `8d5af3daa8f7bcfa1b96c19bd1a736fc7bb4481f` — 修复 BY_STOCK 已小结/停产计划缺少仓库确认 settlement 的桥接，兼容既有 F006 记录时仅幂等补建缺失结单元数据，不重复扣料、报工或生成 PB/FG。

## Scope 锁地图

- `SEC-CREDENTIAL-ROTATION-20260719`：`scripts/systemd/` 中遗留明文启动脚本、现有/新增 secret 扫描配置与测试、`.gitignore` / 凭证模板、`docs/dispatch/ACTIVE.md`、`docs/dispatch/archive/2026-07-19-active-history.md`；外部状态仅限已授权的 Cretas 47/139 服务器配置、PostgreSQL 角色密码、相关阿里云/API 凭证与必要服务重启。验收：tracked tree 与完整 Git 历史脱敏盘点、scanner gate、exact-main 发布门禁、Java/Python/网关健康、登录与 Restaurant Agent 只读 smoke、核心 ERP 零写入。
- `F006-M07-BY-STOCK-SETTLEMENT-20260720`：生产计划结单/小结/停产/仓库确认相关 Java controller、service、DTO、repository 与目标测试，必要时 Web 生产计划入库状态读取/展示及目标测试，`docs/dispatch/ACTIVE.md`、`docs/dispatch/archive/2026-07-20-active-history.md`。验收：BY_STOCK 小结后唯一 `PENDING_WAREHOUSE_RECEIPT` settlement；历史缺口安全幂等补建；GET 200；仓库确认不双入库；普通核对结单路径回归；现有 F006 计划验证期间除允许的缺失 settlement 元数据外零业务写入。
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
