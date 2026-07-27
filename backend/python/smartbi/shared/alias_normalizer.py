"""半自动命名归一通用层

餐饮菜品命名归一 + 工厂 SKU 命名归一 共用基类.
具体的规则层 (例如餐饮的 #..# 去包装) 由子类实现.

工作流:
  1. propose_merges(names) — 系统提议"疑似重名组" 写入 alias_review_queue
  2. 客户在前端审核 (由 api/audit/* 暴露)
  3. confirm_merge(proposal_id, ...) — 写入 dish_alias / sku_alias 表
  4. apply(df) — 数据加载层调用, 把原始名替换成 canonical

依赖:
  - difflib.SequenceMatcher (标准库, 不引入 fuzzywuzzy)
  - alias_review_queue 表 (共享, 通过 domain 隔离)
  - restaurant_dish_alias 表 (餐饮专有)
  - factory_sku_alias 表 (工厂 Phase 2 建)

使用示例 (餐饮子类):
    >>> normalizer = RestaurantMenuNormalizer(factory_id="F001", db_session=db)
    >>> proposals = normalizer.propose_merges([
    ...     "招牌青花椒鱼(微麻微辣)(一吃)",
    ...     "招牌青花椒鱼(微麻微辣)(两吃)",
    ...     "招牌青花椒鱼[小份]",
    ... ])
    >>> # ... 客户审核后 ...
    >>> normalizer.confirm_merge(proposals[0].proposal_id, "招牌青花椒鱼", reviewer="谢总")
"""
from __future__ import annotations

import json
import logging
from dataclasses import dataclass, field
from difflib import SequenceMatcher
from typing import Literal, Optional

from sqlalchemy import text
from sqlalchemy.orm import Session

logger = logging.getLogger(__name__)


# ── Type aliases ────────────────────────────────────────────

Domain = Literal["restaurant", "factory"]
"""隔离域: 餐饮 | 工厂"""

MergeReason = Literal[
    "rule_layer",       # 规则层匹配 (例: 去 #..# 后相同)
    "edit_distance",    # Levenshtein 相似度
    "common_prefix",    # 共同前缀
    "embedding",        # 向量相似度 (Phase 2)
    "manual",           # 客户手动建议
]


# ── Result types ────────────────────────────────────────────

@dataclass
class MergeProposal:
    """系统提议的合并组

    Attributes:
        proposal_id: alias_review_queue.id (持久化后赋值)
        canonical_name_suggested: 系统建议的规范名 (通常是最短/最常见的变体)
        original_names: 候选合并的原始名列表 (≥2)
        confidence: 合并置信度 (0-1, 1=完全肯定)
        reason: 提议来源 (rule_layer / edit_distance / ...)
        sample_data: 给客户审核用的数据片段, 例如 {"name": "...", "qty": 1234}
    """
    proposal_id: Optional[int] = None
    canonical_name_suggested: str = ""
    original_names: list[str] = field(default_factory=list)
    confidence: float = 0.0
    reason: MergeReason = "rule_layer"
    sample_data: list[dict] = field(default_factory=list)

    def to_dict(self) -> dict:
        return {
            "proposal_id": self.proposal_id,
            "canonical_name_suggested": self.canonical_name_suggested,
            "original_names": self.original_names,
            "confidence": self.confidence,
            "reason": self.reason,
            "sample_data": self.sample_data,
        }


# ── Main base class ─────────────────────────────────────────

