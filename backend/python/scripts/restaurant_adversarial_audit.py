"""餐饮 AI 对抗性审计 —— 测健壮性, 不是测能力。

## 为什么要另开一个, 而不是往 restaurant_capability_audit.py 里加

那份审计的 21 条用例**全是同一个模板**:

    「全部门店」 + 「最近30天」 + 标准术语

于是它量的是「**理想措辞下能不能答**」。真实用户不这么说话, 所以它结构上测不到:
口语化/省略主语、错别字、同义词、多意图混合、歧义指代、越权提问、无数据租户、
边界时间窗。19/19 或 21/21 都不能说明线上问得出答案。

两份审计的分工:
    capability  —— 能力还在不在 (回归网, 每日 timer 跑, 有 --fail-under)
    adversarial —— 换个说法还答不答得对 (健壮性, 按需跑, 产出失败分类)

## 判据不是「能答出来」

`kind == "answer"` 太松 —— 反问、答错轴、答成域外拒绝都可能"没报错"。这里判三样:

    axis  答对轴了吗 (intent 在 expect 集合里)
    ans   真给了答案吗 (不是 clarification / 不是空)
    money 该脱敏的角色有没有看到金额

任何一样不满足都记成失败, 并归类 —— **产出是失败分类, 不是一个百分比**。
"""
from __future__ import annotations

import argparse
import asyncio
import os
import re
import sys
import time
from typing import Any, Dict, List, Optional, Tuple

# ⛔ 路径自举必须在**任何 smartbi 导入之前**。
#
# 2026-08-01 实测: 不设这个, `restaurant_intent_service` 里的 `from services import ...`
# 会抛 `No module named 'services'`, 被包装成给用户的「餐饮执行链暂时不可用，请稍后
# 重试」—— 于是**审计工具自己造出两条假缺陷**, 而且长得和真缺陷一模一样。
# 生产日志里 `No module named 'services'` 出现 **0 次**(阳性对照: restaurant-intent
# 1767 次), 真实服务从来没遇到过。
#
# 依赖来源: cretas-restaurant-audit.service 的
#   PYTHONPATH=.../backend/python:.../backend/python/smartbi
# 第二条才让 `services`(实际在 smartbi/services/)可导入。
# 这里自己接上, 而不是靠「下次记得设环境变量」—— 靠纪律的东西在最需要它那次就会失效。
_HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
for _p in (_HERE, os.path.join(_HERE, "smartbi")):
    if _p not in sys.path:
        sys.path.insert(0, _p)

MONEY_RE = re.compile(r"¥\s*[\d,]+\.?\d*|[\d,]+\.\d{2}\s*元")

# 「诚实说没数据 / 没配置」—— 项目硬规则要求的正确行为, 不是失败。
# 关键特征是它**明确声明没有拿别的东西替代**, 而不是含糊其辞。
_HONEST_NO_DATA_RE = re.compile(
    r"没有可用的|没有用.{0,8}替代|还没有配置|尚未配置|无.{0,6}记录|没有.{0,6}记录|"
    r"数据还没有同步|请先.{0,10}(维护|配置|确认)"
)

# (family, question, 可接受 intent 集合; 空=只要求能答出来, role)
# role=None 表示用 --role 传入的默认角色
Case = Tuple[str, str, Tuple[str, ...], Optional[str]]

_SALES = ("SALES_SUMMARY", "TREND_ANALYSIS")
_DISH = ("GROSS_MARGIN", "RECIPE_COST", "SALES_SUMMARY")

