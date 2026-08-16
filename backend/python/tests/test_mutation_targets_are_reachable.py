"""被 monkeypatch 的目标必须是**模块属性访问**，不是 from-import 绑定。

## 为什么是一道闸而不是一句注释

`fill_offers.py` 顶部白纸黑字写过这一条：

> ⛔ 用**模块引用**而不是 `from ... import METRICS` —— from-import 会把名字
>   绑死在本模块上，`monkeypatch.setattr(reg, "METRICS", ...)` 够不着它。

**我在隔壁文件 `generic_answer.py` 又踩了一次**（2026-08-14，`_caveat_short_of`
用了顶部的 from-import，变异「拿掉 caveat_short」当场不生效）。
⇒ owner: **这条不要再写注释了，写成闸。**
   注释挡不住的东西，只有可执行的判据挡得住。

## 它抓什么

测试里写 `monkeypatch.setattr(<模块>, "<NAME>", ...)` 时，如果被测模块是用
`from <模块> import <NAME>` 拿的这个名字，那次 setattr **打不到**它 ——
变异不生效，而「变异没生效」和「守卫没覆盖」长得一模一样、成因相反。

## 它抓不住什么

⚠️ 只扫**同名**的 from-import。`from x import A as B` 改了名的抓不到；
   动态 `getattr` 也抓不到。
⇒ 所以每条变异**仍然**要先证明「行为真的变了」再看断言。
   这道闸是兜底，不是免检。
"""
from __future__ import annotations

import ast
import pathlib
import re

_PY_ROOT = pathlib.Path(__file__).resolve().parents[1]
_TEST_DIRS = ("tests", "smartbi/gold/tests")

#: `monkeypatch.setattr(mod, "NAME", ...)` —— 只认这一种写法（字符串字面量）。
_SETATTR = re.compile(
    r"monkeypatch\.setattr\(\s*([A-Za-z_][\w.]*)\s*,\s*[\"']([A-Za-z_]\w*)[\"']")

#: 这些模块别名 → 真实模块路径。⚠️ 测试里普遍写 `import x as y`，
#: 直接拿别名去反查会一个都对不上（那就是一道恒绿的闸）。
_ALIAS = re.compile(r"^\s*(?:from\s+([\w.]+)\s+)?import\s+([\w.]+)\s+as\s+(\w+)",
                    re.M)


def _module_file(dotted: str):
    path = _PY_ROOT / (dotted.replace(".", "/") + ".py")
    return path if path.exists() else None


def _from_imported_names(src: str):
    """这个模块用 `from X import NAME` 拿到的名字集合（同名，无 as）。"""
    out = set()
    try:
        tree = ast.parse(src)
    except SyntaxError:
        return out
    for node in ast.walk(tree):
        if isinstance(node, ast.ImportFrom):
            for alias in node.names:
                if alias.asname is None:
                    out.add(alias.name)
    return out



def _setattr_calls(src: str):
    """扫出真正的 `monkeypatch.setattr(mod, "NAME", ...)` **调用**。

    🔴 2026-08-16 换成 AST —— 原来是正则扫**原始源码**, 于是**注释和 docstring
    里提到这个写法的那一行也被数进去**。实测: 一条只在 `#:` 注释里出现的
    `monkeypatch.setattr(R, "_provider_config", ...)` 让棘轮从 25 涨到 26,
    而那个文件里一行 setattr 代码都没有。

    ⚠️ 这是本仓同形第四次(数注解不剥注释 / grep 数进 docstring / 闸把自己的
       输出也数了进去)。仓里自己的结论就是「这类闸一律走 AST, 不再收窄正则」——
       字符串计数量的是**文本**, AST 量的是**结构**, 而闸要守的从来是结构。

    ⛔ 只认字符串字面量的第二个参数(与原正则同口径), 动态名字不在本闸射程内。
    """
    try:
        tree = ast.parse(src)
    except SyntaxError:
        return
    for node in ast.walk(tree):
        if not isinstance(node, ast.Call):
            continue
        fn = node.func
        if not (isinstance(fn, ast.Attribute) and fn.attr == "setattr"):
            continue
        if not (isinstance(fn.value, ast.Name) and fn.value.id == "monkeypatch"):
            continue
        if len(node.args) < 2:
            continue
        tgt, nm = node.args[0], node.args[1]
        if not (isinstance(nm, ast.Constant) and isinstance(nm.value, str)):
            continue
        # 目标可能是 `R` 或 `pkg.mod` —— 还原成点号串, 与原正则的捕获同形
        parts = []
        cur = tgt
        while isinstance(cur, ast.Attribute):
            parts.append(cur.attr)
            cur = cur.value
        if isinstance(cur, ast.Name):
            parts.append(cur.id)
        else:
            continue
        yield ".".join(reversed(parts)), nm.value


