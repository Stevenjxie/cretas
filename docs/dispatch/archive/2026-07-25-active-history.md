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
