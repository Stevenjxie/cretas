# 下一步专注任务 — 合成路径:记忆 + 自我学习 + 成本

**日期**: 2026-07-08
**背景**: 一整晚跑完餐饮 AI 战略升级(A 库存 seed / B 归因样板+门店+菜品 / Phase 0 自信答错门控 + Phase 0.1 审计修复 / 冷启动 warmup)后,Steve 追问三件事都落在**同一条合成路径**上,应一起规划:
1. 长对话的**上下文记忆**能力(合成路径单发,看不到上一轮)。
2. **成本**:飞轮"随使用下降"是有条件的(见成本模型 Artifact);归因合成有 ~2800 token 地板。
3. **自我学习**:蒸馏系统 2026-06-11 已建成自维护,但训练+serve 模型这步故意留了经济门,合成路径还没用上。

**成本模型(决策工具)**: 侧栏 Artifact「飞轮成本模型 v3」。四个实测锚点:查询晋升后 0 / 未晋升分类 942 / 归因合成 ~2800 / 通用 LLM 595 tok。

---

## 三个专注任务(同一条路,按优先级)

### 任务 1 — 🟢 现在就开始:无模型 serve-from-corpus 扩到合成路径(让自我学习"用起来")

**目标**: 把已在 chart_insight 上线的**无模型自蒸馏**(serve-from-corpus / gold_read_cache 回读)**扩到归因/综合合成路径** —— 重复或高度相似的合成问题,直接从**质量门 + FactReconciler 已把关过**的语料回读答案,不跑完整 API 合成。这是**立刻能用上自我学习、且低风险**的第一步(不用训模型)。

**为什么低风险**: 语料入库时已过 quality 门(claims-pinning 数字对账)+ LLM-judge G2(overall≥4 & 事实≥4)。回读的是已验证的答案,不是新生成。

**要点**:
- 相似度匹配(问题 + 窗口 + 租户)→ 命中高质量语料 → 直接 serve(便宜),未命中 → 走完整合成(现状)。
- **grounding 铁律**: 回读答案的数字仍要对当前租户当前数据核一遍(数据变了就 miss,不能返回过期数字)。租户隔离(不能跨租户串答案)。
- 效果: 把合成地板对"重复/相似问题"进一步压低(narrative_cache 是精确命中,serve-from-corpus 是相似命中,更广)。

### 任务 2 — 合成路径有界近 N 轮历史窗口(长对话记忆能力)

**目标**: 给 `synthesize()`(chat.py:1795 现在无 session/历史)补**有界近 3–4 轮历史窗口**,让"展开第三点""那家店再深挖"这类自由回指有上下文。

**现状核实**: 我们已经**存了最近 10 轮消息原文**(Java `ConversationMemory.recentMessages` + Redis LPUSH/LTRIM;Python `chat_session_service`);通用 chat 路径已用全历史;意图管线用结构化 slot(`ConversationContext` 实体槽 + lastIntent 解指代)。**唯一 gap = 合成引擎单发**。

**要点**:
- `synthesize(..., recent_turns=[...])` → prompt 加「## 最近对话(供理解指代)」。
- **grounding 铁律**: `FactBook` 仍是数字唯一来源;历史只解指代("那家店"/"第三点"),不许从上一轮答案搬数字;`FactReconciler` 照核。
- 有界(最近几轮)→ 有记忆但不退回通用 LLM 全历史的线性增长(实测通用 LLM 逐轮 255→446→788)。
- ⚠️ 触 grounding 敏感路径,别疲劳时仓促做。

### 任务 3 — 训练扳机决策线(什么时候从 API 合成转本地蒸馏模型)

**目标**: 把"何时扣扳机训模型 serve 合成"写成客观触发(已接进成本模型 v3 §04),并把已就绪的"一条命令训练→serve"接到扳机后。

**三个门(全中才扣)**:
- **门①·语料**: 每业态 ≥ ~2–3k 条 organic(真实使用,非 seeder 合成)q4/q5 合成叙述语料。现状 restaurant 392 q4 organic —— 在长未到。
- **门②·用量**: ≥ ~1800–3500 条新合成问题/月(≈5–10M token/月 = 6-11 定的 GPU break-even)。现状 pre-launch ≈0 —— 远没到。
- **门③·占比/约束**: 归因占比进了缓存也压不到通用 LLM 以下的区间(>~53%),或免费层 quota/延迟成硬约束。

**现状裁定**: 门①在长未到 / 门② pre-launch 远没到 / 门③ 占比未知 → **不该扣扳机,正确**。继续攒语料(飞轮在转:`cretas-corpus-refresh.timer` 每周),先做任务 1(无模型 serve-from-corpus)。

**扣扳机后**: QLoRA 训练(Steve RTX3060 4-bit)→ serve 合成措辞层(替 API `call_chain`)→ **守 FactReconciler**(小模型更易漂,grounding 验收线 = 合成叙述过 FactReconciler + judge overall≥4)。

---

## 自洽性(为什么这套设计对)

成本模型的"高归因区间"= 训练扳机的"门②③"= **同一个点**:真实用量起来、归因占比高、缓存兜不住时,合成地板主导,正好是训本地模型划算的时候。语料一直在攒,扳机一扣就能用。**低用量现在故意不训是对的**(6-11 弃训练转分析的决定仍成立);要"开始用起来"就先扩无模型 serve-from-corpus(任务 1)。

## 关联
- 蒸馏系统全貌: memory `project_2026_06_11_global_self_distillation_system`(捕获/质量门/judge/seeder/export/consent/M4 自维护 timer)。
- 弃训练转分析决定: memory `project_2026_06_11_analysis_capability_gold_etl_maturity`。
- 战略修订(数值归因永远确定性): `docs/dispatch/2026-07-08-attribution-strategy-amendment.md`。
