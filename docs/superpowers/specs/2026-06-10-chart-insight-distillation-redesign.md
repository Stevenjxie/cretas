# Chart-Insight 蒸馏/收敛 重设计 (Phase A.5 v3 — Fable 深审 + 3-critic 审计后定稿)

**日期**: 2026-06-10
**状态**: 设计定稿（Fable 破玻璃深审 → 换架构 → 3-critic 对抗审计 → Opus 裁定收口 → Steve 定调"活 LLM 现用 + corpus 渐进替代"）。待 Steve spec-review → writing-plans。
**核心定调（Steve）**: **保留活 LLM 实时智能(现在就用), corpus 随使用积累 → 未来自有模型渐进替代 LLM**。不是"不用 LLM"，是"用着用着被自有模型接管"。

---

## 0. 设计经过（两轮否决，留下的才可靠）

**第一版"受约束蒸馏(反向模板化+3闸)"——Fable 否决**：定理 = 能通过严格 slot-only validate 的模板 = 8 slot 的确定性函数 = **Tier1 本身**。在线模板飞轮缓存 Tier1 等价残渣，slot 白名单恰好拒掉 LLM 真智能。Goal1×Goal2 formal 互斥。Steve "分析与数据挂钩"直觉 = 这条定理。

**第二版"serve+数值校验 / memo缓存"——3-critic 否决两个在线机制**：
- **memo(降成本) illusory**(critic#2)：全部历史默认 maxDate 每天前移 → data_hash 每天变 → 跨天永不命中；且重造 `narrative_cache`。→ **砍**。
- **serve 数值校验(保智能+修幻觉) 不干净**(critic#1)：误拒派生 stat(前二合计/补集/n家/约2倍 都不在 8 slot)=**Fable 张力在 stat 边界复现**；误收实体错位幻觉(真数字说错话)；CJK 数词抽取是真 NLP 难题。**自由 prose 既不能安全模板化也不能可靠校验**。

**核验的 shipped 真 bug**：U1.8 跨租户 RLS dead-on-arrival(FORCE RLS factory_id policy 未随签名去 factoryId 而改, prod 实证) / serve 路径可返零-slot 字面(no-data prompt 下=臆造) / `_lookup_template` 没 set `app.factory_id` GUC / 在线 consensus 未实现。

**留下的可靠核**：①Tier1 确定性=安全主力 ②corpus=渐进替代的桥 ③活 LLM serve **靠数据丰富 prompt(让它引用真值而非臆造)+硬 RBAC ¥闸**，残留实体错位风险 Steve 接受("可以用的")，靠 ④未来自有模型逐步接管消化。

---

## 1. 架构 — 4 机制（砍 memo, 活 LLM 保留, corpus 是替代桥）

| 机制 | 目标 | 设计 |
|---|---|---|
| **M1 Tier1 = 安全主力 + 唯一跨数据复用** | 多数图 0-token 正确 | 确定性五族(已建) + 离线策展(M4)产 `FIXED_TEMPLATE`。0-token/无幻觉/无¥泄露。**成本控制主力**(配合 budget cap)。 |
| **M2 活 LLM serve（数据丰富 + RBAC ¥闸）** | 保留实时智能(现用) | Tier1 未命中→LLM。**prompt 喂算好的 stats**(让它引用真值不臆造)；**finance_hidden 只喂 %/stats 不喂原始 ¥**；**硬 ¥ serve-gate**(finance_hidden 输出含绝对¥→拒/落 Tier1)。best-effort 数值 sanity(宽容差 + 扩展 stats, 只拒 egregious 臆造, 不拒派生 stat)。残留实体错位风险 Steve 接受, 靠 M4/M5 渐替消化。 |
| **M3 corpus = 渐进替代桥** | 攒语料 → 未来模型接管 | 每次 M2 LLM 调用→`persist_distillation_sample`(正确签名)。input=**数据丰富(stats)**, teacher_output=**LLM 散文**。未来模型 shadow→canary→primary 逐步替 LLM(语法约束解码)，LLM 留 fallback。**这是"渐进替代"落点**。 |
| **M4 离线策展（设计, 本期不实现）** | 收敛(安全) | 夜间 job：聚类 corpus → 离线反向模板化(false-positive 先见人) → 人/Opus 审 → 升 `FIXED_TEMPLATE`/graduate Tier1。需 corpus 有量, defer。 |

**砍掉**：在线 memo 缓存(illusory)、在线 `ai_insight_templates` auto-promote/lookup(转 M4 离线)。

---

## 2. 具体改动

### C1 — 活 LLM serve：数据丰富 prompt + RBAC ¥闸 + 修幻觉（M2, 🔒 no-fake-data + RBAC）
**文件**: `chart_insight_service.py`(`_build_insight_prompt`/`get_insight`/`_call_llm`)。
- **prompt 喂算好的 stats**: `_compute_slot_values` 的 topName/topShare/ratio/concLevel… 给 LLM, 指示"基于这些**已算好的真值**写洞察, 引用它们, 不要自己重算/臆造数字/名称"。**这是主幻觉防线**(LLM 引用真值≠no-data prompt 逼它臆造)。
- **finance_hidden 不喂原始 ¥**: prompt 注入 permission_tier；finance_hidden 只给 %/ratio/stats(无绝对金额); finance_visible 可给。
- **硬 ¥ serve-gate**: serve 前对 finance_hidden, `_ABSOLUTE_AMOUNT_RE` 扫输出三字段, 命中绝对¥→该洞察拒(落 Tier1 或 null)。**这是 serve 层 RBAC 硬闸**(不只 capture 层)。
- **best-effort 数值 sanity(非强校验)**: 扩展 computed stats 加 `top2Share`(前二合计) `complement`(100-topShare) `n`(series 长度)，宽容差(%±1pp, 比率 floor 接受"约N倍")。**只拒 egregious 臆造**(数字离所有 stat 都远), **不拒派生 stat**(避免 critic#1 的智能误杀)。诚实: 挡不住实体错位(真数字说错话), Steve 接受, 靠 M3/M4 渐替。
- **删零-slot 字面隐患**: 走数据丰富 prompt 后不再有 no-data 臆造路径。
- **验收**: 单测 — finance_hidden 输出含¥→拒(serve-gate); 数据丰富 prompt 喂 stats; egregious 臆造数字→拒; 派生 stat(前二/补集/n家/约2倍)→**不**误拒(智能保留); finance_visible 可含¥。

### C2 — 接 corpus（M3, 渐进替代桥, 🔒 corpus 安全）
**文件**: `chart_insight_service.py`(调 `persist_distillation_sample`, distillation_capture.py:69)。
- **正确签名**(critic#3 B1, 否则静默零 corpus): `await persist_distillation_sample(pool, source="chart_insight", task_type="insights", input_text=<数据丰富上下文>, teacher_output=<LLM 散文 JSON>, business_type=ctx.domain, factory_id=ctx.factory_id, system_prompt=...)`。**`domain` 不是参数, 用 `business_type`; `task_type` 必填**。
- **input_text 含真实 series(绝对值)** → 避免 critic#3 MAJOR2 的"同分布跨租户 input_hash 撞 → ON CONFLICT 覆盖"(绝对值不同 → hash 不同)。同时给未来模型真实数据(否则零熵)。
- **corpus 跨租户安全策略**(critic#3 MAJOR1): export/训练时**按 business_type 桶**(restaurant/factory)训, **不按 factory_id**; 训练前**数值分桶/脱敏**(¥区间非精确值)防跨租户记忆; export 文件 ACL(非 /tmp 共享)。**写进 §5 + M4/训练 pipeline 约束**。
- teacher_output = **LLM 填充后散文**(非模板 JSON), 对齐"训生成模型"(critic#3 MINOR5)。
- **验收**: 一次 LLM 调用→`smart_bi_distillation_samples` 多一行, input 含真实 series, teacher_output 散文; 调用签名不抛 TypeError(实证多行非静默吞)。

### C3 — 停在线 ai_insight_templates 读写 + revert U1.8（M4 转离线, 修 RLS 坑, 🔒迁移）
**文件**: `chart_insight_service.py` + 迁移。
- `get_insight` 在线路径**移除** Tier2a lookup(`_lookup_template`) + `_capture_template`/`_maybe_promote`。`ai_insight_templates` 在线**不读不写**(M4 离线 owns)。→ 顺带让 RLS/GUC 坑(critic#2 F6)在线休眠。
- **revert U1.8**: 迁移把 `uk_ait_sig(signature_hash)` 恢复 `uk_ait_sig_factory(signature_hash,factory_id)` 与 RLS 一致。**安全**(表当前近空, 无 dup; critic#2 F3 的 dup 风险不存在——核实 row count=0/极少再迁)。迁移号 > origin/main 最高(Python 独立编号空间, 核 `V20260928_01` 后)。
- **验收**: 在线不读写 ai_insight_templates(grep get_insight 无 _lookup_template/_capture_template 调用); 迁移 RLS 与唯一键一致; row count 核实后再 revert。

### C4 — 前端 source 徽章（小）
**文件**: `ChartInsight.vue`。source='llm'→"AI生成" / 'rules'(Tier1)→"数据驱动" / null→不显。**无 memo 态**(已砍)。loading 同现。

---

## 3. M4 离线策展（设计, 本期不实现, 待 corpus 有量）
夜间 job: 读 `smart_bi_distillation_samples`(source=chart_insight) → 按 signature 聚类 → 离线反向模板化(数值+定性→slot, 严格 validate, **false-positive 先见人**) → 人/Opus 审 → 升 `ai_insight_templates(FIXED_TEMPLATE,is_verified)` 或 graduate Tier1。**未来自有模型(语法约束解码保证产模板)上线后**在线模板飞轮才安全。M4 也是"渐进替代"的策展侧。

---

## 4. 保留有效的 Phase A 成果（不动）
Tier1 五族(U2)/deriveChartMeta(U3)/useChartInsight composable(U4)/上传 meta(U5)/U6 6图迁移/permission_tier 服务端推+budget fail-closed(U1)/2 hotfix。**全保留**。本重设计改"在线蒸馏/收敛"+修 serve 幻觉/RBAC+修 U1.8 RLS。⚠️ U6 的 `autoTier2=true` 保留(活 LLM serve), 但 LLM 路径换成 C1 的数据丰富+RBAC 版。

## 5. RBAC / 诚实 / corpus 安全（🔒 红线）
- **finance_hidden RBAC 硬闸**: prompt 不喂原始¥ + serve 层 `_ABSOLUTE_AMOUNT_RE` 拒¥(不只 capture 层)。permission_tier 服务端推(U1.4 保留)。
- **no-fake-data**: 数据丰富 prompt(引用真值) + egregious-臆造数值 sanity 拒。**诚实声明残留**: 实体错位(真数字说错话)挡不住, Steve 接受, M3/M4 渐替消化。
- **corpus 安全**: 内部表(无 RLS, GRANT smartbi_user, 无用户 API); **训练按 business_type 桶非 factory_id + 数值脱敏/分桶防跨租户记忆**; export 文件 ACL。

## 6. 测试
- C1: finance_hidden¥拒(serve-gate)/数据丰富prompt/egregious臆造拒/派生stat不误拒/finance_visible含¥OK。C2: corpus 写入(正确签名不吞/数据丰富/散文)。C3: 在线不读写templates/RLS一致/迁移号。
- headed real-path: 驾驶舱 Tier1 不回归; Tier1-null 图走 C1 活 LLM(引用真值非臆造); finance_hidden 角色无¥泄露; corpus 表实证多行(数据丰富, 非零熵)。

## 7. 🚦 分发（待 plan 细化）
C1(serve 数据丰富+RBAC¥闸+sanity, 🔒) → C2(corpus 正确签名, 🔒) ‖ C3(停在线templates+revert U1.8, 🔒迁移) → C4(徽章)。各 worktree off origin/main; 🔒 Opus gate + headed real-path 验真。M4 离线 defer。

## 8. 🔒 红线（Opus 终审）
C1 RBAC¥闸+no-fake-data / C2 corpus 跨租户安全 / C3 revert U1.8 RLS 一致+迁移 / prod 部署。
