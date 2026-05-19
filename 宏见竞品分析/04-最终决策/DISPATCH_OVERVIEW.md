# Sprint 1 ASAP — 6 Chat 并行调度总览 (v2 更新)

> ⚠️ **DEPRECATED 2026-05-19** — 本文件已 supersede by `31-DEEP-RE-AUDIT.md §P` (Round 11 deep re-audit). Sprint 1+2 track 已全 ship, 决策面 status 请直接看 31-doc + `30-BACKLOG-STATUS-AUDIT.md §13`. 本文件保留作为 historical reference (Sprint 1+2 时点 brief / 计划 / dispatch).


> **本文件用途**: Organizer (Chat 1, 这个 chat) 的协调视图
>
> **执行机制**: 6 个 worker chat (Chat 2-7) 并行做实现, Chat 1 协调 + review
>
> **总工时**: 名义 48d / Claude 加速 ~28 工作日 / 单人 ~6 周 → **6 chat 并行 ~1 周完成**
>
> **2026-05-14 调整**: 原 4 track 升级到 6 track (拆 B → B1/B2, 拆 D → D1/D2 因为这两个 track 工时最大)

---

## 1. Track 划分 (6 tracks)

| Track | Chat | 焦点 | 工时 | 涉及业务 |
|---|---|---|---|---|
| **A** | Chat 2 | Canvas 死代码修复 | **9d** | 前端 Canvas + AILayout + PageEditor |
| **B1** | Chat 3 | AI 钉钉子轨道 | **6d** | 钉钉机器人 PoC |
| **B2** | Chat 6 | 抄码 + PDF 子轨道 | **6d** | 抄码品识别 + PDF 扫码 RN |
| **C** | Chat 4 | Attachment + 打印 + 三价 + RBAC | **11d** | 通用附件 + 单据打印 + 财务 bug + RBAC 审计 |
| **D1** | Chat 5 | BOM 子轨道 | **9d** | BOM 配方 UI + BOM 物料选择 bug + 单位转换 bug |
| **D2** | Chat 7 | 工序子轨道 | **7d** | 工序管理 + 产品工序配置 + 工序通用 bug |
| **Organizer** | Chat 1 (本) | 协调 + review + 集成 | **不实现** | 跨 chat 协调 + PR review + 冲突解决 |

**Total**: 48 人天名义 = ASAP Phase 0 + Sprint 1 全部内容, 平均 8d/chat (vs 之前 12d/chat)

---

## 2. 文件 Ownership (防冲突, 6 tracks)

| Track | 拥有目录/文件 |
|---|---|
| **A** | `frontend/CretasFoodTrace/src/screens/lowcode/` + `backend/.../service/impl/DecorationServiceImpl.java` + `ai/tool/impl/pagedesign/` + `ai/tool/impl/decoration/` |
| **B1** | NEW `backend/.../service/dingtalk/` + NEW `backend/.../entity/integration/DingTalkWebhookLog.java` |
| **B2** | `ai/tool/impl/material/` (抄码品扩展) + `frontend/.../screens/shared/LabelScanScreen.tsx` (PDF 扫码) |
| **C** | NEW `backend/.../entity/Attachment.java` + NEW `backend/.../service/attachment/` + Flyway V20260516_01__attachment.sql + `frontend/.../screens/smartbi/` (三价对比 bug) + `ai/tool/impl/finance/RBACAuditTool.java` |
| **D1** | `backend/.../entity/bom/` + Flyway V20260516_02__bom_redesign.sql + 工厂端 NEW BomConfigScreen + `frontend/.../screens/management/MaterialSpecManagementScreen.tsx` (单位转换) |
| **D2** | NEW `backend/.../service/workprocess/` + Flyway V20260516_03__work_process.sql + WorkProcessListScreen + ProductWorkProcessConfigScreen + 生产计划页 (工序通用 bug) |

**共享只读** (任何 track 不准改, 改必先 ping organizer):
- `backend/.../entity/BaseEntity.java`
- `backend/.../service/impl/IntentExecutorServiceImpl.java`
- `frontend/.../services/api/aiApiClient.ts`
- `CLAUDE.md` 项目规范文件

