# Dispatch 完成归档 — 2026-07-27

### `BUG-QC-NOTIFICATION-READ-BADGE-20260727`

- **状态**：`merged`，scope 锁已释放；production Android OTA 待按本次用户授权从 exact `origin/main` 发布并核验。
- **Base / 功能提交 / PR**：Base SHA `fddf0b75d97d2f240c42929ab8ece2512bab101d`；候选 `57a05fee4f94d1e444be2629d3dbe132a474e759`；[#1873](https://github.com/Stevenjxie/cretas/pull/1873)。
- **实际范围**：质检通知列表按当前用户查询；单条/全部已读均先写服务器，失败保留未读状态；兼容后端 `isRead` 与大写通知类型；返回首页时重新拉取铃铛角标。首页“待我审核”在聚焦时立即读取，并在停留期间每 10 秒静默刷新，AI 初筛完成后无需手动下拉即可出现。
- **验证证据**：通知 API、失败保护、首页回焦与待审核自动刷新目标 Jest `13/13`，Android production Expo export 成功，runtimeVersion `1.0.3`，`git diff --check` 与 tracked-secret-scan 通过；全仓 TypeScript 仅有未修改的 `ProcessTaskListScreen.test.tsx:227` 既有夹具字段错误，本次文件 0 条类型错误。
- **安全边界**：未修改 Java 通知模型、Repository、迁移或其他角色通知页；未写入生产通知、QC 任务或其他业务数据。

### `BUG-QC-WEB-REVIEW-CLOSEOUT-20260727-003`

- **状态**：`merged`、Web Admin/QCSOP 生产发布与公网只读验收完成，scope 锁已释放。
- **Base / PR / main 合并提交**：Base SHA `c1e4ed173eb8487e1af7d3b5cb3617c5e638e806`；候选 `d1306c00b`、调度收口 `29082c3a4`；[#1868](https://github.com/Stevenjxie/cretas/pull/1868) → `0b91d47a50301302156b5dd96f3dd2d44441bbd3`。
- **实际范围**：质量管理侧栏补“包装标签拍检”入口；质量经理使用真实 Web Admin 完成 AI 框确认/拒绝、人工补框、拖动/缩放/删除、逐图结论、下一张与整单提交；QCSOP 增加 Web 人工审核章节及四张真实页面截图，并明确一线质检员与 Web 质量经理角色边界。
- **验证证据**：Web 人工审核 Playwright `1/1`（包含提交载荷与提交后回读），QCSOP 桌面/390px/资源/灯箱 Playwright `2/2`，菜单/审核模型/训练生命周期 Vitest `71/71`；唯一可信 Web production build 成功，archive SHA-256 `c842d507d9c967f20dd316b01aed7d5f9cfd4a79131cdbc7c789b8e790caf19f`。
- **Web 发布**：从 clean exact `origin/main@0b91d47a50301302156b5dd96f3dd2d44441bbd3` 复用相同 Web tree 制品并原子发布；本地、服务器、网关 HTTP、公网 HTTPS 四方 `index.html` SHA-256 均为 `b85b9076cc8ae66eadf5674523b4d808e4f29883c4b707fb7a94dd0425082948`。菜单 chunk `AppLayout-nmFjW8yY.js` 与审核 chunk `index-DvsWgssf.js` 的本地/公网 SHA-256 分别一致。
- **QCSOP 发布**：原子发布至 `https://aiassist.cretaceousfuture.com/lsmsop/qcsop/`；公网 HTML SHA-256 `6204646ab5442bcc7a5e6ad006498f5e5dbfbcd9f034f4bf4a6c992007e005d6` 与仓库一致，正文 Web 人工审核标记存在，HTML 引用的 21 张图片全部 HTTP 成功（发布目录共 22 张资源），回滚点 `/www/wwwroot/lsmsop/qcsop.bak.20260727T1046`。
- **发布异常与恢复**：首轮远端脚本经 PowerShell 管道带入 BOM，旧目录改名后在新目录切入前中止；通过已核验备份立即恢复，公网回读 HTTP 200。随后改用无 BOM/CRLF 的 LF 脚本重跑并成功完成原子切换、`nginx -t` 与公网哈希验收。
- **业务写入**：本次仅发布静态 Web/QCSOP 制品；未创建生产拍检任务，生产账号、白名单、QC 与其他业务数据写入均为 `0`，未训练或发布模型。

### `SOP-DUAL-LINE-SYNC-20260727`

- **状态**：`merged`、双线生产发布与真实回答验收完成，scope 锁已释放。
- **Base / 检查区间**：Base SHA `a55c9d71ab6239e3856bdf109194c6744b1f957d`；自动化基线 `77a0c419082d42ff999612d732636b928f48046e` 至本次功能合并。
- **功能提交 / PR / main 合并提交**：SOP/回答合同 [#1864](https://github.com/Stevenjxie/cretas/pull/1864) → `4ef75ee79300d6dd2183915936818526b0cf5d94`；HTML 解析依赖补齐 [#1865](https://github.com/Stevenjxie/cretas/pull/1865) → `547bfc233bfcbece8a85425454eff8033499301d`。
- **验证证据**：SOP 合同测试 `19/19`、`py_compile`、BeautifulSoup clean-runtime HTML 解析、`git diff --check` 与两个 PR 的 tracked-secret gate 通过；production preflight 通过，migration dry-run `would-apply=0 / skipped=122`。
- **静态页面**：`/aiassist.html`、`/lsmsop/`、`/cysop/` 均从合入后的仓库精确文件做备份、传输哈希核对与原子替换；仓库/服务器/公网 SHA-256 分别为 `ec6c4ef53a2b29ffba936f7f0393d978397bf55afc280c1264577e6a96216d2c`、`a93242cb887956f68a3ac0290d4a6c09d379fb219213f3327bfe8c72c8258036`、`cd2091a29ba7aec22b198a324a230a52bd3a3991c3771116b9007fcbe5ef4b30`。回滚点：`/www/wwwroot/web-admin/aiassist.html.bak-auto-20260727T094503`、`/www/wwwroot/lsmsop/index.html.bak-auto-20260727T094503`、`/www/wwwroot/cysop/index.html.bak-auto-20260727T094503`。
- **RAG 正式块**：仅重建本次变化的 canonical sources；`f006-production-full-chain-sop.md=42`，`restaurant-full-chain-sop.html=65`，`restaurant-product-manual.html=229`，`restaurant-metrics-glossary.html=169`，创建时间均为 `2026-07-27 09:57–09:58`，`.NEW=0`。工厂真实回答只引用 F006 source；餐饮只引用注册表中的三个餐饮 source。
- **真实回答验收**：工厂 `3/3`（BOM/Workflow ACTIVE 门禁及发布启用、原料包装换算、Workflow 多产出与标签人工审核）；餐饮 `3/3`（中餐单菜毛利边界、同会话范围继承/覆盖、导览助手不做数值计算并指向 SmartBI）。固定基准口径与页面流程一致，无跨业务线引用。
- **生产健康与写入**：Python exact `origin/main@547bfc233bfcbece8a85425454eff8033499301d` 发布完成；8083、Embedding 9090、PostgreSQL 5432 与数据库连接健康。未运行 test 环境发布；生产 ERP 业务写入 `0`。
- **安全后续**：一次失败的环境继承诊断把生产进程环境显示到了当前私有 Codex 任务日志；未写入 tracked 文件或 PR，正文不记录任何值。该事件不影响本次双线页面/RAG/回答一致性验收，但相关可轮换凭证必须另项立即轮换。

### `FEATURE-QC-ENTRY-WEB-TRAINING-QCSOP-20260727-002`

- **状态**：`merged`
- **Owner**：`/root`
- **Base SHA**：`cb09348e7ecbd1520e0666932e5d5c1a9fb0b8a0`
- **功能提交 / PR**：`3a5e4d80f`、`bfce86dc1` / [#1844](https://github.com/Stevenjxie/cretas/pull/1844)
- **实际范围**：质检员 RN 首页首屏增加待人工审核入口并收敛无关信息，底部导航适配安全区；QCSOP 使用真实 Expo Web 与 Web Admin 截图，覆盖手机号白名单邀请、锁定工厂与角色、员工本人设密、登录及忘记密码；Web 标签质检审核后增加可恢复归档、备份记录、技术管理员训练集确认与已批准数据导出，普通质检员无训练确认权限。
- **验证证据**：真实 JPA Context、权限、服务及多设备会话 Java 目标测试 `15/15`；RN Jest `10/10`；Web Vitest `11/11`；Java JAR 与 Web production 制品构建成功且 relevant Git tree 与可信 manifest 一致；tracked secret scan、diff check、真实 Expo Web/Web Admin 截图及 QCSOP 资源完整性检查通过。RN 全量 typecheck 仅剩未修改文件 `ProcessTaskListScreen.test.tsx:227` 的既有 `totalPages` 基线错误。
- **发布状态**：用户后续授权统一发布。Java 已从蓝槽 `10010` 切换到绿槽 `10020`，健康检查、功能标记与切流后 `5/5` 轮观察通过；Web Admin 已原子发布且本地/服务器/网关/公网四方 `index.html` SHA-256 一致；QCSOP 已发布至 `https://aiassist.cretaceousfuture.com/lsmsop/qcsop/`，公网 HTTP 200 且资源校验通过；RN 以 runtime `1.0.3` 发布 production Android OTA `1785095974077`，manifest、CDN bundle 与功能标记校验通过。未训练或发布模型，生产账号、白名单、QC 任务及其他业务数据写入为 `0`。

### `FEATURE-LIUSHANMEN-PHONE-WHITELIST-ONBOARDING-20260727-001`

- **状态**：`merged`
- **Owner**：`/root`
- **Base SHA**：`96025f9e3cfb1d7de1c87fe70cb92177c4760aeb`
- **功能提交 / PR / main 合并提交**：`fef5ec7fb` / [#1840](https://github.com/Stevenjxie/cretas/pull/1840) / `cb09348e7ecbd1520e0666932e5d5c1a9fb0b8a0`
- **实际范围**：工厂管理员按手机号创建白名单邀请并指定本厂角色；员工注册账号固定为手机号、本人设置密码；注册阶段二再次验证同一工厂邀请并拒绝客户端提权；同时保留同手机号多设备并发登录。
- **验证证据**：真实 JPA Context 与 Java 注册/白名单/多设备目标测试 `10/10`，RN Jest `19/19`，Web Vitest `3/3`，Java/Web 候选制品一次构建成功，PR 密钥扫描通过。
- **发布状态**：该能力随 `FEATURE-QC-ENTRY-WEB-TRAINING-QCSOP-20260727-002` 后续统一发布至 Java/Web 生产与 RN production Android OTA；生产账号、白名单和业务数据写入为 `0`。

### `FEATURE-RN-MULTIDEVICE-QC-CONCURRENCY-20260727-001`

- **状态**：`merged`
- **Owner**：`/root`
- **Base SHA**：`986676d1ec1e4e8de54c72f8071a00fe6e0dfda3`
- **功能提交 / PR / main 合并提交**：`4157c93b6c8b4562af7bf8c7279af879c770d541` / [#1839](https://github.com/Stevenjxie/cretas/pull/1839) / `412e6f610bd97889f3bcb6efaa931d08eb21b4f8`
- **实际范围**：移动端 JWT 增加唯一 `jti`，退出仅撤销当前设备会话；标签 QC 审核增加版本、请求幂等键和租户范围行锁；RN 对其他设备已抢先审核的任务给出明确返回待审核列表提示。
- **验证证据**：最终 exact-main 前置 release Maven 目标测试 `13/13`，其中真实 JPA Context 与双线程锁测试 `2/2`；RN 目标 Jest `7/7`；PR 密钥回归门禁通过。
- **发布状态**：该能力随 `FEATURE-QC-ENTRY-WEB-TRAINING-QCSOP-20260727-002` 后续统一发布至 Java 生产与 RN production Android OTA；生产账号、QC 数据和其他业务数据写入为 `0`。

### `BUG-LABEL-QC-WEB-FOOLPROOF-20260726-001`

- **状态**：`merged`
- **Owner**：`/root`
- **Base SHA**：`7e15310df92b85b6031973908e4ee9bc7ae584c2`
- **功能提交 / PR / main 合并提交**：`4c49501ecc01b209b2d42022de952d23e21a18d0` / [#1831](https://github.com/Stevenjxie/cretas/pull/1831) / `1884e9fa8caa30e20441db420de9e175c61e772b`
- **实际范围**：
  - Web QC 审核工作台在低高度桌面端固定显示本图结论与导航，未完成当前照片时禁止进入下一张。
  - 整图正常前显示批量拒绝数量并二次确认；被拒绝 AI 疑点保持可见且支持撤销。
  - 人工补框与整图正常结论互斥；审核页存在未保存变更时阻止误关闭。
- **验证证据**：
  - 目标 Vitest `8/8`，`vue-tsc` 与可信 Web production build 通过。
  - 1440×900、1024×768 浏览器流程通过；四张真实样图按“缺白标 / 正常 / 缺彩标 / 缺白标”完成至 `4/4`。
  - PR `tracked-secret-scan` 通过，浏览器与发布验收的生产业务写入均为 `0`。
- **生产发布**：
  - 从 clean exact `origin/main@1884e9fa8caa30e20441db420de9e175c61e772b` 复用相同 `web-admin` tree `5da5a7ce5d742aed136147b620df7076c7f4ac4f` 的可信制品并原子发布。
  - 制品 archive SHA-256 为 `eecc4115497e6748e30f1f9af53c0e775aa0054f76c23028c1c32de6449dc0d1`。
  - local/server/gateway HTTP/public HTTPS 四方 `index.html` SHA-256 均为 `6a491e118f26c6fb626d4ea2b9bcebea85114f0b44ade5adb4453b2dfc86b79f`，公开 QC 路由 HTTP 200。
  - QC 懒加载资源 `index-Cty-GNXL.js` 的本地/公开 SHA-256 均为 `4b4d2ba2942e38ae6a4b769965a4605a7e9099fa6cbeb38b4add4b69e4b52f7e`，且包含本次功能标记。
- **业务写入审计**：本次仅发布静态 Web 制品；生产业务数据写入为 `0`。

## 餐饮 AI 全量测试、修复与回归收口（2026-07-27 08:47）

- **状态**：`merged`；Google Sheet“餐饮”Tab 已完成最终回写与逐单元格回读核验。
- **最终生产代码**：`769f224a804dcf45365f82fcf64dcd982bb96d9a`；R27/R28/R29 分别由 PR #1860、#1861、#1862 合入。
- **生产发布**：Java release `v20260727_081935`，active=`green/10020`，R28/R29 未改变 Java tree；Python exact-main 已发布至 8083，迁移 122 项全部 up-to-date，import/classifier/Torch smoke 与健康检查通过。
- **最终验收**：同一最终部署版本餐饮 `83/83` 场景、`207/207` 轮通过；普通菜 20 道、米饭和小数销量菜四轮链全部通过；独立 27 轮长上下文 `27/27` 通过，菜品、销量、诊断/优化、时间与单店/多店/全部门店范围不漂移。
- **默认排行与显式米饭**：默认 Top 排除米饭、包装、餐具、纸巾等附属/基础项；明确问米饭时可正常返回销量、原因与优化；显式 exclusion 在时间/门店澄清后保持干净实体。
- **读写安全**：咨询/操作模式分离；危险动作链持续显示未执行提醒，READ 选择保留原排行槽，写动词不进入 resolver；`actualBusinessWrites=0`，`blockedMutationAttempts=0`。
- **自动化门禁**：餐饮 Python 扩大回归 `702/702`，owner-action 基线 `25/25`，Ruff（仅排除仓库既有末尾导入 E402）、py_compile、diff check、tracked-secret 本地/CI 门禁通过。
- **Sheet 收口**：原有 77 条全部更新，新增 compact 后去重问题 11 条，共 88 条；最终 `已解决=88`，其他状态为 0；88 条备注均含最终 commit 和同版证据。
- **UI 证据**：最终 Web tree 未在 R28/R29 改变；已复用真实 Demo 浏览器会话验证时间按钮、门店按钮、非阻塞未执行提醒、普通菜与米饭控制链，既有字号强调和图表交互无回归。

### 已释放的餐饮任务与 scope 锁

- `BUG-RESTAURANT-READ-ANALYSIS-CHOICE-R29-20260727-038`
- `BUG-RESTAURANT-RANKING-EXCLUSION-CONTEXT-R28-20260727-037`
- `BUG-RESTAURANT-FINAL-SEMANTIC-REGRESSION-R27-20260727-036`
- `BUG-RESTAURANT-PRODUCT-CONTEXT-TIME-R25-20260727-034`
- `BUG-RESTAURANT-LONG-CONTEXT-R26-20260727-035`
- `BUG-RESTAURANT-SYNTHESIS-SOURCE-TENANT-R24-20260727-033`
- `BUG-RESTAURANT-CONTROLLER-SUPPLIER-SCOPE-R23-20260727-032`
- `BUG-RESTAURANT-SYNTHESIS-SCOPE-FOLLOWUP-R22-20260727-031`
- `BUG-RESTAURANT-READ-ACTION-CHOICE-R21-20260727-030`
- `BUG-RESTAURANT-SYNTHESIS-STORE-SCOPE-R20-20260727-029`
- `BUG-RESTAURANT-CROSS-GRAIN-READ-R19-20260727-028`
- `BUG-RESTAURANT-SYNTHESIS-CHART-HOVER-R18-20260727-027`
- `BUG-RESTAURANT-PEAK-MONTH-FOLLOWUP-CONTEXT-R17-20260727-026`
- `BUG-RESTAURANT-WEB-UNIFIED-INTENT-ROUTING-R12-20260727-021`
- `BUG-RESTAURANT-CHAT-SESSION-TEST-ISOLATION-R13-20260727-022`
- `BUG-RESTAURANT-DISH-STORE-SCOPE-ACTION-R11-20260727-020`
- `BUG-RESTAURANT-SESSION-PARAM-BRIDGE-R10-20260727-019`
- `BUG-RESTAURANT-SESSION-BRIDGE-R9-20260727-018`
- `BUG-RESTAURANT-FULL-REGRESSION-R8-20260727-017`
- `BUG-RESTAURANT-FULL-REGRESSION-CLOSEOUT-20260726-016`
- `BUG-RESTAURANT-READ-ACTION-SEMANTIC-REPLACEMENT-20260726-015`
- `BUG-RESTAURANT-READ-ACTION-TIME-PRECEDENCE-20260726-014`
- `BUG-RESTAURANT-READ-ACTION-AUTHORITY-20260726-013`
- `BUG-RESTAURANT-TENANT-GATE-RLS-20260726-012`
- `BUG-RESTAURANT-READ-ACTION-CONTEXT-20260726-011`
- `BUG-RESTAURANT-COMPREHENSIVE-GUARD-20260726-010`
- `BUG-RESTAURANT-CONTEXT-SLOT-FREEZE-20260726-009`
- `BUG-RESTAURANT-EXPLICIT-TIME-PARTIAL-COMPARISON-20260726-008`
- `BUG-RESTAURANT-PERIOD-COMPARISON-CONTEXT-20260726-007`
- `BUG-RESTAURANT-PEAK-MONTH-DIMENSIONS-20260726-006`
- `BUG-RESTAURANT-PEAK-MONTH-FOLLOWUP-20260726-005`
- `BUG-RESTAURANT-MULTISTORE-RANKING-20260726-001`
- `RESTAURANT-ISSUE-CLOSEOUT-20260725-001`
- `BUG-RESTAURANT-CONTEXT-UNIT-COST-20260726-001`
- `BUG-RESTAURANT-SYNTHESIS-QUANTITY-R14-20260727-023`
- `BUG-RESTAURANT-EXPLICIT-DISH-SWITCH-R15-20260727-024`
- `BUG-RESTAURANT-RANKING-TIME-R16-20260727-025`

### `TEST-LABEL-QC-PROD-READONLY-20260727`

- **状态**：`merged`
- **Owner**：`/root`
- **Base SHA**：`98e14c8038b829bb0e0f2a8424cc43e2b76fcb7e`
- **功能提交 / PR / main 合并提交**：`5b2d7dc6de31182e51503d5e17292896020518b6` / [#1870](https://github.com/Stevenjxie/cretas/pull/1870) / `61fb4554346d35fa1730715a0a1c6f616865282b`
- **实际范围**：在生产只读 Playwright harness 中新增 `/quality/label-qc` 路由与 `label-qc-readonly` 场景，检查“包装标签拍检 / 待人工审核 / 已审核整理 / 归档记录”，同步确定性 MCP bundle 与本地 fixture；未修改业务页面、API、数据或 mutation allowlist。
- **验证证据**：unit `15/15`、fixture `4/4`、dry-run、CLI/MCP syntax 与 diff gate 通过；PR `tracked-secret-scan` 通过。
- **生产只读验收**：同一干净 UI 登录会话中，F006 `tenant-isolation` 与 `label-qc-readonly` 均 PASS；标签 QC 状态统计与任务列表两个 GET 均为 200，HTTP errors、failed requests、console/page errors、blocked mutation attempts 和 actual business writes 均为 0。
- **发布状态**：测试覆盖已合入 `main`；没有生产业务代码或静态制品变化，因此未重复部署。

## 餐饮 AI LLM-first 语义与白话出口收口（PR #1871）

- **任务**：`UX-RESTAURANT-PLAIN-LANGUAGE-20260727`
- **状态**：`merged`
- **Owner**：`/root`
- **Base SHA**：`c1e4ed173eb8487e1af7d3b5cb3617c5e638e806`
- **功能提交 / PR**：`7b868f75d6afea59d27178d1c6ec86e3ebb365a9` / [#1871](https://github.com/Stevenjxie/cretas/pull/1871)
- **实际范围**：餐饮 Web、流式和 Java 入口统一由 LLM 先理解用户原话，接收最多 20 轮可信历史与真实门店清单；关键词/向量退到工具匹配与执行校验层。修复具体门店选择重复追问、“营收怎么提高”退化为只报营收、门店数量/名单无法回答，并统一顾客可见白话出口。
- **安全边界**：LLM 不可用或返回不完整契约时明确停止，不按关键词猜测；LLM 追问优先于复合句拆分；只读数据继续由确定性 SQL/Gold resolver 计算，写操作权限和确认边界不变。未修改共享模型账号顺序、数据库结构或工厂问答。
- **验证证据**：餐饮目标回归 `833/833`，新增门店按钮续接、20 轮上下文、经营优化、门店目录、LLM 不完整契约 fail-closed、Java 单次语义计划和白话出口覆盖；`py_compile`、`git diff --check` 通过。
- **发布边界**：合并后仅从 clean exact `origin/main` 发布 Python 服务；Java/Web 无代码变化。合并前生产业务写入为 `0`。

## 餐饮 AI 生产回归修复：完整语义上下文与按钮续接（PR #1874）

- **任务**：`BUG-RESTAURANT-LIVE-REGRESSION-20260727-039`
- **状态**：`merged`
- **Owner**：`/root`
- **Base SHA**：`fddf0b75d97d2f240c42929ab8ece2512bab101d`
- **功能提交 / PR**：`a62f17ebbee674be29c8b273828785fd0ba92e53` / [#1874](https://github.com/Stevenjxie/cretas/pull/1874)
- **根因与修复**：餐饮只读自然语言曾被 Java 前置规则、关键词与旧会话继承先于 LLM 抢答；完成多轮补槽后又只保存最后的“全部门店”等按钮文字，导致指标、时间、比较与动作丢失。现将认证餐饮 READ 请求先交给 LLM 生成完整 QueryPlan，显式零歧义门店目录仅作契约修复；补槽续接保存完整 sealed query 和白名单结构化槽位，最多保留最近 20 轮；库存按钮明确为最新库存快照，不再错误询问时间。
- **执行契约**：resolver 接收不可变 requested metrics、action、ranking direction/limit；“这个星期”正确解析为本周并保留上周比较；菜品销量 Top 不再降级为毛利，无销售数据时也不使用相邻指标代答。未修改工厂意图和共享 `common/llm_router.py`。
- **合并门禁**：Python 餐饮/chat/synthesis `1122/1122`；Web 餐饮单测 `53/53`；Java 单次 `clean package` 生命周期 `40/40`、最终 JAR 与可信 manifest 生成成功；compileall、Ruff fatal、编码与 diff check 通过。
- **发布与验收边界**：本归档随 PR 合入；生产 Java/Python 发布和 Google Sheet 88 条餐饮清单的同版本只读验收在合并后执行并另行回写。合并前 `actualBusinessWrites=0`。

## 餐饮 AI semantic-first 高质量模型链 hotfix（PR #1875）

- **任务**：`BUG-RESTAURANT-SEMANTIC-FRONTDOOR-20260727-040`
- **状态**：`merged`
- **Owner**：`/root`
- **Base SHA**：`e5bc7fc87ae96edda20120f9e9a84bfd6c4672d6`
- **功能提交 / PR**：`c447ad7788bfecd1813f2e918951dcdd4171d45e` / [#1875](https://github.com/Stevenjxie/cretas/pull/1875)
- **生产根因**：PR #1874 发布后的真实餐饮电池记录到共享 MAPPER 两个 versioned Flash 返回 403，随后 alias、GLM、Plus 在 6 秒总预算的剩余碎片内连续超时，顶层 LLM 只能安全拒绝首句。
- **修复**：认证餐饮 semantic-first 与其补槽续接改用共享 Router 已审核的 REVIEW 高质量免费链，单候选 5 秒、总链 12 秒；legacy/offline T3 仍使用 MAPPER 2.5/6 秒。未修改 `common/llm_router.py`、工厂意图、账号模型 allowlist、到期或付费保护。
- **验证**：根因目标回归 `308/308`；餐饮/chat/synthesis 扩大回归 `1123/1123`；compileall、Ruff fatal、编码与 diff check 通过。
- **发布与验收边界**：本归档随 PR 合入；exact-main Python 发布、Demo 多轮按钮/上下文和 Google Sheet 餐饮清单回归在合并后继续执行。合并前 `actualBusinessWrites=0`。

## 质检拍检成品 SKU 选择器（PR #1876）

- **任务**：`QC-FINISHED-SKU-PICKER-20260727`
- **状态**：`merged`
- **Owner**：`/root`
- **Base SHA**：`73d60d9d297fc643fcb2ccb380be27805b74ea53`
- **功能提交 / PR**：`ef7f5143e5c84f2697d691377760a6e4bbae8298` / [#1876](https://github.com/Stevenjxie/cretas/pull/1876)
- **实际范围**：质检标签拍检的 SKU 选择器只加载当前工厂已启用的 `FINISHED_PRODUCT` 成品，支持编码和名称搜索；Java 创建任务入口同步 fail-closed，拒绝半成品或其他非成品绕过提交。
- **验证证据**：RN 目标测试 `2/2`、Java `LabelQcServiceTest` `12/12`、Android production Expo export 与 `git diff --check` 通过；全量 RN typecheck 仍只有既存 `ProcessTaskListScreen.test.tsx:227 totalPages` 基线错误。
- **发布边界**：合并后仅从 clean exact `origin/main` 发布 Java 后端与 Android OTA；Web Admin 无变更；生产业务写入保持 `0`。

## 餐饮 AI Google Sheet R30–R38 最终收口（PR #1890–#1894）

- **任务**：`BUG-RESTAURANT-SHEET-R30-R33-20260727-041`
- **状态**：`merged`
- **Owner**：`/root`
- **Base SHA**：`73d60d9d297fc643fcb2ccb380be27805b74ea53`
- **功能提交 / PR**：`0978fa1f30bc8a169b89a986be7b8c54472dca83` / [#1890](https://github.com/Stevenjxie/cretas/pull/1890)，`c41d572fa674f9dd811e8603cee6cd85b64890f6` / [#1891](https://github.com/Stevenjxie/cretas/pull/1891)，`94329c4eda18aed6896693ccda2eefd3906299de` / [#1892](https://github.com/Stevenjxie/cretas/pull/1892)，`57f185344587dbc2a43d46183ab50aa7ca4c417a` / [#1893](https://github.com/Stevenjxie/cretas/pull/1893)，`acc74696932307b050041cdbab47dbdaf3329e8c` / [#1894](https://github.com/Stevenjxie/cretas/pull/1894)。
- **最终能力**：自由文本先由 LLM 完整理解，再由确定性 resolver/tool 取真实数据；固定时间/门店按钮在可信上下文中补齐缺失槽位，不因模型失败丢上下文；具名菜仅推荐当前期间确有数据的门店；同名/简称门店先缩小候选；当前周期缺数时保留原任务并给相邻周期；营收提升可使用同口径 POS 财务摘要生成行动方案。
- **操作安全**：具名菜下架只生成 W0 预览，展示当前状态与目标状态；排行派生批量下架先查候选、逐菜确认，不能把整段排行条件当菜名，也不能直接批量写入。
- **合并门禁**：最终 Python 餐饮与综合回归 `1161/1161`，澄清专项 `60/60`，Java `RestaurantOpsGoldRouteTest` 单次 release 生命周期 `39/39` 并生成最终 JAR；Ruff、`py_compile`、diff check 通过。
- **生产发布**：Java `57f185344587dbc2a43d46183ab50aa7ca4c417a` 已发布到蓝槽 `10010`，切流后 `5/5` 健康观察通过；Python `acc74696932307b050041cdbab47dbdaf3329e8c` 已从 clean exact-main 发布，122 项迁移均 up-to-date，import、route、Torch smoke 和内部健康通过。
- **最终验收**：生产餐饮全量语义电池 `65/65`，平均 `4.8s`、中位 `4.8s`、P95 `6.8s`、最大 `11.8s`，确认不存在 6 秒硬截断；真实 Demo 前端通过米饭三轮补槽、具名门店销量、营收行动方案、具名菜下架预览与排行派生动作安全提示。
- **Google Sheet**：`餐饮` R30–R38 与 `餐饮整改清单` 9 条对应记录均为“已解决”；统计公式已扩展至第 1000 行，回读为总计 `97`、未解决 `0`、P0/P1 `89`。
- **业务写入审计**：所有生产验收均为只读或 `previewOnly`，未点击确认，`actualBusinessWrites=0`。
