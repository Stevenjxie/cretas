"""回填 fact_pos_item.product_id 的去重口径。

写库那部分靠服务器上对真实数据 dry-run + 实跑验证（本仓这类一次性脚本
的既有做法）；容易写错的是「哪些原始菜名应该合并成同一个菜」，那是纯
函数，在这里钉死。
"""
import sys
from pathlib import Path

_SCRIPTS = Path(__file__).resolve().parent.parent / "scripts"
if str(_SCRIPTS) not in sys.path:
    sys.path.insert(0, str(_SCRIPTS))

from backfill_pos_item_product_id import plan_backfill  # noqa: E402


def test_同名菜只算一个():
    mapping, skipped = plan_backfill(["米饭", "米饭"])
    assert set(mapping.values()) == {"米饭"}
    assert skipped == []


def test_标点与首尾空格差异合并成同一个菜():
    """dim_product 的唯一键是 normalized_name —— 不合并就会插出重复菜品，
    菜品排行里同一道菜会裂成两行。"""
    # 只有标点与首尾空格的差异会被抹平；括号里的**文字**会保留下来
    # （"水煮（大份）牛肉" → "水煮大份牛肉"，那是另一道菜，理应不合并）。
    mapping, _ = plan_backfill(["水煮牛肉", "水煮·牛肉", " 水煮牛肉 ", "水煮-牛肉"])
    assert len(set(mapping.values())) == 1, mapping
    assert len(mapping) == 4, "每个原始名都要有自己的映射条目（UPDATE 按原始值匹配）"


def test_括号里的文字会保留_大份小份不算同一道菜():
    mapping, _ = plan_backfill(["水煮牛肉", "水煮（大份）牛肉"])
    assert mapping["水煮（大份）牛肉"] == "水煮大份牛肉"
    assert len(set(mapping.values())) == 2


def test_菜名中间的空格不会被吃掉_是两个不同的菜():
    """⚠️ normalize_for_dim 把连续空白**压成一个空格**，不是删掉
    （`_WS_RE.sub(" ", out).strip()`）。所以「水煮 牛肉」与「水煮牛肉」
    在这套口径下是两道菜。

    这里只钉住行为、**不**在本脚本里另造一套归一化。
    ⚠️ 但别误以为「全仓都已经用它写 normalized_name」——实际上
    `canonical/normalizer.py` 现在传的是 `resolve_product(name, name)`，
    把原名原样当归一化名（那里留着 TODO）。`normalize_for_dim` 是
    migration `2026_04_28_silver_dimensions.sql` 写明的**意图**，收敛
    应该往这边走，但今天还没收敛完。详见本脚本 docstring。
    """
    mapping, _ = plan_backfill(["水煮牛肉", "水煮　牛肉"])
    assert len(set(mapping.values())) == 2
    assert mapping["水煮　牛肉"] == "水煮 牛肉", "全角空格被压成半角单空格，但仍在"


def test_不同菜不会被合并():
    mapping, _ = plan_backfill(["藤椒鸡", "水煮牛肉", "凉拌木耳"])
    assert len(set(mapping.values())) == 3


def test_归一化后为空的不猜_单独报出来():
    """禁降级: 不塞"未知菜品"，留 NULL 并让人看见。"""
    mapping, skipped = plan_backfill(["···", "  ", "藤椒鸡"])
    assert list(mapping) == ["藤椒鸡"]
    assert set(skipped) == {"···", "  "}


def test_映射按原始值建键_不是按归一化值():
    """回填 UPDATE 是 `WHERE source_item_raw = m.raw`，键必须是库里的原始值，
    用归一化值当键会一行都更新不到。"""
    mapping, _ = plan_backfill(["水煮·牛肉"])
    assert "水煮·牛肉" in mapping
    assert mapping["水煮·牛肉"] == "水煮牛肉"
