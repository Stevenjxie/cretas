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


# ═══════════════════════════════════════════════════════════════════════════
# 批 2 · 维度 —— 同一条纪律: prompt 的可选值只能从登记表渲染
# ═══════════════════════════════════════════════════════════════════════════
def test_prompt_renders_every_registered_dimension():
    """🔴 承重: 登记表里的每个维度都必须进 prompt, ⛔ 不许手写第二份清单。

    2026-08-09 实测: 手写的 6 个维度对 16 个已登记维度 —— 员工/餐段/星期/
    时段/台位/城市/品牌/菜品类别/损耗类型/损耗原因 这 10 个**规划器永远指不到**。
    """
    from smartbi.gold.restaurant.metric_registry import DIMENSIONS
    from smartbi.gold.restaurant.restaurant_intent import _build_t3_prompt

    prompt = _build_t3_prompt("本月营收多少", None, None, ("模拟·静安嘉里中心店",), None)
    for key, dim in DIMENSIONS.items():
        assert key in prompt, f"维度「{key}」登记了但没进 prompt —— 规划器指不到它"
        assert dim.asks in prompt, f"维度「{key}」的用法说明没进 prompt"


def test_planner_dimension_domain_follows_the_registry():
    """规划器允许产出的维度 = 登记表的键 ∪ 旧别名。

    ⛔ 登记表加一行, 这里要自动跟上 —— 手写就会漂移, 而漂移的方向是
       「新维度悄悄指不到」, 完全不报错。
    """
    from smartbi.gold.restaurant.metric_registry import DIMENSIONS
    from smartbi.gold.restaurant.restaurant_intent import _SEMANTIC_DIMENSIONS

    missing = set(DIMENSIONS) - set(_SEMANTIC_DIMENSIONS)
    assert not missing, f"这些已登记维度不在规划器取值域里: {sorted(missing)}"
    for legacy in ("dish", "time"):
        assert legacy in _SEMANTIC_DIMENSIONS, (
            f"旧别名 {legacy} 被去掉了 —— 计划缓存和已晋升路由里存着它, "
            f"去掉会让旧计划回放时**静默**校验失败")


def test_new_dimensions_reach_their_cells_without_a_second_list():
    """新维度靠**同名直通**接上, ⛔ 不在对照表里重复列一遍。"""
    for dim in ("staff", "weekday", "hour", "meal_period", "category", "city"):
        spec = _Spec(requested_metrics=("revenue",), dimensions=(dim,),
                     ranking_direction="best")
        assert spec_to_cell(spec) == ("revenue", dim, "rank"), (
            f"维度 {dim} 没接上 —— 登记了但翻译不出来")


def test_legacy_dimension_aliases_still_resolve():
    """dish→菜品 / time→日期: 旧计划回放时必须照旧成立。"""
    assert spec_to_cell(_Spec(requested_metrics=("revenue",), dimensions=("dish",),
                              ranking_direction="best")) == ("revenue", "product", "rank")
    assert spec_to_cell(_Spec(requested_metrics=("revenue",),
                              dimensions=("time",))) == ("revenue", "date", "trend")


def test_customer_dimension_is_deliberately_unmapped():
    """⛔ `customer` 没有对应的登记维度 —— 走原路径如实说没有,
    绝不用「门店」之类近似的顶上去。"""
    spec = _Spec(requested_metrics=("revenue",), dimensions=("customer",),
                 ranking_direction="best")
    assert spec_to_cell(spec) == ("revenue", "all", "summary")


def test_generic_fallback_only_runs_when_the_contract_failed():
    """🔴 承重: 兜底只在**契约判失败**的分支里跑。

    契约通过时它一行都不能执行 —— 否则通用执行器会接管现有能答对的问句,
    而那些问句的数字会悄悄换成另一套口径(同一个问题两个数)。

    ⚠️ 这条是**源码扫描**而不是行为断言: 跑一次 `tiered_answer` 要真库 + 真模型,
       而这条约束是**结构性**的, 结构错了行为测试也未必每次都露出来。
    """
    import io
    import pathlib

    src = io.open(pathlib.Path(__file__).resolve().parents[1]
                  / "restaurant" / "restaurant_intent_service.py",
                  encoding="utf-8").read()
    guard = "if not contract.passed or not displayable:"
    hook = "try_generic_answer"
    assert guard in src, "契约失败分支的判断条件被改了"
    assert hook in src, "兜底没接上"
    i_guard, i_hook = src.index(guard), src.index(hook)
    assert i_guard < i_hook, "🔴 兜底跑到了契约判断之前 —— 会接管现有正确答案"
    between = src[i_guard:i_hook]
    assert "\n        if " not in between and "\n    if " not in between, (
        "🔴 契约判断与兜底之间插进了别的分支 —— 兜底可能在契约通过时也执行")
    # ⛔ 兜底只在**它真答出来了**的时候接管; 答不出来必须继续走原样的拒绝语。
    assert 'generic.get("served")' in src, (
        "兜底没有检查是否真的答出来 —— 会把空结果当成答案返回")


