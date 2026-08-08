"""渠道侧成本（平台抽佣 / 券核销费）从生成器到 gold 的**整条链路**。

🔴 存在的理由（2026-08-09 实测）：MOCK_REST 三个渠道的毛利率是
   67.66% / 67.68% / 67.81% —— 几乎完全相同。原因不是经营健康，是**建模缺口**：
   订单侧只有「毛额 - 折扣 = 净额」，成本只跟菜品配方走、与渠道无关。
   于是「渠道毛利倒挂」这条规则**永远产出 0 条**，而它对应的正是真实餐饮里
   最要紧的问题之一：**外卖做得越大越不赚钱**。

⛔ 这个字段要穿过 **5 个承载点**才有用：
   生成器 → 平台 API → NormalizedOrder 契约 → 落库 writer → agg_daily 物化。
   我今天已经两次栽在「建好了但中间断了」（库存发现的域对不上、
   报工自定义字段被 `delete` 掉），所以这道闸逐段钉，而不是只测两头。
"""
import io
import pathlib
import re

import pytest


def _repo_root() -> pathlib.Path:
    """仓根 —— 用**特征文件**定位，不用固定层数。

    ⚠️ 第一版写死 `parents[4]` 少数了一层，五条断言全部因 FileNotFoundError 红。
       层数会随目录调整悄悄失效，而报出来的是「文件不存在」不是「链路断了」——
       两种红长得完全不同，别让闸用会误导的方式失败。
    """
    here = pathlib.Path(__file__).resolve()
    for parent in here.parents:
        if (parent / "mock-platform").is_dir() and (parent / "backend").is_dir():
            return parent
    raise AssertionError(f"定位不到仓根（从 {here} 往上找不到 mock-platform + backend）")


def _read(rel: str) -> str:
    return io.open(_repo_root() / rel, encoding="utf-8").read()


def test_generator_models_channel_specific_fee_rates():
    """⛔ 三个渠道必须有**不同**的费率，否则毛利率还是一样的。"""
    src = _read("mock-platform/mock_platform/world/generator.py")
    assert "_PLATFORM_FEE_RATE" in src, "生成器没有费率表"

    m = re.search(r"_PLATFORM_FEE_RATE\s*=\s*\{(.*?)\n\}", src, re.S)
    assert m, "费率表写法变了，这道闸要跟着改"
    body = m.group(1)
    for channel in ("takeaway", "groupon", "dine_in"):
        assert channel in body, f"费率表缺 {channel}"

    rates = {c: (float(lo), float(hi)) for c, lo, hi
             in re.findall(r'"(\w+)":\s*\(([\d.]+),\s*([\d.]+)\)', body)}
    assert set(rates) == {"takeaway", "groupon", "dine_in"}, rates
    assert rates["takeaway"][0] > rates["groupon"][1], (
        "外卖抽佣必须明显高于团购 —— 否则「外卖越大越不赚钱」这件事在数据里不成立"
    )
    assert float(rates["dine_in"][1]) == 0.0, "堂食没有平台抽佣，应为 0"


def test_fee_is_taken_on_net_not_gross():
    """⚠️ 按**实付净额**抽，不是毛额 —— 平台抽的是顾客真实付的那笔钱。"""
    src = _read("mock-platform/mock_platform/world/generator.py")
    assert re.search(r"platform_fee\s*=\s*int\(net\s*\*", src), (
        "抽佣基数写错了：必须是 net（实付），不是 gross"
    )


@pytest.mark.parametrize("rel,needle,why", [
    ("mock-platform/mock_platform/api/_paging.py", "platformFee",
     "平台 API 不吐这个字段，下游永远收不到"),
    ("backend/python/smartbi/ingestion/platforms/models.py", "platform_fee_cents",
     "NormalizedOrder 契约里没有这个字段，适配器传不过来"),
    ("backend/python/smartbi/ingestion/platforms/keruyun.py", "platformFee",
     "适配器不读这个字段，契约里就是 0"),
    ("backend/python/smartbi/ingestion/platforms/writer.py", "platform_fee_amount",
     "落库 INSERT 不带这一列，事实表永远是 0"),
    ("backend/python/smartbi/gold/materializer.py", "platform_fee_amount",
     "agg_daily 不汇总，日粒度分析读不到"),
])
def test_every_carrier_in_the_chain_passes_it_through(rel, needle, why):
    """🔴 逐段钉：**任何一段断掉，整条链路都等于没做**。"""
    assert needle in _read(rel), f"{rel} 断了：{why}"


def test_fee_is_not_folded_into_discount():
    """⛔ 抽佣不能并进折扣。

    折扣是**让给顾客**的，抽佣是**付给平台**的 —— 处置动作完全不同
    （前者调价格/套餐策略，后者谈费率或引流私域）。混在一起，老板看到
    「让利 4.5%」时无法区分哪部分是自己主动让的、哪部分是被平台抽走的。
    """
    src = _read("mock-platform/mock_platform/world/generator.py")
    # discount 的计算里不许出现平台费
    m = re.search(r"discount\s*=\s*0(.*?)net\s*=\s*gross\s*-\s*discount", src, re.S)
    assert m, "折扣计算段落写法变了"
    assert "platform_fee" not in m.group(1), "抽佣被并进折扣了"
    # 落库时两列分开
    writer = _read("backend/python/smartbi/ingestion/platforms/writer.py")
    assert "discount_amount" in writer and "platform_fee_amount" in writer


def test_migration_declares_zero_means_no_fee_not_unknown():
    """⚠️ 0 的语义必须写清：是「这个渠道没有抽佣」，不是「不知道」。

    堂食恒为 0、历史数据回填前也是 0 —— 那些订单确实没记过这笔钱，
    写 0 是如实。不写清楚，下一个人会把它当缺失值去填补。
    """
    sql = _read("backend/python/smartbi/database/migrations/"
                "V20261101_11__platform_fee_channel_cost.sql")
    assert "DEFAULT 0" in sql
    assert "不是" in sql and "不知道" in sql, "0 的语义没写清"
