# Dispatch 台账 — ACTIVE

**单一写者**: Opus Organizer（本 chat）。Worker 不直接修改此文件。
**读法**: Organizer 每轮只读此文件，不读全历史对话 → 薄、可重启、零成本接手。
**防撞**: 派活前查 Scope 锁地图，重叠 → 串行 / 切 scope，绝不并发改同一文件。
**规范**: 详见 `.claude/rules/organizer-protocol.md` + `.claude/rules/multi-model-dispatch.md`。

> ⚙️ **Fleet 现状 (2026-06-07)**: **Codex/GPT 暂停**(GPT 10x 额度用尽) → **出 Claude 池只剩 Composer 2.5**。
> 路由临时调整: 改文件/UI/样式/lint → Composer(唯一出池); **跑终端/headed E2E/构建/TDD/查日志 → 回 Claude 20x 桶**(Sonnet subagent 或 Steve 开的 low/med Sonnet chat),**别硬塞 Composer**(它弱在 CLI/E2E/构建);判断/红线/终审 → Opus 自留。GPT 恢复后撤销此行。

> 📌 **基线 (2026-06-07 organizer intake)**: 三份交接线侦察 + 收尾。S1 采购到付款 / S2 六扇门报工 / S3 Phase2a coref 侦察结论 = 大部分已 shipped。本轮收尾 T101–T106 已全部完成(除 T103 需 Steve 真机)。详见 Done 区。
> ⚠️ effort×model 路由按 memory `project_2026_06_07_organizer_routing_refinements_pending` 执行(未落规则);不需要 high 的活输出给 Steve 自己拨(subagent effort 锁死)。

---

## In-flight 任务表

| ID | 任务 | model | effort | orchestration | 分支 | scope 锁 | 状态 | PR | 阻塞 |
|---|---|---|---|---|---|---|---|---|---|
| T103 | S1 🖐️真机走一单 录音→`voiceAudioUrl` OSS 验证 | Steve(手动) | - | - | - | - | ⬜ pending | - | 需真机(不可自动化;APK 已装小米 f79c50d6, API 指向 prod 8086) |
| T108 | #1 菜品续接 Phase2b(DISH coref 镜 2a) | Sonnet subagent→Opus gate | locked(判断走 Opus 本体) | inline | feat/restaurant-dish-coref-p2b | EntitySlot/ConversationMemory/QueryPreprocessor/ToolDispatch/orchestrator/gold dish tool | 🟡 in-progress | - | running(#549 未碰 orchestrator 故无撞);判断我做,待 PR 我终审+部署 |
| T110 | 餐饮专属角色(chef/purchaser/owner)增量 scope A | Sonnet subagent→Opus gate | locked | inline | feat/restaurant-roles-chef-purchaser | FactoryUserRole.java + PermissionServiceImpl.java(+可选 Flyway/账号 SQL) | 🟡 in-progress | - | 🔒 权限+业态;执行到 PR+账号SQL 停,Opus 终审+部署+亲建账号;解锁 T103 真实角色走单 |

<!--
状态: ⬜ pending / 🟡 in-progress / 🟠 review/待终审 / 🟢 已合并待部署 / ✅ done / 🔴 blocked
格式参考:
| T001 | KPI 看板前端 | Composer | default | inline | feat/kpi-ui | web-admin/src/views/kpi/ | 🟡 in-progress | - | 等后端 T002 |
-->

---

## Scope 锁地图

> 派活前必查。两 task 重叠同一路径 → 串行 或 重切 scope，绝不并发改同一文件。

| 文件 / 目录 | 锁定 task | 预计解锁 |
|---|---|---|
| `IntentExecutionOrchestrator.java` + EntitySlot/ConversationMemory/QueryPreprocessor/ToolDispatch + 餐饮菜品 gold tool | T108 | T108 PR merge 后 |
| FactoryUserRole.java + PermissionServiceImpl.java | T110 | T110 PR merge 后 |

---

## Done（待清理）

> PR 合并后的 task 移到这里。每周清一次。