#: 规划器能产出、但**没有任何手写 resolver 声明**的维度。
#: 落在这里的问句会被判成「查询维度超出计划 resolver 的能力范围」而拒答 ——
#: 在批 4 把路由倒过来之前, 这是**已知且可接受**的行为(拒答比编一个数好)。
#: ⛔ 但必须显式登记在这里: 新放开一个维度而不做决定, 症状是「本来能答的问句
#:    悄悄变成拒答」, 从通过率上看像模型退化。2026-08-09 实测: 放开 meal_period
#:    当天,「下个月各店人效安排」就这么挂了 —— 而那个 resolver **本来就按餐段出数**,
#:    只是能力表里没这个名字。
_DIMENSIONS_NO_RESOLVER_SERVES = {
    "brand", "category", "city", "hour", "table", "weekday",
    "wastage_reason", "wastage_type", "staff", "all",
}


def test_every_new_dimension_has_an_explicit_decision():
    """🔴 承重: 放开一个维度必须做一次决定 —— 要么某个 resolver 真能出它
    (补进能力表), 要么明确接受它会拒答(登记进上面这张表)。

    ⛔ 不做决定的后果是**静默**的: 规划器开始给问句打这个标签, 而没有 resolver
       声明它, 于是本来能答的问句变成「查询维度超出能力范围」。
    """
    from smartbi.gold.restaurant.metric_registry import DIMENSIONS, canonical_dimensions
    from smartbi.gold.restaurant.restaurant_intent_service import _RESOLVER_DIMENSIONS

    declared = set()
    for dims in _RESOLVER_DIMENSIONS.values():
        declared |= set(canonical_dimensions(sorted(dims)))
    unserved = {d for d in DIMENSIONS
                if not set(canonical_dimensions((d,))) & declared}
    undecided = unserved - _DIMENSIONS_NO_RESOLVER_SERVES
    assert not undecided, (
        f"这些维度放开了但没有任何 resolver 声明, 也没登记进「已知会拒答」名单: "
        f"{sorted(undecided)} —— 请二选一: 补进 _RESOLVER_DIMENSIONS(它真能出), "
        f"或加进 _DIMENSIONS_NO_RESOLVER_SERVES(接受它拒答)")
    stale = _DIMENSIONS_NO_RESOLVER_SERVES - unserved - set()
    assert not stale, (
        f"这些维度已经有 resolver 服务了, 该从「已知会拒答」名单里去掉: {sorted(stale)}")


def test_dimension_normalization_happens_before_its_first_consumer():
    """🔴 承重: 维度归一必须在 `_build_spec` 里**第一个消费者之前**。

    2026-08-09 实测的代价: 第一版把归一放在构造 spec 的那一行(出口), 而契约修复、
    意图规划、时间对比剥离都在它**之前**读那个局部变量 —— 它们看到的仍是
    `('product','dish')`, 与 resolver 声明的 `['dish','time']` 比不上 →
    修复被跳过 → 停在错的 resolver 上 →「本月米饭的销量」答成
    「问题对象与分析范围不一致」。整个回归电池连飞行前检查都过不去。

    ⚠️ 这条是**源码顺序**断言, 且只在 `_build_spec` 的函数体内比 ——
       第一版拿整份源码比偏移, 把另一个函数的**定义**位置当成了消费点, 自己红了。
       行为测试测不到「哪一行在哪一行前面」, 但比错范围一样得不到有效信号。
    """
    import io as _io
    import pathlib as _pathlib

    src = _io.open(_pathlib.Path(__file__).resolve().parents[1]
                   / "restaurant" / "restaurant_intent.py", encoding="utf-8").read()
    start = src.index("def _build_spec(")
    end = src.index(chr(10) + "def ", start + 10)
    body = src[start:end]

    norm = "dimensions = _canonical_dimensions(tuple(dimension_list))"
    assert norm in body, "维度归一不在 `_build_spec` 里 —— 新写法会直接漏进管线"
    i_norm = body.index(norm)
    for consumer, why in (
        ("_plan_requested_intents(", "意图规划(内部按 dish/time 比对)"),
        ('if comparison and "time" in dimensions:', "时间对比剥离"),
        ("_RESOLVER_DIMENSIONS", "契约修复的 resolver 能力比对"),
    ):
        assert consumer in body, f"消费者不见了({why}) —— 请更新这条测试, 别直接删断言"
        assert i_norm < body.index(consumer), (
            f"🔴 归一排在「{why}」之后 —— 那个消费者会读到未归一的写法, "
            f"比对失败后**静默**走进拒答")


