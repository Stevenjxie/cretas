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
  - 生产 QC 测试账号以 `quality_inspector` 角色登录并进入“质检工作台”。
  - 截图覆盖启动登录、账号表单、标签拍检入口、批次信息、SKU 搜索、照片预览/提交和等待人工审核。
  - 390px 与 1440px 视口均无横向溢出；7 张内容截图均加载成功；截图放大和 Esc 关闭通过。
  - HTML 未检出测试用户名、密码、Bearer Token 或 Access Token；设置 `noindex,nofollow`。
  - `git diff --check` 通过。
- **业务写入审计**：状态截图验收创建 1 个明确命名的 F001 演示任务 `8530fc7b-114a-4bbe-9b87-8ad93c0802db`（批次 `QC-GUIDE-DEMO`、1 张照片、`NEEDS_REVIEW`、8 个 AI 候选）；未做人工审核、纠错或清理。除此以外无生产业务写入。
- **发布边界**：合并后从 exact `origin/main` 取静态制品，原子发布到 `139:/www/wwwroot/lsmsop/qcsop/`，以 `nginx -t`、公网 HTTP 200 和本地/线上 SHA-256 一致为完成证据。
