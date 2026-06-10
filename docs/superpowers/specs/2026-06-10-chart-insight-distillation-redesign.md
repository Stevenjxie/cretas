# Chart-Insight 蒸馏/收敛 重设计 (Phase A.5 v4 — Fable 二审定稿: claims-pinning)

**日期**: 2026-06-10
**状态**: 定稿。Fable 破玻璃深审(v1否决) → 3-critic 审计(v2否决两在线机制) → v3 收口 → **Fable 二审: BUILD + 6 must-fix** → Opus 裁定全采纳。待 Steve spec-review → writing-plans → subagent-driven。
**核心定调(Steve)**: 活 LLM 现用 + corpus 随用积累 → 未来自有模型渐进替代 LLM。
**v4 关键升级(Fable 二审 MF1)**: 不再"接受残留实体错位幻觉"——用 **claims-pinning(结构化声明 + 服务端重算校验 + 数字邻接)** 把它**结构性消灭**, 且不节流智能。

---

## 0. 设计经过（三轮打磨，留下的才可靠）

- **v1 受约束蒸馏** — Fable 定理否决: 能过 slot-only validate 的模板 = 8 slot 确定性函数 = Tier1; 飞轮缓存 Tier1 残渣, 白名单拒掉真智能。
- **v2 serve+数值校验 / memo** — 3-critic 否决: memo 日 churn illusory; 数值校验误拒派生 stat(智能误杀)+误收实体错位幻觉; corpus 签名 TypeError 静默吞; finance_hidden ¥ 泄露。
- **v3 收口** — 砍 memo+在线模板, 留活 LLM(数据丰富 prompt+RBAC¥闸)+corpus 桥, 但"接受残留实体错位"。
- **v4 (本稿) Fable 二审**: 结构对了, 但"接受幻觉"是**不必要的让步**(违 repo no-fake-data 红线), 有 **cheap kill**(MF1 claims-pinning); "砍所有缓存"也是不必要让步(MF4 corpus 读回当日内缓存)。BUILD + 6 must-fix。

**核验 shipped bug**: U1.8 跨租户 RLS dead-on-arrival(prod 实证) / serve 零-slot 臆造 / GUC 未 set / consensus 未实现。

---

## 1. 架构 — 4 机制 + claims-pinning

| 机制 | 目标 | 设计 |
|---|---|---|
| **M1 Tier1 = 安全主力 + 唯一跨数据复用** | 多数图 0-token 正确 | 确定性五族(已建) + M4 离线产 `FIXED_TEMPLATE`。0-token/无幻觉/无¥泄露。 |
| **M2 活 LLM serve（claims-pinning）** | 实时智能现用 + **结构性无幻觉** | LLM 返**结构化声明** `{claims:[{entity,stat_type,value}], finding, implication, suggestion}`; **服务端按 raw series 重算每个 claim** 校验(entity↔value); **数字邻接闸**(prose 中每个数字必在某 validated claim, 最近 entity 必匹配)。智能活在 claim 选择/叙述/含义(不受约束), 数值真伪服务端重算(不会幻觉)。finance_hidden 只喂相对 stats + 硬 ¥ serve-gate。 |
| **M3 corpus = 渐进替代桥（gated, 含 trigger 指标）** | 攒语料 → 自有模型接管 | serve gate **通过后**才 persist(accepted-only); metadata 存 permission_tier/stats/gate 结果/teacher_model。**替换 trigger 指标**(MF3): shadow N≥500, student claim 精度≥teacher, 0 ¥违规, JSON 合法率≥阈 → canary→primary, LLM 留 fallback。 |
| **M4 离线策展（设计, 本期不实现）** | 收敛(安全) | 夜间 job: 聚类 corpus → 离线模板化(false-positive 先见人) → 人/Opus 审 → 升 FIXED_TEMPLATE/Tier1。需 corpus 有量, defer。 |

**缓存**: 不建 memo 表; **M2 调 LLM 前先按 input_hash 读回当日 corpus 行**(MF4)——当日同输入重渲染命中(0 LLM), 跨日数据变自然 miss(对)。复用既有表+hash, 非重造 narrative_cache。

---

## 2. 具体改动

