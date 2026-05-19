# Sprint 6 派工计划 Skeleton (2026-05-19, based on Sprint 5 9 PRs follow-up)

> **触发**: Sprint 5 9 PRs (#51-#59) dispatch 完成 + Steve "继续".
> **基础**: 每 PR body 末尾 "Sprint 6 follow-up" + Round 11 §P / Round 12 §G / Round 13 §15 backlog.
> **总工时**: ~40-50d nominal (Sprint 5 ~64d 的后续 50-60%).
> **策略**: Sprint 5 ship 完整化 (MVP slice → full DOD) + 新 P1/P2 大客户场景.

---

## §0 派工总览 (4 周 W1-W4 sprint)

| Week | 主题 | 工时 nominal | 客户群 | 依赖 Sprint 5 PR |
|---|---|---|---|---|
| **W1** | 数电票真集成 + Personal view 3 sub | 12d | 大客户 + F006 | PR #52 + #59 |
| **W2** | Frontend ship (D + G + 部分 F) | 10d | F006 + 大客户 | PR #56 + #54 + #53 |
| **W3** | Phase-2 (链 chip 拆 + decisionType wiring + 打印 P1) | 10d | F006 + 通用 | PR #58 + #55 + #58 |
| **W4** | Backend depth (微信/通话/wage mode/vflag listener) | 8d | F006 + 大客户 | PR #53 + #57 |

**4 周 = ~40d nominal = ~24d Claude 加速 / ~5-6 周单人 / ~2-3 周双人 / ~1-1.5 周 6 chat 并行**

---

## §W1 Tracks (12d, Sprint 5 PR #52 + #59 follow-up)

### Track W1-A: 数电票真集成 P0 (10d)
- 依赖: PR #52 merge + Steve sign-off (provider + sandbox key)
- W1 D1-D3: Maven `baiwang-sdk` 依赖 + 4 method 真 impl (apply / queryStatus / downloadPdf / cancel)
- W1 D4-D5: HMAC-SHA256 signing + scheduled polling job (TaxDirectPollingScheduler @Scheduled per `@SchedulerLock`)
- W2 if W1 不够: Vue UI toggle + InvoiceList status chip + 沙箱 E2E
- **Steve 前置决策**: confirm 百望 + sandbox key 申请方
- **PR target**: 1 PR `sprint6/W1-tax-direct-impl`

### Track W1-B: Personal view 3 sub frontend (~2d, 12h)
- 依赖: PR #59 merge
- 我参与的工作流 frontend (~2h, mechanical copy of my-created.vue)
- 工作流处理 admin UI (~6h, both backend + frontend, RBAC gate + bulk actions)
- 流转规则设置 frontend (~4h, backend ✅ Round 11 §I.4)
- **PR target**: 1 PR `sprint6/W1-personal-view-rest`

---

## §W2 Tracks (10d, Sprint 5 PR #56 + #54 + #53 follow-up)

### Track W2-A: PurchaseRequisition frontend (3d)
- 依赖: PR #56 merge
- 3 Vue list views (我的请购 / 待审批 / 全部) + create dialog + detail view + Canvas 采购 Tab integration
- Workflow integration (wire submitRequisition into Canvas-Workflow ApprovalChainConfig)
- vflag listener auto-trigger 凭证 generation when CONVERTED_TO_PO
- AI Tool wrapping
- **PR target**: 1 PR `sprint6/W2-requisition-frontend`

### Track W2-B: RBAC 数据权限 sweep 10+ endpoints (5d)
- 依赖: PR #54 merge
- W2 D1-D2: Specification interceptor (transparent WHERE injection, no manual Service branching)
- W2 D3-D4: Apply to 10+ keystone endpoints (customer/PO/invoice/delivery/inventory)
- W2 D5: DEPT_AND_BELOW + SELF_AND_BELOW via User.reportsTo chain
- W2 D5+: CUSTOM scope dialog + Frontend edit mode + PUT endpoint + 3-role × 5-scope E2E matrix
- **PR target**: 1 PR `sprint6/W2-rbac-sweep`

### Track W2-C: 微信/通话 backend (2d each = 4d, but parallel = 3d wall)
- 依赖: PR #53 merge
- 微信记录 backend (WechatRecord entity + REST + 手工补录 dialog, 3d)
- 通话记录 backend (CallRecord entity + 录音 OSS 上传 + audio tab 联动, 3d, 跟 N20 C-ATT-1 集成)
- Frontend AudioRecordingsTab / EmailsTab 接 backend (replace placeholder)
- **PR target**: 2 PRs `sprint6/W2-wechat-backend` + `sprint6/W2-call-record-backend`

---

## §W3 Tracks (10d, Sprint 5 PR #58 + #55 follow-up)

### Track W3-A: 链 chip 拆 file/image/contract (3d)
- 依赖: PR #58 + PR #55 merge (Attachment.EntityType + SALES_ORDER/INVENTORY 加 whitelist)
- backend: SalesOrderListDTO.linkCounts {file: N, image: N, contract: N}
- frontend: 4 list (sales/PO/inventory/voucher) 行内 3-chip 显示
- **PR target**: 1 PR `sprint6/W3-link-chip-split`

### Track W3-B: decisionType 17 new service wiring + admin UI (3d)
- 依赖: PR #55 + PR #59 merge
- Wire 17 new DecisionType values 到 ApprovalWorkflowExecutor (~3d)
- Admin UI (PR #59 admin view) 加 DecisionType dropdown
- 1-2 真 demo workflow per new DecisionType (sample data)
- **PR target**: 1 PR `sprint6/W3-decisiontype-wiring`

### Track W3-C: 打印 P1 3 templates (5d)
- 依赖: 32-doc §G + Round 13 §13 (打印 21 分类 + 3 P0 priority per Z-4)
- 仓库出入库模板 (2d)
- 财务发票/凭证模板 (2d) — 跟 PR #52 数电票 协同
- 装箱模板 (1d) — 跟 N13 W-ABA-1 抄码品配合
- **PR target**: 1 PR `sprint6/W3-print-p1-templates`

---

## §W4 Tracks (8d, Sprint 5 PR #53 + #57 follow-up + 新 P2)

### Track W4-A: 辅助核算 DEPT/PROJECT/INVENTORY/OUTSOURCER generator wiring (3d)
- 依赖: PR #53 merge
- Wire 4 generators to use AuxiliaryType + auxiliaryEntityId (extend SALES/PURCHASE/WAGE pattern from PR #53)
- 报表 by-auxiliary 聚合 view (Vue + SQL aggregate)
- **PR target**: 1 PR `sprint6/W4-auxiliary-full-wiring`

### Track W4-B: WagePolicy mode (按时/混合) + 月底自动 trigger (3d)
- 依赖: PR #57 merge
- New entity / 扩 PieceRateRule for 按时 / 混合 mode (按件 already ship)
- Vflag listener auto-trigger 工资凭证 month-end in `generateFactoryPayroll`
- 我的工资 view 本月计件明细 section
- WagePolicy 配置 admin UI
- **PR target**: 1 PR `sprint6/W4-wage-policy-modes`

### Track W4-C: BomVersion 4 batch UI + ECN paginated list (2d)
- 依赖: PR #55 merge
- BomVersion 4 batch buttons (批量修改/替换/删除/新增) frontend UI
- Reverse-query UI (某物料挂哪些 BomVersion)
- ECN paginated list endpoint + impact report dialog + cascade BomVersion approval panel
- **PR target**: 1 PR `sprint6/W4-bom-batch-ecn-list`

---

## §I 依赖图

```
Sprint 5 (9 PRs merge) — prerequisite
   │
   ├─ W1 (12d): Tax direct + Personal view rest
   │       │
   │       ▼
   ├─ W2 (10d): Frontend ship (requisition / RBAC / 微信/通话)
   │       │
   │       ▼
   ├─ W3 (10d): Phase-2 (链 chip / decisionType / 打印 P1)
   │       │
   │       ▼
   └─ W4 (8d): Backend depth (辅助核算 / wage mode / BOM batch)
```

依赖严格性: W1 ←→ W2 ←→ W3 ←→ W4 (大部分跨周可并行, 仅 W2-B RBAC sweep 跟 W3-A 链 chip 在 Service layer 可能冲突 — 协调).

---

## §J 推荐执行模式 (Sprint 6 with 9 PR follow-up complexity)

### Option 1: 6 chat 并行 (推荐, 跟 Sprint 5 模式一致)
- 1.5-2 周 (W1-A + W1-B + W2-A + W2-B + W2-C + W3-* 等)
- 优势: 时间最短 + worktree 隔离已 proven
- 风险: 大量 PR 同时 review 压力 (但 Sprint 5 已 prove 可承受 9 PR)

### Option 2: 4 chat 并行 + 1 后续整合 (低 review 压力)
- 3-4 周 (W1 + W2 + W3 + W4 各 1 chat)
- 优势: 1 周 1 主题 ship, Steve review focused
- 风险: 时间长 + 每 chat 多 PR 串行

### Option 3: 单 dev sequential (Steve 单干)
- 5-6 周 (~24 工作日 Claude 加速)
- 仅 if Sprint 5 review burden too high

### Option 4: Hybrid (推荐 if Sprint 5 PR review 跟得上)
- W1: Steve 亲做 W1-A 数电票 (需 provider config) + 1 chat 做 W1-B (~1 周)
- W2-W3 并行: 4-5 chats (W2-A/B/C + W3-A/B/C)
- W4: Steve 亲做 W4-B (跟 PayrollRecord 内部熟悉)
- Total: 2-3 周

---

## §K 真目标

Sprint 6 ship 完成后:
- ✅ **大客户 readiness 完整** (数电票 + 数据权限 sweep + 复式记账 / 4 维 / etc)
- ✅ **F006 deal-breaker 100% ship** (Sprint 5 backend + Sprint 6 frontend)
- ✅ **Cretas decisionType 32 → 50+ (含 service wiring)** vs HJ 115+ = ~40% coverage
- ✅ **新 backlog 收口 30+ items** (Round 12 §G + Round 13 §15 大部分)
- ⚠️ **Sprint 7+ 候选** (剩 ~30-40d 大企业深度: 复式记账 F-VOUCHER-2 + 期间结账 F-PERIOD + 报表三表 F-3REPORT + 商机 8 阶段 + 序列号 + 设备 lifecycle)
- ⚠️ **Round 14 (Cretas vs HJ 端到端 demo benchmark)** 排到 Sprint 7+ (post 大客户 readiness)

---

## §L 工时累计 (Round 11+12+13 + Sprint 5 ship + Sprint 6 ship)

| 阶段 | 剩 backlog | 累计时长 |
|---|---|---|
| Round 11 baseline | ~150d | ~3 月 |
| +Round 12+13 新发现 | ~210d 新增 → ~360d total | ~7 月 |
| Sprint 5 ship (in-flight 9 PRs) | ~290d (Sprint 5 ~70d done) | ~6 月剩 |
| **Sprint 6 ship (跟 Sprint 5 follow-up ~40d)** | **~250d** | **~5 月剩** |
| Sprint 7+ (P2 大企业 + 长期) | ~150d剩 | ~3 月 (按客户触发) |

vs Steve sign-off 9 月 → Sprint 6 ship 后 **省 ~4 月 (vs 9 月 sign-off)**.

---

## §M 后续 (post Sprint 6)

- **Sprint 7-8** (W5-W12, ~50d): 大企业深度 (复式记账 + 期间结账 + 报表三表 + 商机 8 阶段 + 业绩 6 项)
- **Sprint 9+** (~50d): 长期 P3 (TV 大屏 / 微服务 / RBAC 细粒度 / 设备 lifecycle / 招聘宿舍 / docs 子域)
- **Round 14** (post Sprint 6): Cretas vs HJ 端到端 demo benchmark (Boss 演示就绪)
- **HJ APK 实测** (Round 9 27-doc): Steve 任意时机 (HJ 测试账号 5/21 过期, APK 跟 web 独立, 可能不过期)

---

**Sprint 6 plan skeleton v1.0 完成 (2026-05-19, organizer based on Sprint 5 9 PRs follow-up)**.

待 Steve sign-off Sprint 6 模式 (Option 1-4) 后展开 detail per-track brief (类似 Sprint 5 plan §A-§H format).
