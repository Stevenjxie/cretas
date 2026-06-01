# 自学习架构策略 + "毕业进规则"闭环设计

**日期**: 2026-06-01
**作者**: Steve + Claude (brainstorm)
**状态**: 设计待审 (brainstorming → 待 writing-plans)

---

## 0. 一句话

Cretas 的"自学习"应该是 **"LLM 当引导器，把反复且确定的部分逐步冻结成确定性规则"**（graduate-to-determinism）。本文先定策略（架构是否合理/持久），再设计第一条要真正落地的闭环：**字段映射的"毕业进规则"**。核心约束：**毕业必须 gated + 可审计 + 可回滚，绝不静默自动**（错规则比调 LLM 更糟）。

---

## 1. 架构策略评估（brainstorm 结论）

### 1.1 三层结构
- **(a) 确定性学习 → 毕业进 curated 规则**（字段映射 / 分类 / 清洗规则）：LLM 解决规则覆盖不了的，验证后冻成规则，0 token、确定、可审计。
- **(b) 叙述/洞察 teacher 对 → 蒸馏样本池 → 够触发器才 SFT/LoRA 训小模型**。
- **(c) RAG 只给外部参考知识**（食安/法规/行业基准）。**绝不 RAG 自己的 LLM 输出**（回音室 + token）。

### 1.2 两个 reframe（已与 Steve 对齐）
1. **护城河 = 正确性 / 可审计 / 数据主权，不是省 token。** LLM 变便宜会侵蚀"省 token"卖点，但侵蚀不了"可证明正确 + 可审计 + 数据不出境"。
2. **自有小模型(b) = 数据主权 hedge，不是成本赌注。** 训+服务自己模型的省钱缺口随老师模型变便宜而缩小（商品化暴露）。对 Cretas，真正需要自有模型的时刻是**客户要求私有化/合规**，跟 token 成本无关。

### 1.3 投资分配（裁决）
| 层 | 持久性 | 姿态 |
|---|---|---|
| (a) 毕业进规则 | 对 bitter lesson 免疫（卖的是保证/审计/成本，不是能力） | **重投**——做成安全 gated 闭环 = 持久核心 |
| (b) 蒸馏→SFT | 训练暴露在商品化 | **只攒语料**（便宜保险）；**主权/合规触发器命中才训**；不建训练 infra |
| (c) RAG | 外部事实永远要 grounding | 只给外部知识；绝不 RAG 自己输出 |

### 1.4 失败模式 + 缓解
1. **错规则毕业**（最大风险）→ gated（置信 + 复现 + 无冲突 + 审核）+ 可审计 + 可回滚，**绝不静默自动毕业**。
2. **规则腐烂**（领域演进）→ 毕业循环半自动、低摩擦（LLM 提议 → 人审 → 毕业）。
3. **蒸馏封顶**（训老师输出会继承其偏见、超不过它）→ 只蒸馏风格/结构，**事实永远确定性**（架构已如此）。
4. **生成层比想的厚**（客户要真开放分析）→ 模板 + grounding 吃 80%，20% 留 LLM（脱敏 + 对账）。

### 1.5 领先指标
**规则命中率**（字段/分类被规则解决 vs 落 LLM 的比例）随时间走势。爬升且稳住 = 确定化论点成立；若 LLM 兜底长期必需 = 生成层比想的厚，重新配权重。

### 1.6 结论
架构**合理，2-3 年持久**。最该做 = 安全的"毕业进规则"闭环（免疫商品化的核心）；最不该做 = 投机建模型训练。

---

## 2. ⚠️ Ground Truth：现有"自学习"并未真正运行

设计前查清的现实（很重要，跟"我们已有自学习引擎"的印象不符）：

- **`learned_field_mappings.json`（16 条财务别名）从不被任何 live 代码读取** —— 只有它自己的 writer 模块读它。
- 该 writer（`structure/semantic_mapper.py`，4 层 exact/rule/LLM/learned）**写到 `services/data/`，prod 上该目录不存在**；仓库里的 committed 文件在另一个路径（`smartbi/data/`，Feb 11 起静态）。
- **structure/semantic_mapper 不被 live 上传流程使用**（仅 `__init__` 导出，无 live importer）。
- **live 上传 mapper（`services/semantic_mapper.py`）没有"毕业进规则"学习** —— 用 hardcoded `STANDARD_FIELDS` + embedding + LLM 投票 + **临时 LLM 结果缓存**（`common.llm_cache`，非"学到的规则"持久层）。

