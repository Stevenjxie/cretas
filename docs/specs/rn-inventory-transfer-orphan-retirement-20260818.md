# RN 库存调拨原型孤儿退役

## 决策

删除 RN 的 `WHInventoryTransfer` 原型屏、导航注册和类型残留。保留后端 Transfer API、正式 Web 调拨流程、RN 调拨接收流程，以及库存列表现有的“请在网页端「库存 - 调拨」办理”提示。

证据：`origin/main` 中已没有任何真实 `navigate` 调用或快捷入口指向该屏，但导航栈仍注册它。原型提交逻辑只更新批次 `storageLocation`，不使用用户输入的调拨数量、不创建调拨单，也不进入 `TransferServiceImpl`；恢复状态或内部深链仍可能进入这条危险写路径。

## UX Flow Analysis（ux-flow 门控产出，不可删除）

### 用户画像

仓管员／一线操作员 — 主要使用 Android，技术素养和系统操作熟练度有限 — 需要依据真实仓库、批次和数量完成调拨，不能自行判断“显示成功但未生成调拨单”的系统矛盾。

### 用户旅程

| 步骤 | 用户看到 | 用户操作 | 期望结果 |
|------|---------|---------|---------|
| 1 | RN 库存列表/详情不显示伪调拨入口；行操作明确提示网页端办理 | 不进入原型屏 | 不会写入虚假库位或误以为数量已调拨 |
| 2 | Web 正式库存调拨流程 | 选择真实仓库、批次和数量并提交 | 请求进入后端 Transfer API，生成可追溯调拨记录 |
| 3 | 旧恢复状态或内部深链引用退役 route | 返回安全页面或 route 无法匹配 | 不再触发原型写路径 |

### 摩擦点清单

| # | 摩擦点描述 | 严重程度 | 来源规则 |
|---|-----------|---------|---------|
| F1 | 原型屏忽略调拨数量，只改批次库位 | HIGH | fool-proof Rule 1 / Rule 2 / Rule 4 |
| F2 | 页面显示“调拨成功”，但没有真实调拨单 | HIGH | fool-proof Rule 5 |
| F3 | 删除后移动端没有调拨写入口 | MED | fool-proof Rule 5 |

### 每个摩擦点的设计回应

- F1 → 删除原型屏、route 和导航类型，关闭恢复状态/内部深链入口。
- F2 → 保留正式 Web 调拨与后端 TransferService，不用批次库位更新冒充调拨。
- F3 → 保留库存行操作的明确下一步提示；在真实仓库、单位、数量和幂等契约完成前不恢复移动写入口。

## 范围与非目标

- 删除：`WHInventoryTransferScreen.tsx`、Stack 注册、ParamList 字段、旧 i18n 迁移脚本条目。
- 加固：源码契约测试同时断言文件、route、类型和迁移残留不存在。
- 不修改：后端 Entity/Repository/JPQL/Flyway、Web 调拨、RN `TransferReceiveScreen`、生产数据和生产部署。

## 验收

- `warehouseDeadEndsContract.test.ts` 通过。
- `npx tsc --noEmit --skipLibCheck` 通过。
- `git diff --check` 通过。
- `git grep WHInventoryTransfer` 只允许测试或历史说明中的退役断言，不得存在可执行 route/import/type。
