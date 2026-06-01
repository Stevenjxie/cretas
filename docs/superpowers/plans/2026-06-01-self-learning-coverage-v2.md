# 自学习覆盖 v2 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use `- [ ]`.

**Goal:** 把 v1 字段映射学习闭环泛化成多域(field_mapping/classification/data_cleaning)+ 层级晋升(行业分支→全局主干) + 补 用户纠正 / live 蒸馏 学习点。

**Architecture:** 泛化候选表 `smart_bi_learning_candidates(learning_type, source_key, target_value, business_type, factory_id, method, confidence, occurrences)` + 两层 committed JSON(主干 + 行业分支)+ 泛化 `learning_promotion.py`(capture/consult/gate)+ 泛化 CLI。各域在其 LLM 汇聚点接 capture/consult。蒸馏 live capture 独立。

**Tech Stack:** Python 3.8 / FastAPI / asyncpg / PostgreSQL(smartbi_prod_db, RLS)。

**Spec:** `docs/superpowers/specs/2026-06-01-self-learning-coverage-v2-design.md`(每个 Task 先读对应段)。

**关键约束(每个 Task 必遵守):**
- 绝不静默自动毕业;capture/consult 双层 fail-open(绝不阻塞调用方);取 pool `from smartbi.config import get_pg_pool` → `await get_pg_pool()`(可能 None)。
- standalone 复现不了 LLM(router uvicorn 启动 wire)→ LLM-path 验证走 running app `/api/excel/auto-parse`(form `factory_id`+`use_cache=false`, prefix `/api/excel` 非 `/api/smartbi/excel`);consult 可 standalone。
- 单测放 `backend/python/tests/`(CI pytest 只跑这里);flake8 扫全目录, import 必须文件顶端(中段 append = E402 阻塞)。
- 迁移走 `V20260601_03__*.sql` + deploy runner, 不手动 psql DDL。

---

## Phase 0 — 泛化框架(critical path)

### Task 1: 迁移 — 泛化候选表

**Files:**
- Create: `backend/python/smartbi/database/migrations/V20260601_03__generalize_learning_candidates.sql`

- [ ] **Step 1: 写迁移**(幂等, rename + 泛化列 + learning_type/business_type + UNIQUE 重建)

```sql
-- v1 smart_bi_field_mapping_candidates(当前空)泛化为多域学习候选表。
-- rename 表 + 列, 加 learning_type/business_type, 重建 UNIQUE。grant/RLS 随表保留。
ALTER TABLE IF EXISTS smart_bi_field_mapping_candidates RENAME TO smart_bi_learning_candidates;
ALTER TABLE smart_bi_learning_candidates RENAME COLUMN column_name TO source_key;
ALTER TABLE smart_bi_learning_candidates RENAME COLUMN standard_field TO target_value;
ALTER TABLE smart_bi_learning_candidates ADD COLUMN IF NOT EXISTS learning_type VARCHAR(32) NOT NULL DEFAULT 'field_mapping';
ALTER TABLE smart_bi_learning_candidates ADD COLUMN IF NOT EXISTS business_type VARCHAR(50) NOT NULL DEFAULT 'unknown';
ALTER TABLE smart_bi_learning_candidates DROP CONSTRAINT IF EXISTS smart_bi_field_mapping_candid_column_name_standard_field_fa_key;
ALTER TABLE smart_bi_learning_candidates ADD CONSTRAINT uq_learning_candidate UNIQUE (learning_type, source_key, target_value, factory_id);
-- 旧索引 idx_fmc_colstd 随列 rename 仍指 (source_key,target_value), 保留即可。
```

- [ ] **Step 2: 本地无法 apply(无库)→ 部署时 runner apply。Task 完成标准 = 文件 + SQL review 通过。** Commit:
```bash
git commit -m "feat(self-learn): migration generalize candidate table to learning_candidates" -- backend/python/smartbi/database/migrations/V20260601_03__generalize_learning_candidates.sql
```

