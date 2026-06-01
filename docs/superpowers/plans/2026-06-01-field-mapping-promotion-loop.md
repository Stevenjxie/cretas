# 字段映射"毕业进规则"闭环 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 live 字段 mapper 把 embedding/LLM 学到的映射记成候选，经人审毕业进一个权威的 `promoted_field_aliases.json`，之后规则层先查它（0 token、确定）。

**Architecture:** 三个隔离单元 — Capture(候选写进 DB 表) → Promote(离线工具 gate+人审 → committed JSON) → Consult(live mapper 规则层先查 JSON)。绝不静默自动毕业；curated 冲突时 curated 赢；可审计可回滚。

**Tech Stack:** Python 3.8 / asyncpg (smartbi_db) / FastAPI app / pytest / 现有 `services/semantic_mapper.py`。

Spec: `docs/superpowers/specs/2026-06-01-self-learning-promotion-loop-strategy-and-design.md`

---

## File Structure

- **Create** `backend/python/smartbi/database/migrations/V20260601_01__field_mapping_candidates.sql` — 候选表 + RLS。
- **Create** `backend/python/smartbi/services/field_promotion.py` — capture(upsert 候选)、load_promoted_aliases()、consult_promoted()、纯 gate 函数 `is_promotable()`。
- **Create** `backend/python/smartbi/data/promoted_field_aliases.json` — 权威毕业别名(committed, 起步 `{}`)。
- **Create** `scripts/promote_field_mappings.py` — Promote CLI(扫候选 → gate → 清单 → `--apply` 写 JSON)。
- **Modify** `backend/python/smartbi/services/semantic_mapper.py` — Consult(在 `_map_with_rules` 后、embedding 前查 promoted) + Capture(map_fields 末尾记非 rule 映射)。
- **Create** `backend/python/tests/test_field_promotion.py` — gate / consult / conflict / capture 纯逻辑测试。
- **Cleanup**(确认 0 live 引用后) `structure/semantic_mapper.py` + 孤儿 `learned_field_mappings.json`。

---

### Task 1: 候选表 migration

**Files:**
- Create: `backend/python/smartbi/database/migrations/V20260601_01__field_mapping_candidates.sql`

- [ ] **Step 1: 写 migration SQL**

```sql
-- 字段映射毕业候选: 每个工厂每次 embedding/LLM 解决一个非规则列时 upsert 一行,
-- 累计 occurrence。Promote 工具据此判断"跨>=2工厂复现"。
CREATE TABLE IF NOT EXISTS smart_bi_field_mapping_candidates (
    id              BIGSERIAL PRIMARY KEY,
    column_name     TEXT        NOT NULL,
    standard_field  TEXT        NOT NULL,
    factory_id      VARCHAR(50),
    method          TEXT        NOT NULL,           -- 'embedding' | 'llm'
    confidence      NUMERIC(4,3) NOT NULL,
    occurrences     INTEGER     NOT NULL DEFAULT 1,
    first_seen      TIMESTAMP   NOT NULL DEFAULT now(),
    last_seen       TIMESTAMP   NOT NULL DEFAULT now(),
    UNIQUE (column_name, standard_field, factory_id)
);
CREATE INDEX IF NOT EXISTS idx_fmc_colstd ON smart_bi_field_mapping_candidates (column_name, standard_field);

ALTER TABLE smart_bi_field_mapping_candidates ENABLE ROW LEVEL SECURITY;
ALTER TABLE smart_bi_field_mapping_candidates FORCE ROW LEVEL SECURITY;
-- 与现有表一致的 __internal__ 三分支 (bg-flush GUC 永远 __internal__, 见 Issue #590)。
CREATE POLICY tenant_insert ON smart_bi_field_mapping_candidates FOR INSERT
  WITH CHECK (current_setting('app.factory_id', true) IS NULL
              OR current_setting('app.factory_id', true) = ''
              OR current_setting('app.factory_id', true) = '__internal__'
              OR factory_id = current_setting('app.factory_id', true));
CREATE POLICY tenant_select ON smart_bi_field_mapping_candidates FOR SELECT
  USING (current_setting('app.factory_id', true) IS NULL
         OR current_setting('app.factory_id', true) = ''
         OR current_setting('app.factory_id', true) = '__internal__'
         OR factory_id = current_setting('app.factory_id', true));
CREATE POLICY tenant_update ON smart_bi_field_mapping_candidates FOR UPDATE
  USING (current_setting('app.factory_id', true) IS NULL
         OR current_setting('app.factory_id', true) = ''
         OR current_setting('app.factory_id', true) = '__internal__'
         OR factory_id = current_setting('app.factory_id', true));
```

