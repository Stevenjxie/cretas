"""「哪家店晚市最好」—— 门店 × 时段本来就能算，产品却说算不出。

## 为什么加这个能力（📏 prod 实测 2026-08-18）

老板问「哪家店晚市最好」（他拿它做的决定：晚市要不要给某家店加人），
拿到的是一句**假话**：

    按门店我能算，但你这句还要求再按餐段拆一层，
    **这两层的数不在同一张表上**，拆不出来。

```
fact_pos_transaction                 99792 行
  store_id 为 NULL 的行 = 0          meal_period 为 NULL 的行 = 0
  门店 × 时段 非空组合 = 20          (10 店 × 午市/晚市)
```

两层就在**这一张表**上。本 resolver 连 `meal_period` 列都不读 ——
它从**同一张表**的时间戳现算，那样两层比「同表」更同源。

⇒ 分两步：`_dimension_gap_advice` 那句假话先删（改成说算法），
这里补上**真能出**的那一半，然后才补登 `_RESOLVER_DIMENSIONS`。
⛔ 顺序不能反：「补的依据是它真能出，写宽了下游会拿它当承诺」
（该文件自己立的程序，2026-08-09 补 `meal_period` / 08-17 补 `wastage_type` 同一道）。

## ⚠️ 这一组必须走**真实 resolver**

PR #2812 的变异 C4（调用点不传 `all_total`）在 7 条只调 helper 的用例上**全绿** ——
「测了 helper，没测接线」。所以这里只桩掉数据库，其余全走真的。
"""
from __future__ import annotations

import pytest

from smartbi.gold.restaurant.restaurant_ops_router import (
    resolve_daypart_performance,
)

#: 价格角色（能看金额）。⛔ 不是 owner —— `PRICE_VIEW_ROLES` 不含它。
_BOSS = "restaurant_manager"
_KITCHEN = "kitchen_staff"

#: 形态 B‴：桩的形状要能在生产上出现。这里取自实测 —— 10 店 × 午市/晚市，
#: 每店每时段都有单。缩到 3 店以保持用例可读。
_STORE_ROWS = [
    {"store_name": "陆家嘴正大店", "daypart": "晚市", "bills": 4037, "revenue": 1461700.10},
    {"store_name": "陆家嘴正大店", "daypart": "午市", "bills": 2100, "revenue": 700000.00},
    {"store_name": "徐汇美罗城店", "daypart": "晚市", "bills": 4037, "revenue": 1456843.36},
    {"store_name": "徐汇美罗城店", "daypart": "午市", "bills": 2050, "revenue": 690000.00},
    {"store_name": "打浦桥日月光店", "daypart": "晚市", "bills": 3437, "revenue": 1234601.38},
    {"store_name": "打浦桥日月光店", "daypart": "午市", "bills": 1900, "revenue": 640000.00},
]

_DAYPART_ROWS = [
    {"daypart": "晚市", "bills": 11511, "revenue": 4153144.84,
     "guests": 23000, "window_start": "2026-07-20", "window_end": "2026-08-18"},
    {"daypart": "午市", "bills": 6050, "revenue": 2030000.00,
     "guests": 12000, "window_start": "2026-07-20", "window_end": "2026-08-18"},
]


def _pool(daypart_rows=None, store_rows=None, untimed=0):
    dp = _DAYPART_ROWS if daypart_rows is None else daypart_rows
    st = _STORE_ROWS if store_rows is None else store_rows

    class _Conn:
        async def execute(self, sql, *a):
            return "SET"

        async def fetch(self, sql, *a):
            # 门店那条查询是**唯一** JOIN dim_store 的
            if "dim_store" in sql:
                return list(st)
            return list(dp)

        async def fetchrow(self, sql, *a):
            return {"n": untimed}

        def transaction(self):
            class _Tx:
                async def __aenter__(self_inner):
                    return None

                async def __aexit__(self_inner, *exc):
                    return False

            return _Tx()

    class _Pool:
        def acquire(self):
            class _Ctx:
                async def __aenter__(self_inner):
                    return _Conn()

                async def __aexit__(self_inner, *exc):
                    return False

            return _Ctx()

    return _Pool()


async def _answer(dimensions=(), role=_BOSS, **kw):
    return await resolve_daypart_performance(
        _pool(**kw), "MOCK_REST", days=30, role=role,
        query="哪家店晚市最好", dimensions=dimensions)


