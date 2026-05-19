# Sprint 5 派工计划 (2026-05-19, based on Round 11+12+13 audit)

> **来源**: Round 11 §P.5 P0 剩 + Round 12 §G P0/P1 + Round 13 §15 new + Steve sign-off "6.5-7 月" (Round 11 3 月 → Round 12 6.5 月 → Round 13 7 月)
> **总工时**: ~60d nominal (Sprint 5 = ~6 周单人 / ~3 周双人 / ~1-1.5 周 8 并行 chat)
> **客户群**: F006 卤制品 (主) + 在谈食品厂 (P1) + 大客户场景 (F-TAX/数据权限)
> **Sprint 5 真目标**: 大客户 readiness (数电票/请购单/数据权限) + 工作流闭环 (personal view + ECN op model + linkno 11 类) + 整合断点 (生产→工资 trigger)

---

## §0 派工总览 (8 tracks + 1 pre-spike)

| Track | 主题 | 工时 | 客户群 | 并行 | 依赖 |
|---|---|---|---|---|---|
| **Z** | Pre-Sprint Spike + Verify (4 verify items) | 3d | 全部 | 单 chat | 无 (先做) |
| **A** | C-MENU-PERSONAL-VIEW (工作流 personal view + admin UI) | 6d | F006 | ✅ | Track-I Canvas 已 ship |
| **B** | F-TAX-DIRECT-1 (数电票税局直连) | 10d | 大客户硬需 | ✅ | 第三方 API (深圳数电) |
| **C** | Sprint 5 Quick Wins bundle (报价试算 + linkcounter + 打印 21+称重) | 12d | F006 + 通用 | ✅ | 无 |
| **D** | P-REQUISITION-1 (请购单 entity) | 5d | F006 + 在谈食品 | ✅ | Round 12 §B.2 X2 finding |
| **E** | M-WAGE-INTEGRATION-1 (生产→工资 trigger) | 5d | F006 (计件场景) | ✅ | H-WAGE 已 ship #833 etc |
| **F** | Customer + 辅助核算 bundle (21 tab 补 + Voucher 7 类) | 9d | 通用 + 大客户 | ✅ | F-VFLAG-1 ship 已有 |
| **G** | RBAC 数据权限维度 (P1 第 2 维) | 6d | 大客户 | ✅ | C-RBAC-1 ship 已有 |
| **H** | C-LINK-11TYPE-1 + Round 11 §P 收尾 (linkno + BOM-VER frontend + Canvas Phase 2) | 8d | F006 + 通用 | ✅ | C-APPROVAL Phase 1 ship |

**8 tracks 并行 = ~10-12 工作日完成 Sprint 5** (~2-3 周)

---

## §Z Pre-Sprint Spike (3d, organizer 亲做 OR 1 sister chat)

**目的**: 跑完 4 个 verify 任务 + 解 #538 blocker, 避免 Sprint 5 Tracks 跑空.

### Z-1: M1 三价对比 unblock #538 F006 test seed (1d)
- 解 issue #538 — F006 factory missing on test DB
- Run seed script to populate F006 test data
- 验证 M1 三价对比刷新逻辑通过 (Round 11 ⚠️ blocked)
- 提 PR

### Z-2: G12-10 采购需求总表 entry verify (0.5d)
- Round 12 §A.3 finding: HJ 采购模块 有 "采购需求总表" entry
- Grep Cretas main: 是否已实装 entry? Round 11 N31 S-MRP-1 PR #682 已 ship
- Verify entry 一致 — 加 frontend route 如缺
- 提 PR (1-2 行 + screenshot)

### Z-3: G12-2 vflag Cretas 2 维度 verify (0.5d)
- Round 13 §2 confirmed HJ vflag = checkstate × check_flag = 2 维
- Cretas `VoucherFlag.java` 现是单维 4-state
- Read `VoucherFlag.java` + `Voucher.java`
- Decision: 加 `voucher.abnormal_flag` boolean OR 保持单维 4-state?
- 写 spec, 决定后入 Track F (或 P3 backlog `F-VOUCHER-ANOMALY-1` 3d)

### Z-4: L13-6 C-PRT-CATEGORIES-21 verify (1d)
- Round 13 §13 finding: HJ 打印模板 21 分类 (含 称重/序列号/装箱/静态/供应商协同)
- Cretas Track-J PR # (per Agent E §I.2) 实际覆盖几个分类?
- Read Cretas `print/templates/` directory
- 列覆盖 vs 缺 list — 入 Track C Sprint 5 补 OR Sprint 6+

