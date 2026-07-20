# Dispatch 台账 — ACTIVE

**最后更新**：2026-07-20

**当前真值**：`origin/main` 为代码与合并真值；本文件为当前任务状态真值。完成记录见 [2026-07-20-active-history.md](archive/2026-07-20-active-history.md)，此前历史见 [2026-07-19-active-history.md](archive/2026-07-19-active-history.md)。

## 在飞任务

- `SEC-CREDENTIAL-ROTATION-20260719` — `blocked` — Owner: Codex coordinator — Base SHA: `1ba9a241a77144a80851051efbac584abf4db69d` — tracked tree、47/139 非受控副本、数据库/JWT/internal/BaoTa 轮换和生产验收已完成；仍需各供应商控制台吊销无法由当前 API 权限完成的旧长期凭证，详情见 [凭证轮换收尾记录](../security/credential-rotation-closeout-2026-07-20.md)。
- `BUG-F006-M10-SALES-DELIVERY-CLOSEOUT` — `in-progress` — Owner: root coordinator — Base SHA: `0e3c979fe0a153405daed3fab9f2b81a78b036f9` — 调拨表单、销售生产动作、发货容量/地址/金额、母子发运、审批与全局表格收尾；验收：Web 目标测试、Java 目标测试/JPA gate、唯一 Web/JAR 构建、main 合入；禁止部署。
- `BUG-F006-M11-YIELD-COST-BACKEND` — `in-progress` — Owner: m11_yield_backend — Base SHA: `0e3c979fe0a153405daed3fab9f2b81a78b036f9` — M11 混合单位出成率与 pinned BOM 包材成本后端核算；验收：相关 Yield/Cost Java 目标测试。
- `BUG-F006-M11-YIELD-COST-WEB` — `review` — Owner: m11_yield_web — Base SHA: `0e3c979fe0a153405daed3fab9f2b81a78b036f9` — M67 中文单位、订单号选择器与可审计核算明细；目标 Vitest 5/5、vue-tsc 已通过，待协调者联合后端终审。
- `BUG-F006-M12-STOCKTAKE-CLOSEOUT` — `in-progress` — Owner: root coordinator — Base SHA: `0e3c979fe0a153405daed3fab9f2b81a78b036f9` — 盘点单位/身份/快捷填充、服务器基准时点、对账范围、审批证据/CAS、自批门禁与零差异审批后仍需应用；验收：Web 目标测试、Java 目标测试与真实 JPA Context gate；禁止部署。
- `BUG-F006-PROD-PLAN-IA-SUMMARY` — `in-progress` — Owner: m11_yield_web — Base SHA: `0e3c979fe0a153405daed3fab9f2b81a78b036f9` — 生产计划操作区收拢、同一生产单据包 PDF 分页、筛选汇总同口径、实际数量单位与销售订单号入口；验收：生产计划目标 Vitest、vue-tsc，缺失/权限 fail-closed；禁止部署。
- `BUG-F006-PROD-PLAN-PDF-SUMMARY-BACKEND` — `in-progress` — Owner: prod_plan_backend — Base SHA: `0e3c979fe0a153405daed3fab9f2b81a78b036f9` — 单次 pinned 快照生成可选章节的生产单据包 PDF，以及生产计划列表/汇总共享筛选、按单位分组和单位安全完成率；验收：Java/Python 目标测试，中文字体/缺失/权限 fail-closed；禁止部署。
- `BUG-F006-M10-M12-READONLY-REVIEW` — `in-progress` — Owner: m11_yield_backend — Base SHA: `0e3c979fe0a153405daed3fab9f2b81a78b036f9` — 只读终审销售母子发运/批次容量与 M12 盘点时间/CAS/零差异两步实现，回报 P0/P1 与精确文件行；不得编辑或运行 Maven，避免与在飞构建冲突。

## Scope 锁地图