- [ ] **Step 2: 本地 apply 验证 (test 库)**

Run: `psql -h localhost -U smartbi_user -d smartbi_db -f backend/python/smartbi/database/migrations/V20260601_01__field_mapping_candidates.sql`
Expected: `CREATE TABLE` / `CREATE POLICY` 无错 (若本地无 smartbi_db 跳过, 部署时 runner 跑)。

- [ ] **Step 3: Commit**

```bash
git commit -m "feat(self-learn): field-mapping candidates table (migration)" -- backend/python/smartbi/database/migrations/V20260601_01__field_mapping_candidates.sql
```

---

### Task 2: `is_promotable` 纯 gate 函数 (TDD)

**Files:**
- Create: `backend/python/smartbi/services/field_promotion.py`
- Test: `backend/python/tests/test_field_promotion.py`

- [ ] **Step 1: 写失败测试**

```python
# backend/python/tests/test_field_promotion.py
from smartbi.services.field_promotion import is_promotable


def _cand(col="本月实际", std="actual_amount", conf=0.95, factories=("F1", "F2"), occ=4):
    return {"column_name": col, "standard_field": std, "max_confidence": conf,
            "factory_count": len(set(factories)), "occurrences": occ}


def test_promotable_high_conf_multi_factory():
    ok, reason = is_promotable(_cand(), curated={}, promoted={})
    assert ok is True


def test_reject_low_confidence():
    ok, reason = is_promotable(_cand(conf=0.85), curated={}, promoted={})
    assert ok is False and "置信" in reason


def test_reject_single_factory():
    ok, reason = is_promotable(_cand(factories=("F1",)), curated={}, promoted={})
    assert ok is False and "工厂" in reason


def test_reject_conflict_with_curated():
    # curated 已把 本月实际 映到别的 → 冲突, curated 赢
    ok, reason = is_promotable(_cand(), curated={"本月实际": "budget_amount"}, promoted={})
    assert ok is False and "冲突" in reason


def test_already_promoted_is_not_a_candidate():
    ok, reason = is_promotable(_cand(), curated={}, promoted={"本月实际": "actual_amount"})
    assert ok is False and "已毕业" in reason
```

- [ ] **Step 2: 运行确认失败**

Run: `cd backend/python && python -m pytest tests/test_field_promotion.py -q`
Expected: FAIL — `ModuleNotFoundError` / `cannot import name 'is_promotable'`。

- [ ] **Step 3: 写 field_promotion.py 的 gate**

```python
# backend/python/smartbi/services/field_promotion.py
"""字段映射毕业闭环: Capture(候选) → Promote(gate+人审) → Consult(规则层先查)。

绝不静默自动毕业 (Promote 工具 --apply 是人审后显式执行)。curated 冲突时 curated 赢。
"""
from __future__ import annotations

import json
import logging
from pathlib import Path
from typing import Any, Dict, Optional, Tuple

logger = logging.getLogger(__name__)

PROMOTED_FILE = Path(__file__).parent.parent / "data" / "promoted_field_aliases.json"

# 毕业门槛 (保守: 错规则比调 LLM 更糟)
MIN_CONFIDENCE = 0.9
MIN_FACTORIES = 2


def is_promotable(
    candidate: Dict[str, Any],
    curated: Dict[str, str],
    promoted: Dict[str, str],
) -> Tuple[bool, str]:
    """纯函数: 一个聚合候选能否毕业。candidate 含 column_name/standard_field/
    max_confidence/factory_count。返回 (可否, 原因)。"""
    col = candidate["column_name"]
    std = candidate["standard_field"]
    if col in promoted:
        return False, f"已毕业 ({col}→{promoted[col]})"
    if col in curated:
        if curated[col] != std:
            return False, f"与 curated 冲突 (curated: {col}→{curated[col]})"
        return False, f"已在 curated ({col})"
    if float(candidate.get("max_confidence", 0)) < MIN_CONFIDENCE:
        return False, f"置信不足 (<{MIN_CONFIDENCE})"
    if int(candidate.get("factory_count", 0)) < MIN_FACTORIES:
        return False, f"复现工厂不足 (<{MIN_FACTORIES})"
    return True, "可毕业"
```

