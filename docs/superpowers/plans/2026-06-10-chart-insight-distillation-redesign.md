# Chart-Insight 蒸馏重设计 (Phase A.5 v4) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 chart-insight 在线路径从"砸掉的模板飞轮"改成 claims-pinning 活 LLM serve（结构性无幻觉）+ corpus 渐进替代桥 + 当日读回缓存，删在线模板死代码并修 U1.8 RLS。

**Architecture:** LLM 返结构化声明 `{claims,finding,implication,suggestion}`；服务端按 raw series 重算每个 claim 校验 + 数字邻接闸 → 数值幻觉/实体错位结构性消灭，智能不节流。corpus 只存通过 gate 的 accepted 输出（含 metadata），调 LLM 前按 input_hash 读回当日 corpus 当缓存。删在线 `_lookup_template`/`_capture_template`/`_maybe_promote`，revert U1.8 跨租户 UNIQUE 回 factory-scoped（与 RLS 一致）。

**Tech Stack:** Python 3.8 + asyncpg + FastAPI（backend/python/smartbi）；pytest；Vue3（web-admin ChartInsight.vue）。

**Spec:** `docs/superpowers/specs/2026-06-10-chart-insight-distillation-redesign.md`（v4，完整设计 + 红线 + RBAC + corpus 安全）。

**Red lines (🔒 Opus gate before merge/deploy):** C1（no-fake-data + RBAC）、C2（corpus 跨租户安全）、C3（revert U1.8 RLS + 删码不破坏）、prod 部署。

---

## File Structure

| File | 责任 | 改动 |
|---|---|---|
| `backend/python/smartbi/services/insights/chart_insight_service.py` | Tier2 服务 | C1 claims-pinning（新 `_recompute_claim`/`_validate_claims` + 改 prompt/`_call_llm`/`get_insight`）；C2 corpus 读回+persist；C3 删模板死码+改 docstring；C5 budget 计失败 |
| `backend/python/smartbi/services/insights/claim_recompute.py` | **新** — 纯函数：从 series 重算 claim 真值 | C1 |
| `backend/python/smartbi/tests/test_chart_insight_service.py` | 单测 | 全任务 |
| `backend/python/smartbi/tests/test_claim_recompute.py` | **新** — claim 重算单测 | C1 |
| `backend/python/smartbi/database/migrations/V20260929_01__revert_chart_insight_cross_factory.sql` | **新** 迁移 | C3 |
| `web-admin/src/views/smart-bi/components/ChartInsight.vue` | 徽章 | C4 |

---

## Task 1: claim 重算纯函数（C1 核心，无 LLM 依赖，先建可测地基）

**Files:**
- Create: `backend/python/smartbi/services/insights/claim_recompute.py`
- Test: `backend/python/smartbi/tests/test_claim_recompute.py`

**契约**：`recompute_claim(entity, stat_type, series_values, series_labels) -> Optional[float]` 返回该 (entity, stat_type) 从 raw series 重算的真值；entity 不在 labels 或 stat_type 未知 → None。`stat_type ∈ {value, share, top2_share, complement, ratio, diff, growth, count}`。

- [ ] **Step 1: 写失败测试**

```python
# test_claim_recompute.py
import pytest
from smartbi.services.insights.claim_recompute import recompute_claim

SERIES = [62000.0, 38000.0]
LABELS = ["堂食", "外卖"]

def test_share():  # 堂食占比 = 62000/100000 = 62.0
    assert recompute_claim("堂食", "share", SERIES, LABELS) == pytest.approx(62.0, abs=0.05)

def test_complement():  # 外卖补集相对堂食 = 38.0 (外卖自身 share)；complement of 堂食 = 38.0
    assert recompute_claim("堂食", "complement", SERIES, LABELS) == pytest.approx(38.0, abs=0.05)

def test_ratio():  # 堂食/外卖 = 1.63
    assert recompute_claim("堂食", "ratio", SERIES, LABELS) == pytest.approx(1.63, abs=0.05)

def test_value():  # 堂食绝对值
    assert recompute_claim("堂食", "value", SERIES, LABELS) == pytest.approx(62000.0, abs=1)

def test_top2_share():  # 前二合计 (本例只有2项) = 100.0
    assert recompute_claim("堂食", "top2_share", [50.0, 30.0, 20.0], ["A","B","C"]) == pytest.approx(80.0, abs=0.05)

def test_diff():  # 堂食-外卖 = 24000
    assert recompute_claim("堂食", "diff", SERIES, LABELS) == pytest.approx(24000.0, abs=1)

def test_growth():  # 时序首末增长 (堂食视为最后一期对首期? growth 用 series 首末)
    assert recompute_claim(None, "growth", [100.0, 130.0], ["1月","2月"]) == pytest.approx(30.0, abs=0.05)

def test_count():  # 项数
    assert recompute_claim(None, "count", SERIES, LABELS) == 2.0

def test_unknown_entity_returns_none():
    assert recompute_claim("不存在", "share", SERIES, LABELS) is None

def test_unknown_stat_returns_none():
    assert recompute_claim("堂食", "bogus", SERIES, LABELS) is None

def test_empty_series_returns_none():
    assert recompute_claim("堂食", "share", [], []) is None
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend/python && python -m pytest smartbi/tests/test_claim_recompute.py -q`
Expected: FAIL（module 不存在）

