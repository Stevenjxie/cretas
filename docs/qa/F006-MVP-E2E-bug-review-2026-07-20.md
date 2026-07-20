# F006 MVP E2E Bug Review — 2026-07-20

> 本文档按 Bug ID 持续追加和更新，不覆盖既有条目。生产部署必须获得用户单独明确授权。

## BUG-F006-M09-INV-UNIT-001

- **发现阶段**：M09 完成仓库确认及成品仓 → 物流仓调拨后，分仓库存查询回归。
- **发现时间**：2026-07-20。
- **页面/步骤**：Web Admin → 仓储管理 → 分仓库存查询 → 选择 WH-FG/WH-LOG → 成品库存。
- **期望**：API/数据库继续保存 canonical unit；页面将 `box/case/slice` 显示为“盒/箱/片”，`g/kg` 保持不变。
- **实际**：成品单位列直接显示 canonical `box`；同组件原料单位列同样绕过共享展示转换。
- **业务影响**：非阻塞显示缺陷；库存数量、预留、仓位、批次、调拨和订单状态没有异常，但破坏全站单位展示契约一致性。
- **证据路径**：`D:\Temp\codex-clipboard-018e7590-5e5e-46fd-8082-32ab0cfe5430.png`；现场调拨 `TRF-20260720-0966` 已 `CONFIRMED`。
- **根因**：`web-admin/src/views/inventory/by-warehouse/index.vue` 的成品单位列使用 `prop="unit"` 直接渲染 API canonical 值；原料单位列也直接输出 `quantityUnit/unit`，均未调用共享 `displayUnit`。
- **修改文件**：
  - `web-admin/src/views/inventory/by-warehouse/index.vue`
  - `web-admin/src/views/inventory/by-warehouse/__tests__/inventoryUnitDisplay.spec.ts`
  - `docs/qa/F006-MVP-E2E-bug-review-2026-07-20.md`
- **测试**：`npx vitest run src/views/inventory/by-warehouse/__tests__/inventoryUnitDisplay.spec.ts src/utils/__tests__/unitPricing.spec.ts`，2 个测试文件、13 项断言全部通过；覆盖 `box/case/slice → 盒/箱/片` 与 `g/kg` 保持，并断言原料、成品单位列均通过 `displayUnit`。唯一一次 Vite 生产构建成功，4429 modules transformed；Web tree 为 `bc8c221d3e715f87aba69559cf7c5fa82effb25c`。
- **Commit/PR/main 状态**：实现 commit `ac560a82ba02d9d44c3c280674103cc1dd54395e`；PR [#1536](https://github.com/Stevenjxie/cretas/pull/1536)；main 状态 `MERGED_TO_MAIN`（本条目随该 PR 合入后生效）。
- **部署状态**：`NOT_DEPLOYED`。
- **回归状态**：待 main 合入并由用户明确授权部署后，测试 Chat 在同一 F006 现场刷新验证。
- **数据边界**：本修复只调整 Web 展示；不修改 API payload、数据库、库存、预留、仓位、批次、调拨或订单，不触碰 LIUSHANMEN。
