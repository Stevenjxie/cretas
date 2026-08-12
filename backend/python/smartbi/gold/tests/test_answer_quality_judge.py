"""评分卡与四象限的行为约束（不打真模型）。

⛔ 这里**不测判定准不准** —— 那要真模型跑真答案，属于服务器上那一轮的事。
   这里测的是**判定结果怎么被解释**，而这一层恰恰是本仓反复出事的地方：
   「判不了」被折进「通过」，一张空的盲区表就读起来跟「一切正常」一模一样。

每条 🔴 承重断言下面记着**变异实测**：改坏它声称守着的那件事，它必须红，
且红的那句话要在说被测行为 —— 「注释写对了不构成约束力，只有会红的断言构成」。
"""
import json

import pytest

from smartbi.gold.customer_text import ANALYST_JARGON, INTERNAL_VOCAB
from smartbi.gold.restaurant.answer_quality_judge import (
    QualityVerdict,
    judge_answer_quality,
    parse_quality_payload,
    scan_listed_jargon,
)
from smartbi.scripts.restaurant_ai_judge import (
    Q_AGREE_FAIL,
    Q_AGREE_PASS,
    Q_BLIND,
    Q_FALSE_ALARM,
    Q_UNJUDGED,
    battery_required_vocabulary,
    build_comparison,
    quadrant,
    render_report,
)

_OK_PAYLOAD = json.dumps({
    "jargon": {"ok": True, "words": []},
    "layout": {"ok": True, "detail": ""},
    "numbers": {"ok": True, "detail": ""},
})


class _FakeChain:
    """按脚本返回的 call_chain 替身（与 test_answer_addresses_query 同形）。"""

    def __init__(self, content=None, raise_exc=None):
        self.content, self.raise_exc, self.payload = content, raise_exc, None

    async def __call__(self, slot, payload, **kwargs):
        self.payload = payload
        if self.raise_exc is not None:
            raise self.raise_exc
        return {"choices": [{"message": {"content": self.content}}]}


class _FakeSlot:
    REVIEW = "review-slot"


@pytest.fixture
def stub_addressed(monkeypatch):
    """桩掉判据①（它有自己的模块和自己的单测，这里不重复测它）。"""
    def _install(answered=True, missing=""):
        import smartbi.gold.restaurant.answer_addresses_query as mod

        async def _fake(query, answer, **kwargs):
            return answered, missing
        monkeypatch.setattr(mod, "judge_answer_addresses_query", _fake)
    return _install


# ── 判据②：词表这一层是确定性的，模型挂了它也要在 ────────────────────────


def test_listed_jargon_is_found_without_any_model():
    """🔴 承重: 词表命中不依赖模型 —— 模型挂了这一层照样报。

    变异实测: 把 `scan_listed_jargon` 改成 `return ()`
      → 红: `assert ('置信区间',) == ()` —— 红在「词表没命中」这个被测行为上。
    """
    hits = scan_listed_jargon("这里不会用简单涨跌替代因果结论，需要置信区间和决定系数")
    assert "置信区间" in hits and "决定系数" in hits


def test_jargon_vocab_has_exactly_one_definition():
    """🔴 承重: 源码闸与运行时判据读的是**同一个**元组。

    两份手写词表必然漂 —— 而漂掉的那一半会静默失效（本仓反复拆过的形状）。

    变异实测: 在测试文件里改回自己写一份字面量
      → 红: `assert (...13 词...) is (...13 词...)` —— 红在「不是同一个对象」上。
    """
    import smartbi.gold.tests.test_no_internal_jargon_in_customer_text as gate
    assert gate._INTERNAL_VOCAB is INTERNAL_VOCAB


def test_analyst_jargon_covers_the_four_words_the_plan_names():
    """🔴 承重: 计划 §1.⑦ 点名的四个词必须在运行时判据的管辖内。

    ⚠️ 它们**刻意不在源码闸里** —— 源码里现存违例
       (`restaurant_intent:1316`「…弹性、置信区间和决定系数」是一句真会发给
       店长的话)，合并两张表 = 在改文案之前先把 CI 弄红。改文案属 P-B。
    """
    for word in ("置信区间", "因果效果", "回归估计", "显著性"):
        assert word in ANALYST_JARGON
        assert word not in INTERNAL_VOCAB, (
            f"{word} 进了源码闸的词表 —— 那道闸现在会红在 restaurant_intent:1316")


