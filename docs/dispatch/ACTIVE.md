# Dispatch 台账 — ACTIVE

**单一写者**: Opus Organizer（本 chat）。Worker 不直接修改此文件。
**读法**: Organizer 每轮只读此文件，不读全历史对话 → 薄、可重启、零成本接手。
**防撞**: 派活前查 Scope 锁地图，重叠 → 串行 / 切 scope，绝不并发改同一文件。
**规范**: 详见 `.claude/rules/organizer-protocol.md` + `.claude/rules/multi-model-dispatch.md`。

> ⚙️ **Fleet 现状 (2026-06-07)**: **Codex/GPT 暂停**(GPT 10x 额度用尽) → **出 Claude 池只剩 Composer 2.5**。
> 路由临时调整: 改文件/UI/样式/lint → Composer(唯一出池); **跑终端/headed E2E/构建/TDD/查日志 → 回 Claude 20x 桶**(Sonnet subagent 或 Steve 开的 low/med Sonnet chat),**别硬塞 Composer**(它弱在 CLI/E2E/构建);判断/红线/终审 → Opus 自留。GPT 恢复后撤销此行。

---

## In-flight 任务表

| ID | 任务 | model | effort | orchestration | 分支 | scope 锁 | 状态 | PR | 阻塞 |
|---|---|---|---|---|---|---|---|---|---|
| — | （当前无进行中任务）| — | — | — | — | — | — | — | — |

<!--
示例行（勿删，供参考格式）：
| T001 | KPI 看板前端 | Composer | default | inline | feat/kpi-ui | web-admin/src/views/kpi/ | 🟡 in-progress | - | 等后端 T002 |
| T002 | 后端口径 + migration | Sonnet | high | inline | feat/kpi-api | backend/java/.../kpi/ db/flyway/V*.sql | 🔴 blocked | - | 依赖 T001 完成 scope 确认 |
| T003 | 🔒 上线终审 + prod 部署 | Opus | xhigh | inline | main | — | ⬜ pending | — | 等 T001+T002 PR |

状态: ⬜ pending / 🟡 in-progress / 🟠 review / ✅ done / 🔴 blocked
-->

---

## Scope 锁地图

| 文件 / 目录 | 锁定 task | 预计解锁 |
|---|---|---|
| — | — | — |

<!--
示例行（勿删）：
| web-admin/src/views/kpi/ | T001 | T001 PR 合并后 |
| backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/restaurant/ | T002 | T002 PR 合并后 |

⚠️ 派活前必查此表。两 task 重叠同一路径 → 串行（T1 PR merge 后再开 T2）或重切 scope（按文件拆）。
-->

---

## Done（待清理）

> PR 合并后的 task 移到这里。每周清一次，保持 In-flight 表精简。

| ID | 任务 | PR | 合并时间 | 备注 |
|---|---|---|---|---|
| — | — | — | — | — |

---

## 使用流程（给 Organizer 自己）

```text
1. 接到 Steve 新任务
   → 查 Scope 锁地图：有无冲突？
   → 拆解 → 写 brief 卡 → 填入 In-flight 表（ID/model/effort/orchestration/分支/scope锁）
   → 更新 Scope 锁地图

2. 派发 brief 卡
   → In-harness (Sonnet): organizer spawn subagent
   → Out-of-harness (Codex/Composer): 出卡 → Steve courier

3. PR 回来
   → 验 scope 干净: git diff origin/main...HEAD --stat
   → 🔒 risky: Opus 终审 → merge main → 从 main 部署 prod
   → 例行: Sonnet review → merge main

4. 完成后
   → In-flight 表标 ✅ done → 移到 Done 区 → 释放 Scope 锁
```
