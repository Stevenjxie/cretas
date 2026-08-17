"""后厨三张事实表要带上门店 —— 它一路带到写库前，被这一层丢了。

## 缺陷（2026-08-17 逐层实测）

老板问「哪家店损耗最多 / 哪家店缺货最严重 / 按门店看领用趋势」全被拒答。
我一度判成「数据层没有，做不到」——**判错了**。门店每一层都在：

```
餐饮平台模拟器  wastage.store_id NOT NULL REFERENCES store(id)   ✅
平台 payload    shopCode                                        ✅
归一化模型      NormalizedWastage.store_code（_require_text 必填）✅
写库 ops_writer INSERT 列清单                                    🔴 一个字没接
fact_restaurant_wastage                                         🔴 没有这一列
```

⇒ 形态 B 第 7 例（投影丢失）：产出端有、归一化层有、**消费端收不到**。
⚠️ 这与「数据缺失」的修法相反 —— 前者接线，后者补数据。搞混会去补一份
   根本不缺的数据（我差一步就去改生成器了）。

`ops_writer.py` 的 docstring 当时白纸黑字写着「三张表都没有 store_id …
要按门店看后厨得先扩 schema」—— 那句话把一件**能做的事**描述成了既定形状。
"""
import datetime

import pytest

from smartbi.ingestion.platforms.ops_models import (
    NormalizedIngredientRef,
    NormalizedRequisition,
    NormalizedStocktaking,
    NormalizedWastage,
)
from smartbi.ingestion.platforms.ops_writer import write_ops

_STORE_ID = 77


def _ref():
    return NormalizedIngredientRef(name="罗氏虾", category="水产", unit="kg")


def _wastage(doc="WS1", store="MK01"):
    return NormalizedWastage(
        platform="keruyun", doc_no=doc, store_code=store,
        biz_date=datetime.date(2026, 7, 27), ingredient=_ref(),
        wastage_type="变质", status="APPROVED",
        qty_milli=1500, cost_cents=13500,
    )


def _requisition(doc="RQ1", store="MK01"):
    return NormalizedRequisition(
        platform="keruyun", doc_no=doc, store_code=store,
        biz_date=datetime.date(2026, 7, 27), ingredient=_ref(),
        qty_milli=5000, cost_cents=45000, status="APPROVED",
    )


def _stocktaking(doc="ST1", store="MK01"):
    return NormalizedStocktaking(
        platform="keruyun", doc_no=doc, store_code=store,
        biz_date=datetime.date(2026, 7, 27), ingredient=_ref(),
        status="COMPLETED", system_qty_milli=10000,
        actual_qty_milli=9700, diff_cost_cents=-720,
    )


class _Conn:
    """只桩数据库。⚠️ 形状取自真实上游：

    · `platform_store_map` 查得到 ⇒ **一行带 store_id**（查不到是 None）
    · `dim_ingredient` UPSERT ⇒ RETURNING 一个 id
    """

    def __init__(self, store_row=None):
        self.executed = []
        self.store_lookups = []
        self._store_row = {"store_id": _STORE_ID} if store_row is None else store_row

    async def execute(self, sql, *args):
        self.executed.append((sql, args))
        return "INSERT 0 1"

    async def fetchrow(self, sql, *args):
        assert "platform_store_map" in sql
        self.store_lookups.append(args)
        return self._store_row

    async def fetchval(self, sql, *args):
        return 501

    def transaction(self):
        class _Txn:
            async def __aenter__(self_inner):
                return None

            async def __aexit__(self_inner, *exc):
                return False

        return _Txn()


class _Pool:
    def __init__(self, conn):
        self._conn = conn

    def acquire(self):
        conn = self._conn

        class _Acq:
            async def __aenter__(self_inner):
                return conn

            async def __aexit__(self_inner, *exc):
                return False

        return _Acq()


def _fact_insert(conn, table):
    return [(sql, args) for sql, args in conn.executed
            if f"INSERT INTO {table}" in sql]