class AliasNormalizer:
    """半自动命名归一通用基类

    子类必须实现:
      - normalize_by_rules(name: str) -> str  规则层归一 (返回去后缀的名字)
      - get_alias_table_name() -> str         返回别名表名 (例: 'restaurant_dish_alias')
      - get_proposal_type() -> str            返回 proposal_type 值 ('merge_dishes' 或 'merge_skus')

    子类可选覆写:
      - HAS_STATUS_COLUMN: bool               别名表是否带 status 三态列
        (pending/confirmed/rejected, per migration V20260728_02)。默认 False。
        ``_load_confirmed_aliases`` 据此决定是否加 status 过滤 —— 没有 status 列的
        别名表 (例如工厂侧尚未建的 factory_sku_alias) 加这个 WHERE 子句会直接报
        UndefinedColumn, 故按子类显式开关走, 不做"猜列存在"的隐式探测。
        ⛔ 目前只有 RestaurantMenuNormalizer (对应 restaurant_dish_alias) 应设为 True
        (per 卡3 fable 终审 B1: 老读路径不过滤 status, 机器 pending 候选会污染线上
        POS 归一结果 — 与 migration 头注 + resolver 承诺的 fail-closed 矛盾)。
    """

    HAS_STATUS_COLUMN: bool = False

    def __init__(
        self,
        factory_id: str,
        domain: Domain,
        db_session: Optional[Session] = None,
    ):
        if domain not in ("restaurant", "factory"):
            raise ValueError(
                f"domain 必须是 'restaurant' 或 'factory', 收到 {domain!r}. "
                f"跨 domain 命名归一会污染数据, 拒绝执行."
            )
        if not factory_id:
            raise ValueError("factory_id 不能为空")

        self.factory_id = factory_id
        self.domain: Domain = domain
        self.db = db_session

        # 已确认的别名缓存 (apply() 时用)
        self._alias_cache: Optional[dict[str, str]] = None

    # ── 必须由子类实现 ──────────────────────────────────

    def normalize_by_rules(self, name: str) -> str:
        """规则层归一 — 子类实现

        例 (餐饮): "#招牌青花椒鱼(微麻微辣)(一吃)#" → "招牌青花椒鱼"

        Args:
            name: 原始名

        Returns:
            规则层归一后的名 (子类负责去除已知后缀)
        """
        raise NotImplementedError("子类必须实现 normalize_by_rules")

    def get_alias_table_name(self) -> str:
        """返回别名表名 — 子类实现

        Returns:
            'restaurant_dish_alias' 或 'factory_sku_alias' (Phase 2)
        """
        raise NotImplementedError("子类必须实现 get_alias_table_name")

    def get_proposal_type(self) -> str:
        """返回 alias_review_queue.proposal_type 值 — 子类实现

        Returns:
            'merge_dishes' (餐饮) 或 'merge_skus' (工厂)
        """
        raise NotImplementedError("子类必须实现 get_proposal_type")

    # ── Step 1: 系统主动提议 ────────────────────────────

    def propose_merges(
        self,
        names: list[str],
        sample_data: Optional[dict[str, dict]] = None,
        rules_only: bool = False,
        edit_distance_threshold: float = 0.85,
        common_prefix_min_chars: int = 4,
        min_group_size: int = 2,
        persist: bool = True,
    ) -> list[MergeProposal]:
        """系统提议"疑似重名组"

        算法分两层:
          Layer 1 (规则层): 用 normalize_by_rules() 归一, 同归一结果 → 同组
          Layer 2 (相似度): 在剩余名上用 SequenceMatcher + 共同前缀

        Args:
            names: 原始名列表 (会自动去重)
            sample_data: 可选的样本数据 {original_name: {qty: 1234, revenue: 5678}}, 用于审核 UI
            rules_only: True = 只跑规则层 (快, 解决简单情况)
            edit_distance_threshold: Levenshtein 相似度阈值 (0-1)
            common_prefix_min_chars: 共同前缀最少字符数
            min_group_size: 最少几个名字才能成一组 (默认 2)
            persist: True = 写入 alias_review_queue (默认), False = 仅返回不持久化

        Returns:
            合并提议列表, 已按 confidence 降序

        说明:
            已经在 alias 表里的原始名会被自动跳过, 避免重复提议.
        """
        # 去重 + 排除已确认的别名
        unique_names = list(dict.fromkeys(n for n in names if n and n.strip()))
        confirmed_aliases = self._load_confirmed_aliases()
        unique_names = [n for n in unique_names if n not in confirmed_aliases]

        if not unique_names:
            return []

        proposals: list[MergeProposal] = []

        # ── Layer 1: 规则层归一 ───
        groups_by_normalized: dict[str, list[str]] = {}
        for name in unique_names:
            normalized = self.normalize_by_rules(name)
            if normalized:
                groups_by_normalized.setdefault(normalized, []).append(name)

        consumed: set[str] = set()
        for normalized, originals in groups_by_normalized.items():
            if len(originals) >= min_group_size:
                proposal = MergeProposal(
                    canonical_name_suggested=normalized,
                    original_names=sorted(originals),
                    confidence=0.95,  # 规则层匹配置信度高
                    reason="rule_layer",
                    sample_data=self._build_sample_data(originals, sample_data),
                )
                proposals.append(proposal)
                consumed.update(originals)

        # ── Layer 2: 相似度匹配 (剩余名) ───
        if not rules_only:
            remaining = [n for n in unique_names if n not in consumed]
            similarity_groups = self._cluster_by_similarity(
                remaining,
                threshold=edit_distance_threshold,
                min_prefix=common_prefix_min_chars,
            )
            for group in similarity_groups:
                if len(group) >= min_group_size:
                    canonical = self._pick_canonical_name(group)
                    proposal = MergeProposal(
                        canonical_name_suggested=canonical,
                        original_names=sorted(group),
                        confidence=0.75,  # 相似度匹配置信度中等
                        reason="edit_distance",
                        sample_data=self._build_sample_data(group, sample_data),
                    )
                    proposals.append(proposal)

        # 按 confidence 降序
        proposals.sort(key=lambda p: -p.confidence)

        # 持久化到 alias_review_queue
        if persist and proposals and self.db is not None:
            self._persist_proposals(proposals)

        logger.info(
            f"[{self.domain}] propose_merges: {len(unique_names)} names → "
            f"{len(proposals)} proposals (rule={sum(1 for p in proposals if p.reason == 'rule_layer')}, "
            f"sim={sum(1 for p in proposals if p.reason == 'edit_distance')})"
        )
        return proposals

    # ── Step 2: 客户确认 ───────────────────────────────

    def confirm_merge(
        self,
        proposal_id: int,
        canonical_name: str,
        merged_originals: list[str],
        reviewer: str,
    ) -> None:
        """客户审核通过, 写入别名表 + 更新 review_queue 状态

        Args:
            proposal_id: alias_review_queue.id
            canonical_name: 客户最终确认的规范名 (可能跟系统建议的不同)
            merged_originals: 客户最终选择合并的原始名列表 (可能跟系统建议的不同)
            reviewer: 审核人

        副作用:
            1. 写入 alias 表 (restaurant_dish_alias 或 factory_sku_alias)
            2. 更新 alias_review_queue.status = 'approved' / 'modified'
            3. 清空 _alias_cache 强制下次重新加载
        """
        if self.db is None:
            raise RuntimeError("无 db_session, 无法持久化")
        if not canonical_name or not canonical_name.strip():
            raise ValueError("canonical_name 不能为空")
        if not merged_originals:
            raise ValueError("merged_originals 不能为空")

        alias_table = self.get_alias_table_name()
        original_col = "original_name"
        canonical_col = "canonical_name"

        # 1. 写入别名表 (UPSERT, 处理重复 review)
        for original in merged_originals:
            self.db.execute(
                text(f"""
                    INSERT INTO {alias_table}
                        (factory_id, {original_col}, {canonical_col},
                         confidence, review_source, reviewed_by, reviewed_at, created_at)
                    VALUES
                        (:fid, :orig, :canon, 1.0, :src, :reviewer, NOW(), NOW())
                    ON CONFLICT (factory_id, {original_col})
                    DO UPDATE SET
                        {canonical_col} = EXCLUDED.{canonical_col},
                        reviewed_by = EXCLUDED.reviewed_by,
                        reviewed_at = NOW()
                """),
                {
                    "fid": self.factory_id,
                    "orig": original,
                    "canon": canonical_name,
                    "src": "manual",
                    "reviewer": reviewer,
                },
            )

        # 2. 判断是 approved 还是 modified
        proposal_row = self.db.execute(
            text("SELECT proposal_data FROM alias_review_queue WHERE id = :id AND factory_id = :fid AND domain = :d"),
            {"id": proposal_id, "fid": self.factory_id, "d": self.domain},
        ).fetchone()

        status = "approved"
        if proposal_row:
            original_proposal = proposal_row.proposal_data
            suggested_canonical = original_proposal.get("canonical_name_suggested", "")
            suggested_originals = set(original_proposal.get("original_names", []))
            if (canonical_name != suggested_canonical
                    or set(merged_originals) != suggested_originals):
                status = "modified"

        # 3. 更新 review_queue
        self.db.execute(
            text("""
                UPDATE alias_review_queue
                SET status = :status,
                    reviewed_by = :reviewer,
                    reviewed_at = NOW(),
                    decision_data = CAST(:decision AS JSONB)
                WHERE id = :id AND factory_id = :fid AND domain = :d
            """),
            {
                "status": status,
                "reviewer": reviewer,
                "decision": json.dumps({
                    "canonical_name": canonical_name,
                    "merged_originals": merged_originals,
                }, ensure_ascii=False),
                "id": proposal_id,
                "fid": self.factory_id,
                "d": self.domain,
            },
        )
        self.db.commit()
        self._alias_cache = None  # 强制重新加载

        logger.info(
            f"[{self.domain}] merge confirmed: '{canonical_name}' ← {len(merged_originals)} originals "
            f"(reviewer={reviewer}, status={status})"
        )

    def reject_merge(
        self,
        proposal_id: int,
        reviewer: str,
        reason: Optional[str] = None,
    ) -> None:
        """客户拒绝合并, 记录原因

        系统记住后, 不再为相同的原始名组合再次提议.
        """
        if self.db is None:
            raise RuntimeError("无 db_session")

        self.db.execute(
            text("""
                UPDATE alias_review_queue
                SET status = 'rejected',
                    reviewed_by = :reviewer,
                    reviewed_at = NOW(),
                    decision_data = CAST(:decision AS JSONB)
                WHERE id = :id AND factory_id = :fid AND domain = :d
            """),
            {
                "reviewer": reviewer,
                "decision": json.dumps({"reject_reason": reason or ""}, ensure_ascii=False),
                "id": proposal_id,
                "fid": self.factory_id,
                "d": self.domain,
            },
        )
        self.db.commit()
        logger.info(f"[{self.domain}] merge rejected: id={proposal_id} (reviewer={reviewer})")

    # ── Step 3: 数据加载层 apply ────────────────────────

    def apply(self, df, name_column: str):
        """把 df[name_column] 列的原始名替换成 canonical 名

        关键: 这个方法应该在 analyzer 之前调用, 让所有 sibling 分析共享同一份归一后 df.

        Args:
            df: pandas DataFrame
            name_column: 要归一的列名 (例: '商品名称' / 'sku_name')

        Returns:
            原 df (in-place 修改 + 返回, 方便链式调用)

        说明:
            没有别名映射的原始名保持不变.
        """
        aliases = self._load_confirmed_aliases()
        if not aliases:
            return df  # 没有任何确认的别名, 不做任何替换

        if name_column not in df.columns:
            logger.warning(f"apply() 找不到列 {name_column!r}, 跳过")
            return df

        replaced_count = 0
        original_unique_count = df[name_column].nunique()

        def _replace(name):
            nonlocal replaced_count
            if name in aliases:
                replaced_count += 1
                return aliases[name]
            return name

        df[name_column] = df[name_column].map(_replace)
        new_unique_count = df[name_column].nunique()

        logger.info(
            f"[{self.domain}] apply: {name_column} 列 "
            f"{original_unique_count} → {new_unique_count} 唯一名 "
            f"({replaced_count} 行被替换)"
        )
        return df

    # ── 内部辅助方法 ───────────────────────────────────

    def _load_confirmed_aliases(self) -> dict[str, str]:
        """从别名表加载所有已确认的 {original: canonical} 映射 (带缓存)

        ⛔ 只加载已确认的行 —— pending (机器初匹配候选, 尚未人审, per
        dish_alias_matcher.propose_dish_alias_candidates) 绝不能进这份缓存, 否则
        apply() 会拿它去改写线上 POS 归一结果 (违反 migration V20260728_02 头注 +
        dish_alias_resolver 承诺的"pending 绝不影响线上答案" fail-closed 原则)。

        过滤条件 ``status IS NULL OR status = 'confirmed'``: 保留 IS NULL 分支是
        兼容"代码先于 migration 起来"的极端情况的零成本保险 (正常部署顺序下 migration
        先跑, runner 失败会 ABORT 部署、不重启 Python, 所以这个分支理论上不会命中,
        但加上不亏)。

        只在 HAS_STATUS_COLUMN=True 的子类才加这个过滤 —— 没有 status 列的别名表
        (工厂侧 factory_sku_alias, Phase 2 才建) 加了会直接报 UndefinedColumn。
        """
        if self._alias_cache is not None:
            return self._alias_cache

        if self.db is None:
            self._alias_cache = {}
            return self._alias_cache

        alias_table = self.get_alias_table_name()
        status_filter = (
            " AND (status IS NULL OR status = 'confirmed')" if self.HAS_STATUS_COLUMN else ""
        )
        try:
            rows = self.db.execute(
                text(
                    f"SELECT original_name, canonical_name FROM {alias_table} "
                    f"WHERE factory_id = :fid{status_filter}"
                ),
                {"fid": self.factory_id},
            ).fetchall()
            self._alias_cache = {row.original_name: row.canonical_name for row in rows}
        except Exception as e:
            # 表不存在时返回空字典 (例如工厂表 Phase 2 才建)
            logger.warning(f"无法加载 {alias_table}: {e}")
            self._alias_cache = {}

        return self._alias_cache

    def _cluster_by_similarity(
        self,
        names: list[str],
        threshold: float,
        min_prefix: int,
    ) -> list[list[str]]:
        """简单的层次聚类 — 基于 SequenceMatcher 相似度 + 共同前缀

        算法 (O(n²) 但在 n<1000 时够用):
          1. 对每对名字算相似度
          2. 相似度 ≥ threshold 且共同前缀 ≥ min_prefix → 同组
          3. 用 union-find 合并

        Returns:
            分组列表, 每组包含相似的名字
        """
        if len(names) < 2:
            return []

        # Union-find
        parent = {n: n for n in names}

        def find(x):
            while parent[x] != x:
                parent[x] = parent[parent[x]]
                x = parent[x]
            return x

        def union(a, b):
            ra, rb = find(a), find(b)
            if ra != rb:
                parent[ra] = rb

        for i in range(len(names)):
            for j in range(i + 1, len(names)):
                a, b = names[i], names[j]
                # 共同前缀检查
                common = 0
                for ca, cb in zip(a, b):
                    if ca == cb:
                        common += 1
                    else:
                        break
                if common < min_prefix:
                    continue
                # 相似度检查
                ratio = SequenceMatcher(None, a, b).ratio()
                if ratio >= threshold:
                    union(a, b)

        # 收集分组
        groups: dict[str, list[str]] = {}
        for name in names:
            root = find(name)
            groups.setdefault(root, []).append(name)

        return [g for g in groups.values() if len(g) >= 2]

    def _pick_canonical_name(self, group: list[str]) -> str:
        """从一组相似名里挑选 canonical 名

        策略: 选最短的 (通常是最 generic 的形式).
        如果有重复, 选字典序最小的.
        """
        return min(group, key=lambda n: (len(n), n))

    def _build_sample_data(
        self,
        names: list[str],
        sample_data: Optional[dict[str, dict]],
    ) -> list[dict]:
        """构造审核 UI 用的样本数据"""
        if not sample_data:
            return [{"name": n} for n in names]
        return [
            {"name": n, **(sample_data.get(n, {}))}
            for n in names
        ]

    def _persist_proposals(self, proposals: list[MergeProposal]) -> None:
        """把 proposals 写入 alias_review_queue, 回填 proposal_id"""
        if self.db is None:
            return

        proposal_type = self.get_proposal_type()
        for proposal in proposals:
            result = self.db.execute(
                text("""
                    INSERT INTO alias_review_queue
                        (factory_id, domain, proposal_type, proposal_data, status, created_at)
                    VALUES
                        (:fid, :d, :pt, CAST(:data AS JSONB), 'pending', NOW())
                    RETURNING id
                """),
                {
                    "fid": self.factory_id,
                    "d": self.domain,
                    "pt": proposal_type,
                    "data": json.dumps(proposal.to_dict(), ensure_ascii=False),
                },
            )
            row = result.fetchone()
            if row:
                proposal.proposal_id = row.id
        self.db.commit()
