# Dispatch 完成归档 — 2026-07-22

## BUG-F006-R3-PURCHASE-RECEIVING-ROUTE-001

- Status: `review`; Owner: `/root`; Base SHA: `53b3f02c6261da8136ce73bc9618e8618bd61bce`.
- Scope: approved purchase order -> unified warehouse pending receipt -> constrained receipt -> existing inventory batch materialization only.
- Canonical write/read API: `/api/mobile/{factoryId}/warehouse/receiving/**`; new UI has no `/purchase/receives/**` consumer. The legacy procurement route only redirects to the unified warehouse view.
- Verification: Java release lifecycle 73/73 including real JPA Context; Web target tests 70/70 plus typecheck and immutable Web manifest.
- Deferred: customer-supplied material, sales shortage procurement, production receipt and ownership expansion until this original path passes user UI regression.
- Scope lock released by the final squash merge; production business writes during implementation: 0.

## FEATURE-F006-R3-CUSTOMER-SUPPLIED-RECEIVING-001

- Status: `merged; NOT_DEPLOYED`; Owner: `/root`; Base SHA: `66e251a1ea836774104c496834df8e45a173ef04`; implementation PR [#1588](https://github.com/Stevenjxie/cretas/pull/1588), exact implementation main `5d9b6d2f23b391b5766c2c57130b69fd8eb83c1b`.
- Scope: sales processing/material-supply contract -> structured customer-supplied requirement -> unified warehouse receipt -> customer-owned raw batch -> exact customer/order production allocation -> customer-owned finished goods and constrained delivery.
- Reuse boundary: the requirement row is the receiving task identity; no parallel receiving-task table, second receipt system, procurement/AP path, or historical backfill was introduced. The old sales-side direct receipt mutation is frozen with HTTP 410 and zero writes.
- Verification: Java immutable release lifecycle 17 classes / 131 tests, including 3 real JPA Context query gates; Web Vitest 3 files / 14 tests, `vue-tsc --noEmit`, and immutable Web release build all passed. The JPA gate caught and closed two real query-contract defects before merge.
- Acceptance handoff: Playwright was explicitly deferred to the user. Production business writes during implementation: 0; `PO-20260721-0001` and all F006/LIUSHANMEN production data remained unchanged.
- Scope locks released: `FEATURE-F006-R3-CUSTOMER-SUPPLIED-RECEIVING-001`, `CSPR-BE-SALES-RECEIPT`, `CSPR-WEB-SALES-WAREHOUSE`, `CSPR-BE-PRODUCTION-ISOLATION`.

## RTAI-S3 — 餐饮 AI 严格语义与多轮上下文收口

- 状态：`merged+deployed+verified`；Owner：`/root`。
- 实现：PR [#1570](https://github.com/Stevenjxie/cretas/pull/1570) / main `35ffe430bb9daf953a09d19d8828d9b84b49d1d4`，残留回归 PR [#1573](https://github.com/Stevenjxie/cretas/pull/1573) / main `6a72bb802`。
- 修复：收敛指标口径、自然问法路由、缺失能力逐项披露、Java/Python 多轮上下文和固定模板冗余；不以营业额替代净利润、过程时长或其他不可得指标。
- 验证：Java/Python 目标测试、单生命周期发布构建和生产只读回放通过；生产业务写入为 0。
- Scope 锁：已释放。

## RTAI-S4 — 餐饮 AI 下游故障语义与演示最近完整日

- 状态：`merged+deployed+verified`；Owner：`/root`；实现 PR [#1575](https://github.com/Stevenjxie/cretas/pull/1575) / main `78607525badf000f72ea0744818e5c89884cb2d1`。
- 修复：低毛利候选异常、今日/昨日与前天/服务速度/门店毛利追问的下游不可用语义、演示租户最近完整日聚合刷新与双重确认/回滚审计。
- 生产：Java `v20260722_034103`，active `blue/10010`；Python 健康；每日刷新任务单实例且脚本哈希核验通过。
- 验证：服务级发布校验通过；严格生产审计记录 17 次餐饮 AI 调用，`actualBusinessWrites=0`、`blockedMutationAttempts=0`、HTTP 与控制台错误均为 0。
- Scope 锁：`RTAI-S4`、`RTAI-S4-JAVA`、`RTAI-S4-PYTHON`、`RTAI-S4-CRON` 已释放。

## RTAI-S5 — 餐饮 AI 多轮日期与门店作用域收口（R5/R6）

- 状态：`merged; NOT_DEPLOYED`；Owner：`/root`；R6 基线 main `53b3f02c6261da8136ce73bc9618e8618bd61bce`。
- R5：PR [#1576](https://github.com/Stevenjxie/cretas/pull/1576) 已发布并完成 13 个生产单轮场景零写复验，进一步暴露日期追问和门店“它”追问两条真实多轮缺陷。
- R6：保存并恢复同一会话的绝对双日期；按 factory/user/session 校验所有权；显式新日期与非法、反向、重叠、越界日期整组 fail-closed；Java→Python 仅转发门店/日期白名单上下文，鉴权租户保持原值。
- 门店语义：演示租户仅在受控门店查询内部映射 canonical 数据空间；SQL 与结果层双重限域；指定门店不存在或缺数时返回定向中文缺口，禁止退化为全店榜或营业额排名。
- 日期语义：两个绝对日期范围分别计算毛利；缺任一范围时点名缺数日期，不替换成其他日期、营业额或其他指标；客户回答不暴露内部英文标识、工具名或异常名。
- 验证：Python 餐饮目标测试 `183 passed`；Java 工具/HTTP 契约/会话/中文时间指代目标测试 `36 passed`；最终 `git diff --check` 通过。
- 发布：本轮按用户要求仅合入 `main`，不在当前任务部署；生产发布与严格只读复验已交接 Claude，必须等待 exact merged main SHA。
- Scope 锁：`RTAI-S5`、`RTAI-S5-ROUTE-AUDIT`、`RTAI-S5-COREF-AUDIT`、`RTAI-S5-PYTHON` 已释放。