@pytest.mark.asyncio
@pytest.mark.parametrize("kind,item,table", [
    ("wastage", _wastage(), "fact_restaurant_wastage"),
    ("requisition", _requisition(), "fact_restaurant_requisition"),
    ("stocktaking", _stocktaking(), "fact_restaurant_stocktaking"),
])
class TestStoreReachesSilver:
    async def test_the_insert_declares_a_store_column(self, kind, item, table):
        conn = _Conn()
        await write_ops(_Pool(conn), "MOCK_REST", kind, [item])
        inserts = _fact_insert(conn, table)
        assert inserts, f"{table} 没有被写"
        sql, _ = inserts[0]
        # ⚠️ 第一版写的是 `"store_id" in sql` —— **变异下不红**:
        #    `DO UPDATE SET store_id = EXCLUDED.store_id` 那半截也含这个词,
        #    于是列清单丢了它照样通过。⇒ 只看 `VALUES` **之前**的列清单。
        columns = sql.split("VALUES", 1)[0]
        assert "store_id" in columns, (
            f"{table} 的 INSERT 列清单里没有 store_id —— 门店又被丢了:\n{sql}"
        )

    async def test_the_resolved_store_id_is_actually_passed(self, kind, item, table):
        """⛔ 列在清单里 ≠ 值传进去了。断言**参数里真有那个 id**。"""
        conn = _Conn()
        await write_ops(_Pool(conn), "MOCK_REST", kind, [item])
        _, args = _fact_insert(conn, table)[0]
        assert _STORE_ID in args, (
            f"{table} 的参数里没有解析出来的 store_id={_STORE_ID}: {args!r}"
        )

    async def test_the_upsert_branch_refreshes_the_store_too(self, kind, item, table):
        """重放同一批单据要能把存量行的 NULL 补上 —— 这是唯一的回填路径。"""
        conn = _Conn()
        await write_ops(_Pool(conn), "MOCK_REST", kind, [item])
        sql, _ = _fact_insert(conn, table)[0]
        assert "store_id" in sql.split("DO UPDATE SET", 1)[1], (
            f"{table} 的 DO UPDATE 分支没有刷新 store_id —— "
            f"存量行永远补不上门店:\n{sql}"
        )


@pytest.mark.asyncio
class TestStoreResolutionIsSharedNotCopied:
    async def test_it_queries_the_platform_store_map(self):
        conn = _Conn()
        await write_ops(_Pool(conn), "MOCK_REST", "wastage", [_wastage()])
        assert conn.store_lookups, "根本没去查门店映射"
        args = conn.store_lookups[0]
        assert "MOCK_REST" in args and "keruyun" in args and "MK01" in args, (
            f"门店映射的查询参数不对: {args!r}"
        )

    async def test_one_lookup_per_distinct_store(self):
        """同一批里重复的门店只查一次；不同门店必须各查一次。

        ⚠️ 阴性对照在后半句：只断言「查得少」会让「所有单据共用第一家店」
           这种缺陷也通过 —— 那正是最糟的一种（把 B 店的损耗算到 A 店头上）。
        """
        conn = _Conn()
        await write_ops(_Pool(conn), "MOCK_REST", "wastage", [
            _wastage("W1", "MK01"), _wastage("W2", "MK01"),
            _wastage("W3", "MK02"),
        ])
        codes = [a[2] for a in conn.store_lookups]
        assert codes == ["MK01", "MK02"], (
            f"门店查询次数/顺序不对（应每个不同门店恰好一次）: {codes!r}"
        )

    async def test_unmapped_store_raises_instead_of_writing_null(self):
        """查不到映射就报错。⛔ 不许写 NULL 混过去 —— 那就是本缺陷本身。"""
        conn = _Conn(store_row=False)   # False ⇒ fetchrow 返回 None 的等价形状
        conn._store_row = None
        with pytest.raises(RuntimeError, match="门店映射失败"):
            await write_ops(_Pool(conn), "MOCK_REST", "wastage", [_wastage()])
        assert not _fact_insert(conn, "fact_restaurant_wastage"), (
            "门店映射失败了却还是写了事实行"
        )
