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


@pytest.mark.asyncio
async def test_prompt_carries_no_metric_vocabulary(patch_chain):
    """⛔ 提示词里不许出现指标清单 —— 给了就退化成「有没有出现我列的词」。

    这正是本项目反复验证过没用的「加关键词」老路：系统已有的那张词表
    把「客单价」归进「订单」一类, 在真实案例上直接失效。
    """
    fake = patch_chain(_FakeChain('{"answered": true, "missing": ""}'))
    await judge_answer_addresses_query("本月营收多少", "总营收 ¥100")
    system_text = fake.payload["messages"][0]["content"]
    # 举例说明是允许的(提示词里用「客单价 vs 营收」举了个例子),
    # 但不许出现成串的指标枚举 —— 用「登记了多少个指标名」来判。
    vocabulary = ("翻台率", "复购率", "坪效", "毛利率", "销量", "损耗", "人效")
    hits = [w for w in vocabulary if w in system_text]
    assert len(hits) == 0, f"提示词里混进了指标词表: {hits}"


@pytest.mark.asyncio
async def test_answer_is_truncated_before_being_sent(patch_chain):
    """判定要便宜: 超长答案截断后再送, 否则每条回答都按全文计费。"""
    fake = patch_chain(_FakeChain('{"answered": true, "missing": ""}'))
    await judge_answer_addresses_query("本月营收多少", "数" * 5000)
    user_text = fake.payload["messages"][1]["content"]
    assert len(user_text) < 2000, "答案没被截断"
