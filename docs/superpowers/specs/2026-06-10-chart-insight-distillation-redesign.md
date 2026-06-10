# Chart-Insight 蒸馏/收敛 重设计 (Phase A.5 v2 — Fable 深审后定稿)

**日期**: 2026-06-10
**状态**: 设计定稿（Fable 5 破玻璃深审 → Opus 裁定采纳 → Steve 批准换架构）。待 spec self-review → 对抗审计 → writing-plans。
**触发**: chart-insight 飞轮"生成 clean 但不收敛"。原"受约束蒸馏(反向模板化+3闸)"设计经 Fable 深审被判**根本性有问题**, 换架构。

---

## 0. 为什么换架构（Fable 定理 + 核验的 shipped bug）

**Fable 定理（Opus 核验成立）**：能通过严格 slot-only validate 的模板 = 8 slot 的确定性函数 = **Tier1 本身**。所以在线反向模板化飞轮缓存的是 Tier1 等价残渣，而 slot 白名单**恰好把 LLM 真正智能的部分拒掉**。Goal1(LLM 智能) 与 Goal2(缓存) 经 slot 白名单 **formal 互斥**。Steve "分析与数据挂钩" 的直觉 = 这条定理。

**核验的 shipped 真 bug（prod 实证）**：
- 🔴 **U1.8 跨租户 RLS 失效**：`ai_insight_templates` FORCE RLS + policy `factory_id=current_setting('app.factory_id')`，但 U1.8 签名去 factoryId 做跨租户共享**没改 RLS policy** → 租户 A 写的模板对租户 B 的 lookup RLS 不可见 → 跨租户共享 dead-on-arrival(静默)。
- 🔴 **serve 路径幻觉**：`get_insight` 对零-slot 字面 finding 直接 source=llm 返回(`_safe_fill` 无 `{slot}` 残留→原样返回, chart_insight_service.py:433-448)。no-data prompt 下这些数值是 LLM 臆造(违反 no-fake-data)。
- corpus 零熵(no-data 输入→input_hash 去重塌成几百行, 没法训练分析模型) + 没接线; consensus 投票没实现(U1.7 first-capture-wins); 在线缓存仅省个位数 ¥/天(不值红线面)。

---

## 1. 新架构 — 4 机制, 一目标一机制（一目标一机制, 避免一机制硬扛三目标全砸）

| 机制 | 服务的目标 | 设计 |
|---|---|---|
| **M1 Tier1 = 唯一跨数据复用** | 大多数图 0-token 正确 | 确定性五族(已建) + 人/Opus 审过的 `FIXED_TEMPLATE` 行(离线策展产出, M4)。"重复案例免费"合法住这。 |
| **M2 Tier2 serve = 数据给 LLM + 数值校验** | 保留实时智能 + 修幻觉 | prompt **附真实 series + 算好的 stats**(让 LLM 引用而非瞎算); serve 前**数值一致性校验**(从 LLM 输出抽数字, 每个须 ≈ 某 computed stat, 容差内; 不符→落 Tier1 或 null, 不返幻觉)。 |
| **M3 精确数据 memo 缓存** | 降成本(精确, 零跨数据风险) | 新表/缓存 key = `signature_hash + sha256(series_values|series_labels)`, 存**填充后成品**, TTL 小时级, factory-scoped(RLS 一致)。lookup 在 LLM 前; 命中→0-token 返成品。**只对完全相同数据复用** → 精确满足 Steve 质疑。 |
| **M4 离线策展飞轮** | 收敛(安全) + FIXED_TEMPLATE | 夜间批量 job(本期**仅设计+占位, 不实现**): 聚类 corpus → 离线反向模板化 → 人/Opus 审 → 升 `FIXED_TEMPLATE/is_verified`。poison/validate 机器搬离线(假阳性先见人)。**需 corpus 先积累, 故 M4 实现 defer 到 corpus 有量**。 |
| **M5 数据丰富语料** | 为未来自有模型 | 每次 LLM 调用→`persist_distillation_sample(source="chart_insight", domain="smartbi")`, input=**数据丰富上下文(series+stats)**, teacher_output=LLM 散文; metadata 存离线反推模板(M4 时)。接入既有统一语料管道。 |

**核心转变**：在线**不再**做反向模板化/auto-promote 到 active(那是 M4 离线的事)。在线路径 = M2 serve(智能+校验) + M3 memo(降成本) + M5 corpus(攒语料)。M1 Tier1 + M4 离线 FIXED_TEMPLATE 是跨数据复用的唯二安全途径。

---

## 2. 具体改动

### C1 — 修 serve 幻觉 + 数据给 LLM（M2, 🔒 no-fake-data）
**文件**: `chart_insight_service.py` (`_build_insight_prompt` + `get_insight` + `_call_llm`)。
- prompt **恢复喂真实数据**: series_labels + series_values + 把 `_compute_slot_values` 算好的 stats(topName/topShare/ratio/concLevel…) 一并给 LLM, 指示"基于这些**已算好的真值**写洞察, 引用它们, 不要自己重算/臆造"。
- **数值一致性校验**(serve 前): 从 LLM finding 抽所有数字(含 CJK 数词归一: 六成→60%, 三分之二→66.7%), 每个须匹配某 computed stat(容差 ±0.5pp / 比率 ±0.1)。**任一数字无匹配 → 判定幻觉 → 不返 LLM 输出**, 落 Tier1(若有) 或 null(诚实空)。
- 删除"零-slot 字面直接返回"隐患: serve 的洞察要么来自数值校验通过的 LLM 输出, 要么 Tier1, 要么 null。
- **验收**: 单测 — LLM 输出含 stats 外的数字(臆造)→校验拒→落 Tier1/null; 含 CJK 数词→归一后校验; 数值全匹配→正常返回。

