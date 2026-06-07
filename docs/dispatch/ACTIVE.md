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
| T103 | S1 🖐️真机走一单 录音→`voiceAudioUrl` OSS 验证 | Steve(手动) | - | - | - | - | ⬜ pending | - | ✅已解锁(真实餐饮角色 qhj_chef/qhj_purchase_mgr/qhj_owner 已建+验登录);需真机(APK 已装小米 f79c50d6) |
| T112 | 餐饮问答优化 impl(①反投毒守卫+②绑峰值+清毒) | Sonnet→Opus gate | locked | inline | (merged) | IntentRecognitionPipelineServiceImpl + Flyway | 🟢 merged 待部署 | #553✅ | 🔒 Opus 深 gate 过:25行 DISH_DELETE 确是真毒(含SQL注入串);守卫保守(tool_name代理+业态);**deploy held 等 T116 批量**(T116 在 prod debug,切端口会断它) |
| T117 | 修 AI配工序草稿渲染bug(PRODUCT_WORK_PROCESS_DRAFT 吐 raw type 没渲染10工序卡) | Sonnet→Opus gate | locked | inline | fix/ai-workprocess-draft-render | web-admin AIChat/canvas 渲染(+可能后端响应shape) | 🟡 in-progress | - | 前端渲染缺口;headed 验;到 PR gate+部署 web-admin |
| T116 | 修菜品 coref D2 dormant | Sonnet→Opus gate | locked | inline | (merged) | IntentRecognitionPipelineServiceImpl(ensureDishReferenceResolved 镜 STORE) | 🟢 merged 待部署 | #554✅ | 🔒 Opus gate 过(根因: STORE 读 originalInput 有 fallback, DISH 缺→补;100/100含 IntentParity 70/70)。与 #553 同文件**已确认共存**(不同方法,auto-merge)。**deploy held 等 T117 批量** |
| T115 | 飞轮分层+二次分析(中置信→staged + promote pipeline + **2588 NULL 行存量审计**) | Sonnet→Opus gate | locked | inline | (待 T112 部署后开) | IntentRecognitionPipelineServiceImpl 学习路径 + learning_* 表 + ai_learned_expressions | 🔴 blocked | - | 🔒 T112 merge 解锁。**新增 scope**: is_active=NULL=active(2588行,247=true)→二次分析须复核存量 NULL 行(含 vip→PROCESSING_BATCH_CREATE hit6 等存量毒)+ 厘清 NULL 语义 + 中置信0.70-0.89→staged。复用 learning_suggestions/tasks |

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
| — (T110 已合并部署,锁释放) | — | — |

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
| T109 | #2 全天备货看板(restock board) | #466 | 2026-06-07 | **✅ T113 深验 PASS**:2 产品(白卤猪舌/猪蹄)逐层 DB 对账=API 完全吻合;FG 软删过滤/WIP deletedAt/Scheduled仅PLANNED+PENDING(IN_PROGRESS排除无双计)/shortfall=max(0,需求−合计)。三层去重真确认正确(不止"存在于main")。 |
| T107 | #3 澄清 padding COMMON-overload | #549 | 2026-06-07 | 🔒Flyway Opus 终审过(`V20260928_03` 幂等)。根因=`MATERIAL_BATCH_QUERY`/`PROCESSING_BATCH_LIST` business_type=COMMON 泄漏餐饮澄清→重标 FACTORY。测试 6/6+70/70+15/15。**已部署** green:10020 v20260607_122604(prod 实证 business_type=FACTORY)。 |
| T110 | 餐饮专属角色 chef/purchaser/owner(scope A 增量) | #550 | 2026-06-07 | 🔒权限+业态 Opus 终审过。enum+权限矩阵最小化(chef warehouse:rw/purchaser procurement:rw+价格可见/owner 全+财务审核),@RequireRole 增量,**无 Flyway**,42测试。**已部署** green:10020 + **prod 建 3 账号验登录**(qhj_chef/qhj_purchase_mgr/qhj_owner /123456,factoryType=RESTAURANT)。界面已由 factoryType 分开,本次只分角色命名空间。 |
| T108 | 菜品续接 Phase2b(DISH coref 镜 2a) | #551 | 2026-06-07 | 🔒AI执行路径 Opus 终审过("它"仅 DISH 槽+解析序 DISH→STORE→SUPPLIER,107测试+70/70+15/15)。**已部署** blue:10010 v20260607_132848。D4 STORE/SUPPLIER 零回归实测 PASS。**⚠️ T113 复验暴露: 菜品数据其实存在(D1 返真 Top5),但 D2"那道菜"返全 Top5 未过滤=coref DORMANT**(gate miss: 我只验单测+D4 没 live 验真功能)→ **T116 修中**。 |
| — | 📌 餐饮问答 A+B+C 基线侦察结论 | — | 2026-06-07 | speed agent("消灭LLM")+ content agent("8%/0 gold 实现")**均过度声称,Opus 交叉验证否决**。真相: gold 工具全在+营收返真数据(¥940 6月/¥11.57M 3月峰),"暂无菜品数据"=诚实空(demo工厂)。真问题窄=跨域误路由(平台/VIP/美团)+飞轮投毒+少数真缺维度→T112。 |
| T114 | §8 基建 deploy-staging CI libcrypto | #552 | 2026-06-07 | CI-only(`echo key>id_rsa` 丢尾换行→OpenSSH8.9+严格PEM拒→改 `webfactory/ssh-agent@v0.9.0`,secret不变)。1文件10/10,下次CI生效无需部署。§8b test采购账号401=**非bug**(e2e_purchase_mgr 实测存在+active+登录success,历史401是seed未跑;无码改)。 |

---

## Deferred (Opus organizer 有意决定 2026-06-07)

- **Maestro 9 步原生 E2E** → DEFER。程序流已有 RN Expo Web smoke + API 全链 E2E 覆盖;Maestro 原生设备自动化边际价值低 + 装/testID/跑需真机(半阻塞)+ demo 无真实客户 = gold-plating。有真原生回归需求时再投。
- **③ 平台/VIP 营收拆分 ETL** → BLOCKED on 二维火 POS creds(gold 无渠道维度,无数据源)。用户面风险已由 T112 反投毒+abstain 兜底(诚实空状态非自信错答)。Steve 给二维火 creds 时解锁:ETL + gold 渠道维度 + 绑工具。
- **③ 平台/VIP 评价** → 工具就绪(17 个 RESTAURANT_REVIEW_* 已绑+emptyMessage),仅缺数据上传(大众点评报表),非代码问题。

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