| ID | 任务 | PR | 完成时间 | 备注 |
|---|---|---|---|---|
| T101 | S1 API 冒烟自动造数(高价异常+绑供应商 DRAFT) | #540 | 2026-06-07 | 5 次 prod 实跑审批链全 PASS;test-script 无需 deploy |
| T102 | S1 对账冻结月 `unReconciledNotes` 只读可见性(Option B) | #543 | 2026-06-07 | 🔒财务 Opus 终审过;**已部署 prod** — jar 含 `buildUnReconciledNotes`/`findOrphanNotesNotInReconciliation`,web-admin 8086 含防呆 banner(Rule 5 next-action);prod 实证 20 孤儿单 |
| T104 | S2 发货应收幂等 | #542 | 2026-06-07 | 守卫早 live(`3f26931f5` on main);#542 仅补缺失回归测试(3 case)test-only |
| T105 | S3 Phase2a coref prod live 验收 | 证据 md | 2026-06-07 | 4/4 判据 PASS;active jar 确含 STORE coref;工厂 SUPPLIER 零回归。证据: `docs/superpowers/handoffs/2026-06-07-phase2a-store-coref-prod-live-verification.md` |
| T106 | f006p1 两 bug(carry-over override + preview LLM 抽参) | #544 | 2026-06-07 | 🔒AI执行路径 Opus 终审过;**已部署 prod** — jar 含 `getEstimatedMinutesOverride` carry-over;backend blue:10010 v20260607_104835 |
| T109 | #2 全天备货看板(restock board) | #466 | 2026-06-07 | 🔎 侦察发现**早已 shipped+部署** — spec/plan/Flyway `V20260913_01`(无撞号)/16测试/web-admin view 全在 main(+horizon `0f31657f9`+audit `21ab30dfe`)。三层去重已正确(FG/WIP/PLANNED+PENDING 互斥)。未造重复。 |
| T107 | #3 澄清 padding COMMON-overload | #549 | 2026-06-07 | 🔒Flyway Opus 终审过(`V20260928_03` 无撞号,幂等)。根因=`MATERIAL_BATCH_QUERY`/`PROCESSING_BATCH_LIST` business_type=COMMON 泄漏餐饮澄清→重标 FACTORY(餐饮过滤/工厂零回归)。测试 6/6+70/70+15/15。**merged,待部署**(批入 T110 一次后端 deploy)。 |

---

## housekeeping (非任务,待清理)

- `git worktree prune` — `cretas-liushanmen-wip-close` / `cretas-liushanmen-e2e-run` 目录已消失但 ref 还在 (prunable)。
- 删远端分支 `origin/feat/restaurant-store-coref-p2a` (已 merge,0 ahead)。
- 清理 deploy worktree `cretas-deploy-543544` (本轮 #543/#544 部署用,完成后可删)。
- 主目录 `my-prototype-logistics` 落后 origin/main ~42 commit,且有 organizer 早期对 stale 台账的误编辑(未 commit,可 `git checkout -- docs/dispatch/ACTIVE.md` 丢弃) — Steve 择机 pull。

---

## 使用流程（给 Organizer 自己）

```text
1. 接到 Steve 新任务
   → 查 Scope 锁地图：有无冲突？
   → 拆解 → 写 brief 卡 → 填入 In-flight 表（ID/model/effort/orchestration/分支/scope锁）
   → 不需要 high effort 的活 → 输出 brief 卡给 Steve 自己拨(subagent effort 锁死)
   → 更新 Scope 锁地图

2. 派发 brief 卡
   → In-harness (Sonnet): organizer spawn subagent (effort 锁死, 只能选 model)
   → Out-of-harness (Composer / Steve 自开 Sonnet chat): 出卡 → Steve courier(可拨 effort)

3. PR 回来
   → 验 scope 干净: git diff origin/main...HEAD --stat
   → 🔒 risky: Opus 终审 → merge main → 从 main 部署 prod
   → 例行: Sonnet review → merge main

4. 完成后
   → In-flight 表标 ✅ done → 移到 Done 区 → 释放 Scope 锁
```
