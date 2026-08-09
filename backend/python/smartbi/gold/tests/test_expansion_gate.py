"""防膨胀闸的行为约束（桩掉模型，不打网络）。

⛔ 这里测的不是「审核判得准不准」—— 那要真模型。
   测的是**判错时会怎样**：假阳性（说是重复、其实不是）如果直接放行，
   系统会把这句话永久路由到错的能力上然后自信地答错，比不加这道闸更糟。
"""
import pytest

from smartbi.gold.restaurant.expansion_gate import (
    VERDICT_DUPLICATE,
    VERDICT_NEW,
    VERDICT_UNSURE,
    ExpansionVerdict,
    review_and_verify,
    review_expansion,
    _capability_catalogue,
)


class _FakeChain:
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


@pytest.fixture
def patch_judge(monkeypatch):
    def _install(answered, missing=""):
        async def _fake(query, answer, **kwargs):
            return answered, missing
        import smartbi.gold.restaurant.answer_addresses_query as mod
        monkeypatch.setattr(mod, "judge_answer_addresses_query", _fake)
    return _install


def test_catalogue_is_computed_from_registry_not_handwritten():
    """⛔ 喂给审核的能力清单必须**从登记表算出来**。

    手写会随登记表增长而过期, 而过期的方向恰恰是「清单里没有、其实已经有了」
    → 审核放行 → 膨胀。这道闸自己不能是膨胀的来源。
    """
    from smartbi.gold.restaurant.metric_registry import METRICS, DIMENSIONS

    cat = _capability_catalogue()
    keys = {c["key"] for c in cat}
    assert set(METRICS) <= keys, "指标没全部进清单"
    assert set(DIMENSIONS) <= keys, "维度没全部进清单"
    assert len(cat) >= len(METRICS) + len(DIMENSIONS), "清单条数少于登记条数"


@pytest.mark.asyncio
async def test_duplicate_without_verification_does_not_block(patch_chain):
    """🔴 承重: 审核说「重复」但**没验证**时, 不许拦。

    拦下来 = 把这句话永久路由到 X。如果审核判错(假阳性), 用户从此
    问 A 得到 B 的答案, 而系统认为一切正常 —— 今天真实发生过
    (「客单价最高的店」得到营收报表, served=true, contract_pass=true)。
    """
    patch_chain(_FakeChain(
        '{"verdict": "duplicate", "existing_key": "revenue", "reason": "同义"}'))
    v = await review_and_verify("客单价最高的店是哪家")   # 不给已有能力的答案
    assert v.verdict == VERDICT_DUPLICATE
    assert v.verified is None, "没验证却给了 verified 结论"
    assert v.should_block is False, "🔴 未经验证就拦下了"


@pytest.mark.asyncio
async def test_duplicate_verified_true_blocks(patch_chain, patch_judge):
    """审核说重复 + 判定说「已有能力确实答到了」→ 才拦, 并可登记路由。"""
    patch_chain(_FakeChain(
        '{"verdict": "duplicate", "existing_key": "avg_ticket", "reason": "同一个式子"}'))
    patch_judge(True)
    v = await review_and_verify("每单平均花多少", answer_of_existing="客单价 ¥360")
    assert v.should_block is True
    assert v.existing_key == "avg_ticket"


@pytest.mark.asyncio
async def test_duplicate_but_judge_says_not_answered_is_released(patch_chain, patch_judge):
    """🔴 承重: 审核说重复, 但拿已有能力真跑一次**没答到** → 审核判错了,
    这确实是新能力 → **放行去人审**, 不许拦。

    这一条就是整道闸的安全阀: 它把「审核的正确性」变成可证伪的,
    而不是靠信任模型。
    """
    patch_chain(_FakeChain(
        '{"verdict": "duplicate", "existing_key": "revenue", "reason": "看着像营收"}'))
    patch_judge(False, "回答给了营收, 但用户问的是客单价")
    v = await review_and_verify("客单价最高的店是哪家", answer_of_existing="营收最高的是 A 店")
    assert v.verified is False
    assert v.should_block is False, "🔴 审核判错了却仍然拦下 —— 会把问句路由到错的能力"
    assert "客单价" in v.verify_note


@pytest.mark.asyncio
async def test_judge_unavailable_does_not_block(patch_chain, patch_judge):
    """判定不可用(None) ≠ 验证通过。供应商池一干就放行, 不拦。"""
    patch_chain(_FakeChain(
        '{"verdict": "duplicate", "existing_key": "revenue", "reason": "x"}'))
    patch_judge(None)
    v = await review_and_verify("随便问点什么", answer_of_existing="某个回答")
    assert v.verified is None
    assert v.should_block is False


@pytest.mark.asyncio
async def test_new_capability_is_released_without_verification(patch_chain):
    """审核说是新能力 → 直接放行去人审, 不必再跑验证(省一次调用)。"""
    patch_chain(_FakeChain(
        '{"verdict": "new", "existing_key": "", "reason": "需要桌位数, 清单里没有"}'))
    v = await review_and_verify("翻台率怎么样")
    assert v.verdict == VERDICT_NEW
    assert v.should_block is False


@pytest.mark.asyncio
async def test_model_unavailable_is_unsure_not_duplicate(patch_chain):
    """⛔ 模型不可用必须是 unsure —— 若降级成 duplicate, 供应商池一干
    就会把所有新能力申请全部拦掉。"""
    patch_chain(_FakeChain(raise_exc=RuntimeError("All providers exhausted")))
    v = await review_expansion("任意问句")
    assert v.verdict == VERDICT_UNSURE
    assert v.should_block is False


@pytest.mark.asyncio
async def test_unknown_verdict_string_is_unsure(patch_chain):
    """模型返回没见过的结论词 → unsure, ⛔ 不许猜它想说什么。"""
    patch_chain(_FakeChain('{"verdict": "maybe", "reason": "x"}'))
    v = await review_expansion("任意问句")
    assert v.verdict == VERDICT_UNSURE


@pytest.mark.asyncio
async def test_prompt_carries_the_live_catalogue(patch_chain):
    """审核必须看到**当前**登记表 —— 看不到就只能凭记忆猜什么已经有了。"""
    fake = patch_chain(_FakeChain('{"verdict": "new", "reason": "x"}'))
    await review_expansion("测试问句")
    user_text = fake.payload["messages"][1]["content"]
    assert "客单价" in user_text, "清单里的派生指标没进提示词"
    assert "门店" in user_text, "清单里的维度没进提示词"
