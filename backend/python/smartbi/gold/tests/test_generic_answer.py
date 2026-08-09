"""通用执行器接线层 —— 承重的是「不动现有 20 个 resolver」。

⛔ 这条路径是**并行**的：只在没有手写 resolver 时才走。
   一旦它开始接管已有的码，现有任何一条能答的问法都可能悄悄换了口径。
"""
import datetime
from dataclasses import dataclass, field
from typing import Sequence, Tuple

import pytest

from smartbi.gold.restaurant.generic_answer import render, spec_to_cell
from smartbi.gold.restaurant.generic_executor import CellResult


@dataclass
class _Spec:
    """⚠️ 字段与默认值**逐字对齐** `RestaurantQuerySpec` —— 夹具与真实规格
    不一致时，测试测的是夹具不是系统（今天已经被这一点咬过一次）。"""
    requested_metrics: Sequence[str] = field(default_factory=tuple)
    dimensions: Sequence[str] = field(default_factory=tuple)
    #: 真实取值只有 lookup | compare | diagnose | optimize
    analysis_action: str = "lookup"
    #: 真实取值只有 best | worst | None —— **排名由它决定，不由 analysis_action**
    ranking_direction: object = None
    ranking_limit: int = 5
    #: 批 1 新增。取值域 = 登记表的 AGGREGATIONS; None = 规划器没表态
    aggregation: object = None
    date_range: Tuple[datetime.date, datetime.date] = (
        datetime.date(2026, 8, 1), datetime.date(2026, 8, 9))
    window_label: str = "本月"


def test_the_target_case_translates_to_the_right_cell():
    """「客单价最高的店」—— 今天真实答错的那条, 必须翻译到正确的格子。"""
    spec = _Spec(requested_metrics=("orders",), dimensions=("store",),
                 ranking_direction="best")
    assert spec_to_cell(spec) == ("orders", "store", "rank")

    spec2 = _Spec(requested_metrics=("gross_margin",), dimensions=("dish",),
                  ranking_direction="best")
    assert spec_to_cell(spec2) == ("gross_margin", "product", "rank")


def test_ranking_comes_from_the_slot_the_planner_actually_fills():
    """🔴 承重: 排名由 `ranking_direction` 决定, ⛔ 不是 `analysis_action`。

    第一版按 analysis_action 里的 "rank"/"top" 判 —— 而那个槽**只有**
    lookup|compare|diagnose|optimize 四个值, "rank" 规划器一次都不会产出。
    后果不是报错而是**静默退化成汇总**: 问「哪家店最高」得到「全部门店合计
    ¥2,872 万」—— 答的不是那个问题, 但它看起来像个正经答案。
    """
    # 规划器不会产出的值, 不该被当成排名信号
    fake = _Spec(requested_metrics=("revenue",), dimensions=("store",),
                 analysis_action="rank")
    assert spec_to_cell(fake) == ("revenue", "store", "summary")
    # 真正的排名信号
    best = _Spec(requested_metrics=("revenue",), dimensions=("store",),
                 ranking_direction="best")
    assert spec_to_cell(best) == ("revenue", "store", "rank")
    # 方向反过来是**另一个形态**, 不是同一个
    worst = _Spec(requested_metrics=("revenue",), dimensions=("store",),
                  ranking_direction="worst")
    assert spec_to_cell(worst) == ("revenue", "store", "bottom")


def test_compare_and_time_dimension_pick_their_own_shapes():
    cmp_ = _Spec(requested_metrics=("revenue",), dimensions=("channel",),
                 analysis_action="compare")
    assert spec_to_cell(cmp_) == ("revenue", "channel", "compare")
    # 按时间分组而没说排名 = 走势, ⛔ 不是排行榜
    trend = _Spec(requested_metrics=("revenue",), dimensions=("time",))
    assert spec_to_cell(trend) == ("revenue", "date", "trend")


