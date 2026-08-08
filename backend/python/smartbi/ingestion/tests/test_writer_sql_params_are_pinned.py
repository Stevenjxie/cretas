"""多次复用的 SQL 参数必须显式标注类型。

🔴 存在的理由（2026-08-09 实测炸在 prod 演示租户上）：
   `fact_pos_transaction` 那条 INSERT 里 `$1` 既在 VALUES 的列位置
   （列类型 character varying），又在子查询里做 `d.factory_id = $1`。
   PostgreSQL **没有独立的 varchar 等号操作符** —— varchar 与 text 二进制兼容，
   `varchar = varchar` 实际解析成 `text = text`，于是比较处把 `$1` 推断成 text，
   列位置推断成 character varying，同一个参数两种推断，服务端直接拒绝：

       ERROR: inconsistent types deduced for parameter $1
       DETAIL: text versus character varying

   整条语句**根本 prepare 不了**，一行都写不进去。

⛔ 为什么规则是「出现多次就全部钉死」而不是「只钉会打架的」：
   静态看不出哪两种推断会冲突（要知道列类型、操作符解析、隐式转换全套规则，
   那等于把 PostgreSQL 的类型系统抄一遍）。**分不清就全钉**——多写一个 `::int`
   的代价是一行噪音，漏钉一个的代价是整条写入路径静默失效。

⚠️ 这道闸是**代理不是证明**：它只能保证参数被钉死，不能保证语句在真库上
   prepare 得过（仓里没有连真库的测试基建）。真正的证明只有一种 ——
   让写入真跑一次。判据：改了写入 SQL，必须真触发一次写入再看结果。

🔴 上面这条判据正是本次的教训：修复前那版部署到 prod 后**一次都没执行过**，
   因为当时同步游标已在末尾、没有新订单可拉。日志上是干干净净的
   `{'keruyun': 0}`，「写入路径是坏的」被「同步中，0 条」完整掩盖，
   直到重灌演示数据才炸出来。
"""
import io
import pathlib
import re

import pytest

_WRITER = pathlib.Path(__file__).resolve().parents[1] / "platforms" / "writer.py"

# 形如 $1 / $12，后面**没有**紧跟 `::`
_PARAM_UNPINNED = re.compile(r"\$(\d+)(?!\d)(?!::)")
_PARAM_ANY = re.compile(r"\$(\d+)(?!\d)")


def _sql_literals(source: str) -> list[str]:
    """把 writer.py 里拼接出来的 SQL 串还原成整条语句。

    源码里 SQL 是相邻字符串字面量隐式拼接的，逐条字面量看会把
    `$1` 和它在别处的复用切成两段，正是这道闸要看的东西。
    用 ast 取整条拼接结果，而不是按行 grep。
    """
    import ast

    tree = ast.parse(source)
    out: list[str] = []
    for node in ast.walk(tree):
        if isinstance(node, ast.Constant) and isinstance(node.value, str):
            if "$1" in node.value and "$2" in node.value:
                out.append(node.value)
    return out


def test_writer_source_is_parseable():
    """先证明我读到的是完整源码 —— 解析不了的话下面每条断言都是空转。"""
    source = io.open(_WRITER, encoding="utf-8").read()
    import ast

    ast.parse(source)
    assert len(source) > 2000, "writer.py 短得不正常，八成读错文件了"


@pytest.mark.parametrize("sql", _sql_literals(io.open(_WRITER, encoding="utf-8").read()))
def test_reused_params_carry_an_explicit_cast(sql):
    """🔴 同一语句里出现多次的参数，每一处都必须带 `::类型`。"""
    counts: dict[str, int] = {}
    for name in _PARAM_ANY.findall(sql):
        counts[name] = counts.get(name, 0) + 1
    reused = {n for n, c in counts.items() if c > 1}
    if not reused:
        pytest.skip("这条语句没有复用参数")

    unpinned = {n for n in _PARAM_UNPINNED.findall(sql) if n in reused}
    assert not unpinned, (
        f"参数 {sorted('$' + n for n in unpinned)} 在同一条语句里出现多次却没有显式类型标注。\n"
        f"PostgreSQL 会对每处独立推断，两种推断不一致就整条 prepare 失败"
        f"（inconsistent types deduced），写入路径会**一行都进不去**。\n"
        f"语句片段: {sql[:160]}..."
    )


def test_the_transaction_insert_is_actually_covered():
    """⛔ 阴性对照：上面那条参数化断言必须真的**跑在**订单 INSERT 上。

    没有这条的话，`_sql_literals` 哪天匹配不到东西，
    parametrize 会退化成 0 个用例 —— 一个用例都不跑，报告依然全绿。
    """
    sqls = _sql_literals(io.open(_WRITER, encoding="utf-8").read())
    hit = [s for s in sqls if "INSERT INTO fact_pos_transaction" in s]
    assert hit, "没有取到订单 INSERT —— 这道闸此刻什么都没在测"
    reused = [n for n in set(_PARAM_ANY.findall(hit[0]))
              if hit[0].count(f"${n}") > 1]
    assert reused, "订单 INSERT 里没有复用参数？那子查询大概被改没了，这道闸失去了对象"
