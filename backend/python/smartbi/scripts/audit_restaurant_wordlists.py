"""餐饮读路径的手工中文词表普查。

## 为什么有这个脚本

2026-08-17 实测: 老板追问「那最差的呢」, 产品把**「最差」当成了菜名**
(`dish='最差'`), 于是按菜品维度去查, 当场拒答。

成因是 `_verbatim_entity` 只做两件事: ① 防幻觉(必须是原句子串)
② 对一张**手工黑名单**排除。「最差」两个字在原句里、不含疑问词、不在名单 ⇒ 放行。

▎那张黑名单正是本仓当晚刚**退役掉**的疑问词做法 —— 手工词表永远补不全。

⇒ 先普查: 这个形状在读路径里还有多少个。

## ⛔ 这个脚本不猜

第一版自动判「这个词表跟谁比」, 用 ±2 行上下文匹配变量名。**实测漏判三个**
(`_FOLLOWUP_PREFIXES` / `leading_dependent` / `_STORE_MENTION_STOPWORDS`
分别用 `current` / `candidate` 命名消费点), 把它们错报成「未见消费点」。

⇒ 分类改成**显式登记表**(下面的 `REGISTRY`): 它是人的判断, 写下来可被质疑、
可被替换 —— 而不是一个会同时造出假阳性和假阴性的启发式(形态 E)。
脚本只负责两件机械的事: **数出来** 和 **把消费点打出来**。

## 四类, 只有前两类该动

| 类 | 该怎么办 |
|---|---|
| `ENTITY`   判用户说的是哪个实体/范围 | 换成**对租户目录校验**(查得到/查不到), ⛔ 不是补词表 |
| `INTENT`   判用户的意图/动作 | 本来是 planner 的活; 要动得先量出 planner 在同样输入上更准 |

⚠️ **`INTENT` 这批词表【不是】抢在 LLM 前面短路的** —— 这一点实测核过, ⛔ 别照
「关键词命中就 return 一个 intent」那种直觉去改::

    门店成本怎么提高毛利   LLM 规划器被调用 1 次  authority='llm'  intent=BUSINESS_OPTIMIZATION
    这周营收怎么提升       LLM 规划器被调用 1 次  authority='llm'
    哪家店卖得最好(对照)   LLM 规划器被调用 1 次

若真有短路, 计数应为 0。而且 `BUSINESS_OPTIMIZATION` 这个判断**是 LLM 自己做的**。
最大的那张 `_OPTIMIZATION_OBJECTIVE_TOKENS`(33 条)在 `parse_restaurant_query`
的第 7011 行被消费, 而 LLM 规划器在 6885 行 —— 它在**后面**, 且作用是**反过来的**:
命中就 `return None`(= 不要反问)。

⇒ 这批词表的真实岗位是「LLM 之后追加/抑制反问、补 LLM 没给的槽」。
  退役它们要回答的问题不是「该不该让 LLM 判」(已经是 LLM 在判了), 而是
  **「LLM 判完之后, 拿什么来验」** —— 与 `ENTITY` 那批是同一个问题的两半。
| `SAFETY`   写操作/变更意图的 fail-closed 闸 | ⛔ **不许退役** —— 漏判的代价是执行写操作 |
| `LEXICAL`  天然就是词法的(时间词、餐段词) | 「本月」就是「本月」, 退役没意义 |

## 登记一张新词表时, 按这三问判它属于哪类

    ① 这些词的意思是不是固定的?        是 → LEXICAL, 它就是个词典
    ② 这件事有没有数据源能查?          有 → ENTITY, 换成查(菜名查目录 /
                                          门店查门店表 / 维度查 schema)
    ③ 判错的代价是不是一边重得多?      是 → SAFETY, 留着当保守底线, 允许它误报
    三句都不是                          → INTENT, 它在替 LLM 做判断

🔴 关键的划分**不是**「词表 vs LLM」, 是「猜 vs 查」::

    词表   用【词长什么样】猜语义    「最差」看着像名词 ⇒ 当菜名
    LLM    用【语言模型】猜语义      「最差」在这个位置像实体 ⇒ 当菜名
    目录   【查】                    菜单里有没有「最差」这道菜 ⇒ 没有

「最差」那次 **LLM 和词表犯的是同一个错, 因为它俩都在猜** —— 而且是 LLM 先判错的
(`dish` 就是 LLM 填的), 词表只是没拦住。
⇒ 把词表换成 LLM **不解决问题**。该换的对象是「查」, ⛔ 不是换一个更聪明的猜。

⚠️ 还有第四种用法, 是对的但容易被本脚本算成问题: **把清单喂给 LLM 当封闭选项集**
(把这家店的菜单塞进 prompt, 让它只能从里面选)。同样一份词的清单, **位置不同
性质完全不同** —— 事后拦 vs 事前限定。登记时按用法判, ⛔ 不按长相判。

用法::

    python -m smartbi.scripts.audit_restaurant_wordlists          # 普查 + 消费点
    python -m smartbi.scripts.audit_restaurant_wordlists --ratchet  # 只查未登记数
"""
from __future__ import annotations