---

### Task 2: 两层 promoted JSON + 迁移 16 种子

**Files:**
- Create: `backend/python/smartbi/data/promoted_learnings.json`
- Create: `backend/python/smartbi/data/promoted_learnings_by_industry.json`
- Delete: `backend/python/smartbi/data/promoted_field_aliases.json`(迁移后)

- [ ] **Step 1:** 读现有 `promoted_field_aliases.json`(16 字段映射种子), 写成
  `promoted_learnings.json = {"field_mapping": {<16 seeds>}, "classification": {}, "data_cleaning": {}}`。
- [ ] **Step 2:** `promoted_learnings_by_industry.json = {"field_mapping": {}, "classification": {}, "data_cleaning": {}}`。
- [ ] **Step 3:** 删 `promoted_field_aliases.json`。Commit 三个改动。

---

### Task 3: 泛化 `learning_promotion.py`(capture/consult/gate)

**Files:**
- Create: `backend/python/smartbi/services/learning_promotion.py`(泛化自 field_promotion.py)
- Test: `backend/python/tests/test_learning_promotion.py`
- 保留 `field_promotion.py` 暂作 thin re-export shim(避免一次改爆所有引用), 或直接改引用(Task 5 处理 SemanticMapper)。

- [ ] **Step 1: 写失败测试**(纯函数 gate + consult, 全覆盖):

```python
# tests/test_learning_promotion.py
from smartbi.services.learning_promotion import (
    is_branch_promotable, is_trunk_promotable, consult_in,
)

def _g(**kw):  # group helper
    d = dict(learning_type="field_mapping", source_key="营业进账", target_value="revenue",
             business_type="restaurant", max_confidence=0.95, factory_count=2, has_correction=False)
    d.update(kw); return d

def test_branch_llm_consensus():
    ok, _ = is_branch_promotable(_g(), branch={}, trunk={}); assert ok
def test_branch_blocks_single_factory():
    ok, _ = is_branch_promotable(_g(factory_count=1), {}, {}); assert not ok
def test_branch_blocks_low_conf():
    ok, _ = is_branch_promotable(_g(max_confidence=0.8), {}, {}); assert not ok
def test_branch_correction_fasttrack():  # 折中: 纠正 + 再1工厂任意证据(conf 不限)
    ok, _ = is_branch_promotable(_g(max_confidence=0.5, has_correction=True, factory_count=2), {}, {}); assert ok
def test_branch_correction_needs_corroboration():  # 单条纠正不够
    ok, _ = is_branch_promotable(_g(has_correction=True, factory_count=1), {}, {}); assert not ok
def test_branch_unknown_business_type_never():
    ok, _ = is_branch_promotable(_g(business_type="unknown"), {}, {}); assert not ok
def test_branch_skip_already_branched():
    ok, _ = is_branch_promotable(_g(), branch={"field_mapping": {"restaurant": {"营业进账": "revenue"}}}, trunk={}); assert not ok
def test_trunk_promote_two_industries():
    bs = {"field_mapping": {"restaurant": {"营业进账": "revenue"}, "factory": {"营业进账": "revenue"}}}
    ok, _ = is_trunk_promotable("field_mapping", "营业进账", "revenue", bs); assert ok
def test_trunk_blocks_one_industry():
    bs = {"field_mapping": {"restaurant": {"营业进账": "revenue"}}}
    ok, _ = is_trunk_promotable("field_mapping", "营业进账", "revenue", bs); assert not ok
def test_consult_branch_over_trunk():
    branch = {"field_mapping": {"restaurant": {"客流": "traffic_branch"}}}
    trunk = {"field_mapping": {"客流": "traffic_trunk"}}
    assert consult_in(branch, trunk, "field_mapping", "客流", "restaurant") == ("traffic_branch", "promoted_industry")
    assert consult_in(branch, trunk, "field_mapping", "客流", "factory") == ("traffic_trunk", "promoted")
    assert consult_in(branch, trunk, "field_mapping", "未知列", "restaurant") == (None, None)
```

