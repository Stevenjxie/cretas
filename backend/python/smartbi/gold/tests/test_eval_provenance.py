"""电池要说清「本轮是在什么条件下取的数」。

## 为什么

电池分数**不是代码版本的函数**, 是 `代码 × 今天哪个模型还活着 × 计划缓存冷热`
三者的函数。2026-08-11 实测:

  · 交接文档与我自己都报过「两轮读数完全一致」—— 它是**构造出来的**:
    第二轮 85 题里 59 题直接吃了第一轮刚写进进程内计划缓存(TTL 6h)的计划,
    结构上不可能与第一轮不同。
  · [27] 连过 12 轮再连挂 2 轮, 转折点压在部署重启上, 看着像那次部署的回归;
    落库显示同代码同模型下 21:16 通过、21:25 失败 —— planner 在摇摆,
    缓存只是把某一次冻住了, 部署重启 = 重掷骰子。

判据: **两个读数之间有因果链, 就不构成重复验证。**

## 🔴 本文件最承重的一条

`test_markers_match_real_prod_log_lines` —— 计数用的标记必须能匹配**真实的**
服务日志行。我要是凭空发明格式, 计数器在 prod 上恒为 0, 于是每轮都报「全冷」:
一个永远不会变红、且**方向是让人放心**的闸, 比没有更糟。
所以那条测试的输入是从 prod 日志逐字复制的, 不是我编的。
"""
from __future__ import annotations

import inspect

from smartbi.scripts.restaurant_ai_eval import (
    render_provenance, summarize_provenance,
)

# ⛔ 逐字复制自 /www/wwwroot/cretas/python-prod.log (2026-08-11 00:14~00:45)。
#    不要"整理"它们 —— 一整理就成了我编的格式, 这条测试也就白做了。
_REAL_LOG = """2026-08-11 00:14:32,137 - common.llm_router - INFO - [-] - [llm_router] slot=review skipping aliyun_a/qwen3.7-flash (quota-exhausted, re-probe after TTL)
2026-08-11 00:14:32,824 - httpx - INFO - [-] - HTTP Request: POST https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions "HTTP/1.1 200 OK"
2026-08-11 00:14:32,825 - common.llm_router - INFO - [-] - [cache] slot=review via aliyun_c/qwen3.8-max: prompt=436 cached=0 (0%) completion=10
2026-08-11 00:14:32,825 - common.llm_router - INFO - [-] - [llm_router] slot=review OK via aliyun_c/qwen3.8-max
2026-08-11 00:14:35,862 - common.llm_router - INFO - [-] - [llm_router] slot=review OK via aliyun_c/qwen3.8-max
2026-08-11 00:45:03,718 - smartbi.gold.restaurant.restaurant_intent - INFO - [-] - [restaurant-intent] zero-token plan-cache hit: authority=validated_plan_cache_contract_repair intent=RESTAURANT_OPS_STORE_MARGIN clarification=False stale=False query=本月模拟·打浦桥日月光店的米饭卖得怎么样
2026-08-10 21:25:49,132 - smartbi.gold.restaurant.restaurant_intent - INFO - [-] - [restaurant-intent] zero-token plan-cache hit: authority=validated_plan_cache intent=RESTAURANT_OPS_STORE_MARGIN clarification=False stale=False query=本月模拟·打浦桥日月光店的米饭卖得怎么样
"""


def test_markers_match_real_prod_log_lines():
    """🔴 承重: 标记必须匹配**真实**日志行, 否则 prod 上恒为 0 = 每轮都报「全冷」。"""
    got = summarize_provenance(_REAL_LOG)
    assert got["cache_hits"] == 2, "计划缓存命中数对不上真实日志"
    assert got["fresh_parses"] == 2, "真解析数对不上真实日志"
    assert got["models"] == {"aliyun_c/qwen3.8-max": 2}


def test_skipping_line_is_not_counted_as_served():
    """⛔ `slot=review skipping <model> (quota-exhausted)` 是**跳过**, 不是服务。

    把它算进去会得出「今天 qwen3.7-flash 在服务」的相反结论 —— 而链头烧没烧完
    正是要靠这个数来看的。
    """
    got = summarize_provenance(_REAL_LOG)
    assert "aliyun_a/qwen3.7-flash" not in got["models"]


def test_the_cache_line_next_to_it_is_not_double_counted():
    """⛔ `[cache] slot=review via X: prompt=…` 紧挨在 `OK via X` 前面。

    两行都带 `slot=review` 和模型名; 把它们都算上会让真解析数**翻倍**,
    于是一轮全冷也能显示成一半命中。判据靠 ` OK via ` 这一段区分。
    """
    only_cache_line = (
        "2026-08-11 00:14:32,825 - common.llm_router - INFO - [-] - "
        "[cache] slot=review via aliyun_c/qwen3.8-max: prompt=436 cached=0 (0%) completion=10\n"
    )
    assert summarize_provenance(only_cache_line)["fresh_parses"] == 0


def test_cold_round_says_it_is_comparable():
    lines = render_provenance(summarize_provenance(
        "[llm_router] slot=review OK via m/x\n" * 3))
    text = "\n".join(lines)
    assert "命中 0 / 真解析 3" in text
    assert "全冷" in text
    assert "m/x ×3" in text


def test_warm_round_says_it_is_a_replay():
    """🔴 热轮必须**明说这部分不是独立样本** —— 这正是我上报错结论那次缺的话。"""
    text = "\n".join(render_provenance(summarize_provenance(
        "zero-token plan-cache hit: a\n" * 59
        + "[llm_router] slot=review OK via m/x\n" * 24)))
    assert "命中 59 / 真解析 24" in text
    assert "不是独立样本" in text
    assert "全冷" not in text


def test_unreadable_log_says_unknown_loudly():
    """⛔ 读不到日志时必须明说不可知, 不能省略这一段。

    缺失的段落会被读成「没问题」—— 「沉默即通过」是本仓反复在拆的东西。
    """
    text = "\n".join(render_provenance({"unavailable": "读不到 /x/y.log"}))
    assert "不可知" in text
    assert "读不到 /x/y.log" in text
    assert "不要拿本轮分数与别轮直接比" in text


def test_provenance_is_printed_whether_or_not_the_round_passed():
    """🔴 取数条件是**读这个分数的前提**, 不是失败时才附的注解。

    判据: 打印它的那行必须在 `if failures:` 之前, 且不在任何条件分支里 ——
    否则全绿的那一轮(最需要「这轮是不是重放」这句话的那一轮)恰好不打。
    """
    import smartbi.scripts.restaurant_ai_eval as ev

    src = inspect.getsource(ev.run_eval)
    render_at = src.index("render_provenance(_provenance_since(log_cursor))")
    failures_at = src.index("if failures:")
    assert render_at < failures_at, "取数条件被排到失败明细之后/之内了"
    # 阴性对照: 这两个锚点真的都在源码里, 否则上面的比较等于空转
    assert render_at > 0 and failures_at > 0


def test_cursor_is_taken_before_the_preflight():
    """预检夹具也会发查询、也会焐热缓存 —— 它属于本轮条件, 必须被框进来。"""
    import smartbi.scripts.restaurant_ai_eval as ev

    src = inspect.getsource(ev.run_eval)
    assert src.index("log_cursor = _log_cursor()") < src.index("_preflight_fixture(")