import argparse
import ast
import pathlib
import re
import sys
from collections import defaultdict

ROOT = pathlib.Path(__file__).resolve().parents[1] / "gold" / "restaurant"
CJK = re.compile(r"[一-鿿]")

ENTITY, INTENT, SAFETY, LEXICAL = "ENTITY", "INTENT", "SAFETY", "LEXICAL"

#: 显式登记表。⛔ 新增词表要在这里登记, 否则被棘轮拦住。
#: 登记是**留痕**不是豁免 —— 写进 ENTITY 就等于承认它该被目录校验取代。
REGISTRY: dict[str, str] = {
    # ── 判实体/范围: 这一类该换成对租户目录校验 ──────────────────────
    "_DISH_GENERIC_TOKENS": ENTITY,
    "_DISH_TOKENS": ENTITY,
    "_DISH_SCOPE_HINTS": ENTITY,
    "_INGREDIENT_TOKENS": ENTITY,
    "_INGREDIENT_GENERIC_TOKENS": ENTITY,
    "_STORE_TOKENS": ENTITY,
    "_ALL_STORE_SCOPE_TOKENS": ENTITY,
    "_STORE_RANK_SCOPE_TOKENS": ENTITY,
    "_STORE_BREAKDOWN_SCOPE_TOKENS": ENTITY,
    "_STORE_MENTION_STOPWORDS": ENTITY,
    "_STORE_DISH_METRIC_TOKENS": ENTITY,
    "_ALL_STORE_AGG_DISH_MARGIN_METRIC_TOKENS": ENTITY,
    "_INTERROGATIVE_MARKERS": ENTITY,
    "ANAPHORA": ENTITY,
    "NOT_ANAPHORA": ENTITY,
    "EXPLICIT_SCOPE": ENTITY,
    "_KITCHEN_OPS_NOUNS": ENTITY,
    # ── 判意图/动作: planner 的活, 词表在覆盖它 ─────────────────────
    "_OPTIMIZATION_OBJECTIVE_TOKENS": INTENT,
    "_COMPARISON_DIRECTION_TOKENS": INTENT,
    "_ATTRIBUTION_CUES": INTENT,
    "_WRITE_CUES": INTENT,
    "_EXPLICIT_RANKING_NEGATION_TOKENS": INTENT,
    "_SALES_VALUE_TOKENS": INTENT,
    "_WASTAGE_COST_AXIS_TOKENS": INTENT,
    "_NEW_TOPIC_TOKENS": INTENT,
    "_FOLLOWUP_PREFIXES": INTENT,
    "leading_dependent": INTENT,
    # ── 安全闸: ⛔ 不许退役 ───────────────────────────────────────
    "_READ_ONLY_MUTATION_TOKENS": SAFETY,
    "_EXPLICIT_READ_MUTATION_TOKENS": SAFETY,
    "_HISTORICAL_MUTATION_TOKENS": SAFETY,
    # ── 天然词法 ────────────────────────────────────────────────
    "_CALENDAR_PERIOD_TOKENS": LEXICAL,
    "_DAYPART_WORDS": LEXICAL,
    # ── 判产品自己的输出(不是判用户), 与本次课题无关 ──────────────────
    "_PROFIT_VERDICT_TOKENS": LEXICAL,
    "_EXPLICIT_GAP_TOKENS": LEXICAL,
    "_MARGIN_TOKENS": LEXICAL,
    "_ANALYSIS_CANNOT_TOKENS": LEXICAL,
    "_ANALYSIS_TOPIC_TOKENS": LEXICAL,
    "_APPROVED_TIME_ANSWERS": LEXICAL,
}

