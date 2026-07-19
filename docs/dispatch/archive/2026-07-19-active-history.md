# Dispatch 台账归档 — 2026-07-19

## 已合并任务

### `CRETAS-REDUNDANCY-CV01-20260719`

- 状态：`merged`
- Owner：Codex (`/root`)
- Base SHA：`a8e9d2c42ec1070ec84682b36e12012c76dc3565`
- 实现 commit：`e65b658c777da95e8236c283f0d54aa7d6f2289d`
- PR：[#1466](https://github.com/Stevenjxie/cretas/pull/1466)
- `main` squash merge commit：`57300e51f22d49b97560d8f8dfee8ccfea2b8ffb`
- 范围：CV-01 增加 fail-closed Flyway 删除迁移、迁移契约测试和真实 JPA Repository 启动测试；提交 PR-01/SH-01 迁移或冻结写方案、BS-01 收敛设计，并确认 WF-01/SCH-01 保留。
- 生产只读证据：`cost_variance_configs` 为 0 行、无外部入站/出站外键、无非内部触发器、无 View 依赖；完整 SQL 预览及回滚 SQL 见 `docs/architecture/2026-07-19-redundancy-cleanup-wave-1.md`。
- 本地验收：`mvn "-Dtest=CostVarianceConfigRepositoryQueryValidationTest,CostVarianceServiceImplTest,CostVarianceServiceTest,CostVarianceConfigsRemovalMigrationContractTest" test`，32 tests，0 failures/errors，BUILD SUCCESS；真实 Hibernate/JPA Context 启动成功。
- GitHub 门禁：手动 full-audit run [29670705254](https://github.com/Stevenjxie/cretas/actions/runs/29670705254) 的 `JPA repository query startup gate` 在 exact head 上通过。Python Flake8 基线、Web/RN Node OOM 属未改动模块；Java 后续长跑在门禁通过后取消。
- 状态边界：代码已合并；未部署、未执行生产 DDL、未删除任何生产数据、未重启生产服务。
- Scope 锁：已释放。

### `CRETAS-REDUNDANCY-PR01-20260719`

- 状态：`merged`
- Owner：Codex (`/root`)
- Base SHA：`5d0fbdab88090178afe80d576cae32856a474d91`
- 实现 commit：`a45278bf7d98c7b5a4c3e934c6886264312b6397`
- PR：[#1470](https://github.com/Stevenjxie/cretas/pull/1470)
- `main` squash merge commit：`d127af6b8a8bd8b5220c164968993482de87bfd9`
- 范围：删除 PR-01 旧 `product_recipes` / `recipe_ingredients` 运行时双轨、旧 CRUD 和迁移 API、成本 fallback、重复 Entity/Repository/DTO/计算入口、孤立 Web API/路由及依赖旧页面的无消费者 E2E 脚本；增加 V79 fail-closed 删除迁移。
- 生产只读证据：旧头 2 行、旧明细 17 行，均为 `DEMO_FACTORY / DF_pt10` 测试数据；仅有明细到头的入站 FK，无其他入站 FK、View 或业务触发器；近 14 天旧 API 网关调用为 0。完整 SQL 预览、SH-01 冻结写/清数据方案和 BS-01 设计见 `docs/architecture/2026-07-19-redundancy-cleanup-wave-1.md`。
- 本地验收：后端目标集 76 tests 通过；最终真实 JPA Context + V79 migration contract 2 tests 通过；Web 旧路由专项 1 test 通过；`git diff --check` 通过。
- GitHub 门禁：手动 full-audit run [29672597201](https://github.com/Stevenjxie/cretas/actions/runs/29672597201) 的 `JPA repository query startup gate` 在 exact head 上通过。Python Flake8、RN tests 和 Web build 仍为未改动模块的基线/资源失败；Java 全量在 JPA 门禁通过、PR 合并后取消，避免继续消耗资源。
- 状态边界：代码已合并；未部署、未执行生产 DML/DDL、未删除 2+17 条生产测试数据或两张旧表、未重启生产服务。
- Scope 锁：已释放。

### `REDUNDANCY-SH01-FREEZE-20260719`

- 状态：`merged`
- Owner：Codex (`/root`)
- Base SHA：`6368314cc33c12bcf0c6705a002f5b78f1eead77`
- 实现 commit：`e965d35e33c47f14f9be9c55443f1c10e7cf287e`
- PR：[#1477](https://github.com/Stevenjxie/cretas/pull/1477)
- `main` squash merge commit：`3120b98600b683e8f7528b8e62cf050fa8806a3d`
- 范围：旧 `/shipments` 四类 mutation 固定返回 410；`ShipmentRecordService` 收敛为只读；删除 8 个旧 AI mutation Tool、descriptor、RBAC/Skill 注册和 embedding 路由；RN/Web 删除旧写消费者，保留历史 GET；正式写链统一为 sales delivery → warehouse confirm。
- 数据边界：本阶段未删除 `shipment_records` 的 64 条测试数据，也未删除表。必须先部署本冻结版本，再基于冻结后的生产快照提交独立清理 migration。
- 本地验收：后端冻结与 descriptor 9 tests 通过；既有追溯与正式发货批次/库存链 32 tests 通过；RN 合约 3 tests 和 `tsc --noEmit` 通过；Web 合约 2 tests 与 Vite 生产构建通过；`git diff --check` 通过。完整 Web `vue-tsc` 仅受未改动的 `ProductProcessWorkflowEditor.vue` 既有类型错误阻塞。
- GitHub 门禁：PR 未报告 required checks；本次未修改 Entity/Repository query，不触发 JPA Repository 查询启动门禁。
- 状态边界：代码已合并；尚未生产部署，V80 尚未在生产执行，旧表数据尚未清理。
- Scope 锁：已释放。

### `REDUNDANCY-SH01-DATA-CLEAR-20260719`

- 状态：`merged`
- Owner：Codex (`/root`)
- Base SHA：`0bd8d80cada4ff00fc1b06fd5e4f5aeabfc5f7d0`
- 实现 commit：`19281e2e2344969ee6f08efdac431a60da2513c8`
- PR：[#1479](https://github.com/Stevenjxie/cretas/pull/1479)
- `main` squash merge commit：`17bea8cf52a0171d9643fce96debf8dfb92bb84e`
- 范围：V81 在 ACCESS EXCLUSIVE 锁内核对 64/56/8 行数、整表 checksum、逐工厂分布及入站 FK=0，随后只清用户授权的 64 条旧 shipment 测试数据；保留表、Entity/Repository、旧 GET、索引、外键和触发器。
- 本地验收：`LegacyShipmentDataClearMigrationContractTest` 1 test，BUILD SUCCESS；`git diff --check` 通过。
- 生产部署：V81 已随 exact `origin/main` JAR 蓝绿发布并切换到 green/10020，5/5 稳定观察通过；`shipment_records=0`，表仍存在，V80/V81 均成功记录。
- 正式链对账：`sales_delivery_records=71`、`sales_delivery_items=45`、`sales_delivery_item_batch_allocations=1`，与清理前一致。
- Scope 锁：已释放。

### `CRETAS-F006-PROD-WRITE-EXCEPTION-20260719`

- 状态：`merged`
- Owner：Codex (`/root`)
- Base SHA：`12039cad4c04c20d68218ace7f04156575917531`
- 实现 commit：`93517eecca304ac52dba1f7f5bfe09ca16fcf837`
- PR：[#1472](https://github.com/Stevenjxie/cretas/pull/1472)
- `main` squash merge commit：`27705995e2be1ae85af6f52775f85ac0a905a1e2`
- 范围：将生产业务写入默认零写入规则改为 F006 任务级受控特例；同步 Web/Playwright E2E skill、生产只读 README 与漂移测试，同时明确其他租户继续零写入且生产只读 harness 对 F006 仍严格零写入。
- 本地验收：`node --test scripts/e2e/production-readonly/tests/unit.test.js`，13/13 通过；`git diff --check` 通过；合并后实现分支 tree 与 `origin/main` tree 等价。
- 状态边界：规则已合并并可供其他 chat 从 `origin/main` 获取；未执行任何生产业务写入、部署、迁移或服务重启。
- Scope 锁：已释放。

### `REDUNDANCY-BS01-INJECTION-CONFIG-20260719`

- 状态：`merged`
- Owner：Codex (`/root`)
- Base SHA：`0f81ca356dbbf5d14546eb684ea5e64eaa0a97b9`
- 实现 commit：`62856f73549381c6ed181505cf913089db163f28`
- PR：[#1484](https://github.com/Stevenjxie/cretas/pull/1484)
- `main` squash merge commit：`fd89aa5093a2101239ae8ebfdcfcd048abdfb868`
- 范围：把空的 mixed-purpose `bom_process_seasoning`/Entity/Repository/DTO 收敛为 injection-only 模型；熟制续锅比例唯一真值改为 `bom_seasoning_items.subsequent_pot_ratio`；删除 recipe header/process fallback；同步保存、复制、版本快照、SKU 组装、报工成本、AI Tool 和 Web 消费者；增加 fail-closed V82。
- 数据边界：V82 不含 DELETE；保留 47 条 live 调料，部署时仅将 28 条整 SKU COOKING 比例从已审查 header 值 `0.3333` 回填到 binding；旧配置表快照为 0 行，若部署前出现数据则 migration 阻断。
- 本地验收：后端目标集 100 tests 通过（含真实 JPA Context 和 V82 contract）；Web 17 tests 与 Vite production build 通过；`git diff --check` 通过。
- GitHub 门禁：manual CI run [29676762026](https://github.com/Stevenjxie/cretas/actions/runs/29676762026) 的 `JPA repository query startup gate` 在 exact head 上通过；随后取消其余长跑作业。
- 状态边界：代码已合并；生产部署、V82 执行和 Web 发布仍作为独立状态处理。
- Scope 锁：已释放。

### `CRETAS-F006-WORKFLOW-TOPOLOGY-MATRIX-20260719`

- 状态：`merged`
- Owner：Codex (`/root`)
- Base SHA：`cc210a61b4afb4f4e88338a71f37610f5467f1b6`
- 实现 commit：`b60c7e9a99c0db1ff8096cfac1e8f94faa4856a0`
- PR：[#1481](https://github.com/Stevenjxie/cretas/pull/1481)
- `main` squash merge commit：`0100906ce4b84cd3c2c2217bbed42f41cf6aac2a`
- 范围：新增任务专用 `tests/e2e-workflow-routing/f006-topology-matrix.mjs`，以 F006 受控创建并验证 1→1、1→多、多→1、多→多、可替代原料、精确匹配、最小超集、同集合歧义及无共享 Workflow 的生产计划路由矩阵；未修改生产只读 harness 或产品运行时代码。
- 生产验证：首次写入前实时证明登录 `factoryId=F006`；夹具 53/53 次 mutation 成功，10/10 个 resolver 场景通过，创建 8 个 Workflow、11 个产品和 8 个 BOM。Playwright MCP 实际触发多→1 自动固定、1→多最小超集补全、精确重叠人工选路、无共享路线阻断及 Workflow Cell 悬浮预览。
- 生产计划证据：UI 创建 F006 计划 `9aee62e4-e5bb-4510-a964-12cf9a6aba96`（`PLAN-1784437835291-6DB33FDC`），固定 Workflow `97@v1` 与 BOM `74ac6dfc-a9d7-4c14-8fdd-c43fd4ba06ea`；API、UI 与数据库回读一致，其他租户同测试前缀计数均为 0。
- 本地验收：`node --check tests/e2e-workflow-routing/f006-topology-matrix.mjs`、`git diff --check` 通过；落盘报告为 `APPLY_PASS`，断言 `factoryId=F006`、53 次 mutation、10 个 resolver、8 个 Workflow、11 个产品和 8 个 BOM 全部一致。
- GitHub 门禁：PR 显示 `MERGEABLE/CLEAN`，未报告 required checks；本次仅测试脚本与 dispatch 文档，不触发 JPA Repository 查询启动门禁。
- 状态边界：脚本和验证记录已合并；没有部署、迁移、生产代码变更或服务重启。F006 测试数据按用户要求保留，不迁移、不自动清理。
- Scope 锁：已释放。

### `CRETAS-WORKFLOW-TOPOLOGY-LABELS-20260719`

- 状态：`merged`
- Owner：Codex (`/root`)
- Base SHA：`ca31c937fb3fadc20a1a1140822d96726649558a`
- 实现 commits：`e201346fb2a00263c3bf3ee723298156aaa34494`、`8a7f07bbb72ffcb0a7882912a6db23ce1dc1b72c`
- PR：[#1483](https://github.com/Stevenjxie/cretas/pull/1483)
- `main` squash merge commit：`0d214e9f785c01992bb579b24710249fd9b9396e`
- 范围：新增只读 `logicalRootInputCount`，复用 EXACTLY_ONE 可替代原料组折叠规则，令生产计划 Workflow 候选准确区分 1→1、多→1、1→多和多→多；旧接口缺少逻辑投入数时 fail-safe 显示“投入关系待确认”，不误判为 1→1。
- 数据边界：未修改数据库枚举、Entity、Repository、迁移、Workflow 持久化或历史数据；F006 既有拓扑矩阵测试数据继续保留。
- 本地验收：Web `productionPlanWorkflowResolution.spec.ts` 11/11 通过；唯一 Maven release lifecycle 中 `WorkflowTopologyClassifierTest,ProductWorkflowUnifiedResolutionTest` 15/15 通过；`git diff --check` 通过。
- 可信制品：后端 tree `afd2cc0ad140be92863bb22ebc8a6eead54a2e50`，JAR SHA-256 `8a33bbc026d701bd2f13b630fe83a3e01117ed18778bff59c119022fd1d5a260`；Web tree `94103967c1275d2d351a898cd5c6f9d673a53e58`，archive SHA-256 `0c7a8c80b7e91cd61e489ed49bbd60b41172bb8240fdad7ad1d23eb2b93c0a53`。
- 生产部署：后端版本 `v20260719_143735`，JAR MD5 `bc5d7fcd2a5ad6338e7442eea06c460e`，由 green/10020 切换至 blue/10010，5/5 稳定观察通过；Web 原子发布后本地、服务器、网关和公网 index SHA-256 均为 `3b8a39b0e2c4aa81bbfa7968141e4522182694c62300bc6e7518f881abe51c9c`。
- F006 生产只读 UI 验收：既有多→1 Workflow `97@v1` 的解析结果为 `SINGLE_OUTPUT_PRODUCT`、`logicalRootInputCount=2`、`rootInputCount=2`、`terminalCount=1`，页面显示“多→1 · 多投入单产出”；未提交新生产计划，本次验收业务写入为 0，浏览器 console errors 为 0。
- 验收证据：`.playwright-mcp/f006-topology-label-many-to-one-prod.png`、`.playwright-mcp/f006-topology-label-many-to-one-response.json`、`.playwright-mcp/f006-topology-label-console-errors.txt`；后端结构化发布报告为 `C:\Users\Steve\.cache\cretas\deploy-reports\backend-1784443042-13706.json`。
- 并发边界：本次生产发布锁定 commit `0d214e9f785c01992bb579b24710249fd9b9396e`；发布后 `origin/main` 已因无关 PR #1484/#1485 前进至 `4946662c05b4b5cab37cd36be6c5afa82eed759f`，未把这些后续变更夹带部署。测试环境 10011 当时不健康，本任务未触碰。
- Scope 锁：已释放。

### `CRETAS-F006-PLAN-REPORTING-LINKAGE-E2E-20260719`

- 状态：`merged`
- Owner：Codex (`/root`)
- Base SHA：`9ebe0073f9346ee190f2f5a45319c350009c529a`
- 实现 commits：`d48afb3fca2666cabef74afe0b5051434c6a35e5`、`96842d1b15c79d46aeda1b3424afd367cc26db90`、`9a3e355613fba2e10f0f26ba782f94cb54eb098b`
- PR：[#1489](https://github.com/Stevenjxie/cretas/pull/1489)、[#1492](https://github.com/Stevenjxie/cretas/pull/1492)、[#1493](https://github.com/Stevenjxie/cretas/pull/1493)
- `main` squash merge commits：`c6b51523e09d8bcc76347f449a0cc86519dfec75`、`4249f60056e07de32b63f38160ee31332b8e488e`、`359f17440694328ac4ab4b8887842d6eb6a2ed10`
- 范围：修正 RAW-only→成品 Workflow 报工表单映射；V83 将联产品批次的 Workflow pin 外键与 Workflow 归属 SKU 解耦；同一计划存在多个同 pin 联产批次时复用唯一已物化运行时，仍对不同 Workflow pin 或重复运行时保持歧义阻断。
- F006 身份与数据边界：首次写入前实时证明 `f006_admin / factoryUser.factoryId=F006`；仅创建/修改 F006 任务专用 SKU、Workflow、生产计划、批次和报工数据，其他租户业务写入为 0；测试数据按用户要求保留，不迁移、不自动清理。
- 选择规则验收：`ALL_REQUIRED` 场景输入 A/B 与单产出均默认选中且禁用；`EXACTLY_ONE` 场景 A/B 互斥；`AT_LEAST_ONE` 场景可同时选择 A+B 和联产品 G+H，最小选择数约束生效。
- 正式报工验收：计划 `e5bb5007-8c5d-4dcf-b4d7-b8a57bae9b9f` 固定 Workflow `104@v2`；提交 A=2kg、B=3kg、G=2kg、H=2.5kg 返回 200，生成批次 `10585` 与 `10587`，两个批次都回读为 F006、同一计划及 `104@v2`；原料消耗只在主批次生成两条，未在联产品批次重复扣料。
- 刷新/重开验收：部署 #1493 后再次打开同一计划“逐道录入”，`workflow-config`、仓库及调料配置请求均为 200，console error 为 0；页面恢复唯一 Workflow 实例 `43`，显示同一历史行的 G=2kg、H=2.5kg，已入账数据只读。
- 本地验收：Web 目标测试 34/34；V83 schema contract 与真实 PostgreSQL 集成测试通过；#1492 唯一 Maven release lifecycle 42 tests 通过；#1493 唯一 Maven release lifecycle `WorkflowClerkSheetServiceTest,ProcessSheetControllerTest` 14/14 通过。
- 可信制品与部署：#1493 后端 tree `202c9163983e772f05bc955a6e7e837f5a9b03db`，JAR SHA-256 `07f39d0b4a7b21b6585b79ba3809c3ca82d9429be65993866deab9a070dd3dcc`；生产由 blue/10010 切换至 green/10020，5/5 稳定观察通过，结构化报告 `C:\Users\Steve\.cache\cretas\deploy-reports\backend-1784450143-16975.json`。测试环境 10011 不健康，本任务未触碰。
- 验收证据：`C:\Users\Steve\my-prototype-logistics\.playwright-mcp\f006-plan-reporting-linkage\` 下保存选择交互、正式提交、响应体、批次/消耗/运行时回读和 Workflow 激活恢复记录。
- Scope 锁：已释放。

## Cretas Agent Architecture V2 — D9/D10

### `CRETAS-AI-PYTHON-SESSION-IDENTITY-D9SP-20260719`

- 状态：`merged`
- Owner：Codex (`/root`) + 执行/审查子代理
- PR：[#1473](https://github.com/Stevenjxie/cretas/pull/1473)
- `main` squash merge commit：`6368314cc33c12bcf0c6705a002f5b78f1eead77`
- 范围：Python synthesis/chat session 使用严格 trusted positive user parser，所有 lookup/upsert/prune 精确绑定 factory+user+session；兼容旧全局 unique 和新复合 unique，跨用户同 SID 不覆盖；Web session key 含 factory+user。
- 验收：Pure/contract 78 项、真实 PostgreSQL 随机 schema 1 项、Web 2 项及独立终审通过。
- 状态边界：代码已合并；`V20261028_02__chat_session_user_identity.sql` 未在生产执行，未部署。
- Scope 锁：已释放。

### `CRETAS-AI-D10-GAP-AUDIT-20260719`

- 状态：`merged`
- Owner：Codex (`/root/d10_gap_audit`)
- Base SHA：`6368314cc33c12bcf0c6705a002f5b78f1eead77`
- 范围：只读核验 Tool/Gateway、餐饮 Runtime、AgentOps 与工厂 Capability Pack 的已完成能力和缺口；裁决先迁固定写入口与餐饮 adaptive runtime，再做 AgentOps 和 config-only 工厂 Pack。
- 结果：确认保留 601 Tool inventory、Gateway/ledger、10 个餐饮 Evidence Tool、有界 Runtime、Run/Event+RLS 和真实 SSE/replay；不把低代码画布或通用工厂 Runtime 纳入本阶段。
- 状态边界：仓库零修改、未测试、未部署。
- Scope 锁：已释放。

### `CRETAS-AI-D10A-GATEWAY-FIXED-WRITES-20260719`

- 状态：`merged`
- Owner：Codex (`/root`) + 执行/审查子代理
- PR：[#1480](https://github.com/Stevenjxie/cretas/pull/1480)
- `main` squash merge commit：`0f81ca356dbbf5d14546eb684ea5e64eaa0a97b9`
- 范围：`product_create` 与 `bom_adjust` 的 preview/execute 迁入 ToolExecutionGateway；opaque confirmation token 绑定可信 factory/user/tool/version/mode/参数摘要/expiry，execute 仅接受 header token；Web token 只驻留内存。
- 验收：Java 73/73、Web 4/4、Vite production build 与独立终审通过；Gateway direct bypass 降至 11 files / 12 expressions。
- 状态边界：代码已合并，未部署。
- Scope 锁：已释放。

### `CRETAS-AI-D10D-FACTORY-CAPABILITY-PACKS-20260719`

- 状态：`merged`
- Owner：Codex (`/root`) + 执行/审查子代理
- PR：[#1490](https://github.com/Stevenjxie/cretas/pull/1490)
- `main` squash merge commit：`7a5d785ce7af5da9d9426593d467b123e994eefe`
- 范围：新增 operator、warehouse、quality、manager 四个严格 schema、固定 SHA-256、PUBLISHED 的 config-only Capability Pack；仅允许 `READ + LOW + REVIEW_REQUIRED` 元数据，不接 Tool 执行、动态规划、Runtime 或 DAG。
- 验收：可信 factory/role/业态 fail-closed，16/16 Java 目标测试、`git diff --check` 与独立终审通过。
- 状态边界：无迁移，未部署。
- Scope 锁：已释放。

### `CRETAS-AI-D10B-RESTAURANT-ADAPTIVE-RUNTIME-20260719`

- 状态：`merged`
- Owner：Codex (`/root`) + 执行/审查子代理
- PR：[#1491](https://github.com/Stevenjxie/cretas/pull/1491)
- `main` squash merge commit：`a7951cc54600a0f400ab914ecdc2f9b568afd9ac`
- 范围：餐饮 Agent 增加最多两轮的 `EVIDENCE_GAP → REPLAN → CLARIFICATION`、跨进程显式取消、checkpoint/resume/replay、32 KiB Evidence drill-down 与严格 `READ_ONLY_PROPOSAL`；Run/Event 全链绑定可信 factory+owner。
- 验收：Python 100、真实 disposable PostgreSQL 13/13、Java 26/26、RN 21/21、TypeScript 与独立 Review3 通过。
- 状态边界：代码已合并；`V20261028_03__restaurant_agent_adaptive_events.sql` 与 `V20261028_05__restaurant_agent_owner_enforcement.sql` 未在生产执行，未部署。
- Scope 锁：已释放。

### `CRETAS-AI-D10C-AGENTOPS-EVAL-20260719`

- 状态：`merged`
- Owner：Codex (`/root`) + Python/Java/Web 执行与四轮审查子代理
- PR：[#1495](https://github.com/Stevenjxie/cretas/pull/1495)
- `main` squash merge commit：`a9bbd8c4fd547907bf26bcdc00723820c1adb4cc`
- 范围：建立租户隔离且不可变的 Eval Set、离线 Experiment、comparison、分页 detail 和管理员 Run Trace；持久化 actual snapshots、RunnerBounds、EvalSet digest、三类精确 config SHA 与 evaluator artifact SHA-256，rerun 只按源 build registry 执行。
- 幂等与隔离：Create/Run/Rerun 使用 factory+actor+requestId/requestDigest 原子幂等；response-loss retry 在 evaluator 前返回既有结果；RLS/FORCE RLS、同租户 lineage self-FK 和 immutable trigger fail closed。
- 验收：disposable PostgreSQL 27/27、Java 21/21、Web 17/17 + production build、AgentOps build:check 0 错误与 Review4 P0-P3 无发现。
- 状态边界：代码已合并；`V20261028_04__restaurant_agent_eval_experiments.sql` 未在生产执行，未部署。
- Scope 锁：已释放。

### `CRETAS-AI-D9-D10-PRODUCTION-HANDOFF-20260719`

- 状态：`merged`
- Owner：Codex (`/root`)
- Base SHA：`a9bbd8c4fd547907bf26bcdc00723820c1adb4cc`
- 范围：确认 D10A/B/C/D 代码 scope 与临时 WIP 全部释放；后续生产迁移与发布作为独立 high-stakes 任务处理。
- 迁移边界：待统一编排 Python D9 `V02`、D10 `V03/V04/V05`；Java Flyway 历史 `V76` 冲突已在 PR #1463 修为唯一 `V76`（BOM）与 `V77`（Gateway），当前主线无重复版本。
- 发布门禁：构建、DDL、上传、重启、蓝绿切流或 OTA 前，必须核验 exact `origin/main`、现场制品/slot/schema、兼容窗口、回滚点与验收清单，并取得用户确认。
- 状态边界：本记录不代表已构建、已迁移或已部署生产。
- Scope 锁：已释放。
