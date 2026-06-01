# 自学习覆盖 v2 — 设计

**日期**: 2026-06-01
**前置**: v1 闭环 (字段映射 capture→promote→consult) 已 LIVE prod (PR #364/#367/#368)。
**触发**: 审计发现多处"学习点遗漏" + Steve 要求跨行业/跨门店层级晋升。

---

## 背景与动机

v1 把字段映射的 LLM/embedding 结果 capture 进候选表, 人审毕业进**扁平全局**规则字典, consult 在规则层 0-token 命中。审计发现三类遗漏 + 一个结构局限:

1. **用户纠正不进闭环** (金标准漏): `/auto-parse/feedback` 的人工纠正只写临时 schema_cache, 不进候选表。人明确给了正确答案却没学。
2. **live LLM 分析不进蒸馏** (高频语料漏): 蒸馏 capture 全代码库只在 `llm_materializer.py`(物化)。真实的 `agent/orchestrator.py`(经营驾驶舱洞察) + `api/chat.py`(AI问答) 每次 LLM 分析的 teacher pair 全漏, 没进 `smart_bi_distillation_samples`。
3. **晋升是扁平全局, 跨行业盲目**: `promoted_field_aliases.json` 无 business_type 分区。一旦毕业对所有行业生效, gate 数工厂也跨行业混数。通用财务词 OK, 行业特定词危险(同名不同义)。

本设计修复 1/2 + 把扁平晋升升级为 Steve 描述的**层级晋升**(行业分支 → 全局主干)。

---

## 共享地基: business_type 源

唯一 business_type 源 = 检测到的 `domain`(`llm_materializer` 已用同一个), 取值 `restaurant` / `factory` / `unknown`。三处复用同一源, 保证一致:
- 主上传 capture: auto_parse 检测的 domain 一路带进 `SemanticMapper.map_fields` → capture。
- 用户纠正 capture: cache_key → 缓存的 parse 上下文里的 domain。
- 蒸馏 live capture: orchestrator/chat 上下文的 domain。

`unknown` 是合法值; 行业分支晋升只对已知行业(restaurant/factory)生效, unknown 候选只能走"跨已知行业一致 → 全局主干"或停在候选。

---

## Feature 1: 用户纠正 → 候选表 (金标准)

### 改动
- **`capture_candidate`** (`services/field_promotion.py`): 放开 `method` 白名单, 加 `"user_correction"`; 新增 `business_type` 参数(默认 `None`→`"unknown"`), 写入候选表新列。
- **候选表迁移**: 加 `business_type VARCHAR(50) NOT NULL DEFAULT 'unknown'`。UNIQUE 约束保持 `(column_name, standard_field, factory_id)`(business_type 是 factory 的函数依赖属性, 同 factory 同行业)。
- **`/auto-parse/feedback`** (`api/excel.py`): `correction_type=="mapping"` 时, 从 `schema_cache` 取该 cache_key 的 factory_id + domain, 调
  `await capture_candidate(pool, original_value→实际是列名?, ...)`。
  - ⚠️ 注意语义: feedback 的 `original_value`=系统原检测值, `correct_value`=用户改的正确值。对 mapping 纠正: 我们要记 **(列名 → 用户纠正后的标准字段)**。需要从 correction 上下文拿到**列名**(不只是 original/correct 的标准字段值)。实现时确认 schema_cache 的 correction 结构含列名; 若 feedback API 现有字段不足以定位列名, 加一个 `column_name` form 字段(前端纠正时带上)。
  - method=`"user_correction"`, confidence=`1.0`。
  - 双层 fail-open(同 v1 capture): 任何失败只 warn, 绝不让 feedback 提交报错。

### 不变
- 绝不静默自动毕业 — 纠正只是进候选表(高置信 + 高亮), 仍人审 `--apply`。

---

## Feature 2: live 问答/报表 → 蒸馏语料

### 改动
- **抽共享 helper**: 把 `llm_materializer.py:_persist_distillation_sample` 提到一个可复用模块(如 `services/distillation_capture.py`), 签名:
  `async def persist_distillation_sample(pool, source, task_type, input_text, teacher_output, *, business_type="unknown", factory_id=None, system_prompt=None, teacher_model=None, template_codes=None, quality=None, metadata=None)`。
  幂等(input_hash = sha256(source+task_type+input_text)), fire-and-forget(永不 raise)。`llm_materializer` 改为调它(行为不变)。
- **`orchestrator.answer_insight`**: 拿到 `answer` 后 fire-and-forget 调
  `persist_distillation_sample(pool, source="agent_insight", task_type="insight", input_text=user_prompt, teacher_output=answer, business_type=domain, factory_id=factory_id, system_prompt=<系统提示>, teacher_model=<模型>)`。
  - 流式 `stream_insight`: 累计完整 answer 后(流结束)再 capture 一次。
- **`chat.py`** AI问答主答路径: 同样 fire-and-forget capture `source="chat_qa"`, `task_type="qa"`。
  - ⚠️ chat.py 有多条路径(general-analysis-stream / drill-down / C1/C2/C3), 选**主答路径**(用户问题→LLM答)埋点, 不在每个 SQL 分支埋。实现时定位"LLM 生成最终 answer"的唯一汇聚点。

### 安全
- 只 capture, 不改变任何返回。fire-and-forget 失败只 warn。
- 脱敏: 这些 input_text/teacher_output 可能含真实店名/客户名。**复用现有 LLM 出境脱敏的 redaction**? 不 — 蒸馏样本是**内部训练语料**(自有, 不出境), 但仍含敏感名。决策: 蒸馏表是内部主权数据(私有化训练用), **存原始值 OK**(不出公有 AI), 但表必须 RLS + 内部访问。`smart_bi_distillation_samples` 已 RLS。保持。

---

## Feature 3: 层级晋升 (subbranch → main branch)

### 数据结构
- 候选表已加 `business_type`(Feature 1 迁移)。主上传 capture 也带 business_type。
- **两层 promoted 文件** (都 committed, 可 PR-review 可回滚):
  - `data/promoted_field_aliases.json` — **全局主干**(现有扁平文件, 16 种子留此)。
  - `data/promoted_field_aliases_by_industry.json` — **行业分支**, 结构 `{ "restaurant": {col: std, ...}, "factory": {...} }`。

### consult 改动
- `consult_promoted(column_name, business_type=None)`:
  1. 若 business_type 已知且在分支文件中有 `branch[business_type][col]` → 返该(method=`"promoted_industry"`)。
  2. 否则查全局主干 `trunk[col]` → 返该(method=`"promoted"`)。
  3. 都没 → None。
  **行业分支优先于全局主干**(具体优先于通用)。
- `SemanticMapper.map_fields` 调 consult 时把 business_type 传进去。
- 模块缓存 `_PROMOTED_TRUNK` + `_PROMOTED_BRANCH` 启动各加载一次(fail-open)。

### promote CLI 两级 gate
候选聚合按 `(column_name, standard_field, business_type)` 分组, 每组算 `max_confidence`, `factory_count`, `has_correction`(组内有无 method=user_correction 且 conf≥0.99)。

- **行业分支晋升** (per business_type, 写进 `branch[business_type]`):
  promotable_to_branch = `(factory_count≥2 且 max_conf≥0.9)` **或** `(has_correction 且 factory_count≥2)`
  - 后者 = Steve 选的"折中": 人工纠正(conf=1.0) + 再 1 工厂任意证据(任意 conf/method) → 升分支。corroboration 不需独立达 0.9。
  - business_type=unknown 的组**不**升分支(无行业归属)。
- **全局主干晋升** (写进 trunk):
  promotable_to_trunk = 同一 `(col, std)` 已在 **≥2 个不同 business_type 的分支**里(跨行业收敛)。
  - 即: 先各行业分支毕业, 再统计该映射横跨几个行业分支; ≥2 → 升主干。
  - 升主干后可选: 从各分支移除该条(主干已覆盖)—— v2 先**不移除**(留分支冗余无害, consult 分支优先仍先命中分支, 等价)。保持简单。
- CLI 输出分两段: 「行业分支候选」+「全局主干候选」, 各列 `--apply` 写对应文件。
- **冲突**: 同 col 在某行业分支 → std_A, 主干已 → std_B(≠A): 分支优先, consult 先命中分支 std_A。这是 feature(行业覆盖通用), 不是 bug。但 promote 时若主干已有该 col→不同 std, 行业分支晋升**仍允许**(行业特例); 反向主干晋升要求跨≥2行业**同一 std** 一致(冲突的行业不计入同一 std 的跨行业计数)。

### is_promotable 重构
`is_promotable` 拆成 `is_branch_promotable(group, branch, trunk)` + `is_trunk_promotable(col, std, branch_state)`。纯函数, 单测覆盖各分支。

---

## 迁移与部署

- 1 个候选表迁移 `V20260601_03__field_mapping_candidates_business_type.sql`: `ALTER TABLE ... ADD COLUMN business_type VARCHAR(50) NOT NULL DEFAULT 'unknown';`(幂等 `IF NOT EXISTS`)。无新表无新 grant(列加在已授权表上)。
- 新 committed JSON `promoted_field_aliases_by_industry.json` 初始 `{}`(空, 等真实晋升填)。
- deploy-smartbi-python.sh runner 自动 apply 迁移 + 同步 scripts/ + JSON。

---

## 不做 (YAGNI)

- 自动毕业(永远人审 --apply)。
- SFT 训练(只攒语料)。
- RAG 自己的 LLM 输出。
- 清洗/分类/表类型 学习(留后续; 本 v2 只补 mapping 纠正 + 蒸馏 capture + 层级晋升)。
- `/api/field/map`(legacy llm_mapper 端点)的 capture/consult — 低 ROI, 不在主上传路径, 单独记 backlog。
- 主干晋升后从分支移除条目(留冗余, consult 分支优先等价)。

---

## 测试策略

- **单测**(纯函数, 必须): `is_branch_promotable` / `is_trunk_promotable` 各 gate 分支; `consult_promoted` 分支优先 vs 主干; `persist_distillation_sample` 幂等 input_hash。
- **集成**(mock pool): capture_candidate 写 business_type + user_correction method; feedback 端点 → 候选行。
- **端到端**(prod 实测, 同 v1 验证手法): 用 `/api/excel/auto-parse` 真 running app 模拟跨行业(restaurant + factory 两工厂)→ 候选带 business_type → CLI 两级 gate dry-run → 行业分支毕业 → consult 行业优先命中。**验完清理**(恢复 JSON + 删候选 + 删 temp)。绝不留 prod 残留。
- ⚠️ standalone 脚本复现不了 LLM(router 在 uvicorn 启动 wire); capture 必须走 running app; consult 可 standalone(pre-LLM dict 查)。见 memory `project_2026_06_01_self_learning_promotion_loop`。

---

## 风险

- **feedback 拿不到列名**: 若现有 feedback API 不足以定位被纠正的列名, 需前端配合加 `column_name` 字段。实现 Task 里先确认 schema_cache correction 结构, 不够则降级(只记能拿到的, 或加字段)。fail-open 保证不崩。
- **chat.py 主答汇聚点定位**: chat.py 多路径, 需准确找到"LLM 最终 answer"唯一点, 避免重复/遗漏 capture。
- **business_type=unknown 占多数**: 若多数上传 domain 检测为 unknown, 行业分支晋升数据稀疏 → 退化成只有全局主干(等价 v1)。可接受(unknown 仍能跨已知行业升主干)。监控 unknown 占比。
