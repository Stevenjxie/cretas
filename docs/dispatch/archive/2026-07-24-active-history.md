# 2026-07-24 调度归档

## `AUTO-SOP-RAG-SYNC-20260724` — `merged`

- Owner: `/root`
- Base SHA: `19bed09f8f78474c78ef84164bac44621535d458`
- 合入：PR #1732 / `cfe2b63cd7688da2e3581081eb16e86e3674ff1e` 更新 SOP、RAG 来源和系统回答约束；PR #1733 / `99f1d2301a894b308f0daf54038d25ff231016c4` 将关键 BOM/Workflow 发布门禁改为保留 RAG 来源的代码级确定性答案。
- 发布：从 clean exact `origin/main` 运行 `scripts/deploy/deploy-smartbi-python.sh --env prod`；生产 migration `0 pending / 120 already applied`，依赖缓存命中，import smoke 和 `8083/health` 通过。
- 验收：`python -m pytest tests/test_food_kb_manual_chat_sop_contract.py -q` 为 `10 passed`；3 种生产真实问法均为 HTTP 200、命中完整 `Workflow 草稿 → BOM 激活 → Workflow 发布启用` 顺序、ACTIVE BOM 门禁与双状态验收，禁词计数 0，每次返回 8 个当前 SOP 来源。
- 页面与知识库：`/aiassist.html` SHA-256 为 `a7000b8dd425b355b0b2dafb4775e6cce9bc9c066b9d4fa1bd5058053b837524`；`/lsmsop/` SHA-256 为 `9d9914aab5a76c07413a8d4e140f4e860c9ce7f4632a835d086e37bb7bf501b8`；RAG 为 36 个正式块且无 `.NEW` 临时块。
- 边界：生产业务写入为 0。测试 Python 未重启，原因是服务器缺少 `/www/wwwroot/cretas/.env.test`；测试库已由标准 runner 应用 34 个历史待执行 migration，需另项治理测试环境配置漂移。
- Scope 锁已释放。

## `PERF-RELEASE-LATENCY-20260724` — `review`

- Owner: `/root`
- Base SHA: `f61e94599bc62df0ca826940a24e1ea1ba4287fd`
- 结果：统一候选 build 支持显式 `--stage-backend YES-STAGE`，在合并前把已验证 Java JAR 上传到不可变 SHA-256 缓存，但不安装、不重启、不切流；Web release archive 按精确 `web-admin` Git tree 保留并可 A/B/A 恢复，避免并行候选覆盖 `current` 后重复 `npm ci/build`。
- 稳定性：昂贵 fallback 前增加短 exact-main freshness guard，制品校验/回退后、任何 child deploy 前再次 fetch 并确认 `HEAD == origin/main` 与 clean worktree；主线漂移时 child deploy 调用数为 0。
- 流程：AGENTS 与 deploy skill 要求预期合并后部署时前置唯一构建/Java 预热，生产发布窗口不再合入无关 PR；统一成功回执已含服务级验证时不重复 raw SSH，只补任务特有断言。JPA/Flyway、蓝绿 5×6 秒观察、Web 四方哈希与自动回滚门禁均保留。
- 验证：`test-release-web-manifest.sh` 通过（A/B/A tree cache）；`test-release-cretas.sh` 26 个场景通过；`test-web-admin-deploy-acceleration.sh` 已按 tree cache 语义更新 fixture 并通过；`test-release-pipeline-acceleration.sh`、`test-release-preflight.sh`、`test-deploy-web-admin-preflight.sh`、全部修改脚本 `bash -n` 与 `git diff --check` 通过。
- 边界：只创建 PR，`NOT_DEPLOYED`；没有执行生产制品 staging、服务重启、切流或业务数据写入。
- Scope 锁已释放。

## `FEATURE-WORKFLOW-BOM-AUTO-BINDING-DAG-001` — `merged`

- Owner: `/root`
- Base SHA: `19bed09f8f78474c78ef84164bac44621535d458`
- 结果：BOM 首次创建自动固定唯一完整 Workflow DRAFT revision；DAG 按目标终端精确回溯；BOM-only 克隆保持历史 snapshot；多投入、多产出、稳定替代料槽、共享/部分共享/产出专属成本和副产品 NRV 抵扣形成一致模型。
- 风险关闭：三产出以上的部分共享使用稳定终端 nodeId 集合与 `cost_scope_key` 精确归属；BY_PRODUCT 分摊比例固定为 0，净值仅抵扣实际共享路径，缺价、负成本、无 MAIN、比例或单位不完整均失败关闭。
- UI：普通用户不再选择 Workflow revision；工艺来源只读，DAG 工序锁定；新增桌面表格与移动卡片式产出成本配置，价格权限不足时 NRV 字段脱敏。
- 验证：后端单一 release lifecycle 11 suites / 91 tests 全通过，包含真实 JPA Context；Web 目标测试 3 files / 24 tests、`vue-tsc` 与可信 Web build 通过；桌面/移动本地 Playwright 无横向溢出且 console/page errors 为 0；secret gate 与 `git diff --check` 通过。
- 实现：commits `a0ac271f8eac53d2911b1bfad8a10074522f7b8e`、`fa4d3f3fa640d3b819cbec4dbf87da8e58c114bd`；PR [#1731](https://github.com/Stevenjxie/cretas/pull/1731)。
- 发布：用户已明确授权合入后从 clean exact `origin/main` 统一部署 Java 与 Web；最终 release receipt 记录 exact main、真实 upstream、健康轮次及 Web 四方哈希。
- 安全：部署前未写入生产业务数据；发布仅执行 schema migration、服务切流和 Web 原子替换，不执行 F006 或其他租户业务 mutation。
- Scope 锁已释放。

## `FIX-F006-PRODUCTION-INTEGRITY-20260724` — `review`

- Owner: `/root`
- Base SHA: `14fcf69a231948fd6345d8e40847c4ec8108ae16`
- 结果：生产计划创建增加客户端幂等键与重复创建防线；取消/停产状态机收紧并记录审计字段；逐工序调料和成品包装包材按固定 BOM/Workflow 自动分配、扣减并进入实际成本；生产批次列表及逐工序报工 UI 重排。
- 数据库：新增生产计划幂等键、取消审计字段，并将调料/包材库存及消耗数量精度无损扩展为 `NUMERIC(18,6)`；真实 `ProductionPlanRepositoryQueryValidationTest` JPA Context 门禁通过。
- 验证：单次 `mvn clean package` 生命周期执行 19 个目标测试类、188 tests，0 failures / 0 errors / 0 skipped；Web 6 files / 40 tests、`vue-tsc -b`、RN 17 tests、Web release build、`git diff --check` 均通过。
- 制品：候选 JAR SHA-256 `969246e3fe4d603190ef1d08764acd2244ec15935bd54c6b40ca3434f3727432` 已只读校验并暂存至不可变 release cache；暂存未安装、未重启、未切流。
- 发布边界：等待 PR #1730 合并后，从 clean exact `origin/main` 统一部署 Java/Web；部署只执行 schema migration、蓝绿切流与 Web 原子替换，不执行 F006 或其他租户业务写入。
- Scope 锁已释放。
