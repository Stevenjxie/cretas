# OA 两问题修复：发起人自审 + 审批内容可读且可操作

**日期**: 2026-08-01
**来源**: Steve 截图（六膳门 → 个人 OA → 待我审批）+ `HANDOFF-2026-08-01-liushanmen-rebuild.md` §2b
**基线**: `origin/main` = `f327994223`

---

## 1. 问题与根因

### 问题 1：发起人不能审批自己的单 —— 两层叠加的结构性死锁

**数据层**（prod 实测，workflow `415e61d3-fe95-402a-8ddc-0855fa3a3f69`）：

```json
{ "id": "admin_approval", "type": "approval",
  "config": { "approverRoles": ["factory_super_admin"],
              "approverUserIds": [1638],
              "requiredApprovers": 1 } }
```

**数据层第二条**：六膳门 30 个活跃用户中 `factory_super_admin` **只有 1638 一个**（`liushanmen_admin`），
而它正是 `SO-20260801-0001` 的发起人。配置不只是「角色范围恰好剩一人」，还**直接点名** `approverUserIds:[1638]`。

**代码层**（`SalesServiceImpl.java:1125`）：

```java
if (actorId != null && actorId.equals(instance.getInitiatedBy())) {
    throw new BusinessException(403, "发起人不能审批自己的销售订单")
        .withHint("请由当前 OA 节点授权的其他审批人处理");
}
```

**叠加结果**：唯一授权审批人 = 发起人 = 1638，而代码要求两者不同 → 该单在 OA 里**永远批不过去**，
且提示指向一个**不存在的人**。

🔴 **关键发现：这个闸有三处承载，其中一处已经修过但没同步。**

| 文件 | 行 | 现状 |
|---|---|---|
| `PurchaseServiceImpl.java` | 679 | ✅ **已有例外** `&& !isExplicitCurrentNodeApprover(factoryId, instance, actorId)` |
| `SalesServiceImpl.java` | 1125 | ❌ 无例外 |
| `TransferServiceImpl.java` | 687 | ❌ 无例外 |

`isExplicitCurrentNodeApprover` 是 `PurchaseServiceImpl` 的 **private** 方法（852 行），全仓仅此一份 —— 所以另两处
无法复用，只能各自复制或干脆没有。这正是本仓反复出现的「一个闸多处承载，改一处静默失效」。

### 问题 2：审批内容看不懂

截图那条：业务类型「未知状态（BUDGET）」/ 单据 `BUDGET b67922a2-…` / 申请人空白 / 只有禁用的「只读」按钮。
三个独立成因：

1. **业务类型** — 🔴 **不是「漏了 BUDGET 一个码」，是前端只覆盖了权威表 36 个里的 4 个。**

   后端 `DecisionTypeMetadataRegistry` 类注释自称「32 个 DecisionType 的中文名/分类/默认角色/moduleCode
   **单一来源**」，实测 **36 个 `moduleCode`，每个都自带 `chineseName`**（`BUDGET` → 「预算审批」）。
   而前端 `web-admin/src/views/workflow/pending.vue:15` 的 `MODULE_LABELS` **手抄了 4 个**
   （`PURCHASE_ORDER` / `SALES_ORDER` / `INVENTORY_TRANSFER` / `INVENTORY_ADJUSTMENT`）。

   → 除 BUDGET 外**还有约 30 个码**会同样显示成「未知状态（X）」，只是还没人点到。
   这是本仓反复出现的「私有表遮蔽权威表」：权威在后端，前端私抄一份然后漂。
   ⚠️ 连兜底文案也错了：它是**业务类型**，却被说成「未知**状态**」。

   ⚠️ 另注：`BUDGET` 这一个 moduleCode 承载了多种业务 —— 其 `description` 写着
   「年度/季度/月度预算 + 超预算授权 + **期间结账审批**」。所以泛称「预算审批」虽比 UUID 好，
   但对截图那条（`entityType=ACCOUNTING_PERIOD`）而言仍不够准。
2. **单据只有 UUID** — `WorkflowInstanceController` 的 `businessSummary` hydrate **只覆盖
   `PURCHASE_ORDER` 和 `SALES_ORDER`**，其余 module 一律 fallback 成 `moduleCode + businessEntityId`。
3. **申请人空白不是 bug** — prod 实测该实例 `initiated_by` 就是 **NULL**，
   `initiated_at = 2026-08-01 02:00:00`，context `{"year":2026,"month":7,"entityType":"ACCOUNTING_PERIOD"}`
   —— 它是**定时任务自动发起的月度会计期间结账审批**，本来就没有人类发起人。
   → 正确修法是显示「系统自动发起」，**不是**去补一个不存在的申请人。
4. **「只读」标签忠实反映了现状** — `executeDomainAction` 按 moduleCode 分发，**BUDGET 没有分支**；
   前端 `ACTIONABLE_MODULE_CODES` 因此也不含它。

---

## 2. 🔒 红线声明（实现前必读）

