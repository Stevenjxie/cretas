# Dispatch 完成归档 — 2026-07-21

## BUG-F006-R3-MATERIAL-CATEGORY-PREFIX-001

- 状态：`merged-ready`；Owner：Codex coordinator (`/root`)。
- 登记 Base SHA：`e7b4cc58936c1ff1caa7aca940a64cef3faae3e7`。
- 实现 commit：`83deeba16f7c91693e443666951b878758300945`；PR：[#1547](https://github.com/Stevenjxie/cretas/pull/1547)。
- 根因：Web 仍调用只生成16位分类码的旧预览入口，而创建边界随后独立调用业务编码前缀分配器；新双码迁移没有为全部历史分类预置前缀，因此页面先展示可保存的16位码，保存时却因另一套 resolver 拒绝。页面 catch 又重复展示已由 API 拦截器提示的同一错误。
- 修复：合法启用 L3 的只读预览和事务内创建统一使用 `MaterialBusinessCodeService`；显式祖先前缀优先，缺省时按不可变10位 L3 数字身份的 base36 生成稳定、工厂隔离、无分隔符前缀。预览不写前缀/计数器，首次保存才在 L3 行锁下持久化并原子分配；停用或冲突配置 fail-closed，绝不覆盖、猜测或桥接历史编码。
- Web：完整 L1-L3 选定后同时展示业务编码和16位兼容分类编码，保存前复用只读契约；过期异步响应被丢弃；业务 4xx 仅由共享拦截器显示一次，纯网络错误才显示单一兜底提示。
- 验收：Java 19/19，通过真实 Spring/Hibernate JPA Context，覆盖显式前缀、缺省稳定前缀、预览零写、并发首次分配、跨厂隔离、停用前缀拒绝、重复名称先拒绝；Web 12/12；`vue-tsc -b && vite build` 成功（4452 modules）。最终只读代码审查 P0=0、P1=0。
- 生产边界：`NOT_DEPLOYED`；生产业务 mutation=0；没有创建/修改分类或原料，没有历史桥接，未触碰 LIUSHANMEN。
- Scope 锁：已释放；`ENH-F006-MATERIAL-BUSINESS-CODE-001` 父任务仍在 ACTIVE，不因本阻塞子修复冒充整体完成。

## ENH-F006-MATERIAL-BUSINESS-CODE-001 — 按当前 L3 受控映射历史编码

- 状态：`merged-ready`；Owner：Codex coordinator (`/root`)。
- 登记 Base SHA：`c7fb4f9ad3c8f86894b8edb3b04c05534aac83a4`；最新 main 重放基线：`c257d8fdf780797835a352f2c9c76dce5925e9d1`。
- 实现状态：目标代码与文档合为单一安全提交；PR 在创建后补记。
- 只读结论：历史记录的当前 L3 可证明性因工厂而异；可证明行使用同源 resolver，不能证明的行只报告、不猜测。精确生产计数不进入公开仓库。历史16位 `code` 全部保留，`displayCode` 继续由 `businessCode` 优先、旧码兜底动态派生。
- 修复：增加只读预览与显式确认回填接口；悲观锁和条件更新保证并发唯一、重放 no-op；既有码、非法旧码和无启用 L3 行安全跳过；普通新增/编辑路径不能改写历史业务码。
- 验收：最新 main 基线上 Maven 17/17 PASS，包含真实 Spring/Hibernate JPA Context、预览零写、正式映射、旧码保持、displayCode切换、无效映射跳过、并发只分配一次与 Controller 确认门禁；最终只读代码审查 P0=0、P1=0。
- 生产边界：`NOT_DEPLOYED`；生产业务 mutation=0；未执行任何历史回填，未修改生产物料或分类。
- Scope 锁：已释放；后续若要正式执行历史映射，必须在部署后按工厂先预览，并取得单独生产写入授权。

## BUG-F006-R3-MATERIAL-DISPLAY-CODE-002

- 状态：`merged-ready`；Owner：Codex coordinator (`/root`)；登记 Base SHA：`6c259992d89cd8e1a9709975ccb084d50d8236d1`。
- 实现 commit：`8a39acecd045a6338659f8a7e71eefe125bc1fdb`；PR：[#1552](https://github.com/Stevenjxie/cretas/pull/1552)。
- 根因：后端创建、DTO 与搜索已经支持 `businessCode/displayCode`，但 Web 列表、编辑态和分类文案仍以16位兼容 `code` 为主，导致新双码能力在用户界面看似没有生效。
- 修复：物料身份统一优先显示 `displayCode → businessCode → legacy code`；有短码时16位编码仅作为“兼容码”次级信息，历史未映射行安全回退并标“历史编码”。分类选择器以名称为主、分类码为次级说明，创建区和搜索文案同步双码契约。
- 验收：Web 目标测试 13/13 PASS；`vue-tsc -b` PASS；正式 Web release build PASS（735 assets），构建仅执行一次并生成可信清单。
- 生产边界：后端、数据库和生产物料均未修改；没有执行历史回填；生产业务 mutation=0；未触碰 LIUSHANMEN。合入后仅发布 Web。
- Scope 锁：已释放。

## F006 供应关系、采购契约与统一 OA 收尾

- 状态：`merged`；Owner：`/root`。
- 实现 commit：`94bfdb74071d94e41c87426e91b190bf471ece12`；PR：[#1557](https://github.com/Stevenjxie/cretas/pull/1557)；main：`9de6436d1fe49eb9cadfa24c6de893cb9eb74cfc`。
- 完成范围：`ENH-F006-MATERIAL-SUPPLIER-BIDIRECTIONAL-001`、`BUG-F006-PURCHASE-ORDER-CREATE-ENTRY-001`、`BUG-F006-PURCHASE-ORDER-MATERIAL-SPEC-001`（含供应商价格/单位契约）、`BUG-F006-R3-OA-PROCUREMENT-001`。
- 验证：后端目标测试37项、Web目标测试14项、真实JPA Context 1项、`vue-tsc`、Web release manifest build 均通过。
- 边界：`NOT_DEPLOYED`；生产业务 mutation=0；`PO-20260721-0001` 未取消、重提、重建或桥接；未触碰 LIUSHANMEN。
- Scope 锁：以上四项已归档并释放。
## RTAI-S2 — 餐饮 AI Google Sheet 生产回归收口

- 状态：`merged+deployed+verified`；Owner：`/root`；登记 Base SHA：`a11f4bb46a73b0f8c1137731c5aad50f46af65b4`。
- 实现：PR [#1555](https://github.com/Stevenjxie/cretas/pull/1555) / main `79cd6604ac78402e78d4c4384c59ab2288e06865`，生产残留增量 PR [#1556](https://github.com/Stevenjxie/cretas/pull/1556) / main `cc85bd72458e638caf571bb9a2b162134ac5a775`。
- 修复：分析型只读问题优先于老板动作强制路由；相对日期、出餐能力边界、成本毛利澄清和禁止动作问题进入正确数据契约；Web 保留同一分析 session；门店冠军直答只返回第一名与核心依据；无数据比较同时写明主日期和基准日期，不用其他周期代替。
- 验证：首批 Java 27 项、Web 10 项、残留 Java 6 项、Python 1 项全部通过；Java 与 Web 可信 release manifest 均通过，PR secret gate 通过。
- 生产：Java 蓝绿最终 active `green/10020`，Python `8083` 健康，Web HTTP 200；Google Sheet 餐饮 AI 全量 11 场景首轮 9 项通过，剩余 2 项修复后定向通过，多轮日期继承通过。
- 安全：生产回归 `blockedMutationAttempts=0`、`actualBusinessWrites=0`、`safetyPassed=true`；没有数据库迁移、权限变更或业务数据写入。
- 证据：gitignored `.playwright-mcp/restaurant-sheet-20260721/live-regression/report.json` 与对应截图；发布结构化报告保存在本机 `.cache/cretas/deploy-reports/`。
- Scope 锁：已释放。
- `ARCH-CRETAS-UNIFIED-OA-APPROVAL-001` — `merged` — 个人 OA 四队列、工厂/用户/角色可见性与采购动作边界已随 PR [#1560](https://github.com/Stevenjxie/cretas/pull/1560) 合入 `main` `481b57f3b07755f0ad6fd7b0a68e9208e9093f90`；实现 commit `9d48e91ba61feada2fb356da44958002fd9516c6`，Web 64/64、Java 19/19、真实 JPA Context 与 Web production build 通过；生产业务写入 0，`NOT_DEPLOYED`。

## F006-OA-RECOVERY-001 — 历史采购单 OA 受限恢复边界

- 状态：`merged+deployed+verified`；Owner：`/root`；登记 Base SHA：`d21d1312e79313c800e9e8336519416b46a8a4b2`。
- 实现：PR [#1567](https://github.com/Stevenjxie/cretas/pull/1567) / main `c93e31a63d860db8e98996c705c5ee25dfa93108`；仅 `factory_super_admin` 可调用，要求精确订单号、显式确认、幂等键和原因，并验证同厂订单快照及可执行 OA 路由。
- 验证：正式 release lifecycle 16/16 PASS；backend tree `25802487482a3c1605c28becc5eb44f39d2df326`；JAR SHA-256 `0bd4a698095af829a8f1d984015089dcf24bbc770c5221782c21d6e05ca8167c`。
- 生产：版本 `v20260721_233852`，active `green/10020`，5/5 切流后健康通过；统一 verify-release 的 systemd、直连健康和 `approval-recovery` JAR marker 全部通过。
- 历史现场：部署后 query-only 证明 `PO-20260721-0001` 仍为 `SUBMITTED`、`workflowInstanceId` 空、`hasInstance=false`；生产业务 mutation=0，恢复写入继续等待用户单独明确授权，未触碰 LIUSHANMEN。
- Scope 锁：已释放。
