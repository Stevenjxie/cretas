"""折扣构成从生成器到 gold 的**整条链路**。

🔴 存在的理由（2026-08-09 实测）：MOCK_REST 的 `fact_pos_discount` 是 **0 行**，
   于是 `agg_discount` 物化出来恒为空，折扣构成端点对这个租户永远返回 ¥0。
   排查下来根因有两层，缺一层都修不好：

   1. **源头就没有这一维** —— 订单上只有一个标量 `discount_cents`
      （让了多少），没有「因为哪个活动让的」。下游按活动分组的表当然填不出来。
   2. **物化函数写了却没人调** —— `GoldMaterializer.materialize_discount`
      早就存在，但 `live_refresh` 只调 daily / channel / product 三个，
      漏了 discount。**写了重算却不接调度 = 写了一个永不执行的函数。**

⛔ 这个字段要穿过 **7 个承载点**才有用：
   生成器种子 → 生成器归属 → 平台 API → NormalizedOrder 契约 → 适配器解析
   → 落库 writer → 物化调度。逐段钉，不只测两头 ——
   同一轮里 `platform_fee` 已经栽过「建好了但中间断了」，
   `has_discount/meal_period/staff_id` 更是三列一起漏写过。

⚠️ 这道闸是**静态代理**：它证明每一段都提到了这件事，不证明真跑通。
   真正的证明只有一种 —— 让同步真拉一轮，回查 `fact_pos_discount` 有行、
   且每单构成加总等于该单的 `discount_amount`。
"""
import io
import pathlib
import re

import pytest


def _repo_root() -> pathlib.Path:
    """仓根 —— 用**特征目录**定位，不用固定层数。

    ⚠️ 写死 `parents[N]` 会随目录调整悄悄失效，而报出来的是
       「文件不存在」不是「链路断了」—— 两种红长得完全不同。
    """
    here = pathlib.Path(__file__).resolve()
    for parent in here.parents:
        if (parent / "mock-platform").is_dir() and (parent / "backend").is_dir():
            return parent
    raise AssertionError(f"定位不到仓根（从 {here} 往上找不到 mock-platform + backend）")


def _read(rel: str) -> str:
    return io.open(_repo_root() / rel, encoding="utf-8").read()


_GEN = "mock-platform/mock_platform/world/generator.py"
_SEED = "mock-platform/mock_platform/world/seed.py"


@pytest.mark.parametrize("rel,needle,why", [
    (_SEED, "_DISCOUNT_CAMPAIGNS", "没有活动种子，归属时无从可选"),
    (_GEN, "INSERT INTO order_discount", "生成器不写归属，源头就没有这一维"),
    ("mock-platform/mock_platform/world/schema.sql", "CREATE TABLE IF NOT EXISTS order_discount",
     "源库没有这张表，写入会直接报错"),
    ("mock-platform/mock_platform/api/_paging.py", '"discounts"',
     "平台 API 不吐构成，下游永远收不到"),
    ("backend/python/smartbi/ingestion/platforms/models.py", "NormalizedDiscount",
     "契约里没有这个类型，适配器传不过来"),
    ("backend/python/smartbi/ingestion/platforms/keruyun.py", "discounts=[",
     "适配器不解析，契约里就是空列表"),
    ("backend/python/smartbi/ingestion/platforms/writer.py", "INSERT INTO fact_pos_discount",
     "落库不写这张表，事实层永远是空的"),
    ("backend/python/smartbi/gold/live_refresh.py", "materialize_discount",
     "物化没人调，agg_discount 永远刷不出来"),
])
def test_every_carrier_in_the_chain_passes_it_through(rel, needle, why):
    """🔴 逐段钉：**任何一段断掉，整条链路都等于没做**。"""
    assert needle in _read(rel), f"{rel} 断了：{why}"


def test_attribution_does_not_change_the_amount():
    """⛔ 只做归属，不改金额。

    折扣金额的算法已经过验证（团购 15–30%、外卖 0–12%）。这次加的是
    「记在哪个活动头上」，一旦顺手改了金额，之前所有关于营收/毛利的核对
    全部作废，而且改动会藏在一个名字叫「折扣构成」的改动里。
    """
    src = _read(_GEN)
    m = re.search(r"discount\s*=\s*0(.*?)net\s*=\s*gross\s*-\s*discount", src, re.S)
    assert m, "折扣计算段落写法变了，这道闸要跟着改"
    body = m.group(1)
    assert "order_discount" not in body, "归属逻辑跑到金额计算里去了"
    assert "campaign" not in body, "活动选择跑到金额计算里去了"


