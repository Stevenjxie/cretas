# R8: 双栈合并设计 — ProcessWorkReporting ↔ YieldReportController

> **状态**: 设计稿，待 Steve 拍板方案选项后派实现。
> **分类**: 🔒 架构红线，合并后须经 Opus 终审 + 从 main 部署。
> **追溯矩阵位置**: W2-R8（验证矩阵 verification-matrix.md 第四节决策点 1）

---

## 一、现状取证

### 1.1 两栈能力边界（代码一律读 origin/main）

#### 栈 A — YieldReportController 栈（新，SP1 起）

| 维度 | 细节 |
|---|---|
| **后端入口** | `YieldReportController` → `YieldReportServiceImpl` |
| **URL** | `POST /api/mobile/{fid}/production/batches/{batchId}/reports` |
| **RN 入口** | `YieldStepReportScreen` |
| **操作员入口** | `OperatorNavigator` → `OperatorAssignedProcessScreen` → `YieldStepReport` |
| **主管入口** | `WSHomeStackNavigator` → `ProcessTaskListScreen` / `ProcessTaskDetailScreen` / `YieldBatchSelectScreen` → `YieldStepReport` |
| **写哪些表** | `production_reports`（report_kind=INPUT/SEGMENT/OUTPUT）、`work_process_tasks`、`semi_finished_inventory`（批准后由 `WipInventoryService.postApprovedOutput` 过账）、`semi_finished_inventory_transactions`（目前 prod 为 0 行，WIP 只靠 SFI 表）、`batch_lineage_edges` |
| **三阶段报工** | 有（INPUT/SEGMENT/OUTPUT）；字段 `report_kind` 隔离，防误填 |
| **WIP 半成品台账** | 完整：产出写 SFI，消耗扣减，余额追踪，源 WIP 防呆校验 |
| **T-3 补录时效锁** | 有（`BackdateWindowValidator.assertWithinWindow`），业务日期补录窗口 T/T-1/T-2，T-3 起拒 |
| **多段工时** | 有（`LaborSegment`：start/end/headcount/processedQty） |
| **副产物** | 有（`Byproduct`：name/qty/unit 列表） |
| **照片标注** | 有（`PhotoAnnotation`：label chip + 自由文字备注，T161） |
| **超收防呆** | 有（`YieldLimitsDTO`，`/yield/limits` 端点，入参 dialog 打开即显边界） |
| **代报工** | 有（`targetWorkerId`，主管替工人提交） |
| **撤回** | 有（SP2，独立 Reversal 端点） |
| **出成率派生** | 有（`getYield`，按批次+工序汇总） |
| **WIP 可借余额** | 有（`listWip`，每道产出/已领/余额/状态列表） |

#### 栈 B — ProcessWorkReportingController 栈（旧）

| 维度 | 细节 |
|---|---|
| **后端入口** | `ProcessWorkReportingController` → `ProcessWorkReportingServiceImpl` |
| **URL** | `POST /api/mobile/{fid}/process-work-reporting/normal`（和 `/supplement`、`/{id}/reversal`） |
| **RN 入口** | `ProcessOperationScreen` |
| **导航路径** | `WSHomeStackNavigator` 注册了 `ProcessOperation`；**快捷操作「工序操作」quickActionsStore** 也指向此屏 |
| **Tutorial** | `tutorialStore` 的 Workshop Supervisor 引导第一步指向 `ProcessOperation`（"最常用的功能！"） |
| **写哪些表** | `production_reports`、`process_tasks`（completedQuantity/status）；**WIP 仅在批准时**（`postWipForApprovedReport`）有条件触发 — 要求报工记录带 `workProcessTaskId` 且 `wipPostingMode=APPROVAL`（`customFields` 字段），普通提交路径**不触发** |
| **三阶段报工** | 无（`report_kind` 永为 null） |
| **WIP 半成品台账** | **有条件**：仅在 APPROVED + `workProcessTaskId` 不为 null + `wipPostingMode=APPROVAL` 三条件均满足时过账；普通 `submitNormalReport` 路径不过账 |
| **T-3 补录时效锁** | **没有**（`BackdateWindowValidator` 未注入，`reportDate` 字段有值但不校验） |
| **超收防呆** | 没有（无 `/yield/limits` 调用，无 max 显示） |
| **代报工** | 有（主管 `targetWorkerId`，同逻辑） |
| **DTO 字段** | `ProcessWorkReportSubmitRequest`（`processTaskId` 必填，输出 `outputQuantity` 必填；`workProcessTaskId`/`batchId`/`sourceWipNo` 可选，作为与栈 A 的"桥接字段"） |