# ═══════════════════════════════════════════════════════════════════════════
# 批 3 · 指标 —— 同一条纪律
# ═══════════════════════════════════════════════════════════════════════════
def test_prompt_renders_every_registered_metric():
    """🔴 承重: 登记表里的每个指标(含派生量)都必须进 prompt。"""
    from smartbi.gold.restaurant.metric_registry import DERIVED, METRICS
    from smartbi.gold.restaurant.restaurant_intent import _build_t3_prompt

    prompt = _build_t3_prompt("本月营收多少", None, None, ("模拟·静安嘉里中心店",), None)
    for key, m in list(METRICS.items()) + list(DERIVED.items()):
        assert key in prompt, f"指标「{key}」登记了但没进 prompt —— 规划器指不到它"
        assert m.asks in prompt, f"指标「{key}」的用法说明没进 prompt"


def test_data_gap_metrics_stay_out_of_the_prompt():
    """⛔ 数据缺口项**不进 prompt** —— 它们没有登记, 本来就该走「如实说没有」。

    塞进去等于让规划器承诺一个系统给不出的东西。
    ⚠️ 但它们必须留在**取值域**里: 确定性的关键词编译会识别出「净利润」,
       取值域去掉它, 那条识别结果会被校验丢弃 —— 于是连「没有这项数据」
       都说不出来, 变成答非所问。
    """
    from smartbi.gold.restaurant.restaurant_intent import (
        _build_t3_prompt, _SEMANTIC_METRICS)

    prompt = _build_t3_prompt("本月营收多少", None, None, ("模拟·静安嘉里中心店",), None)
    for gap in ("net_profit", "table_turnover", "staffing", "stocktaking_shortage",
                "customer_review", "production_time", "service_speed"):
        assert gap not in prompt, f"数据缺口项「{gap}」进了 prompt —— 等于承诺一个给不出的东西"
        assert gap in _SEMANTIC_METRICS, (
            f"数据缺口项「{gap}」被移出取值域 —— 确定性编译识别出它之后会被丢弃, "
            f"连「没有这项数据」都说不出来")


def test_planner_metric_domain_follows_the_registry():
    from smartbi.gold.restaurant.metric_registry import DERIVED, METRICS
    from smartbi.gold.restaurant.restaurant_intent import _SEMANTIC_METRICS

    missing = (set(METRICS) | set(DERIVED)) - set(_SEMANTIC_METRICS)
    assert not missing, f"这些已登记指标不在规划器取值域里: {sorted(missing)}"


def test_new_metrics_reach_their_cells_without_a_second_list():
    """新指标靠**同名直通**接上。⚠️ 归一之后管线用旧写法, 所以这里用管线写法测。"""
    from smartbi.gold.restaurant.metric_registry import canonical_metrics
    for metric in ("guests", "platform_fee", "avg_ticket", "gross_revenue",
                   "discount_rate", "tax_amount"):
        spec = _Spec(requested_metrics=canonical_metrics((metric,)),
                     dimensions=("store",), ranking_direction="best")
        cell = spec_to_cell(spec)
        assert cell is not None and cell[0] == metric, (
            f"指标 {metric} 没接上 —— 登记了但翻译不出来, 实际 {cell}")


def test_legacy_metric_aliases_still_resolve():
    """sales_volume→销量 / recipe_cost→食材成本 / wastage→损耗成本:
    计划缓存和已晋升路由里存着旧写法, 回放时必须照旧成立。"""
    for legacy, expect in (("sales_volume", "sales_qty"),
                           ("recipe_cost", "food_cost"),
                           ("wastage", "wastage_cost")):
        cell = spec_to_cell(_Spec(requested_metrics=(legacy,), dimensions=("store",),
                                  ranking_direction="best"))
        assert cell is not None and cell[0] == expect, f"旧别名 {legacy} 失效: {cell}"


