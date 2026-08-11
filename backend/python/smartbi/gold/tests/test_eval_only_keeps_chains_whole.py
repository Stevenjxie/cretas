"""`--only` 必须按**执行单元**选：链里任何一步命中，整条链都跑。

## 为什么这条不能只靠一道 FATAL 守着

2026-07-28 加过一道守卫：`--only` 命中链式用例就直接拒绝运行，理由是
「单跑一步会话里缺前置轮次，结果不可信」—— 那个担心是**对的**。

但当时过滤是**按单条用例**做的；后来循环改成按单元（整条链一起跑），
守卫却没跟着删。于是它挡住的是一个**本来就正确**的能力：
想验证某一条链，就只能跑全部 85 题。

⚠️ 实测代价（2026-08-11）：为验证 15 个 PR 跑了 **12 轮全量**，约 2100 次 LLM 调用、
**4.3M token**，把当天免费额度烧掉 **8 个模型**（`qwen3.8-max` 三个账号全没）。
而真实用户流量是 0 —— 烧掉额度的全是验证本身。

📌 判据：**守卫挡住正确能力时，要问它守的不变量能不能改成一条会变红的断言。**
   FATAL 只会让人绕开（或者干脆跑全量）；断言会在破坏发生时指出来。
"""
from __future__ import annotations

from smartbi.scripts.restaurant_ai_eval import CASES, build_units, select_units


def test_units_cover_every_case_exactly_once():
    """⛔ 阴性对照: 分组本身不能丢题或重复。

    没有这条, 一个把链分错组的实现也能让下面几条通过 —— 而丢掉的那几题
    **不会有任何提示**。
    """
    units = build_units(CASES)
    flat = [idx for _chain, steps in units for idx, _case in steps]
    assert sorted(flat) == list(range(1, len(CASES) + 1))


def test_only_pulls_in_the_whole_chain():
    """🔴 承重: 命中链里某一步 -> **整条链**都在选中结果里。

    这就是 2026-07-28 那道 FATAL 守卫真正想守的不变量。它现在由本条守着 ——
    区别是: 破坏它的时候这条会**变红**, 而 FATAL 只会让人跑全量绕开。
    """
    units = build_units(CASES)
    # 找一条真实的多步链, 用它**中间某一步**的问句去筛。
    chain_units = [(c, s) for c, s in units if c and len(s) >= 3]
    assert chain_units, "阴性对照: CASES 里没有多步链, 这条断言等于空转"
    chain, steps = chain_units[0]
    middle_q = steps[1][1]["q"]

    selected = select_units(units, middle_q)
    picked = {c: [i for i, _ in s] for c, s in selected}
    assert chain in picked, f"用中间一步的问句筛, 没把链 {chain} 选进来"
    assert picked[chain] == [i for i, _ in steps], (
        f"链 {chain} 被截断了: 选中 {picked[chain]}, 应为 {[i for i, _ in steps]} —— "
        f"少跑前置轮次, 通过/失败都不可信")


def test_only_actually_narrows_the_run():
    """⛔ 阴性对照: 筛完还是全量的话, 这个功能等于没有(而它存在的理由就是省额度)。"""
    units = build_units(CASES)
    selected = select_units(units, "哪个菜卖得好")
    ran = sum(len(s) for _c, s in selected)
    assert 0 < ran < len(CASES) / 4, (
        f"--only 选中 {ran}/{len(CASES)} 题 —— 没起到收窄作用")


def test_no_filter_runs_everything():
    """不传 --only 时一题不少 —— 收窄不能变成默认行为。"""
    units = build_units(CASES)
    assert sum(len(s) for _c, s in select_units(units, None)) == len(CASES)
    assert sum(len(s) for _c, s in select_units(units, "")) == len(CASES)


def test_a_miss_selects_nothing_rather_than_everything():
    """⛔ 筛不中时返回空, 不是「筛不中就全跑」—— 后者会让人以为验证过了。"""
    assert select_units(build_units(CASES), "这个问句不存在于电池里") == []