def test_user_stated_limit_wins_over_the_registered_default():
    """问「前 10」就给 10 条。⛔ 用登记的默认 5 会答非所问, 而且看起来像答对了。"""
    from smartbi.gold.restaurant.generic_answer import spec_limit
    from smartbi.gold.restaurant.generic_executor import build_sql
    assert spec_limit(_Spec(ranking_limit=10)) == 10
    sql, _r, _b = build_sql("revenue", "store", "rank", limit_override=10)
    assert "LIMIT 10" in sql
    # ⛔ 但不许给本来没有 limit 的形态硬加 —— 那会悄悄截断结果
    sql2, _r2, _b2 = build_sql("revenue", "store", "compare", limit_override=10)
    assert "LIMIT" not in sql2, "给「对比」加了 limit —— 会静默截断"


def test_non_query_specs_return_none_so_the_original_path_continues():
    """🔴 承重: 翻译不出来返回 None 是**正常出口**, 不是失败。

    预测/建议/归因都会走到这里, 它们必须继续走原路径 ——
    把 None 当成「答不出来」会让这条并行路径吃掉本该由别人答的问题。
    """
    assert spec_to_cell(_Spec()) is None                                   # 没指标
    assert spec_to_cell(_Spec(requested_metrics=("staffing",))) is None    # 未登记的指标
    assert spec_to_cell(_Spec(requested_metrics=("net_profit",))) is None  # 数据缺口


def test_rank_without_a_dimension_falls_back_to_summary_not_refusal():
    """用户问「哪个最高」却没说按什么分 —— 给个总数比什么都不给强。"""
    spec = _Spec(requested_metrics=("revenue",), analysis_action="rank")
    assert spec_to_cell(spec) == ("revenue", "all", "summary")


def test_missing_columns_are_told_truthfully_never_zero():
    """🔴 承重: 缺列的措辞必须说「没接入」, ⛔ 不许出现一个 0。

    「你的平台抽佣是 ¥0」比「这项数据你还没接入」危险得多。
    """
    r = CellResult("revenue", "营收", "store", "rank", "money", [],
                   ("fact_pos_transaction.net_amount",), "")
    text = render(r, "本月")
    assert "还没有接入" in text
    assert "¥0" not in text and "0.00" not in text
    assert "没有用其他数据替代" in text


def test_empty_result_is_distinguished_from_missing_columns():
    """「查出来是空的」和「缺数据」是两件事, 措辞不能混。"""
    r = CellResult("revenue", "营收", "store", "rank", "money", [], (), "")
    text = render(r, "本月")
    assert "没有可用的" in text
    assert "还没有接入" not in text, "把「空结果」说成了「没接入」"


def test_rank_narration_marks_the_top_row():
    r = CellResult("revenue", "营收", "store", "rank", "money",
                   [{"dim_label": "A店", "revenue": 100},
                    {"dim_label": "B店", "revenue": 90}], (), "")
    text = render(r, "本月")
    assert "**A店**" in text, "排行第一没有加重"
    assert "¥100.00" in text and "¥90.00" in text


def test_narration_is_template_never_a_model_call():
    """⛔ 叙述层不许调模型 —— 那会把「数字不经模型」从后门破掉。"""
    import io
    import pathlib

    src = io.open(pathlib.Path(__file__).resolve().parents[1]
                  / "restaurant" / "generic_answer.py", encoding="utf-8").read()
    for banned in ("call_chain", "SLOT.", "llm_router"):
        assert banned not in src, f"叙述层引入了模型调用: {banned}"


def test_dispatch_only_falls_through_when_no_handwritten_resolver():
    """🔴 承重: 通用路径挂在 `resolver is None` 之后 ——
    现有 20 个格子一条都不该被它接管。"""
    import io
    import pathlib

    src = io.open(pathlib.Path(__file__).resolve().parents[1]
                  / "restaurant" / "restaurant_ops_router.py", encoding="utf-8").read()
    idx_guard = src.find("resolver = _RESOLVERS.get(code)")
    idx_generic = src.find("try_generic_answer")
    assert idx_guard != -1 and idx_generic != -1
    assert idx_guard < idx_generic, "通用路径跑到了手写 resolver 之前 —— 会接管已有格子"
    between = src[idx_guard:idx_generic]
    assert "if resolver is None:" in between, (
        "通用路径没有被 `resolver is None` 守住")


