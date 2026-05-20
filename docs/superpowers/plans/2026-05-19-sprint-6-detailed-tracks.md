# Sprint 6 Detailed Track Briefs (2026-05-19, post Sprint 5+6 wave 1 dispatch)

> **触发**: Sprint 5 9 PRs (#51-#59) + Sprint 6 wave 1 6 PRs (#65-#70) dispatched. 这份是 Sprint 6 **缺失的 4 个 tracks** detailed brief, Steve merge Sprint 5+6 后立刻可 dispatch.
> **基础**: `docs/superpowers/plans/2026-05-19-sprint-6-skeleton.md` §W2 + §W3 + §W4
> **总工时**: ~13d nominal (W2-C 4d + W3-B 3d + W4-B 3d + W4-C 2d) = ~3-4 工作日 4 chat 并行
> **覆盖 Sprint 6 skeleton 缺漏**: W1-A (blocked Steve provider) / W1-B + W2-A + W2-B + W3-A + W3-C + W4-A 已 in PRs #65-#70

---

## §M Marching Order (dispatch sequence, post Sprint 5 merge)

| 优先级 | Track | 依赖 Sprint 5 PR merge | Wall time | 客户群 |
|---|---|---|---|---|
| ⚡ IMMEDIATE | W3-B decisionType wiring | #55 H + #59 A | 3d | F006 + 通用 |
| ⚡ IMMEDIATE | W4-C BomVersion batch + ECN | #55 H | 2d | F006 + 通用 |
| ⏳ QUEUED | W4-B WagePolicy modes | #57 E | 3d | F006 计件 |
| ⏳ QUEUED | W2-C 微信/通话 backend | #53 F | 4d (2 PR parallel = 3d wall) | F006 + 大客户 CRM |

**并行**: 全 4 tracks 互独立 entity / service / frontend → 4 chats 同时 fire OK. 唯一共享 file: enum import 在 Customer.java (W2-C) + ApprovalWorkflowExecutor.java (W3-B) — pre-coordinate.

---

## §W2-C-1 Track 微信记录 backend (P1 2d)

### Brief (paste-ready for sister chat)

> Sprint 6 Track W2-C-1 — 微信记录 backend (P1 2d).
>
> **Context**: Round 11 §F.2 finding — HJ Customer 详情 "微信记录" tab. Cretas Customer.java + 4 related entities ship (PR #53 F), 缺 微信 record entity. F006 业务员场景: 跟客户微信聊单进度, 手工补录到 CRM (微信 OAuth API 第三方限制, 手工补录最实际).
>
> **Scope (2d)**:
> 1. **Day 1 backend**:
>    - 新 entity `WechatRecord` (customerId/recordTime/direction enum INBOUND/OUTBOUND/INTERNAL/messageContent/createdBy/createdAt/updatedAt + soft delete)
>    - Flyway `V20260520_01__add_wechat_record.sql` (PG schema, factory_id RLS column, indexes)
>    - REST: `WechatRecordController` (POST/GET/PUT/DELETE) under `/api/mobile/{factoryId}/customer/{customerId}/wechat-record`
>    - Service `WechatRecordService` + Repository (paginated query by customerId + date range)
> 2. **Day 2 frontend**:
>    - `web-admin/src/views/customer/components/WechatRecordsTab.vue` (timeline display + 手工补录 dialog)
>    - Wire into existing CustomerDetail tab system
>    - 防呆: createDialog 必有 customer name + 直接 receivers selector (rule 2 context) + dropdown direction (rule 3 标准选项)
> 3. **Test**: 1 单元 test (entity validation) + Playwright E2E (创建 → list → edit → delete).
>
> **Spec inputs**:
> - 31-doc §F.2 (Round 11 Customer 17 tab finding)
> - 32-doc §A.5 (Round 12 CRM 子菜单)
> - `feedback_pr_review_must_grep_migration_table_names.md` HARD (V*.sql 必 grep schema)
>
> **DOD**:
> - REST 4 endpoint pass smoke
> - Vue tab UI render 真数据 (创建 1 条 → list 显)
> - Flyway V20260520_01 apply test + prod 无 error
> - 防呆 4 位一体 (per fool-proof-design.md): rule 2 context / rule 3 dropdown / rule 5 dead-end navigation
>
> **PR**: 1 PR `sprint6/W2-C-1-wechat-backend`

---

## §W2-C-2 Track 通话记录 backend + 录音 OSS (P1 3d)

### Brief (paste-ready)

> Sprint 6 Track W2-C-2 — 通话记录 backend + 录音 OSS 上传 (P1 3d).
>
> **Context**: Round 11 §F.2 finding — HJ Customer 详情 "通话记录" tab + 录音 attachment. F006 销售场景: 电话沟通后上传录音 + 标记关键时间点. AudioRecordingsTab 当前是 placeholder (PR #53 F 只 wire CallRecord entity ID).
>
> **Scope (3d)**:
> 1. **Day 1 backend entity + OSS**:
>    - 新 entity `CallRecord` (customerId/callTime/duration/callType enum INCOMING/OUTGOING/MISSED/audioOssUrl/transcriptText/createdBy/createdAt/updatedAt + soft delete)
>    - Flyway `V20260520_02__add_call_record.sql`
>    - OSS upload service: leverage existing `OssAttachmentService` (per `Attachment.java`) + new `CallRecordAttachment` type (Sprint 5 PR #58 Attachment.EntityType extension)
> 2. **Day 2 backend REST + transcription**:
>    - REST `CallRecordController` (POST/GET/PUT/DELETE) under `/api/mobile/{factoryId}/customer/{customerId}/call-record`
>    - POST 接 multipart/form-data (audio + metadata) → OSS upload → save entity
>    - 转写: 调用 existing Python service `/api/efficiency/whisper-transcribe` (Round 11 verified ship) async transcript fill
> 3. **Day 3 frontend**:
>    - `web-admin/src/views/customer/components/AudioRecordingsTab.vue` (replace PR #53 F placeholder)
>    - Audio player + 转写 text display + 上传 dialog (audio file + 手工 metadata)
>    - 防呆: rule 1 file size max display / rule 3 callType dropdown / rule 4 idempotent (5min dedup window)
>    - E2E: 上传 audio → OSS URL → list 显 → 播放
>
> **Spec inputs**:
> - 31-doc §F.2 (Round 11 Customer 17 tab)
> - 32-doc §A.5 + §G.4 (Round 12 CRM)
> - `OssAttachmentService.java` (existing impl, extend)
> - Python `whisper-transcribe` endpoint (Round 11 verified)
>
> **DOD**:
> - 上传 1 个真音频 → OSS URL 回写 → entity 含 audioOssUrl
> - Whisper 转写 ~30s 内完成 (async, frontend poll)
> - AudioRecordingsTab UI 替换 placeholder + 真播放
> - Flyway + 防呆 4 位一体 ✅
>
> **PR**: 1 PR `sprint6/W2-C-2-call-record-backend`

---

## §W3-B Track decisionType 17 service wiring + admin UI (P1 3d)

### Brief (paste-ready)

> Sprint 6 Track W3-B — decisionType 17 new service wiring + admin UI (P1 3d).
>
> **Context**: Sprint 5 PR #55 H 加 decisionType enum 14→32 (Round 12 §G.6 finding — HJ 115+ vs Cretas 14). **PR #55 H 只加 enum + frontend**, 实际 backend `ApprovalWorkflowExecutor` 仍 only handle 14 old types. Sprint 6 W3-B = wire 17 new types + admin UI dropdown 选择.
>
> **17 new DecisionType values** (from PR #55 H — confirm post-merge):
> - 角色 (ROLE_BASED) — 按角色路由 (e.g. 区域销售经理 / 财务总监)
> - 部门负责人 (DEPT_HEAD) — 当前 user.department.head
> - 上级 (REPORT_TO) — User.reportsTo
> - 上上级 (REPORT_TO.REPORT_TO) — 2-level up
> - 项目负责人 (PROJECT_OWNER) — task.project.owner
> - 跳过 (SKIP) — auto-approve (e.g. 金额 < ¥100)
> - 多人或签 (PARALLEL_ANY) — 任一人审完 即完
> - 多人会签 (PARALLEL_ALL) — 全部审完 才完
> - 多人依次 (SEQUENTIAL) — 顺序审
> - 客户 (CUSTOMER_CONTACT) — 客户对接人审 (B2B 场景)
> - 供应商 (SUPPLIER_CONTACT)
> - 发起人 (REQUESTOR_SELF) — 自审 (e.g. 自助调休)
> - + 5 个 ?  (verify PR #55 H merge 后)
>
> **Scope (3d)**:
> 1. **Day 1 ApprovalWorkflowExecutor wiring**:
>    - 每 new DecisionType impl `resolveAssignee(DecisionTypeContext)` method
>    - Strategy pattern (1 class per DecisionType) OR switch in ApprovalWorkflowExecutor (Steve 二选)
>    - 推荐: 1 interface `AssigneeResolver` + 17 @Component impl + Map<DecisionType, AssigneeResolver> in Executor
>    - Unit test per resolver (17 tests min)
> 2. **Day 2 admin UI dropdown**:
>    - PR #59 A `WorkflowAdminConfig.vue` (假设 ship) 加 decisionType dropdown
>    - PR #55 H 已加 enum frontend type, wire into admin form
>    - dropdown 显 中文 description + 英文 enum (e.g. "上级 (REPORT_TO)")
> 3. **Day 3 1-2 demo workflow per new DecisionType**:
>    - Sample data (per Sprint 5 seed) 加 2 个 demo workflow (e.g. 出差申请 → 上级 → 部门负责人 → 财务总监)
>    - E2E test: 创建 workflow → trigger → assignee 正确 → 各 resolver 跑
>
> **依赖**: PR #55 H + PR #59 A merge
>
> **Spec inputs**:
> - 31-doc §I (Round 11 workflow 决策路由)
> - 32-doc §D X4 (Round 12 workflow 6 sub-menu)
> - 33-doc §F (Round 13 RBAC 1746 f_no — decisionType 跟权限交互)
> - PR #55 H DecisionType.java + frontend type definitions
>
> **DOD**:
> - 17 resolver impl + 17 unit test pass
> - admin UI dropdown 渲染 17 + 14 = 32 values, 中文 description 全
> - 1 demo workflow E2E pass (创建 → trigger → resolver → assignee 收到 → 审 → 完)
>
> **PR**: 1 PR `sprint6/W3-B-decisiontype-wiring`

---

## §W4-B Track WagePolicy 按时/混合 modes + 月底 trigger (P1 3d)

### Brief (paste-ready)

> Sprint 6 Track W4-B — WagePolicy 按时/混合 mode + 月底自动 trigger 工资凭证 (P1 3d).
>
> **Context**: Sprint 5 PR #57 E ship Wage trigger (ProcessingService 钩 + 计件 PieceRateRule). **只 ship 按件 mode**. F006 客户场景: 部分员工按时 (8 小时 × 时薪) / 部分员工混合 (基础 + 计件提成). 必须扩展.
>
> **Scope (3d)**:
> 1. **Day 1 entity 扩展**:
>    - 扩 `WagePolicy.java` 加 mode enum (PIECE_RATE / HOURLY / MIXED) — 默认 PIECE_RATE 兼容 PR #57
>    - 新 entity `HourlyRateRule` (employeeId/baseHourlyRate/overtimeMultiplier/effectiveFrom/effectiveTo)
>    - 扩 `WageCalculation.java` 加 mode + hourlyAmount + pieceRateAmount + totalAmount derived getter
>    - Flyway `V20260520_03__wage_policy_modes.sql` (alter wage_policy + add hourly_rate_rule)
> 2. **Day 2 calculation service**:
>    - 扩 `WageCalculationService.calculateMonthly(factoryId, month, employeeId)` 按 mode 分支:
>      - PIECE_RATE: 现有 logic (per PR #57)
>      - HOURLY: hours × baseHourlyRate + overtime × overtimeMultiplier
>      - MIXED: HOURLY + PIECE_RATE 合 (per WagePolicy.mixedFormula SpEL or simple sum)
>    - 单元 test per mode (3 tests min) + edge case (mode change mid-month)
> 3. **Day 3 vflag listener + admin UI**:
>    - vflag listener: `Voucher.businessKey="WAGE_MONTHLY"` 月底 1 号 auto-fire `WageCalculationService.calculateMonthly` for all employees, batch generate 工资凭证
>    - Schedule via existing `@Scheduled` infra (per `ShedLockConfig`)
>    - admin UI: `WagePolicyConfig.vue` (Canvas → 人事 → 工资策略) 加 mode dropdown + HourlyRateRule CRUD section
>    - 防呆: rule 2 context (员工名) / rule 3 mode dropdown / rule 1 hourly rate max ¥500/h (防误输)
>
> **依赖**: PR #57 E merge (WagePolicy.java 基础 entity)
>
> **Spec inputs**:
> - 32-doc §C.3 (Round 12 HR-WAGE 模块)
> - 33-doc §13 (Round 13 wage 5 modes finding)
> - PR #57 E WagePolicy.java + PieceRateRule.java
>
> **DOD**:
> - 3 mode unit test pass
> - 月底 1 号 auto-trigger 工资凭证 (test via @Scheduled fixed clock injection)
> - admin UI WagePolicyConfig 3 mode 切换 + HourlyRate CRUD
> - F006 sample 1 员工每 mode 算对 + 真凭证生成
>
> **PR**: 1 PR `sprint6/W4-B-wage-modes`

---

## §W4-C Track BomVersion 4 batch UI + ECN paginated list (P1 2d)

### Brief (paste-ready)

> Sprint 6 Track W4-C — BomVersion 4 batch UI buttons + ECN paginated list + impact report (P1 2d).
>
> **Context**: Sprint 5 PR #55 H ship BomVersion 基础 frontend. **缺**: 4 批量操作 (批量修改/替换/删除/新增) + ECN (Engineering Change Notice) paginated list + 影响报告 dialog. Round 11 §H.5 X3 finding.
>
> **Scope (2d)**:
> 1. **Day 1 BomVersion 4 batch UI**:
>    - `BomVersionList.vue` 加 4 button: 批量修改 / 批量替换 / 批量删除 / 批量新增
>    - 选中 N rows + 弹 dialog 输入 batch 参数
>    - Backend: `BomVersionService.batchUpdate(ids, fieldChanges)` / `batchReplace(ids, oldComponent, newComponent)` / `batchDelete(ids)` / `batchAdd(parentBomId, componentList)` (4 REST 加 `/batch-update` etc.)
>    - Reverse-query: 新 page "某物料挂哪些 BomVersion" — `MaterialAttachedBomList.vue` + REST `GET /bom-version/by-component/{componentId}`
> 2. **Day 2 ECN paginated list + impact report**:
>    - 新 entity `Ecn` (id/title/changeReason/impactedBomVersionIds/status enum DRAFT/PENDING_APPROVAL/APPROVED/REJECTED/IMPLEMENTED/createdAt+by + soft delete)
>    - Flyway `V20260520_04__add_ecn.sql`
>    - REST `EcnController` (POST/GET pagedList/PUT/DELETE)
>    - frontend `EcnList.vue` + `EcnDetail.vue` (impact report dialog 显 影响 BomVersion list + cascading approval link)
>    - 集成 Canvas-Workflow: ECN approval workflow trigger on submit (per Sprint 5 PR #54 G RBAC + PR #55 H decisionType)
>    - 防呆: rule 2 context (ECN title + impacted BOM count) / rule 5 dead-end (无 approval workflow 配置 → 跳 navigation per rule 5)
>
> **依赖**: PR #55 H merge (BomVersion frontend + DecisionType enum)
>
> **Spec inputs**:
> - 31-doc §H.5 X3 (Round 11 BomVersion batch + ECN finding)
> - 32-doc §B.3 (Round 12 ECN 8 阶段 finding)
> - `BomVersion.java` existing entity (already in main)
>
> **DOD**:
> - 4 batch operation E2E pass (创建 5 BomVersion → 批量修改 1 字段 → list 显新值)
> - Reverse-query page 选 1 component → 显 N BomVersion
> - ECN CRUD + paginated list (默认 page=20) + impact report 显 N 影响 BomVersion
> - ECN 提交 → workflow approval trigger → assignee 收到 → 审 → 完
>
> **PR**: 1 PR `sprint6/W4-C-bom-batch-ecn`

---

## §N 并行 dispatch 模式

**4 chat 全并行 (推荐)** — ~2-3 工作日 ship 4 PRs:
- Chat 1: W2-C-1 微信 backend (2d)
- Chat 2: W2-C-2 通话 backend + OSS (3d, 最慢)
- Chat 3: W3-B decisionType wiring (3d)
- Chat 4: W4-B WagePolicy modes (3d)
- (W4-C BomVersion 2d 可 Chat 5 OR organizer 亲做)

**worktree isolation 必须** — 4 个独立 git worktree (per `feedback_concurrent_edit_safety.md` HARD).

**Dispatch 前检查**:
- ☑ Sprint 5 PR #53 / #55 / #57 / #59 全 merged (W2-C-1 / W3-B / W4-B / W4-C 依赖)
- ☑ Sprint 6 wave 1 PRs (#65-#70) review 完, 不冲突
- ☑ Steve sign-off "继续 Sprint 6 wave 2"
- ☑ gh ops budget ≤10/hour (per [[feedback_github_anti_abuse_burst]])

**预期 PR 数**: 4-5 PRs (W2-C-1 / W2-C-2 / W3-B / W4-B / W4-C)

---

## §O 不在 Sprint 6 (Sprint 7+ 候选)

| Item | 工时 | 依赖 | 客户群 |
|---|---|---|---|
| W1-A 数电票真集成 | 10d | Steve provider 决策 (百望/航天/诺诺) | 大客户硬需 |
| 链 chip drilldown 拆 file/image/contract | — | PR #70 W3-A ship 后 P2 followup | F006 + 通用 |
| 辅助核算 OUTSOURCER generator | 1d | PR #69 W4-A ship | 通用 |
| RBAC 3-role × 5-scope E2E matrix | 5d | PR #68 W2-B ship | 大客户测试覆盖 |
| Customer 17 tab 微信/通话 → 微信 OAuth 真集成 | 5d | W2-C-1 + 第三方 API | 大客户 deep CRM |
| 复式记账 F-VOUCHER-2 | 8d | PR #69 辅助核算 ship + 财务 spec | 大企业财务 |
| 期间结账 F-PERIOD | 5d | F-VOUCHER-2 | 大企业财务 |
| 报表三表 F-3REPORT | 8d | F-VOUCHER-2 + F-PERIOD | 大企业财务 + 上市公司 |
| 商机 8 阶段 (CRM funnel) | 6d | Customer + 销售机会 entity | 大客户 sales |
| 业绩 6 项 (sales target/commission/quota) | 4d | 商机 8 阶段 | F006 销售 |
| Round 14 HJ 端到端 demo benchmark | 5d | Sprint 6 ship | Boss 演示 |

**Sprint 7 nominal 工时**: ~60d (vs Sprint 6 ~13d) → 2-3 周 4 chat 并行.

---

## §P 后续 organizer todo

1. **Sprint 5 merge 完** → check 17 new DecisionType actual list (PR #55 H merge 后 grep), update §W3-B brief 真值
2. **Sprint 6 wave 1 PRs (#65-#70) merge 完** → fire wave 2 (本文 4-5 PRs)
3. **Wave 2 ship 完** → 决定 Sprint 7 优先级 (Round 14 vs 大企业财务 vs CRM 微信 OAuth)
4. **每 wave ship 完** → MEMORY.md 记 lessons + PR retrospective

---

**Sprint 6 detailed track briefs v1.0 完成 (2026-05-19)**.

Sprint 6 wave 1 PRs (#65-#70) merge → fire 本文 4 brief 立即开 wave 2.