# ── 解析：模型说了什么，怎么算数 ──────────────────────────────────────


def test_model_words_already_in_the_list_are_not_double_counted():
    """🔴 承重: 模型列的词若已在词表里，不重复计一次。

    模型这一层的价值是「词表**没收**的」；重复报会让读的人以为有两个问题。

    变异实测: 去掉 `parse_quality_payload` 里的 `listed_set` 过滤
      → 红: `assert () == ('置信区间',)` —— 红在「重复报了同一个词」上。
    """
    parsed = parse_quality_payload(
        json.dumps({"jargon": {"ok": False, "words": ["置信区间", "夏普比率"]}}),
        listed=("置信区间",))
    assert parsed["jargon_unlisted"] == ("夏普比率",)


@pytest.mark.parametrize("content", ["", "不是 JSON", "{}", '{"foo": 1}'])
def test_unparseable_output_is_none_not_a_clean_bill(content):
    """🔴 承重: 解析不出来返回 None（判不了），**不是**「三项都通过」。

    变异实测: 把 `return None` 改成 `return {"jargon_unlisted": (), ...}`
      → 红: `assert {...} is None` —— 红在「把判不了当成了通过」上。
    """
    assert parse_quality_payload(content) is None


# ── 失败方向：判不了 ≠ 通过 ──────────────────────────────────────────


@pytest.mark.asyncio
async def test_model_down_is_unknown_not_pass(stub_addressed):
    """🔴 承重: 判定模型挂了 → verdict = unknown，**绝不是 pass**。

    折成 pass 就是「沉默即通过」：判定链路一挂，盲区表当场变空，
    而空表读起来跟「一切正常」一模一样。

    变异实测: 把 `verdict` 里的 `if self.unavailable ... return "unknown"` 删掉
      → 红: `assert 'pass' == 'unknown'` —— 红在「不可用被读成通过」上。
    """
    stub_addressed()
    verdict = await judge_answer_quality(
        "本月营收多少", "总营收 ¥100",
        call_chain=_FakeChain(raise_exc=RuntimeError("链头全挂")),
        slot_enum=_FakeSlot)
    assert verdict.verdict == "unknown"
    assert "判定模型不可用" in verdict.unavailable


@pytest.mark.asyncio
async def test_deterministic_problems_survive_a_dead_model(stub_addressed):
    """🔴 承重: 模型挂了，确定性那一层的问题**仍然要报出来**。

    变异实测: 把不可用分支里的 `jargon_listed=listed` 改成 `jargon_listed=()`
      → 红: `assert [] != []` / 缺「置信区间」—— 红在「模型一挂连免费的那层也丢了」。
    """
    stub_addressed()
    verdict = await judge_answer_quality(
        "弹性怎么算", "需要置信区间才能算",
        call_chain=_FakeChain(raise_exc=RuntimeError("链头全挂")),
        slot_enum=_FakeSlot,
        table_problems=("表格前缺空行",))
    # ⛔ 不写 `assert any(...)` —— 那个红成一句 `assert False`, 读不出被测的是什么。
    #    摊平成一个字符串再断言, 红的时候原文自己会说清丢了哪一层。
    survived = " | ".join(verdict.deterministic_problems)
    assert "置信区间" in survived, f"模型挂了之后词表层也丢了: {survived!r}"
    assert "表格前缺空行" in survived, f"模型挂了之后表格层也丢了: {survived!r}"
    # 有确定性问题 → fail 优先于 unknown（问题是真的，跟模型在不在无关）
    assert verdict.verdict == "fail"


@pytest.mark.asyncio
async def test_addressed_none_alone_makes_it_unknown(stub_addressed):
    """🔴 承重: 判据①判不了，整条也算 unknown —— 不许拿剩下三条凑一个 pass。"""
    stub_addressed(answered=None)
    verdict = await judge_answer_quality(
        "本月营收多少", "总营收 ¥100",
        call_chain=_FakeChain(_OK_PAYLOAD), slot_enum=_FakeSlot)
    assert verdict.verdict == "unknown"


@pytest.mark.asyncio
async def test_clean_answer_passes(stub_addressed):
    """阴性对照：四条判据都干净时确实是 pass（否则上面几条 unknown 说明不了什么）。"""
    stub_addressed()
    verdict = await judge_answer_quality(
        "本月营收多少", "本月总营收 ¥100，共 20 单。",
        call_chain=_FakeChain(_OK_PAYLOAD), slot_enum=_FakeSlot)
    assert verdict.verdict == "pass", verdict.problems


