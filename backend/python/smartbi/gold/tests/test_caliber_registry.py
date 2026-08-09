"""口径登记表 + 对账的行为约束。

🔴 2026-08-08 prod 实测的真实分叉（本文件的存在理由）：
   同一租户、同一 30 天窗口，`discount_breakdown` 报 ¥0，`discount_summary` 报
   ¥3,543,242。老板从 Java 工具面问会听到「没有折扣」，从问答链问会听到
   「让了 354 万」。

⛔ 这道闸判的不是「哪个数对」——那需要人判断。它判的是
   **「有没有两个入口在对同一个问题给不同的数」**，并且保证这件事**说得出口**：
   谁是权威、为什么、从属方为什么不能当口径来源。
"""
from datetime import date

import pytest

from smartbi.gold.restaurant import caliber_registry as C


@pytest.mark.asyncio
async def test_reconcile_flags_the_real_discount_divergence():
    """用 prod 实测的那两个数走一遍：必须判成分叉。"""
    async def call(name, pool, factory_id, date_range):
        if name == "discount_summary":
            return {"total_discount_amount": 3543242.2, "total_revenue": 78124104.8}
        return {"discounts": [], "total_amount": 0.0}

    got = await C.reconcile(None, "MOCK_REST", (date(2026, 7, 9), date(2026, 8, 7)),
                            call=call)

    assert len(got) == 1
    rec = got[0]
    assert rec.diverged is True
    assert rec.authority_value == 3543242.2
    assert rec.others["discount_breakdown"] == 0.0
    assert "不一致" in rec.render()


@pytest.mark.asyncio
async def test_agreement_is_not_flagged():
    async def call(name, pool, factory_id, date_range):
        if name == "discount_summary":
            return {"total_discount_amount": 100.0}
        return {"discounts": [{"amount": 60.0}, {"amount": 40.0}]}

    got = await C.reconcile(None, "F", (date(2026, 1, 1), date(2026, 1, 31)), call=call)
    assert got[0].diverged is False


@pytest.mark.asyncio
async def test_a_broken_side_is_divergence_not_silence():
    """⛔ 一侧跑不动 -> 值是 None -> 仍算分叉。

    方向反了这道闸就会在最需要它的时候闭嘴：一侧挂了正是最可能出现
    「两个入口两个答案」的时刻。
    """
    async def call(name, pool, factory_id, date_range):
        if name == "discount_summary":
            return {"total_discount_amount": 100.0}
        raise RuntimeError("表不存在")

    got = await C.reconcile(None, "F", (date(2026, 1, 1), date(2026, 1, 31)), call=call)
    assert got[0].diverged is True
    assert got[0].others["discount_breakdown"] is None


@pytest.mark.asyncio
async def test_no_tolerance_band():
    """⛔ 用绝对相等判，不设容差。

    有容差就会把小分叉放过去 —— 而口径分叉的危害与差额大小无关：
    差 1 块钱同样说明两处在算不同的东西。
    """
    async def call(name, pool, factory_id, date_range):
        if name == "discount_summary":
            return {"total_discount_amount": 100.00}
        return {"discounts": [{"amount": 100.01}]}

    got = await C.reconcile(None, "F", (date(2026, 1, 1), date(2026, 1, 31)), call=call)
    assert got[0].diverged is True


def test_every_caliber_states_why_the_authority_wins():
    """⛔ 每条登记必须写清「凭什么它是权威」。

    不写理由的登记表，下一个人会照着直觉改回去 —— 那正是这类事故复发的方式。
    """
    for cal in C.REGISTRY:
        assert cal.why and len(cal.why) >= 30, f"{cal.capability} 没写清权威理由"
        assert cal.authority not in cal.subordinates
        assert cal.subordinates, f"{cal.capability} 没有从属实现就不该登记"


def test_registered_queries_all_exist():
    """登记的函数名必须真的存在 —— 改名/删除后这条会红。"""
    import smartbi.gold.queries as Q

    for cal in C.REGISTRY:
        assert hasattr(Q, cal.authority), cal.authority
        for s in cal.subordinates:
            assert hasattr(Q, s), s


def test_subordinate_is_not_used_as_a_caliber_source_by_resolvers():
    """🔴 承重的一条：从属实现**不得被餐饮 resolver 直接调用**。

    它可以有别的调用方（agent 洞察层等），但问答链路上的口径只能来自权威方。
    """
    import inspect

    from smartbi.gold.restaurant import restaurant_ops_router as R

    src = inspect.getsource(R)
    offenders = [s for cal in C.REGISTRY for s in cal.subordinates if s in src]
    assert not offenders, (
        f"resolver 直接用了从属实现: {offenders} —— 口径只能来自权威方 "
        f"({[c.authority for c in C.REGISTRY]})"
    )