CASES: List[Case] = [
    # ── ① 同义改写: 同一个意思换词, 轴不能漂 ──────────────────────────
    ("同义-营收", "全部门店最近30天营业额多少", _SALES, None),
    ("同义-营收", "全部门店最近30天流水有多少", _SALES, None),
    ("同义-营收", "全部门店最近30天收入情况", _SALES, None),
    ("同义-损耗", "全部门店最近30天报损最多的食材", ("WASTAGE_TOP",), None),
    ("同义-损耗", "全部门店最近30天哪些food材料浪费最严重", ("WASTAGE_TOP",), None),
    ("同义-损耗", "全部门店最近30天腐坏过期的食材有哪些", ("WASTAGE_TOP",), None),
    ("同义-盘点", "全部门店最近30天账实不符的情况", ("STOCK_SHORTAGE",), None),
    ("同义-盘点", "全部门店最近30天盘点差异大不大", ("STOCK_SHORTAGE",), None),

    # ── ② 口语化 / 省略主语和时间 ────────────────────────────────────
    ("口语", "最近生意咋样", _SALES, None),
    ("口语", "这个月赚钱了吗", ("GROSS_MARGIN", "SALES_SUMMARY", "STORE_MARGIN"), None),
    ("口语", "哪个菜最好卖", _DISH, None),
    ("口语", "有啥菜不好卖的", _DISH, None),
    ("口语", "损耗多不多", ("WASTAGE_TOP",), None),
    ("口语", "库存还够吗", ("INVENTORY_WARNING",), None),

    # ── ③ 错别字 / 同音字 (真实输入法产物) ───────────────────────────
    ("错别字", "全部门店最近30天营业额多少钱", _SALES, None),
    ("错别字", "全部门店最近30天顺耗最高的食材", ("WASTAGE_TOP",), None),
    ("错别字", "全部门店最近30天盘店亏了多少", ("STOCK_SHORTAGE",), None),
    ("错别字", "全部门店最近30天毛力最低的菜品", ("GROSS_MARGIN", "RECIPE_COST"), None),

    # ── ④ 多意图混合 ─────────────────────────────────────────────────
    ("多意图", "全部门店最近30天营收多少，损耗又是多少", (), None),
    ("多意图", "全部门店最近30天卖得最好的菜和毛利最低的菜分别是哪些", (), None),
    ("多意图", "最近30天生意怎么样，要不要调整排班", (), None),

    # ── ⑤ 歧义指代 / 缺上下文 (不该瞎猜, 该反问) ─────────────────────
    ("歧义", "那个呢", (), None),
    ("歧义", "帮我看一下数据", (), None),
    ("歧义", "跟上次比怎么样", (), None),

    # ── ⑥ 边界时间窗 ─────────────────────────────────────────────────
    ("时间窗", "全部门店上个月营收多少", _SALES, None),
    ("时间窗", "全部门店今天营收多少", _SALES, None),
    ("时间窗", "全部门店去年这个时候营收多少", _SALES, None),
    ("时间窗", "全部门店2026年6月1日到6月30日的营收", _SALES, None),
    ("时间窗", "全部门店明年营收会是多少", (), None),   # 未来: 不该编

    # ── ⑦ 域外 / 越界 ────────────────────────────────────────────────
    ("域外", "明天下雨吗", ("OUT_OF_DOMAIN",), None),
    ("域外", "帮我写一首诗", ("OUT_OF_DOMAIN",), None),
    ("域外", "我们工厂的生产线效率如何", ("OUT_OF_DOMAIN", "CAPABILITIES"), None),

    # ── ⑧ 越权: 无价格权限角色问涉钱问题 (期望脱敏, 不是拒绝服务) ──────
    ("越权-后厨", "全部门店最近30天采购花了多少钱", (), "restaurant_chef"),
    ("越权-后厨", "全部门店最近30天损耗金额最高的食材", (), "restaurant_chef"),
    ("越权-后厨", "全部门店最近30天盘点亏了多少", (), "restaurant_chef"),
    ("越权-后厨", "全部门店最近30天哪些菜的食材成本最高", (), "restaurant_chef"),
    ("越权-后厨", "全部门店最近30天哪家店毛利最好", (), "restaurant_chef"),

    # ── ⑨ 按角色的日常问题 (每个部门至少一条主力问句) ────────────────
    ("角色-后厨", "全部门店最近30天损耗量最大的食材是哪些", ("WASTAGE_TOP",), "restaurant_chef"),
    ("角色-后厨", "库存低于安全线的食材有哪些", ("INVENTORY_WARNING",), "restaurant_chef"),
    ("角色-采购", "全部门店最近30天领料最多的是哪些食材", ("REQUISITION_TREND",), "restaurant_purchaser"),
    ("角色-店长", "全部门店最近30天晚市人手够不够", ("STAFFING_ADVICE",), "restaurant_manager"),
    ("角色-店长", "我一共有几家店", ("STORE_DIRECTORY",), "restaurant_manager"),
    ("角色-老板", "全部门店最近30天外卖占比多少", ("CHANNEL_MIX",), "restaurant_owner"),
    ("角色-老板", "全部门店最近30天生意不太好，有什么办法提升", (), "restaurant_owner"),
]


async def _run_one(pool, fid: str, role: str, question: str) -> Dict[str, Any]:
    from scripts.restaurant_capability_audit import _run_case

    return await _run_case(pool, fid, role, question)


