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
- **发布边界**：代码已合并，严格 `NOT_DEPLOYED`；未经新的明确生产部署授权不发布。