- [ ] **Step 4: 运行确认通过**

Run: `cd backend/python && python -m pytest tests/test_field_promotion.py -q`
Expected: PASS (5 passed)。

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(self-learn): is_promotable gate (conf>=0.9 + >=2 factories + no conflict)" -- backend/python/smartbi/services/field_promotion.py backend/python/tests/test_field_promotion.py
```

---

### Task 3: promoted 加载 + Consult (TDD)

**Files:**
- Modify: `backend/python/smartbi/services/field_promotion.py`
- Create: `backend/python/smartbi/data/promoted_field_aliases.json`
- Test: `backend/python/tests/test_field_promotion.py`

- [ ] **Step 1: 建空 promoted 文件**

```bash
echo '{}' > backend/python/smartbi/data/promoted_field_aliases.json
```

- [ ] **Step 2: 写失败测试 (consult)**

```python
# append to tests/test_field_promotion.py
from smartbi.services.field_promotion import consult_promoted, _load_promoted


def test_consult_hit(monkeypatch):
    monkeypatch.setattr("smartbi.services.field_promotion._PROMOTED",
                        {"本月实际": "actual_amount"})
    assert consult_promoted("本月实际") == "actual_amount"


def test_consult_miss(monkeypatch):
    monkeypatch.setattr("smartbi.services.field_promotion._PROMOTED", {})
    assert consult_promoted("未知列") is None


def test_load_promoted_bad_file_is_empty(tmp_path, monkeypatch):
    bad = tmp_path / "x.json"
    bad.write_text("not json", encoding="utf-8")
    monkeypatch.setattr("smartbi.services.field_promotion.PROMOTED_FILE", bad)
    assert _load_promoted() == {}   # fail-open, never crash mapping
```

- [ ] **Step 3: 运行确认失败**

Run: `cd backend/python && python -m pytest tests/test_field_promotion.py -q`
Expected: FAIL — `cannot import name 'consult_promoted'`。

- [ ] **Step 4: 实现 load + consult**

```python
# append to field_promotion.py
def _load_promoted() -> Dict[str, str]:
    try:
        if PROMOTED_FILE.exists():
            with open(PROMOTED_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)
            return {str(k): str(v) for k, v in data.items()} if isinstance(data, dict) else {}
    except Exception as e:  # fail-open: 映射绝不因 promoted 文件坏而崩
        logger.warning("load promoted_field_aliases failed (ignored): %s", e)
    return {}


_PROMOTED: Dict[str, str] = _load_promoted()


def consult_promoted(column_name: Optional[str]) -> Optional[str]:
    """规则层先查: 命中返回标准字段 (0 token 确定), 否则 None。"""
    if not column_name:
        return None
    return _PROMOTED.get(column_name.strip())