- [ ] **Step 2: 跑测试确认 fail** `pytest backend/python/tests/test_learning_promotion.py -v`
- [ ] **Step 3: 实现** `learning_promotion.py`:

```python
"""多域学习毕业: capture(候选) → promote(两级 gate + 人审) → consult(行业分支优先, 全局主干兜底)。
绝不静默自动毕业; curated/冲突时具体优先; capture/consult fail-open。"""
from __future__ import annotations
import json, logging
from pathlib import Path
from typing import Any, Dict, Optional, Tuple

logger = logging.getLogger(__name__)
_DATA = Path(__file__).parent.parent / "data"
TRUNK_FILE = _DATA / "promoted_learnings.json"
BRANCH_FILE = _DATA / "promoted_learnings_by_industry.json"
MIN_CONFIDENCE = 0.9
MIN_FACTORIES = 2
MIN_INDUSTRIES = 2
CAPTURE_METHODS = ("embedding", "llm", "user_correction")

def is_branch_promotable(group, branch, trunk) -> Tuple[bool, str]:
    lt, src, tgt, bt = group["learning_type"], group["source_key"], group["target_value"], group["business_type"]
    if bt == "unknown":
        return False, "unknown 业态不升分支"
    if branch.get(lt, {}).get(bt, {}).get(src) == tgt:
        return False, "已在行业分支"
    fc = int(group.get("factory_count", 0)); mc = float(group.get("max_confidence", 0))
    has_corr = bool(group.get("has_correction", False))
    if fc >= MIN_FACTORIES and mc >= MIN_CONFIDENCE:
        return True, "LLM 共识"
    if has_corr and fc >= MIN_FACTORIES:
        return True, "纠正+corroboration"
    return False, f"未达标(工厂{fc}/置信{mc})"

def is_trunk_promotable(learning_type, source_key, target_value, branch_state) -> Tuple[bool, str]:
    lt_branches = branch_state.get(learning_type, {})
    n = sum(1 for bt, m in lt_branches.items() if m.get(source_key) == target_value)
    if n >= MIN_INDUSTRIES:
        return True, f"跨{n}行业一致"
    return False, f"仅{n}行业"

def consult_in(branch, trunk, learning_type, source_key, business_type) -> Tuple[Optional[str], Optional[str]]:
    if not source_key:
        return None, None
    k = source_key.strip()
    if business_type:
        v = branch.get(learning_type, {}).get(business_type, {}).get(k)
        if v: return v, "promoted_industry"
    v = trunk.get(learning_type, {}).get(k)
    if v: return v, "promoted"
    return None, None

def _load(path) -> Dict[str, Any]:
    try:
        if path.exists():
            with open(path, encoding="utf-8") as f: return json.load(f)
    except Exception as e:
        logger.warning("load %s failed (ignored): %s", path, e)
    return {}

_TRUNK = _load(TRUNK_FILE)
_BRANCH = _load(BRANCH_FILE)

def consult_promoted(learning_type, source_key, business_type=None) -> Tuple[Optional[str], Optional[str]]:
    return consult_in(_BRANCH, _TRUNK, learning_type, source_key, business_type)

async def capture_candidate(pool, learning_type, source_key, target_value,
                            factory_id, method, confidence, business_type="unknown") -> None:
    if not (source_key and target_value and method in CAPTURE_METHODS):
        return
    try:
        async with pool.acquire() as conn:
            await conn.execute(
                """INSERT INTO smart_bi_learning_candidates
                   (learning_type, source_key, target_value, factory_id, method, confidence, business_type)
                   VALUES ($1,$2,$3,$4,$5,$6,$7)
                   ON CONFLICT (learning_type, source_key, target_value, factory_id) DO UPDATE
                     SET occurrences = smart_bi_learning_candidates.occurrences + 1,
                         last_seen = now(),
                         confidence = GREATEST(smart_bi_learning_candidates.confidence, EXCLUDED.confidence),
                         business_type = EXCLUDED.business_type""",
                learning_type, source_key.strip(), target_value.strip(),
                factory_id, method, round(float(confidence), 3), business_type or "unknown")
    except Exception as e:
        logger.warning("capture_candidate failed (ignored): %s", e)
```