- [ ] **Step 3: 实现 `claim_recompute.py`**

实现 `recompute_claim`：按 stat_type 分支从 series 重算（share=entity值/total*100；complement=100-entity_share；ratio=entity值/min值；value=entity值；top2_share=前二大值合计/total*100；diff=entity值-min值；growth=(last-first)/abs(first)*100，entity 可为 None；count=len）。entity 用 labels.index 定位，缺失返 None；total≤0 / 空 series 返 None。纯函数，无副作用。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd backend/python && python -m pytest smartbi/tests/test_claim_recompute.py -q`
Expected: PASS（11 passed）

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(chart-insight): C1.1 claim 重算纯函数(从 series 算 share/ratio/diff/growth 等)" -- backend/python/smartbi/services/insights/claim_recompute.py backend/python/smartbi/tests/test_claim_recompute.py
```

---

## Task 2: claims 校验 + 数字邻接闸（C1，结构杀幻觉）

**Files:**
- Modify: `backend/python/smartbi/services/insights/chart_insight_service.py`（新增 `_validate_claims`）
- Test: `backend/python/smartbi/tests/test_chart_insight_service.py`（新增 `TestValidateClaims`）

**契约**：`_validate_claims(llm_obj, ctx, tolerance_pct=1.0, tolerance_ratio=0.3) -> Optional[dict]`：
- 对 `llm_obj["claims"]` 每个 `{entity, stat_type, value}`，`recompute_claim(...)` 重算真值；|claim.value - 真值| 超容差 → 该 claim **invalid**。
- **数字邻接闸**：扫 finding/implication/suggestion 散文里每个阿拉伯数字（正则 `\d+\.?\d*`），该数字必 ≈ 某 **valid** claim 的 value（容差内），且散文中该数字最近的 entity 提及必等于该 claim 的 entity。任一数字不满足 → 整体 **reject（返 None）**。
- 全通过 → 返 `{finding, implication, suggestion}`（prose）。

- [ ] **Step 1: 写失败测试**