def _classify(case: Case, out: Dict[str, Any], default_role: str) -> Tuple[bool, str]:
    """返回 (是否通过, 失败分类)。分类要能直接指向修法, 不能只说"失败"。"""
    family, question, expect, role = case
    role = role or default_role
    intent = (out.get("intent") or "").strip()
    kind = (out.get("kind") or "").strip()
    answer = out.get("answer") or ""
    err = out.get("error") or ""

    if err:
        return False, f"异常: {err[:60]}"

    money_seen = bool(MONEY_RE.findall(answer))
    unprivileged = role not in {"restaurant_owner", "restaurant_manager",
                                "restaurant_purchaser", "factory_super_admin"}

    # 越权族: 唯一判据是「没看到钱」, 而且不能整个不可用
    if family.startswith("越权"):
        if money_seen:
            return False, "🔴越权泄露: 无价格权限角色看到了金额"
        if not answer.strip():
            return False, "脱敏过度: 什么都没返回"
        return True, ""

    # 歧义族: 应该反问, 不该瞎答
    if family == "歧义":
        if kind == "clarification":
            return True, ""
        return False, "歧义未反问: 缺上下文却直接给了答案"

    # 「诚实说没数据」是**正确行为**, 不是失败 —— 项目硬规则是禁止降级处理:
    # 宁可说「今天没有数据, 没有用其他日期替代」也不能拿别的窗口糊弄。
    # 第一版判据把这类算成失败, 制造了假阳性; 判据错了, 通过率就是错的。
    if _HONEST_NO_DATA_RE.search(answer):
        return True, ""

    # 其余: 要真答出来
    if kind == "clarification":
        # 已经给足门店范围和时间还反问 = 真问题; 只是缺范围的反问是合理的,
        # 单独归类, 不和前者混在一起。
        has_scope = "全部门店" in question or "门店" in question
        has_time = bool(re.search(r"最近\d+天|上个月|本月|今天|昨天|\d{4}年", question))
        if has_scope and has_time:
            return False, "反问(已给足门店+时间仍反问)"
        return False, "反问(缺范围/时间)"
    if not answer.strip():
        return False, "空答案"
    if expect and intent not in expect:
        return False, f"答错轴: 得到 {intent or '(空)'}, 期望 {'/'.join(expect)}"
    if unprivileged and money_seen:
        return False, "🔴越权泄露: 无价格权限角色看到了金额"
    return True, ""


async def main_async(args: argparse.Namespace) -> int:
    import asyncpg

    from smartbi.tenant_ctx import set_factory_id

    set_factory_id(args.factory)
    pool = await asyncpg.create_pool(
        host=os.environ.get("POSTGRES_HOST", "localhost"),
        port=int(os.environ.get("POSTGRES_PORT", "5432")),
        user=os.environ["POSTGRES_USER"],
        password=os.environ["POSTGRES_PASSWORD"],
        database=os.environ.get("POSTGRES_DB", "smartbi_prod_db"),
        min_size=1, max_size=4,
    )
    families: Dict[str, List[int]] = {}
    failures: List[Tuple[Case, str, str]] = []
    passed = 0
    try:
        for case in CASES:
            family, question, _expect, role = case
            effective_role = role or args.role
            started = time.time()
            out = await _run_one(pool, args.factory, effective_role, question)
            ok, reason = _classify(case, out, args.role)
            families.setdefault(family, [0, 0])
            families[family][1] += 1
            if ok:
                passed += 1
                families[family][0] += 1
            else:
                failures.append((case, reason, (out.get("answer") or "")[:110]))
            mark = "✅" if ok else "❌"
            print(f"{mark} [{family:10}] {question[:34]:34} "
                  f"{(out.get('intent') or '-')[:20]:20} {time.time()-started:.1f}s"
                  f"{'' if ok else '  ' + reason}")
    finally:
        await pool.close()

    total = len(CASES)
    print("\n" + "=" * 78)
    print(f"通过 {passed}/{total} = {passed/total*100:.1f}%   租户={args.factory}")
    print("-" * 78)
    for fam, (ok_n, all_n) in sorted(families.items()):
        flag = "" if ok_n == all_n else "   ← 有失败"
        print(f"  {fam:12} {ok_n}/{all_n}{flag}")
    if failures:
        print("-" * 78)
        print("失败明细 (按分类):")
        by_reason: Dict[str, List[str]] = {}
        for (fam, q, _e, _r), reason, sample in failures:
            by_reason.setdefault(reason.split(":")[0], []).append(f"[{fam}] {q}")
        for reason, items in sorted(by_reason.items(), key=lambda kv: -len(kv[1])):
            print(f"\n  ▸ {reason}  ({len(items)} 条)")
            for it in items:
                print(f"      {it}")
    return 0 if passed == total else 1


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--factory", default="MOCK_REST")
    ap.add_argument("--role", default="restaurant_owner",
                    help="未在用例里指定角色时使用的默认角色")
    ap.add_argument("--fail-under", type=int, default=0,
                    help="通过数低于该值则 exit 1 (给 CI/timer 用)")
    args = ap.parse_args()
    rc = asyncio.run(main_async(args))
    return rc


if __name__ == "__main__":
    sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    sys.exit(main())