def _strip_docstrings(source: str) -> str:
    """去掉文档字符串再扫。

    ⚠️ 第一版没做这一步，`test_no_fallback_bucket_is_invented` 搜到的是
       **它自己解释「不要写兜底桶」的那段中文注释**，于是一条正确的实现被判红。
       闸只能量代码在做什么，不能量它自己怎么描述自己。
    """
    import ast

    tree = ast.parse(source)
    spans = []
    for node in ast.walk(tree):
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef,
                             ast.ClassDef, ast.Module)):
            doc = ast.get_docstring(node, clean=False)
            if doc:
                spans.append(doc)
    out = source
    for doc in spans:
        out = out.replace(doc, "")
    # 行注释同理: `# 不要写「其他」兜底` 也是描述不是行为
    return "\n".join(re.sub(r"#.*$", "", line) for line in out.splitlines())


def test_one_order_attributes_the_whole_discount_to_one_campaign():
    """⛔ 构成加总必须恒等于订单折扣总额。

    拆成多个活动会让「构成之和 = 订单折扣」这条最容易被下游依赖的恒等式
    退化成近似。整笔记到一个活动上，恒等式就是精确的。
    """
    src = _strip_docstrings(_read(_GEN))
    start = src.find("INSERT INTO order_discount")
    assert start != -1, "order_discount 的写入不见了"
    stmt = src[start:start + 400]
    assert re.search(r",\s*discount\s*\)", stmt), (
        "写进 order_discount 的金额不是订单的整笔 discount —— 恒等式会破"
    )
    for splitter in (r"discount\s*[*/]", r"discount\s*//", r"int\s*\(\s*discount"):
        assert not re.search(splitter, stmt), (
            f"折扣在归属时被拆分了({splitter}) —— 构成之和不再等于订单折扣"
        )


def test_campaigns_are_bound_to_one_channel_each():
    """⛔ 每个活动绑死一个渠道。

    团购券在外卖单上核销、外卖满减出现在堂食单上，都会让
    「哪个渠道的让利来自哪个活动」失去意义 —— 而老板问「团购划不划算」
    问的正是这个。
    """
    src = _read(_SEED)
    m = re.search(r"_DISCOUNT_CAMPAIGNS\s*=\s*\[(.*?)\n\]", src, re.S)
    assert m, "活动种子写法变了"
    rows = re.findall(r'\(\s*"([^"]+)",\s*"([^"]+)",\s*"([^"]+)",\s*"([^"]+)"', m.group(1))
    assert rows, "解析不出任何活动 —— 这道闸此刻什么都没在测"
    channels = {r[3] for r in rows}
    assert channels <= {"groupon", "takeaway", "dine_in"}, f"出现未知渠道: {channels}"
    assert {"groupon", "takeaway"} <= channels, (
        "团购和外卖都必须有活动 —— 生成器对这两个渠道会产出折扣，"
        "没有可归属的活动会在造数时直接抛错"
    )


def test_no_fallback_bucket_is_invented():
    """⚠️ 没有构成时一行都不写，不许兜底成「其他折扣」。

    编一个不存在的活动名，会让「哪个活动让利最多」答出一个假冠军 ——
    比返回空更糟：空是「不知道」，假冠军是「言之凿凿的错」。
    """
    writer = _strip_docstrings(
        _read("backend/python/smartbi/ingestion/platforms/writer.py"))
    m = re.search(r"async def _write_discounts.*?(?=\nasync def |\Z)", writer, re.S)
    assert m, "_write_discounts 不见了"
    body = m.group(0)
    assert "for disc in order.discounts" in body, (
        "去掉注释后 _write_discounts 的主体没了 —— 这道闸此刻什么都没在测"
    )
    for banned in ("其他", "未知", "UNKNOWN", "OTHER"):
        assert banned not in body, f"出现兜底桶 {banned!r} —— 会造出假冠军"
