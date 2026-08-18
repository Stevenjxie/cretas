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
    # 判「无价格权限时正文有没有把三件事说清」(缺什么/怎么拿到/他自己要干什么)。
    # 与上面那几张同一类: **判产品自己产出的正文**, ⛔ 不是判用户输入,
    # 所以没有「换成对租户目录校验」这条出路。
    # ⚠️ 它们是**代理判据**, 真正的收敛是把面向用户的权限说明收到一处产出、
    #    契约改扫那一处 —— 见 docs/decisions/2026-08-18-契约认因权限而缺-设计卡.md
    #    「五、没做完的」第 4 条。登记是留痕, ⛔ 不是豁免。
    # 📌 `_PERMISSION_REASON_TOKENS` 只有 2 条, 低于 census 的 3 条门槛因而
    #    当前点不到名 —— 一并登记, 免得下一个人给它加第 3 个词时突然被拦。
    "_PERMISSION_REASON_TOKENS": LEXICAL,
    "_PERMISSION_REMEDY_TOKENS": LEXICAL,
    "_PERMISSION_ALTERNATIVE_TOKENS": LEXICAL,
    # ── 2026-08-18 扩到正则之后新点到名的, 逐条读过再登记 ────────────────
    #
    # ⛔ 这不是「为了让闸绿」批量豁免 —— 每一条都**读过定义**, 归到
    #    owner 划的那两类边界里(判产品自己输出的 / 单位日期归一)。
    #    剩下 87 个**没有**登记, 它们继续在棘轮里冻着。
    #
    # a) 判产品**自己输出的正文**, 不是判用户输入
    #    📏 `_comparison_present` 读的是 `baseline_label in answer_text`,
    #       `_request_coverage_present` 读的是 `"优先级" in answer_text`
    #       —— 两个的被测对象都是 `answer_text`。
    "_comparison_present": LEXICAL,
    "_request_coverage_present": LEXICAL,
    # 判产品正文里的数字有没有出处（`2026-07-14` 这类要先剔掉再算命中率）
    "_DATETIME_RE": LEXICAL,
    # 拿不到开价时产品自己给的三条建议措辞
    "_GENERIC_ACTIONS": LEXICAL,
    # `phrasing.py` 的措辞池 —— 产品自己说的话的轮转变体, ⛔ 不判用户
    "CHANNEL_MIX_CLOSING": LEXICAL,
    "DISCOUNT_CLOSING": LEXICAL,
    "DISH_MARGIN_CLOSING": LEXICAL,
    "HINT_LEAD_IN": LEXICAL,
    "STORE_SCOPE_DISCLOSURE": LEXICAL,
    "SUPPLIER_PRICE_CLOSING": LEXICAL,
    "TIME_RANGE_DISCLOSURE": LEXICAL,
    #
    # b) 单位 / 日期 / 时段的**词法归一** ——「本月」就是「本月」
    #    ⚠️ 它们确实拿用户问句在匹配, 但与 `_CALENDAR_PERIOD_TOKENS`
    #       `_DAYPART_WORDS`(早已登记 LEXICAL)同类: 这些词的意思是固定的,
    #       没有「换成查目录」这条出路。
    "_ABS_DATE_RANGE_RE": LEXICAL,
    "_ABSOLUTE_TIME_PREFIX_RE": LEXICAL,
    "_HALF_YEAR_QUARTER_RE": LEXICAL,
    "_TODAY_SO_FAR_RE": LEXICAL,
    "_EXPLICIT_TIME_RE": LEXICAL,
    "_SEMANTIC_TIME_OPTIONS": LEXICAL,
    "_SWITCHABLE_WINDOWS": LEXICAL,
    "DAYPART_ORDER": LEXICAL,
    "_relative_period_match": LEXICAL,
    #
    # ⚠️ **没有**登记的两个, 写下来免得下一个人以为是漏了:
    #    `_FALLBACK_OUTPUT_CLAUSE_RE` 名字像判产品输出, 实际匹配的是
    #        **用户问句里**的「…, 如果无法绘图就…」从句 ⇒ 判用户, 留在靶子里
    #    `_DATE_BACKREF_RE` / `_TIME_SLOT_ONLY_PATTERN` 名字像日期词法,
    #        实际是**追问指代**(「那上个月呢」) ⇒ 与 ANAPHORA 同类, 留在靶子里
}