# ── 承重 ────────────────────────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_it_splits_by_store_when_asked():
    """🔴 问了门店就要给门店 —— 这是整条改动的理由。"""
    ans = await _answer(dimensions=("store",))
    text = ans.answer_text
    for name in ("陆家嘴正大店", "徐汇美罗城店", "打浦桥日月光店"):
        assert name in text, f"门店 {name} 没出现\n{text}"


@pytest.mark.asyncio
async def test_it_names_the_best_store_in_the_top_daypart():
    """老板要的是**一句能拿去做决定的话**，不是让他从表里找。

    （与 PR #2805 的门店首段同一条判据：数据在、答案不在，等于没答。）
    """
    ans = await _answer(dimensions=("store",))
    head = ans.answer_text.split("| 门店")[0]
    assert "晚市" in head, "首段没说是哪个时段\n" + head
    assert "陆家嘴正大店" in head, "首段没点名最强的门店\n" + head
    assert "相差" in head, "首段没给差距 —— 老板判断不了值不值得单独管\n" + head


@pytest.mark.asyncio
async def test_the_cross_table_has_one_column_per_daypart():
    """交叉表：行=门店，列=各时段。⛔ 不替老板挑时段（他问的「晚市」是个**值**）。"""
    ans = await _answer(dimensions=("store",))
    text = ans.answer_text
    assert "晚市营收" in text and "午市营收" in text, (
        "交叉表没有按时段分列 —— 那就退回成一张单时段表了\n" + text
    )


@pytest.mark.asyncio
async def test_price_role_sees_money_as_money():
    """阳性对照：价格角色的门店表里，金额得**长得像钱**。

    ⚠️ 补写的：变异「`_fmt` 无条件用不带 ¥ 的格式」在原来那批用例上**全绿** ——
       所有 RBAC 断言都在问「非价格角色**看不看得到**」，一条都没问
       「价格角色**看到的是不是钱**」。阴性对照齐了，阳性对照缺了。
    """
    ans = await _answer(dimensions=("store",), role=_BOSS)
    text = ans.answer_text
    assert "晚市营收" in text, "价格角色的列头不是营收\n" + text
    assert "¥1,461,700" in text, (
        "价格角色的门店表里金额没带货币符号 —— 那是一串没有单位的数\n" + text
    )


@pytest.mark.asyncio
async def test_the_table_is_sorted_by_the_same_slot_the_lead_names():
    """🔴 首段点名的店必须是表格第一行 —— 两者按**同一个口径**排。

    📏 prod 实测（2026-08-18，表格原来按**总计**排）：

        首段:       按门店看晚市：陆家嘴正大店最强 ¥1,480,432
        表格第一行:  徐汇美罗城店  晚市 ¥1,470,600 / 午市 ¥724,470

    徐汇总计 2,195,070 > 陆家嘴 2,188,797，而**晚市**陆家嘴更高。
    两个数各自都对、口径也都标了 —— ⛔ 但让老板去核对为什么对不上，
    花的就是「这东西说的话能信」那笔本钱。

    ⚠️ 桩里特意做成**两个口径会给出不同第一名**：
        徐汇  晚市 1,456,843 + 午市 900,000 = 2,356,843  ← 总计最高
        陆家嘴 晚市 1,461,700 + 午市 700,000 = 2,161,700  ← 晚市最高
    ⛔ 不这样构造的话，这条断言在两种实现下都绿（形态 B′：恒真式）。
    """
    skewed = [
        {"store_name": "陆家嘴正大店", "daypart": "晚市", "bills": 4037,
         "revenue": 1461700.10},
        {"store_name": "陆家嘴正大店", "daypart": "午市", "bills": 2100,
         "revenue": 700000.00},
        {"store_name": "徐汇美罗城店", "daypart": "晚市", "bills": 4000,
         "revenue": 1456843.36},
        {"store_name": "徐汇美罗城店", "daypart": "午市", "bills": 2900,
         "revenue": 900000.00},
    ]
    ans = await _answer(dimensions=("store",), store_rows=skewed)
    text = ans.answer_text

    lead = text.split("各门店分时段")[0]
    assert "陆家嘴正大店" in lead, "首段点名的不是晚市最强那家\n" + lead

    # 表格第一条数据行的门店名
    body = text.split("各门店分时段")[1]
    data_rows = [ln for ln in body.splitlines()
                 if ln.startswith("|") and "---" not in ln]
    assert len(data_rows) >= 3, f"表格没解析出来\n{body}"
    first_store = data_rows[1].split("|")[1].strip()
    assert first_store == "陆家嘴正大店", (
        f"表格第一行是 {first_store}，而首段点名的是陆家嘴正大店 —— "
        f"两者不是同一个排序口径\n{body}"
    )