**Git 策略**: 每 track 一个 worktree + 独立 branch (推荐 `feature/asap-track-{A|B|C|D}-{编号}`)

---

## 3. 依赖关系图

```
                 ┌─ Track A (Canvas) ─┐
                 │   (Phase 0 必先)    │
Organizer ──────┤                     │
                 ├─ Track B (AI钉钉)   ├── 三 track 并行
                 ├─ Track C (Attach)   │
                 └─ Track D (BOM/工序)─┘
                     │
                     └─ Track C attachment 接好后, B/D 可 import 用
```

**关键依赖**:
- Track C 的 `attachment` 实体 → 提供给 Track A/B/D 用 (Day 3 后)
- Track B 的 抄码品 字段扩展 → 不依赖其他, 独立
- Track D 的 BOM 主子表重设计 → 不影响其他 track 的 BOM 用法 (旧 API 兼容期)

---

## 4. 每日 Status 同步机制

每个 worker chat 每天在 `宏见竞品分析/04-最终决策/STATUS/TRACK_{X}_STATUS.md` 追加 1 段:

```markdown
## Day N (YYYY-MM-DD)
- ✅ 完成: X / Y / Z
- 🟡 进行中: A
- ❌ Blocker: B (需 organizer 协调)
- 明日计划: C / D
```

Organizer (Chat 1) 每天读 4 个 STATUS, 处理 blocker, 准备 review。

---

## 5. PR + Review 流程

每个 track 完成一项, 推 PR 到 `main`:
1. PR 标题: `[Track-X] N# 编号 项目名`
2. PR body 含: 涉及文件 / 测试方式 / 风险点
3. Organizer (Chat 1) review + merge
4. 多 track 并行 PR 时, organizer 决定 merge 顺序避免冲突

---

## 6. 冲突处理

如果 Track A 发现需要改 Track D 的文件:
1. Worker chat 在 STATUS 报 blocker
2. Organizer 协调: 要么让 Track A 等, 要么让 Track D 先暴露 interface
3. 紧急: organizer 临时改 (不推荐)

---

## 7. 验收标准 (ASAP 末)

Week 6/7 末, 4 个 track 全部 merge 后:
- [ ] Phase 0 死代码 3 项全修 (Track A 交付)
- [ ] 5 个客户已反馈 bug 全修 (跨 Track B/C/D)
- [ ] 钉钉机器人 PoC 客户能用 (Track B)
- [ ] 通用 attachment 5 模块接入 (Track C)
- [ ] 抄码品识别 (Track B)
- [ ] BOM 配方编辑 UI (Track D)
- [ ] RBAC 仓管隔离审计 (Track C)
- [ ] 单据打印 PDF 起步 (Track C)
- [ ] 工序管理 + 产品工序配置 (Track D)
- [ ] 2 分钟 demo 视频 (Organizer 录)

---

## 8. 给用户的使用指南

### Step 1: 启动 4 个 Chat
打开 4 个新 Claude chat session (Chat 2-5)

### Step 2: 把对应 Brief 发到对应 Chat
- Chat 2 → 复制 `TRACK_A_BRIEF.md` 全文
- Chat 3 → 复制 `TRACK_B_BRIEF.md` 全文
- Chat 4 → 复制 `TRACK_C_BRIEF.md` 全文
- Chat 5 → 复制 `TRACK_D_BRIEF.md` 全文

### Step 3: 让 Chat 2-5 开始干活
每个 chat 收到 brief 后, 它知道:
- 任务范围
- 每日具体动作
- 文件 ownership
- 参考文档
- 何时跟 organizer 同步

### Step 4: 每日来本 Chat (organizer) 同步
- 跟 Chat 1 说: "Track A Day 2 完成 X / Y, 明日做 Z"
- Chat 1 协调跨 track 冲突, 标 STATUS, 决定 review 顺序

### Step 5: PR Review
Chat 1 (organizer) 负责 review + merge 4 个 track 的 PR

---

## 9. 关键参考文档清单 (每个 track 都要读)