def _iter_test_files():
    for d in _TEST_DIRS:
        base = _PY_ROOT / d
        if base.exists():
            yield from base.rglob("test_*.py")


def test_monkeypatched_names_are_module_attributes_not_from_imports():
    """🔴 被打的名字如果在**消费它的模块**里是 from-import，那次打空了。

    ⚠️ 阳性对照在下面 —— 没有它，这条断言在「一条 setattr 都没扫到」时恒绿。
    """
    # ⚠️ 索引**建一次**。第一版每条 setattr 都 rglob 一遍整棵树, 跑了 10 分钟
    #    还没完 —— 一道跑不完的闸等于没有闸(CI 会超时, 然后被人关掉)。
    consumers = {}          # (来源模块, 名字) -> [消费它的模块]
    for module in (_PY_ROOT / "smartbi").rglob("*.py"):
        if "tests" in module.parts:
            continue
        try:
            tree = ast.parse(module.read_text(encoding="utf-8", errors="ignore"))
        except (SyntaxError, ValueError):
            continue
        for node in ast.walk(tree):
            if not isinstance(node, ast.ImportFrom) or not node.module:
                continue
            for alias in node.names:
                if alias.asname is None:
                    consumers.setdefault((node.module, alias.name), []).append(
                        module.relative_to(_PY_ROOT))

    offenders = []
    scanned = 0
    for test_file in _iter_test_files():
        src = test_file.read_text(encoding="utf-8", errors="ignore")
        aliases = {a[2]: (a[0] or a[1]) for a in _ALIAS.findall(src)}
        for target, name in _setattr_calls(src):
            scanned += 1
            dotted = aliases.get(target, target)
            if _module_file(dotted) is None:
                continue                      # 打的是三方/内建模块, 不管
            for consumer in consumers.get((dotted, name), ()):
                offenders.append(
                    f"{test_file.name} 打 {dotted}.{name}，"
                    f"而 {consumer} 是 from-import 拿的它")
    assert scanned > 0, "一条 monkeypatch.setattr 都没扫到 —— 这条断言会恒绿"

    # ⚠️ 这道闸建的时候仓里**已经有 25 处**（`get_pg_pool` 之类被广泛 from-import）。
    #    一上来就硬红 = 一道当天就被关掉的闸。⇒ 做成**棘轮**: 只禁增长。
    # 🔴 棘轮量的是「**有多少个 模块.名字 打不到**」, ⛔ 不是「有多少行 setattr」——
    #    后者会因为「同一个问题被拆成几处调用」而虚高, 本仓记过这条。
    distinct = {o.split("，")[0].split("打 ")[-1] for o in offenders}
    assert len(distinct) <= _RATCHET, (
        f"🔴 打不到目标的变异从 {_RATCHET} 涨到 {len(distinct)} 处。\n"
        f"新增的那个: 被测模块用 `from X import NAME` 拿的它, "
        f"`monkeypatch.setattr(X, \"NAME\")` 够不着 —— **变异不会生效**。\n"
        f"改法: 被测模块改成 `import X as _x` + `_x.NAME`。\n  "
        + "\n  ".join(sorted(distinct)))


#: 建闸当天的存量。⛔ 只许降不许升。降到 0 之后把这个常量删掉、改成硬断言。
_RATCHET = 25


def test_the_scanner_can_actually_see_a_from_import():
    """阳性对照: 扫描器认不认得 from-import。

    ⛔ 没有它, 上面那条在「解析器坏了」时也是绿的 —— 本仓踩过
       「对照全红时先怀疑夹具」的反面。
    """
    src = "from a.b import NAME, OTHER as X\nimport c\n"
    got = _from_imported_names(src)
    assert "NAME" in got, "同名 from-import 没认出来"
    assert "OTHER" not in got, "带 as 的不该算（它没被绑死成同名）"