### C1 — claims-pinning 活 LLM serve（M2, 🔒 no-fake-data + RBAC）
**文件**: `chart_insight_service.py`(`_build_insight_prompt`/`get_insight`/`_call_llm`/新 `_validate_claims`)。
- **LLM 契约改结构化声明**: 返 `{claims:[{entity, stat_type, value}], finding, implication, suggestion}`。`stat_type ∈ {value, share, top2_share, complement, ratio, diff, growth, count}`。prompt 喂相对 stats(供参考), 让 LLM 选 claim + 叙述。
- **服务端重算校验(MF1 核心)**: 对每个 claim, 用 `series_values`+`series_labels`(chart_insight_service.py:98-99) **重算** `(entity, stat_type)` 的真值, 比对 claim.value(容差)。不符→丢该 claim。**校验对象 = series 的全算术闭包(任意 pairwise ratio/子集和/growth 按需算), 不是 8-slot 空间 → 不重触定理, 不节流智能**。
- **数字邻接闸**: finding/implication/suggestion 散文里每个阿拉伯数字必出现在某 validated claim; 最近 entity 提及必匹配该 claim 的 entity → **杀实体错位**("外卖占62%"实为堂食=堂食 claim, 邻接不匹配→拒)。残留仅非数字 wrong-entity prose(类小, Tier1 也做不了)。
- **finance_hidden RBAC(MF5)**: prompt 只喂**相对 stats 白名单**(topShare/ratio/growthRate/concLevel/n), **排除 `changeAmt`(=last-first 绝对¥, line 298)和 raw series**; serve 层 `_ABSOLUTE_AMOUNT_RE` 三字段拒¥(硬闸)。finance_visible 可喂绝对值。
- **验收**: 单测 — 实体错位 claim→重算不符丢弃+邻接拒; 派生 stat(top2/complement/diff/growth)→重算通过**不误拒**; finance_hidden 输出含¥→拒 + prompt 无 changeAmt/raw; LLM 返非结构化/claim 缺失→落 Tier1/null。

