"""Gold 查询必须自己保证租户上下文, 不能靠调用方碰巧设过。

## 这条用例守的是什么

`smartbi/gold/queries.py` 的表都是 FORCE RLS, 过滤依据是
`current_setting('app.factory_id')`。而 GUC 是**连接级**的 —— `pool.acquire()`
拿到的是被反复复用的连接, 上一位借用者留下的值会原样留着。

于是「不自己设 GUC 的函数」读到什么取决于**它碰巧拿到哪条连接**:
  · 上一位设过同一租户  → 正常
  · 上一位设过别的租户  → RLS 放行别人的行 ∩ SQL 的 `WHERE factory_id=$1` 放行
                          本租户的行 = **空**
  · 新建连接 / 没人设过 → 同样空

2026-08-01 之前 31 个 async 函数里**只有 3 个**自设 GUC。线上实测表现是**非确定性**:
同一 `date_range` 连跑两次, 一次 `total_revenue=34,160,545.84 / bill_count=94,862`,
一次全 0, 而同一个池上的直查始终正确。

⚠️ **返回 0 行和「真的没有数据」长得一模一样** —— 这是它躺了这么久的原因, 也是
为什么必须用**静态判据**守住, 而不能指望谁在联调时发现。

## 为什么扫源码而不是跑一个用例

跑用例只能证明「被测的那几个函数是对的」。真正的风险是**下一个新加的函数忘了** ——
那是当初 28/31 漏掉的成因。扫源码禁止裸 `pool.acquire()`, 覆盖的是「以后所有函数」。
"""
from __future__ import annotations

import inspect
import re
from pathlib import Path

import smartbi.gold.queries as queries

_SOURCE = Path(inspect.getfile(queries)).read_text(encoding="utf-8")
# helper 自己那一行必须用裸 acquire(否则自我递归), 用这个标记豁免。
_EXEMPT = "TENANT-CONN-EXEMPT"


def _code_lines():
    """去掉 docstring / 注释后的代码行, 避免把说明文字当成调用。"""
    out = []
    in_doc = False
    for i, raw in enumerate(_SOURCE.split("\n"), 1):
        stripped = raw.strip()
        # 粗略的三引号跟踪: 本模块的 docstring 都是独占行的 """ 开合
        if stripped.startswith('"""') or stripped.startswith("'''"):
            if not (len(stripped) > 3 and stripped.endswith(('"""', "'''"))):
                in_doc = not in_doc
            continue
        if in_doc or stripped.startswith("#"):
            continue
        out.append((i, raw))
    return out


def test_不得出现裸的_pool_acquire():
    """新加的查询函数必须走 tenant_conn —— 裸 acquire 会重新引入非确定性返空。"""
    offenders = [
        (i, line.strip())
        for i, line in _code_lines()
        if "pool.acquire()" in line and _EXEMPT not in line
    ]
    assert not offenders, (
        "这些地方绕开了 tenant_conn, 会拿到上一位借用者的租户上下文:\n  "
        + "\n  ".join(f"L{i}: {t}" for i, t in offenders)
        + "\n改成 `async with tenant_conn(pool, factory_id) as conn:`"
    )


def test_tenant_conn_确实设了租户上下文():
    """helper 必须真的执行 set_config, 而不只是转发连接。"""
    src = inspect.getsource(queries.tenant_conn)
    assert "set_config('app.factory_id'" in src
    assert "pool.acquire()" in src, "helper 自己得取连接"


def test_每个查询函数都拿得到_factory_id():
    """tenant_conn 需要 factory_id —— 签名里没有它的函数用不了这个 helper。

    这条挡的是「新函数忘了收 factory_id 参数, 于是又只好裸 acquire」那条退路。
    """
    missing = []
    for name, fn in vars(queries).items():
        if name.startswith("_") or not inspect.iscoroutinefunction(fn):
            continue
        if getattr(fn, "__module__", None) != queries.__name__:
            continue
        params = inspect.signature(fn).parameters
        if "pool" in params and "factory_id" not in params:
            missing.append(name)
    assert not missing, f"这些函数收了 pool 却没有 factory_id, 无法保证租户隔离: {missing}"


def test_覆盖面_至少守住当前全部查询函数():
    """如果有人把查询搬走或改名, 这条会提醒重新确认覆盖面。"""
    used = len(re.findall(r"async with tenant_conn\(pool, factory_id\) as conn:", _SOURCE))
    assert used >= 25, f"tenant_conn 使用处只剩 {used}, 远少于建立时的 29 —— 确认是不是被绕开了"