```python
# 追加到 test_chart_insight_service.py
from smartbi.services.insights.chart_insight_service import _validate_claims, ChartInsightContext

def _ctx(values, labels, domain="restaurant"):
    return ChartInsightContext(chart_type="PIE", x_dim="channel", y_metric="revenue",
        aggregation="sum", domain=domain, data_pattern="proportion:top-share:50-65:n2-3",
        permission_tier="finance_visible", factory_id="R_SSW_DEMO",
        series_values=values, series_labels=labels)

class TestValidateClaims:
    def test_valid_claim_passes(self):
        obj = {"claims":[{"entity":"堂食","stat_type":"share","value":62.0}],
               "finding":"堂食占62.0%","implication":None,"suggestion":None}
        out = _validate_claims(obj, _ctx([62000.0,38000.0],["堂食","外卖"]))
        assert out is not None and out["finding"] == "堂食占62.0%"

    def test_entity_swap_rejected(self):  # 真相: 堂食=62%, LLM 说"外卖占62%"
        obj = {"claims":[{"entity":"外卖","stat_type":"share","value":62.0}],
               "finding":"外卖占62.0%","implication":None,"suggestion":None}
        # 外卖真实 share=38 ≠ claim 62 → claim invalid → 邻接闸: 62 无 valid claim → reject
        assert _validate_claims(obj, _ctx([62000.0,38000.0],["堂食","外卖"])) is None

    def test_derived_stat_not_false_rejected(self):  # 前二合计 80% 是派生 stat, 必须通过
        obj = {"claims":[{"entity":"A","stat_type":"top2_share","value":80.0}],
               "finding":"前二名合计占80.0%","implication":None,"suggestion":None}
        assert _validate_claims(obj, _ctx([50.0,30.0,20.0],["A","B","C"])) is not None

    def test_invented_number_in_prose_rejected(self):  # 散文含 claim 外的数字
        obj = {"claims":[{"entity":"堂食","stat_type":"share","value":62.0}],
               "finding":"堂食占62.0%，受天气影响下降15%","implication":None,"suggestion":None}
        # 15 不对应任何 valid claim → reject
        assert _validate_claims(obj, _ctx([62000.0,38000.0],["堂食","外卖"])) is None

    def test_no_claims_returns_none(self):
        assert _validate_claims({"claims":[],"finding":"x"}, _ctx([1.0,2.0],["a","b"])) is None
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend/python && python -m pytest smartbi/tests/test_chart_insight_service.py::TestValidateClaims -q`
Expected: FAIL（`_validate_claims` 不存在）

- [ ] **Step 3: 实现 `_validate_claims`**

在 chart_insight_service.py 实现：import `recompute_claim`；对每个 claim 重算+容差比对得 valid_claims；正则抽散文数字；每个数字找 valid_claims 里 value 最近的；不在容差→reject；用字符距离找散文中该数字最近的 entity 子串，须等于该 claim.entity（entity 为 None 的 stat 如 growth/count 跳过邻接 entity 检查，只验数值）；全过返 prose dict，否则 None。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd backend/python && python -m pytest smartbi/tests/test_chart_insight_service.py::TestValidateClaims -q`
Expected: PASS（5 passed）

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(chart-insight): C1.2 _validate_claims 重算校验+数字邻接闸(结构杀实体错位幻觉)" -- backend/python/smartbi/services/insights/chart_insight_service.py backend/python/smartbi/tests/test_chart_insight_service.py
```

---

## Task 3: prompt 改结构化声明契约 + finance_hidden stat 白名单（C1, 🔒RBAC）

**Files:**
- Modify: `backend/python/smartbi/services/insights/chart_insight_service.py`（`_build_insight_prompt`、新 `_stats_for_tier`）
- Test: `test_chart_insight_service.py`（`TestPromptAndTierStats`）

- [ ] **Step 1: 写失败测试**

```python
from smartbi.services.insights.chart_insight_service import _build_insight_prompt, _stats_for_tier

class TestPromptAndTierStats:
    def test_finance_hidden_excludes_absolute(self):  # MF5: 排除 changeAmt + raw
        stats = _stats_for_tier(_compute_slot_values(_ctx([62000.0,38000.0],["堂食","外卖"])), "finance_hidden")
        assert "changeAmt" not in stats
        # 相对 stats 保留
        assert "topShare" in stats or "ratio" in stats

    def test_finance_visible_keeps_absolute(self):
        slots = _compute_slot_values(_ctx([100.0,200.0],["1月","2月"]))
        stats = _stats_for_tier(slots, "finance_visible")
        if "changeAmt" in slots:
            assert "changeAmt" in stats

    def test_prompt_asks_for_structured_claims(self):
        p = _build_insight_prompt(_ctx([62000.0,38000.0],["堂食","外卖"]), "finance_hidden")
        assert "claims" in p and "stat_type" in p
        # finance_hidden prompt 不含 raw series 绝对值
        assert "62000" not in p
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend/python && python -m pytest smartbi/tests/test_chart_insight_service.py::TestPromptAndTierStats -q`
Expected: FAIL（`_stats_for_tier` 不存在 / prompt 旧版）

- [ ] **Step 3: 实现**