### C2 — corpus(gated, accepted-only) + 当日读回缓存（M3, 🔒 corpus 安全）
**文件**: `chart_insight_service.py`(调 `persist_distillation_sample`, distillation_capture.py:69)。
- **读回当日缓存(MF4)**: `get_insight` 调 LLM 前, 按 `input_hash=sha256(input_text)` 查当日 `smart_bi_distillation_samples` 行; 命中且重过确定性 serve gate(当前 tier)→ 返 `teacher_output`(0 LLM)。当日同输入重渲染命中, 跨日 miss。
- **正确签名(MF2 + critic#3 B1)**: `await persist_distillation_sample(pool, source="chart_insight", task_type="insights", input_text=<数据丰富: series+stats>, teacher_output=<accepted LLM 散文>, business_type=<ctx.domain 映射: finance→factory>, factory_id=ctx.factory_id, system_prompt=..., teacher_model=<模型名>, metadata={permission_tier, stats, gate_outcomes})`。**`domain` 非参数→用 `business_type`; `task_type` 必填; teacher_model 必传**。
- **gate 后才 persist(MF2)**: 只存**通过所有 serve gate(claims 校验+邻接+¥闸)的 accepted 输出**。被拒的不进 corpus(否则训模型学被拒输出)。
- **input_text 含真实 series 绝对值**: 避免同分布跨租户 input_hash 撞覆盖 + 给模型真数据(非零熵)。
- **验收**: gate 通过→corpus 多一行(正确签名不抛 TypeError); 被拒输出→不进 corpus; 当日同输入二次→读回命中 0 LLM。

### C3 — 物理删在线模板代码 + revert U1.8 + 修 docstring（M4 转离线, 🔒迁移）
**文件**: `chart_insight_service.py` + 迁移。
- **物理删除(非仅 unwire, MF6)**: 删 `_lookup_template`(454-545)/`_capture_template`(621-710)/`_maybe_promote`(712-780)/`validate_template_parameterization`(140-169)/`_safe_fill`/`_fill_slots`/slot-白名单 prompt(787-827)/`CHART_INSIGHT_PROMOTE_THRESHOLD`(chart_insight.py:135) + **改 line 1-25 module docstring**(现描述已删的 Tier2a/distillation 架构=lying, 并发 session 陷阱)。**保留**: `_compute_slot_values`(C1 stats 源)/poison 检查/`_ABSOLUTE_AMOUNT_RE`/`ai_insight_templates` 表(M4 owns)。可把 poison/validate 移 `m4_curation.py` 标 offline-only。
- **revert U1.8**: 迁移 `uk_ait_sig(signature_hash)` → `uk_ait_sig_factory(signature_hash,factory_id)` 与 RLS 一致。**迁移前核 row count**(近空, 无 dup 风险再迁)。迁移号 > origin/main Python 最高(核 V20260928_01 后)。
- **验收**: grep get_insight 无 _lookup/_capture; docstring 描述 v4 架构; 迁移 RLS 一致; row count 核实。

### C4 — 前端 source 徽章（小）
`ChartInsight.vue`: 'llm'→"AI生成" / 'rules'→"数据驱动" / 'cache'(读回)→"数据驱动·已缓存" / null→不显。loading 同现。

### C5 — 小修: budget 计 LLM 失败（MF minor）
`get_insight` line 395-396 LLM 失败/parse 失败也耗 provider token 但没 `consume()`(只 line 408 成功才计)→失败也计 token(防 budget 低估)。

---

## 3. M4 离线策展（设计, 本期不实现）
夜间 job(待 corpus 有量): 聚类 corpus(source=chart_insight) → 离线反向模板化(严格 validate, false-positive 先见人) → 人/Opus 审 → 升 `FIXED_TEMPLATE`/graduate Tier1。未来自有模型(语法约束解码)上线后在线模板飞轮才安全。

---

## 4. 保留有效的 Phase A 成果（不动）
Tier1 五族(U2)/deriveChartMeta(U3)/useChartInsight composable(U4)/上传 meta(U5)/U6 6图迁移/permission_tier 服务端推+budget fail-closed(U1)/2 hotfix。全保留。U6 `autoTier2=true` 保留(LLM 路径换 C1 claims-pinning 版)。

## 5. RBAC / 诚实 / corpus 安全（🔒 红线）
- **no-fake-data 结构性达成(MF1)**: claims 服务端重算 + 邻接闸 → 数值幻觉/实体错位结构性消灭(非"接受残留")。
- **finance_hidden RBAC**: prompt 排除 changeAmt+raw series(MF5) + serve 层 ¥ 闸。permission_tier 服务端推(U1.4)。
- **corpus 跨租户安全**: 内部表无用户 API; **训练按 business_type 桶非 factory_id**; 训练前**数值脱敏/分桶 + 实体标签(店名/菜名)伪名化**(MF2, 防记忆); export 文件 ACL。
- **替换 trigger 指标(MF3)**: shadow N≥500 / student claim 精度≥teacher / 0 ¥违规 / JSON 合法率≥阈 → 才 canary→primary。

## 6. 测试
- C1: 实体错位重算丢弃+邻接拒 / 派生 stat 不误拒 / finance_hidden 无 changeAmt+¥拒 / 非结构化落 Tier1。C2: gate 后才 persist(被拒不入) / 正确签名不吞 / 当日读回命中。C3: 在线无模板调用 / docstring 修 / RLS 一致。
- headed real-path: 驾驶舱 Tier1 不回归 / Tier1-null 走 claims-pinning(数值服务端重算真) / finance_hidden 无¥ / corpus 实证多行(数据丰富 accepted-only) / 当日重渲染读回 0 LLM。

## 7. 🚦 分发（待 plan 细化）
C1(claims-pinning+RBAC, 🔒) → C2(corpus gated+读回, 🔒) ‖ C3(删模板代码+revert U1.8, 🔒迁移) → C4(徽章) → C5(budget 计失败)。worktree off origin/main; 🔒 Opus gate + headed real-path。M4 defer。

## 8. 🔒 红线（Opus 终审）
C1 claims-pinning no-fake-data+RBAC / C2 corpus 跨租户安全+gated / C3 revert U1.8 RLS+删码不破坏 / prod 部署。
