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
