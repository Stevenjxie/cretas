# Google Sheet 反馈 —— 评估结论（待 owner 拍板的产品决策）

**日期**: 2026-08-15
**来源**: Steve 提供的 Google Sheet（tab: main / 工厂app）
**状态**: 已核实，**未实施**。按「产品决策先出长期方案 + mark，审核时再讨论」处理。

---

## 一、采购订单审批完就显示「财务已审核」

### 报告原文
> 采购订单审批直接变财务已审核

### 核实（prod 库 `cretas_prod_db`，2026-08-15）

`approval_workflows` 里 `decision_type LIKE '%PURCHASE%'` 的记录**只有 2 条**：

| factory_id | 名称 | 节点 | 有没有财务节点 |
|---|---|---|---|
| F006 | 采购订单默认审批 (¥30000 阈值) | 5 | ✅ 有 `approval_finance`；但 ≤¥30000 走 `end_auto` 直接结束 |
| LIUSHANMEN | PURCHASE_ORDER_APPROVAL - admin approval | 4 | ❌ **只有一个 `admin_approval`（角色 `factory_super_admin`）** |

其余工厂**一条采购审批链都没有**。

### 代码里三条路都会盖上「财务已审核」

`PurchaseServiceImpl`：

1. **没有配置审批链**（绝大多数工厂）—— `submitOrder` 第 549 行起：
   ```java
   if (instance.isEmpty()) {
       order.setStatus(PurchaseOrderStatus.FINANCE_APPROVED);
       order.setFinanceReviewedBy(initiatorUserId);   // ← 提交人给自己财务审核
       order.setFinanceReviewedAt(LocalDateTime.now());
   ```
2. **有链但没有财务节点**（LIUSHANMEN）—— `projectWorkflowState` 第 902 行：
   ```java
   case APPROVED -> PurchaseOrderStatus.FINANCE_APPROVED;
   ...
   order.setFinanceReviewedBy(actorId);   // ← 盖成最后那个业务审批人
   ```
   源码注释写着「配置的 PURCHASE_ORDER 链就是完整 OA 链，走到 APPROVED 即已含财务节点
   或其受审计的跳过」——**这个假设在 LIUSHANMEN 上不成立**。
3. **F006 的 ≤¥30000 分支**走 `end_auto`，同样落到 case APPROVED。

所以报告属实，而且**不是个别单据，是三条路都这样**。

### 为什么值得改：产品里真有财务审核模块

`web-admin/src/views/procurement/finance-review/`（列表 + 详情）是完整功能。
自动盖章意味着采购单**从来不会进入这个模块**——功能建了但没有输入。

### 建议的长期方案

问题的根在于 `FINANCE_APPROVED` 这一个状态同时承担了两件事：
**(a) 单据可以往下走**（收货门禁读它，`PurchaseServiceImpl:1679`）、
**(b) 财务已经看过了**。两件事必须拆开：

| | 现在 | 建议 |
|---|---|---|
| 可否收货 | 读 `status == FINANCE_APPROVED` | 不变（避免回归） |
| 审计痕迹 | 无论谁批都盖 `financeReviewedBy` | **只有财务节点真的执行过才盖**，否则留 null |
| 界面文案 | 一律显示「财务已审核」 | 无财务节点时显示「无需财务审核（本工厂未配置财务节点）」 |

**风险评估（已查证）**：`financeReviewedBy/At` 在 Java 侧**没有任何读取方**
（`grep getFinanceReviewedAt|getFinanceReviewedBy` → 0 命中），前端两处都是
`v-if="order.financeReviewedAt"` 守着的展示块。所以「不盖假章」这半步**不会卡住任何流程**，
可以先落；界面文案那半步需要 owner 确认口径。

⚠️ 反过来说，**不能**简单地把无财务节点的单据挡在 `APPROVED` 不放行 ——
收货门禁认的就是 `FINANCE_APPROVED`，那样会把 LIUSHANMEN 的采购收货整条堵死。

### 为什么没有直接改

这是审批控制的语义变更，属于产品决策（「本工厂不设财务审核」到底算不算审核通过）。
按当前指令：先评估、标记，审核时讨论。

---

## 二、质检看不了图片 —— 已定根因（**不是产品决策，是缺陷**）

照片在**三层各自被丢掉一次**，每层都返回成功：

| 层 | 代码 | 行为 |
|---|---|---|
| RN 提交 | `qualityInspectorApi.submitInspection` | payload 里**带着** `photos`，POST 出去 |
| 后端落库 | `ProcessingServiceImpl.submitInspection`（约 963-987 行） | 逐字段搬 `notes` / `customFields` / 计数，**从不碰 `photos`**。`QualityInspection` 实体也根本没有照片字段（只有 `notes` + `custom_fields`） |
| RN 读回 | `qualityInspectorApi.ts:190` | `photos: []` —— **硬编成空数组**，后端返什么都一样 |

于是 `QIRecordDetailScreen` 的 `{record.photos?.length > 0 && ...}` 永远不成立，
照片区从不渲染。用户看到的就是「质检看不了图片」。

附带一处：`QIRecordDetailScreen:169` 那个包住图片的 `TouchableOpacity` **没有 `onPress`** ——
即使有图，点了也不会放大。

### 建议改法：接上已有的附件子系统，不要给实体加照片列

`Attachment.EntityType` 里**已经有 `QUALITY_CHECK`**（`entity/Attachment.java:122`），
但全仓没有任何地方把它当附件归属用过 —— 机制建好了，没接上。
采购单详情已经在用同一套（`AttachmentList` / `AttachmentUploadButton`）。

1. `QIFormScreen` 提交成功拿到 `inspection.id` 后，把照片按
   `entityType=QUALITY_CHECK, entityId=<inspectionId>` 走附件上传
2. `QIRecordDetailScreen` 用 `AttachmentList` 替掉那段死代码
3. 顺手给图片加 `onPress` 放大

这样不需要改 `quality_inspections` 表结构，也和采购/收货的证据链保持一致口径。

---

## 三、其余待评估项

| 项 | 状态 |
|---|---|
| 采购订单新建 409 | 未核实（409 文案见 `PurchaseServiceImpl:519/523`，两条都带 hint，需要复现具体触发点） |
| 新建计划产品类型无法滑动 | 未核实（RN 端交互） |
| 个人 OA 模块 | 未核实 |

## 四、fool-proof 待办（本轮改动里留下的）

| 位置 | 现状 | 应改成 |
|---|---|---|
| 报工驳回原因 | 自由文本 | Rule 3：标准原因下拉 + 「其他」才展开文本框 |
| 换工种（`EmployeeProcessSegmentScreen`） | 让用户手输「工种编号」 | 工种选择器 |

---

## 三、本轮已直接修掉的（不属产品决策）

- **AI 追问管手机端操作员要 ID** → PR #2644。实测「牛腩排入库42件」被追问「请提供原材料类型ID」，
  而下一轮回答「55厂 牛腩排」就能走到 `WRITE_CONFIRM_REQUIRED` —— 后端本来就吃名称。