# ── 判据③：排版判据必须拿到未压平的原文 ────────────────────────────────


@pytest.mark.asyncio
async def test_layout_judge_receives_raw_newlines(stub_addressed):
    """🔴 承重: 传给模型的答案里**必须还有换行**。

    电池里到处在用 `" ".join(message.split())`；那个 flat 一旦流到这里，
    排版判据就等于自发了一张永远绿的通行证 —— 08-11 那 8 张塌掉的表
    正是这么躲过两轮 85/85 的。

    变异实测: 把 `_build_messages` 里的 `answer` 换成 `" ".join(answer.split())`
      → 红: `assert '\\n' in '...一行...'` —— 红在「原文的换行被压掉了」上。
    """
    stub_addressed()
    fake = _FakeChain(_OK_PAYLOAD)
    await judge_answer_quality(
        "排行给我看看", "**排行：**\n| # | 菜 |\n|---|---|\n| 1 | 米饭 |",
        call_chain=fake, slot_enum=_FakeSlot)
    sent = fake.payload["messages"][-1]["content"]
    assert "\n| # | 菜 |" in sent, "答案被压平了，排版判据形同虚设"


# ── 四象限：unknown 不许折叠 ────────────────────────────────────────


@pytest.mark.parametrize("assertion_failed,judge,expected", [
    (False, "pass", Q_AGREE_PASS),
    (False, "fail", Q_BLIND),
    (True, "pass", Q_FALSE_ALARM),
    (True, "fail", Q_AGREE_FAIL),
    (False, "unknown", Q_UNJUDGED),
    (True, "unknown", Q_UNJUDGED),
])
def test_quadrant_mapping(assertion_failed, judge, expected):
    """🔴 承重: 最后两行 —— unknown 无论断言红绿都落「判不了」。

    变异实测: 把 `if judge_verdict == VERDICT_UNKNOWN` 那行删掉
      → 红: `assert '一致通过' == '判不了'` —— 红在「判不了被折进了通过」上。
    """
    assert quadrant(assertion_failed, judge) == expected


def test_blind_spot_row_carries_both_sides():
    """盲区那一行要同时带着「断言说什么」和「判定说什么」—— 只有一边没法核实。"""
    rows = [{"idx": 7, "q": "有没有菜在亏钱卖", "assertion_problems": [],
             "message": "按销量排的榜单"}]
    verdicts = [QualityVerdict(addressed=False, missing="问的是毛利，答的是销量",
                               jargon_unlisted=(), layout_llm="", number_conflict="")]
    buckets = build_comparison(rows, verdicts)
    assert len(buckets[Q_BLIND]) == 1
    entry = buckets[Q_BLIND][0]
    assert entry["assertion_problems"] == []
    assert any("毛利" in p for p in entry["judge_problems"])


# ── 判据④ 降级：只报，不计入 verdict ──────────────────────────────────


def test_number_doubt_alone_does_not_fail_the_verdict():
    """🔴 承重: 只有判据④报了问题时，verdict 仍是 pass，问题进 advisory。

    owner 2026-08-12 拍板降级，依据是它在真实语料上 0/2（两条都把一句话里
    两个指标的数字绑错了主语）。

    变异实测: 把 `number_conflict` 加回 `llm_problems`
      → 红: `assert 'fail' == 'pass'` —— 红在「判据④又开始决定归类了」上。
    """
    v = QualityVerdict(addressed=True, jargon_unlisted=(), layout_llm="",
                       number_conflict="销量低于中位却称高位")
    assert v.verdict == "pass", v.problems
    assert v.problems == []
    assert v.advisory and "销量低于中位却称高位" in v.advisory[0]


def test_number_doubt_lands_in_its_own_report_section():
    """🔴 承重: 降级 ≠ 静音 —— 它必须仍然出现在报告里。

    变异实测: 删掉 render_report 里的「数字存疑」段
      → 红: `assert '数字存疑' in ...` —— 红在「降级变成了藏起来」上。
    """
    buckets = build_comparison(
        [{"idx": 5, "q": "毛利怎么样", "assertion_problems": []}],
        [QualityVerdict(addressed=True, jargon_unlisted=(), layout_llm="",
                        number_conflict="分项加总对不上")])
    report = render_report({"provenance": {"cache_hits": 0, "fresh_parses": 1}}, buckets)
    assert "数字存疑" in report
    assert "分项加总对不上" in report
    assert "不计入四象限" in report
    # 且它没有把这一题打进盲区格
    assert "🔴 **0 盲区**" in report or "| 0 一致通过 | 🔴 **0 盲区** |" in report