@pytest.mark.asyncio
async def test_the_table_declares_how_it_is_sorted():
    """⛔ 一张不说自己怎么排的表，读的人得自己去推。"""
    ans = await _answer(dimensions=("store",))
    assert "按晚市营收从高到低" in ans.answer_text, (
        "表格没写排序口径\n" + ans.answer_text
    )


@pytest.mark.asyncio
async def test_meta_carries_the_store_projection():
    """机器可读侧也要有 —— 正文有表而 meta 没有就是投影丢失（形态 B 第 7 例）。"""
    ans = await _answer(dimensions=("store",))
    assert ans.meta.get("by_store") is True, ans.meta
    assert ans.meta.get("store_count") == 3, ans.meta


# ── 阴性对照：没问就不给 ────────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_it_says_nothing_about_stores_when_not_asked():
    """⛔ 不问门店时**一个门店都不许出现** —— 否则每个时段问句都多一张十行表。"""
    ans = await _answer(dimensions=())
    text = ans.answer_text
    for name in ("陆家嘴正大店", "徐汇美罗城店"):
        assert name not in text, f"没问门店却出现了 {name}\n{text}"
    assert ans.meta.get("by_store") is False, ans.meta


@pytest.mark.asyncio
async def test_the_daypart_table_itself_is_unchanged():
    """阴性对照：原来那张时段表的行为逐字不变。"""
    ans = await _answer(dimensions=())
    assert "**各时段表现（" in ans.answer_text
    assert "生意最好的是**晚市**" in ans.answer_text


# ── RBAC：金额是价格权限数据 ────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_non_price_role_gets_bills_not_money():
    """非价格角色只看单量 —— ⛔ 门店表不能成为绕过 RBAC 的新出口。

    🔴 **这条断言第一版只看 `¥`，被变异 M4 打穿。**
       M4 把表格取值改成无条件读 `revenue`，而 `can_see_money` 仍是 False ⇒
       格式化照样走「不带 ¥」那一支，于是营收数字**原样印出来只是没有货币符号**。
    ▎断言在守「有没有 ¥ 这个符号」，而被守的行为是「不许看到金额**数值**」。
    ⇒ 改成钉数值本身：该出现的是单量，营收那串数字一个都不许出现。
    """
    ans = await _answer(dimensions=("store",), role=_KITCHEN)
    text = ans.answer_text
    assert "陆家嘴正大店" in text, "非价格角色连门店都看不到就过头了\n" + text
    assert "晚市单量" in text, "非价格角色的列头不是单量\n" + text
    assert "¥" not in text, "非价格角色看到了货币符号\n" + text
    # 承重：印出来的必须是单量的数
    assert "4,037" in text, "非价格角色没看到单量\n" + text
    # 阴性：营收那串数字一个都不许出现（M4 就是从这里漏的）
    for leaked in ("1,461,700", "1461700", "1,456,843", "4,153,144"):
        assert leaked not in text, (
            f"非价格角色看到了金额数值 {leaked} —— 只是没带 ¥\n{text}"
        )


@pytest.mark.asyncio
async def test_non_price_role_ranks_by_bills():
    """排序也要按他看得见的量 —— 否则「最强」是按一个他看不见的数排的。"""
    ans = await _answer(dimensions=("store",), role=_KITCHEN)
    head = ans.answer_text.split("| 门店")[0]
    assert "陆家嘴正大店" in head, head


# ── 覆盖度：归不到店的单必须说出来 ──────────────────────────────────────────