### 1.2 RN 入口拓扑总结

```
操作员 (operator 角色)
  → OperatorNavigator
      → OperatorAssignedProcessScreen          【唯一入口，只有栈A】
          → YieldStepReportScreen              【栈A】

Workshop Supervisor 角色
  → WSHomeStackNavigator
      → WSHomeScreen (快捷操作) → ProcessOperation   【栈B，quickActions默认第1项】
      → WSHomeScreen (任务列表) → ProcessTaskListScreen → YieldStepReport  【栈A】
      → WSHomeScreen (Tutorial 步骤1) → ProcessOperation  【栈B】
      → ProcessTaskDetailScreen → YieldStepReport  【栈A】
      → YieldBatchSelectScreen → YieldStepReport   【栈A】
```

**关键发现**：操作员（低技术素养，F006 现役）**只走栈A**。Workshop Supervisor 有两条路：快捷操作入口 `ProcessOperation` 走栈 B，任务列表/批次详情入口走栈 A。

### 1.3 Prod 数据盘底（F006，2026-06-10）

| 指标 | 数量 | 说明 |
|---|---|---|
| `production_reports` 总数 | 250 | F006 全量 |
| 有 `report_kind`（栈A） | 237 | MODE_1 + INPUT/SEGMENT/OUTPUT 三阶段 |
| 无 `report_kind` 但有 `process_task_id`（纯栈B） | 8 | **均创建于 2026-06-06 13:xx**，已 APPROVED，为一次集中复现操作，非日常业务 |
| 无 `report_kind` + 无 `process_task_id`（旧式） | 5 | 早期存量 |
| 近 7 天新增（栈A） | 211 | 全部正常三阶段 |
| 近 7 天新增（纯栈B） | 8 | 同上，一次集中操作 |
| `semi_finished_inventory` | 47 行 | 35 AVAILABLE + 12 DEPLETED，均为栈A产出 |
| `semi_finished_inventory_transactions` | 0 行 | WIP 台账 IN/OUT 流水尚未启用 |
| `work_process_tasks`（PENDING） | 40 | 当前栈A活跃任务 |
| `process_tasks`（PENDING） | 22 | 当前栈B活跃任务 |

**结论**：F006 **事实上已以栈A为主导**（97% 报工）。8 条纯栈B记录是一次集中复现写入，不代表操作员日常使用。但 22 个 PENDING `process_tasks` 表明仍有活跃 WS 路径可能触发栈B。

---

## 二、问题定义

两栈并行造成三类闭合缺口：

1. **半成品台账割裂**：栈B提交后不写 SFI（仅批准时有条件触发），导致出成率分母（投入量）无法完整统计。
2. **T-3 时效锁缺失**：栈B可提交任意历史日期报工，绕过六扇门客户硬约束。
3. **超收防呆缺失**：栈B无 `/yield/limits` 调用，操作员可无限超量提交。

---

## 三、方案对比

### 方案 A — RN 入口直切：把 ProcessOperationScreen 的报工按钮指向 YieldStepReportScreen（推荐）

**思路**：不动后端。只改 `ProcessOperationScreen` 的报工提交路径，将 `submitNormalReport` 调用替换为导航至 `YieldStepReportScreen`，并传入 `batchId` + `workProcessTaskId`（从当前选中的 `ProcessTask` 关联的 `WorkProcessTask` 查得）。后端 `ProcessWorkReportingController` 保留，只把已有 8 条数据的 `/normal` 端点加 **@Deprecated 注释**，不改签名，不下线（避免断路）。

**改动面**：
- `frontend/CretasFoodTrace/src/screens/processing/ProcessOperationScreen.tsx`：把提交按钮改为导航调用（约 20 行改动），并在 `selectedTask` 选中后通过 API `/api/mobile/{fid}/process-tasks/{taskId}` 拿到关联的 `workProcessTaskId`。
- `frontend/CretasFoodTrace/src/store/quickActionsStore.ts`：`process-operation` 条目保持 `screen: 'ProcessOperation'`（入口不变，操作员/主管不感知路由变化）。
- `tutorialStore.ts`：Tutorial 仍导航到 `ProcessOperation`；到达 ProcessOperation 后的报工按钮体验变好（进入三阶段屏幕），Tutorial 不需修改导航目标，但可选更新引导文案。

**工作量**：S（3-5 天，主要是 RN + 转换 workProcessTaskId 查询逻辑）。

