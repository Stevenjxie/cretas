"""FactBook — deterministic metric dictionary for P2 综合分析 (multi-dim synthesis).

The synthesis layer's "fact底座". Every number the LLM / InsightDimensionAnalyzer
sees originates here, computed by gold/P1 SQL aggregations — the LLM never sees
raw rows and never computes, so it cannot fabricate a store name or a number.

Composition (filled by ``synthesis_engine._build_factbook`` per the dimension plan):
  - ``review``  : P1 review_summary / review_vip / review_platform /
                  review_time_period / review_good_tags / review_dish_issues /
                  review_store_ranking (差评门店) etc.
  - ``finance`` : gold finance_summary (total_revenue / bills / avg_bill / top_stores)
  - ``sales``   : gold top_products / channel_breakdown / discount_breakdown
  - ``cross_hints`` : pre-computed cross-relations (VIP×星级, 时段×营收 ...) — these
                  are DESCRIPTIVE relationships only (相关 ≠ 因果).
  - ``notes``   : honest labels (小样本 / 标签语义 / 空数据 next-action).

Honesty铁律 (per fool-proof-design + relabel lessons, spec §2.1 + §5.6):
  - 菜品标签 = 口味/品质标签 (味道好/鲜嫩/太软了), NOT dish names.
  - 差评 (≤3星) 是小样本 (~2%); 任何"差评高频"结论必带样本量.
  - VIP 评分反而更低 (4.50 < 4.83) 是真实信号; 因果归因需标"推测".
  - 投诉类型 = 商家申诉口径 (小样本), 不等于客诉总量.

``to_prompt_lines()`` renders这些 into readable prompt text (mirroring
``orchestrator._build_user_prompt`` style — NOT a JSON dump). ``to_facts_index()``
flattens the numbers into a {fact_name: value} map for FactReconciler to reconcile
the LLM's output against. ``factbook_to_dataframe()``摊平 numeric facts into a 1-row
DataFrame so InsightDimensionAnalyzer can run its descriptive/correlation passes.

This module uses ``float()`` directly (per python-java-port: this template is NOT
byte-strict; dict-eq is sufficient — no Java parity contract).
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

try:  # pandas only needed for factbook_to_dataframe; keep import soft for unit tests.
    import pandas as pd  # type: ignore
except Exception:  # pragma: no cover
    pd = None  # type: ignore


# Honest-label note strings reused across renders + asserted in tests.
NOTE_DISH_TAG_NOT_NAME = "菜品标签实为口味/品质标签 (味道好/鲜嫩/太软了)，不代表菜名销量。"
NOTE_LOW_STAR_SMALL_SAMPLE = "差评(≤3星)为小样本，'差评高频'结论需带样本量谨慎解读。"
NOTE_VIP_SIGNAL = "VIP 评分低于非VIP 是真实数据信号；因果归因(如'期望更高')需标注为推测。"
NOTE_COMPLAINT_DISPUTE = "投诉类型为商家申诉口径，样本小，不等于客诉总量。"
NOTE_REVIEW_ABSENT_NEXTACTION = (
    "评价数据暂未接入，当前分析基于经营数据。上传大众点评/美团评价导出后可补充口碑维度。"
)


def _money(v: Any) -> str:
    """Render an amount with thousands separator + 2 decimals (mirror orchestrator._fmt_money)."""
    if v is None:
        return "0.00"
    try:
        return f"{float(v):,.2f}"
    except (TypeError, ValueError):
        return str(v)


def _num(v: Any) -> Optional[float]:
    if v is None:
        return None
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


@dataclass
class FactBook:
    """Deterministic metric汇总. LLM/analyzer read here, never self-compute."""

    review: Optional[Dict[str, Any]] = None
    finance: Optional[Dict[str, Any]] = None
    sales: Optional[Dict[str, Any]] = None
    # Per-store 客流(bills)×客单价(avg_ticket) 拖后腿归因 — laggard + effect
    # decomposition. Populated when the plan flags `attribution` (store-lag /
    # traffic-vs-ticket question). See ``compute_store_attribution``.
    attribution: Optional[Dict[str, Any]] = None
    cross_hints: List[Dict[str, Any]] = field(default_factory=list)
    notes: List[str] = field(default_factory=list)
    # Echoed window for the prompt header (set by the engine).
    period: Optional[str] = None

    # -----------------------------------------------------------------
    # Prompt rendering
    # -----------------------------------------------------------------
    def to_prompt_lines(self) -> List[str]:
        """Render FactBook → readable prompt lines (honest labels inline).

        Mirrors orchestrator._build_user_prompt: pretty text, not JSON dumps.
        金额口径用自然语言说明 (不用方括号标记, 客户反馈方括号像 JSON 不可读, #5).
        Only the sections present (per plan) are rendered; absent sections are
        replaced by a next-action note (fool-proof Rule 5, no dead-end).
        """
        lines: List[str] = []
        if self.period:
            lines.append(f"数据范围：{self.period}")
            lines.append("")

        self._render_review(lines)
        self._render_finance(lines)
        self._render_attribution(lines)
        self._render_sales(lines)
        self._render_cross(lines)

        if self.notes:
            lines.append("")
            lines.append("## 诚实标注（必须在回答中遵守）")
            for n in self.notes:
                lines.append(f"- {n}")
        return lines

    def to_prompt_text(self) -> str:
        return "\n".join(self.to_prompt_lines())

    def _render_review(self, lines: List[str]) -> None:
        rv = self.review
        if not rv:
            return
        summary = rv.get("summary") or {}
        total = summary.get("total_reviews")
        if total:
            lines.append(f"## 评价（共 {int(total):,} 条去重评价，按 评价ID DISTINCT）")
            avg_star = summary.get("avg_star")
            avg_service = summary.get("avg_service")
            avg_env = summary.get("avg_env")
            avg_taste = summary.get("avg_taste")
            lines.append(
                f"- 平均星级 {avg_star} / 服务 {avg_service} / 环境 {avg_env} / 口味 {avg_taste}"
            )
            high = summary.get("high_star_count")
            low = summary.get("low_star_count")
            if high is not None and low is not None:
                lines.append(
                    f"- 好评(≥4.5星) {int(high):,} 条；差评(≤3星) {int(low):,} 条"
                    f" [小样本，结论需谨慎]"
                )
        # VIP vs 非VIP
        vip = rv.get("vip") or {}
        for g in (vip.get("groups") or []):
            grp = g.get("group")
            n = g.get("review_count")
            star = g.get("avg_star")
            if grp and n is not None:
                lines.append(f"- {grp} 评价 {int(n):,} 条（平均 {star} 星）")
        # Platform
        plat = rv.get("platform") or {}
        plats = plat.get("platforms") or []
        if plats:
            seg = "；".join(
                f"{p.get('platform')} {int(p.get('review_count') or 0):,}({p.get('avg_star')}星)"
                for p in plats[:5]
            )
            lines.append(f"- 平台分布：{seg}")
        # Good tags (口味标签非菜名)
        good = rv.get("good_tags") or {}
        gtags = good.get("tags") or []
        if gtags:
            seg = " / ".join(f"{t.get('tag')} {int(t.get('count') or 0):,}" for t in gtags[:5])
            lines.append(f"- 高频好评词 [口味/品质标签，非菜名]：{seg}")
        # Low-star dish tags (口味标签非菜名, 小样本)
        issues = rv.get("dish_issues") or {}
        itags = issues.get("tags") or []
        if itags:
            seg = " / ".join(f"{t.get('tag')} {int(t.get('count') or 0):,}" for t in itags[:5])
            low_n = issues.get("low_star_count")
            sample = f"（基于 {int(low_n):,} 条差评小样本）" if low_n else ""
            lines.append(f"- 高频差评词 [口味/品质标签，非菜名]{sample}：{seg}")
        # Worst stores (差评最多门店, 小样本)
        worst = rv.get("worst_stores") or {}
        wstores = worst.get("stores") or []
        if wstores:
            top = wstores[0]
            lines.append(
                f"- 差评最多门店：{top.get('store')}（差评 {int(top.get('low_star_count') or 0):,} 条，"
                f"该店共 {int(top.get('review_count') or 0):,} 条评价）"
            )
        lines.append("")

    def _render_finance(self, lines: List[str]) -> None:
        fin = self.finance
        if not fin:
            # honest next-action only if review/sales carried the analysis
            return
        lines.append(f"## 经营（gold agg_daily，{fin.get('start_date')}~{fin.get('end_date')}）")
        revenue = fin.get("total_revenue")
        bills = fin.get("bill_count") or 0
        avg_bill = fin.get("avg_bill_value")
        store_count = fin.get("store_count") or 0
        day_count = fin.get("day_count") or 0
        lines.append(f"- 营业日 {int(day_count):,} 天，门店 {int(store_count):,} 家")
        lines.append(f"- 总营业额（应收口径）¥{_money(revenue)}；订单数 {int(bills):,}")
        if fin.get("net_profit") is not None:
            lines.append(
                f"- 总成本 ¥{_money(fin.get('total_cost'))}"
                f"（食材¥{_money(fin.get('material_cost'))}/人工¥{_money(fin.get('labor_cost'))}"
                f"/其他¥{_money(fin.get('overhead_cost'))}）；"
                f"净利润 ¥{_money(fin.get('net_profit'))}，净利率 {fin.get('net_margin_pct')}%"
            )
        if avg_bill is not None:
            lines.append(f"- 客单价 ¥{_money(avg_bill)}")
        stores = fin.get("top_stores") or []
        if stores:
            lines.append("- Top 门店（按营业额）：")
            for i, s in enumerate(stores[:5], 1):
                name = s.get("store_name") or s.get("store_id") or "未知门店"
                rev = s.get("revenue")
                bc = s.get("bill_count") or 0
                lines.append(f"  {i}. {name}：¥{_money(rev)}（{int(bc):,} 单）")
        lines.append("")

    def _render_attribution(self, lines: List[str]) -> None:
        att = self.attribution
        if not att or att.get("no_data"):
            return
        lg = att.get("laggard") or {}
        lines.append("## 门店拖后腿归因（营收=客流×客单价，按店拆解）")
        lines.append(
            f"- 全链基准：门店平均客流 {int(att.get('bench_bills') or 0):,} 单，"
            f"全链客单价 ¥{_money(att.get('chain_avg_ticket'))}，"
            f"门店平均营收 ¥{_money(att.get('bench_revenue'))}"
        )
        lines.append(
            f"- 拖后腿门店：{lg.get('store_name')}，营收 ¥{_money(lg.get('revenue'))}"
            f"（较门店平均少 ¥{_money(abs(lg.get('delta_revenue') or 0))}）"
        )
        lines.append(
            f"- 客流效应 ¥{_money(lg.get('traffic_effect'))}"
            f"（该店 {int(lg.get('bills') or 0):,} 单 vs 平均 {int(att.get('bench_bills') or 0):,} 单）"
        )
        lines.append(
            f"- 客单价效应 ¥{_money(lg.get('ticket_effect'))}"
            f"（该店客单价 ¥{_money(lg.get('avg_ticket'))} vs 全链 ¥{_money(att.get('chain_avg_ticket'))}）"
        )
        lines.append(f"- 主因判定（确定性）：{att.get('primary_cause')}")
        anomalies = att.get("anomalies") or []
        if anomalies:
            lines.append(
                f"- 注：{('、'.join(anomalies))} 客流极低（可能新店/歇业），未计入门店对比。"
            )
        lines.append("")

    def _render_sales(self, lines: List[str]) -> None:
        sales = self.sales
        if not sales:
            return
        prods = sales.get("top_products") or []
        channels = sales.get("channels") or []
        discounts = sales.get("discounts") or []
        if not (prods or channels or discounts):
            return
        lines.append("## 销售")
        if prods:
            lines.append("- Top 商品（按营业额）：")
            for i, p in enumerate(prods[:5], 1):
                name = p.get("product_name") or p.get("name") or "未知商品"
                rev = p.get("revenue")
                qty = p.get("qty_sold") or p.get("quantity") or 0
                lines.append(f"  {i}. {name}：¥{_money(rev)}（销量 {int(qty):,}）")
        if channels:
            seg = "；".join(
                f"{c.get('channel_name')} ¥{_money(c.get('amount'))}（{c.get('share_pct')}%）"
                for c in channels[:6]
            )
            lines.append(f"- 渠道占比（按营业额）：{seg}")
        if discounts:
            seg = "；".join(
                f"{d.get('discount_name')} ¥{_money(d.get('amount'))}"
                for d in discounts[:6]
            )
            lines.append(f"- 折扣结构：{seg}")
        lines.append("")

    def _render_cross(self, lines: List[str]) -> None:
        if not self.cross_hints:
            return
        lines.append("## 交叉关系（数据关系，相关≠因果）")
        for h in self.cross_hints:
            desc = h.get("description")
            if desc:
                lines.append(f"- {desc}")
        lines.append("")

    # -----------------------------------------------------------------
    # Flat fact index (for FactReconciler)
    # -----------------------------------------------------------------
    def to_facts_index(self) -> Dict[str, float]:
        """Flatten到 {中文指标名: value} for reconciliation.

        Names match what the LLM is most likely to write back ('平均星级',
        '总营业额', '客单价', VIP/非VIP 'VIP平均星级' ...). FactReconciler does
        EXACT name-substring + 紧跟数字 matching only —宁漏不错.
        """
        idx: Dict[str, float] = {}
        rv = self.review or {}
        summary = rv.get("summary") or {}
        for label, key in (
            ("平均星级", "avg_star"),
            ("平均服务分", "avg_service"),
            ("平均环境分", "avg_env"),
            ("平均口味分", "avg_taste"),
        ):
            v = _num(summary.get(key))
            if v is not None:
                idx[label] = v
        for label, key in (("好评", "high_star_count"), ("差评", "low_star_count"),
                           ("评价总数", "total_reviews"), ("VIP数", "vip_count")):
            v = _num(summary.get(key))
            if v is not None:
                idx[label] = v
        # VIP / 非VIP avg star
        for g in ((rv.get("vip") or {}).get("groups") or []):
            grp = g.get("group")
            v = _num(g.get("avg_star"))
            if grp and v is not None:
                idx[f"{grp}平均星级"] = v

        fin = self.finance or {}
        for label, key in (("总营业额", "total_revenue"), ("订单数", "bill_count"),
                           ("客单价", "avg_bill_value"), ("门店数", "store_count"),
                           ("总成本", "total_cost"), ("净利润", "net_profit"),
                           ("净利率", "net_margin_pct"), ("食材成本", "material_cost")):
            v = _num(fin.get(key))
            if v is not None:
                idx[label] = v

        att = self.attribution or {}
        if att and not att.get("no_data"):
            lg = att.get("laggard") or {}
            for label, val in (
                ("门店平均客流", att.get("bench_bills")),
                ("全链客单价", att.get("chain_avg_ticket")),
                ("门店平均营收", att.get("bench_revenue")),
                ("拖后腿门店营收", lg.get("revenue")),
                ("拖后腿门店客单价", lg.get("avg_ticket")),
                ("客流效应", lg.get("traffic_effect")),
                ("客单价效应", lg.get("ticket_effect")),
            ):
                v = _num(val)
                if v is not None:
                    idx[label] = v
        return idx

    # -----------------------------------------------------------------
    # Entity names (for FactReconciler fabricated-name detection + redaction)
    # -----------------------------------------------------------------
    def known_store_names(self) -> List[str]:
        """All store names present in the FactBook (top_stores + worst_stores)."""
        names: List[str] = []
        fin = self.finance or {}
        for s in (fin.get("top_stores") or []):
            n = s.get("store_name")
            if isinstance(n, str) and n.strip():
                names.append(n.strip())
        rv = self.review or {}
        for s in ((rv.get("worst_stores") or {}).get("stores") or []):
            n = s.get("store")
            if isinstance(n, str) and n.strip():
                names.append(n.strip())
        # Attribution stores (laggard + full per-store list) — so the reconciler's
        # fabricated-name check and the egress redactor both know these names.
        att = self.attribution or {}
        for s in (att.get("stores") or []):
            n = s.get("store_name")
            if isinstance(n, str) and n.strip():
                names.append(n.strip())
        # Anomaly (low-traffic, excluded) stores are still printed in the render
        # note ("注：{names} 客流极低…"), so register them too — otherwise the
        # reconciler's fabricated-name check false-flags a real store the LLM
        # merely echoed when explaining the exclusion.
        for n in (att.get("anomalies") or []):
            if isinstance(n, str) and n.strip():
                names.append(n.strip())
        # dedup preserve order
        seen = set()
        out = []
        for n in names:
            if n not in seen:
                seen.add(n)
                out.append(n)
        return out

    def known_tags(self) -> List[str]:
        """口味/品质标签 strings present (good_tags + dish_issues)."""
        tags: List[str] = []
        rv = self.review or {}
        for bucket in ("good_tags", "dish_issues"):
            for t in ((rv.get(bucket) or {}).get("tags") or []):
                tag = t.get("tag")
                if isinstance(tag, str) and tag.strip():
                    tags.append(tag.strip())
        return tags

    def collect_sensitive_names(self) -> Dict[str, List[str]]:
        """Pull real entity names for the egress redactor (P0 数据主权).

        Returns {类型前缀: [值...]} matching llm_redactor.register_values:
          门店 → store names; 菜品 → top product names; 标签 NOT redacted (口味词
          like 味道好/鲜嫩 are generic quality words, not identity — registering
          them would over-redact analysis. Only PROPER names go).
        """
        out: Dict[str, List[str]] = {}
        stores = self.known_store_names()
        if stores:
            out["门店"] = stores
        sales = self.sales or {}
        prods = []
        for p in (sales.get("top_products") or []):
            n = p.get("product_name") or p.get("name")
            if isinstance(n, str) and n.strip():
                prods.append(n.strip())
        if prods:
            out["菜品"] = prods
        return out


def factbook_to_dataframe(fb: FactBook) -> "pd.DataFrame":
    """Flatten numeric facts into a 1-row DataFrame for InsightDimensionAnalyzer.

    The analyzer's descriptive/correlation passes operate on numeric columns;
    a single row is enough for it to surface KPI snapshots + (with cross_hints
    carrying numeric pairs) relationships. Non-numeric facts are excluded.
    """
    if pd is None:  # pragma: no cover
        raise RuntimeError("pandas not available")
    row = dict(fb.to_facts_index())
    # Append numeric cross-hint pairs so correlation has ≥2 columns to relate.
    for h in fb.cross_hints:
        for k, v in (h.get("values") or {}).items():
            num = _num(v)
            if num is not None:
                row[str(k)] = num
    if not row:
        return pd.DataFrame()
    return pd.DataFrame([row])


def compute_store_attribution(
    store_rows: List[Dict[str, Any]], *, traffic_floor_frac: float = 0.10
) -> Optional[Dict[str, Any]]:
    """Deterministic 客流×客单价 拖后腿 attribution over ALL stores.

    ``store_rows`` = ``gold.store_comparison`` output items, each with
    ``{name, revenue, orderCount, avgTicket, ...}``. Decomposes each store's
    revenue gap vs the chain per-store benchmark into a traffic (客流) effect
    and an average-ticket (客单价) effect — an exact identity, no LLM:

        营收 = 客流 × 客单价
        基准 B̄ = 总客流 / 门店数 ,  Ā = 总营收 / 总客流 ,  R̄ = B̄·Ā
        ΔR_i        = 营收_i − R̄
        客流效应_i   = (客流_i − B̄) × Ā
        客单价效应_i = B̄ × (客单价_i − Ā)      （交互项并入差额，不单列）

    Laggard = most-negative ΔR among stores above a traffic floor (a
    shuttered/near-zero store must not masquerade as "拖后腿"). Returns None
    when there are fewer than 2 stores with bills (nothing to compare).
    """
    rows = [
        s for s in (store_rows or [])
        if _num(s.get("orderCount")) and int(s.get("orderCount") or 0) > 0
        and _num(s.get("revenue")) is not None
    ]
    if len(rows) < 2:
        return {"no_data": True}

    # Anomaly floor: stores whose traffic is a tiny fraction of the median are
    # likely new / shuttered / partially-loaded and would distort a peer
    # benchmark. They are reported separately, NOT compared.
    median_bills = sorted(int(s["orderCount"]) for s in rows)[len(rows) // 2]
    floor = median_bills * traffic_floor_frac
    anomaly_rows = [s for s in rows if int(s["orderCount"]) < floor]
    eligible_rows = [s for s in rows if int(s["orderCount"]) >= floor]
    # Need ≥2 comparable peers; if the floor left too few, compare everyone.
    if len(eligible_rows) < 2:
        eligible_rows, anomaly_rows = rows, []

    # Benchmark is computed over the SAME eligible set the laggard is chosen
    # from — otherwise excluded low-traffic stores drag the mean down and every
    # eligible store looks above-average (a false "no laggard" / positive ΔR).
    total_bills = sum(int(s["orderCount"]) for s in eligible_rows)
    total_rev = sum(float(s["revenue"]) for s in eligible_rows)
    n = len(eligible_rows)
    if total_bills <= 0:
        return {"no_data": True}

    bench_bills = total_bills / n          # B̄  门店平均客流
    chain_ticket = total_rev / total_bills  # Ā  全链客单价
    bench_rev = bench_bills * chain_ticket  # R̄  门店平均营收

    stores: List[Dict[str, Any]] = []
    for s in eligible_rows:
        b = int(s["orderCount"])
        rev = float(s["revenue"])
        ticket = rev / b if b else 0.0
        stores.append({
            "store_name": s.get("name"),
            "bills": b,
            "revenue": round(rev, 0),
            "avg_ticket": round(ticket, 1),
            "delta_revenue": round(rev - bench_rev, 0),
            "traffic_effect": round((b - bench_bills) * chain_ticket, 0),
            "ticket_effect": round(bench_bills * (ticket - chain_ticket), 0),
            "below_traffic_floor": False,
        })

    laggard = min(stores, key=lambda s: s["delta_revenue"])
    tr, tk = laggard["traffic_effect"], laggard["ticket_effect"]
    if tr < 0 and tr <= tk:
        primary = "客流"
    elif tk < 0 and tk < tr:
        primary = "客单价"
    else:
        primary = "客流" if tr <= tk else "客单价"

    return {
        "no_data": False,
        "n_stores": n,
        "total_bills": total_bills,
        "total_revenue": round(total_rev, 0),
        "bench_bills": round(bench_bills, 0),
        "chain_avg_ticket": round(chain_ticket, 1),
        "bench_revenue": round(bench_rev, 0),
        "laggard": laggard,
        "primary_cause": primary,
        "anomalies": [s.get("name") for s in anomaly_rows if s.get("name")],
        "stores": stores,
    }
