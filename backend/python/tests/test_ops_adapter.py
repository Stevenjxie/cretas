"""后厨供应链 adapter(领料 / 损耗 / 盘点)。

重点全在**失败路径**上 —— 正常路径写错了三条断言就红, 而下面这几类写错了
正常路径照样绿:
  1. 业务错误码被当成空页(HTTP 恒 200, 只看 status_code 什么都看不出来);
  2. 小数数量/金额被 `int()` 静默截断;
  3. kind 直接拼进 URL(白名单退化成透传);
  4. 缺 status 的领料单被默认成 COMPLETED, 凭空算进领料成本。
"""
import datetime
import pathlib
import sys

import pytest

from smartbi.ingestion.platforms.keruyun import KeruyunBusinessError, KeruyunPayloadError, sign
from smartbi.ingestion.platforms.ops_keruyun import KeruyunOpsAdapter, KeruyunOpsKindError
from smartbi.ingestion.platforms.ops_models import (
    NormalizedRequisition, NormalizedStocktaking, NormalizedWastage,
)

# 模拟端在仓库根的 mock-platform/ 下, 不是 backend/python 的包 —— 显式加路径。
_MOCK_ROOT = pathlib.Path(__file__).resolve().parents[3] / "mock-platform"
if str(_MOCK_ROOT) not in sys.path:
    sys.path.insert(0, str(_MOCK_ROOT))


class _FakeResponse:
    def __init__(self, payload):
        self._payload = payload
        self.status_code = 200      # 平台恒 200, 刻意固定死


    def json(self):
        return self._payload


class _FakeClient:
    def __init__(self, payload):
        self._payload = payload
        self.last_url = None
        self.last_params = None
        self.calls = 0

    async def get(self, url, params=None, timeout=None):
        self.calls += 1
        self.last_url = url
        self.last_params = params
        return _FakeResponse(self._payload)


def _page(*items, next_cursor=42, has_more=False):
    return {"code": "0", "message": "success",
            "data": {"list": list(items), "nextCursor": next_cursor, "hasMore": has_more}}


_REQ = {"docNo": "RQ2026072900001", "shopCode": "MK01", "bizDate": "2026-07-29",
        "ingredientName": "牛腩", "ingredientCategory": "肉禽", "unit": "kg",
        "qty": 12500, "cost": 48800, "status": "COMPLETED"}

_WST = {"docNo": "WS2026072900001", "shopCode": "MK02", "bizDate": "2026-07-29",
        "ingredientName": "生菜", "ingredientCategory": "蔬菜", "unit": "kg",
        "wastageType": "变质", "qty": 800, "cost": 1200}

_STK = {"docNo": "ST2026072900001", "shopCode": "MK03", "bizDate": "2026-07-28",
        "ingredientName": "花椒", "ingredientCategory": "调料", "unit": "kg",
        "systemQty": 5000, "actualQty": 4200, "diffCost": -3600}

_SAMPLE = {"requisition": _REQ, "wastage": _WST, "stocktaking": _STK}


def _adapter(payload, base="http://mock", key="kk", secret="ss"):
    return KeruyunOpsAdapter(base, key, secret, _FakeClient(payload))


# ───────────────────────── 归一化: 三类单据 ─────────────────────────

async def test_领料单被正确归一化():
    adapter = _adapter(_page(_REQ))
    page = await adapter.fetch_page("requisition", "0", 50)
    assert page.kind == "requisition"
    assert page.next_cursor == "42" and page.has_more is False
    doc = page.items[0]
    assert isinstance(doc, NormalizedRequisition)
    assert doc.platform == "keruyun"
    assert doc.doc_no == "RQ2026072900001"
    assert doc.store_code == "MK01"
    assert doc.biz_date == datetime.date(2026, 7, 29)
    assert (doc.ingredient.name, doc.ingredient.category, doc.ingredient.unit) == (
        "牛腩", "肉禽", "kg")
    assert doc.qty_milli == 12500
    assert doc.cost_cents == 48800
    assert doc.status == "COMPLETED"


async def test_损耗单被正确归一化():
    page = await _adapter(_page(_WST)).fetch_page("wastage", "0", 50)
    assert page.kind == "wastage"
    doc = page.items[0]
    assert isinstance(doc, NormalizedWastage)
    assert doc.doc_no == "WS2026072900001"
    assert doc.store_code == "MK02"
    assert doc.biz_date == datetime.date(2026, 7, 29)
    assert doc.ingredient.name == "生菜" and doc.ingredient.unit == "kg"
    assert doc.wastage_type == "变质"
    assert doc.qty_milli == 800
    assert doc.cost_cents == 1200