| 文档 | 用途 |
|---|---|
| `01-客户档案/NUMBERING_MAP.md` | 所有 N# 编号双向映射 |
| `01-客户档案/能力拆分表.md` | 这项改动影响餐饮还是食品厂 |
| `01-客户档案/SCHEMA_DESIGN.md` | 9 张表完整 DDL + Entity + API spec |
| `01-客户档案/MIGRATION_DESIGN.md` | 产品导入助手设计 (Sprint 1 末用) |
| `00-MASTER-PLAN-v2.md` 附录 A | 销售红线 (2 解除 + 2 仍禁) |
| `00-MASTER-PLAN-v2.md` §9.1 | ASAP 按周排期 |
| `01-客户档案/` 4 次会议 | 客户原话 (当任何决策模糊时回查) |
| `CLAUDE.md` + `.claude/rules/` | Cretas 项目规范 (字段命名 / API / JWT 等) |

---

## 10. 元注意事项

1. **不要在 worker chat 里讨论战略** — 战略决策在 organizer (Chat 1) 拍板
2. **worker chat 要严格按 brief 执行** — 跑偏会导致 4 track 互相不兼容
3. **每个 chat 加 task 跟踪** — worker chat 也用 TaskCreate 跟自己的进度
4. **共享文件改动必先 ping organizer** — Avoid silent breaking changes
5. **每个 track 完成一项就 PR 一次** — Avoid 大爆炸 PR
6. **Claude 加速倍数预期 1.7-2x** — 名义 9d 实际 ~5-6 工作日

---

## 11. 紧急联系点 (organizer 心智 checklist)

| 信号 | 我的动作 |
|---|---|
| Track A 死代码 sessionId 又出问题 | 立即让 Track A 停, 验证 v2 § 销售红线决策 |
| Track B 钉钉 webhook 跑不通 | 帮 Track B 看 PythonLLMClient 配置 |
| Track C attachment 多业务接入冲突 | 协调谁先 import, 排队 |
| Track D BOM 重设计破坏现有 API | 立即让 Track D 加 backward compat layer |
| 任何 track 工时 >> 名义 1.5 倍 | 协调减 scope 或拉外援 |

---

**下一步**: 把 4 份 TRACK BRIEF 发给 4 个 chat, 开始并行干活。Chat 1 (本) 等他们的 STATUS update。

---

# Sprint 2 — 6 Chat 并行调度 (Week 8-10)

> **添加日期**: 2026-05-15
> **基于**: `SPRINT_2_PLAN.md` 全文拆分
> **状态**: 6 份 Brief 已写, 6 份 STATUS 已建, **等 Steve 派发**

---

## 12. Sprint 2 总览

**执行机制**: Sprint 1 (Week 6-7) 完成 ASAP 后, 启动 Sprint 2。6 个 worker chat (Chat E-J) 并行干活, Chat 1 协调 + review。

**总工时**: 名义 39d / Claude 加速 ~23 工作日 / 6 chat 并行 ~15 工作日 = **Week 8-10**

| Chat | 编号 | 项 | 工时名义 | 加速 | 类型 | Brief 文件 |
|---|---|---|---|---|---|---|
| **E** | S-MRP-1 | N31 销售→采购自动分流 | 4d | ~2.5d | 业务后端 | `TRACK_E_BRIEF.md` |
| **F** | S-RD-1 | N48 研发样品→BOM→报价 | 5d | ~3.5d | 业务全栈 | `TRACK_F_BRIEF.md` |
| **G** | U-NAV-1 | UX-A1 业务流程图导航 | 10d | ~6d | UX RN+Vue | `TRACK_G_BRIEF.md` |
| **H** | U-ACT-1 | UX-A2 行末操作下拉 | 10d | ~6d | UX RN+Vue | `TRACK_H_BRIEF.md` |
| **I** | U-FOOTER-1 | UX-A3 Sticky Footer 实时合计 | 7d | ~4.5d | UX RN+Vue | `TRACK_I_BRIEF.md` |
| **J (新加)** | P-FIN-1 | 采购单财务审核+三价标红 | 3d | ~2d | 业务后端+小前端 | `TRACK_J_BRIEF.md` |
| **Organizer** | — | Chat 1 协调 + review | — | — | 不实现 | 本文件 |

**Total**: 39 人天名义 = 6 chat 并行 ~15 工作日

---

## 13. Sprint 2 文件 Ownership (6 chats)