@pytest.mark.asyncio
async def test_partial_store_coverage_is_declared():
    """🔴 门店靠 JOIN 得到，归不到店的单**必须说出来**。

    ⚠️ MOCK_REST 上 store_id 零 NULL ⇒ **prod 验不到这一条**，只能由本用例守。
       桩的形状取自 `_store_breakdown_block` docstring 记的那次真实事故
       （存量行 store_id 全 NULL，门店表只占总额的 2%）。
    """
    thin = [{"store_name": "陆家嘴正大店", "daypart": "晚市",
             "bills": 100, "revenue": 50000.00}]
    ans = await _answer(dimensions=("store",), store_rows=thin)
    assert "只覆盖了" in ans.answer_text, (
        "覆盖不全却什么都没说 —— 老板会把这张表读成全部生意\n" + ans.answer_text
    )


@pytest.mark.asyncio
async def test_full_coverage_says_nothing_extra():
    """阴性对照：覆盖完整时 ⛔ 不许出现覆盖度提示。

    ▎反目标里最重的一条：一条误发的提示，烧掉的是「这东西说的话能信」。
    """
    ans = await _answer(dimensions=("store",))
    assert "只覆盖了" not in ans.answer_text, ans.answer_text


def test_the_coverage_threshold_is_the_shared_one():
    """⛔ 阈值读同一个常量，不写第二份（形态 D：极差被算两遍那次的教训）。"""
    import inspect

    from smartbi.gold.restaurant import restaurant_ops_router as rr

    src = inspect.getsource(rr.resolve_daypart_performance)
    assert "_STORE_COVERAGE_COMPLETE_RATIO" in src, (
        "门店覆盖度阈值又写了一份字面量"
    )


# ── 接线：`resolve_by_code` 按签名过滤 kwargs ───────────────────────────────

def test_the_resolver_declares_the_dimensions_parameter():
    """🔴 `resolve_by_code` 按**签名**过滤 kwargs —— 没声明这个形参就**静默**丢掉。

    ⚠️ 这个机制在本仓造过两次事故：#2076 丢 `date_range`（答错时间窗）、
       2026-08-01 丢 `role`（钱没脱敏）。两次的症状都不是报错，是**行为不对**。
       ⇒ 这一条钉住形参真的在，⛔ 不靠「我记得加了」。
    """
    import inspect

    from smartbi.gold.restaurant import restaurant_ops_router as rr

    params = inspect.signature(rr.resolve_daypart_performance).parameters
    assert "dimensions" in params, (
        "resolver 没声明 dimensions —— 规划器算出的维度会被静默丢掉"
    )
    # 阳性对照：同一个机制守着的另外两个参数也在，说明这条断言读的是真签名
    assert "role" in params and "date_range" in params, sorted(params)


def test_the_producer_side_still_emits_dimensions():
    """接线的另一半：出口字典里必须真的有这个键。

    ⛔ 用 AST 数 `Constant`，不 grep —— 上面那段注释里就写着 `"dimensions"`
       （形态 C⁸：本仓栽过三次）。
    """
    import ast
    import inspect

    from smartbi.gold.restaurant import restaurant_intent_service as svc

    tree = ast.parse(inspect.getsource(svc._resolver_kwargs))
    keys = {
        node.value
        for node in ast.walk(tree)
        if isinstance(node, ast.Constant) and isinstance(node.value, str)
    }
    assert "dimensions" in keys, "产出端不发 dimensions —— 接了也收不到"
    # 阳性对照：这份出口本来就有的键也在
    assert "ranking_limit" in keys, sorted(keys)[:10]


# ── 能力表：登记必须与真实输出一致 ──────────────────────────────────────────

@pytest.mark.asyncio
async def test_the_capability_table_matches_what_it_really_emits():
    """🔴 能力表说服务 `store`，那就必须**真的**出得来（反过来也是）。

    ⚠️ 这是本仓反复记的形态 D 最贵的一种长相：能力表比真实能力**窄**，
       于是把能算的说成算不出（漏登 `wastage_type` 那次的原话）。
       写宽了同样糟 —— 下游会拿它当承诺。
    """
    from smartbi.gold.restaurant.restaurant_intent_service import (
        _RESOLVER_DIMENSIONS,
    )

    declared = _RESOLVER_DIMENSIONS["RESTAURANT_OPS_DAYPART_PERFORMANCE"]
    assert "store" in declared, "能力表没登记 store"

    ans = await _answer(dimensions=("store",))
    assert ans.meta.get("by_store") is True, (
        "能力表声明服务 store，而实际输出里没有门店 —— 那就是一句承诺"
    )