async def test_盘点单被正确归一化_盘亏负数被接受():
    """盘亏(实盘 < 系统账)是正常业务形态, 金额同号为负。加个非负校验会把
    盘点分析里最有价值的一半直接拒掉。"""
    page = await _adapter(_page(_STK)).fetch_page("stocktaking", "0", 50)
    assert page.kind == "stocktaking"
    doc = page.items[0]
    assert isinstance(doc, NormalizedStocktaking)
    assert doc.doc_no == "ST2026072900001"
    assert doc.store_code == "MK03"
    assert doc.biz_date == datetime.date(2026, 7, 28)
    assert doc.ingredient.name == "花椒"
    assert doc.system_qty_milli == 5000
    assert doc.actual_qty_milli == 4200
    assert doc.diff_cost_cents == -3600
    # 实盘 - 系统账。方向写反的话数量差会变 +800 而金额仍是 -3600, 两个符号
    # 打架, 但只看单个字段的断言发现不了。
    assert doc.diff_qty_milli == -800


async def test_盘盈方向也正确():
    raw = dict(_STK, systemQty=4000, actualQty=4500, diffCost=2200)
    page = await _adapter(_page(raw)).fetch_page("stocktaking", "0", 50)
    assert page.items[0].diff_qty_milli == 500
    assert page.items[0].diff_cost_cents == 2200


async def test_三类单据各自解析成对应的dataclass():
    """跨类判别力: 白名单表把 kind 映射到解析函数, 表里两行写串了(比如
    wastage 指向 _to_requisition), 单看某一类的用例是发现不了的。"""
    expected = {"requisition": NormalizedRequisition,
                "wastage": NormalizedWastage,
                "stocktaking": NormalizedStocktaking}
    for kind, cls in expected.items():
        page = await _adapter(_page(_SAMPLE[kind])).fetch_page(kind, "0", 50)
        assert isinstance(page.items[0], cls), f"{kind} 解析成了 {type(page.items[0])}"


async def test_食材分类与单位缺失不炸_整页仍可解析():
    """category / unit 在 ops_models 里声明了默认值 = 可空。它们只影响维度
    分组粒度, 不参与金额对账, 缺了不该让整页失败。"""
    raw = {k: v for k, v in _REQ.items() if k not in ("ingredientCategory", "unit")}
    page = await _adapter(_page(raw)).fetch_page("requisition", "0", 50)
    assert page.items[0].ingredient.category is None
    assert page.items[0].ingredient.unit is None
    assert page.items[0].ingredient.name == "牛腩"


async def test_空页不是错误():
    """真的没有增量时返回空 items —— 与"业务错误"必须能区分开。"""
    page = await _adapter(_page(next_cursor=7)).fetch_page("wastage", "3", 50)
    assert page.items == [] and page.next_cursor == "7" and page.has_more is False


async def test_hasMore与游标透传():
    page = await _adapter(_page(_REQ, next_cursor=999, has_more=True)).fetch_page(
        "requisition", "0", 50)
    assert page.has_more is True and page.next_cursor == "999"


async def test_缺nextCursor时游标不前进():
    """禁降级: 报文没给 nextCursor 就原地不动, 不能猜一个值把这段增量跳过去。"""
    payload = {"code": "0", "data": {"list": [], "hasMore": False}}
    page = await _adapter(payload).fetch_page("wastage", "88", 50)
    assert page.next_cursor == "88"


# ───────────────────────── 禁降级: 业务错误码 ─────────────────────────

@pytest.mark.parametrize("kind", ["requisition", "wastage", "stocktaking"])
async def test_业务错误码被识别为失败_而不是当成空页(kind):
    """平台 HTTP 恒 200, `data` 是 None。只看 status_code 或只看 list 长度,
    这一页失败都会被当成"本轮无数据" → 框架推进游标 → 这段增量永久丢失。"""
    adapter = _adapter({"code": "AUTH_SIGN_INVALID", "message": "签名校验失败", "data": None})
    with pytest.raises(KeruyunBusinessError, match="AUTH_SIGN_INVALID"):
        await adapter.fetch_page(kind, "0", 50)


async def test_业务错误码为数字形态也被识别():
    """有的平台把 code 发成数字 1001 而不是字符串。"""
    adapter = _adapter({"code": 1001, "message": "限流", "data": None})
    with pytest.raises(KeruyunBusinessError, match="1001"):
        await adapter.fetch_page("requisition", "0", 50)


