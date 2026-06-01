# 自学习覆盖 v2 — 泛化多域学习框架 设计

**日期**: 2026-06-01
**前置**: v1 闭环 (字段映射 capture→promote→consult, 扁平全局) 已 LIVE prod (PR #364/#367/#368)。
**触发**: 审计发现多处"学习点遗漏" + Steve 要求 (1) 跨行业/跨门店层级晋升 (2) 把学习泛化到分类/清洗等域。

---

## 目标

把 v1 的"只学字段映射、扁平全局毕业"升级为:
1. **泛化框架**: 一套 capture→promote→consult 支持多个 `learning_type`(field_mapping / classification / data_cleaning / 未来更多), 不再每域复制一套。
2. **层级晋升**(Steve 的 subbranch→main branch): 行业分支 → 全局主干, 按 `business_type` scope。
3. **补漏的学习点**: 用户纠正(金标准)、live 问答/报表蒸馏、分类、清洗(值归一化粒度)。

核心不变量(架构原则, 永不动): **绝不静默自动毕业**(人审 `--apply`)、**绝不 RAG 自己的 LLM 输出**、**绝不自动执行 LLM 生成的代码毕业**(本 v2 清洗只学"值→归一值", 不毕业生成规则代码)。

---

## 共享地基

### business_type 源
唯一源 = 检测到的 `domain`(`llm_materializer` 已用): `restaurant` / `factory` / `unknown`。三处 capture 复用同一源。`unknown` 候选不升行业分支, 但可跨已知行业升主干。

### 泛化候选表 (v1 表迁移)
v1 的 `smart_bi_field_mapping_candidates`(当前空)迁移泛化为 `smart_bi_learning_candidates`:

```sql
-- 迁移 V20260601_03: rename + 泛化列 + 加 learning_type/business_type
ALTER TABLE smart_bi_field_mapping_candidates RENAME TO smart_bi_learning_candidates;
ALTER TABLE smart_bi_learning_candidates RENAME COLUMN column_name TO source_key;
ALTER TABLE smart_bi_learning_candidates RENAME COLUMN standard_field TO target_value;
ALTER TABLE smart_bi_learning_candidates ADD COLUMN IF NOT EXISTS learning_type VARCHAR(32) NOT NULL DEFAULT 'field_mapping';
ALTER TABLE smart_bi_learning_candidates ADD COLUMN IF NOT EXISTS business_type VARCHAR(50) NOT NULL DEFAULT 'unknown';
-- UNIQUE 重建: (learning_type, source_key, target_value, factory_id)
ALTER TABLE smart_bi_learning_candidates DROP CONSTRAINT IF EXISTS smart_bi_field_mapping_candid_column_name_standard_field_fa_key;
ALTER TABLE smart_bi_learning_candidates ADD CONSTRAINT uq_learning_candidate UNIQUE (learning_type, source_key, target_value, factory_id);
-- 索引重建 + 序列/索引随 rename 自动跟随; grant/RLS 随表保留 (ALTER 非新表)
```
- RLS policy 随 rename 保留(tenant_insert/select/update)。grant 保留(#367 已授, ALTER 不丢)。**仍无 DELETE policy**(清理用 postgres superuser, 同 v1)。
- 列语义: `source_key`=被映射/分类/清洗的原值(列名/菜品名/脏值), `target_value`=学到的目标(标准字段/品类/归一值)。

### 两层 promoted 文件 (按 learning_type 结构化)
- `data/promoted_learnings.json`(**全局主干**): `{ "field_mapping": {src: tgt}, "classification": {...}, "data_cleaning": {...} }`。v1 的 16 个字段映射种子迁移进 `["field_mapping"]`。
- `data/promoted_learnings_by_industry.json`(**行业分支**): `{ "field_mapping": {"restaurant": {src: tgt}}, "classification": {...}, ... }`。初始按需填。
- 都 committed, 可 PR-review 可回滚。

---

## 泛化核心: `field_promotion.py` → `learning_promotion.py`

### capture
```python
async def capture_candidate(pool, learning_type, source_key, target_value,
                            factory_id, method, confidence, business_type="unknown"):
    # method 白名单: embedding / llm / user_correction (放开 user_correction)
    # ON CONFLICT (learning_type, source_key, target_value, factory_id) DO UPDATE
    #   occurrences+1, last_seen=now, confidence=GREATEST, business_type=EXCLUDED
    # 双层 fail-open: 任何失败只 warn, 绝不阻塞调用方
```

### consult (层级 + 按域)
```python
def consult_promoted(learning_type, source_key, business_type=None) -> Optional[str]:
    # 1. 行业分支优先: branch[learning_type][business_type][source_key]  -> method 标 promoted_industry
    # 2. 全局主干兜底: trunk[learning_type][source_key]                  -> method 标 promoted
    # 3. None
    # 模块缓存 _TRUNK / _BRANCH 启动各加载一次, fail-open
```

### gate (纯函数, 两级, 单测覆盖)
```python
def is_branch_promotable(group, branch, trunk) -> (bool, reason):
    # group: 聚合 (learning_type, source_key, target_value, business_type)
    #        含 max_confidence / factory_count / has_correction(组内 method=user_correction 且 conf>=0.99)
    # business_type == 'unknown' -> 不升分支
    # 已在 branch[lt][bt] -> 跳过
    # 与 trunk 冲突 (主干 src->别的 tgt): 行业特例仍允许升分支
    # promotable = (factory_count>=2 and max_conf>=0.9)            # LLM 共识
    #           or (has_correction and factory_count>=2)           # 折中: 纠正 + 再1工厂任意证据
def is_trunk_promotable(learning_type, source_key, target_value, branch_state) -> (bool, reason):
    # 同 (lt, src, tgt) 已在 >=2 个不同 business_type 的分支 -> 升主干
```

### promote CLI (`promote_learnings.py`, 泛化自 promote_field_mappings.py)
- `_fetch_candidates`: `GROUP BY learning_type, source_key, target_value, business_type` + MAX(conf) + COUNT(DISTINCT factory_id) + bool_or(method='user_correction' AND conf>=0.99) AS has_correction。
- 输出分段: 「行业分支候选」(按 learning_type 再分组) + 「全局主干候选」。
- `--apply` 写对应文件(branch → `_by_industry.json`, trunk → `promoted_learnings.json`)。绝不自动。
- `--type <learning_type>` 可选过滤(只看某域)。

---

## 接入的域 (capture/consult 站点)

### 域 1: field_mapping (已有, 迁到泛化框架 + 加层级 + 用户纠正)
- `SemanticMapper.map_fields`:
  - capture 改调泛化 `capture_candidate(learning_type="field_mapping", source_key=col, target_value=std, business_type=<domain>, ...)`。domain 从 auto_parse 上下文传入(map_fields 加可选 `business_type` 参数)。
  - consult 改调 `consult_promoted("field_mapping", col, business_type)`(层级)。
- **用户纠正(F1, 金标准)**: `/auto-parse/feedback` 的 `correction_type=="mapping"`:
  - 从 schema_cache 取 cache_key 的 factory_id + domain + **列名**。⚠️ 确认 correction 结构含列名; 不足则前端纠正多带 `column_name` form 字段。
  - `capture_candidate("field_mapping", column_name, correct_value, factory_id, method="user_correction", confidence=1.0, business_type=domain)`。
  - 双层 fail-open, 绝不让 feedback 报错。

### 域 2: classification (新, 菜品→品类)
- 定位分类 LLM 点(dish→category)。当前菜品分类散在 dish_classifier / restaurant 服务。
- LLM 给出 `dish_name → category` 时 capture `("classification", dish_name, category, business_type=<restaurant>, method="llm", conf)`。
- 分类消费方查 consult `("classification", dish_name, business_type)` 命中则 0-token 用毕业品类(替代每次 LLM/规则猜)。
- ⚠️ 实现 Task 先定位"分类的唯一汇聚点"(避免在多处重复埋)。若当前分类是纯规则(无 LLM), 则 capture 点是"规则未命中走 LLM/模糊"的那支。

### 域 3: data_cleaning (新, 只到"脏值→归一值")
- `data_cleaner.py` 现有 TTL=3600s 内存缓存的"自动学习"(LLM 识别问题 + 生成规则)。
- v2 **只**把"脏值 → 归一值"这种 key→value 级别持久化 + 毕业: LLM 把某 raw_value 归一成 canonical_value 时 capture `("data_cleaning", raw_value, canonical_value, business_type, method="llm", conf)`。
- consult `("data_cleaning", raw_value, business_type)` 命中 → 0-token 直接用归一值。
- ⛔ **不做**: LLM 生成的清洗规则代码毕业(codegen 执行毕业 = 单独安全子项目)。本 v2 清洗学习只限确定的 值→值 归一。

---

## Feature 2: live 问答/报表 → 蒸馏语料 (独立于上面, 可并行)

- 抽共享 helper `services/distillation_capture.py::persist_distillation_sample(pool, source, task_type, input_text, teacher_output, *, business_type="unknown", factory_id=None, system_prompt=None, teacher_model=None, ...)`, 幂等(input_hash), fire-and-forget。`llm_materializer` 改调它(行为不变, 回归测试保证)。
- `orchestrator.answer_insight`: 拿到 answer 后 capture `source="agent_insight", task_type="insight"`。`stream_insight`: 流结束累计完整 answer 后 capture 一次。
- `chat.py` AI问答**主答汇聚点**: capture `source="chat_qa", task_type="qa"`。⚠️ 实现 Task 先定位唯一"LLM 生成最终 answer"点。
- 脱敏: 蒸馏样本是**内部主权语料**(不出公有 AI), 存原始值 OK, 但表 RLS + 内部访问(`smart_bi_distillation_samples` 已 RLS)。

---

## 迁移与部署

- 迁移 `V20260601_03__generalize_learning_candidates.sql`(rename + 泛化列 + learning_type/business_type + UNIQUE 重建, 全 `IF [NOT] EXISTS` 幂等)。无新表无新 grant。
- 新 committed JSON 两个(`promoted_learnings.json` 含迁移后的 16 字段映射种子; `promoted_learnings_by_industry.json` 初始 `{}`)。
- 旧 `promoted_field_aliases.json` 留作迁移源后**删除**(代码不再读它); `promoted_field_aliases_by_industry.json` 从未真用, 不创建。
- runner 自动 apply 迁移 + 同步 scripts/ + JSON。
- 部署后 prod 端到端验证(见下), 验完清理。

---

## 不做 (YAGNI / 原则)

- **自动毕业**(永远人审 `--apply`)— 原则。
- **RAG 自己的 LLM 输出** — 原则。
- **LLM 生成清洗规则代码的毕业**(codegen 执行)— 单独安全子项目, 本 v2 清洗只学值→值归一。
- **SFT 训练** — 只攒蒸馏语料, 触发器命中才训。
- **table_type 学习** — 低价值 + 喂上游检测有循环风险, backlog。
- **`/api/field/map`(legacy)** — 死端点(无 live caller), backlog 改"删除"。
- **主干晋升后从分支删条目** — 零行为差异, 省。

---

## 测试策略

- **单测**(纯函数, 必须): `is_branch_promotable` / `is_trunk_promotable` 各分支(LLM共识 / 纠正折中 / unknown不升分支 / 冲突 / 跨行业升主干); `consult_promoted` 分支优先 vs 主干 vs miss, 按 learning_type 隔离; `persist_distillation_sample` 幂等。
- **集成**(mock pool): capture 写 learning_type/business_type/user_correction; feedback→候选; classification/cleaning capture 站点。
- **回归**: `llm_materializer` 改调共享 helper 后行为不变。
- **端到端**(prod 真 running app, 同 v1 手法, 验完清理): `/api/excel/auto-parse` 跨行业(restaurant+factory)→ field_mapping 候选带 business_type → CLI 两级 gate dry-run → 行业分支毕业 → consult 行业优先。classification/cleaning 各跑一次真实触发验 capture。**绝不留 prod 残留**。
- ⚠️ standalone 复现不了 LLM(router 在 uvicorn 启动 wire); capture 必须走 running app; consult 可 standalone。见 memory `project_2026_06_01_self_learning_promotion_loop`。

---

## 风险

- **v1 表 rename 影响面**: field_promotion.py + 1 capture 站点 + CLI 全要改引用。表当前空, 迁移安全。改完 grep 确认无残留旧表名/旧列名引用。
- **feedback 拿不到列名 / chat.py 主答汇聚点 / classification 汇聚点**: 三个"先定位唯一点"风险, 实现 Task 第一步确认, 不足则降级(加字段或标 backlog), fail-open 保证不崩。
- **business_type=unknown 占多数**: 行业分支稀疏 → 退化成全局主干(等价 v1)。可接受, 监控 unknown 占比。
- **分类/清洗当前可能是纯规则无 LLM**: 若某域当前无 LLM 调用, 则该域暂无 capture 源(capture 只对 llm/embedding/user_correction)。实现 Task 确认每域确有非确定性映射点; 没有则该域记 backlog 不强塞。