#: 未登记的**命名**载体数量上限。⛔ 只许调小。
#:
#: 2026-08-17 建闸时 **22**（只数中文序列字面量）
#: 2026-08-18 扩到**正则 + 单串比较**后的实测值 **88**:
#:
#:     22  →  115   加正则/单串比较（含 18 个局部变量名当键的坏读数）
#:        →  108   载体名改成「模块级常量名 或 所在函数名」
#:        →   88   登记 owner 划的两类边界（判产品输出 / 日期词法）
#:
#: ⛔ 这个数**不许调得高于实测值** —— 留出的余量就是悄悄新增的空间。
#: `test_the_limit_is_not_slack` 守着它（容差 3）。
#: ⚠️ 88 里绝大多数是**存量**（`_DISH_*_RE` / `_STORE_*_RE` 那两批就占一半），
#:    它们正是这个课题的靶子。棘轮只保证**不再长**, ⛔ 不代表它们都合理。
MAX_UNREGISTERED = 88


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


# ── 🔴 2026-08-18 扩面：**正则** 与 **单串比较** ────────────────────────────
#
# 缺口是实测出来的（docs/decisions/2026-08-18-词表棘轮盲区-正则-登记.md）:
# 有人写了一个 `asks_about_store_distribution` 正则去判「有几家店」这句话
# 是什么意思, 并据此改系统行为 —— **本闸一个字都没说**, 因为
# `_cjk_seq` 只认序列字面量, 而正则是**一个字符串**。
#
#     ① _FOO = ("有几家店", "门店数量", "几个门店")     看得见 ✅
#     ② _FOO = re.compile(r"有几家店|门店数量|几个门店") 看不见 🔴
#     ③ if any(t in q for t in ("有几家店", "门店数量")) 看得见 ✅
#
# ▎闸建起来了，而它挡不住**触发它的那一类**。
#
# ⛔ 判据仍然只做机械的事: 数出来 + 打出载体名。分类走显式 `REGISTRY`,
#    ⛔ 不自动判「这是不是在判用户输入」—— 第一版分类器就是想自动判
#    「跟谁比」而漏判三个（见本文件头部）。
#    「判产品自己输出的 / SQL·日志解析 / 单位日期归一」通过**登记成 LEXICAL**
#    出列, ⛔ 不通过脚本猜。

#: 正则里的中文片段。⚠️ 量的是**片段数**不是字符数 —— `r"最近(\d+)天"`
#: 只有一个片段, 而 `r"有几家店|门店数量"` 有两个。
_CJK_RUN = re.compile(r"[一-鿿]+")

#: 一条正则要有几个中文片段才算「拿中文在匹配」。
#: ⚠️ 2 而不是 1: 单片段的绝大多数是**词法**（`r"最近(\d+)天"` 的「最近」「天」
#:    被 `(\d+)` 隔开算两段, 而 `r"^第(\d+)页"` 这种真的只有一段）。
#: ⛔ 这是**门槛**不是语义判断 —— 它回答「多小不算词表」, 不回答「它在判什么」。
_REGEX_MIN_CJK_RUNS = 2

#: 会把第一个参数当模式用的 `re` 函数。
_RE_FUNCS = frozenset({
    "compile", "search", "match", "fullmatch", "findall", "finditer",
    "sub", "subn", "split",
})


def _regex_cjk_terms(node) -> list[str] | None:
    """`re.compile(r"…中文…")` / `re.search(r"…中文…", x)` 的中文片段。

    ⚠️ 也认**动态拼接**的模式（`r"(?:%s)…(?:门店|店)" % ...`）——
       那正是本次缺口的实例长相, 它的字面量部分照样含中文。
    """
    if not isinstance(node, ast.Call):
        return None
    fn = node.func
    name = fn.attr if isinstance(fn, ast.Attribute) else (
        fn.id if isinstance(fn, ast.Name) else None)
    if name not in _RE_FUNCS or not node.args:
        return None
    pat = node.args[0]
    # 直接字面量, 或 `字面量 % (...)` / `字面量.format(...)` / f-string 的字面部分
    literals: list[str] = []
    for sub in ast.walk(pat):
        if isinstance(sub, ast.Constant) and isinstance(sub.value, str):
            literals.append(sub.value)
    if not literals:
        return None
    runs = _CJK_RUN.findall("".join(literals))
    return runs if len(runs) >= _REGEX_MIN_CJK_RUNS else None


