# 归因类战略修订 (2026-07-08)

**触发**: Steve 问"后续就是不断找维度开发吗?" —— 手搓每个归因维度正是原始战略
(`2026-07-08-handoff-agentforce-direction.md`) 明说要避免的"永不收敛"反模式。
一个独立 (Fable) read-only 战略 review 核实后, Steve 认可以下修订。

---

## 修订 1 — 数值归因永远确定性, LLM 只编排+复述 (核心)

原始"LLMCompiler 泛化到不用手搓 resolver"**有一半是一厢情愿**。代码自证:

- 引擎契约 (`smartbi/agent/factbook.py` docstring): "LLM 从不看原始行、**从不计算**"。
- `FactReconciler` (`smartbi/services/llm_guard.py`) 是**宁漏不错**: 只核 `FactBook.to_facts_index`
  里 ~20 个精确命名 fact (精确子串 + 紧跟数字, tol 5%); "无匹配 fact → no-op"。
  更关键, `_reconcile_one_fact` **显式跳过任何含 店/馆/门店 的句子** —— 而归因答案
  天生是门店口径散文。→ LLM 自己算出来的归因数字 (如"客流少贡献约¥30万") **隐形放行**,
  可以是"看着合理但算错"。
- spike (commit `18843feb0`) 自己的实测结论: "**确定性内核负责对错, LLM 只负责措辞**"。

**结论**: `LLMCompiler` 真正泛化的是 **编排 (plan→并行取数) + 复述 (grounded narration)**,
**不是分解数学**。任何 "哪个X拖后腿、差多少 / 是因子A还是因子B" 的数字**必须来自确定性
producer** (如 `compute_store_attribution`), 不得让 LLM 推。synthesis 层 (方案 b) 只对
**定性跨维**问题合法 ("为什么差评变多" 这种无精确恒等式、答案是叙述的)。

已落地: `synthesis_engine.py` docstring 加了这条明规则 (防后人"让 LLM 算")。

## 修订 2 — 通用乘法原语是过早抽象, 先收敛接口不收敛公式

已发 2 个归因实例**本就是两个不同分解族**:
- 门店 (`compute_store_attribution`): **双因子方差分解** `ΔR=(B−B̄)Ā+B̄(A−Ā)`, peer-mean benchmark, 客流地板异常门。
- 菜品 (`_compute_margin_dragger`): **混合比率贡献** `drag=share×(rate−avg_rate)`, revenue-weighted benchmark, min_revenue 门。

一个 `factor_attribution(total,A,B)` 连这两个都盖不住。**该抽的是 fact-producer 接口**
(几乎零成本), **公式抽象等真同族第 2 实例** (损耗=数量×单价 才是门店亲兄弟)。预期终态
是 **2–4 个分解族的小 library**, 不是一个函数。**现在不建通用原语**。

## 修订 3 — 优先级: Phase 0 + 写操作覆盖 跳到归因维度之前

已有 2 个归因功能上线, 而 **Phase 0 (自标"最紧急止血": 覆盖外自信答错, demo 现场杀伤)
+ 11 个零意图绑定的写操作 Tool ("帮我建个领料单"系统性问不到) 一个 commit 没动**。
第 1 个归因样板被授权 (锁架构); 第 2 个 (菜品) 是漂移起点, 且恰是"往手搓 ops resolver
加"的反模式 → 记**技术债** (ops-only, synthesis FactBook 看不见, 归因类无 class 级统一路由)。

## 修订 4 — "铺完" 正解 = 需求驱动, 不是供给驱动找维度

样板已证明模式 (确定性分解 + grounded 复述, 1–2 次 LLM, spike 实测)。→ **停止供给驱动
找维度** → 新维度只在**飞轮显示老板真在问**该族时才做 → 每个 = 一个小 producer, 不是新架构。

已落地: `restaurant_intent_promotion.classify_question_family` + `family_breakdown` 给 LLM-tail
问题打 attribution/write/query 族标签, 让维度 backlog 变**证据驱动** (周度 mining 看哪族高频)。

---

## 执行顺序 (Steve 拍板"按推荐顺序走别停")

1. ✅ 零代码: 本文档 + memory + `synthesis_engine.py` docstring 修订; 冻结新归因维度。
2. ✅ 微小: 飞轮归因族 tag (`classify_question_family` + `family_breakdown` + 测试)。
3. 🔒 Phase 0: 全业态 Java Stage-8 自信答错门控 (低置信度→澄清/明说不会), Opus 自做/终审。
4. Phase 1: 一个高频写操作 ("建领料单" + PreviewToken 确认流), 验证写操作识别+确认+捕获。
5. 推迟通用原语 (等同族第 2 实例); (b) 限定定性跨维推理。
