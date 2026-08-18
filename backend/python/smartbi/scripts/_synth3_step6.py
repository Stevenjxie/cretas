# -*- coding: utf-8 -*-
"""任务一：归因第⑥步「给排查方向」在 prod 上没出现 —— 三种可能各自证伪。

三种可能（⛔ 不推断，逐条给读数）：
  1. 被接地闸整行删掉了（`_keep_what_is_grounded` / `strip_ungrounded_lines`）
  2. `primary_cause` 判成了别的（不在 `_CAUSE_INVESTIGATION_DIMENSIONS` 里）
  3. 叙述缓存挡着（TTL 24h，`window_key` 含当天日期）

🔑 每条读数都带**来源标记**（形态 A¹³）：
   - `SynthesisResponse.source`（cache / semantic_cache / llm / thin_restate /
     deterministic_fallback）—— 没有它就分不清「⑥ 没加」和「这份答案根本不是
     这次生成的」
   - 喂给模型的门店行数（0/None ⇒ 答案没重新生成，本轮读数无意义）
   - `_append_investigation_directions` 有没有被调用、进出长度差

⛔ 只读。不删 prod 数据 —— 绕过缓存用 monkeypatch 让**读**失效，不动表。
"""
from __future__ import annotations

import asyncio
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__)))))

from smartbi.scripts._probe_bootstrap import bootstrap_probe  # noqa: E402

ctx = bootstrap_probe("MOCK_REST")

from smartbi.agent import factbook as fb  # noqa: E402
from smartbi.agent import synthesis_engine as se  # noqa: E402
from smartbi.agent.narrative_cache import NarrativeCacheService  # noqa: E402
from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402
from smartbi.gold.restaurant import restaurant_intent_service as svc  # noqa: E402
from smartbi.gold.restaurant import restaurant_ops_router as rr  # noqa: E402
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

FID = "MOCK_REST"
Q = "我要不要关掉最差的那家店"

_CLEARED = ("clear_semantic_plan_cache", "clear_route_cache",
            "clear_tenant_gate_cache", "clear_promoted_routes_cache")

#: 每一轮的观测收在这里。⛔ 不靠正文推断。
OBS = {}


def _reset():
    OBS.clear()
    OBS.update(
        fed_rows=None, fed_calls=0,
        synth_sources=[], synth_lens=[],
        step6_calls=0, step6_delta=[], step6_cause=None,
        step6_can=None, step6_cannot=None,
        attribution=None, avail=None, missing=None,
        findings=[], strip_calls=0, strip_kept_ratio=None,
    )


