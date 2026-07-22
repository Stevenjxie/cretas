# Dispatch 完成归档 — 2026-07-22

## BUG-F006-R3-SALES-OA-INSTANCE-001

- Status: `merged+deployed+service-verified`; Owner: `/root`; implementation PR [#1614](https://github.com/Stevenjxie/cretas/pull/1614), exact deployed implementation main `a0982983c0c88b63d1dff246ef7be61eaef1fd13`.
- Scope: sales confirmation -> persisted unified OA instance -> automatic terminal approval or finance OA task -> transactional sales-state and voucher projection. New sales submissions use unified OA only; legacy approval records/config remain read-only compatible and are not dual-written or backfilled.
- F006 default policy: external orders and orders at or below 5000 yuan auto-complete inside OA; orders above 5000 yuan create one finance task. The threshold and assignee roles are maintained in the unified OA Canvas definition; the migration seeds only the first missing default and never overwrites an existing active definition.
- Verification: immutable Java release lifecycle 11 selected classes / 63 tests including real PostgreSQL JPA startup; Web Vitest 10 files / 29 tests, `vue-tsc --noEmit`, and immutable Vite release build (4457 modules) all passed. Durable OA action idempotency, definition-running guards, finance voucher timing, deep-link reactivity and role routing are covered.
- Production release: Java `v20260722_201341`, blue/10010 -> green/10020, artifact MD5 `d4089d36fa060a771c9e903f94f9bc43`, post-switch health 5/5 and unified verification passed; Web four-way index SHA-256 `842d132378e23fafa0ce9d81510788c872af8a0d578171f9e6814286e8e5ee63` passed. Flyway `20261029.01`/`20261029.02` are successful and F006 has exactly one published+enabled `SALES_ORDER_APPROVAL` definition. Implementation/deployment business writes 0; existing `SO-20260722-0001`, PO, inventory and LIUSHANMEN records were not changed or bridged. Test Java 10011 remained unhealthy and was not modified because authorization covered production only.

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

## BUG-F006-R3-PRODPLAN-WORKFLOW-BINDING-001 — 生产计划误报 Workflow 未绑定

- 状态：`review-ready; NOT_DEPLOYED`；Owner：`/root`；Base SHA：`23fcb6880287213e301e8eeb755574a6d0abe721`。
- Headed 生产只读复现：F006 存货生产选择 `CPF0060016` 后 `resolve-by-outputs` 返回 `NONE`；实际 activation 为 enabled `108/v1`，发布图终端 SKU identity 正确且实时 `unitWarnings=[]`，仅遗留宽泛 `unitReviewRequired=true`。打开 Workflow 还会尝试自动 PUT 草稿，右侧 Workflow AI 固定显示；业务写入 0。
- 修复：宽泛 review 标记改为触发实时单位契约复核，当前有效则允许生产计划解析/激活/运行时，真实不兼容继续 fail-closed；发布版页面只读加载不再自动 fork/save；移除右侧 AI 回归并恢复全宽画布。
- 验证：Java 6 类 `55/55`，Web Vitest `3/3`，`vue-tsc --noEmit`，`git diff --check` 全部通过。
- Scope 锁：已释放；最终 commit/main 以本任务合并回执为准。

## BUG-F006-SALES-DETAIL-PURCHASE-PERMISSION-001 — 销售详情越权加载采购关联阻塞 OA

- 状态：`review-ready`；Owner：`/root`；Base SHA：`27eeb2374cda55c75eaaf4c768b836baaee4f24e`；实现 commit：`8e2cb88b2852a8558c4fffeb17f624356713063d`。
- 根因与修复：销售详情无条件加载关联采购接口，销售主管收到 403 全局通知并无法点击 OA 提交；现以共享 `permissionStore.canAccess('procurement')` 同时门禁请求与页签，不放宽销售权限，有采购读取权限的角色保持原能力。
- 验证：销售订单目标 Vitest `6 files / 34 tests` PASS；唯一 Web release build `4457 modules` PASS，web tree `5ef7f95db8d45ef15e192d276a4d8b7bbc68b9e4`，archive SHA-256 `f02dd2e3dd12fcd5807fd21caf525de98a6ff7fe086b7c343e4d7578eab3be21`。
- 续测边界：部署后复用 F006 `SO-20260722-0002` 从“确认并提交 OA 审批”继续，不重建订单；实现/构建期间生产业务写入 0，未触碰 LIUSHANMEN。
- Scope 锁：已释放。

## BUG-F006-R3-OA-FINANCE-ROUTE-001 — 财务经理个人 OA 路由 403

- 状态：`review-ready; NOT_DEPLOYED`；Owner：`/root`；Base SHA：`78ec4c2070cc0646afc07ce25f9d88d2a7acabd1`。
- 生产 headed 现场：销售主管从页面创建高金额 `SO-20260722-0003`，唯一创建 POST 与唯一 OA 提交 POST 均为 200，订单进入 `PENDING_FINANCE_REVIEW`，详情解析财务节点、角色及 `f006_finance_mgr`；财务经理访问统一 `/workflow/pending` 时被前端路由守卫直接导向 403，未发起审批写请求。
- 根因与修复：财务专用菜单已经包含个人 OA，但历史 `ROLE_PATH_WHITELIST.finance_manager` 漏掉 `/workflow`；仅补该共享 OA 前缀，后端仍按 factory、当前节点角色和明确 assignee 逐任务鉴权，不扩大采购、销售或其他模块权限。
- 验证：Web 路由/财审/菜单目标 Vitest `3 files / 63 tests` PASS，`git diff --check` PASS；部署后从同一 `SO-20260722-0003` 财务节点续测，不创建第二订单。
- Scope 锁：已释放。