- `BUG-F006-M10-M12-QA-REVIEW-DOC` — `in-progress` — Owner: qa_review_doc — Base SHA: `0e3c979fe0a153405daed3fab9f2b81a78b036f9` — Scope: `docs/qa/F006-MVP-E2E-bug-review-2026-07-20.md` only; append all delegated M10/M11/M12/production-plan bug entries, including withdrawn zero-diff auto-complete and final one-PDF decision. Acceptance: no old entry overwritten, every item has `NOT_DEPLOYED` pending release result.
- `BUG-F006-M10-SALES-CONCURRENCY-AUDIT-FIX` — `in-progress` — Owner: sales_concurrency_fix — Base SHA: `0e3c979fe0a153405daed3fab9f2b81a78b036f9` — Scope: `SalesServiceImpl.java`, sales delivery repositories/entities and focused tests only; fixes duplicate child-line capacity, exact sales-order-item shipment posting, and cancel/ship/allocation lock ordering. Acceptance: focused Java tests plus coordinator JPA gate; `NOT_DEPLOYED`.

- `SEC-CREDENTIAL-ROTATION-20260719`：`scripts/systemd/` 中遗留明文启动脚本、现有/新增 secret 扫描配置与测试、`.gitignore` / 凭证模板、`docs/dispatch/ACTIVE.md`、`docs/dispatch/archive/2026-07-19-active-history.md`；外部状态仅限已授权的 Cretas 47/139 服务器配置、PostgreSQL 角色密码、相关阿里云/API 凭证与必要服务重启。验收：tracked tree 与完整 Git 历史脱敏盘点、scanner gate、exact-main 发布门禁、Java/Python/网关健康、登录与 Restaurant Agent 只读 smoke、核心 ERP 零写入。
- `BUG-F006-M10-SALES-DELIVERY-CLOSEOUT`：`web-admin/src/views/transfer/**`、`web-admin/src/views/sales/orders/**`（不含 M67）、`web-admin/src/style.css`、销售发货 Entity/DTO/Service/Controller/Repository/迁移与相关测试、`docs/qa/F006-MVP-E2E-bug-review-2026-07-20.md`。
- `BUG-F006-M11-YIELD-COST-BACKEND`：`backend/java/cretas-api/src/main/java/com/cretas/aims/{dto,service}/yield/**` 与对应 `src/test/**/yield/**`；不得修改 SalesService、销售发货实体/迁移、ACTIVE 或 QA 复盘。
- `BUG-F006-M11-YIELD-COST-WEB`：`web-admin/src/views/production-analytics/M67YieldCost.vue`、该页新增 helper 与目标测试；不得修改全局样式、销售订单/调拨页面、ACTIVE 或 QA 复盘。
- `BUG-F006-M12-STOCKTAKE-CLOSEOUT`：盘点 Entity/DTO/Repository/Service/Controller/迁移与真实 JPA Context/Service 测试，`web-admin/src/views/warehouse/stocktakes/**`、仓库 badge 工具与对应目标测试；不得修改 M11 核算或生产计划页面。
- `BUG-F006-PROD-PLAN-IA-SUMMARY`：`web-admin/src/views/production/plans/**`、其直接调用的生产计划 API 类型/helper/目标测试，以及仅在确有必要时新增只读生产单据包生成端点与对应测试；不得修改销售发货、盘点、M11 核算、ACTIVE 或 QA 复盘。
- `BUG-F006-PROD-PLAN-PDF-SUMMARY-BACKEND`：`backend/java/cretas-api/src/main/java/com/cretas/aims/controller/PrintController.java`、`backend/java/cretas-api/src/main/java/com/cretas/aims/service/listsummary/impl/ListSummaryServiceImpl.java`、对应 Java 目标测试；`backend/python/printing/api/print.py`、`backend/python/printing/services/pdf_renderer.py` 与对应 Python 目标测试；不得修改销售发货、盘点、M11 yield、ACTIVE 或 QA 复盘。
- `BUG-F006-M10-M12-READONLY-REVIEW`：只读检查销售发货、批次分配、盘点相关 diff 与测试覆盖；不持有文件写锁，不修改任何文件，不运行 Maven。
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