def _instrument():
    """挂观测点。⛔ 不改行为。返回 restore 闭包。"""
    orig_render = fb.FactBook._render_finance
    orig_step6 = se.ComprehensiveSynthesisEngine.__dict__[
        "_append_investigation_directions"].__func__
    orig_findings = se._grounding_findings
    orig_strip = se.strip_ungrounded_lines
    orig_synth = se.ComprehensiveSynthesisEngine.synthesize

    def render(self, lines):
        before = len(lines)
        out = orig_render(self, lines)
        added = lines[before:]
        OBS["fed_calls"] += 1
        OBS["fed_rows"] = sum(1 for ln in added if re.match(r"^\s+\d+\. ", ln))
        return out

    def step6(answer, factbook):
        OBS["step6_calls"] += 1
        att = factbook.attribution or {}
        OBS["attribution"] = {
            "no_data": att.get("no_data"),
            "primary_cause": att.get("primary_cause"),
            "laggard_store": (att.get("laggard") or {}).get("store_name"),
            "n_stores": att.get("n_stores"),
        }
        OBS["avail"] = sorted(
            "%s:%s" % (i.get("code"), i.get("status"))
            for i in (factbook.available_dimensions or []) if i.get("code"))
        OBS["missing"] = sorted(
            str(i.get("code")) for i in (factbook.missing_dimensions or [])
            if i.get("code"))
        cause = str(att.get("primary_cause") or "").strip()
        OBS["step6_cause"] = cause
        codes = se._CAUSE_INVESTIGATION_DIMENSIONS.get(cause)
        have = {str(i.get("code")) for i in (factbook.available_dimensions or [])
                if i.get("code")}
        lack = {str(i.get("code")) for i in (factbook.missing_dimensions or [])
                if i.get("code")}
        OBS["step6_can"] = [c for c in (codes or ()) if c in have]
        OBS["step6_cannot"] = [c for c in (codes or ()) if c not in have and c in lack]
        out = orig_step6(answer, factbook)
        OBS["step6_delta"].append(len(out) - len(answer))
        return out

    def findings(answer, factbook, question=""):
        out = orig_findings(answer, factbook, question)
        OBS["findings"] = [(d, c[:60]) for d, c in out]
        return out

    def strip(answer, factbook, question=""):
        cleaned, dropped = orig_strip(answer, factbook, question)
        OBS["strip_calls"] += 1
        base = len((answer or "").strip()) or 1
        OBS["strip_kept_ratio"] = round(len(cleaned) / base, 3)
        return cleaned, dropped

    async def synth(self, *a, **kw):
        res = await orig_synth(self, *a, **kw)
        OBS["synth_sources"].append(getattr(res, "source", "?"))
        OBS["synth_lens"].append(len(getattr(res, "answer", "") or ""))
        return res

    fb.FactBook._render_finance = render
    se.ComprehensiveSynthesisEngine._append_investigation_directions = staticmethod(step6)
    se._grounding_findings = findings
    se.strip_ungrounded_lines = strip
    se.ComprehensiveSynthesisEngine.synthesize = synth

    def restore():
        fb.FactBook._render_finance = orig_render
        se.ComprehensiveSynthesisEngine._append_investigation_directions = staticmethod(orig_step6)
        se._grounding_findings = orig_findings
        se.strip_ungrounded_lines = orig_strip
        se.ComprehensiveSynthesisEngine.synthesize = orig_synth

    return restore


def _clear():
    for n in _CLEARED:
        getattr(ri, n)()


async def _ask(pool, key):
    _clear()
    set_factory_id(FID)
    async with rr.dish_catalogue_scope(pool, FID):
        spec = await ri.parse_restaurant_query(
            Q, pool, factory_id=FID, session_key=key, semantic_first=True)
    res = await svc.tiered_answer(Q, pool, FID, ctx.role,
                                  precomputed_spec=spec, session_key=key)
    return (res or {})


MARKS = ("差距出在", "系统里有数", "现在查不了", "才轮到怀疑",
         "说法我删掉了", "还可补充的分析维度")


def _report(tag, text):
    print("\n【%s】 n=%d" % (tag, len(text)))
    print("   synthesize.source = %s   answer_len = %s"
          % (OBS["synth_sources"], OBS["synth_lens"]))
    print("   🔑 喂给模型的门店行 = %s（_render_finance 调用 %d 次）"
          % (OBS["fed_rows"], OBS["fed_calls"]))
    print("   第⑥步被调用 %d 次，进出长度差 = %s"
          % (OBS["step6_calls"], OBS["step6_delta"]))
    print("   attribution = %s" % (OBS["attribution"],))
    print("   ⑥ 能查 = %s   查不了 = %s" % (OBS["step6_can"], OBS["step6_cannot"]))
    print("   接地闸命中 %d 条，strip 调用 %d 次，保留比 = %s"
          % (len(OBS["findings"]), OBS["strip_calls"], OBS["strip_kept_ratio"]))
    for d, c in OBS["findings"]:
        print("      - %s ‖ 子句=%s" % (d[:70], c))
    for m in MARKS:
        print("   含「%s」= %s" % (m, m in text))


