"""跨租户共享训练语料脱敏 (shared-model anonymization).

当一个真实租户授权 ``consent_scope='shared_anon'`` (跨租户共享模型) 时, 它的
(input_text → teacher_output) 对在进入**共享**训练集前必须经过本模块脱敏。

数据主权承诺 (pitch s4d):
  - 'private'      → 单租户私有模型: 原始数据直接用, **不经本模块**。
  - 'shared_anon'  → 跨租户共享模型: **必经本模块** —— 数值分桶 + 实体伪名化 +
                     保留分析形状 (按业态桶在导出层已分好)。

🔒 铁律 (no-fake-data / 不破坏分析意义):
  - **保留** 百分比 / 比率 / 占比 (``%`` / ``x.x倍`` / ``占比`` 等) —— 模型要学
    的"形状"(趋势/结构/相对关系)必须原样保留。
  - **只分桶绝对金额** (¥ / 万元 / 元 / 绝对数量) → 数量级区间 (如 "¥10-50万"),
    抹掉租户的精确财务数字, 但保留量级让模式仍可学。
  - **伪名化租户专属实体名** (门店/菜品/供应商/工厂名) → 一致占位符
    (门店A/菜品B/供应商C); 同一样本内同名映射同一占位符。
  - 输出仍是合法的分析教材: 同样的"X 比 Y 高 Z%, 集中在前 N 名"模式, 只是把
    精确值换成区间、把租户名换成占位符。

纯函数, 无 I/O, 可单测。
"""
from __future__ import annotations

import re
from typing import Dict, List, Optional, Tuple

# ---------------------------------------------------------------------------
# 1. 绝对金额分桶 (value bucketing)
# ---------------------------------------------------------------------------
#
# 把"¥1234567" / "123.4万元" / "5000元" 这类**绝对金额**替换成数量级区间。
# 百分比 / 倍数 / 占比**不动** (它们是相对量, 是要学的形状)。

# 量级区间梯度 (单位: 元)。低→高, 第一个 >= 命中即用其 label。
_AMOUNT_BUCKETS: List[Tuple[float, str]] = [
    (1_000,         "¥1千以内"),
    (10_000,        "¥1千-1万"),
    (100_000,       "¥1-10万"),
    (500_000,       "¥10-50万"),
    (1_000_000,     "¥50-100万"),
    (5_000_000,     "¥100-500万"),
    (10_000_000,    "¥500-1000万"),
    (50_000_000,    "¥1000-5000万"),
    (100_000_000,   "¥5000万-1亿"),
]
_AMOUNT_BUCKET_TOP = "¥1亿以上"


def _bucket_amount(value_yuan: float) -> str:
    """绝对金额 (元) → 量级区间 label。"""
    v = abs(value_yuan)
    for ceiling, label in _AMOUNT_BUCKETS:
        if v < ceiling:
            return label
    return _AMOUNT_BUCKET_TOP


# 匹配带货币/金额语义的数字。捕获: 前缀符号(¥/￥)、数字、可选单位(万/亿/元/万元/亿元)。
# 关键: 不匹配纯百分比 (后面跟 % 的) 和纯倍数 (后面跟 倍)。
#
# 模式说明:
#   (?:¥|￥)?          可选货币符
#   ([0-9][0-9,]*\.?[0-9]*)  数字 (允许千分位逗号 + 小数)
#   \s*(亿元|万元|亿|万|元)?  可选金额单位
# 仅在 (有货币符) 或 (有金额单位) 时才认定为"绝对金额" → 避免误伤裸数字/占比。
_AMOUNT_RE = re.compile(
    r"(?P<sym>¥|￥)?\s*"
    r"(?P<num>[0-9][0-9,]*(?:\.[0-9]+)?)"
    r"\s*(?P<unit>亿元|万元|亿|万|元)?"
)

_UNIT_MULTIPLIER = {
    "亿元": 100_000_000.0,
    "亿":   100_000_000.0,
    "万元": 10_000.0,
    "万":   10_000.0,
    "元":   1.0,
}


def _bucket_amounts_in_text(text: str) -> str:
    """把文本里的绝对金额替换为量级区间, 保留百分比/倍数等相对量。"""
    if not text:
        return text

    # 先标记出"%"和"倍"前的数字区段, 避免误伤。我们采用 finditer + 逐段判定:
    # 对每个 _AMOUNT_RE 匹配, 仅当它带货币符或金额单位时才算绝对金额。
    out: List[str] = []
    last = 0
    for m in _AMOUNT_RE.finditer(text):
        sym = m.group("sym")
        unit = m.group("unit")
        # 紧跟其后的字符: 若是 % 或 倍/折, 说明这是相对量, 不动。
        tail = text[m.end():m.end() + 1]
        is_relative_follow = tail in ("%", "％", "倍", "折")
        # 只有"带货币符"或"带金额单位"且非相对量后缀, 才是绝对金额。
        if (sym or unit) and not is_relative_follow:
            num_raw = m.group("num").replace(",", "")
            try:
                base = float(num_raw)
            except ValueError:
                continue
            mult = _UNIT_MULTIPLIER.get(unit or "", 1.0)
            yuan = base * mult
            out.append(text[last:m.start()])
            out.append(_bucket_amount(yuan))
            last = m.end()
    out.append(text[last:])
    return "".join(out)


