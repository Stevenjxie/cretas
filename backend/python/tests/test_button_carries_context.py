"""按钮携带自己的上下文 —— 把按钮路径上的「串」从源头消掉。

## 实测的那个串（2026-08-15）

    问句: '按日期看毛利'          ← T1 按钮产生, 自足无指代
      date_range     ('2026-07-17','2026-08-15') → ('2026-08-01','2026-08-15')  🔴
      window_label   '最近30天' → '本月'                                        🔴

**他在一屏「最近30天」的数上点了个按钮，得到「本月」。**

## 裁定（owner 2026-08-15）

  ❌ 加强 prompt 指令 —— 弱，而且已经有一句，它没拦住这次
  ❌ 改继承机制 —— 太宽，会同时改掉「罗氏虾呢」那种**正确**的继承
  ✅ **按钮携带自己的上下文** —— 按钮是我们生成的，我们知道它从哪一屏长出来

▎**按下去到的，必须是它长出来的那一屏。**
"""
from __future__ import annotations

import pytest

from smartbi.gold.restaurant import follow_up_actions as fa


_OFFERS = [{"kind": "fill_dishes", "dishes": (),
            "text": "先补这 3 道的成本卡（A、B、C）——能算进毛利的营收会从 40.2% 提到约 47.7%"}]


def _body(window_label: str) -> str:
    note = fa.drilldown_note("gross_profit", ("all", "product"),
                             "RESTAURANT_OPS_GROSS_MARGIN")
    return f"{_OFFERS[0]['text']}\n\n{note}"


def _actions(window_label: str):
    return fa.build_actions(
        metric_key="gross_profit", used_dimensions=("all", "product"),
        offers=_OFFERS, answer_text=_body(window_label),
        resolver_code="RESTAURANT_OPS_GROSS_MARGIN",
        window_label=window_label)


# ── 判据一：T1/T2 的问句都带上那一屏的窗口 ───────────────────────────────
def test_every_button_question_carries_the_originating_window():
    """🔴 每个按钮的问句都带上它长出来那一屏的时间窗。"""
    acts = _actions("最近30天")
    assert acts, "一个按钮都没有 —— 下面的断言会恒真"
    for a in acts:
        assert a["question"].startswith("最近30天"), (
            f"{a['type']} 按钮的问句没带窗口: {a['question']!r} —— "
            f"按下去会掉进 history 继承, 到的不是它长出来的那一屏")
    # 两类都覆盖到了(⛔ 不能只有 T1 带)
    assert {a["type"] for a in acts} >= {"T1", "T2"}, (
        f"只覆盖了 {sorted({a['type'] for a in acts})} —— 另一类没验到")


def test_the_window_is_not_duplicated_when_already_present():
    """⚠️ 问句里已经有窗口词就不重复加 —— 「本月毛利本月多少」读起来像坏了。"""
    assert fa.contextualize("本月按日期看毛利", "本月") == "本月按日期看毛利"
    assert fa.contextualize("按日期看毛利", "本月") == "本月按日期看毛利"


def test_an_unknown_window_is_not_invented():
    """⛔ 窗口空着就原样返回 —— 编一个默认值塞进去等于让按钮**声称**了
    一个我们并不知道的范围。
    """
    assert fa.contextualize("按日期看毛利", "") == "按日期看毛利"
    assert fa.contextualize("按日期看毛利", "   ") == "按日期看毛利"