### C2 — 精确数据 memo 缓存（M3, 降成本）
**文件**: `chart_insight_service.py` + 新迁移 `V<新号>__chart_insight_memo.sql`。
- 新表 `chart_insight_memo`: `(id, factory_id, signature_hash, data_hash, insight JSONB{finding,implication,suggestion,source,tier}, permission_tier, hit_count, created_at, expires_at)`, UNIQUE(signature_hash, data_hash, factory_id, permission_tier), **factory-scoped RLS(与现有一致, 不踩 U1.8 坑)**, TTL(expires_at, 过期 lookup 忽略 + 定期清)。
- `get_insight`: 算 `data_hash=sha256(series_values|series_labels)`, **LLM 前先查 memo**(同 sig+data+factory+tier 且未过期)→命中返成品(0-token, source 标 'memo')。未命中→M2 serve→**写 memo**。
- **验收**: 同数据二次调用→memo 命中 0-token; 数据变(data_hash 变)→miss→重生成; 过期→miss。

### C3 — 接 corpus（M5, 为自有模型）
**文件**: `chart_insight_service.py` (调 `persist_distillation_sample`, distillation_capture.py:69)。
- 每次 M2 LLM 调用(非 memo 命中)后: `await persist_distillation_sample(source="chart_insight", domain="smartbi", business_type=domain, factory_id=…, system_prompt=…, input_text=<数据丰富上下文: series+stats>, teacher_output=<LLM 原始 finding+implication+suggestion JSON>, quality=None)`。
- input 必须**数据丰富**(含真实 series+stats), 否则零熵(Fable §6)。input_hash 去重天然按数据变化区分(不再塌成几百行)。
- **验收**: 一次 LLM 调用→`smart_bi_distillation_samples` 多一行, input 含真实数据, teacher_output 是 LLM 散文。

### C4 — 停在线 auto-promote + revert U1.8 跨租户（M4 转离线, 修 RLS 坑）
**文件**: `chart_insight_service.py` + 迁移。
- `get_insight` 在线路径**移除** `_capture_template`/`_maybe_promote` 到 `ai_insight_templates(is_active)` 的 auto 写入(那是 M4 离线的事)。`ai_insight_templates` 保留(给 M4 离线 + 未来 FIXED_TEMPLATE)。
- **revert U1.8 跨租户**: `compute_signature` 加回 factory_id(或 memo 已 factory-scoped, ai_insight_templates 既然在线不写, 跨租户键暂无害——但为干净, 迁移把 `uk_ait_sig` 恢复为 `uk_ait_sig_factory(signature_hash,factory_id)` 与 RLS 一致)。**Opus 终审迁移 + RLS 一致性**。
- M4 离线 job 设计写进 spec §3, **实现 defer**(需 corpus 积累; 占位文档 + 不写代码)。
- **验收**: 在线不再写 ai_insight_templates active; RLS 与唯一键一致; 迁移号 > origin/main 最高。

### C5 — 前端 source 徽章（小, 配合 M3/M2）
**文件**: `ChartInsight.vue`。
- source='memo' → 徽章"数据驱动·已缓存"(或复用"已学习"); source='llm' → "AI生成"; source='rules' → "数据驱动"。loading 同现。
- **验收**: 三态徽章正确。

---

## 3. M4 离线策展飞轮（设计, 本期不实现）
夜间 job(future, 待 corpus 有量): 读 `smart_bi_distillation_samples`(source=chart_insight) → 按 signature 聚类 → 离线反向模板化(数值+定性→slot, 严格 validate, **此处 false-positive 先见人**) → 候选交人/Opus 审 → 升 `ai_insight_templates(source_type='FIXED_TEMPLATE', is_verified=true)` 或 graduate 进 Tier1 code。**未来自有模型用语法约束解码保证产模板后**, 在线模板飞轮才安全(届时是我们掌控模型的属性)。

---

## 4. 保留有效的 Phase A 成果（不动）
Tier1 五族(U2) / deriveChartMeta(U3) / useChartInsight composable(U4) / 上传 meta(U5) / U6 6图迁移 / safe_fill+permission_tier 服务端推+budget fail-closed(U1) / 2 hotfix。**这些全保留**。本重设计只改"在线蒸馏/收敛"那部分 + 修 serve 幻觉 + 修 U1.8 RLS。

## 5. RBAC / 诚实（🔒 红线）
- serve 数值校验 = no-fake-data 的硬执行(幻觉数字不返)。
- memo factory-scoped(RLS 一致); corpus 内部训练表(含真实营收=可, 非用户面, 但**访问控制**: corpus 表权限审计, 不经任何用户 API 暴露)。
- permission_tier 服务端推(C1 保留 U1.4); finance 洞察只 %/倍 by construction。

## 6. 测试
- C1: 数值校验(臆造拒/CJK 数词归一/匹配通过); C2: memo 命中/miss/过期; C3: corpus 写入数据丰富; C4: 在线不写 active + RLS 一致。
- headed real-path: 驾驶舱 Tier1 不回归; 同数据二次 memo 命中(0 token); corpus 表实证多行(数据丰富)。

## 7. 🚦 分发（待 plan 细化）
C1(serve+校验, 🔒) ‖ C2(memo+迁移, 🔒) → C3(corpus) ‖ C4(停 auto-promote+revert RLS, 🔒迁移) → C5(前端徽章)。各 worktree off origin/main; 红线 Opus gate + headed real-path 验真。M4 离线 defer。

## 8. 🔒 红线（Opus 终审）
C1 serve 幻觉(no-fake-data) / C2 memo 迁移+RLS / C4 revert U1.8 RLS 一致性 / corpus 访问控制 / prod 部署。
