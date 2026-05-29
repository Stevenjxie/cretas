"""Classify dishes into business categories (饮品/主食/锅底/小吃/啤酒/套餐/招牌/其他).

Keyword-based rules. Extensible via add_category() for per-tenant customization.
"""
from __future__ import annotations

from typing import Dict, List

# Category → list of keywords (substring match; order matters for ambiguity)
# Built for qhj 青花椒鱼 chain — will expand for other restaurant types in W3.
_DEFAULT_CATEGORIES: Dict[str, List[str]] = {
    "啤酒": ["百威", "青岛", "喜力", "嘉士伯", "科罗娜", "燕京", "雪花", "勇闯天涯", "纯生"],
    "饮品": ["可乐", "雪碧", "芬达", "柠茶", "果茶", "果汁", "奶茶", "酸梅汤", "椰汁", "豆浆",
           "柠檬水", "矿泉水", "苏打水", "气泡水", "维他", "美年达"],
    "套餐": ["套餐", "双人餐", "三人餐", "四人餐", "家庭餐"],
    "招牌菜": ["招牌", "推荐"],  # detected ALSO via is_signature flag
    "主菜": ["鱼", "虾", "牛蛙", "牛肉", "羊肉", "鸡翅", "鸡肉", "猪肉", "排骨", "烤鸭"],
    "主食": ["饭", "面", "粉", "饼", "面条", "米线", "馒头", "饺子", "抄手"],
    "配菜": ["豆腐", "娃娃菜", "金针菇", "脆笋", "莴笋", "木耳", "粉条", "毛肚", "白菜",
           "生菜", "菠菜", "黄瓜", "海带", "土豆", "红薯"],
    "小吃": ["飞饼", "把把串", "烤串", "关东煮", "麻辣烫"],
    "调料/附加": ["打包盒", "餐位费", "外卖包装费"],
}


def classify_dish(name: str, is_signature: bool = False) -> str:
    """Return the first-matching category for a dish name, or '其他' if none."""
    if not name:
        return "其他"
    if is_signature:
        return "招牌菜"
    for cat, keywords in _DEFAULT_CATEGORIES.items():
        for kw in keywords:
            if kw in name:
                return cat
    return "其他"


def get_categories() -> List[str]:
    """All known categories in display order."""
    return list(_DEFAULT_CATEGORIES.keys()) + ["其他"]


# May 30 2026: dedicated staple/surcharge check for the 畅销菜品 Top-N filter.
# classify_dish() puts 主菜 (含 牛肉/鸡肉…) BEFORE 主食, so 牛肉面/鸡丝凉面 would
# be mis-categorised as 主菜 and slip past a category-based exclusion. This
# helper does a STAPLE-FIRST keyword scan so noodle/rice dishes that contain a
# protein word are still recognised as staples. It does NOT alter classify_dish
# (dish_category_breakdown keeps its existing 主菜-first semantics).
_STAPLE_KEYWORDS = _DEFAULT_CATEGORIES["主食"]            # 饭/面/粉/饼/米线/...
_SURCHARGE_KEYWORDS = _DEFAULT_CATEGORIES["调料/附加"]     # 打包盒/餐位费/外卖包装费


def is_staple_or_surcharge(name: str) -> bool:
    """True if a dish is a staple (米饭/面/粉/...) or a surcharge/packaging item.

    Used to drop non-菜品 entries from the 畅销菜品 Top-N ranking. Staple match
    is checked directly (not via classify_dish) so 牛肉面/扬州炒饭 are caught even
    though they contain a protein keyword.
    """
    if not name:
        return False
    if any(kw in name for kw in _SURCHARGE_KEYWORDS):
        return True
    if any(kw in name for kw in _STAPLE_KEYWORDS):
        return True
    return False
