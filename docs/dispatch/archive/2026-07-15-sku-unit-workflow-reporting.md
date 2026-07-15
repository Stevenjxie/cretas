# SKU-UNIT-WORKFLOW-REPORTING-20260715

- 状态：`merged`
- 主实现提交：`43a1e4248`、`fd7fbeea6`
- 编辑详情修复：`39f6036dc`
- 主线合并：PR #1367（merge `281d94b7b`）、PR #1368（merge `fb0f1d503`）
- 后端发布：`v20260715_145534`，green/10020 active，blue/10010 inactive
- Web 发布：2026-07-15 15:31:56 原子交换，生产入口 HTTP 200
- 实现：SKU 多包装规格与规范规格生成；SKU/Workflow/报工单位继承；生产库 FEFO 投料分摊与库存不足草稿；成品实际生产、留样、入库守恒；撤销释放分摊；半成品全历史加权出成率只读统计；计划与 Workflow 单位/克重快照。
- F006 数据：`CPSHHKH0142` 规范为 `200g/盒 50盒/箱 10kg/箱`；`PTEGGBOOM0132` 规范为 `200g/袋 10袋/箱 2kg/箱`。迁移前确认无批次、计划、运行端口及销售明细引用。另与 `产品信息_20260701140323.xlsx` 交叉后，通过官方 API 补齐 19 条可唯一确定的标准克重及 3 条明确装箱换算；仍有 29 条源表本身缺重量或单位语义冲突，未猜测写入。
- 验证：后端目标测试 48 项通过；Web 核心测试 199 项及补充 6 项通过；严格 TypeScript 检查、Maven package、Vite build 通过；真实 F006 页面列表与编辑弹窗 E2E 通过，无页面错误与 HTTP 500。
- 审查：独立终审无 Critical/Important；合并提交已验证属于 `origin/main`。
- Scope 锁：已全部释放。

## 生产 E2E 跟进收尾

- 主功能 PR：#1369，merge `7681d9e0b`；旧快照显示修复 PR：#1370，merge `5b5d38e74`。
- 跟进修复：旧 `g` 报工/半成品库存/出成率统一按 `kg` 展示且单价同步换算；历史成品 `box` 显示为中文基本单位；已发布 Workflow 检测到 SKU 单位漂移时生成下一版草稿，不改写历史发布版本；Workflow 为折叠 AI 条预留空间；逐道宽表增加横向滑动提示。
- 生产发布：后端 `v20260715_193218`，MD5 `04579b4d0a27267d4f75cd204abc6d84`，blue/10010 → green/10020，切换后健康检查 5/5；Web 于 2026-07-15 19:44 原子部署，生产入口 HTTP 200，local/server/localhost/public `index.html` SHA-256 一致。
- 验证：跟进前端 34 项目标测试、完整 build-check、后端 4 项目标测试及 JAR package 通过；PR #1370 的远端 E2E PR Gate 与 Vue CI 通过。
- F006 生产只读 E2E：登录、SKU 编辑、Workflow、逐道录入、成品期初入库、销售订单全部 PASS；控制台错误、页面异常、HTTP >=400、业务写请求均为 0。截图人工检查确认 Workflow “适应画布”后完整节点可见，AI 条无覆盖，逐道宽表与单位层级清晰。
- 证据目录：`C:\Users\Steve\cretas-e2e-evidence-20260715\post-fix`。
- Scope 锁：`MASTER-SALES-UX-20260715`、`UNIT-RUNTIME-E2E-20260715`、`PROCESS-UI-E2E-20260715`、`POSTDEPLOY-UNIT-DISPLAY-20260715` 均已释放。

## 发布提速规则固化

- 任务：`RELEASE-EFFICIENCY-RULES-20260715`。
- PR：#1371，merge `2df1e84ef`。
- `AGENTS.md`：新增唯一发布构建、10010/10020 交替蓝绿、生产形态历史单位门禁、单次 F006 零写入验收和全量 CI 分层规则。
- `deploy-backend`：修正真实 upstream/Web 地址，改为原子脚本，禁止无来源制品使用 `SKIP_BUILD=1`，不再固定假设活动槽。
- `e2e-web-admin`：新增旧 `g/box/case`、多包装、历史/新行兼容测试，以及产品故障与测试脚本故障分离。
- `depth-first-e2e`：新增风险优先顺序和只重跑失败场景的预算规则。
- 验证：frontmatter、引用路径、关键契约、无 Superpowers 引用及 `git diff --check` 均通过；纯规则路径 CI 正确跳过业务构建。
- Scope 锁已释放。