async def test_报文缺code也算失败():
    adapter = _adapter({"data": {"list": [], "nextCursor": 1, "hasMore": False}})
    with pytest.raises(KeruyunBusinessError):
        await adapter.fetch_page("requisition", "0", 50)


# ───────────────────────── 禁降级: 数值严格性 ─────────────────────────

_NUMERIC_FIELDS = [
    ("requisition", _REQ, "qty"),
    ("requisition", _REQ, "cost"),
    ("wastage", _WST, "qty"),
    ("wastage", _WST, "cost"),
    ("stocktaking", _STK, "systemQty"),
    ("stocktaking", _STK, "actualQty"),
    ("stocktaking", _STK, "diffCost"),
]


@pytest.mark.parametrize("kind,sample,field", _NUMERIC_FIELDS)
@pytest.mark.parametrize("bad", [128.5, "128.5", "12.0kg", None, True])
async def test_小数数量与金额必须报错_不许静默截断(kind, sample, field, bad):
    """裸 int(128.5) 静默给 128。数量单位是毫、金额单位是分, 截断 = 无声改写
    一笔领料成本/损耗金额, 而且没有任何留痕 —— 对账拿到的是"看起来正常"的错数。
    """
    adapter = _adapter(_page(dict(sample, **{field: bad})))
    with pytest.raises(KeruyunPayloadError):
        await adapter.fetch_page(kind, "0", 50)


@pytest.mark.parametrize("kind,sample,field", _NUMERIC_FIELDS)
async def test_整数形态的浮点与字符串仍被接受(kind, sample, field):
    """128.0 和 " 128 " 是合法的整数, 不该误伤。"""
    for good in (128, 128.0, "128", " 128 "):
        adapter = _adapter(_page(dict(sample, **{field: good})))
        page = await adapter.fetch_page(kind, "0", 50)
        assert page.items[0] is not None


async def test_负的数量与金额直接透传_不做非负校验():
    """盘点差异必须能为负; 这条同时钉住"没有人顺手给 diffCost 加非负断言"。"""
    page = await _adapter(_page(dict(_STK, diffCost=-1))).fetch_page("stocktaking", "0", 50)
    assert page.items[0].diff_cost_cents == -1


# ───────────────────────── 禁降级: 必填字段 ─────────────────────────

@pytest.mark.parametrize("kind,sample,field", [
    ("requisition", _REQ, "docNo"),
    ("requisition", _REQ, "shopCode"),
    ("requisition", _REQ, "bizDate"),
    ("requisition", _REQ, "ingredientName"),
    ("requisition", _REQ, "status"),
    ("wastage", _WST, "docNo"),
    ("wastage", _WST, "wastageType"),
    ("stocktaking", _STK, "docNo"),
    ("stocktaking", _STK, "ingredientName"),
])
async def test_必填字段缺失就报错_不填默认值(kind, sample, field):
    raw = {k: v for k, v in sample.items() if k != field}
    with pytest.raises(KeruyunPayloadError, match=field):
        await _adapter(_page(raw)).fetch_page(kind, "0", 50)


@pytest.mark.parametrize("blank", ["", "   "])
async def test_必填字段为空白也报错(blank):
    """空串单号会在 Silver 侧当 source_pk 用, 落进去就是一条谁也对不上的单。"""
    with pytest.raises(KeruyunPayloadError, match="docNo"):
        await _adapter(_page(dict(_REQ, docNo=blank))).fetch_page("requisition", "0", 50)


async def test_领料单缺status不能被默认成COMPLETED():
    """dataclass 上 status 默认 "COMPLETED", 但 Silver 只统计 COMPLETED ——
    替平台决定"这单已完成"就等于凭空把它算进领料成本。默认值是给构造对象的
    人用的, 不是给报文解析用的。"""
    raw = {k: v for k, v in _REQ.items() if k != "status"}
    with pytest.raises(KeruyunPayloadError):
        await _adapter(_page(raw)).fetch_page("requisition", "0", 50)


async def test_非COMPLETED状态原样带过来_不在adapter过滤():
    """平台给什么带什么, 由 writer 决定怎么落 —— adapter 私自丢弃在制单
    等于让上游数据凭空消失。"""
    page = await _adapter(_page(dict(_REQ, status="DRAFT"))).fetch_page(
        "requisition", "0", 50)
    assert page.items[0].status == "DRAFT"


async def test_一页里有一条坏数据就整页失败_不静默跳过():
    """禁降级: 跳过坏行会让这页看起来"成功", 框架推进游标, 坏行永远不再出现。"""
    with pytest.raises(KeruyunPayloadError):
        await _adapter(_page(_REQ, dict(_REQ, docNo="RQ2", qty=1.5))).fetch_page(
            "requisition", "0", 50)