# ── 🔴 判据二：变异 —— 拿掉携带，退回从 history 猜 ───────────────────────
def test_mutation_dropping_the_carried_window_reproduces_the_bleed():
    """🔴 拿掉携带（window_label 传空）→ 问句退回裸串，正是实测那个串的入口。

    ⚠️ 变异打在**携带这件事**上，⛔ 不是改断言的字符串。
    """
    with_ctx = _actions("最近30天")
    without_ctx = _actions("")            # ← 退回改之前的行为

    q_with = {a["type"]: a["question"] for a in with_ctx}
    q_without = {a["type"]: a["question"] for a in without_ctx}
    assert q_with != q_without, "变异没生效 —— 携带与不携带产出一样"

    # 退回之后, T1 的问句就是实测那次串的那一句
    assert q_without["T1"] == "按日期看毛利", q_without
    assert q_with["T1"] == "最近30天按日期看毛利", q_with

    # 而「带窗口的问句自己说全了」这件事可判定:
    from smartbi.gold.restaurant import context_bleed as cb
    assert cb.question_is_self_contained(q_with["T1"])
    assert cb.question_is_self_contained(q_without["T1"])
    # ⇒ 两者都自足, 区别在于**带窗口的那句把窗口说了出来**, 于是不需要继承。
    assert "最近30天" in q_with["T1"] and "最近30天" not in q_without["T1"]


# ── 🔴 判据三：阳性对照 —— 正确的继承仍然成立 ────────────────────────────
def test_positive_control_legitimate_inheritance_still_works():
    """✅ 「罗氏虾呢」那种**正确继承**不受影响。

    ⛔ 防「把继承删了」当成修好了 —— 本轮**一个字都没动继承机制**，
       只改了按钮自己产出的问句。
    """
    from smartbi.gold.restaurant import context_bleed as cb

    q = "罗氏虾呢"
    assert not cb.question_is_self_contained(q)
    # 判据本身仍然允许它继承(用最小替身, 与 test_context_bleed 同一套)
    from tests.test_context_bleed import _Spec
    from dataclasses import replace
    plain = _Spec(metrics=("food_cost",))
    inherited = replace(plain, store_scope="single", window_label="本月")
    r = cb.detect(q, inherited, plain)
    assert r["diff"], "没有继承就没有对照"
    assert not r["bleed"], f"正确的继承被判成串了: {r['bleed']}"


# ── 判据四：history 参数不全时抛错 ───────────────────────────────────────
def test_history_without_semantic_first_raises():
    """🔴 传了 history 却没传 semantic_first 时**抛错**，⛔ 不静默不继承。

    实测代价: 探针连着两轮读出 `0/7`, 而那两个 0 是「history 压根没进编译」。
    函数不报错, 于是错读数被当成结论写进了报告。
    """
    import asyncio
    import inspect

    from smartbi.gold.restaurant.restaurant_intent import (
        _parse_restaurant_query_impl,
        parse_restaurant_query,
    )

    # 🔴 2026-08-16 时间词语料接线(Task 2): `parse_restaurant_query` 现在是一层
    # 薄壳(调 `_parse_restaurant_query_impl` 再判断要不要记语料), 这条守卫的
    # 源码仍在 impl 上 —— 源码字面量检查要跟着搬, 否则它查的是薄壳的源码,
    # 而薄壳里本来就不含这句判据。**运行时行为**(下面 `pytest.raises`)不变:
    # 走的仍是公共入口 `parse_restaurant_query`, 守卫照样通过 impl 生效。
    src = inspect.getsource(_parse_restaurant_query_impl)
    assert "history and not semantic_first" in src, (
        "参数不全那条守卫不在了 —— 它会重新变成静默不继承")

    async def _go():
        await parse_restaurant_query(
            "毛利多少", None, factory_id="X",
            history=[{"q": "上个月营收", "a_summary": "..."}])

    with pytest.raises(ValueError, match="semantic_first"):
        asyncio.new_event_loop().run_until_complete(_go())

    # 阳性对照: 不传 history 时**不该**抛 —— 否则上面那条在「永远抛」时也绿。
    #  ⚠️ 只查它不是在这一步抛的; 后面因为 pool=None 抛别的与本条无关。
    async def _no_history():
        try:
            await parse_restaurant_query("毛利多少", None, factory_id="X")
        except ValueError as exc:
            assert "semantic_first" not in str(exc), (
                "没传 history 也抛了那条 —— 守卫过宽")
        except Exception:
            pass

    asyncio.new_event_loop().run_until_complete(_no_history())
