"""餐饮菜品命名归一 — 改进 1

继承 shared.AliasNormalizer, 加餐饮专属规则层.

设计依据 (来自 200K 青花椒订单分析):
  - 39 个"招牌青花椒"变体 80%+ 可被规则层归并
  - 6 个米饭变体: '米饭' / '#米饭#' / '米饭#' / '米饭(单人份)#' / ...
  - 14 个冰粉变体: '经典红糖冰粉#' / '小份手工冰粉' / '自助冰粉(小份)' / ...

规则层覆盖的 5 类后缀:
  1. 外层 #...# 包装 (大众点评/美团外卖菜品命名特征)
  2. 份量后缀: (单人份) / (2-3人份) / [小份] / [大份]
  3. 口味后缀: (微麻微辣) / (微辣) / (中辣)
  4. 吃法后缀: (一吃) / (两吃)
  5. 制作方式后缀: [活鱼现做] / [手工去刺] / [小心鱼刺]

不合并的情况 (NP-hard, 需客户审核):
  - "招牌青花椒鱼" vs "招牌青花椒烤鱼煲" (烤鱼煲是另一道菜)
  - "招牌青花椒味" vs "招牌青花椒鱼" (味/鱼可能是同/异)
  - "红烧牛肉" vs "红烧牛腩" (字面相似但是两道菜)
"""
from __future__ import annotations

import logging
import re
from typing import Optional

from sqlalchemy.orm import Session

from shared.alias_normalizer import AliasNormalizer

logger = logging.getLogger(__name__)