| Chat | 拥有目录/文件 |
|---|---|
| **E** | NEW `backend/.../service/shortage/` + NEW `ai/tool/impl/shortage/ShortageAnalysisTool.java` + NEW `frontend/.../screens/sales/SalesOrderShortageReviewScreen.tsx` + NEW `frontend/.../components/chain/ShortageChainCard.tsx` |
| **F** | NEW `backend/.../entity/sample/SampleRequest.java` + Flyway V20260601_03 + NEW `service/sample/` + NEW `ai/tool/impl/sample/SampleToBomTool.java` + NEW `frontend/.../screens/rd/` |
| **G** | NEW `frontend/.../components/workflow/` + NEW `web-admin/src/components/workflow/` + NEW `backend/.../controller/WorkflowStatsController.java` + 修改 5 角色 HomeScreen + 5 Vue ListView |
| **H** | NEW `frontend/.../components/list/RowActionBottomSheet.tsx` + NEW `web-admin/.../components/list/RowActionMenu.vue` + NEW `frontend/.../hooks/useRowActions.ts` + 8 RN list + 8 Vue list 修改 |
| **I** | NEW `frontend/.../components/list/StickyFooterSummary.tsx` + NEW `web-admin/.../components/list/TableFooter.vue` + NEW `backend/.../controller/ListSummaryController.java` + 10 RN list + 10 Vue list 修改 |
| **J** | NEW `backend/.../entity/purchase/PurchaseOrderApprovalFlow.java` + NEW `service/purchase/PurchaseOrderApprovalService.java` + Flyway V20260601_05+06 + NEW `ai/tool/impl/purchase/PurchaseOrderApproveTool.java` + NEW `frontend/.../screens/purchase/PurchaseOrderApprovalScreen.tsx` + NEW `frontend/.../components/purchase/PriceComparisonTable.tsx` |

**共享目录冲突点**:
- Chat H + Chat I 共享 `frontend/.../components/list/` + `web-admin/.../components/list/` 但**不同文件** — commit 前 `git status` 必须确认

**共享只读** (任何 chat 不准改, 改必先 ping organizer):
- `backend/.../entity/BaseEntity.java`
- `backend/.../service/impl/IntentExecutorServiceImpl.java`
- `backend/.../ai/tool/AbstractBusinessTool.java`
- `frontend/.../services/api/aiApiClient.ts`
- `CLAUDE.md` + `.claude/rules/*`
- **Sprint 1 ship 的 6 个 track 核心文件** (尤其 Track D1 BOM 跟 E/F 强耦合, Track C Attachment 跟 F 强耦合)

---

## 14. Sprint 2 依赖图

```
Sprint 1 ship → Sprint 2 解锁
─────────────────────────────────────────────────────────
Track A Canvas (sessionId + LLM + PageEditor) ──┬─→ Chat E (N31) AIChat sessionId 多轮
                                                  ├─→ Chat F (N48) AILayoutAssistant 推 BOM
                                                  └─→ Chat G/H/I AI 入口多轮

Track B1 钉钉机器人 ─────────────────────────────┬─→ Chat E (N31) 缺料推送钉钉群
                                                  ├─→ Chat F (N48) 样品审核通知钉钉
                                                  └─→ Chat J (P-FIN-1) 标红通知财务

Track B2 抄码品识别 + PDF 扫码 RN ───────────────→ Chat E (N31) 采购单可含抄码品物料

Track C 通用 Attachment ──────────────────────────┬─→ Chat F (N48) 样品照片 5+ 附件
                                                   └─→ Chat G (UX-A1) 流程节点挂证据照片
Track C 单据打印 PDF ─────────────────────────────┬─→ Chat H (UX-A2) "打印 PDF" action
                                                   └─→ Chat I (UX-A3) sticky footer 导出
Track C 三价对比 PR #660 ────────────────────────┬─→ Chat E (N31) 推荐采购三价数据
                                                   └─→ Chat J (P-FIN-1) 三价标红规则
Track C RBAC 审计 ───────────────────────────────┬─→ Chat H (UX-A2) BottomSheet 价格隐藏
                                                   ├─→ Chat I (UX-A3) sticky footer 金额隐藏
                                                   └─→ Chat J (P-FIN-1) 财务审核端点 gate

Track D1 BOM 配方编辑 UI ─────────────────────────┬─→ Chat F (N48) 强依赖 BomService.createFromSample
                                                   └─→ Chat E (N31) 缺料判断准确性
Track D1 BOM 物料选择器 + 单位转换 ───────────────┬─→ Chat E (N31) 缺料判断准确性
                                                   └─→ Chat F (N48) AI 生成 BOM 时物料 select

Track D2 工序管理 + 产品工序配置 ──────────────────→ Chat E (N31) productionSuggestions[].workProcessIds
```

