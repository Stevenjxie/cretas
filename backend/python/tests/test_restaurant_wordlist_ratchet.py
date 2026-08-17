"""手工中文词表只许减少 —— 一张补不全的词表就是下一个「最差=菜名」。

## 缺陷（2026-08-17 MOCK_REST prod 实测）

老板追问「那最差的呢」，产品把**「最差」当成了菜名**（`dish='最差'`），
于是按菜品维度去查，当场拒答。

`_verbatim_entity` 只做两件事：① 防幻觉（必须是原句子串）② 对一张**手工黑名单**
排除。「最差」两个字在原句里、不含疑问词、不在名单 ⇒ 放行。

▎那张黑名单正是本仓当晚刚**退役掉**的疑问词做法。手工词表永远补不全，
▎而补它的动作看起来永远像在修 bug。

## 普查（AST，全读路径）

```
命名词表    60 个 /  595 条
内联无名   256 个 / 1580 条   ⚠️ 连名字都没有, 无法被登记/测试引用/审计
```

## ⛔ 这道闸不判「这个词表对不对」

静态分析判不出「这张表是不是该被目录校验取代」——那是人的判断。
所以闸只做一件**机械**的事：**未登记的命名词表数量只许减少**。

分类写在脚本的 `REGISTRY` 里，是**显式登记**：写进 `ENTITY` 就等于承认
它该被目录校验取代。⛔ 登记是留痕，不是豁免。

⚠️ 为什么是棘轮而不是硬红：建闸当天存量就有 22 个未登记，
一上来硬红就是一道**当天被关掉**的闸（形态 E）。

## 阳性对照

⛔ 没红过的闸不算闸。建闸时注入过一个假词表，实测 exit 1 并点名 offender：

```
未登记的命名词表: 23 / 上限 22
  _POSITIVE_CONTROL_FAKE    4 条  phrasing.py:120
```
"""
import pytest

from smartbi.scripts.audit_restaurant_wordlists import (
    ENTITY,
    MAX_UNREGISTERED,
    REGISTRY,
    SAFETY,
    census,
)


@pytest.fixture(scope="module")
def counted():
    return census()


class TestTheRatchetOnlyGoesDown:
    def test_no_new_unregistered_wordlists(self, counted):
        """🔴 承重：新增一张手工词表，必须先在 REGISTRY 里说清它属于哪一类。"""
        named, _inline = counted
        unregistered = sorted(n for n in named if n not in REGISTRY)
        assert len(unregistered) <= MAX_UNREGISTERED, (
            f"新增了未登记的手工词表（{len(unregistered)} > {MAX_UNREGISTERED}）：\n  "
            + "\n  ".join(f"{n} — {named[n][1]} 条 @ {named[n][0]}:{named[n][2]}"
                          for n in unregistered)
            + "\n请在 REGISTRY 里登记它属于哪一类，并顺手问一句："
              "这件事能不能改成对目录/闭集校验？"
        )

    def test_the_limit_is_not_slack(self, counted):
        """⛔ 上限不许比实际值高太多 —— 留出的余量就是悄悄新增的空间。"""
        named, _inline = counted
        unregistered = [n for n in named if n not in REGISTRY]
        assert MAX_UNREGISTERED - len(unregistered) <= 3, (
            f"上限 {MAX_UNREGISTERED} 比实际 {len(unregistered)} 宽出 "
            f"{MAX_UNREGISTERED - len(unregistered)} —— 把它调到实际值"
        )


class TestTheCensusActuallySeesThings:
    """🔴 阳性对照：普查器必须真的找得到东西。

    ⛔ 少了这些，一个坏掉的 `census()`（返回空）会让上面的棘轮**永远绿**，
       而它一张词表都没看过 —— 本仓记过这个形状（跑批天天绿但一个样本没看）。
    """

    def test_it_finds_the_known_lists(self, counted):
        named, _inline = counted
        for known in ("_DISH_GENERIC_TOKENS", "_CALENDAR_PERIOD_TOKENS",
                      "_READ_ONLY_MUTATION_TOKENS"):
            assert known in named, f"普查器没找到已知存在的 {known} —— 仪器坏了"

    def test_it_finds_the_inline_ones_too(self, counted):
        """内联无名的那一批是最危险的，普查必须看得见它们。"""
        _named, inline = counted
        assert len(inline) > 100, (
            f"只找到 {len(inline)} 个内联词表 —— 实测量级是三位数，仪器可能坏了"
        )


class TestTheRegistryMeansSomething:
    def test_safety_gates_are_registered_as_safety(self):
        """写操作闸 ⛔ 不许被登记成 ENTITY 顺手退役掉。

        漏判的代价是**执行一次写操作**，fail-closed 关键词在这里是正当的。
        """
        for name in ("_READ_ONLY_MUTATION_TOKENS", "_EXPLICIT_READ_MUTATION_TOKENS",
                     "_HISTORICAL_MUTATION_TOKENS"):
            assert REGISTRY.get(name) == SAFETY, (
                f"{name} 是写操作 fail-closed 闸，不该被登记成 {REGISTRY.get(name)}"
            )

    def test_the_entity_bucket_is_not_empty(self):
        """ENTITY 是本次课题的靶子 —— 它空了说明登记表被清过。"""
        entity = [n for n, k in REGISTRY.items() if k == ENTITY]
        assert len(entity) >= 10, f"ENTITY 只剩 {len(entity)} 个，登记表被改动过"

    def test_the_anaphora_lists_are_entity(self):
        """「最差」那个 bug 的直系亲属 —— 指代识别必须留在靶子里。"""
        assert REGISTRY.get("ANAPHORA") == ENTITY
        assert REGISTRY.get("NOT_ANAPHORA") == ENTITY
