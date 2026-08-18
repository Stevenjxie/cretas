# -*- coding: utf-8 -*-
"""22 项缺口的**当前状态**逐条实测。

⛔ 不凭印象、不从注释推断。每一项配一条机械判据 + 一条阳性对照
（会读出「全都没有」的仪器必须有对照，否则分不清真的没有 / 仪器坏了）。

判据分两类：
  AST  —— 「这个东西有没有被【调用】」，⛔ 不 grep 文本（docstring 会混进来）
  RUN  —— 「跑一次让它自己说」，用于行为类

输出三态：  ✅ 做了 / 🔴 没做 / ⚠️ 判不了(仪器不足, 单独列)
"""
from __future__ import annotations

import ast
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, ROOT)

R = os.path.join(ROOT, "smartbi", "gold", "restaurant")


def _src(name: str) -> str:
    with open(os.path.join(R, name), "r", encoding="utf-8", newline="") as fh:
        return fh.read()


def _tree(name: str):
    return ast.parse(_src(name))


def calls_of(name: str, func: str) -> int:
    """AST 数**调用**次数 —— ⛔ 不数文本出现次数（docstring/注释会混进来）。"""
    n = 0
    for node in ast.walk(_tree(name)):
        if isinstance(node, ast.Call):
            f = node.func
            if isinstance(f, ast.Name) and f.id == func:
                n += 1
            elif isinstance(f, ast.Attribute) and f.attr == func:
                n += 1
    return n


def defines(name: str, func: str) -> bool:
    return any(isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef)) and n.name == func
               for n in ast.walk(_tree(name)))


def imports_module(name: str, needle: str) -> bool:
    for node in ast.walk(_tree(name)):
        if isinstance(node, ast.ImportFrom) and needle in (node.module or ""):
            return True
        if isinstance(node, ast.Import):
            for a in node.names:
                if needle in a.name:
                    return True
    return False


def has_param(name: str, func: str, param: str) -> bool:
    for node in ast.walk(_tree(name)):
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name == func:
            args = node.args
            names = ([a.arg for a in args.args] + [a.arg for a in args.kwonlyargs]
                     + [a.arg for a in args.posonlyargs])
            return param in names
    return False


FILES = ("restaurant_intent.py", "restaurant_intent_service.py",
         "restaurant_ops_router.py")

ROWS = []


def rec(no, title, ok, evidence):
    ROWS.append((no, title, ok, evidence))


# ── 阳性对照：仪器本身活着吗 ────────────────────────────────────────────────
CONTROL = []
CONTROL.append(("AST 能数到调用",
                calls_of("restaurant_ops_router.py", "_markdown_table") > 0,
                "_markdown_table 调用 %d 次" % calls_of("restaurant_ops_router.py",
                                                        "_markdown_table")))
CONTROL.append(("AST 能认出 def",
                defines("restaurant_ops_router.py", "resolve_channel_mix"),
                "resolve_channel_mix 已定义"))
CONTROL.append(("AST 能认出形参",
                has_param("restaurant_ops_router.py", "resolve_daypart_performance",
                          "dimensions"),
                "resolve_daypart_performance(dimensions=…) 存在"))
CONTROL.append(("阴性对照：不存在的名字数出 0",
                calls_of("restaurant_ops_router.py", "__不存在的函数__") == 0,
                "拿一个必然不存在的名字，数出 0"))

# ── 1 菜单目录接进核对 ─────────────────────────────────────────────────────
_cat_calls = sum(calls_of(f, "_catalogue_says_not_a_dish") for f in FILES)
_where = {f: calls_of(f, "_catalogue_says_not_a_dish") for f in FILES}
rec(1, "菜单目录接进 dish 槽位核对",
    _where.get("restaurant_intent.py", 0) > 0,
    "_catalogue_says_not_a_dish 调用点: %s（总 %d）" % (_where, _cat_calls))

# ── 2 绝对日期核对「原话里有没有」 ────────────────────────────────────────
_d = any(defines(f, n) for f in FILES
         for n in ("_absolute_date_in_user_wording", "_date_backed_by_user_wording"))
rec(2, "绝对日期核对「原话里有没有」", _d,
    "找不到 _absolute_date_in_user_wording / _date_backed_by_user_wording"
    if not _d else "已定义")

# ── 3 实体向量检索（门店/菜品模糊匹配） ───────────────────────────────────
_ent = any(imports_module(f, "entity_resolution") for f in FILES)
rec(3, "实体向量检索接进读路径", _ent,
    "三个读路径文件里 import entity_resolution = %s" % _ent)

# ── 4 菜单 + 能力表走检索进 prompt ────────────────────────────────────────
_p = _src("restaurant_intent.py")
_full_store = "只能从这里选择，禁止编造" in _p
rec(4, "菜单/能力表走检索（⛔ 不全量塞 prompt）", not _full_store,
    "prompt 里仍有「只能从这里选择，禁止编造」= 全量塞门店" if _full_store
    else "已改成检索")

# ── 5 能力表登记「为什么不能」三态 ────────────────────────────────────────
_svc = _src("restaurant_intent_service.py")
_three = all(k in _svc for k in ("没有这个数据", "没有这个维度", "没有判定标准"))
rec(5, "能力表「为什么不能」三态", _three,
    "三个态的措辞在 service 里都能找到" if _three
    else "三态措辞没有同时出现（找到: %s）" % [
        k for k in ("没有这个数据", "没有这个维度", "没有判定标准") if k in _svc])