- [ ] **Step 4: 跑测试 pass。** Commit `learning_promotion.py` + test。

---

### Task 4: 泛化 CLI `promote_learnings.py`

**Files:**
- Create: `scripts/promote_learnings.py`(泛化自 promote_field_mappings.py)
- Modify: `scripts/deploy/deploy-smartbi-python.sh`(同步 scripts/ 已有, 确认覆盖新文件名)

- [ ] **Step 1:** `_fetch_candidates`: `GROUP BY learning_type, source_key, target_value, business_type` + `MAX(confidence) max_confidence, COUNT(DISTINCT factory_id) factory_count, bool_or(method='user_correction' AND confidence>=0.99) has_correction`。
- [ ] **Step 2:** main(apply, type_filter): 加载 trunk+branch;先跑 branch gate(按 lt/bt 分组)出「行业分支候选」;用毕业后的 branch_state 跑 trunk gate 出「全局主干候选」;`--apply` 写两个文件;`--type` 过滤。sys.path 同 v1(加 backend/python + backend/python/smartbi)。
- [ ] **Step 3:** 手验 dry-run 逻辑(可 mock)。Commit。

---

## Phase 1 — field_mapping 接入泛化框架(F1 用户纠正 + F3 层级)

### Task 5: SemanticMapper 接泛化 capture/consult + business_type

**Files:**
- Modify: `backend/python/smartbi/services/semantic_mapper.py`(consult + capture 段)
- Modify: `backend/python/smartbi/api/excel.py`(auto_parse 把 detected domain 传进 map_fields)

- [ ] **Step 1:** `map_fields` 加可选参 `business_type: Optional[str] = None`。
- [ ] **Step 2:** consult 段改 `from smartbi.services.learning_promotion import consult_promoted`;`std, _m = consult_promoted("field_mapping", col, business_type)`;命中按返回 method 标 `promoted`/`promoted_industry`。
- [ ] **Step 3:** capture 段改调泛化 `capture_candidate(pool, "field_mapping", m.original, m.standard, factory_id, m.method, m.confidence, business_type)`。
- [ ] **Step 4:** excel.py auto_parse: 把已检测的 domain 作为 business_type 传入 `mapper.map_fields(..., business_type=<domain>)`。
- [ ] **Step 5:** grep 确认无残留 `field_promotion` 引用(或保留 shim)。Commit。

---

### Task 6: 用户纠正 capture(F1 金标准)

**Files:**
- Modify: `backend/python/smartbi/api/excel.py`(`/auto-parse/feedback`)
- 可能 Modify: 前端纠正调用(若需带 column_name)— 先确认 schema_cache 结构再定。

- [ ] **Step 1: 确认列名可得** — 读 `schema_cache.add_user_correction` + correction 结构, 确认能从 cache_key 拿到 factory_id + domain + 被纠正的列名。不足 → 给 feedback 加 `column_name: Optional[str]=Form(None)` 并前端带上。
- [ ] **Step 2:** `correction_type=="mapping"` 时: `pool=await get_pg_pool()`;`await capture_candidate(pool,"field_mapping", column_name, correct_value, factory_id, "user_correction", 1.0, business_type=domain)`。双层 try/except fail-open。
- [ ] **Step 3:** 集成测试(mock pool)验证写一行 method=user_correction。Commit。

---

## Phase 2 — live 蒸馏 capture(F2, 独立)

### Task 7: 抽共享 `persist_distillation_sample`