`_stats_for_tier(slots, permission_tier)`：finance_hidden → 只返相对白名单键 `{topName,botName,topShare,ratio,growthRate,concLevel}`（去 changeAmt）；finance_visible → 全 slots。改 `_build_insight_prompt(ctx, permission_tier)`：喂 `_stats_for_tier` 的 stats（不喂 raw series_values 给 finance_hidden）；指示 LLM 返 JSON `{claims:[{entity,stat_type,value}],finding,implication,suggestion}`，stat_type 枚举，claim.value 用上面给的 stats，finding 散文里数字必来自 claims。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd backend/python && python -m pytest smartbi/tests/test_chart_insight_service.py::TestPromptAndTierStats -q`
Expected: PASS（3 passed）

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(chart-insight): C1.3 prompt 改结构化声明契约 + finance_hidden 排 changeAmt/raw(MF5)" -- backend/python/smartbi/services/insights/chart_insight_service.py backend/python/smartbi/tests/test_chart_insight_service.py
```

---

## Task 4: get_insight 串起 claims-pinning + ¥ serve-gate + 删模板死码（C1+C3, 🔒）

**Files:**
- Modify: `backend/python/smartbi/services/insights/chart_insight_service.py`（`get_insight`、`_call_llm`；删 `_lookup_template`/`_capture_template`/`_maybe_promote`/`validate_template_parameterization`/`_safe_fill`/`_fill_slots`/slot-prompt；改 module docstring 1-25）
- Test: `test_chart_insight_service.py`（`TestGetInsightClaimsPinning`，monkeypatch `_call_llm`）

- [ ] **Step 1: 写失败测试**

```python
class TestGetInsightClaimsPinning:
    @pytest.mark.asyncio
    async def test_valid_llm_served(self, monkeypatch):
        svc = _make_service_with_fake_pool()  # 见 existing test helpers
        async def fake_llm(self, ctx, permission_tier):
            return {"claims":[{"entity":"堂食","stat_type":"share","value":62.0}],
                    "finding":"堂食占62.0%","implication":None,"suggestion":None}
        monkeypatch.setattr(ChartInsightService, "_call_llm", fake_llm)
        r = await svc.get_insight(_ctx([62000.0,38000.0],["堂食","外卖"]),
                                  caller_role="factory_super_admin", jwt_factory_id="R_SSW_DEMO")
        assert r is not None and r.source == "llm" and "62" in r.finding

    @pytest.mark.asyncio
    async def test_finance_hidden_yuan_rejected(self, monkeypatch):
        async def fake_llm(self, ctx, permission_tier):
            return {"claims":[{"entity":"堂食","stat_type":"value","value":62000.0}],
                    "finding":"堂食营收¥62000","implication":None,"suggestion":None}
        monkeypatch.setattr(ChartInsightService, "_call_llm", fake_llm)
        svc = _make_service_with_fake_pool()
        r = await svc.get_insight(_ctx([62000.0,38000.0],["堂食","外卖"]),
                                  caller_role="operator", jwt_factory_id="R_SSW_DEMO")
        assert r is None or "¥" not in (r.finding or "")  # serve-gate 拒¥

    @pytest.mark.asyncio
    async def test_entity_swap_llm_falls_to_none(self, monkeypatch):
        async def fake_llm(self, ctx, permission_tier):
            return {"claims":[{"entity":"外卖","stat_type":"share","value":62.0}],
                    "finding":"外卖占62.0%","implication":None,"suggestion":None}
        monkeypatch.setattr(ChartInsightService, "_call_llm", fake_llm)
        svc = _make_service_with_fake_pool()
        r = await svc.get_insight(_ctx([62000.0,38000.0],["堂食","外卖"]),
                                  caller_role="factory_super_admin", jwt_factory_id="R_SSW_DEMO")
        assert r is None  # 邻接闸拒 → 不 serve 幻觉
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend/python && python -m pytest smartbi/tests/test_chart_insight_service.py::TestGetInsightClaimsPinning -q`
Expected: FAIL

- [ ] **Step 3: 实现 + 删死码**

