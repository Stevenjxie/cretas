# Claude Code 执行 Prompt：一加物流排线 MVP 后端闭环

将下面整段复制给 Claude Code：

---

你现在负责把 Cretas 的“一加物流排线”从前端高保真 Demo 完成到真正可用的后端持久化 MVP 闭环。

仓库：

`C:\Users\Steve\my-prototype-logistics`

开始前必须完整阅读：

1. `AGENTS.md`
2. `docs/dispatch/2026-07-11-logistics-mvp-closed-loop-handoff.md`
3. `tmp/meeting-20260709-logistics/transcript.md`
4. `tmp/meeting-20260709-logistics/transcript.json`（用于核对时间段）
5. `tmp/meeting-20260709-logistics/requirements-summary.md`
6. `tmp/meeting-20260709-logistics/meeting.m4a` 或 `meeting.wav`（仅在转录歧义时回听）
7. `docs/superpowers/specs/2026-07-10-logistics-demo-modular-workbench-design.md`
8. `docs/superpowers/plans/2026-07-10-logistics-demo-modular-workbench.md`
9. 当前 `web-admin/src/views/scheduling/logistics/` 全目录
10. Java 的 `Vehicle`、`ShipmentRecord`、`Customer`、Import Center、Scheduling 相关 controller/entity/service
11. `backend/python/scheduling/api/routes.py`
12. demo login、router guards、permission 和 menu 配置

必须完整阅读 `transcript.md`，不能只读需求摘要或 Handoff 摘录。会议原文、JSON 和音频都属于本地客户材料，不得加入 Git staging 或提交到 public GitHub。

重要事实：

- 当前物流模块主要是 Vue 前端 mock，不是后端闭环。
- `routeEngine.ts` 是浏览器内确定性排线逻辑。
- CSV 上传目前只校验字段，不替换订单、不写数据库。
- `MOCK_STORES`、`MOCK_VEHICLES`、`MOCK_RECORDS` 是 fixture。
- 物流 Demo 当前使用 `demo-logistics-local-token` 假 token。
- Java 有车辆、出货、客户、导入中心和生产排程底座，但没有物流计划/车次/停靠点领域。
- Java `SchedulingPlan/LineSchedule` 是生产排程，不能直接当物流线路表。
- Python scheduling 是 LinUCB 工人推荐，不是路径规划。

目标：

实现以下真实闭环，并持续执行到测试、commit、push 和 PR 完成，不要只给审计或计划：

`模板下载 → 文件预检 → 行级错误 → 确认导入写库 → 门店地图点位 → 后端生成多点线路 → 人工调整写回 → 车辆司机匹配 → 正式确认 → 调度记录回读 → 后端 CSV/Excel 导出`

核心产品目的：

- 调度员导入每日门店订单后，系统快速形成可解释的推荐线路。
- 每家门店只分配一次；超载、缺车、缺司机、缺定位和缺路线数据必须明确暴露。
- 地图、线路卡、人工调整、记录和导出使用同一个后端计划 snapshot。
- 刷新、重新登录和跨页面后数据仍存在。
- 保留现有“导入即出图、简单调整”的易用性，避免传统 TMS 的复杂操作。

执行方式：

1. `git fetch origin`，从最新 `origin/main` 创建独立 worktree 和分支 `claude/logistics-mvp-closed-loop`。
2. 不要从 `codex/restaurant-unified-ai-entry` 开分支；它混有大量其他提交。
3. 把 Handoff 视为已批准的产品/架构基线。先核对代码事实，再写正式 spec 和 implementation plan；不要重新退回纯前端或 localStorage 方案。
4. 使用 TDD：每个后端规则和前端行为先写失败测试，再实现。
5. 按 Handoff 的 Phase 1–6 分阶段完成，每个阶段独立 commit 和验证。
6. 优先复用现有 Java 分层、统一 `ApiResponse`、权限、导入中心模式、Vehicle 和 Shipment 能力。
7. 新增独立 logistics bounded context；不要污染生产 `SchedulingPlan/LineSchedule`。
8. 首期排线使用 Java 确定性 heuristic：容量、载重、区域、班次、车辆/司机匹配、稳定装箱和距离边 nearest-neighbor。不要调用 LLM，不要假装现有 Python 是路径算法。
9. 首期不要求真实地图 SDK。建立 `DistanceProvider`/geometry adapter；Demo 可迁移当前脱敏道路 fixture，真实租户缺距离边时必须 `NEEDS_ROUTE_DATA`，禁止伪造公里数或直线降级。
10. 删除物流假 token，接后端 demo-login，JWT 必须锁定 `DEMO_LOGISTICS`，并提供可恢复的 Demo seed/reset。
11. 前端保留现有四模块、四步流程、详细地图和交互；把内存 fixture/state 替换为 API-backed state。
12. 所有调整必须写回后端，返回完整最新 plan snapshot 和 version；并发冲突返回 409。
13. 导出必须由后端生成，测试中解析下载文件逐字段核对。