def test_authority_of_maps_subordinates_back():
    assert C.authority_of("discount_breakdown") == "discount_summary"
    assert C.authority_of("discount_summary") is None
    assert C.authority_of("完全没登记的函数") is None


# ─── 全局闸：确定性层不许靠置信度/相似度授权 ────────────────────────────────

def test_no_confidence_threshold_authorizes_execution():
    """🔴 扫全部餐饮模块：**没有任何地方拿置信度/相似度当授权依据**。

    Steve 定的规矩：
      · 确定性层只能「认得」，不能「猜」
      · 只有 100% 和 0%，没有中间态
      · 置信度不作授权依据 —— 相似度是连续量、不可证伪：
        「一段很长的话里只有一个字不一样(高 vs 低)，整体相似度还是很高的」，
        而那一个字正是意图的全部差别。

    ⛔ 允许存在的两类比较（它们方向相反，是**拒绝**不是**授权**）：
      1. `confidence < 阈值` -> 拒绝/反问（低置信不放行）
      2. 晋升候选的 SQL 预过滤（只加宽读取范围，不排除任何东西）

    🔴 2026-08-08 实测抓到的真实违规（已撤除）：
       `should_delegate` 里 `spec.source_tier == "vector" and spec.confidence >= 0.85`
       —— 向量高分直通执行。prod 历史 17k 条里 vector tier 有 63 条，是活的。

    ⚠️ 第一版这道闸只匹配「>= **数字**」，而 `t3_confidence >= _T3_MIN_CONFIDENCE`
       用的是**常量名** —— 闸扫不到它，**而好代码恰恰都这么写**。
       现在两种都匹配，靠下面的登记表放行合法的那几处；
       **登记必须写理由**，与 `caliber_registry` 同一个规矩。
    """
    import pathlib
    import re

    import smartbi.gold.restaurant as pkg

    #: 允许存在的「分数越高越放行」——每条必须说清**为什么它不是在猜**。
    ALLOWED = {
        "_T3_MIN_CONFIDENCE": (
            "T3 是 LLM，是设计上**唯一被允许猜的那一层**。这个阈值判的是"
            "「模型自己说它没把握」时不采信它的解析 —— 是对权威方的安全过滤，"
            "不是拿相似度替代证据。去掉它会变成「模型说不确定也照答」，更差。"
        ),
        "_T2_HIGH_CONFIDENCE": (
            "只给**提示**分档，从不授权。`_t2_vector_match` 高分返回 "
            "`(code, sim, None)`、低分返回 `(None, sim, (code, sim))`，而两个消费点"
            "都写成 `(t2_code, t2_sim) if t2_code else t2_hint` —— **两条分支拿到的"
            "是同一个 `(code, sim)` 元组**，随后一律喂给 T3。也就是说这个阈值今天"
            "对结果零影响，更不是在猜；留着是因为删它要动向量层的返回契约。"
        ),
    }

    root = pathlib.Path(pkg.__file__).parent
    # 「>= 阈值」= 分数越高越放行 = 用置信度授权。阈值可以是数字或常量名。
    pattern = re.compile(
        r"(confidence|similarity|_sim\b|score)\s*(>=|>)\s*([\w.]+)", re.I)
    offenders = []
    for f in sorted(root.glob("*.py")):
        for i, line in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
            stripped = line.strip()
            if stripped.startswith("#") or stripped.startswith("--"):
                continue          # 注释里写着「曾经有过」是可以的
            m = pattern.search(line)
            if not m:
                continue
            if m.group(3) in ALLOWED:
                continue
            offenders.append(f"{f.name}:{i}: {stripped[:78]}")
    assert not offenders, (
        "有地方拿「置信度/相似度高于阈值」当放行条件（= 用置信度授权）。"
        "确实合法的请登记进 ALLOWED 并写清为什么不是在猜:\n  "
        + "\n  ".join(offenders)
    )


def test_the_confidence_allowlist_states_why_each_entry_is_not_guessing():
    """⛔ 白名单每条都要有理由 —— 没理由的豁免下次会被随手加长。"""
    import inspect

    src = inspect.getsource(test_no_confidence_threshold_authorizes_execution)
    body = src[src.index("ALLOWED = {"):src.index("root = pathlib")]
    entries = body.count('": (')
    assert entries >= 1
    assert body.count("不是在猜") + body.count("安全过滤") >= entries, (
        "白名单里有条目没写清为什么它不是在猜"
    )