改 `get_insight`：保留 cross-tenant guard + budget fail-closed；**删** Tier2a `_lookup_template` 调用 + `_capture_template`/`_maybe_promote` 调用。新流程：budget check → `_call_llm`(返结构化 obj) → `_validate_claims` → None 则返 None（落 Tier1 由前端） → 通过则对 finance_hidden 跑 `_ABSOLUTE_AMOUNT_RE` 三字段 ¥ serve-gate（命中→返 None）→ 返 `InsightResult(finding,implication,suggestion,source="llm",tier=2)`。`_call_llm` 签名加 permission_tier，response_format json，解析用既有 `_extract_json_object`。**物理删除** `_lookup_template`/`_capture_template`/`_maybe_promote`/`validate_template_parameterization`/`_safe_fill`/`_fill_slots` 及旧 slot-白名单 prompt 文本；**改 module docstring(1-25)** 描述 v4 claims-pinning 架构（删 Tier2a/distillation 旧描述）。保留 `_compute_slot_values`/poison/`_ABSOLUTE_AMOUNT_RE`。

- [ ] **Step 4: 跑测试确认通过 + 全文件回归**

Run: `cd backend/python && python -m pytest smartbi/tests/test_chart_insight_service.py -q`
Expected: PASS（含既有未删测试；删码相关的旧测试同步删除）

- [ ] **Step 5: 确认无死码残留**

Run: `grep -nE "_lookup_template|_capture_template|_maybe_promote|_safe_fill" backend/python/smartbi/services/insights/chart_insight_service.py`
Expected: 无匹配（全删）

- [ ] **Step 6: Commit**

```bash
git commit -m "feat(chart-insight): C1+C3 get_insight claims-pinning serve + ¥闸 + 物理删在线模板死码+改docstring(MF6)" -- backend/python/smartbi/services/insights/chart_insight_service.py backend/python/smartbi/tests/test_chart_insight_service.py
```

---

## Task 5: corpus 读回当日缓存 + gated accepted-only persist（C2, 🔒corpus 安全）

**Files:**
- Modify: `backend/python/smartbi/services/insights/chart_insight_service.py`（`get_insight` 加读回 + persist）
- Test: `test_chart_insight_service.py`（`TestCorpus`，monkeypatch persist + pool）

- [ ] **Step 1: 写失败测试**

```python
class TestCorpus:
    @pytest.mark.asyncio
    async def test_persist_called_after_gate_with_correct_signature(self, monkeypatch):
        calls = []
        async def fake_persist(pool, *, source, task_type, input_text, teacher_output, **kw):
            calls.append({"source":source,"task_type":task_type,"business_type":kw.get("business_type"),
                          "teacher_model":kw.get("teacher_model")})
        monkeypatch.setattr("smartbi.services.insights.chart_insight_service.persist_distillation_sample", fake_persist)
        async def fake_llm(self, ctx, permission_tier):
            return {"claims":[{"entity":"堂食","stat_type":"share","value":62.0}],"finding":"堂食占62.0%","implication":None,"suggestion":None}
        monkeypatch.setattr(ChartInsightService, "_call_llm", fake_llm)
        svc = _make_service_with_fake_pool()
        await svc.get_insight(_ctx([62000.0,38000.0],["堂食","外卖"]), caller_role="factory_super_admin", jwt_factory_id="R_SSW_DEMO")
        assert len(calls) == 1
        assert calls[0]["source"] == "chart_insight" and calls[0]["task_type"] == "insights"
        assert calls[0]["business_type"] in ("restaurant","factory")  # finance→factory 映射
        assert calls[0]["teacher_model"]  # MF2 必传

    @pytest.mark.asyncio
    async def test_rejected_output_not_persisted(self, monkeypatch):  # MF2 gate 后才存
        calls = []
        async def fake_persist(pool, **kw): calls.append(kw)
        monkeypatch.setattr("smartbi.services.insights.chart_insight_service.persist_distillation_sample", fake_persist)
        async def fake_llm(self, ctx, permission_tier):  # entity-swap → 被 reject
            return {"claims":[{"entity":"外卖","stat_type":"share","value":62.0}],"finding":"外卖占62.0%","implication":None,"suggestion":None}
        monkeypatch.setattr(ChartInsightService, "_call_llm", fake_llm)
        svc = _make_service_with_fake_pool()
        await svc.get_insight(_ctx([62000.0,38000.0],["堂食","外卖"]), caller_role="factory_super_admin", jwt_factory_id="R_SSW_DEMO")
        assert len(calls) == 0  # 被拒不进 corpus
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend/python && python -m pytest smartbi/tests/test_chart_insight_service.py::TestCorpus -q`
Expected: FAIL

- [ ] **Step 3: 实现**