# ── 可接受词表：推导，不是手写 ────────────────────────────────────────


def test_allow_list_is_derived_from_what_the_battery_requires():
    """🔴 承重: 电池 `contains` 要求出现的词，进可接受词表。

    owner 拍板「成本覆盖率是业务词」；[21] 的 contains 正好要求它出现。

    变异实测: 把 `battery_required_vocabulary` 改成 `return frozenset()`
      → 红: `AssertionError: 成本覆盖率 不在推导出的词表里` —— 红在「推导没生效」上。
    """
    vocab = battery_required_vocabulary()
    assert "成本覆盖率" in vocab, "成本覆盖率 不在推导出的词表里（电池 [21] 明确要求它出现）"
    assert "毛利率" in vocab


def test_explicit_jargon_lists_outrank_the_derived_allow_list():
    """🔴 承重: 内部概念词/统计黑话**压过**推导 —— 万一电池写错也不许被静音。

    ⚠️ 用**合成用例**测，不用真实 CASES。2026-08-12 变异实测发现：拿真实 CASES
       测这条时它**恒真** —— 今天没有任何 `contains` 含黑话词，所以去掉
       `- banned` 也不会红。一条不可能红的断言不是断言。
       （「今天的电池确实没写错」由下一条读真实 CASES 单独守着。）

    变异实测: 去掉 `battery_required_vocabulary` 里的 `- banned`
      → 红: `这些词同时被判成「该说的」和「黑话」: ['维度', '置信区间']`
    """
    poisoned = [{"q": "x", "contains": ["维度", "置信区间", "毛利率"]}]
    vocab = battery_required_vocabulary(poisoned)
    leaked = sorted(vocab & (set(INTERNAL_VOCAB) | set(ANALYST_JARGON)))
    assert not leaked, f"这些词同时被判成「该说的」和「黑话」: {leaked}"
    # 阴性对照: 正常业务词照样收进来, 否则上面的空集可能只是因为函数什么都不返回
    assert "毛利率" in vocab


def test_battery_never_requires_a_word_it_also_calls_jargon():
    """🔴 承重: 电池自身不许自相矛盾 —— `contains` 里不能出现内部概念词。

    上一条保证「矛盾时黑话优先」；这一条保证**矛盾根本不该存在**。
    两条都要有: 只有上一条的话, 电池里写进一个 `维度` 也不会有人知道。
    """
    from smartbi.scripts.restaurant_ai_eval import CASES
    banned = set(INTERNAL_VOCAB) | set(ANALYST_JARGON)
    offenders = [
        (i, m) for i, c in enumerate(CASES, 1)
        for m in c.get("contains", ()) if str(m).strip("「」 ") in banned
    ]
    assert not offenders, (
        f"电池断言要求这些内部概念词必须出现在给店长的答案里: {offenders}")


def test_allowed_word_is_dropped_from_model_jargon_findings():
    """推导出的词表真的在过滤模型输出（否则上面几条只是在测一个没人用的集合）。"""
    parsed = parse_quality_payload(
        json.dumps({"jargon": {"ok": False, "words": ["成本覆盖率", "夏普比率"]}}),
        listed=(), allow=("成本覆盖率",))
    assert parsed["jargon_unlisted"] == ("夏普比率",)


def test_report_states_unjudged_separately():
    """🔴 承重: 报告里「判不了」必须单独出现，不能只报四格。

    变异实测: 删掉 render_report 里那句「另有 N 题判不了」
      → 红: `assert '判不了' in '...'` —— 红在「报告把判不了藏起来了」上。
    """
    buckets = build_comparison(
        [{"idx": 1, "q": "x", "assertion_problems": []}],
        [QualityVerdict(addressed=None, unavailable="判定模型不可用: 全挂")])
    report = render_report({"provenance": {"cache_hits": 0, "fresh_parses": 3}}, buckets)
    assert "1 题判不了" in report
    assert "不计入任何一格" in report