「让 BUDGET 真能审批」= **在待办列表点「通过」就执行月度关账**。会计期间状态机：

```
OPEN → PENDING_CLOSE（requestClose，同时启动 BUDGET OA 实例）
     → CLOSED（confirmClose）→ LOCKED
```

`confirmClose` 的后果（`AccountingPeriodServiceImpl`）：
- 期间 → `CLOSED`
- 触发 `InventoryLedgerSnapshotService#createForPeriod` 建库存台账快照
- 凭证写入进入 **20 天调整窗口**，逾期硬锁 `LOCKED`
- 仅 `CLOSED` 可反结账

当前 OA 实例是**孤儿**（fail-open 设计，代码注释明写：「期间结账是合规级业务，不能因 workflow 没配就阻塞。
requestClose 仍前进到 PENDING_CLOSE，由 finance director 手工 confirmClose 推进」）。

**Steve 在知悉上述后果后仍拍板接上，但要求加二次确认。** 本 spec 按此执行，
实现与验收中所有涉及关账的部分标 🔒，报告时单独列出。

---

## 3. 设计

### A. 自审例外统一（后端）

**新增** `service/workflow/SelfApprovalPolicy.java` —— 单一职责、可独立测试：

```java
public boolean allowsSelfApproval(String factoryId,
                                  ApprovalWorkflowInstance instance,
                                  Long actorId,
                                  String actorRole) {
    return isExplicitCurrentNodeApprover(factoryId, instance, actorId)   // ① OA 节点显式点名
        || FACTORY_SUPER_ADMIN.equals(actorRole);                        // ② 工厂超管
}
```

- `isExplicitCurrentNodeApprover` 从 `PurchaseServiceImpl:852` **移动**到此（不是复制），
  `PurchaseServiceImpl` 改为委托。
- 三处调用点统一为：
  ```java
  if (actorId != null && actorId.equals(instance.getInitiatedBy())
          && !selfApprovalPolicy.allowsSelfApproval(factoryId, instance, actorId, actorRole)) {
      throw new BusinessException(403, "发起人不能审批自己的<单据名>") ...
  }
  ```
- 三处 `.withHint(...)` 统一为采购单现有的那句（它已经提到「或在 Canvas 中明确将发起人配置为该节点审批人」，
  信息量更大）。

**行为变化面**：
- 采购单：**放宽**（原本只认①，现在①或②）
- 销售单 / 调拨单：**从无到有**（原本无条件禁止）
- 六膳门 `SO-20260801-0001` 因 ① 和 ② 同时成立而立即可自审

### B. 待办展示（后端 DTO + 前端）

**后端**
1. `WorkflowInstancePendingDTO` 新增 `boolean systemInitiated`，
   赋值判据 **`inst.getInitiatedBy() == null`**。
   ⚠️ 不能用「`initiatedByUsername` 为空」当判据 —— 用户被删也会为空，那会把「查不到人」误报成「系统发起」。
2. `businessSummary` hydrate 新增 BUDGET 分支：批量查 `accounting_periods`（避免 1+N，与现有 PO/SO 写法一致）
   → `"2026 年 7 月 会计期间"`。查不到时 fallback 保持现状。

3. 🔴 **DTO 新增 `moduleLabel`，取自 `DecisionTypeMetadataRegistry`** —— 不让前端再维护第二份表。
   - 默认取该 moduleCode 的 `chineseName`
   - **细化**：`BUDGET` 且 `context_json.entityType == "ACCOUNTING_PERIOD"` 时返回「会计期间结账」，
     而非泛称「预算审批」（该 moduleCode 一码多用，见 §1）
   - 取不到时 `moduleLabel` 留空，由前端兜底 —— **后端不编造**

**前端**（`web-admin/src/views/workflow/pending.vue`）
4. 业务类型列改用 `row.moduleLabel || enumLabel(row.moduleCode, MODULE_LABELS)`。
   `MODULE_LABELS` **降级为纯兜底**（后端没给时才用），不再是事实来源；
   注释写明「权威表在后端 `DecisionTypeMetadataRegistry`，此处只是离线兜底，别在这里加新码」。
5. 申请人列：`row.systemInitiated ? '系统自动发起' : (row.initiatedByUsername || '—')`。

### C. BUDGET 接入 OA 操作 🔒

**后端**
1. `AccountingPeriodService` 新增 `applyWorkflowAction(factoryId, periodId, action, userId, notes)`：
   - `APPROVE` → 复用现有 `confirmClose(...)`（含快照，不另写一套）
   - `REJECT` → 期间回 **`OPEN`**（撤销本次 `requestClose`；与「驳回必须填原因」配套）
   - 幂等：`confirmClose` 已有「already CLOSED 幂等命中」，`REJECT` 同样要对已 OPEN 幂等
2. `WorkflowInstanceController.executeDomainAction` 加 BUDGET 分支，委托上面的方法。
   走**已有的** `/{instanceId}/actions` 统一入口 + `oaActionIdempotencyService`，**不新建第二套状态机**。