### 14.1 关键阻断关系

| 阻断关系 | 影响 | Organizer 协调 |
|---|---|---|
| **Chat F 强依赖 Track D1 (BOM)** | D1 未 ship 则 F 没法做 "样品 → BOM" | Sprint 1 末必须 merge D1, F 才能 Day 1 启动 |
| **Chat E 强依赖 Track D2 (工序)** | D2 未 ship 则 E 推荐生产无工序可挂 | Sprint 1 末必须 merge D2 |
| **Chat E + J 中依赖 Track A (Canvas)** | A 未 ship sessionId AIChat 多轮失败 | Sprint 1 必交付, 否则降级 single-turn |
| **Chat F 中依赖 Track C (Attach)** | C 未 ship 样品照片只能跳过 | F 可降级用 mock placeholder |
| **Chat J 强依赖 Sprint 1 PR #660 三价** | PR #660 没 ship → 三价数据不准 | J Day 1 前确认 PR #660 merged |
| **UX-A1/A2/A3 (Chat G/H/I) 弱依赖** | 大部分独立 — 抽组件 + 接入既有 list | 不阻断 |

---

## 15. Sprint 2 时序看板 (Week 8-10)

```
                Week 8                  Week 9                  Week 10
              Mon Tue Wed Thu Fri    Mon Tue Wed Thu Fri    Mon Tue Wed Thu Fri
Chat E (N31)  D1  D2  D3  D4  ▶PR
Chat F (N48)  D1  D2  D3  D4  D5/PR
Chat G (UX-1) D1  D2  D3  D4  D5     D6  D7  D8  D9  D10/PR
Chat H (UX-2) D1  D2  D3  D4  D5     D6  D7  D8  D9  D10/PR
Chat I (UX-3) D1  D2  D3  D4  D5     D6  D7/PR
Chat J (FIN)  D1  D2  D3/PR

Organizer (Chat 1):
  Week 8 W1: review Chat E/F early PR + Chat J 全程
  Week 8 W3-W5: review Chat I PR + 集成
  Week 9 全周: 跟踪 G/H 大件
  Week 10 W1-W2: review G/H 最后 PR
  Week 10 W3-W5: Sprint 2 整体 demo 录 5 分钟 + 客户演示准备
```

**Claude 加速假设 1.7x**:
- E (4d) ≈ 2.5 实际
- F (5d) ≈ 3 实际
- G/H (10d) ≈ 6 实际
- I (7d) ≈ 4 实际
- J (3d) ≈ 2 实际

**3 周 buffer 充足**.

---

## 16. Sprint 2 PR Merge 顺序

多 chat 并行 PR 时, organizer 推荐 merge 顺序:

**J < I < E < F < H < G** (从小到大, 从底层到顶层)

理由:
- J 最小 (3d), 早 ship + 不阻断其他
- I 改 list 底部, 不动列表业务逻辑
- E/F 是新功能, 不冲突 list UI
- H 大改 list 行末 (要先 ship 让 G review)
- G 改顶部 + 业务逻辑联动最深, 最后

---

## 17. Sprint 2 启动前 Organizer 必查清单

Sprint 1 末 (Week 7 周五) Organizer 验收下面才能启动 Sprint 2:

