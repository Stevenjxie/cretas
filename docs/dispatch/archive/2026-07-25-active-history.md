# 2026-07-25 调度归档

## `BUG-RESTAURANT-CONTEXT-20-TURN-TIME-003` — `merged`

- Owner: `/root`
- Base SHA: `50b460e4ed643167c2ad96660e3b4983af358086`
- 合入：PR #1754 squash 合并为 `4ededd69dbf93379ee99f31a2008c3ef65d28bd3`。
- 上下文：Java Redis、Python 会话表与 Web 回退链统一保留最近 20 轮；Web 显示当前菜品/门店、时间与指标，并允许“清除上下文”真正开启新 session。
- 时间门禁：需要时间窗口的餐饮分析若当前句和可信会话均无时间，不再暗补 30 天，而是追问并提供“本月 / 上个月 / 最近7天 / 最近30天”按钮；点击后通过同一 session 接回原问题。
- 路由收口：点名菜品营收、换菜品、销量怎么提升、下一步先做什么均继承可信对象/时间/指标；餐饮分析延续句不会被通用老板动作路由抢走。
- 排行边界：延续既有主菜排行规则，米饭、餐巾纸、湿纸巾、餐具及包装等附属/基础项从泛销量排行排除；点名查询仍可回答。
- 验证：Python 餐饮链 506 项、会话链 23 项通过（本地 6 项真实 PostgreSQL fixture 按既定条件排除）；Java 目标测试通过；Web AIQuery 32 项与 `vue-tsc -b` 通过；`git diff --check` 通过。
- 发布：当前会话继续从 clean exact `origin/main` 发布 Java/Python/Web，并在生产 Demo“咨询”模式执行只读验收；业务写入必须为 0。
- Scope 锁已释放。

## `BUG-RESTAURANT-TIME-BUTTON-CONTEXT-004` — `merged`

- Owner: `/root`
- Base SHA: `72ec252304706aab41b1be03b007eaa94a3037ec`
- 合入：PR #1756 squash 合并为 `d88156c875136cd9794a0decf185f7a0ae6a8907`。
- 生产复现：新版页面正确追问并显示“本月 / 上个月 / 最近7天 / 最近30天”，但点击“本月”后 resolver 只收到孤立时间词，原问“哪个菜卖得好”丢失，错误返回菜品毛利分析。
- 修复：QueryPlan 新增并封装 `resolver_query_seed`；clarification continuation 将“原问题 + 按钮答案”作为 resolver 唯一语义种子，同时保留实体、指标、动作和时间，且时间补充不重复追加。
- 回归：220 项餐饮 intent/clarification/service 目标测试通过；餐饮全量 796 项通过，另有 1 项与本任务无关的既有 owner-action mock 未接收 `live_overrides` 参数；Ruff、`compileall`、`git diff --check` 通过。
- 发布：当前会话继续发布 Python，并在生产 Demo“咨询”模式验收“四按钮→本月菜品销量排行”及后续上下文；业务写入必须为 0。
- Scope 锁已释放。

## `ARCH-RESTAURANT-AUTO-DIMENSION-001` — `merged`

- Owner: `/root`
- Base SHA: `9925cdad2d76310cc9a3c1fd667f5429109d360e`
- 合入：PR #1758；餐饮决策分析补齐 21 维数据可用性契约、自动扩维、缺失维度提示、主菜销量排行过滤、供应商稳定数据与异常区分，以及外部信号的 `REAL` / `PROXY` / `SIMULATED` / `MISSING` 明示。
- Demo 数据：`V20261029_01__demo_rest_comprehensive_dimensions.sql` 为 `DEMO_REST` 提供 2025-07-01 至 2026-07-31 连续种子；迁移在 PostgreSQL 16 中连续执行两遍保持幂等，真实租户哨兵未变化。
- Gold 集成测试：旧测试不再误连默认 `localhost:5432` 空密码数据库；新增一次性 PostgreSQL 16 runner，显式 loopback/test-DB 门禁下 `15 passed`，普通无 DSN 测试明确 `15 skipped`。
- 验证：餐饮目标 Pytest `413 passed`，Gold 一次性数据库 `15 passed`，`compileall`、增量 Ruff、`git diff --check` 通过。
- 发布边界：用户已明确授权合并后从 clean exact `origin/main` 发布 Python SmartBI；发布仅允许迁移、代码同步和服务重启，生产业务写入必须为 0。
- Scope 锁已释放。

## `OPS-PYTHON311-RUNTIME-UPGRADE-001` — `merged`

- Owner: `/root`
- Base SHA: `5b2fa05ec2e4e6937cfd45e58ed3263b52bb3895`
- 合入：PR #1760；实现提交 `4a7e0aa2788d87a27832cdc3105f07e9eab98e44`。
- 运行时：生产 Python 从 3.8 旁路升级为官方仓库 Python 3.11；PyTorch 固定为官方 CPU wheel，主服务、Gold ETL、语料刷新及餐饮 cron 统一使用原子 `venv-current` 选择器，旧 `venv38` 保留为即时回滚目标。
- 验证：部署脚本语法、依赖缓存/迁移目标测试通过；服务器侧 `venv311` 的 `pip check`、112 路由主应用导入、Jieba/分类器/Food NER 导入、Torch CPU 运算及 Linux 原子链接拒绝越界测试通过。
- 发布边界：合并与生产切换是独立事实；生产只允许从 clean exact `origin/main` 执行发布，必须验证 8083/公共网关、`NRestarts=0`、所有运行时消费者和回滚目标，生产业务写入为 0。
- Scope 锁已释放。

## `BUG-PYTHON311-FASTAPI-COMPAT-002` — `merged`

- Owner: `/root`
- Base SHA: `89c27a5e1355f6b1d947de87e1c7e00daa7d9f18`
- 合入：PR #1761；实现提交 `b1f0dd909`。
- 生产处置：首次 Python 3.11 切换后，主 health 为 200，但 classifier 路由因 FastAPI 0.140.0 / Starlette 1.3.1 与 Prometheus instrumentator 5.11.2 不兼容返回 500；运行时已立即原子回滚至 `venv38`，同一路由恢复 200，`NRestarts=0`。
- 修复：锁定线上已验证的 FastAPI 0.124.4 / Starlette 0.44.0，并将 `/api/classifier/health` 的完整中间件请求加入部署前 fail-closed 冒烟。
- 验证：旁路 Python 3.11 环境 `pip check` 通过；classifier 路由返回 200、模型加载 259 个标签；部署测试可模拟该类中间件回归并证明发布在重启前终止。
- 发布边界：合并后仅从 clean exact `origin/main` 再次发布；必须同时通过主 health、classifier 200、稳定观察、所有 runtime 消费者和回滚目标验证，生产业务写入为 0。
- Scope 锁已释放。
