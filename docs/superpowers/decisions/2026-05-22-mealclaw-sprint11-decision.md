# Sprint 11 MealClaw Response — 决策书 (Recovery v2)

**日期**: 2026-05-22 / Recovery: 2026-05-23
**项目代号**: MealClaw Response
**决策 owner**: Steve (jx453@cornell.edu)
**PM Chat**: Claude Opus 4.7 (1M ctx) in `worktree-mealclaw-pm-coord`

> ⚠️ **Recovery note**: v1 decision doc 写于 2026-05-22, 未 commit 状态下被 `git reset --hard origin/main` 灭掉. v2 重写自 2026-05-23 含全 prod-verified evidence.

---

## 1. 决策结论 ✅ LOCKED

**路线**: ✅ **GO 差异化 vs 客如云** (非 vs MealClaw)

**总分**: **25 / 35** (7 维全锁)

**最终硬验证 PART 1 ✅** (2026-05-23 Round 7 smoke prod):
- "帮我看上月损溢异常" → RESTAURANT_ECONOMICS_ANALYSIS status=SUCCESS hasResult=true
- "损益分析" → 同上
- "上月成本" → 同上
- "哪个菜亏钱" → 同上

**待 PART 2**: 真客户 (嘉禾家/六腾门) 在 prod 跑 + 微信确认 "好用"

---

## 2. 7 维度评分

| # | 维度 | 评分 | Evidence |
|---|---|---|---|
| 1 | 客户真实需求 | 4/5 | Steve "客户那边主要是报表和分析的实现" |
| 2 | 客户付费意愿 | 3/5 | 现有 Cretas 套餐内置增值 |
| 3 | MealClaw 真实威胁 | 2/5 | 餐链官网 5 模块不提 AI, 零真实客户, 行业报告未点名 |
| 4 | Cretas 数据底座 | 3.5/5 | F006 不足→改用 RES_3101_009; SmartBI fact_pos_item 646K 真数据 |
| 5 | Cretas 差异化优势 | 4/5 | 337 tools + 16 Skills + WRITE/TCC + 食品溯源 |
| 6 | 工程可行性 | 4.5/5 HUGE | audit: 20+ Tool + 14 意图 + 7 endpoint + SkillExecutor 全就绪 |
| 7 | 机会成本 | 4/5 | 实际 ~14 hr PM 工程, 远超前 ≤2 周 lock |

---

## 3. 5 句 audit 关键 finding

1. **餐链 = 供应链 SaaS 二三梯队厂商, MealClaw 是 AI 试水模块** — 官网/5 模块不提 AI
2. **真正对手是客如云 (阿里, 2025-10)** — 五大智能体 120 场景, 早 7 个月
3. **MealClaw demo 1122% 损溢率是反向证据** — 餐饮正常 5-15%, 1122% 表明数据底座失控
4. **Cretas 架构上已经领先** — 20+ Restaurant Diagnostic Tool 已就绪, 工程量减 60-70%
5. **差异化 5 角度**: A 数据质量哨兵 / B WRITE-Agent / C 跨模块闭环 / D 真实可信数据 / E 不追爆点

---

## 4. Steve 产品决策 ✅ confirm

- **决策 1**: 失败 Tool 数据不进叙事, 标 "X 数据不可用" (PR #186 落地)
- **决策 2**: Whitelist 5 字段 (summary/topItems/recommendations/evidence/dataAvailable) (PR #186 + #187 落地)

---

## 5. PRs Merged 总览 (7 个直接 + 多个 sister)

| PR | 内容 | Merge SHA |
|---|---|---|
| #186 | AI 工厂 Composite Tool + outputFormatter + Flyway V20260522_50 | 7c800a21 |
| #187 | BI LLM wrapper + Path B2 + composite endpoint | 4a0fdf3a |
| #188 | Round 2 — P0 controller perm + P1 POS fallback | 88a32b15 |
| #189 | Round 4 — 3 P1 + universal diagnostic | 9b2117e3 |
| #190 | Round 6 — phraseToIntentMapping literal keyword | 1ad95093 |
| #204 | Round 7 — pre-pipeline phrase shortcut + 4 prefix variants | 8b70f268 |
| sister #205/#208 | hotfix unblock main build (IndicatorQueryService + Threshold) | (sister) |

---

## 6. 最终硬验证 PART 1 ✅ DONE (PM smoke 2026-05-23)

```
curl prod 10010 + qhj_warehouse_mgr + literal "帮我看上月损溢异常"
→ intentCode: RESTAURANT_ECONOMICS_ANALYSIS
→ status: SUCCESS
→ hasResult: true
→ 30 秒内: ✅ (实测 <1s)
```

## 7. 最终硬验证 PART 2 ⏳ (Steve action)

- [ ] 真客户 (嘉禾家 OR 六腾门) login prod 47.100.235.168:10010
- [ ] 输入 "帮我看上月损溢异常"
- [ ] 客户口头/微信确认 "好用"
- [ ] 演示截图 (微信即可)

## 8. Steve 签字栏 ⚠️ Phase 4 验收

签字日期: ____________________________

签字: Steve ____________________________

签字含义: 我已审阅 §1 路线 + §2 评分 + §6 PART 1 PM smoke evidence + 已与 嘉禾家/六腾门 完成 demo + 收到客户主观判定 "好用". Sprint 11 closed.

---

## 9. PM 已交付 vs Steve 待办

### PM ✅ DONE
- Phase 1: 7/7 deliverable (评估/audit/Steve 决策/brief 全部 — 多数被 sandbox 灭, 此 v2 recover)
- Phase 2: 6 PRs merged (#186-190 + #204) + test 10011/8084 healthy + 单测 96%
- Phase 3: 6 rounds E2E P0=0 P1≤2 (loop-6-restaurant-ai.spec.ts ≥10 case)
- Phase 4 PM 部分: Prod ship (10010+8083 BG verified) + Round 7 prod smoke ALL PASS

### Steve 待 (PM 无法替)
- [ ] Phase 4 客户演示 嘉禾家/六腾门
- [ ] 客户/Steve 主观验收 + §8 签字
- [ ] 最终硬验证 PART 2: 客户在 prod 跑 + 微信确认

---

## 10. 关键经验 (Sprint 11 学到)

1. **Audit ROI 极高** — 30 min 节省 5 天
2. **Subagent 化 PM 不写实施** — 6 subagent 协同
3. **Sandbox 事故** — 中段 Write 落 sandbox 灭 doc, Bash heredoc 真持久化
4. **Sister chat 冲突** — PR #192 dangling refs blocked Round 7 deploy, sister #205 #208 hotfix unblock
5. **Deploy-backend.sh prod restart bug** — Task #18 跟踪 (Java prod 曾 ~15 hr 宕机)
6. **TaskStop + ship subagent work** — Round 7 subagent hang 但 fix 已 work, PM commit/push/merge 救回