导入字段至少包括：

- 业务日期
- 门店编码
- 门店名称
- 配送地址
- 件数
- 箱数
- 重量 kg
- 体积 m³
- 可选配送时间窗
- 可选经纬度和区域

后端领域至少覆盖：

- order batch
- delivery order
- vehicle logistics profile
- driver
- vehicle-driver binding
- logistics plan
- trip
- stop
- distance edge / geometry source

API 前缀统一为：

`/api/mobile/{factoryId}/logistics`

至少实现：

- 模板、preview、commit、批次和订单查询
- 车辆 profile、司机和绑定维护
- generate/regenerate plan
- reorder stop、跨线路 move、换车、换司机
- trip confirm、plan confirm
- plan list/detail
- CSV/Excel export

硬约束：

- 一家门店首期不可拆到多车。
- 体积和重量不可超过车辆硬容量。
- 门店不可重复或静默漏排。
- 车辆和司机不可同时冲突。
- 固定区域和工作时段必须校验。
- 缺定位或缺路线数据不能正式确认。
- 正式确认后不能绕过状态机修改。
- 所有读写按认证 factory 隔离，不能信任 body 的 factoryId。
- 不允许 fake success、mock fallback、catch 后返回假数据。

明确非目标：

- 实时 GPS、轨迹、偏航告警
- 司机端 App
- 工资、公里计费、财务结算
- 复杂冷链车型拆分；客户首期是双温车总容量约束
- 成熟全局最优 VRP 宣称
- 未确认的付费地图 SDK

隐私和安全：

- 不提交原始客户地图截图、会议音频、转录文档。
- 不提交密码、JWT、地图 Key 或真实租户数据。
- Public Demo JWT 只能访问 `DEMO_LOGISTICS`。
- 不触碰 `LIUSHANMEN` 真实客户数据。

验收必须是真实 L4 E2E：

1. 后端签发物流 Demo 身份。
2. 上传至少 8 家门店的测试文件。
3. preview 显示有效和错误行。
4. commit 后门店来自数据库。
5. 刷新后仍存在。
6. 生成至少 2 条多点线路。
7. 调整顺序并跨线路移动门店。
8. 刷新后调整仍存在。
9. 解决缺车/司机后正式确认。
10. 调度记录页读回同一计划。
11. 下载并解析 CSV/Excel，核对车辆、司机、门店顺序、方数、重量、装载率和公里数。
12. 无 401/403/500、无错误 toast、无 console error。

必须运行并报告：

- Java scoped unit/integration tests
- Migration/repository tests
- Web-admin logistics Vitest
- Java build
- Web-admin `npm run build:check` 或项目当前等价命令
- Playwright 深度闭环 E2E
- `git diff origin/main...HEAD --stat`
- `git log --oneline origin/main..HEAD`

完成标准以 Handoff 的 Definition of Done 为准。构建通过不等于完成；按钮可点击不等于完成；前端 state 有数据不等于持久化。

Git 交付：

- 只提交物流相关 scoped diff。
- 分阶段 commit。
- push feature branch。
- 创建 ready-for-review PR，PR 正文写清根因、数据模型、API、算法边界、测试和剩余风险。
- 未经用户明确授权，不 merge、不部署生产。

如果发现现有通用能力不能安全复用，先提供代码证据，然后在物流 bounded context 内实现最小必要能力。不要因为已有类名叫 Scheduling、Vehicle 或 Import 就假设它满足物流语义。

持续执行直到 PR 和验证证据齐全。只有地图供应商凭证、客户最终模板或生产授权等真正外部阻塞才询问用户；其他设计细节按 Handoff 的推荐方案做合理决定。

---