**风险**：
- 中等：`ProcessTask` 与 `WorkProcessTask` 的 1:1 关联必须存在（通过 `WorkProcessTask.productionBatchId` + `processOrder` 关联），若某 `ProcessTask` 没有对应 `WorkProcessTask`（旧式任务），需回退策略（见下文）。
- 低：后端不改，零回归风险。

**迁移与回滚**：
- 无数据迁移。
- 回滚：git revert ProcessOperationScreen 改动，即刻生效（RN OTA 推送）。

**对 F006 现役操作员影响**：
- 操作员（operator 角色）**零感知**：其入口 `OperatorAssignedProcessScreen` → `YieldStepReportScreen` 本就是栈A，本方案不改此路径。
- Workshop Supervisor：快捷操作「工序操作」入口不变，打开后选工序、点报工后进入三阶段屏幕（原来是直接提交 1 个数字，现在进入更完整的三阶段流程）。用户体验**变好但改变了操作步骤**，需评估是否增加认知负担（参见防呆设计：更多 context 显示反而降低错误率）。

**验收指标**：
- 合并后 `production_reports` 所有新报工均有 `work_process_task_id`（可断言）。
- `process_tasks.PENDING` 数量 7 天内降至 0（自然消化）。
- 无新增纯栈B记录（grep `process_task_id IS NOT NULL AND work_process_task_id IS NULL` 监控）。

---

### 方案 B — 后端适配层：ProcessWorkReportingServiceImpl 内部转发到 YieldReportServiceImpl

**思路**：`ProcessWorkReportingController` 和 RN 入口均保持不变。在 `ProcessWorkReportingServiceImpl.submitNormalReport` 内部，当 `workProcessTaskId` 可以解析时，将调用转发给 `YieldReportService.submitReport`（构造 `YieldReportRequest` 并调用），保证经过同一校验链（T-3、超收、WIP）。

**改动面**：
- `ProcessWorkReportingServiceImpl`：在 `submitNormalReport` 加入转发分支（约 40-60 行）。
- 需要保证 `ProcessWorkReportSubmitRequest` → `YieldReportRequest` 的字段映射完整（`outputQuantity`/`workProcessTaskId`/`batchId`/`reportDate`/`photos`/`notes` 等）。

**工作量**：M（5-8 天，含字段映射、测试、T-3/超收回归验证）。

**风险**：
- 高：**循环依赖/事务传播风险**：两个 Service 在同一事务调用，若 `YieldReportService` 中有 `@Transactional(REQUIRES_NEW)` 边界，可能出现事务交叉。需仔细追踪 Spring 事务传播链。
- 中：字段映射遗漏（`report_kind`、`outputKind`、`laborSegments`、`byproducts` 等栈A独有字段在转发时为 null，可能导致出成率计算不完整）。
- 低：RN 端无变化，操作员零感知。

**迁移与回滚**：
- 无数据迁移。
- 回滚：revert `ProcessWorkReportingServiceImpl` 改动（后端重新部署约 2-3 min）。
- 回滚窗口：约 5 min（后端 deploy + 健康检查）。

**对 F006 现役操作员影响**：完全透明，RN 侧无变化。

**缺点**：把两条栈强行耦合进同一 Service，未来维护时需同时理解两套逻辑；且栈B屏幕依然存在，根因未治（WorkShop Supervisor 仍然在两个入口之间分裂体验）。

---

### 方案 C — 栈B全面下线：ProcessTask 体系废弃，统一到 WorkProcessTask 路径

**思路**：将所有 `process_tasks` PENDING 任务迁移到 `work_process_tasks`（按 `productTypeName + processOrder` 关联），然后下线 `ProcessWorkReportingController`（HTTP 404）、`ProcessOperationScreen`（移除注册），并修改快捷操作指向 `YieldBatchSelect`。

**改动面**：
- 一次性数据迁移脚本（`process_tasks` 22 条 PENDING → `work_process_tasks`）。
- `ProcessWorkReportingController` 端点全部返回 410 GONE + 提示语。
- `ProcessOperationScreen` 从导航器移除。
- `quickActionsStore`：`process-operation` 改为 `yield-report`。
- `tutorialStore`：Tutorial 流程重写。

**工作量**：L（10-15 天，含迁移脚本、测试、E2E、Tutorial 重写）。

**风险**：
- 高：**数据迁移失败或字段不对齐**导致 22 个 PENDING 任务丢失，F006 当天无法报工（prod 真客户在用）。
- 高：Tutorial 流程需完整测试，低技术素养操作员首次使用如果流程变化可能卡住。
- 中：需要验证所有依赖 `process_task_id` 的查询（报工列表、审批列表、工资计算）不回归。