`get_insight`：import `persist_distillation_sample`（from smartbi.services.distillation_capture）。**读回**：调 LLM 前算 `input_hash`（与 corpus 一致的 input_text），查当日 `smart_bi_distillation_samples` 行（`WHERE input_hash=$1 AND created_at::date=CURRENT_DATE`，且 SET app.factory_id GUC 或显式 factory_id 过滤），命中→重过 ¥ serve-gate→返 teacher_output（source="cache"）。**persist**（仅 `_validate_claims`+¥闸通过的 accepted 输出，**gate 后**）：`await persist_distillation_sample(self._pool, source="chart_insight", task_type="insights", input_text=<数据丰富 stats 上下文>, teacher_output=json.dumps(accepted), business_type=_map_domain(ctx.domain), factory_id=ctx.factory_id, system_prompt=..., teacher_model=<from resp/env>, metadata={"permission_tier":..., "stats":..., "gate":"passed"})`。`_map_domain`: finance→factory, restaurant→restaurant, 其他→unknown。fire-and-forget 不阻塞返回。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd backend/python && python -m pytest smartbi/tests/test_chart_insight_service.py::TestCorpus -q`
Expected: PASS（2 passed）

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(chart-insight): C2 corpus gated accepted-only persist(正确签名+metadata) + 当日input_hash读回缓存(MF2/MF4)" -- backend/python/smartbi/services/insights/chart_insight_service.py backend/python/smartbi/tests/test_chart_insight_service.py
```

---

## Task 6: revert U1.8 跨租户迁移（C3, 🔒迁移 — Opus 终审 + row-count precheck）

**Files:**
- Create: `backend/python/smartbi/database/migrations/V20260929_01__revert_chart_insight_cross_factory.sql`

- [ ] **Step 1: 核迁移号 + row count（实现前必做）**

Run: `git ls-tree origin/main backend/python/smartbi/database/migrations/ | grep -oE 'V[0-9]{8}_[0-9]{2}' | sort | tail -3`
Expected: 最高是 `V20260928_01` → `V20260929_01` 合法。
Run（核 prod row count，Opus 在 gate 时执行）: 通过 ssh psql `SELECT count(*) FROM ai_insight_templates;` → 应近 0；若有跨 factory dup signature_hash，迁移需先 dedup（见 Step 2 注释）。

- [ ] **Step 2: 写迁移 SQL**

```sql
-- V20260929_01__revert_chart_insight_cross_factory.sql
-- Revert U1.8 (V20260928_01): cross-tenant uk_ait_sig(signature_hash) was dead-on-arrival
-- because V20260927_01 keeps FORCE RLS policy factory_id=current_setting('app.factory_id').
-- v4 moves templates fully offline (M4); online no longer reads/writes this table.
-- Restore factory-scoped UNIQUE so the unique key is consistent with the RLS policy.
BEGIN;
-- Precheck assumption: table is ~empty online (online writes removed in C1/C3).
-- If duplicate signature_hash across factories exist, keep highest proposal_count per (sig,factory).
DELETE FROM ai_insight_templates a USING (
    SELECT id FROM (
        SELECT id, ROW_NUMBER() OVER (PARTITION BY signature_hash, factory_id ORDER BY proposal_count DESC, id) rn
        FROM ai_insight_templates
    ) t WHERE rn > 1
) d WHERE a.id = d.id;
ALTER TABLE ai_insight_templates DROP CONSTRAINT IF EXISTS uk_ait_sig;
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='ai_insight_templates'::regclass AND conname='uk_ait_sig_factory') THEN
        ALTER TABLE ai_insight_templates ADD CONSTRAINT uk_ait_sig_factory UNIQUE (signature_hash, factory_id);
    END IF;
END;
$$;
COMMIT;
```

- [ ] **Step 3: Commit**

```bash
git commit -m "feat(chart-insight): C3 revert U1.8 跨租户 UNIQUE 回 factory-scoped(与 RLS 一致)" -- backend/python/smartbi/database/migrations/V20260929_01__revert_chart_insight_cross_factory.sql
```

> 🔒 部署时 Opus 终审：先 ssh 核 row count + 唯一键现状，再走 `deploy-smartbi-python.sh`（runner apply 迁移）。

---

## Task 7: budget 计 LLM 失败（C5, 小修）

