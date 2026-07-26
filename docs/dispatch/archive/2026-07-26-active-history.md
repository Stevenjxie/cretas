# Dispatch 归档 — 2026-07-26

## 已完成

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