**迁移与回滚**：
- 需要 Flyway 迁移脚本（数据迁移）+ 代码部署同步进行。
- 回滚复杂：下线后 RN 端已删除入口，需 OTA 重推才能恢复。
- **不推荐在 prod 真客户在用时执行。**

**对 F006 现役操作员影响**：
- Workshop Supervisor 「工序操作」入口消失，改为「逐道报工」。这是**最大的 UX 变化**：现在「工序操作」里报工只需输一个数字，改后进入三阶段屏幕。
- 根据 fool-proof-design 规范，此变化前必须有 `ux-flow` skill 评审（高风险 UX 路径改变）。

---

## 四、推荐方案及理由

**推荐方案 A（RN 入口直切）**，原因：

1. **最小改动面，零后端风险**：只改 RN 一个屏幕，后端不触碰。改动范围精确，E2E 验证范围小。
2. **操作员零感知**：`operator` 角色（F006 张权等低技术素养操作员）完全不受影响，其入口路径本就是栈A。
3. **渐进式**：后端栈B端点标记 @Deprecated 保留，已有 8 条历史数据不受影响，未来可按计划下线。
4. **向前兼容**：若某 `ProcessTask` 没有关联 `WorkProcessTask`（边缘情况），回退到旧栈B提交（安全网），不会断路。
5. **T-3 锁和超收防呆自动生效**：一旦报工路径进入 `YieldReportServiceImpl`，`BackdateWindowValidator` 和 `getLimits` 自动应用，无需额外工作。

**方案 A 的一个已知 gap**（需 Steve 拍板）：Workshop Supervisor 使用「工序操作」 → 报工后，将进入三阶段屏幕，UX 比现在「一个数字直接提交」复杂。这是**功能改进还是认知负担**，需要结合六扇门实际调度员（张权）使用习惯判断（参见 fool-proof-design 规范 Rule 2：context 显示降低错误率 vs Rule 3：简化输入）。

---

## 五、待 Steve 拍板的点

| # | 决策点 | 背景 | 影响 |
|---|---|---|---|
| **D1** | 选择方案 A / B / C？ | 见三方案对比，推荐 A | 实现工作量：S / M / L |
| **D2（A方案内）** | ProcessTask 没有关联 WorkProcessTask 的回退行为：(a) 拒绝报工+提示「请联系主管重新创建任务」；(b) 静默降级走旧栈B；(c) 方案A实施前先完成 ProcessTask → WorkProcessTask 补关联 | 22 个 PENDING ProcessTask 是否都有对应 WPT 需先核查 | 回退路径设计 |
| **D3** | Tutorial 「工序操作」引导文案是否需要在方案A实施后更新？（现在指向「最常用：签到+报产量」，改后流程变复杂） | tutorialStore 当前文案已引导到 ProcessOperation | 操作员首次使用体验 |
| **D4** | 后端 `ProcessWorkReportingController` 下线时间表：(a) 方案A上线后立刻下线（最彻底）；(b) 观察 2 周无新接入后下线；(c) 永久保留（@Deprecated 标注） | 8 条历史记录不影响功能，端点可安全下线 | 代码整洁度 vs 保守策略 |
| **D5** | 是否在合并前先确认 22 个 `process_tasks.PENDING` 与 `work_process_tasks` 的 1:1 关系已建立？（sprint 规划依赖） | 若缺关联，方案A需先执行 D2 分支 c | 实施顺序 |

---

## 六、验收设计

合并完成（方案A上线）后，以下断言须全部通过：

### 6.1 半成品台账一致性断言

```sql
-- 断言1: 合并后所有新报工必须有 work_process_task_id（栈A路径）
-- 运行条件: 合并部署 7 天后
SELECT COUNT(*) FROM production_reports
WHERE factory_id = 'F006'
  AND created_at > '<merge_deploy_date>'
  AND work_process_task_id IS NULL
  AND report_kind IS NOT NULL;
-- 期望: 0

-- 断言2: SFI 余额非负（仓库台账健康）
SELECT COUNT(*) FROM semi_finished_inventory
WHERE factory_id = 'F006' AND available_quantity < 0;
-- 期望: 0

-- 断言3: 出成率数据完整性 — 每个 OUTPUT 报工必须有对应 INPUT（同 work_process_task_id + batch_id）
SELECT wpt_id, COUNT(DISTINCT report_kind) as phase_count
FROM production_reports
WHERE factory_id = 'F006'
  AND report_kind IN ('INPUT','OUTPUT')
  AND work_process_task_id IS NOT NULL
GROUP BY work_process_task_id
HAVING COUNT(DISTINCT report_kind) = 1 AND MAX(report_kind) = 'OUTPUT';
-- 期望: 0（每个有 OUTPUT 的任务必须有对应的 INPUT）
```