# ── 6 冲突批量收集 + 一次性重试 ───────────────────────────────────────────
_retry = any(defines(f, n) for f in FILES
             for n in ("_collect_all_conflicts", "_retry_with_conflicts"))
rec(6, "冲突批量收集 + 一次性重试", _retry,
    "找不到 _collect_all_conflicts / _retry_with_conflicts" if not _retry else "已定义")

# ── 7 prompt 加「信息不足必须 clarify + 列候选维度」 ──────────────────────
_clar = ("信息不足" in _p and "clarify" in _p.lower())
rec(7, "prompt 要求信息不足必须 clarify", _clar,
    "prompt 里同时有「信息不足」和 clarify = %s" % _clar)

# ── 8 反问一次问全 + 按钮 + 拼完整句重走 ─────────────────────────────────
_rewalk = any(defines(f, n) for f in FILES
              for n in ("_rebuild_full_sentence", "_compose_full_query"))
rec(8, "反问回答后拼完整句整句重走", _rewalk,
    "找不到 _rebuild_full_sentence / _compose_full_query" if not _rewalk else "已定义")

# ── 9 dimensions 传进执行器 ───────────────────────────────────────────────
_resolvers = [n.name for n in ast.walk(_tree("restaurant_ops_router.py"))
              if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef))
              and n.name.startswith("resolve_")]
_with_dims = [r for r in _resolvers
              if has_param("restaurant_ops_router.py", r, "dimensions")]
rec(9, "dimensions 传进执行器", len(_with_dims) > 0,
    "%d/%d 个 resolver 收 dimensions: %s" % (
        len(_with_dims), len(_resolvers), sorted(_with_dims)))

# ── 11 飞轮 B：拒答队列 ───────────────────────────────────────────────────
_fb = any(defines(f, n) for f in FILES
          for n in ("record_refusal", "enqueue_refusal", "log_refusal_reason"))
rec(11, "飞轮 B（拒答队列 分类+频次）", _fb,
    "找不到 record_refusal / enqueue_refusal / log_refusal_reason" if not _fb
    else "已定义")

# ── 13 实体命中后替换成库里规范名 + 抬头显示全名 ─────────────────────────
_norm = any(defines(f, n) for f in FILES
            for n in ("_canonicalize_entity", "_normalize_to_catalogue_name"))
rec(13, "实体归一成库里规范名", _norm,
    "找不到 _canonicalize_entity / _normalize_to_catalogue_name" if not _norm
    else "已定义")

# ── 14 部分能做时给部分结果 ───────────────────────────────────────────────
_partial = any(defines(f, n) for f in FILES
               for n in ("_partial_answer", "_serve_what_we_can"))
rec(14, "部分能做时给部分结果", _partial,
    "找不到 _partial_answer / _serve_what_we_can" if not _partial else "已定义")

# ── 19 计算式指代：先算再核对 ─────────────────────────────────────────────
_calc = any(defines(f, n) for f in FILES
            for n in ("_computed_reference", "_resolve_computed_entity"))
rec(19, "计算式指代先算再核对", _calc,
    "找不到 _computed_reference / _resolve_computed_entity" if not _calc else "已定义")

# ── 20 答案契约认「因权限而缺」一态 ───────────────────────────────────────
try:
    ac = open(os.path.join(R, "answer_contract.py"), "r",
              encoding="utf-8", newline="").read()
except OSError:
    ac = ""
_perm = ("权限" in ac) or ("price_view" in ac) or ("can_see_money" in ac)
rec(20, "答案契约认「因权限而缺」", _perm,
    "answer_contract.py 里提到权限/价格视角 = %s（文件 %d 字节）" % (_perm, len(ac)))

# ── 22 反问最多两轮 + 时间给按钮 ─────────────────────────────────────────
_btn = any(("clarification_options" in _src(f)) for f in FILES)
_two = any(("反问" in _src(f) and "两轮" in _src(f)) for f in FILES)
rec(22, "反问最多两轮 + 时间给按钮", _btn and _two,
    "clarification_options 存在=%s / 「两轮」上限存在=%s" % (_btn, _two))


def main() -> int:
    print("=" * 78)
    print("阳性/阴性对照（仪器活着吗）")
    print("=" * 78)
    bad = 0
    for name, ok, ev in CONTROL:
        print("  %s %-28s %s" % ("✅" if ok else "🔴", name, ev))
        if not ok:
            bad += 1
    if bad:
        print("\nrc=2 仪器对照没过 —— 本次读数作废")
        return 2

    print()
    print("=" * 78)
    print("22 项缺口 · 逐条实测（只覆盖能机械判定的那些）")
    print("=" * 78)
    done = 0
    for no, title, ok, ev in sorted(ROWS):
        print("  %2d %s %-32s %s" % (no, "✅" if ok else "🔴", title, ev))
        done += bool(ok)
    print("\n  机械判定覆盖 %d 项，其中 ✅ %d / 🔴 %d" % (len(ROWS), done, len(ROWS) - done))
    print("\n  ⚠️ 未覆盖（判不了，需要跑行为或人读）:")
    covered = {r[0] for r in ROWS}
    for no in range(1, 23):
        if no not in covered:
            print("      %2d" % no)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