**Z 输出**: 4 PR + 1 vflag spec + 1 print categories coverage report.

---

## §A Track A — C-MENU-PERSONAL-VIEW (P0 6d, Sprint 5 W1)

**Steve sign-off**: P0 = ASAP, 工作流 personal view + admin UI 是 Round 12 §D X4 finding (HJ 6 sub-menu, Cretas 仅 2 ship). 必上.

### Brief (paste-ready for sister chat)

> Sprint 5 Track A — C-MENU-PERSONAL-VIEW (P0, 6d).
>
> **Context**: Round 12 audit §D X4 Agent finding: HJ 工作流 6 sub-menu (待处理 / 工作流处理 / 工作流设置 / 流转规则设置 / **我创建的工作流 / 我参与的工作流**). Cretas Sprint 3 Track-I + Canvas Phase 1 (PR #862) ship: 工作流设置 ✅ + 待处理 ✅, **缺 4 个**:
> - **我创建的工作流** (personal view, user 看自己起的流)
> - **我参与的工作流** (personal view, user 看 task assigned to me)
> - **工作流处理** (admin UI, super admin 看全 org workflow 状态)
> - **流转规则设置** (Round 12 §I.4 backend ship + UI 缺)
>
> **Scope**:
> 1. Backend: `WorkflowParticipantQueryService.findCreatedBy(userId)` + `findParticipatedBy(userId)` (Day 1-2)
> 2. Frontend: 2 personal Vue views `my-created.vue` + `my-participated.vue` 在 Canvas → 工作流 Tab 下 (Day 3-4)
> 3. Backend: `WorkflowAdminController.listAllRunning()` + 流转规则 CRUD (Day 5)
> 4. Frontend: 工作流处理 admin Vue view + 流转规则 list+dialog (Day 6)
>
> **依赖**: Track-I `ApprovalChainConfig` + Canvas Phase 1 框架 (已 ship)
>
> **Spec inputs**:
> - 33-doc §10 (workflow 6 sub-menu Round 13 verify)
> - 32-doc §D X4 (1726 lines RBAC + workflow deep)
> - 31-doc §I.4 (Round 11 C-WF-RULE-1 backend ship, frontend P1 follow-up)
>
> **DOD**: 4 sub-menu Vue 全 navigable in Canvas → 工作流 → side nav. E2E 1 个 demo 流 走完 (创建 → 我创建的 ↑1 → 流转 → 我参与的 ↑1 → 处理 → admin 看到 → 完成).
>
> **PR**: 单 PR + screenshot 5 sub-views.

---

## §B Track B — F-TAX-DIRECT-1 (P1 10d, 大客户硬需)

**Steve sign-off**: 数电票税局直连是 Round 12 §B.5 X2 finding — 大客户 (会计师事务所/政府/上市公司) 硬需. F006 选做 (现 P1 if 客户问).

### Brief (paste-ready)

> Sprint 5 Track B — F-TAX-DIRECT-1 (P1 10d, 大客户硬需).
>
> **Context**: Round 12 §B.5 X2 finding: HJ 不支持 直连税局 (数电票需手动从税局端开+扫码绑定). Cretas 加 直连税局集成 → **大客户痛点直接解决** (Cretas > HJ).
>
> **Scope**:
> 1. **Spike (Day 1-3)**: 选 provider (深圳/广东/江苏 数电票直连 API, 推荐百望/航天信息/诺诺/瑞宏). 调研 API spec + 费率 + 试用沙箱.
> 2. **Backend (Day 4-7)**:
>    - 新 `TaxDirectInvoiceProvider` interface + 1 impl (推荐百望)
>    - `InvoiceService.applyForDirect(invoiceRequest)` 触发税局 open + 拉 PDF 回写
>    - `InvoiceRecord.taxDirectStatus` 字段 (NOT_REQUESTED → REQUESTED → SUCCESS / FAILED)
>    - vflag listener 自动 trigger 凭证生成 on SUCCESS
> 3. **Frontend (Day 8-9)**:
>    - 发票申请 dialog 加 "直连税局" toggle
>    - InvoiceList 加 直连状态 chip
>    - PDF 回写后自动 download link
> 4. **Test (Day 10)**: 1 沙箱客户 test 跑 + 文档.
>
> **依赖**: 第三方 API (建议 Steve 联系 1-2 provider 拿 sandbox key)
>
> **风险**: Provider API 不稳/费率高 — backup plan: 只做 "API ready, UI hidden behind feature flag" 给 Sprint 6 真上.
>
> **DOD**: 1 真客户/沙箱 1 张数电票 流程完成 (申请 → 税局 open → PDF 回写 → vflag 凭证).
>
> **Spec inputs**:
> - 32-doc §B.5 X2 (5 chain 数据流 §B.5 invoice→voucher)
> - 31-doc §P.5 P0+P1 list

---

## §C Track C — Quick Wins Bundle (P1 12d, F006 + 通用)

**Bundle 多小 P1**: 报价试算 (3d) + inline link counter (4d) + 打印 21+称重 (4d) + 采购需求 verify (1d).

### Brief (paste-ready)

> Sprint 5 Track C — Quick Wins Bundle (P1 12d, 4 items).
>
> **Context**: 4 个 Cretas missing UX/feature, Round 12+13 finding, 单独写 spec 太轻, 打包 1 个 PR/bundle.
>
> **Items**:
>
> ### C-1: G12-9 报价试算 (3d)
> - **Source**: Round 12 §A.2 — HJ 销售模块 "报价试算" sub-menu (HJ S-QUOTE-CALC). Cretas 缺.
> - **Backend**: `QuotationCalculator.tryQuote(productList, customerCreditTier)` 返试算 totalAmount + 利润预估
> - **Frontend**: 销售模块 Quick Action "试算" button + dialog (1 input 客户 + 多 产品行 + 结果 read-only)
> - **DOD**: 销售员可不创建报价单, 即时算 1 张试算 + 利润预估
>
> ### C-2: G12-1 inline link counter (4d)
> - **Source**: Round 12 §B.6 X2 + Round 11 §O.5 — HJ 销售单 list 行内 `文件(N) 图片(N) 合同(N)` 3 个 link counter, Cretas 缺.
> - **Backend**: SalesOrderListDTO 加 `linkCounts: {file: N, image: N, contract: N}` (LEFT JOIN count)
> - **Frontend**: 销售订单 list 列 + 销售订单 detail 顶部 chip row. 同样应用 procurement / inventory list.
> - **DOD**: 4 list (sales/PO/inventory/voucher) 行内 link counter 全 ship.
>
> ### C-3: 打印模板 21 分类 + 称重模板 (4d, 含 L13-6 verify)
> - **Source**: Round 13 §13 + §15 — HJ print.hongjian.com 新子域, 21 模板分类. Cretas Track-J ship 几个待 verify (Z-4 task).
> - **Z-4 output 依赖** (本 task 第 0.5 day verify Cretas 现有分类, 列缺)
> - 加 **称重模板** (L13-8, F006 N13 W-ABA-1 抄码品配合 P1 3d)
> - 加 **静态模板** (L13-7 P3 2d, 可选)
> - **DOD**: Cretas print template categories ≥ Cretas 现有 + 2 (称重 + 静态) — bundle 21 分类 spike defer Sprint 6+
>
> ### C-4: G12-10 采购需求总表 entry verify (1d, 跟 Z-2 重合)
> - **从 Z-2 拉取** Z 输出 — verify Cretas entry route 一致 + 加 frontend route 如缺
>
> **DOD**: 单 PR + 4 个 sub-PR 或合并, 每 item ≥ 1 screenshot.
>
> **Spec inputs**: 32-doc §A.2 X1 + §B.6 X2, 33-doc §13 + §15

---

## §D Track D — P-REQUISITION-1 (P1 5d, F006 + 在谈食品)

### Brief (paste-ready)

> Sprint 5 Track D — P-REQUISITION-1 请购单 entity (P1 5d).
>
> **Context**: Round 12 §B.2 X2 finding: HJ 采购模块 有 "请购单" entity (Material Requisition) + "请购汇总" sub-menu. Cretas 仅 ShortageAnalysisService (无 user-facing entity). 在谈食品厂客户问"我能不能从生产员发请购单上来" — Cretas 当前不行.
>
> **Scope**:
> 1. **Backend (Day 1-3)**:
>    - 新 entity `PurchaseRequisition` (id / factoryId / requesterId / requestedItems / status / requesterDeptId)
>    - State machine: DRAFT → PENDING_APPROVAL → APPROVED → CONVERTED_TO_PO → REJECTED
>    - Service: `submitRequisition()` + `approveRequisition()` + `convertToPO(requisitionId)` (auto-create PO from approved req)
>    - Controller: REST + AI Tool
> 2. **Frontend (Day 4)**:
>    - Vue list (我的请购 / 待审批我请购 / 所有请购)
>    - 请购单 create + detail dialog
>    - 接入 Canvas → 采购 Tab
> 3. **Test (Day 5)**: E2E 1 流: 仓库员发请购 → 采购经理审批 → 自动转 PO → vflag 凭证.
>
> **依赖**:
> - PurchaseOrderService 现有 (复用 createPO from requisition)
> - Round 12 ApprovalChain (复用 personal view from Track A)
>
> **DOD**: 1 请购单 端到端跑完, auto-converted PO 跟其关联 (linkno).

---

## §E Track E — M-WAGE-INTEGRATION-1 (P1 5d, F006 计件场景)

### Brief (paste-ready)

> Sprint 5 Track E — M-WAGE-INTEGRATION-1 生产→工资 自动 trigger (P1 5d).
>
> **Context**: Round 12 §C.7 X3 finding: HJ 生产工序完工自动 trigger 计件 record → 工资计算. Cretas H-WAGE ship #833/#844/#863/#870 但**缺 trigger** — 生产员完工 → 工资 record 是手动. F006 计件场景痛点.
>
> **Scope**:
> 1. **Day 1**: 调研 Cretas H-WAGE current state + ProcessingService listener point
> 2. **Backend (Day 2-3)**:
>    - `ProcessingService.completeProcess(taskId, completedQty, operatorId)` 触发 `WageRecordTriggerService.recordPieceWage(...)`
>    - 新 entity 或扩 `WageRecord` 加 `sourceTaskId` (linkno from production)
>    - 配置: `WagePolicy` per product/operator (按件 vs 按时 vs 混合)
>    - vflag listener: WageRecord 计入 → 月底自动 trigger 工资凭证
> 3. **Frontend (Day 4)**:
>    - 我的工资 view 加 "本月计件明细" section (从 Production trigger)
>    - WagePolicy 配置 page
> 4. **Test (Day 5)**: 完 1 个生产 task → 验 WageRecord 出现 + linkno 反查正确
>
> **依赖**: H-WAGE-FULL ship (PR #833/#844/#863/#870) + ProcessingService
>
> **DOD**: 1 计件 task 完成 → WageRecord auto-trigger → 我的工资 view 显示 → linkno 反查 production task.

---

## §F Track F — Customer + 辅助核算 bundle (P1 9d)

### Brief (paste-ready)

> Sprint 5 Track F — Customer 档案补 8 tabs + Voucher 辅助核算 7 类 (P1 9d).
>
> **Context**: 2 个相关 P1 bundle (都涉及 Customer 维度).
>
> ### F-1: G12-3 客户档案补剩 8 tabs (5d)
> - **Source**: Round 13 §1 修正 — HJ 实测 17 tab (Round 11 baseline 21 偏高). Cretas Round 11 §A.2 ship 13/17 = 76%, 缺 4 tab.
> - **缺 tabs** (per Round 11 §A.2 X1): TBD — Read `web-admin/src/views/customer/detail/...` 找现有 tab list, 对照 Round 13 §1 17 tabs.
> - **典型缺**: 谈话录音 / 邮件列表 / 商品统计 / 短信记录 (4 个 communication-type tabs)
> - **DOD**: 17 / 17 tab covered + cascade load.
>
> ### F-2: G12-4 Voucher 辅助核算 7 类 (4d)
> - **Source**: Round 13 §2 + Round 12 §A.5 X1 + 32-doc §A.5 — HJ Voucher 7 类 (客户/供应商/部门/职员/项目/存货/委外商) Cretas Voucher 实体可能仅 1-2 类.
> - **Backend**: `Voucher.auxiliaryType` enum 加 7 values + `Voucher.auxiliaryEntityId` 字段 (polymorphic FK)
> - **Frontend**: Voucher dialog 加 "辅助核算" dropdown 7 类 + entity picker per type
> - **Spec inputs**: Round 12 §A.5 + Round 13 §2
> - **DOD**: 录凭证可选 7 类辅助核算 + 反查可按辅助 filter.
>
> **依赖**: F-VFLAG-1 ship (PR #693), Customer/Supplier/Department/Employee/Project/Inventory/Outsourcer 现有 entities

---

## §G Track G — RBAC 数据权限维度 (P1 6d, 大客户)

### Brief (paste-ready)

> Sprint 5 Track G — G12-6 RBAC 数据权限维度 (P1 6d, 大客户).
>
> **Context**: Round 12 §D.1 X4 + Round 13 §4 — HJ RBAC 4 维 (功能/数据/打印/第三方), Cretas 仅功能 1 维. **数据权限** (row-level filter by customer/department) 是大客户必需 (per Round 13 §15 finding).
>
> **Scope**:
> 1. **Day 1-2 Spec + design**:
>    - 新 enum `DataScope`: ALL / DEPT_AND_BELOW / SELF_AND_BELOW / SELF / CUSTOM (5 级 per Round 12 §D.1)
>    - 新 `Role.dataScope` 字段 per role
>    - `DataScopeAspect` annotation: `@DataScope("client_id")` on Repository methods → auto-inject WHERE clause
> 2. **Day 3-4 Backend**:
>    - 实现 5 级 scope 解析逻辑
>    - 应用到 ≥10 个 service methods (sale/customer/PO/invoice 等关键 endpoint)
> 3. **Day 5 Frontend**:
>    - 角色管理 detail page 加 "数据权限" tab
>    - 5 级 radio + CUSTOM dialog
> 4. **Day 6 Test**:
>    - 3 角色 × 5 scope 矩阵测试 (e.g. 销售员只看自己客户的销售单)
>    - 端到端 verify list endpoint 按 scope filter
>
> **依赖**: C-RBAC-1 ship (#661), @RequirePermission framework
>
> **DOD**: 1 销售员 角色 with SELF scope, login 后 list 只看自己创建的 sales orders.

---

## §H Track H — linkno 11 类 + Round 11 §P 收尾 bundle (P0/P1 8d)

### Brief (paste-ready)

> Sprint 5 Track H — C-LINK-11TYPE-1 + Round 11 §P 收尾 bundle (P0/P1 8d).
>
> **Context**: Round 12 §B.6 X2 finding + Round 11 §P P0 收尾.
>
> ### H-1: C-LINK-11TYPE-1 (P1 3d)
> - **Source**: Round 12 §B.6 X2 — Cretas BusinessLink 8 类 (sale/sample/request/produce/outsource/stock/project/free) vs HJ baseline 8 类 (file/image/contract/sample/request/produce/outsource/stock). **3 类 mismatch**.
> - **Decision**: 扩 11 类 (Cretas 8 + HJ 3 = file/image/contract) OR 拆 AttachmentRecord 独立 entity
> - **推荐**: 拆 AttachmentRecord 独立 entity (file/image/contract 是 attachment 性质, 不应跟 sale/sample 等业务关联混)
> - **Backend**: `AttachmentRecord` entity (id / entityType / entityId / fileType / fileUrl / uploaderId) — 跟 Round 11 N20 C-ATT-1 集成 (已 ship)
> - **DOD**: BusinessLink 保持 8 类 + AttachmentRecord 独立, sale 行内 `文件(N) 图片(N) 合同(N)` link counter 接 AttachmentRecord
>
> ### H-2: M-BOM-VER-1 frontend follow-up (P0 3d, Round 11 §P)
> - **Source**: Round 11 §P.5 P0 剩 5d — backend Sprint3-H ship (PR #694), frontend 缺
> - **Frontend**: BomVersion list view + ECN 编辑器 + 反查 UI + 4 批量按钮 (per Round 11 §E.1)
>
> ### H-3: C-APPROVAL-EDITOR Phase 2 收尾 (P0 2d, Round 11 §P)
> - **Source**: Round 11 §I.1 X5 + Round 12 §G — Canvas Phase 1 (PR #862) 含 758-line VueFlow editor + 4 执行模式 (含 N-of-M 会签 HJ 没有). 剩 3-5d incremental:
>   - WorkflowRule UI (跟 Track A 重合, 协调一下)
>   - OpinionTemplate dialog (节点意见模板)
>   - decisionType 扩枚举 (Cretas 14 → 30+, 但 不 ship 全 115 实例 — 仅扩 base enum)
>
> **DOD**: 单 PR + 3 个 sub-PR 或合并, AttachmentRecord 独立 + BomVersion frontend live + decisionType 扩 25+.

---

## §I 依赖图 + 时间表

```
Day 0-3: Z (Pre-Spike)
            │
   ┌────────┼────────┐
   ▼        ▼        ▼
Day 1-12: 8 tracks (A-H) 并行
   │   │   │   │   │   │   │   │
   A   B   C   D   E   F   G   H
   │   │   │   │   │   │   │   │
   └───┴───┴───┴───┴───┴───┴───┘
                │
                ▼
        Day 13: integration test + release
```

**关键依赖**:
- Z → 所有 (Pre-Spike 必先, 否则 Sprint 5 跑空)
- Track A → Track H-3 (Personal view 跟 decisionType 扩枚举 协调一下)
- Track G → Track F-2 (RBAC 数据权限 影响 Voucher 辅助核算 filter)
- Track H-1 (AttachmentRecord) ← Track C (linkcounter UI 需要 AttachmentRecord backend)
- Track E (生产→工资) → Track A (Personal view 显示 WageRecord)

---

## §J 推荐执行模式

### Option 1: 单 dev sequential (Steve 单干)
- 6-7 周 (~35 工作日 Claude 加速)
- 顺序: Z → A → H → C → D → E → F → G → B (按依赖优先级)
- 风险: 时间长 + Sprint 5 dispatch 跑不开

### Option 2: 双 dev parallel (推荐, Sprint 5 加 1 帮手)
- 3-4 周 (~17-20 工作日)
- Dev1: Z + A + H + C (核心架构 + UI)
- Dev2: B + D + E + F + G (功能 + 集成)

### Option 3: 8 chat 并行 (Round 11+12+13 模式)
- 1.5-2 周 (~10-12 工作日)
- 每 track 单独 1 chat (类似 Sprint 1-2 6-track 模式)
- organizer (Steve) admin-merge + integration test
- 风险: 并发 PR 冲突 (per memory `feedback_canvas_tab_merge_conflict_pattern.md` HARD — Canvas Tab 中心文件 N-1 rebase)

### Option 4: Hybrid (推荐 if Sprint 5 在 1 月内 ship)
- Z + H-2 + H-3: Steve 亲做 (Pre-Spike + 收尾 Round 11 P0 剩, ~1 周)
- A + B + D + E + G + Quick Wins bundle (C): 6 sister chats 并行 (~2 周)
- F: Steve 亲做 (Voucher + Customer 跟 F-VFLAG-1 集成需熟悉 Sprint3-E PR #693 内部)
- Total: 3-4 周

---

## §K Sprint 5 真完成度评分

| Track | P0/P1/P2 | F006 deal-breaker | 大客户 value |
|---|---|---|---|
| Z | mixed | 高 (M1 + verify) | 低 |
| A | P0 | 高 (审批 Canvas 闭环) | 中 |
| B | P1 | 低 (F006 现手开票) | **高** (大客户硬需) |
| C-1 报价试算 | P1 | 中 | 中 |
| C-2 linkcounter | P1 | 中 (UX 体验) | 中 |
| C-3 打印 21+称重 | P1 | 高 (F006 N13 协同) | 中 |
| D | P1 | 中 (生产员发请购) | 高 (在谈食品厂) |
| E | P1 | 高 (F006 计件痛点) | 低 |
| F-1 客户 17 tab | P1 | 中 (UX) | 高 |
| F-2 辅助核算 7 类 | P1 | 低 | **高** (大客户记账) |
| G 数据权限 | P1 | 低 | **高** (大客户必需) |
| H-1 linkno 11 类 | P1 | 中 (UX) | 中 |
| H-2 BOM frontend | P0 | 高 (Round 11 余项) | 中 |
| H-3 Canvas Phase 2 | P0 | 中 (workflow 完整) | 高 |

**Sprint 5 完成后 Cretas readiness**:
- ✅ F006 deal-breaker 项 全 ship (M1/A/C-3/E/H-2)
- ✅ 大客户 readiness (B/F-2/G) 接近就绪 (剩 Sprint 6+ 复式记账/报表三表)
- ✅ Round 11 P0 余项收口 (H-2/H-3)
- ✅ Round 12 5 P1 新发现 全交付 (A/B/D/E/G)
- ✅ Round 13 8 P1/P2 新发现 部分交付 (C-3 / F / H-1)

---

## §L 后续 (Sprint 6+ 候选, 不在本计划范围)

per 32-doc §G + 33-doc §15:
- **Sprint 6**: 大客户深 (复式记账 F-VOUCHER-2 / F-PERIOD-1 / F-3REPORT-1 三表) + 商机 8 阶段 (S-OPP-FULL-LIFECYCLE-1) + 序列号管理 + 报废单 + 业绩 6 项
- **Sprint 7+**: 设备 lifecycle / 模具 / 大屏看板 / docs 子域 / 招聘宿舍 等 P3
- **Round 14 (HJ 过期后)**: HJ APK 实测 (Steve 手动) + Cretas vs HJ 端到端 demo benchmark

---

**Sprint 5 派工计划 v1.0 完成 (2026-05-19, organizer based on Round 11+12+13 audit + Steve sign-off)**.