### 6.2 E2E 闭合链（方案A实施后回归）

```
前置: Workshop Supervisor 登录 F006
步骤:
  1. 首页「工序操作」快捷入口 → ProcessOperationScreen 弹出
  2. 选择一个 PENDING 工序任务
  3. 点「报工」→ 期望跳转到 YieldStepReportScreen（NOT submitNormalReport 直接提交）
  4. 完成三阶段报工（INPUT → SEGMENT → OUTPUT）
  5. 在 web-admin 审批通过
  6. 查 SFI 台账：对应工序的 SFI 余额 > 0（WIP 已过账）
  7. 补录测试: 在 YieldStepReportScreen 尝试报 T-3 日期 → 期望 409 BACKDATE_WINDOW_EXCEEDED
  8. 超收防呆测试: 输入超过计划量 ×1.31 → 期望禁止提交（limits.remaining 限制）

回归对照:
  - 操作员路径（OperatorAssignedProcessScreen）功能不变（无回归）
  - 旧栈B历史报工记录仍可查看（不被隐藏）
```

### 6.3 监控快照（上线后 1 周）

每日运行如下查询，若 `pure_stackB_new` > 0 则告警：

```sql
SELECT COUNT(*) as pure_stackB_new
FROM production_reports
WHERE factory_id = 'F006'
  AND created_at > '<merge_deploy_date>'
  AND process_task_id IS NOT NULL
  AND work_process_task_id IS NULL
  AND report_kind IS NULL;
```

---

## 七、实现摘要（方案A确定后供实现者参考）

### 后端变更（仅标注，不动业务逻辑）

1. `ProcessWorkReportingController.submitNormalReport`：加 `@Deprecated` Javadoc 注释，保留端点不下线。
2. 无其他后端改动。

### RN 变更

**文件**: `frontend/CretasFoodTrace/src/screens/processing/ProcessOperationScreen.tsx`

核心逻辑：
```typescript
// 现在: handleSubmitReport 直接调用 processTaskApiClient.submitNormalReport(data)
// 改后: 改为导航到 YieldStepReportScreen

const handleReportPress = async () => {
  if (!selectedTask) return;
  // 查当前 ProcessTask 关联的 WorkProcessTask
  const wpt = await processTaskApiClient.getWorkProcessTask(selectedTask.id);
  if (!wpt) {
    // D2 回退策略（待 Steve 拍板）
    // Option a: Alert.alert('请联系主管重新创建任务')
    // Option b: fallback to submitNormalReport
    return;
  }
  navigation.navigate('YieldStepReport', {
    batchId: wpt.productionBatchId,
    batchNumber: selectedTask.batchNumber,
    assignedWorkProcessTaskId: wpt.id,
    assignedProcessOrder: wpt.processOrder,
  });
};
```

新 API 端点（栈A已有 `/process-tasks/{taskId}` 可返回关联 wptId）：验证 `processTaskApiClient.getProcessTask(taskId)` 响应中是否包含 `workProcessTaskId` 字段，若无则在 Java `ProcessTask` DTO 中补字段映射。

### Flyway 迁移

方案A无需 Flyway 迁移。

---

## 八、风险矩阵

| 风险 | 概率 | 影响 | 方案A下缓解措施 |
|---|---|---|---|
| ProcessTask 无对应 WorkProcessTask | 中（22 个 PENDING，关联完整性待核查）| 高（WS 无法报工） | 合并前先核查（D5），不全则先补关联 |
| YieldStepReportScreen 引入额外步骤导致主管反映「变麻烦」 | 中 | 低-中（体验下降，可通过 OTA 回滚） | 先灰度 1-2 位主管使用，收集反馈后再全量 |
| T-3 锁拦截已有补录习惯（主管之前可能补 T-3 以前） | 低 | 中（主管投诉） | 与张权确认当前有无 T-3 以前补录习惯；BackdateWindowValidator 的 maxDays 可调 |
| SFI 台账并发写（两个 WS 同一批次同时报） | 低 | 中 | 栈A已有 SELECT FOR UPDATE 悲观锁（WipInventoryServiceImpl R1）|

---

*文档生成时间: 2026-06-10*
*作者: R8 Design Subagent（Sonnet in-harness）*
*待 Steve 拍板 D1-D5 后由 organizer 派实现。*
