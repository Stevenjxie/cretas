"""预测排班的准入判据: **正向证据**, 不是「没说过去就默认明天」。

🔴 2026-08-08 prod 实测(MOCK_REST, 老板角色):

    问「员工人效怎么样」-> 答回来一整篇「**明天**预测排班 FactBook」
    (预测客流 39318 人、建议 4041 人、缺口 3761)。

用户问的是现状, 拿到的是未来。而且这句是**已晋升的零 token 路由** ——
答非所问被永久重放, 比没有答案更糟。

根因不是某处写错, 是**缺证据时的默认方向反了**: 旧判据只问「问句里有没有过去
时间词」, 没有就放行, 再由 `horizon_from_question` 的 backward-compatible 默认
把它变成 "tomorrow"。一句根本没提时间的话就这样成了「明天」。

⇒ 判据: 一个「缺证据就走默认值」的闸, 要问的不是「这个默认值合不合理」, 而是
  **「缺证据时错的那一侧代价有多大」**。这里错的一侧是答非所问 + 被固化, 所以
  必须要求正向证据。
"""
import pytest

from smartbi.services.restaurant.staffing_forecast import (
    requests_non_forecast_staffing_window as needs_confirmation,
)


@pytest.mark.parametrize("question", [
    "员工人效怎么样",      # ← prod 上真被答成明天排班的那句
    "门店人手够不够",
    "最近人效怎么样",
    "人效高不高",
    "在岗情况如何",
])
def test_state_questions_never_silently_become_tomorrow(question):
    """🔴 没有任何时间指向的**状态查询** -> 必须要求确认, 不许默认成明天。"""
    assert needs_confirmation(question) is True, question


@pytest.mark.parametrize("question", [
    "今天在岗多少人",
    "上个月人效如何",
    "最近30天人效怎么样",
    "上周排班合理吗",
])
def test_explicit_past_or_current_still_refused(question):
    """显式过去/当前窗口 —— 原本就该拦, 这次改动不能把它放过去。"""
    assert needs_confirmation(question) is True, question


@pytest.mark.parametrize("question", [
    "明天怎么排班",
    "下周要几个人",
    "下个月排班怎么安排",
    "明天晚市需要多少人",
])
def test_genuine_forecast_questions_are_not_harmed(question):
    """⛔ 阴性对照: 正当的预测排班问句一条都不许被误伤。

    只收紧不放宽的改动最容易在这里出事 —— 把闸调严, 顺手把正常路也堵了。
    """
    assert needs_confirmation(question) is False, question


def test_scheduling_action_without_a_date_still_forecasts():
    """「怎么排班」有排班动作但没说时间 -> 仍走预测(默认明天)。

    排班这个动作**只对未来成立**, 所以它本身就是指向未来的正向证据。
    这条记录的是有意为之的边界, 不是遗漏。
    """
    assert needs_confirmation("怎么排班") is False


def test_state_words_are_not_forecast_evidence():
    """⛔ 「人效/人手/在岗/几个人」不能被当成排班动作词。

    它们是状态查询。把它们收进正向证据表, 这道闸就退回改之前的样子了。
    """
    from smartbi.services.restaurant.staffing_forecast import (
        _FORECAST_ACTION_TOKENS,
    )

    for state_word in ("人效", "人手", "在岗", "几个人"):
        assert state_word not in _FORECAST_ACTION_TOKENS, state_word