**含义**：这不是"接通已有的自学习"，而是"**干净地建闭环 + 清掉死脚手架**"。好处：白纸建对；代价：是真 build 不是接线。

---

## 3. 闭环设计：字段映射"毕业进规则"

### 3.1 三个隔离单元
1. **Capture（捕获）**：live mapper（`services/semantic_mapper`）走 LLM 解决一个字段时，把"列名 → 标准字段 + 置信 + 工厂 + 时间"作为**候选**记到一个持久表（不是临时缓存）。带 **occurrence/source** 元数据（解决现有"只有置信、没复现次数"的问题）。
2. **Promote（毕业，gated）**：一个工具/端点扫描候选，筛出**可毕业**项（置信 ≥ 阈值 + 跨 ≥N 工厂复现 + 与现有 curated 无冲突），产出**审核清单**。审核通过 → 写入**单一权威的 `promoted_field_aliases`**（committed JSON 或 DB，可 PR review、可回滚）。
3. **Consult（消费）**：live mapper 在 embedding/LLM **之前**先查 `promoted_field_aliases`（精确列名命中 → 直接返回，0 token、确定）。

### 3.2 数据流
```
上传 → live mapper
  → 查 STANDARD_FIELDS (curated) + promoted_field_aliases (graduated)  [规则层, 0 token]
  → 未命中 → embedding + LLM 投票  [生成层]
       → 成功(≥阈值) → 记 Capture 候选 (列名/标准/置信/工厂/时间)
  → 返回映射
（离线/按需）Promote 工具: 候选 → gate(置信+复现+无冲突) → 审核 → promoted_field_aliases
```

### 3.3 安全设计（失败模式 #1 的缓解，最重要）
- **毕业门槛**：置信 ≥ 0.9 **且** 跨 ≥2 个不同工厂复现 **且** 不与现有 curated/promoted 冲突（同列名映到不同标准 = 冲突，拒绝 + 标记人工）。
- **审核闸**：默认**不自动毕业**，工具产出候选清单供人确认；`--apply` 显式执行。（可后续加"超高置信 + 高复现"的保守自动档，但首版人审。）
- **可审计 + 可回滚**：`promoted_field_aliases` 是 committed 文件（PR 可见、可 revert）；每条带来源（哪些工厂/何时学到）。
- **冲突优先**：promoted 与 curated 冲突时，**curated 赢**（人写的权威）+ 记日志，不静默覆盖。

### 3.4 清理（顺手）
- 死脚手架 `structure/semantic_mapper.py` + 孤儿 `learned_field_mappings.json`：**先确认 0 live 引用，再删/归档**（避免未来又有人以为它在工作）。本设计不依赖它。

### 3.5 隔离边界（每单元能独立理解/测试）
- **Capture**：输入=一次 LLM 映射结果 + 上下文；输出=候选表一行；依赖=DB。
- **Promote**：输入=候选表 + curated/promoted 现状；输出=审核清单 / 写 promoted；依赖=两个 store + 阈值。纯函数易测。
- **Consult**：输入=列名；输出=标准字段 or None；依赖=promoted store。live mapper 只多一个 O(1) 字典查。

---

## 4. 实施计划（writing-plans 细化）
1. **建 Capture 持久表**（candidate mappings，带 occurrence/source）+ live mapper 在 LLM 成功后写候选。
2. **建 `promoted_field_aliases` store**（committed JSON 起步）+ Consult：live mapper 规则层先查它。
3. **建 Promote 工具**（扫候选 → gate → 审核清单 → `--apply` 写 promoted）。先填一次现有的 16 条静态别名（人审后毕业，作为种子）。
4. **测试**：Consult 命中（0 token）、冲突拒绝、gate 逻辑、毕业回滚。
5. **清理死脚手架**（确认 0 引用后）。
6. **领先指标埋点**：规则命中率（curated+promoted vs LLM）记进 metrics，观察趋势。

---

## 5. 不做（YAGNI）
- 不现在训 SFT/LoRA（只攒语料，主权触发器才训）。
- 不 RAG 自己的 LLM 输出。
- 不做"清洗规则自动注册"的扩展（先把字段映射这条闭环做对、做安全，再复制套路到分类/清洗）。
- 不做自动毕业（首版人审；保守自动档留后）。