async def _dump_cache(pool):
    """③ 的直接证据：缓存里那条是**什么时候**写的、含不含⑥的标记。"""
    async with pool.acquire() as conn:
        async with conn.transaction():
            await conn.execute(
                "SELECT set_config('app.factory_id', $1, false)", FID)
            rows = await conn.fetch(
                """
                SELECT question_hash, created_at, expires_at, window_key,
                       length(answer) AS n,
                       (answer LIKE '%差距出在%')       AS has_step6,
                       (answer LIKE '%说法我删掉了%')   AS has_dropped_notice,
                       (answer LIKE '%还可补充的分析维度%') AS has_dim_list
                  FROM narrative_cache
                 WHERE factory_id = $1
                   AND expires_at > NOW()
                 ORDER BY created_at DESC
                 LIMIT 12
                """,
                FID)
    print("\n## narrative_cache 里 MOCK_REST 未过期的条目（最近 12 条）")
    print("   %-10s %-22s %6s %6s %6s %6s  %s"
          % ("hash", "created_at(UTC)", "n", "⑥", "删除告示", "维度表", "window_key"))
    for r in rows:
        print("   %-10s %-22s %6d %6s %6s %6s  %s"
              % (r["question_hash"][:10], str(r["created_at"])[:19], r["n"],
                 r["has_step6"], r["has_dropped_notice"], r["has_dim_list"],
                 r["window_key"]))
    return rows


async def main() -> int:
    pool = await ctx.pool()
    if getattr(ctx, "llm_dead_slots", None):
        print("rc=2 LLM 槽没有活账号: %s" % "、".join(ctx.llm_dead_slots))
        return 2
    print("清了: %s" % "、".join(_CLEARED))
    await _dump_cache(pool)

    restore = _instrument()
    try:
        # ── A 组：现状（叙述缓存照常）────────────────────────────────────
        _reset()
        a = await _ask(pool, "s3-a")
        a_text = a.get("answer_text") or ""
        _report("A 现状（缓存照常）", a_text)
        a_src = list(OBS["synth_sources"])
        a_step6 = OBS["step6_calls"]

        # ── B 组：绕过叙述缓存 ──────────────────────────────────────────
        orig_get = NarrativeCacheService.get
        orig_sem = getattr(NarrativeCacheService, "get_semantic", None)

        async def _miss(self, *a_, **kw):
            return None

        NarrativeCacheService.get = _miss
        if orig_sem is not None:
            NarrativeCacheService.get_semantic = _miss
        try:
            _reset()
            b = await _ask(pool, "s3-b")
            b_text = b.get("answer_text") or ""
        finally:
            NarrativeCacheService.get = orig_get
            if orig_sem is not None:
                NarrativeCacheService.get_semantic = orig_sem
        _report("B 绕过叙述缓存", b_text)
        b_rows = OBS["fed_rows"]
        b_step6 = OBS["step6_calls"]
        b_delta = list(OBS["step6_delta"])
    finally:
        restore()

    print("\n" + "=" * 92)
    # 🔴 阳性对照：B 组必须真的重新生成，否则本轮读数无意义
    if not b_rows:
        print("rc=2 B 组喂给模型的门店行 = %r ⇒ 答案没有重新生成，读数作废" % (b_rows,))
        print("     （0/None 不是 5 也不是 20 —— memory 里救过我一次的那格）")
        return 2
    print("阳性对照 ✅ B 组真的重新生成了（门店行 %s）" % b_rows)

    print("\n## 三种可能的裁定")
    print("  ③ 叙述缓存挡着: A 组 source=%s / 第⑥步调用 %d 次"
          % (a_src, a_step6))
    print("     ⇒ source 是 cache/semantic_cache 且⑥调用 0 次 ⇒ 就是它")
    print("  ② primary_cause 判成别的: B 组 cause=%r，表里的键=%s"
          % (OBS.get("step6_cause"), list(se._CAUSE_INVESTIGATION_DIMENSIONS)))
    print("  ① 被接地闸删掉: B 组⑥调用 %d 次、长度差 %s，正文含「差距出在」=%s"
          % (b_step6, b_delta, "差距出在" in b_text))
    print("     ⚠️ ⑥ 在闸**之后**才追加（synthesis_engine.py:1553 → 1582）,")
    print("        所以「长度差 > 0 而正文没有」才说明是被后面的东西删了。")
    print("\n" + "=" * 92)
    print(b_text)
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