# ═══════════════════════════════════════════════════════════════════════════
# 扩表后新增的聚合形态在叙述层的措辞
# ═══════════════════════════════════════════════════════════════════════════
def _cell(agg, rows, key="revenue", label="营收", dim="store", unit="money"):
    return CellResult(key, label, dim, agg, unit, rows, (), "")


def test_null_group_label_is_told_not_shown_as_none():
    """🔴 承重: 分组值为 NULL 时说「未填写」, ⛔ 不是字符串 "None"。

    实测 MOCK_REST 的 `table_no` 整列为 NULL。渲染成 "None" 会让用户看到
    一个叫 None 的台位 —— 那不是缺数据的诚实说法, 是一个假实体。
    """
    text = render(_cell("compare", [{"dim_key": None, "dim_label": None,
                                     "revenue": 100}], dim="table"), "本月")
    assert "未填写" in text
    assert "None" not in text


def test_zero_is_a_real_group_value_not_a_missing_one():
    """⚠️ 用 `or` 判空会把 0 / 空串当成缺失 —— 它们是**合法的分组值**。

    🔴 这条测试第一版用 `dim_key=0, dim_label=0`，变异(`or`)**没红**：
       两个都是 0 时 `or` 退到 dim_key 也还是 0，两种写法结果相同。
       判别力来自**两侧不同**的输入 —— dim_label 是 0、dim_key 是别的东西时，
       `or` 会跳过正确的展示名去用内部 key。
    """
    text = render(_cell("compare", [{"dim_key": "T01", "dim_label": 0,
                                     "revenue": 100}], dim="table"), "本月")
    assert "未填写" not in text, "0 被当成缺失了"
    assert "T01" not in text, "0 被当成空值, 跳过展示名去用了内部 key"
    assert "0 ¥100.00" in text


def test_trend_does_not_bold_the_first_row():
    """趋势按时间排; 加重第一项会把「1 号」读成「最高的那天」。"""
    rows = [{"dim_key": "2026-08-01", "dim_label": "08-01", "revenue": 10},
            {"dim_key": "2026-08-02", "dim_label": "08-02", "revenue": 99}]
    text = render(_cell("trend", rows, dim="date"), "本月")
    assert "**" not in text, "趋势里出现了加重 —— 会被读成排行榜"
    assert text.index("08-01") < text.index("08-02"), "时间顺序被打乱"


def test_share_shows_both_the_amount_and_the_percentage():
    rows = [{"dim_key": "a", "dim_label": "外卖", "revenue": 30, "share": 30.0},
            {"dim_key": "b", "dim_label": "堂食", "revenue": 70, "share": 70.0}]
    text = render(_cell("share", rows, dim="channel"), "本月")
    assert "¥30.00" in text and "30.0%" in text
    assert "¥70.00" in text and "70.0%" in text


def test_concentration_states_how_many_items_carry_the_bulk():
    rows = [{"dim_key": "a", "dim_label": "罗氏虾", "revenue": 50,
             "share": 50.0, "cum_share": 50.0},
            {"dim_key": "b", "dim_label": "鲈鱼", "revenue": 30,
             "share": 30.0, "cum_share": 80.0}]
    text = render(_cell("concentration", rows, dim="product"), "本月")
    assert "2 个" in text, "没说清是几个撑起来的"
    assert "80.0%" in text


def test_extremes_names_both_ends():
    rows = [{"dim_key": "a", "dim_label": "静安店", "revenue": 100},
            {"dim_key": "b", "dim_label": "宝山店", "revenue": 10}]
    text = render(_cell("extremes", rows), "本月")
    assert "静安店" in text and "宝山店" in text
    assert "**静安店**" in text, "最高的那个没加重"


def test_above_average_shows_the_threshold_it_used():
    """阈值要说出来 —— 用户看不到线画在哪就无法判断这个筛选合不合理。"""
    rows = [{"dim_key": "a", "dim_label": "静安店", "revenue": 100,
             "_threshold": 50.0}]
    text = render(_cell("above_avg", rows), "本月")
    assert "¥50.00" in text, "没告诉用户平均线是多少"
    assert "1 个" in text