- [ ] Track A 3 PR 全 merge: AILayoutAssistant 接真 LLM ✅ + PageEditor 挂导航 ✅ + Canvas Repository 统一 ✅
- [ ] Track B1 钉钉机器人 PoC 客户群 webhook 跑通 ✅
- [ ] Track B2 抄码品识别 + PDF 扫码 RN ✅
- [ ] Track C 通用 Attachment 5 模块接入 ✅ + 三价对比 PR #660 ✅ + 单据打印 ✅ + RBAC 审计 ✅
- [ ] Track D1 工厂端 BomConfigScreen ✅ + BOM 物料选择 ✅ + 单位转换 ✅
- [ ] Track D2 WorkProcessListScreen ✅ + ProductWorkProcessConfigScreen ✅
- [ ] main 分支可 `mvn spring-boot:run` + `npx expo start` 无报错
- [ ] F006 prod 账号能登, 关键演示路径未 regression

任何一项 ❌ → Sprint 2 对应 chat 推迟启动.

---

## 18. Sprint 2 紧急联系点

| 信号 | Organizer 动作 |
|---|---|
| Sprint 1 Track D1 没 ship | Chat F (N48) 延后, 推到 Week 9; 让 Chat F 先做 SampleRequest Entity 准备 |
| Sprint 1 Track D2 没 ship | Chat E (N31) 推荐生产部分降级, 只做缺料 + 采购 |
| Sprint 1 Track A sessionId 有 bug | UX-A1 + UX-A2 + N31 + N48 + J 的 AI 入口全降级 single-turn |
| Sprint 1 Track C Attachment 没 ship | Chat F 样品照片用 mock placeholder |
| Sprint 1 PR #660 三价没 ship | Chat J 推迟, 让 Sprint 1 紧急 fix |
| Chat G 或 H 工时 >> 12d | 减 scope: 先 5 模块 / 5 list, 剩余推到 Sprint 3 |
| Chat H / I 共享 components/list/ 冲突 | 立即停, 检查 git status, 协调 commit 顺序 |
| F006 prod 账号 regression | 立即 freeze 当天 merge, 回滚最近 PR 直到 fix |

---

## 19. Sprint 2 给用户的使用指南

### Step 1: 启动 6 个 Chat
打开 6 个新 Claude chat session (Chat E-J)

### Step 2: 把对应 Brief 发到对应 Chat
- Chat E → 复制 `TRACK_E_BRIEF.md` 全文
- Chat F → 复制 `TRACK_F_BRIEF.md` 全文
- Chat G → 复制 `TRACK_G_BRIEF.md` 全文
- Chat H → 复制 `TRACK_H_BRIEF.md` 全文
- Chat I → 复制 `TRACK_I_BRIEF.md` 全文
- Chat J → 复制 `TRACK_J_BRIEF.md` 全文 (新加 chat)

### Step 3: 让 Chat E-J 开始干活
每个 chat 收到 brief 后, 它知道:
- 任务范围
- 每日具体动作
- 文件 ownership
- 参考文档
- 何时跟 organizer 同步

### Step 4: 每日来本 Chat (organizer) 同步
- 跟 Chat 1 说: "Chat E Day 2 完成 X / Y, 明日做 Z"
- Chat 1 协调跨 chat 冲突, 决定 review 顺序

### Step 5: PR Review
Chat 1 (organizer) 负责 review + merge 6 个 chat 的 PR

---

## 20. Sprint 2 关键参考文档

| 文档 | 用途 |
|---|---|
| `01-客户档案/NUMBERING_MAP.md` | N# 编号双向映射 (S-MRP-1, S-RD-1, U-NAV-1, U-ACT-1, U-FOOTER-1, P-FIN-1) |
| `01-客户档案/SCHEMA_DESIGN.md` | DDL + Entity + API spec |
| `04-最终决策/MUST_COPY.md` §B (N31, N48) + §D (P1-2) | 业务定义 + 客户原话 |
| `04-最终决策/UX_BORROW.md` §A (Top 3) + §F (示意图) | UX 模式定义 + Cretas 接入策略 |
| Sprint 1 全部 `TRACK_*_BRIEF.md` | Sprint 1 6 chat 交付内容 |
| `04-最终决策/SPRINT_2_PLAN.md` | Sprint 2 主文档 (Day-by-day 来源) |
| `.claude/rules/*` | 全部规范 (ai-intent / api-response / database-entity / field-naming / typescript / concurrent-edit) |

---

**Sprint 2 状态**: ✅ 6 Brief 已写, ✅ 6 STATUS 已建, ⏳ **等 Steve 派发给 6 个新 chat**