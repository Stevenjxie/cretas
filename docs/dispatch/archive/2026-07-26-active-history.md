# Dispatch 归档 — 2026-07-26

## 已完成

### `UX-INVENTORY-MATERIAL-CODE-COLUMN-20260726`

- **状态**：`merged`
- **Owner**：`/root`
- **Base SHA**：`24d73dd70a9b2c9d8d7eaf5a7fe696df1704f1a0`
- **功能提交 / PR**：`68e5e8787e3ff2a28b580dcda531e735940cab1d` / [#1825](https://github.com/Stevenjxie/cretas/pull/1825)
- **实现**：分仓库存查询的原料库存表新增独立“物料编码”列，缺失编码显示 `-`；关键词搜索同步支持批次号、物料编码和物料名称。复用既有 `MaterialBatchDTO.materialCode`，未修改 Java、数据库或生产业务数据。
- **验证**：合入最新 `origin/main` 后目标 Vitest `8 passed`，`vue-tsc -b` 与 Vite production build 通过，PR tracked secret scan 通过。
- **发布边界**：用户已授权合并后执行 Web-only 生产部署；实际部署状态以 clean exact `origin/main` 的统一发布回执和 Web 四方哈希验收为准。

### `REL-CRETAS-OTA-OPTIMIZED-APK-20260726`

- **状态**：`merged`
- **Owner**：`/root`
- **Base SHA**：`6d7dba97ede29249a043ed052196aacd15b4d47e`
- **功能提交 / PR / main 合并提交**：`5b470a6fdf267c45b49b07ddda92b2ac91d5206a` / [#1818](https://github.com/Stevenjxie/cretas/pull/1818) / `c1d13bb3dcfe2db9ec2c650088c1587ff7ca7cf8`
- **实现**：RN OTA 对齐自托管 Expo Updates v1：runtime/channel 隔离、JS 冷启动与前台主动检查、后台预下载、用户确认后重载、会话防抖与失败冷却；服务端使用 RSA 代码签名，Hermes bundle 以 gzip 发布到 OSS/CDN，共享资源使用内容寻址资产仓并在 CDN 完成后原子公开 bundle。
- **验证**：Python OTA `119 passed`、RN OTA `4 passed`；production Expo export 与正式 APK 构建通过。APK `1.0.3` / versionCode `14` / `122224038` bytes，SHA-256 `0bf94bbe174ab544822de93dc982167390c23970c899a31516fc5c20f773005a`，正式签名 SHA-256 `e2c55e0b74e0d12a4e0fbfcf0527d3ef571df950c60932c37faa24689ca6a941`；版本化地址与 `cretas-latest.apk` 完整下载哈希一致。
- **OTA 生产验收**：公网 manifest runtime `1.0.3` 的 RSA 签名由 APK 内证书成功验证；Hermes gzip 对象为 `6910672` bytes、解压 SHA-256 与 manifest hash 一致；39/39 个共享资源 HTTP 200 且 immutable。版本网关对 `1.0.2` 返回 `updateAvailable=true`、`latestVersion=1.0.3`、正确版本化下载地址与文件大小。
- **生产发布**：Python OTA 服务从 exact `origin/main` 发布并健康；Java 版本网关通过可信相同 JAR 的强制蓝绿配置刷新加载新版本，最终 active 为 `cretas-backend` / `10010`，5/5 切流后观察通过。下载页已发布 `1.0.3`。
- **业务写入审计**：本次仅发布代码、静态制品、OTA bundle 与版本配置；生产业务数据写入为 0。

### `BUG-F006-MATERIAL-PACKAGING-REQUIRED-LAYOUT-001`

- **状态**：`merged`
- **Owner**：`/root`
- **Base SHA**：`76e87cae68a5572930d9c0be369e364041fb4c73`
- **功能提交 / PR**：`f2e723a4ebe114290adc02cddadf01ef3c8973c5` / [#1810](https://github.com/Stevenjxie/cretas/pull/1810)
- **实现**：原料多包装规格已贯通采购、收货入库与跨仓调拨；交易行保留包装规格、单位、基本单位和换算系数快照，库存批次、调拨库存与生产事件只使用原料基本单位。
- **入库任务闭环**：仅按已确认累计量判断完成；每个采购行已收大于等于计划时采购单变为 `COMPLETED` 并退出待入库。活动草稿量独立展示，不再把“草稿占用后可新增量为零”误报为已收齐。
- **少收关闭**：仓储可在无活动草稿时选择标准原因关闭剩余数量；界面显示计划、已确认和少收差额，后端记录原因、说明、操作人和时间，已确认库存不回滚，重复关闭幂等。
- **验收证据**：精确分支单一 fallback Maven 生命周期 12 个选择器 `48 passed`，其中 4 个真实 JPA Context 通过；可信 JAR SHA-256 `5b549c720f5296db817b4a11b7f7e092f7822db25fe278ea37ccea6f636bb1e6`。Web 目标 `9 passed`，`vue-tsc -b` 与 Vite production build 通过；PR tracked secret scan 通过。
- **业务写入审计**：仅本地代码、迁移映射和构建验证；生产业务写入为 0。
- **发布边界**：严格 `NOT_DEPLOYED`；合并代码不等于生产部署，后续部署需独立授权和 exact-main 发布门禁。

### `UX-OA-APPROVAL-CELL-VISUAL-20260726`

- **状态**：`review`
- **Owner**：`/root`
- **Base SHA**：`e8c41c349a37ad6ac3e81e2cb7665c918023fdc2`
- **PR**：[#1802](https://github.com/Stevenjxie/cretas/pull/1802)
- **修正**：移除审批画布遗留的大圆形开始节点、彩色大标题审批节点和旋转菱形条件节点。开始/结束改为 136px 白底胶囊；审批、条件、并行、汇聚、通知统一为 208px 紧凑业务卡片，标题、节点类型、业务内容与辅助信息分层展示。
- **溢出控制**：审批角色、指定审批人和通知对象使用固定宽度与省略显示，悬停可查看完整内容，不再因长用户名撑宽 Cell。
- **画布适配**：取消无上限的初始化自动放大，首次/加载/自动布局使用最高 1.1 倍并为顶部文字工具栏和底部 AI 留出安全区；用户仍可手动放大到 1.8 倍。
- **验证**：审批编辑器目标 Vitest `54 passed`，`npx vue-tsc -b --pretty false` 通过；本地 Mock API 浏览器实测 5 个 Cell、5 条连线、无 Cell 裁切、无工具栏遮挡且 `scrollWidth == clientWidth`；`git diff --check` 通过。
- **边界**：未修改审批业务逻辑、API、数据库或生产数据；严格 `NOT_DEPLOYED`。

### `UX-OA-APPROVAL-BUSINESS-CANVAS-20260726`

- **状态**：`review`
- **Owner**：`/root`
- **Base SHA**：`76e87cae68a5572930d9c0be369e364041fb4c73`
- **PR**：[#1802](https://github.com/Stevenjxie/cretas/pull/1802)
- **实现**：系统设置新增审批业务总览，按采购、销售、生产、仓储等业务展示审批状态；未配置活动流程时明确为“无需审批”。业务入口直达对应审批画布，普通用户不再进入通用 Canvas 模块树或 JSON/差异确认界面。
- **画布 UX**：所有主要操作改为中文文字按钮；支持 Cell 新增、拖动、拉线、多选、删除、撤销/重做、缩放、适应画布与自动布局。属性面板按基础配置和折叠的高级配置分层，审批角色、指定审批人和部门均来自目录选择器。
- **AI**：AI 对话固定在画布底部，自然语言结果直接作用于当前本地草稿，仅显示简洁完成/失败消息，不展示 JSON、变更明细或二次确认；保存、发布仍使用既有显式操作与后端校验。
- **兼容与连续性**：已发布版本禁止原地修改；克隆为独立草稿后编辑。发布新版本只影响新审批实例，在途实例继续固定原 Workflow ID。停用后该业务的新单据无需审批，历史与审计记录不变。
- **验证**：Java 目标测试 `27 passed`；Web 目标 Vitest `62 passed`；`npx vue-tsc -b --pretty false`、`npm run build`、`git diff --check` 通过；本地 Mock API 浏览器验收覆盖审批业务总览、专用画布、AI 直改、Cell 操作、属性折叠、键盘操作和 1366×768 无横向溢出。
- **门禁**：未修改 Entity、Repository、JPQL 或数据库结构，不触发 JPA Repository 启动门禁；未写生产数据。
- **发布状态**：`NOT_DEPLOYED`，本任务仅提交并更新 PR，未合并、未生产部署。

### `DOC-QC-LABEL-SOP-20260726-001`

- **状态**：`merged`
- **Owner**：`/root`
- **Base SHA**：`62c08252d5a0927bc99627c2a7853b9999a30eed`
- **PR**：[#1782](https://github.com/Stevenjxie/cretas/pull/1782)
- **范围**：QC 包装标签拍检 UX Flow 规格、静态 HTML 操作指南、当前 RN Expo Web 手机视口截图，以及 AI Assist `/lsmsop/qcsop/` 静态部署。
- **交付物**：
  - `docs/specs/qc-label-inspection-sop.md`
  - `docs/manual/qc-label-inspection-sop.html`
  - `docs/manual/qcsop-assets/**`
- **验收证据**：
  - 六扇门 F006 QC 账号以 `quality_inspector` 角色登录并进入“质检工作台”。
  - 截图覆盖启动登录、账号表单、标签拍检入口、批次信息、SKU 搜索、照片预览/提交和等待人工审核。
  - F006 表单仅预填 `QC-GUIDE-SAMPLE` 并上传照片预览，没有点击提交；首页在途字段在浏览器 DOM 中脱敏，等待审核截图只展示裁切后的通用状态区域。
  - 390px 与 1440px 视口均无横向溢出；7 张内容截图均加载成功；截图放大和 Esc 关闭通过。
  - HTML 未检出测试用户名、密码、Bearer Token 或 Access Token；设置 `noindex,nofollow`。
  - `git diff --check` 通过。
- **业务写入审计**：状态截图验收创建 1 个明确命名的 F001 演示任务 `8530fc7b-114a-4bbe-9b87-8ad93c0802db`（批次 `QC-GUIDE-DEMO`、1 张照片、`NEEDS_REVIEW`、8 个 AI 候选）；未做人工审核、纠错或清理。除此以外无生产业务写入。
- **发布边界**：合并后从 exact `origin/main` 取静态制品，原子发布到 `139:/www/wwwroot/lsmsop/qcsop/`，以 `nginx -t`、公网 HTTP 200 和本地/线上 SHA-256 一致为完成证据。

### `BUG-F006-PURCHASE-ORDER-APPROVAL-ROUTING-001`

- **状态**：`merged`
- **Owner**：`/root`
- **原始提交**：`c56c1b4f5`；重放提交：`6a9ec8daa`
- **PR / main 合并提交**：[#1803](https://github.com/Stevenjxie/cretas/pull/1803) / `4b088aa70a6d9cd0cb61e9cbfe54dc36f8038c6d`
- **实际范围**：
  - `backend/java/cretas-api/src/main/java/com/cretas/aims/service/inventory/impl/PurchaseServiceImpl.java`
  - `backend/java/cretas-api/src/test/java/com/cretas/aims/service/inventory/impl/PurchaseServiceOaSubmissionTest.java`
- **行为结论**：
  - 只有实例发起人同时被当前 Canvas 审批节点的 `approverUserIds` 明确点名时，才允许该发起人自审。
  - `actorRole` 不参与自审豁免判断；仅角色匹配但未明确点名时继续返回 `PURCHASE_SELF_APPROVAL_FORBIDDEN`。
  - 订单、审批实例和绑定 Workflow 均按 `factoryId` 与业务身份读取；终态重放在任何自审或 transition 逻辑前纯读返回。
  - 获准的审批仍调用既有 `WorkflowEngineService.transitionNode`，由引擎写入节点、人员、角色、动作、备注和耗时审计历史。
- **验收证据**：
  - `c56c1b4f5`、`6a9ec8daa`、`4b088aa70` 的 stable patch-id 均为 `3b0d51acb088a0ed70ca0483c27f7a4d1de6aeb3`，证明原始、重放及 squash 合并补丁等价。
  - 在 exact `origin/main@4b088aa70` 运行 `mvn "-Dtest=PurchaseServiceOaSubmissionTest" test`：`14 passed`，`0 failures`，`0 errors`。
  - PR tracked secret scan 通过；`git diff --check 4b088aa70^ 4b088aa70` 通过。
  - 未修改 Entity、Repository、JPQL 或数据库结构，不触发 JPA Repository 启动门禁。
- **业务写入审计**：仅源码审查与本地测试，生产采购单和审批实例写入均为 0。
- **发布状态**：在后续已获授权的统一发布中随 exact `origin/main@4b088aa70` 一并部署；其 `PurchaseServiceOaSubmissionTest` 与本次物料换算测试在同一最终 JAR 生命周期共 `72 passed`，生产业务数据写入为 0。

### `BUG-F006-MATERIAL-PACKAGING-CONVERSION-001`

- **状态**：`merged`
- **Owner**：`/root`
- **Base SHA**：`a39e25717059b742a56a1b8e01286addbd95e33f`
- **功能提交 / PR / main 合并提交**：`9ffd4365596e22e8dadab3f45e5d36acae5cbe98` / [#1801](https://github.com/Stevenjxie/cretas/pull/1801) / `98367382ddbb5f0997c6871db9b168101fb71406`
- **实际范围**：
  - 原料、辅料和包装材料均可配置采购包装层级及“1 箱 = N kg”等换算关系。
  - 显式产品单位换算不存在路径时，`UnitContractService` 回退使用物料包装层级；显式换算仍优先。
  - 保留采购订单换算快照和库存基本单位真值，并增加基本单位一致性、重复层级和不完整层级校验。
  - Web 物料类型页面增加可选的采购与库存单位换算配置。
- **验收证据**：
  - 候选提交 Java 目标测试 `58 passed`；Web 目标测试 `7 passed`；`npm run build:check` 与 `git diff --check` 通过。
  - 发布期间 `main` 合入 #1803，故在 exact `origin/main@4b088aa70` 重新执行单一 Maven 生命周期，覆盖本任务与并发采购审批改动：`72 passed`，JAR SHA-256 `830233a1a547396059c44097b34d70677c822dd46fab5b1cfd74ae5b4c83f78c`。
  - Web 复用相同 `web-admin` tree 的可信制品，archive SHA-256 `3c8492bbf57636dd83406e084f06ea1de6c22051de01aa761e8bdff71b7ad6a9`。
  - PR tracked secret scan 通过；未修改 Entity、Repository、JPQL 或数据库结构，不触发 JPA Repository 启动门禁。
- **生产发布**：
  - 统一发布回执：`cretas-1785056733-1742.json`，最终状态 `deployed`，发布提交 `4b088aa70a6d9cd0cb61e9cbfe54dc36f8038c6d`。
  - Java 蓝绿切换后 active 为 `cretas-backend` / `10010`，5/5 切流后观察通过，网关和直连健康均为 `UP`。
  - Web HTTP 200；local/server/gateway HTTP/public HTTPS 四方 `index.html` SHA-256 均为 `2f13d31d0e672b06ac2041d18abb80cff4389a03ac56a2b97d2f5ab7c69171c7`。
  - 后续 `origin/main@0a1e8c2f7` 仅变更调度文档，Java/Web tree 与已部署提交一致。
- **业务写入审计**：本次仅发布代码和静态 Web 制品；生产业务数据写入为 0，未执行写入型业务 E2E。

### `ENH-LABEL-QC-MOBILE-REVIEW-20260726`

- **状态**：`merged`
- **Owner**：`/root`
- **Base SHA**：`f2e1a5f9a23cb5ce7a968fd3512830e965b93735`
- **功能提交 / PR**：`890f2ba04338a60a60773848cfce58a0bb107a9a` / [#1812](https://github.com/Stevenjxie/cretas/pull/1812)
- **实际范围**：
  - RN 质检员端新增标签拍检任务队列、逐张人工审核和提交状态回流。
  - AI 疑点必须逐一确认或拒绝；拒绝后从照片移除框，但以原 annotation ID 和 `NO_DEFECT` 保存训练真值。
  - 支持点击照片补人工框、移动/缩放/删除框、照片缩放/平移、照片级整图结论及未完成照片循环回补。
- **验收证据**：
  - RN 目标 Jest：2 suites、`6 passed`；ESLint 目标文件 quiet 检查通过。
  - 390×844 Expo Web 手机视口实操覆盖队列、固定当前操作、AI 确认/拒绝、点击补框、框移动/缩放、照片缩放/平移、4 张照片完成和最终提交按钮。
  - 浏览器验收标签质检业务写入为 0。
- **发布边界**：仅合并代码，严格 `NOT_DEPLOYED`；未发布 OTA、未构建或分发 APK，生产业务数据写入为 0。

### `ENH-LABEL-QC-WEB-REVIEW-20260726`

- **状态**：`merged`
- **Owner**：`/root`
- **Base SHA**：`890f2ba04338a60a60773848cfce58a0bb107a9a`
- **功能提交 / PR**：`6362d7fd873ed9d754f631615623e64d7b0dd6c4` / [#1812](https://github.com/Stevenjxie/cretas/pull/1812)
- **实际范围**：
  - Web 审核抽屉重构为左侧照片队列、中央可缩放大图、右侧固定当前操作和底部固定导航的桌面质检工作台。
  - AI 疑点不再默认接受；支持显式确认/拒绝、人工点图补框、框移动/缩放/删除、整图结论和未完成照片循环回补。
  - Web 与 RN 源码保持隔离，仅共享现有 Label QC 后端请求契约。
- **验收证据**：
  - Web 目标 Vitest `6 passed`；`npm run build:check` 的 Vue 类型检查和 Vite production build 通过。
  - 1440×900 与 1024×768 浏览器实操均证明三栏工作台和底部导航完整可见；AI 拒绝后框移除、点击补框、框移动/缩放、照片放大、4 张照片完成和最终提交按钮均通过。
  - 浏览器验收无控制台错误，标签质检业务写入为 0。
- **发布边界**：仅合并代码，严格 `NOT_DEPLOYED`；未发布 Web 制品，生产业务数据写入为 0。

### `ARCH-OA-CANVAS-RUNTIME-CUTOVER-20260726`

- **状态**：`merged`
- **Owner**：`/root`
- **Base SHA**：`6d7dba97ede29249a043ed052196aacd15b4d47e`
- **功能提交 / PR**：`1e9b25c11`、`6cb69c6a2`、`e661150b3` / [#1820](https://github.com/Stevenjxie/cretas/pull/1820)
- **实际范围**：
  - 新审批请求统一解析 published + enabled Canvas Workflow；无审批明确直通，旧平面配置仍启用时 fail-closed 并要求迁移。
  - 采购、销售、调拨、库存调整及餐饮 AI 审核接入持久化 Canvas 实例和业务状态投影；未接入业务阻止发布，避免假启用。
  - 实例固定稳定定义摘要；兼容切换前摘要，在途实例可在版本停用或归档后继续按原图运行。
  - 多 active、草稿启用和旧配置写入均有明确状态机门禁；Web 业务目录展示启用、无需审批、待迁移、未接入和版本冲突。
- **验收证据**：后端范围测试累计 `101 passed`（最终受影响复验 `37/37`）；Web 目录测试 `7/7`、`vue-tsc`、production build、tracked secret scan 与 `git diff --check` 通过。
- **数据与发布边界**：未修改 Entity、Repository、JPQL 或数据库结构；无生产业务写入；仅合并代码，严格 `NOT_DEPLOYED`。