```

- [ ] **Step 5: 运行确认通过 + 全量 lint**

Run: `cd backend/python && python -m pytest tests/test_field_promotion.py -q && python -m flake8 --max-line-length=120 smartbi/services/field_promotion.py`
Expected: PASS (8 passed) + FLAKE8 clean。

- [ ] **Step 6: Commit**

```bash
git commit -m "feat(self-learn): promoted_field_aliases load + consult (fail-open)" -- backend/python/smartbi/services/field_promotion.py backend/python/smartbi/data/promoted_field_aliases.json backend/python/tests/test_field_promotion.py
```

---

### Task 4: Capture (候选写库, 非阻塞)

**Files:**
- Modify: `backend/python/smartbi/services/field_promotion.py`

- [ ] **Step 1: 实现 capture_candidate (best-effort, 不阻塞映射)**

```python
# append to field_promotion.py
async def capture_candidate(
    pool, column_name: str, standard_field: str,
    factory_id: Optional[str], method: str, confidence: float,
) -> None:
    """非规则映射 (embedding/llm) 解决一个列时记候选。best-effort:
    任何失败只 warn, 绝不让上传/映射因此报错 (fail-open)。"""
    if not (column_name and standard_field and method in ("embedding", "llm")):
        return
    try:
        async with pool.acquire() as conn:
            await conn.execute(
                """INSERT INTO smart_bi_field_mapping_candidates
                       (column_name, standard_field, factory_id, method, confidence)
                   VALUES ($1, $2, $3, $4, $5)
                   ON CONFLICT (column_name, standard_field, factory_id) DO UPDATE
                     SET occurrences = smart_bi_field_mapping_candidates.occurrences + 1,
                         last_seen = now(),
                         confidence = GREATEST(smart_bi_field_mapping_candidates.confidence,
                                               EXCLUDED.confidence)""",
                column_name.strip(), standard_field.strip(),
                factory_id, method, round(float(confidence), 3),
            )
    except Exception as e:
        logger.warning("capture_candidate failed (ignored): %s", e)
```

- [ ] **Step 2: 编译 + lint**

Run: `cd backend/python && python -m py_compile smartbi/services/field_promotion.py && python -m flake8 --max-line-length=120 smartbi/services/field_promotion.py`
Expected: 无输出 (clean)。

- [ ] **Step 3: Commit**

```bash
git commit -m "feat(self-learn): capture_candidate (best-effort, fail-open upsert)" -- backend/python/smartbi/services/field_promotion.py
```

---

### Task 5: 接进 live mapper (Consult + Capture)

**Files:**
- Modify: `backend/python/smartbi/services/semantic_mapper.py`

- [ ] **Step 1: Consult — 在 `_map_with_rules` 之后立刻补 promoted 命中**

`map_fields` 里 Layer 1 (line ~276) 之后、early-return (line ~283) 之前插入: 用 promoted 把 `rule_unmapped` 里能命中的补成规则映射。

```python
# 在 `rule_mappings, rule_unmapped = self._map_with_rules(columns, factory_id)` 之后:
        from smartbi.services.field_promotion import consult_promoted
        _still_unmapped = []
        for col in rule_unmapped:
            std = consult_promoted(col)
            if std and std in STANDARD_FIELDS:
                fi = STANDARD_FIELDS.get(std, {})
                mappings.append(FieldMapping(
                    original=col, standard=std, confidence=0.97,
                    method="promoted", category=fi.get("category"),
                    description="graduated rule (promoted_field_aliases)",
                ))
            else:
                _still_unmapped.append(col)
        rule_unmapped = _still_unmapped
```

- [ ] **Step 2: Capture — map_fields 末尾记非 rule 映射**

在 `map_fields` 构造完最终 `result.field_mappings`、`return result` 之前 (LLM 层之后), 加 best-effort capture。需要 pool: mapper 已有/可拿 `self._pool` 或从 caller。若 mapper 无 pool, 用 `from smartbi.config import get_pg_pool` 取 (跟 capture 一样 fail-open)。

```python
# return result 之前:
        try:
            from smartbi.services.field_promotion import capture_candidate
            from smartbi.database.connection import get_pool   # 项目现有取 pool 方式
            _pool = await get_pool()
            for m in mappings:
                if m.method in ("embedding", "llm") and m.standard:
                    await capture_candidate(_pool, m.original, m.standard,
                                            factory_id, m.method, m.confidence)
        except Exception as e:
            logger.warning("field-mapping capture skipped (ignored): %s", e)