class RestaurantMenuNormalizer(AliasNormalizer):
    """餐饮菜品命名归一器

    使用示例:
        >>> normalizer = RestaurantMenuNormalizer(factory_id="F001", db_session=db)
        >>> proposals = normalizer.propose_merges([
        ...     "#招牌青花椒鱼(微麻微辣)(一吃)#",
        ...     "招牌青花椒鱼(微麻微辣)(两吃)",
        ...     "招牌青花椒鱼[小份]",
        ...     "招牌青花椒鱼[大份活鱼现做]",
        ...     "#招牌青花椒烤鱼煲(微麻微辣)#",  # 不会被合并
        ... ])
        >>> assert proposals[0].canonical_name_suggested == "招牌青花椒鱼"
        >>> assert "烤鱼煲" not in str(proposals[0].original_names)
    """

    # ── 餐饮专属规则 (5 类后缀) ─────────────────────────

    # 1. 外层 #...# 包装 — 大众点评/美团外卖特征
    OUTER_HASH_PATTERN = re.compile(r"^#(.+?)#?$")

    # 2. 份量后缀
    PORTION_SUFFIX_PATTERNS = [
        re.compile(r"\(单人份\)"),
        re.compile(r"（单人份）"),  # 全角
        re.compile(r"\(双人份\)"),
        re.compile(r"（双人份）"),
        re.compile(r"\([0-9]+-[0-9]+人份\)"),    # (2-3人份)
        re.compile(r"（[0-9]+-[0-9]+人份）"),    # 全角
        re.compile(r"\([0-9]+人份\)"),
        re.compile(r"（[0-9]+人份）"),
        re.compile(r"\[小份\]"),
        re.compile(r"\[大份\]"),
        re.compile(r"\[中份\]"),
        re.compile(r"\(小份\)"),
        re.compile(r"\(大份\)"),
        re.compile(r"（小份）"),
        re.compile(r"（大份）"),
        re.compile(r"小份$"),
        re.compile(r"大份$"),
    ]

    # 3. 口味后缀
    FLAVOR_SUFFIX_PATTERNS = [
        re.compile(r"\(微麻微辣\)"),
        re.compile(r"（微麻微辣）"),
        re.compile(r"\(微辣\)"),
        re.compile(r"（微辣）"),
        re.compile(r"\(中辣\)"),
        re.compile(r"（中辣）"),
        re.compile(r"\(特辣\)"),
        re.compile(r"（特辣）"),
        re.compile(r"\(无辣\)"),
        re.compile(r"（无辣）"),
        re.compile(r"\(变态辣\)"),
        re.compile(r"（变态辣）"),
        re.compile(r"\(不辣\)"),
        re.compile(r"（不辣）"),
    ]

    # 4. 吃法后缀
    EATING_STYLE_PATTERNS = [
        re.compile(r"（一吃）"),
        re.compile(r"\(一吃\)"),
        re.compile(r"（两吃）"),
        re.compile(r"\(两吃\)"),
        re.compile(r"（双吃）"),
        re.compile(r"\(双吃\)"),
    ]

    # 5. 制作方式后缀
    PREPARATION_PATTERNS = [
        re.compile(r"\[活鱼现做\]"),
        re.compile(r"\[现做\]"),
        re.compile(r"\[小心鱼刺\]"),
        re.compile(r"\[手工去刺\]"),
        re.compile(r"\[去刺\]"),
        re.compile(r"\[活杀\]"),
    ]

    # 所有后缀模式合集 (用于 normalize_by_rules)
    ALL_SUFFIX_PATTERNS = (
        PORTION_SUFFIX_PATTERNS
        + FLAVOR_SUFFIX_PATTERNS
        + EATING_STYLE_PATTERNS
        + PREPARATION_PATTERNS
    )

    # restaurant_dish_alias 带 status 三态列 (pending/confirmed/rejected, per
    # migration V20260728_02) —— 告知基类 _load_confirmed_aliases 加 status 过滤,
    # 避免机器 pending 候选污染 apply() 应用到线上 POS 归一结果 (per 卡3 fable 终审 B1)。
    HAS_STATUS_COLUMN = True

    def __init__(self, factory_id: str, db_session: Optional[Session] = None):
        super().__init__(factory_id=factory_id, domain="restaurant", db_session=db_session)

    # ── 基类要求实现的 3 个钩子 ──────────────────────────

    def normalize_by_rules(self, name: str) -> str:
        """规则层归一: 去 5 类后缀

        步骤:
          1. 去外层 #...# 包装
          2. 依次移除 4 类后缀 (份量/口味/吃法/制作方式)
          3. 去除剩余空格

        例:
          "#招牌青花椒鱼(微麻微辣)(一吃)#"          → "招牌青花椒鱼"
          "招牌青花椒鱼[小份活鱼现做]"               → "招牌青花椒鱼"
          "#招牌青花椒味(2-3人份)#"                  → "招牌青花椒味"
          "招牌青花椒鱼(微麻微辣)[小份小心鱼刺]"      → "招牌青花椒鱼"
          "#红糖冰粉z"                              → "红糖冰粉z" (z 是奇怪字符不去)

        Args:
            name: 原始 SKU 名

        Returns:
            归一后的名 (空字符串时返回原值)
        """
        if not name or not name.strip():
            return ""

        result = name.strip()

        # Step 1: 外层 #...# 包装
        # 注意: 外卖菜品的 # 通常成对出现, 但也有 "米饭#" / "#米饭" 这种半截的
        match = self.OUTER_HASH_PATTERN.match(result)
        if match:
            result = match.group(1).strip()
        # 单边 # 兜底
        result = result.lstrip("#").rstrip("#").strip()

        # Step 2-5: 移除所有后缀模式 (重复跑直到收敛)
        prev = ""
        max_iter = 10
        while result != prev and max_iter > 0:
            prev = result
            for pattern in self.ALL_SUFFIX_PATTERNS:
                result = pattern.sub("", result)
            result = result.strip()
            max_iter -= 1

        # 末尾的空括号清理
        result = re.sub(r"[\(\[（【].*?[\)\]）】]\s*$", "", result).strip()

        return result

    def get_alias_table_name(self) -> str:
        """餐饮使用 restaurant_dish_alias 表"""
        return "restaurant_dish_alias"

    def get_proposal_type(self) -> str:
        """餐饮归一的 proposal_type"""
        return "merge_dishes"

    # ── 餐饮专用便捷方法 ────────────────────────────────

    def propose_from_pos_data(
        self,
        df,
        product_col: str = "商品名称",
        qty_col: Optional[str] = "销售数量",
        revenue_col: Optional[str] = "销售金额",
        rules_only: bool = False,
    ) -> list:
        """从 POS 销量报表 df 直接提议合并

        便捷封装: 自动从 df 提取菜品名 + 销量/销售额 sample_data, 调用 propose_merges.

        Args:
            df: 包含菜品名列的 DataFrame
            product_col: 菜品名列名
            qty_col: 销量列 (用于 sample_data, 可选)
            revenue_col: 销售额列 (用于 sample_data, 可选)
            rules_only: 是否只跑规则层

        Returns:
            MergeProposal 列表
        """
        if product_col not in df.columns:
            logger.warning(f"propose_from_pos_data: 找不到列 {product_col!r}")
            return []

        # 提取菜品名 (去重)
        names = df[product_col].dropna().astype(str).unique().tolist()

        # 构造 sample_data
        sample_data: dict[str, dict] = {}
        if qty_col and qty_col in df.columns:
            grouped = df.groupby(product_col).agg(
                qty=(qty_col, "sum"),
            ).to_dict("index")
            for name, row_dict in grouped.items():
                sample_data.setdefault(str(name), {}).update({"qty": float(row_dict.get("qty", 0))})

        if revenue_col and revenue_col in df.columns:
            grouped = df.groupby(product_col).agg(
                revenue=(revenue_col, "sum"),
            ).to_dict("index")
            for name, row_dict in grouped.items():
                sample_data.setdefault(str(name), {}).update({"revenue": float(row_dict.get("revenue", 0))})

        return self.propose_merges(
            names=names,
            sample_data=sample_data,
            rules_only=rules_only,
        )