# ---------------------------------------------------------------------------
# 2. 实体名伪名化 (label pseudonymization)
# ---------------------------------------------------------------------------
#
# 把租户专属实体名 (门店/菜品/供应商/工厂等) 替换成一致的占位符。
# 同一样本内同名映射同一占位符 (门店A/门店B/...), 跨样本不保证一致 (无需且不应)。

# 实体类别 → (识别正则, 占位符前缀)。
# 识别"〈名字〉+类别词"或"类别词:〈名字〉"等中文实体命名习惯。
# 我们捕获实体**修饰名**部分, 用占位符替换该名, 保留类别词让句子仍通顺。
_ENTITY_PATTERNS: List[Tuple[str, "re.Pattern[str]"]] = [
    # 门店 / 店: "XX店" / "XX门店" / "XX分店"
    ("门店", re.compile(r"(?P<name>[一-龥A-Za-z0-9]{1,12}?)(?P<kind>门店|分店|店)")),
    # 供应商: "XX供应商" / "供应商XX"
    ("供应商", re.compile(r"(?P<name>[一-龥A-Za-z0-9]{1,12}?)(?P<kind>供应商)")),
    # 菜品 / 产品: "XX菜品" / "XX产品" (放在门店之后, 避免吞并)
    ("菜品", re.compile(r"(?P<name>[一-龥A-Za-z0-9]{1,12}?)(?P<kind>菜品|产品)")),
    # 工厂: "XX工厂"
    ("工厂", re.compile(r"(?P<name>[一-龥A-Za-z0-9]{1,12}?)(?P<kind>工厂)")),
]

# 占位符字母序列。
_PLACEHOLDER_LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"


def _pseudonymize_entities(text: str, mapping: Dict[str, str]) -> str:
    """把租户专属实体名替换成占位符, 同一样本内 ``mapping`` 复用保证一致。

    ``mapping`` 在调用方跨 input_text / teacher_output 共享 → 同一实体在两段里
    映射同一占位符。
    """
    if not text:
        return text

    # 每个类别独立计数器 (门店A/门店B, 供应商A/供应商B)。从已有 mapping 推断。
    def _next_placeholder(kind: str) -> str:
        used = sum(1 for v in mapping.values() if v.startswith(kind))
        # used 是已分配数; 下一个字母。
        idx = used
        if idx < len(_PLACEHOLDER_LETTERS):
            return f"{kind}{_PLACEHOLDER_LETTERS[idx]}"
        return f"{kind}{idx + 1}"

    result = text
    for kind, pat in _ENTITY_PATTERNS:
        def _repl(m: "re.Match[str]") -> str:
            name = m.group("name")
            m.group("kind")
            # 名字为空 / 纯类别词 → 不动。
            if not name:
                return m.group(0)
            # 已知通用占位符前缀本身 (避免二次伪名化) → 不动。
            m.group(0)
            key = f"{kind}:{name}"
            if key not in mapping:
                mapping[key] = _next_placeholder(kind)
            # 用占位符替换"名字", 保留类别词 → "门店A" 之类已含类别, 直接用占位符。
            return mapping[key]
        result = pat.sub(_repl, result)
    return result


# ---------------------------------------------------------------------------
# 3. 公共入口
# ---------------------------------------------------------------------------


def anonymize_for_shared(
    input_text: str,
    teacher_output: str,
    business_type: Optional[str] = None,
) -> Tuple[str, str]:
    """脱敏一对 (input_text, teacher_output) 供**跨租户共享**训练集使用。

    流程 (顺序重要: 先伪名化实体, 再分桶金额):
      1. 实体伪名化 —— 门店/菜品/供应商/工厂专属名 → 占位符 (样本内一致)。
      2. 绝对金额分桶 —— ¥/万元/元 等绝对数值 → 量级区间; **保留** %/倍/占比。

    保留分析形状: 趋势、结构、相对关系 (百分比/倍数/排名) 原样保留, 只抹掉
    精确财务数字和租户身份。

    Args:
        input_text:     原始输入文本。
        teacher_output: teacher 模型原始输出。
        business_type:  业态 (restaurant/factory/...); 当前仅作签名占位, 业态桶
                        在导出层已分好, 本函数对所有业态行为一致。

    Returns:
        (anon_input, anon_output) —— 脱敏后的两段。纯函数, 无副作用。
    """
    # 跨两段共享的实体映射 → 同一实体名在 input / output 里映射同一占位符。
    shared_mapping: Dict[str, str] = {}

    anon_in = _pseudonymize_entities(input_text or "", shared_mapping)
    anon_out = _pseudonymize_entities(teacher_output or "", shared_mapping)

    anon_in = _bucket_amounts_in_text(anon_in)
    anon_out = _bucket_amounts_in_text(anon_out)

    return anon_in, anon_out
