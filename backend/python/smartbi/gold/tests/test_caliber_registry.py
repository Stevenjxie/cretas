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
