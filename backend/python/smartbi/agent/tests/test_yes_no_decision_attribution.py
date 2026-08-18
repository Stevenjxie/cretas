# -*- coding: utf-8 -*-
"""是非决策问句要接上量价分解（归因链第②步）。

📏 为什么有这个文件 —— prod 实测（MOCK_REST，绕过 narrative_cache，真实入口）:

    问「我要不要关掉最差的那家店」
      synthesize 收到 '我要不要关掉最差的那家店 最近30天'
      mode=lookup / auto_expand=False / attribution=False
      factbook.attribution = None          ⇒ 量价分解那一步**根本没跑**
      老板拿到的 670 字里「客单价」出现 **0 次**

`compute_store_attribution`（客流效应 / 客单价效应，恒等式）代码里早就有 ——
形态 B：机制在，没接上。这里守的就是「接上了」。

⛔ 本文件**不**新写第二份量价分解（形态 D）；分解仍然只有
   `factbook.compute_store_attribution` 一份，这里只守路由。
"""
from __future__ import annotations

import pytest

from smartbi.agent.synthesis_engine import (
    ComprehensiveSynthesisEngine,
    _HIGH_IMPACT_ACTION_RE,
    _is_thin_restate_eligible,
)


def _engine() -> ComprehensiveSynthesisEngine:
    return ComprehensiveSynthesisEngine.__new__(ComprehensiveSynthesisEngine)


#: prod 上真实入口喂给 `synthesize` 的那串（带时间后缀），⛔ 不是我想象的干净问句。
PROD_SHAPED_QUESTION = "我要不要关掉最差的那家店 最近30天"


class TestYesNoDecisionReachesAttribution:
    def test_prod_shaped_close_store_question_becomes_a_decision(self):
        """守：那句实测问句拿得到量价分解。"""
        plan = _engine().plan_dimensions(PROD_SHAPED_QUESTION)
        assert plan["analysis_mode"] == "decision", plan["analysis_mode"]
        assert plan["auto_expand"] is True
        # ▎这一条才是本轮要的东西 —— 前两条只是它的载体。
        assert plan["attribution"] is True

    @pytest.mark.parametrize("q", [
        "我要不要关掉最差的那家店",
        "垫底那家店该不该关",
        "落后的那家店值不值得继续开",
        "关掉拖后腿的门店划不划算",
        "有没有必要把最弱的门店关掉",
    ])
    def test_yes_no_phrasings_all_reach_attribution(self, q):
        plan = _engine().plan_dimensions(q)
        assert plan["analysis_mode"] == "decision", q
        assert plan["attribution"] is True, q

    def test_decision_plan_keeps_full_synthesis_not_thin_restate(self):
        """守最不显眼的那个（形态 C⁶）。

        只点亮 attribution 会让它走 500-token 的薄复述 —— 对「要不要关店」
        这种决策问句反而更薄。decision 模式必须同时带上 review/sales，
        这样 `_is_thin_restate_eligible` 才是 False。
        """
        plan = _engine().plan_dimensions(PROD_SHAPED_QUESTION)
        assert plan["review"] is True and plan["sales"] is True
        assert _is_thin_restate_eligible(plan) is False

        # 阳性对照：纯归因问句**确实**是 eligible 的 —— 否则上面那条
        # 恒为 False，等于没在守任何东西（B′：断言可能是恒真式）。
        pure = _engine().plan_dimensions("哪家店客流拖后腿")
        assert _is_thin_restate_eligible(pure) is True


class TestYesNoDecisionDoesNotOverreach:
    def test_bare_yes_no_without_store_cue_is_not_a_decision(self):
        """「要不要看一下昨天营收」不该拉满 21 个维度 —— 那是纯成本。"""
        plan = _engine().plan_dimensions("要不要看一下昨天营收")
        assert plan["analysis_mode"] != "decision"
        assert plan["auto_expand"] is False
        # 硬约束 9：配一个「必然会发生」的读数 —— 否则「没触发」和
        # 「这个函数根本没跑」长得一模一样。
        assert plan["finance"] is True, "plan 连 finance 都没点亮 ⇒ 上面那条读数无意义"

    def test_nengbuneng_is_a_data_request_not_a_decision(self):
        """「能不能给我看看各店营收」是取数请求，故意不收进是非词表。"""
        plan = _engine().plan_dimensions("能不能给我看看各店营收")
        assert plan["analysis_mode"] != "decision"
        assert plan["finance"] is True, "阳性对照：函数确实跑过"

    @pytest.mark.parametrize("q", [
        "哪家店拖后腿",
        "哪家店客流拖后腿",
        "十六家店里头哪家最不行，是没人来还是客人花的钱少",
    ])
    def test_plain_attribution_questions_stay_lookup(self, q):
        """我没有顺手把归因问句也改成 decision（那会让它们全部拉满维度）。"""
        plan = _engine().plan_dimensions(q)
        assert plan["analysis_mode"] == "lookup", q
        assert plan["attribution"] is True, q  # 阳性对照：它们本来就该有归因


class TestHighImpactGateWasDeliberatelyNotWidened:
    """⛔ 故意**没有**把「关店 / 关掉」加进 `_HIGH_IMPACT_ACTION_RE`。

    📏 理由是实测的：prod 基线答案里最有用的一句是

        「关掉它省不了多少成本，反而少一块收入」

    这是**对后果的陈述**，不是「建议关店」。而高影响动作闸是**分句级**的，
    这个分句里没有「先验证/试点/待确认/不建议」，一加词它就会被判违规、
    整行删掉 —— 形态 E：一道会误删有用正文的闸，代价比它挡住的东西大。

    ⇒ 这条断言守的是「我没有做那件事」。有人日后加词，它会红。
    """

    def test_close_store_consequence_sentence_is_not_flagged(self):
        assert _HIGH_IMPACT_ACTION_RE.search("关掉它省不了多少成本") is None
        assert _HIGH_IMPACT_ACTION_RE.search("不建议现在关店") is None

    def test_gate_still_catches_the_actions_it_always_caught(self):
        """阳性对照：闸没有被我弄哑。"""
        for clause in ("建议下架毛利最低的五个菜", "对这几道菜调价", "给VIP优先出餐"):
            assert _HIGH_IMPACT_ACTION_RE.search(clause) is not None, clause