def test_data_gap_metrics_are_deliberately_untranslatable():
    """⛔ 数据缺口项翻译不出来 —— 返回 None 走原路径如实说没有,
    绝不硬凑一个相邻指标顶包。"""
    for gap in ("net_profit", "table_turnover", "staffing", "stocktaking_shortage"):
        assert spec_to_cell(_Spec(requested_metrics=(gap,))) is None, (
            f"数据缺口项「{gap}」被翻译成了某个格子 —— 会拿相邻指标顶包")


# ═══════════════════════════════════════════════════════════════════════════
# 批 4 · resolver 审计 —— 18 个手写 resolver 逐个判定
# ═══════════════════════════════════════════════════════════════════════════
#: 🔴 **审计结论(2026-08-09, prod MOCK_REST 实测)**
#:
#: 判据是「数字逐字相等」不是「都能跑」。三处已在 prod 上逐字核对:
#:     总营收        resolver ¥6,490,180.61  ==  revenue×all×summary   6490180.61
#:     门店营收榜首  resolver ¥663,083.12    ==  revenue×store×rank     663083.12
#:     堂食营收      resolver ¥4,114,412     ==  revenue×channel×share  4114412.00
#:
#: ⛔ **结论: 不删这 7 个 resolver。** 它们在**取数层面**确实是格子的特例
#:    (数字等价已证), 但它们的答案里还有格子给不了的东西 —— 建议动作、
#:    KPI 卡、顺带提示、门店中位数对比。删掉 = 用一个更薄的答案换一个
#:    更整洁的架构, 那是拿用户价值换代码美观。
#:
#: ⚠️ 「倒转路由」这件事本身也被证据否掉了: 重放语料里剩下的 20% 契约失败
#:    全是「差异的**根本原因**」「下一步**该做什么**」「该重点**改善**哪个指标」——
#:    `analysis_action=diagnose/optimize`, **结构上就不是取数问题**。
#:    登记表答不了「为什么」, 倒转路由一条都救不回来。
#:
#: ✅ 真正起作用的是**契约失败兜底**(批 2 接的): 格子答得了而 resolver 答不了时
#:    接住。重放语料「答上了」41% → **44%**, 且电池 [56] 的实际答案已经是
#:    通用执行器的输出格式 —— 它在 prod 真的接球了。
_RESOLVER_CELL_EQUIVALENTS = {
    "resolve_sales_summary": ("revenue", "all", "summary"),
    "resolve_store_margin": ("revenue", "store", "rank"),
    "resolve_gross_margin": ("gross_margin", "product", "rank"),
    "resolve_recipe_cost": ("food_cost", "product", "rank"),
    "resolve_wastage_top": ("wastage_cost", "ingredient", "rank"),
    "resolve_channel_mix": ("revenue", "channel", "share"),
    "resolve_daypart_performance": ("revenue", "meal_period", "compare"),
}


def test_every_audited_resolver_still_has_its_equivalent_cell():
    """🔴 承重: 审计结论里声称「数字等价」的那 7 个格子必须一直拼得出 SQL。

    ⛔ 拼不出来 = 上面那份审计结论失效了(某次改动把等价关系弄断了), 而它是
       「不删 resolver」这个决定的**全部依据**。依据没了, 决定就成了想当然。
    """
    from smartbi.gold.restaurant.generic_executor import build_sql

    for resolver, cell in _RESOLVER_CELL_EQUIVALENTS.items():
        sql, requires, _base = build_sql(*cell)
        assert "SELECT" in sql and "$1" in sql, f"{resolver} 的等价格子拼不出 SQL: {cell}"
        assert requires, f"{resolver} 的等价格子没声明依赖列 —— 缺列时不会被拦"


def test_the_audited_resolvers_all_still_exist():
    """审计是对**当时存在**的 resolver 做的。有 resolver 被删/改名而审计表没跟上,
    这份结论就在描述一个不存在的系统。"""
    from smartbi.gold.restaurant import restaurant_ops_router as _router

    missing = [n for n in _RESOLVER_CELL_EQUIVALENTS if not hasattr(_router, n)]
    assert not missing, (
        f"审计表里这些 resolver 已经不在了: {missing} —— 请更新审计结论, "
        f"⛔ 别直接删断言")