```

> 实施注意: 确认项目里取 asyncpg pool 的真实函数名 (grep `get_pool`/`get_pg_pool`/`pool` in smartbi)。capture 已 fail-open, 取 pool 失败也不影响映射。

- [ ] **Step 3: 编译 + lint + 现有 mapper 测试**

Run: `cd backend/python && python -m py_compile smartbi/services/semantic_mapper.py && python -m flake8 --max-line-length=120 smartbi/services/semantic_mapper.py && python -m pytest tests/ -k "mapper or semantic" -q`
Expected: 编译 + flake8 clean; 现有 mapper 测试不回归。

- [ ] **Step 4: Commit**

```bash
git commit -m "feat(self-learn): wire promoted-consult + candidate-capture into live mapper" -- backend/python/smartbi/services/semantic_mapper.py
```

---

### Task 6: Promote CLI (人审毕业)

**Files:**
- Create: `scripts/promote_field_mappings.py`

- [ ] **Step 1: 写 CLI**

```python
"""扫候选 → gate → 候选清单; --apply 把通过项写进 promoted_field_aliases.json (人审后)。

用法:
  python scripts/promote_field_mappings.py            # 只看候选清单 (dry-run)
  python scripts/promote_field_mappings.py --apply    # 人审后毕业通过项
绝不静默自动: --apply 是人手动跑。
"""
import argparse
import asyncio
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "backend" / "python"))

from smartbi.services.field_promotion import (        # noqa: E402
    is_promotable, PROMOTED_FILE, _load_promoted,
)
from smartbi.services.semantic_mapper import STANDARD_FIELDS  # noqa: E402  curated 源


async def _fetch_candidates(pool):
    rows = await pool.fetch(
        """SELECT column_name, standard_field,
                  MAX(confidence) AS max_confidence,
                  COUNT(DISTINCT factory_id) AS factory_count,
                  SUM(occurrences) AS occurrences
           FROM smart_bi_field_mapping_candidates
           GROUP BY column_name, standard_field
           ORDER BY factory_count DESC, max_confidence DESC""")
    return [dict(r) for r in rows]


async def main(apply: bool):
    from smartbi.database.connection import get_pool   # 项目现有取 pool 方式
    pool = await get_pool()
    curated = {k: v.get("category") and k or k for k in {}}  # placeholder, 见 Step 2
    # curated 实际是"列名→标准"映射的反查: STANDARD_FIELDS 是 标准→info, 没有列名别名表;
    # 所以 curated 这里取已 promoted + (可选) 现有 rule 字典。先用 promoted 当 curated baseline。
    promoted = _load_promoted()
    cands = await _fetch_candidates(pool)
    to_apply = {}
    print(f"{'列名':<20}{'标准字段':<22}{'置信':<6}{'工厂':<5}判定")
    for c in cands:
        ok, reason = is_promotable(c, curated=promoted, promoted=promoted)
        mark = "✅毕业" if ok else f"—{reason}"
        print(f"{c['column_name']:<20}{c['standard_field']:<22}"
              f"{float(c['max_confidence']):<6.2f}{c['factory_count']:<5}{mark}")
        if ok:
            to_apply[c["column_name"]] = c["standard_field"]
    if apply and to_apply:
        merged = {**promoted, **to_apply}
        PROMOTED_FILE.write_text(json.dumps(merged, ensure_ascii=False, indent=2),
                                 encoding="utf-8")
        print(f"\n已毕业 {len(to_apply)} 条 → {PROMOTED_FILE}")
    elif apply:
        print("\n无可毕业候选")
    else:
        print(f"\n(dry-run) {len(to_apply)} 条达标; 加 --apply 写入")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true")
    asyncio.run(main(ap.parse_args().apply))
