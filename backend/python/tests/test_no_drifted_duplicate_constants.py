"""跨文件同名常量不许悄悄漂成两套值。

## 为什么需要这条闸

2026-08-10 做耦合点普查时实测: 全仓有 **32 个「注释写着要与别处保持同步」的硬编
集合**。抽查同名跨文件重复项, 当场抓到一个**已经漂了两个月的**:

    Java(权威)                 FOOD  = 食材 原材料 食品 饮料 酒水 菜品
    health_check_metrics.py    = 一致 ✅
    value_refresh_pipeline.py  = 食材 原料 采购 食品   🔴 少 4 个, 多 2 个

两个文件的注释**都写着「镜像同一个 Java 类」**, 却互相矛盾。后果是一条写着「酒水」
的成本在一条路上算食材成本、另一条路上不算 —— **同一个数两条路算出来不一样, 而且
没有任何东西会报警**。

判据: **手工镜像漂移时不会响。** 它安静地错, 然后以另一个模块的症状出现。
      唯一的解法是让它响 —— 这就是本文件。

## 这条闸做什么 / 不做什么

做: 扫全仓, 找**同名、跨文件、字面量集合**的常量; 值不一致就红。
不做: 判断它们「应不应该」一致 —— 那是人的判断, 写在下面的 _KNOWN_DISTINCT 里。

⛔ _KNOWN_DISTINCT 是**豁免名单**, 每条必须写清「为什么同名但确实是两个概念」。
   往里加一条之前先问: 是真的两个概念, 还是我不想修?
   2026-08-10 的教训: `_ADMIN_ROLES` 4 处 2 种值, 乍看是漂移, 读完用途才发现是
   **两个概念恰好同名**(一个是「可查任意租户」, 一个是「可管理 agent-ops 评测集」)。
   按「统一它们」去改, 会把跨租户特权授给 restaurant_manager —— **把一个不存在的
   问题修成一个真的安全漏洞**。
   判据: **同名不等于同概念; 合并两个集合之前先读它们各自的用途。**
"""
from __future__ import annotations

import ast
import collections
from pathlib import Path

_ROOT = Path(__file__).resolve().parents[1]          # backend/python
_SCAN = ("smartbi", "common")

#: 同名但**确实是两个概念**的常量 —— 每条必须说明理由。
_KNOWN_DISTINCT = {
    # 「可查任意租户」(restaurant_completeness / restaurant_llm_composite)
    # vs 「可管理 agent-ops 评测集」(agent/eval/store / api/agent_ops)。
    # 前者是跨租户数据特权, 后者是运维操作权限, 合并会把跨租户特权授给
    # restaurant_manager。
    "_ADMIN_ROLES",
    # 各 extract 脚本按各自源表的表头识别列, 表头本来就不同(zone 表多一列日期)。
    "_HEADER_MARKERS",
    # 各 seed 脚本各自声明它支持的环境, 不是同一份清单。
    "ENVS",
    "_TARGET_SOURCE_MONTHS",
    # 各 seed 脚本各自声明它要种哪些制造租户, 不是同一份清单。
    "MFG_TENANT_IDS",
    # 各物化模板按**各自源表**猜列名: 销售侧猜「实收/成交金额」, 采购侧猜
    # 「不含税金额(元)/成本金额」—— 不同数据源本来就该不同。
    "_AMOUNT_CANDIDATES", "_REVENUE_CANDIDATES", "_STATUS_CANDIDATES",
    "_PRIORITY_MEASURE_KW",
    # 工厂电池与餐饮电池各自的禁语表, 领域不同。
    "_FORBIDDEN_EVERYWHERE",
    # 「什么字符串算 true」—— 两处差一个 "on"。同一个概念, 但两处都在读各自的
    # 环境变量开关, 且都 fail-safe(认不出就当 false)。收口价值低于改动风险,
    # 留在这里是**明知而不修**, 不是没看见。
    "_TRUTHY",
}


def _literal_constants(path: Path) -> dict:
    """模块顶层的 `NAME = <字面量集合>`。非字面量(含函数调用/推导式)跳过。"""
    try:
        tree = ast.parse(path.read_text(encoding="utf-8", errors="replace"))
    except (SyntaxError, OSError):
        return {}
    out = {}
    for node in tree.body:                            # 只看顶层, 不进函数
        if not isinstance(node, ast.Assign):
            continue
        for target in node.targets:
            if not isinstance(target, ast.Name):
                continue
            name = target.id
            if not name.isupper() and not (name.startswith("_") and name[1:].isupper()):
                continue
            try:
                value = ast.literal_eval(node.value)
            except (ValueError, SyntaxError):
                try:                                   # frozenset({...}) / tuple([...])
                    value = ast.literal_eval(node.value.args[0])   # type: ignore[attr-defined]
                except Exception:                      # noqa: BLE001
                    continue
            if isinstance(value, (set, frozenset, list, tuple)) and value:
                out[name] = tuple(sorted(map(str, value)))
    return out


def test_no_duplicate_constant_has_drifted():
    seen: dict[str, list] = collections.defaultdict(list)
    for pkg in _SCAN:
        for path in (_ROOT / pkg).rglob("*.py"):
            s = str(path)
            if "__pycache__" in s or f"{pkg}/tests" in s.replace("\\", "/"):
                continue
            for name, value in _literal_constants(path).items():
                seen[name].append((path.relative_to(_ROOT).as_posix(), value))

    drifted = []
    for name, occurrences in sorted(seen.items()):
        if len(occurrences) < 2 or name in _KNOWN_DISTINCT:
            continue
        values = {v for _, v in occurrences}
        if len(values) > 1:
            detail = "\n".join(f"      {f}\n        {v}" for f, v in occurrences)
            drifted.append(f"  {name} —— {len(occurrences)} 处, {len(values)} 种值:\n{detail}")

    assert not drifted, (
        "以下同名常量在不同文件里已经漂成了不同的值。\n"
        "先读它们各自的用途: 若确实是**两个概念恰好同名**, 加进 _KNOWN_DISTINCT 并\n"
        "写明理由; 若是同一个概念漂了, 找出权威(通常是 Java 或某个登记表)并对齐。\n"
        "⛔ 不要为了让这条变绿而随便加豁免。\n\n" + "\n".join(drifted))


def test_the_scanner_actually_finds_things():
    """阴性对照: 扫描器本身得能扫到东西, 否则上面那条恒绿。

    ⚠️ 2026-08-10 同一轮里, 我的两版普查脚本各出过一次假信号 ——
       一次是 10 行注释窗口把邻近常量的说明**串台**(47 → 真实 32),
       一次是路径写错导致断言**整片 skip**(看起来 1 passed, 实际一条没跑)。
       判据: **扫描类的闸必须自带「我确实扫到了东西」的断言。**
    """
    total = 0
    for pkg in _SCAN:
        for path in (_ROOT / pkg).rglob("*.py"):
            if "__pycache__" in str(path):
                continue
            total += len(_literal_constants(path))
    assert total > 200, f"只扫到 {total} 个字面量常量, 扫描器多半坏了"