**前端**
3. `ACTIONABLE_MODULE_CODES` 加 `'BUDGET'`。
4. APPROVE 二次确认文案对 BUDGET **特化**（复用已有的 `ElMessageBox.confirm`，不新增弹窗层）：
   > 这将关闭「2026 年 7 月」账期，并生成库存台账快照。
   > 关账后凭证进入 20 天调整窗口，逾期将硬锁。

   参照同文件 `INVENTORY_TRANSFER` 那段既有先例（审批后提示「还差最后一步」）的写法风格。

---

## 4. 测试策略

**必须先红后绿**：每条都要先证明它在改动前是红的。

| # | 测试 | 防的是 |
|---|---|---|
| T1 | **横跨三处的自审语义一致性测试** —— 参数化 销售/采购/调拨，断言「点名可自审」「admin 可自审」「既非点名又非 admin 则 403」**三处结果相同** | 「一个闸多处承载」再漂（本仓有前科：采购已修而另两处没跟） |
| T2 | `SelfApprovalPolicy` 单测：点名命中 / 角色命中 / 两者都不中 / instance 无 currentNode | 例外逻辑本身 |
| T3 🔒 | BUDGET APPROVE → 期间 `CLOSED` 且 `createForPeriod` 被调用一次 | 关账链路真接上了 |
| T4 🔒 | BUDGET REJECT → 期间回 `OPEN`；重复 REJECT 幂等不报错 | 驳回语义 |
| T5 | `businessSummary` BUDGET 分支产出可读编号；查不到期间时 fallback 不炸 | UUID 甩给用户 |
| T6 | `systemInitiated` 在 `initiatedBy=null` 时为 true；**在「用户已删导致 username 为空」时为 false** | 判据选错 |
| T7 | **权威表全覆盖契约**（后端）：遍历 `DecisionTypeMetadataRegistry` 全部 36 个 moduleCode，断言每个都能解析出非空 `moduleLabel` 且**不含「未知」字样** | 下一个码再漏（这次是 32/36 漏网） |
| T8 | `BUDGET` + `entityType=ACCOUNTING_PERIOD` → `moduleLabel` 为「会计期间结账」而非「预算审批」 | 一码多用被泛称糊掉 |
| T9 | 前端：后端给了 `moduleLabel` 时优先用它；没给时才落 `MODULE_LABELS` 兜底 | 前端又变成第二事实来源 |

⚠️ 跑 Java 测试用 `mvn clean test`，不要只 `mvn test` —— maven 增量编译不重编会给假红/假绿。

---

## 5. 影响面与风险

| 项 | 影响 |
|---|---|
| 🔒 BUDGET 接入 | 所有工厂的会计期间结账审批从「只读」变成「可一键关账」。**这是可见的新行为**，不是静默修 bug |
| 自审放宽（采购单） | 原本只有「点名」可自审，现在 `factory_super_admin` 也可 —— 削弱该角色单据的双人复核 |
| 自审新增（销售/调拨） | 原本无条件禁止，现在两种情况可自审 |
| 六膳门 | `SO-20260801-0001` 已由 Steve 手工放行，不受影响；但其 BUDGET 实例（2026-07）会变成可操作 |

**不做**（明确排除）：
- 不动 `approval_workflows.nodes_json`（会让在飞实例撞 definition-digest 校验 409）
- 不收紧「无 workflow 时手工 confirmClose」这条 fail-open 通道（属既有合规设计）
- 不碰凭证重号 `V-2026-0011`（独立问题）
- 不碰销售财审 fan-out 丢写（另一 session 在做，其禁改区正是本 spec 要改的 `SalesServiceImpl` OA 自审校验 —— 两边已隔离）

---

## 6. 交付：拆两个 PR

🔴 **不合成一个 PR** —— A 是 Steve 眼下的真实痛点且不碰财务，C 是红线需要他单独决定部署。
捆在一起会让 A 被 C 的审查节奏拖住。

| PR | 内容 | 部署 |
|---|---|---|
| **PR-1** | §3.A 自审例外统一（3 处 + `SelfApprovalPolicy`）+ T1/T2 | 三条齐了可自合自部（Steve 既有授权） |
| **PR-2** 🔒 | §3.B 展示（权威表 + systemInitiated + 可读编号）+ §3.C BUDGET 接入 + T3–T9 | **实现 + 自测 + PR 后停下报告**，由 Steve 决定 |

B 归到 PR-2 是因为 `moduleLabel` / `businessSummary` 改的是同一个 DTO 与同一个 Controller，
与 C 天然同文件；拆开会造成两个 PR 改同一处，徒增冲突。

## 7. 交付约束

- worktree `codex/claude-oa-selfapproval-budget` off `origin/main`
- PR 前 `git diff origin/main...HEAD --stat` 确认无并发 session 夹带
- commit 用 `git commit -m "..." -- <显式路径>`；**新文件先 `git add`**（否则会被静默跳过）
- 🔒 关账相关部分：实现 + 自测 + PR 后**停下来报告**，由 Steve 决定是否部署