def _cjk_membership(node) -> list[str] | None:
    """单个中文串被拿去匹配: `"有几家店" in q` / `q.startswith("哪家")`。

    ⚠️ 只认**中文串在左**的 `in`（`"哪家" in query`）——
       `query in _SOME_TOKENS` 是在查表, 那张表自己会被上面的判据数到。
    """
    if isinstance(node, ast.Compare):
        if len(node.ops) != 1 or not isinstance(node.ops[0], (ast.In, ast.NotIn)):
            return None
        left = node.left
        if (isinstance(left, ast.Constant) and isinstance(left.value, str)
                and CJK.search(left.value)):
            return [left.value]
        return None
    if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute):
        if node.func.attr not in ("startswith", "endswith") or not node.args:
            return None
        arg = node.args[0]
        vals = []
        if isinstance(arg, ast.Constant) and isinstance(arg.value, str):
            vals = [arg.value]
        elif isinstance(arg, ast.Tuple):
            vals = [e.value for e in arg.elts
                    if isinstance(e, ast.Constant) and isinstance(e.value, str)]
        vals = [v for v in vals if CJK.search(v)]
        return vals or None
    return None


class _CarrierScan(ast.NodeVisitor):
    """给每个「拿中文做匹配」的点找一个**载体名**, 好让它能被登记。

    🔴 载体名 = 具名常量名, 或**所在函数名**。
       ⚠️ 内联的没有常量名, 但它有函数名 —— 而 owner 的判据是
          「凡是拿用户问句去匹配的新增点, 词表/正则/**内联 if** 都要登记」。
          不给它一个名字就登记不了, 于是本次那个缺口的实例
          （`asks_about_store_distribution` 里内联拼的正则）**照样点不到名**。
    """

    def __init__(self, module: str):
        self.module = module
        self.stack: list[str] = []
        self.found: dict[str, tuple[str, int, int]] = {}
        self._assigned: set[int] = set()

    # -- 载体：具名常量（⛔ 只在模块级）----------------------------------
    def _visit_assign(self, tgt, val, lineno):
        if not isinstance(tgt, ast.Name):
            return
        inner = val.args[0] if (isinstance(val, ast.Call)
                                and val.func.__class__ is ast.Name
                                and val.args) else val
        terms = _regex_cjk_terms(val) or _regex_cjk_terms(inner)
        if not terms:
            return
        self._assigned.add(id(val))
        # 🔴 函数**内部**的赋值一律归给函数, ⛔ 不用局部变量名当登记键 ——
        #    `body` / `match` / `raw` / `values` 这种名字在多个文件里都有,
        #    登记一个等于**豁免全部同名的**。第一版实测点出 18 个这种名字。
        #    ▎登记键必须是能被唯一指认的东西, 否则「登记」本身就是个漏洞。
        self._record(self._carrier() or tgt.id, len(terms), lineno)

    def visit_Assign(self, node):
        if len(node.targets) == 1:
            self._visit_assign(node.targets[0], node.value, node.lineno)
        self.generic_visit(node)

    def visit_AnnAssign(self, node):
        if node.value is not None:
            self._visit_assign(node.target, node.value, node.lineno)
        self.generic_visit(node)

    # -- 载体：所在函数 --------------------------------------------------
    def _enter_func(self, node):
        self.stack.append(node.name)
        self.generic_visit(node)
        self.stack.pop()

    visit_FunctionDef = _enter_func
    visit_AsyncFunctionDef = _enter_func

    def _record(self, name: str, count: int, lineno: int):
        prev = self.found.get(name)
        self.found[name] = (self.module, (prev[1] if prev else 0) + count, lineno)

    def _carrier(self) -> str | None:
        return self.stack[-1] if self.stack else None

    def visit_Call(self, node):
        if id(node) not in self._assigned:
            terms = _regex_cjk_terms(node) or _cjk_membership(node)
            if terms and (name := self._carrier()):
                self._record(name, len(terms), node.lineno)
        self.generic_visit(node)

    def visit_Compare(self, node):
        terms = _cjk_membership(node)
        if terms and (name := self._carrier()):
            self._record(name, len(terms), node.lineno)
        self.generic_visit(node)


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
        # 🔴 正则 / 单串比较 —— 与序列字面量同样进 `named`（按载体名登记）
        scan = _CarrierScan(path.name)
        scan.visit(tree)
        for name, row in scan.found.items():
            if name in named:          # 同名已被序列字面量占用 ⇒ 合并条数
                mod, cnt, ln = named[name]
                named[name] = (mod, cnt + row[1], ln)
            else:
                named[name] = row
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
