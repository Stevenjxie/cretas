"""判定器的行为约束（不打真模型，桩掉 call_chain）。

⛔ 这里**不测判定准确率** —— 那要靠真模型，已在 2026-08-09 用 6 个真实案例
   实测过（5/6，两个真实故障案例都抓住）。这里测的是**判定结果怎么被解释**：
   「判不了」必须与「判定没答到」区分开，因为两者的下游动作完全不同 ——
   前者只是不学，后者要进待办清单。混成一个会让清单被噪音淹掉。
"""
import pytest

from smartbi.gold.restaurant.answer_addresses_query import (
    judge_answer_addresses_query,
)


class _FakeChain:
    """按脚本返回的 call_chain 替身。记录被传入的 payload 供断言。"""

    def __init__(self, content=None, raise_exc=None):
        self.content = content
        self.raise_exc = raise_exc
        self.payload = None

    async def __call__(self, slot, payload, **kwargs):
        self.payload = payload
        if self.raise_exc is not None:
            raise self.raise_exc
        return {"choices": [{"message": {"content": self.content}}]}


@pytest.fixture
def patch_chain(monkeypatch):
    def _install(fake):
        import common.llm_router as router
        monkeypatch.setattr(router, "call_chain", fake)
        return fake
    return _install


@pytest.mark.asyncio
async def test_answered_true_returns_no_missing(patch_chain):
    patch_chain(_FakeChain('{"answered": true, "missing": ""}'))
    answered, missing = await judge_answer_addresses_query("本月营收多少", "总营收 ¥100")
    assert answered is True
    assert missing == ""


@pytest.mark.asyncio
async def test_answered_false_carries_what_is_missing(patch_chain):
    """没答到时必须说清缺什么 —— 待办清单要靠这句话分组。"""
    patch_chain(_FakeChain(
        '{"answered": false, "missing": "没有给出客单价最高的门店"}'))
    answered, missing = await judge_answer_addresses_query(
        "客单价最高的店是哪家", "营收最高的是 A 店")
    assert answered is False
    assert "客单价" in missing


@pytest.mark.asyncio
async def test_model_unavailable_is_none_not_false(patch_chain):
    """🔴 承重: 模型不可用返回 None, **不是 False**。

    2026-08-09 当天 REVIEW 槽 20 个供应商一度全部耗尽。若那时把「判不了」
    当成「没答到」, 每一条回答都会被塞进待办清单 —— 清单当场被噪音淹掉,
    而它本来是用来决定「接下来补哪个能力」的唯一依据。
    """
    patch_chain(_FakeChain(raise_exc=RuntimeError("All providers exhausted")))
    answered, missing = await judge_answer_addresses_query("本月营收多少", "总营收 ¥100")
    assert answered is None
    assert missing == ""


@pytest.mark.asyncio
async def test_unparseable_output_is_none_not_false(patch_chain):
    """输出不是 JSON 也算「判不了」, 同上不能当成「没答到」。"""
    patch_chain(_FakeChain("我觉得这个回答挺好的。"))
    answered, _ = await judge_answer_addresses_query("本月营收多少", "总营收 ¥100")
    assert answered is None


@pytest.mark.asyncio
async def test_json_wrapped_in_prose_is_still_parsed(patch_chain):
    """模型常在 JSON 前后附一句说明 —— 整体解析失败会白丢一次有效判定。"""
    patch_chain(_FakeChain(
        '判定如下：\n```json\n{"answered": false, "missing": "缺翻台率"}\n```\n以上。'))
    answered, missing = await judge_answer_addresses_query("翻台率怎么样", "总营收 ¥100")
    assert answered is False
    assert "翻台率" in missing


@pytest.mark.asyncio
async def test_empty_query_or_answer_is_unjudgeable(patch_chain):
    """空输入不调模型 —— 省一次调用, 且「没东西可判」本来就不是「没答到」。"""
    fake = patch_chain(_FakeChain('{"answered": true}'))
    assert (await judge_answer_addresses_query("", "有答案"))[0] is None
    assert (await judge_answer_addresses_query("有问题", ""))[0] is None
    assert fake.payload is None, "空输入不该触发模型调用"


#: 提示词里允许出现的指标词个数上限。
#: 当前是 2 —— 唯一那句示例「问『客单价最高的店』却给了『营收最高的店』」。
#: ⛔ 想调高这个数之前先问: 你是在举例, 还是在列表?
_PROMPT_METRIC_WORD_BUDGET = 2


@pytest.mark.asyncio
async def test_prompt_does_not_grow_a_metric_vocabulary(patch_chain):
    """⛔ 提示词里不许长出指标词表 —— 长出来就退化成「有没有出现我列的词」。

    🔴 这条测试的第一版是错的, 而且错得很典型: 我自己手写了一张词表
       (翻台率/复购率/坪效/毛利率/销量/损耗/人效)去检查「有没有词表」,
       而那张表**恰好不含提示词里真实出现的「客单价」「营收」** ——
       等于划了一张不会红的表来证明「没有表」。
       判据: **闸不能拿自己写的清单当基准**(同一形状本轮已栽过三次)。

    ✅ 改成拿**系统已有的那张权威表** `answer_contract._REQUEST_TEXT_TOKENS`
       当基准。它不是我写的, 是生产在用的; 谁要往提示词里塞词表, 用的几乎
       必然是这张表里的词。这也正是本仓 `_drop_planner_invented_metrics`
       注释里定的规矩:「复用它, **不另建一张词表**」—— 我上一版恰恰违反了。

    ⚠️ 允许**一句示例**(教「相关但不同的问题」这个概念), 不允许**一串枚举**:
       所以既卡总数, 也卡「必须落在同一句里」—— 后者让列表在结构上长不出来。
    """
    from smartbi.gold.restaurant.answer_contract import _REQUEST_TEXT_TOKENS

    fake = patch_chain(_FakeChain('{"answered": true, "missing": ""}'))
    await judge_answer_addresses_query("本月营收多少", "总营收 ¥100")
    system_text = fake.payload["messages"][0]["content"]

    all_tokens = {tok for tokens in _REQUEST_TEXT_TOKENS.values() for tok in tokens}
    hits = sorted(tok for tok in all_tokens if tok in system_text)
    assert len(hits) <= _PROMPT_METRIC_WORD_BUDGET, (
        f"提示词里出现了 {len(hits)} 个指标词 {hits}，超过示例预算 "
        f"{_PROMPT_METRIC_WORD_BUDGET} —— 这是在列词表，不是在举例。"
        f"判定一旦依赖词表，新提法(未登记的)就判不出来，"
        f"而那正是这套判定要取代的东西。"
    )

    # 结构约束: 允许的那几个词必须挤在**同一行**里(那句示例), 不能散开成清单。
    if hits:
        lines = [ln for ln in system_text.splitlines() if any(t in ln for t in hits)]
        assert len(lines) == 1, (
            f"指标词散落在 {len(lines)} 行里: {lines} —— "
            f"示例应当只占一行；跨行就是清单的形状。"
        )


@pytest.mark.asyncio
async def test_answer_is_truncated_before_being_sent(patch_chain):
    """判定要便宜: 超长答案截断后再送, 否则每条回答都按全文计费。"""
    fake = patch_chain(_FakeChain('{"answered": true, "missing": ""}'))
    await judge_answer_addresses_query("本月营收多少", "数" * 5000)
    user_text = fake.payload["messages"][1]["content"]
    assert len(user_text) < 2000, "答案没被截断"
