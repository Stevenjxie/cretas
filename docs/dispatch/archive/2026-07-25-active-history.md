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
