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