def test_bottom_is_worded_as_last_not_as_top():
    """「倒数」和「排行」的措辞不能一样 —— 用户会把最差读成最好。"""
    rows = [{"dim_key": "a", "dim_label": "米饭", "revenue": 3}]
    top = render(_cell("rank", rows, dim="product"), "本月")
    bot = render(_cell("bottom", rows, dim="product"), "本月")
    assert top != bot, "排行与倒数措辞相同"
    assert "倒数" in bot


# ═══════════════════════════════════════════════════════════════════════════
# 批 1 · 聚合槽 —— 「prompt 的可选值只能从登记表渲染」
# ═══════════════════════════════════════════════════════════════════════════
def test_prompt_renders_every_registered_aggregation():
    """🔴 承重(整个「根治」方案就靠这一条): 规划器 prompt 里的聚合可选值
    **必须**覆盖登记表里的每一个, ⛔ 不许手写第二份清单。

    2026-08-09 实测: 可选值手写在 prompt 里的后果是 —— 执行侧登记了 3168 个
    格子, 规划器穷举所有输出只能到达 147 个(4%)。登记表的 96% 是死的,
    而**任何现有的闸都不会因此变红**(单测绿、prod 全量实跑通过、电池 80/85)。

    登记表加一行而 prompt 没跟上 → 这条红。这是防止病灶换个位置复发的唯一机制。
    """
    from smartbi.gold.restaurant.metric_registry import AGGREGATIONS
    from smartbi.gold.restaurant.restaurant_intent import _build_t3_prompt

    prompt = _build_t3_prompt("本月营收多少", None, None, ("模拟·静安嘉里中心店",), None)
    for key, agg in AGGREGATIONS.items():
        assert key in prompt, (
            f"聚合「{key}」登记了但没进 prompt —— 规划器指不到它, "
            f"这个格子是死的")
        assert agg.asks in prompt, (
            f"聚合「{key}」的用法说明没进 prompt —— 规划器不知道什么时候选它")


def test_planner_stated_aggregation_wins_over_inference():
    """规划器直接说了形态就用它 —— 占比/集中度/两端/高于平均**推不出来**，
    只能由它指定。⛔ 别给这四种编推断规则, 那是猜。"""
    for agg in ("share", "concentration", "extremes", "above_avg"):
        spec = _Spec(requested_metrics=("revenue",), dimensions=("store",),
                     aggregation=agg)
        assert spec_to_cell(spec) == ("revenue", "store", agg), (
            f"规划器说了要 {agg}, 却被推断规则盖掉了")


def test_stated_aggregation_still_falls_back_when_dimension_missing():
    """说了要排行却没给分组对象 —— 退回汇总, ⛔ 不拒绝也不硬排。"""
    spec = _Spec(requested_metrics=("revenue",), aggregation="share")
    assert spec_to_cell(spec) == ("revenue", "all", "summary")


def test_unregistered_aggregation_is_ignored_not_crashed():
    """模型编了个不存在的形态 —— 忽略它走旧规则, ⛔ 不炸也不当真。"""
    spec = _Spec(requested_metrics=("revenue",), dimensions=("store",),
                 ranking_direction="best", aggregation="模型编的形态")
    assert spec_to_cell(spec) == ("revenue", "store", "rank")


def test_aggregation_slot_is_purely_additive():
    """🔴 承重: 规划器**不填**这个槽时, 行为与加槽之前逐字相同。

    这是这一批敢上 prod 的全部依据 —— 老模型/缓存的旧计划都不带这个槽。
    """
    cases = [
        (_Spec(requested_metrics=("revenue",), dimensions=("store",),
               ranking_direction="best"), ("revenue", "store", "rank")),
        (_Spec(requested_metrics=("revenue",), dimensions=("store",),
               ranking_direction="worst"), ("revenue", "store", "bottom")),
        (_Spec(requested_metrics=("revenue",), dimensions=("channel",),
               analysis_action="compare"), ("revenue", "channel", "compare")),
        (_Spec(requested_metrics=("revenue",), dimensions=("time",)),
         ("revenue", "date", "trend")),
        (_Spec(requested_metrics=("revenue",)), ("revenue", "all", "summary")),
    ]
    for spec, expected in cases:
        assert getattr(spec, "aggregation", None) is None
        assert spec_to_cell(spec) == expected, f"未填槽时行为变了: {spec}"