**Files:**
- Create: `backend/python/smartbi/services/distillation_capture.py`
- Modify: `backend/python/smartbi/services/materialized_analytics/llm_materializer.py`(改调共享 helper)
- Test: `backend/python/tests/test_distillation_capture.py`(幂等 input_hash)

- [ ] Step 1: 把 `_persist_distillation_sample` 逻辑提到 `distillation_capture.py::persist_distillation_sample(pool, source, task_type, input_text, teacher_output, *, business_type="unknown", factory_id=None, system_prompt=None, teacher_model=None, template_codes=None, quality=None, metadata=None)`, input_hash=sha256(source+task_type+input_text), fire-and-forget。
- [ ] Step 2: llm_materializer 改 import 它(行为不变, 回归测试)。
- [ ] Step 3: 测试幂等 + 永不 raise。Commit。

### Task 8: orchestrator 洞察 capture

**Files:** Modify `backend/python/smartbi/agent/orchestrator.py`

- [ ] `answer_insight` 拿到 answer 后 fire-and-forget `persist_distillation_sample(pool, source="agent_insight", task_type="insight", input_text=user_prompt, teacher_output=answer, business_type=<domain>, factory_id=factory_id, teacher_model=<model>)`。`stream_insight` 流结束累计完整 answer 后 capture 一次。不改返回。Commit。

### Task 9: chat.py 主答 capture

**Files:** Modify `backend/python/smartbi/api/chat.py`

- [ ] Step 1: 定位"LLM 生成最终 answer"唯一汇聚点(避免每 SQL 分支埋)。
- [ ] Step 2: 该点 fire-and-forget `source="chat_qa", task_type="qa"`。Commit。

---

## Phase 3 — classification 域

### Task 10: 菜品→品类 capture/consult

**Files:** 实现 Task 先 grep 定位分类 LLM 汇聚点(dish_classifier / restaurant 服务)。

- [ ] Step 1: 定位"分类的唯一非确定性(LLM/模糊)汇聚点"。若当前纯规则无 LLM → 标 backlog 不强塞(记录原因), 跳过本 Task。
- [ ] Step 2: 有 LLM 点则: 分类前 `consult_promoted("classification", dish_name, business_type)` 命中 0-token 用毕业品类;LLM 给出分类后 `capture_candidate(pool,"classification", dish_name, category, factory_id, "llm", conf, business_type)`。Commit。

---

## Phase 4 — data_cleaning 值归一域

### Task 11: 脏值→归一值 capture/consult

**Files:** Modify `backend/python/smartbi/services/data_cleaner.py`

- [ ] Step 1: 定位 LLM 把 raw_value 归一成 canonical_value 的点(只这类 key→value, **不碰生成规则代码**)。
- [ ] Step 2: 归一前 consult `("data_cleaning", raw_value, business_type)` 命中 0-token;LLM 归一后 capture `("data_cleaning", raw_value, canonical_value, factory_id, "llm", conf, business_type)`。若无此类 value→value 点(全是生成规则)→ 标 backlog 跳过。Commit。

---

## Phase 5 — E2E + 清理

### Task 12: prod 端到端 + 清理

- [ ] Step 1: 部署 test 先(`deploy-smartbi-python.sh --env test`)→ 迁移 apply 成功。
- [ ] Step 2: 真 running app 跨行业(restaurant+factory)`/api/excel/auto-parse` → 候选带 learning_type/business_type → `promote_learnings.py` dry-run 两级 gate → 行业分支毕业 → consult 行业优先(standalone 验)。
- [ ] Step 3: classification/cleaning 各跑一次真实触发验 capture(若 Task 10/11 未 backlog)。
- [ ] Step 4: 部署 prod + 同上验证。
- [ ] Step 5: **清理** 所有测试候选/upload/JSON 改动(恢复种子态), 表回干净。temp 文件删。
- [ ] Step 6: 最终 code review subagent。