#: 未登记的**命名**词表数量上限。⛔ 只许调小。
#: 2026-08-17 建闸时的实测值 —— 存量冻结, 新增必须登记。
MAX_UNREGISTERED = 22


def _cjk_seq(node) -> list[str] | None:
    """一个多数元素是中文字符串的序列字面量。"""
    if not isinstance(node, (ast.Tuple, ast.Set, ast.List)):
        return None
    vals = [e.value for e in node.elts
            if isinstance(e, ast.Constant) and isinstance(e.value, str)]
    if len(vals) < 3 or len(vals) < len(node.elts) * 0.8:
        return None
    if sum(1 for v in vals if CJK.search(v)) < len(vals) * 0.8:
        return None
    return vals


def census() -> tuple[dict[str, tuple[str, int, int]], list[tuple[str, int, int]]]:
    """返回 (命名词表, 内联无名词表)。

    ⚠️ 内联的最危险 —— 它连名字都没有, **无法被登记、被测试引用、被审**。
    """
    named: dict[str, tuple[str, int, int]] = {}
    inline: list[tuple[str, int, int]] = []
    for path in sorted(ROOT.rglob("*.py")):
        try:
            tree = ast.parse(path.read_text(encoding="utf-8"))
        except SyntaxError:
            continue
        seen = set()
        for node in ast.walk(tree):
            tgt = val = None
            if isinstance(node, ast.Assign) and len(node.targets) == 1:
                tgt, val = node.targets[0], node.value
            elif isinstance(node, ast.AnnAssign) and node.value is not None:
                tgt, val = node.target, node.value
            if not isinstance(tgt, ast.Name):
                continue
            inner = val.args[0] if isinstance(val, ast.Call) and val.args else val
            vals = _cjk_seq(inner)
            if vals:
                seen.add(id(inner))
                named[tgt.id] = (path.name, len(vals), node.lineno)
        for node in ast.walk(tree):
            vals = _cjk_seq(node)
            if vals and id(node) not in seen:
                inline.append((path.name, len(vals), node.lineno))
    return named, inline


def consumers(name: str) -> list[str]:
    """这个词表在哪里被消费 —— ⛔ 不判断「跟谁比」, 只把行打出来让人读。"""
    out = []
    for path in sorted(ROOT.rglob("*.py")):
        for i, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if name in line and not line.lstrip().startswith("#"):
                out.append(f"{path.name}:{i}: {line.strip()[:96]}")
    return out[1:]  # 第一条是定义本身


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--ratchet", action="store_true",
                    help="只查未登记数量, 超过上限退出码 1")
    args = ap.parse_args()

    named, inline = census()
    unregistered = sorted(n for n in named if n not in REGISTRY)

    if args.ratchet:
        print(f"未登记的命名词表: {len(unregistered)} / 上限 {MAX_UNREGISTERED}")
        for n in unregistered:
            mod, cnt, ln = named[n]
            print(f"  {n:<44} {cnt:>3} 条  {mod}:{ln}")
        if len(unregistered) > MAX_UNREGISTERED:
            print("\n⛔ 新增了未登记的手工词表。请在 REGISTRY 里登记它属于哪一类, "
                  "并顺手问一句: 这件事能不能改成对目录/闭集校验?")
            return 1
        return 0

    buckets: dict[str, list[tuple[str, str, int]]] = defaultdict(list)
    for name, (mod, cnt, _ln) in named.items():
        buckets[REGISTRY.get(name, "未登记")].append((name, mod, cnt))

    for kind in (ENTITY, INTENT, SAFETY, LEXICAL, "未登记"):
        rows = sorted(buckets.get(kind, []), key=lambda r: -r[2])
        if not rows:
            continue
        print(f"\n{'=' * 96}")
        print(f"== {kind} ==  {len(rows)} 个词表 / {sum(r[2] for r in rows)} 条")
        for name, mod, cnt in rows:
            print(f"  {name:<44} {cnt:>3} 条  {mod}")
            for line in consumers(name)[:1]:
                print(f"        {line}")

    print(f"\n{'=' * 96}\n== 汇总 ==")
    print(f"  命名词表   {len(named):>4} 个 / {sum(v[1] for v in named.values()):>5} 条")
    print(f"  内联无名   {len(inline):>4} 个 / {sum(v[1] for v in inline):>5} 条"
          f"   ⚠️ 无法被登记/测试引用/审计")
    print(f"  未登记     {len(unregistered):>4} 个 (上限 {MAX_UNREGISTERED})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