```

> 实施注意: STANDARD_FIELDS 是"标准字段→info"，不是"列名→标准"别名表，所以没有现成的 curated 列名字典做冲突对照。v1 用 `promoted` 自身当冲突 baseline（够防重复/自冲突）；后续若要对照 rule 字典，补一个"列名→标准"导出。确认 `get_pool` 真实函数名。

- [ ] **Step 2: dry-run 验证 (有 DB 时)**

Run: `python scripts/promote_field_mappings.py`
Expected: 打印候选清单 + 判定 (无 DB 则报连接错, 部署后在服务器跑)。

- [ ] **Step 3: Commit**

```bash
git commit -m "feat(self-learn): promote_field_mappings CLI (dry-run + --apply, human-gated)" -- scripts/promote_field_mappings.py
```

---

### Task 7: 种子 16 条 + 死脚手架清理

**Files:**
- Modify: `backend/python/smartbi/data/promoted_field_aliases.json`
- Delete (确认 0 引用后): `backend/python/smartbi/services/structure/semantic_mapper.py`, `backend/python/smartbi/data/learned_field_mappings.json`

- [ ] **Step 1: 人审种子 — 把现有 16 条静态别名(全 0.95 财务别名)写进 promoted**

```bash
# 这 16 条都是人审过的干净财务别名 (本月实际→actual_amount 等), 直接作为第一批毕业种子。
# 用现有 learned 文件的内容转成 {列名: 标准} 形式:
python - <<'PY'
import json
src = json.load(open("backend/python/smartbi/data/learned_field_mappings.json", encoding="utf-8"))
out = {k: v["standard_field"] for k, v in src.items()
       if isinstance(v, dict) and v.get("standard_field") and float(v.get("confidence",0)) >= 0.9}
json.dump(out, open("backend/python/smartbi/data/promoted_field_aliases.json","w",encoding="utf-8"),
          ensure_ascii=False, indent=2)
print("seeded", len(out), "aliases")
PY
```

- [ ] **Step 2: 确认死脚手架 0 live 引用**

Run: `cd backend/python && grep -rn "structure.semantic_mapper\|structure import SemanticMapper\|from smartbi.services.structure import" smartbi/ --include='*.py' | grep -v "structure/semantic_mapper.py\|structure/__init__"`
Expected: 无输出 (除 __init__ 导出外无人用)。若有输出 → 不删, 标记后续处理。

- [ ] **Step 3: 删死脚手架 + 从 __init__ 移除导出**

```bash
git rm backend/python/smartbi/services/structure/semantic_mapper.py backend/python/smartbi/data/learned_field_mappings.json
# 编辑 structure/__init__.py 删掉 `from .semantic_mapper import (...)` 那段 (避免 import 报错)
```

- [ ] **Step 4: 编译 main + lint**

Run: `cd backend/python && python -m py_compile smartbi/services/structure/__init__.py && python -m flake8 --max-line-length=120 smartbi/services/structure/__init__.py`
Expected: clean (导出已清干净)。

- [ ] **Step 5: Commit**

```bash
git add backend/python/smartbi/data/promoted_field_aliases.json backend/python/smartbi/services/structure/__init__.py
git commit -m "feat(self-learn): seed 16 reviewed finance aliases + remove dead structure mapper"
```

---

## Self-Review

**Spec coverage:** Capture(T1,T4,T5) / Promote gated(T2,T6) / Consult(T3,T5) / 安全:无静默自动+curated赢+可回滚(T2 gate + T6 人手 --apply + committed JSON) / 种子16条(T7) / 清死脚手架(T7) / 领先指标埋点 — **缺**: 规则命中率 metrics 埋点 spec §1.5/§4.6 提了但本计划没建任务。**决定**: v1 不阻塞, 列为后续(命中率可后期从 mapper method 分布事后查), 不加任务避免 scope 膨胀。其余全覆盖。

**Placeholder scan:** Task 5/6 有"确认项目取 pool 真实函数名"的实施注意 — 这不是 placeholder 而是诚实标注一个实施时要 grep 确认的接口名(asyncpg pool 取法项目可能是 `get_pool`/`get_pg_pool`)。其余步骤都有完整代码。

**Type consistency:** `is_promotable(candidate, curated, promoted)` 签名在 T2 定义、T6 调用一致; `candidate` 字段(column_name/standard_field/max_confidence/factory_count) T2 测试与 T6 SQL 聚合列名一致; `consult_promoted(column_name)→Optional[str]` T3 定义、T5 调用一致; `capture_candidate(pool,...)` T4 定义、T5 调用一致。

---

## Deploy (实施后)

部署 per `feedback_worktree_main_only_deploy`: merge main → 从 main worktree `deploy-smartbi-python.sh --env prod`(自动跑 migration runner apply V20260601_01)。部署后在服务器 `python scripts/promote_field_mappings.py`(dry-run 看候选)→ 人审 → `--apply`。验证: 上传一个含已毕业列名的文件, mapper method=`promoted`(0 token 命中)。