async def test_日期格式非法就报错():
    with pytest.raises(ValueError):
        await _adapter(_page(dict(_REQ, bizDate="2026/07/29"))).fetch_page(
            "requisition", "0", 50)


# ───────────────────────── kind 白名单 ─────────────────────────

@pytest.mark.parametrize("bad_kind", [
    "order",            # 存在但不属于本 adapter
    "requisitions",     # 复数拼错
    "REQUISITION",      # 大小写
    "",
    "../order",         # 路径穿越: 透传就能把这个 adapter 指到别的接口上
])
async def test_未知单据类型直接拒绝(bad_kind):
    adapter = _adapter(_page(_REQ))
    with pytest.raises(KeruyunOpsKindError):
        await adapter.fetch_page(bad_kind, "0", 50)


async def test_未知单据类型在发请求之前就被拒():
    """一旦拼进 URL 就已经是一次真实外呼了, 再看响应发现就晚了。"""
    adapter = _adapter(_page(_REQ))
    with pytest.raises(KeruyunOpsKindError):
        await adapter.fetch_page("../order", "0", 50)
    assert adapter._client.calls == 0, "拒绝未知 kind 之前不该发出任何请求"
    assert adapter._client.last_url is None


@pytest.mark.parametrize("kind,expected_path", [
    ("requisition", "/keruyun/open/stock/requisition/list"),
    ("wastage", "/keruyun/open/stock/wastage/list"),
    ("stocktaking", "/keruyun/open/stock/stocktaking/list"),
])
async def test_每类单据打到各自的URL(kind, expected_path):
    adapter = _adapter(_page(), base="http://mock/")     # 尾斜杠必须被吃掉
    await adapter.fetch_page(kind, "0", 50)
    assert adapter._client.last_url == "http://mock" + expected_path


# ───────────────────────── 签名 ─────────────────────────

@pytest.mark.parametrize("kind", ["requisition", "wastage", "stocktaking"])
async def test_请求带上了正确的签名(kind):
    adapter = _adapter(_page(), key="kk", secret="ss")
    await adapter.fetch_page(kind, "7", 30)
    params = adapter._client.last_params
    assert params["appKey"] == "kk"
    assert params["cursor"] == "7" and params["limit"] == "30"
    assert params["timestamp"].isdigit()
    assert params["sign"] == sign(
        {k: v for k, v in params.items() if k != "sign"}, "ss")


async def test_签名用模拟端算法校验也通过():
    """跨端对拍: 用模拟平台**自己**的 keruyun_sign 复核。只和 adapter 里的
    sign() 自比是同义反复 —— 两边算法一起写错照样绿。"""
    from mock_platform.api._auth import keruyun_sign

    adapter = _adapter(_page(), key="APPKEY", secret="s3cr3t")
    await adapter.fetch_page("stocktaking", "0", 200)
    params = dict(adapter._client.last_params)
    got = params.pop("sign")
    assert got == keruyun_sign(params, "s3cr3t")


async def test_签名用的是本adapter的secret():
    """判别力: 签名如果没把 secret 真正带进去, 换个 secret 结果也不会变。"""
    a1 = _adapter(_page(), secret="s1")
    a2 = _adapter(_page(), secret="s2")
    await a1.fetch_page("requisition", "0", 50)
    await a2.fetch_page("requisition", "0", 50)
    p1, p2 = dict(a1._client.last_params), dict(a2._client.last_params)
    p2["timestamp"] = p1["timestamp"]       # 排除时间戳造成的差异
    assert sign({k: v for k, v in p1.items() if k != "sign"}, "s1") != \
        sign({k: v for k, v in p2.items() if k != "sign"}, "s2")
    assert p1["sign"] != sign(
        {k: v for k, v in p1.items() if k != "sign"}, "s2")


# ───────────────────────── 不碰 DB ─────────────────────────

def test_adapter源码不引入任何数据库依赖():
    """这个模块的职责边界是 HTTP + 解析。悄悄 import asyncpg 会把它拖进
    连接池/RLS 的生命周期里, 而它的测试全是假 client, 发现不了。"""
    src = (pathlib.Path(__file__).resolve().parents[1] / "smartbi" / "ingestion"
           / "platforms" / "ops_keruyun.py").read_text(encoding="utf-8")
    for forbidden in ("asyncpg", "import psycopg", "from .writer", "mock_platform"):
        assert forbidden not in src, f"ops_keruyun.py 不该依赖 {forbidden}"