**Files:**
- Modify: `backend/python/smartbi/services/insights/chart_insight_service.py`（`get_insight` LLM 失败/parse 失败路径）

- [ ] **Step 1: 写失败测试**

```python
class TestBudgetOnFailure:
    @pytest.mark.asyncio
    async def test_consume_called_on_llm_failure(self, monkeypatch):
        consumed = []
        svc = _make_service_with_fake_budget(on_consume=lambda fid,n: consumed.append(n))
        async def fake_llm(self, ctx, permission_tier): return None  # LLM 失败
        monkeypatch.setattr(ChartInsightService, "_call_llm", fake_llm)
        await svc.get_insight(_ctx([1.0,2.0],["a","b"]), caller_role="factory_super_admin", jwt_factory_id="R_SSW_DEMO")
        assert sum(consumed) > 0  # 失败也计 token
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend/python && python -m pytest smartbi/tests/test_chart_insight_service.py::TestBudgetOnFailure -q`
Expected: FAIL

- [ ] **Step 3: 实现**

`get_insight`：`_call_llm` 返 None（失败/parse 失败）路径也 `await self._budget_tracker.consume(factory_id, <估算 token>)` 再返 None。

- [ ] **Step 4: 跑测试确认通过 + 全套**

Run: `cd backend/python && python -m pytest smartbi/tests/test_chart_insight_service.py smartbi/tests/test_claim_recompute.py -q`
Expected: PASS（全绿）

- [ ] **Step 5: Commit**

```bash
git commit -m "fix(chart-insight): C5 LLM 失败也计 budget token(防低估)" -- backend/python/smartbi/services/insights/chart_insight_service.py backend/python/smartbi/tests/test_chart_insight_service.py
```

---

## Task 8: 前端 source 徽章（C4，小）

**Files:**
- Modify: `web-admin/src/views/smart-bi/components/ChartInsight.vue`

- [ ] **Step 1: 改徽章映射**

`ChartInsight.vue` 徽章 computed：`source==='llm'`→"AI生成"；`'rules'`→"数据驱动"；`'cache'`→"数据驱动·已缓存"；其余/null→不显。loading 同现。

- [ ] **Step 2: build 验证**

Run: `cd web-admin && npx vue-tsc --noEmit -p tsconfig.json 2>&1 | grep -iE "ChartInsight|error TS" || echo "tsc clean"`
Expected: tsc clean

- [ ] **Step 3: Commit**

```bash
git commit -m "feat(chart-insight): C4 source 徽章(llm/rules/cache)" -- web-admin/src/views/smart-bi/components/ChartInsight.vue
```

---

## 验收（全任务后，Opus 终审 + headed real-path）
- pytest 全绿（claim_recompute + chart_insight_service）。
- 🔒 Opus gate：C1 no-fake-data（实体错位/臆造数字拒）+ RBAC（finance_hidden 无 changeAmt/raw+¥闸）；C2 corpus 跨租户安全（gated/正确签名/business_type 桶）；C3 revert U1.8 RLS 一致（row-count precheck）。
- 部署（Opus 从 main）：`deploy-smartbi-python.sh --env prod`（runner apply V20260929_01）+ web-admin。
- headed real-path：驾驶舱 Tier1 不回归；Tier1-null 走 claims-pinning（数值服务端重算真，无实体错位）；finance_hidden 角色无¥泄露；corpus 表实证多行（accepted-only，数据丰富）；当日重渲染读回 source=cache 0 LLM。

---

## Self-Review
- **Spec 覆盖**：C1(Task1-4)/C2(Task5)/C3(Task4 删码+Task6 迁移)/C4(Task8)/C5(Task7)/MF1(Task1-2)/MF2(Task5)/MF4(Task5 读回)/MF5(Task3)/MF6(Task4)。MF3(trigger 指标)是 spec 文档约束非代码任务（M3/M4 未实现，指标写在 spec §5），无代码任务——✓ 符合（trigger 在未来替换时用）。
- **类型一致**：`recompute_claim`/`_validate_claims`/`_stats_for_tier`/`_map_domain` 跨任务一致。`InsightResult(finding,implication,suggestion,source,tier)` 沿用既有。
- **占位扫描**：测试均含具体断言；实现步骤给算法+签名（TDD 由测试驱动），无 TBD。
