"""ReviewsSentimentSummary — 大众点评/美团评价下载分析.

Triggered by review exports (评价下载 xlsx) with columns 星级分 / 口味分 /
环境分 / 服务分 / 评价详情 / 评价门店 / 投诉状态.

Outputs:
  - 评价总数 + 平均星级 / 口味 / 环境 / 服务
  - 星级分布 (5/4/3/2/1 星 buckets)
  - Top/Bottom 门店 by 平均星级
  - 投诉率 + VIP 评价占比
  - 趋势 by month (if 评价时间 date present)

Applies when schema has 评价 OR 星级 column.
"""
from __future__ import annotations

import logging
from typing import Any, ClassVar, Dict, List

import polars as pl

logger = logging.getLogger(__name__)

from smartbi.capability.contract import RequiresSpec  # noqa: E402

from ..compute.base import ComputeBackend  # noqa: E402
from ..restaurant import industry_benchmarks as bench  # noqa: E402
from ..restaurant.action_rec_formatter import format_action_rec  # noqa: E402
from ..schema import DataSchema  # noqa: E402
from .base import AnalysisTemplate, TemplateResult  # noqa: E402
from .registry import register  # noqa: E402
from ..restaurant import schema_helpers  # noqa: E402

_TOP_N = 10

# 大众点评/美团 的"评价标签"列混了真菜品名 (羊骨煲/地瓜拼盘) 和口碑/服务/价格类
# 形容词标签 (味道好/实惠/新鲜...)。后者不是菜品, 当"最常提及菜品"展示会误导
# (用户看到"最常提及菜品: 味道好" 一脸懵), 且会让下游 action_rec 把"味道好"
# 当招牌菜去做营销 = 胡说。这里把常见的非菜品口碑词过滤掉, 让 dish-tag 特性
# 只在真有菜品名时才出 (全是口碑词 → top_dish_tags 空 → 那一行优雅省略)。
_NON_DISH_REVIEW_TAGS = frozenset([
    # 味道 / 口感
    "味道好", "味道不错", "味道赞", "味道一般", "味道差", "好吃", "很好吃", "超好吃",
    "美味", "难吃", "口感好", "口感不错", "口感一般", "口味好", "口味不错", "口味一般",
    # 价格
    "实惠", "划算", "超值", "性价比高", "性价比", "便宜", "价格实惠", "价格公道",
    "价格贵", "不便宜", "量大实惠",
    # 食材
    "新鲜", "食材新鲜", "很新鲜", "不新鲜",
    # 环境 / 卫生
    "环境好", "环境优雅", "环境不错", "环境舒适", "环境一般", "环境差", "装修好",
    "氛围好", "氛围不错", "干净", "卫生", "干净卫生", "整洁",
    # 服务
    "服务好", "服务热情", "服务态度好", "服务周到", "服务一般", "服务差", "态度好",
    "态度差", "上菜快", "出餐快", "上菜慢",
    # 分量
    "分量足", "量足", "份量足", "分量小", "分量一般", "量大", "份量大",
    # 位置 / 体验
    "位置好", "交通便利", "停车方便", "好找", "排队", "等位", "人多", "回头客",
    "推荐", "值得推荐", "下次再来", "还会再来", "值得一去", "不错", "一般", "还行",
    "满意", "很满意", "失望",
])


@register
class ReviewsSentimentSummary(AnalysisTemplate):

    sample_queries = [
        "评价分析",
        "客户评价怎么样",
        "用户评价分析",
        "大众点评口碑",
        "星级分布",
        "好评差评比例",
        "评分统计",
        "投诉多不多",
        "哪些菜评价最多",
        "差评分析",
        "门店评分排名",
        "评分最高的门店",
    ]

    # spec §6.1: B 阶段引入 review_text/rating 字段后再填
    requires: ClassVar[RequiresSpec | None] = None

    @property
    def code(self) -> str:
        return "reviews_sentiment_summary"

    @property
    def title(self) -> str:
        return "评价数据汇总 (星级 / 投诉 / 门店)"

    def applies(self, schema: DataSchema) -> bool:
        names = {f.name for f in schema.fields}
        return schema_helpers.find_star_col(names) is not None

    def compute(self, backend: ComputeBackend, schema: DataSchema) -> TemplateResult:
        df = backend._df  # type: ignore[attr-defined]
        cols = set(df.columns)

        star_col = schema_helpers.find_star_col(cols)
        taste_col = schema_helpers.find_taste_score_col(cols)
        env_col = schema_helpers.find_env_score_col(cols)
        svc_col = schema_helpers.find_service_score_col(cols)
        store_col = schema_helpers.find_review_store_col(cols)
        time_col = schema_helpers.find_review_time_col(cols)
        vip_col = schema_helpers.find_vip_flag_col(cols)
        complaint_col = schema_helpers.find_complaint_status_col(cols)
        platform_col = schema_helpers.find_review_platform_col(cols)

        if star_col is None:
            return TemplateResult(code=self.code, title=self.title, data={},
                                  applies=False, skip_reason="no 星级 column")

        total = df.height

        def _avg(col):
            if col is None:
                return None
            try:
                v = df.select(pl.col(col).cast(pl.Float64, strict=False).mean()).item()
                return round(float(v), 2) if v is not None else None
            except Exception:
                return None

        avg_star = _avg(star_col)
        avg_taste = _avg(taste_col)
        avg_env = _avg(env_col)
        avg_svc = _avg(svc_col)

        # Star distribution (round to nearest int for bucketing)
        star_dist: List[Dict[str, Any]] = []
        star_series = df.select(
            pl.col(star_col).cast(pl.Float64, strict=False).round(0).alias("_star")
        )
        star_rows = (
            star_series
            .filter(pl.col("_star").is_not_null())
            .group_by("_star").agg(pl.len().alias("count"))
            .sort("_star", descending=True).to_dicts()
        )
        for r in star_rows:
            s = int(r["_star"])
            cnt = int(r["count"])
            star_dist.append({
                "star": s, "count": cnt,
                "share_pct": round(cnt / total * 100, 2) if total else 0.0,
            })

        # Top stores by review count + avg rating (require ≥5 reviews to filter noise)
        top_stores: List[Dict[str, Any]] = []
        worst_stores: List[Dict[str, Any]] = []
        if store_col:
            rows = (
                df.filter(pl.col(store_col).is_not_null())
                .group_by(store_col)
                .agg([
                    pl.len().alias("reviews"),
                    pl.col(star_col).cast(pl.Float64, strict=False).mean().alias("avg_star"),
                ])
                .filter(pl.col("reviews") >= 5)
                .sort("reviews", descending=True)
                .head(_TOP_N)
                .to_dicts()
            )
            top_stores = [
                {
                    "store": str(r.get(store_col) or "<空>"),
                    "reviews": int(r["reviews"]),
                    "avg_star": round(float(r["avg_star"] or 0.0), 2),
                }
                for r in rows
            ]
            rows_bad = (
                df.filter(pl.col(store_col).is_not_null())
                .group_by(store_col)
                .agg([
                    pl.len().alias("reviews"),
                    pl.col(star_col).cast(pl.Float64, strict=False).mean().alias("avg_star"),
                ])
                .filter(pl.col("reviews") >= 5)
                .sort("avg_star")
                .head(_TOP_N)
                .to_dicts()
            )
            worst_stores = [
                {
                    "store": str(r.get(store_col) or "<空>"),
                    "reviews": int(r["reviews"]),
                    "avg_star": round(float(r["avg_star"] or 0.0), 2),
                }
                for r in rows_bad
            ]

        # Complaint rate
        complaint_rate_pct = None
        complaint_count = 0
        if complaint_col:
            complaint_count = df.filter(
                pl.col(complaint_col).cast(pl.Utf8, strict=False).is_not_null()
                & (pl.col(complaint_col).cast(pl.Utf8, strict=False).str.strip_chars() != "")
                & (pl.col(complaint_col).cast(pl.Utf8, strict=False) != "无")
            ).height
            complaint_rate_pct = round(complaint_count / total * 100, 2) if total else 0.0

        # VIP rate
        vip_rate_pct = None
        if vip_col:
            vip_count = df.filter(
                pl.col(vip_col).cast(pl.Utf8, strict=False).is_in(["是", "1", "true", "True", "yes"])
            ).height
            vip_rate_pct = round(vip_count / total * 100, 2) if total else 0.0

        # Monthly trend
        by_month: List[Dict[str, Any]] = []
        if time_col:
            rows = (
                df.filter(pl.col(time_col).is_not_null())
                .with_columns(
                    pl.col(time_col).cast(pl.Utf8, strict=False).str.slice(0, 7).alias("_ym")
                )
                .group_by("_ym")
                .agg([
                    pl.len().alias("reviews"),
                    pl.col(star_col).cast(pl.Float64, strict=False).mean().alias("avg_star"),
                ])
                .sort("_ym").to_dicts()
            )
            by_month = [
                {
                    "month": str(r.get("_ym") or ""),
                    "reviews": int(r["reviews"]),
                    "avg_star": round(float(r["avg_star"] or 0.0), 2),
                }
                for r in rows
            ]

        # Top mentioned 菜品标签 — 大众点评 exports a 菜品标签 col with
        # comma-separated dish names the reviewer tagged (e.g.
        # "羊骨煲,地瓜拼盘"). Explode + count to find the dishes most
        # celebrated (or most complained-about) in reviews. This is a
        # key operational signal — it tells ops which dishes drive
        # word-of-mouth independent of raw POS volume.
        top_dish_tags: List[Dict[str, Any]] = []
        dish_tag_col = schema_helpers.find_dish_tag_col(cols)
        if dish_tag_col:
            try:
                rows = (
                    df.filter(
                        pl.col(dish_tag_col).is_not_null()
                        & (pl.col(dish_tag_col).cast(pl.Utf8, strict=False).str.strip_chars() != "")
                    )
                    .with_columns(
                        pl.col(dish_tag_col).cast(pl.Utf8).str.split(",").alias("_tags")
                    )
                    .explode("_tags")
                    .with_columns(pl.col("_tags").str.strip_chars().alias("_tags"))
                    .filter(
                        pl.col("_tags").is_not_null()
                        & (pl.col("_tags") != "")
                        & (pl.col("_tags") != "无")
                        # 滤掉非菜品的口碑/服务/价格类形容词标签 (味道好/实惠/新鲜...),
                        # 它们不是菜品名, 当"最常提及菜品"展示会误导。
                        & ~pl.col("_tags").is_in(list(_NON_DISH_REVIEW_TAGS))
                    )
                    .group_by("_tags").agg(pl.len().alias("mentions"))
                    .sort("mentions", descending=True)
                    .head(15)
                    .to_dicts()
                )
                for r in rows:
                    top_dish_tags.append({
                        "dish": str(r["_tags"]),
                        "mentions": int(r["mentions"]),
                        "share_pct": round(r["mentions"] / total * 100, 2) if total else 0.0,
                    })
            except Exception as e:
                logger.warning(f"[reviews] dish_tag extraction failed: {e}")

        # Platform share
        by_platform: List[Dict[str, Any]] = []
        if platform_col:
            rows = (
                df.filter(pl.col(platform_col).is_not_null())
                .group_by(platform_col)
                .agg(pl.len().alias("reviews"))
                .sort("reviews", descending=True).to_dicts()
            )
            by_platform = [
                {
                    "platform": str(r.get(platform_col) or "<空>"),
                    "reviews": int(r["reviews"]),
                    "share_pct": round(r["reviews"] / total * 100, 2) if total else 0.0,
                }
                for r in rows
            ]

        # Per-store 点评榜单资格 eligibility per 点评榜单规则:
        #   - 好评/口味/环境/服务榜 require 星级总分 >= 4.0, 总评价 >= 50, 近30天新增 >= 1
        #   - 热门/销量/打卡榜 require 星级总分 >= 3.5, 总评价 >= 50
        #   - 必吃榜 requires 365-day collection + 182+ active days + 口味分位居前列
        # We approximate by marking stores that already pass the star+review-count bar.
        ranking_qualified: List[Dict[str, Any]] = []
        if store_col and top_stores:
            # Need ALL stores' (not just top N) aggregates for eligibility
            all_stores_rows = (
                df.filter(pl.col(store_col).is_not_null())
                .group_by(store_col)
                .agg([
                    pl.len().alias("reviews"),
                    pl.col(star_col).cast(pl.Float64, strict=False).mean().alias("avg_star"),
                    *([pl.col(taste_col).cast(pl.Float64, strict=False).mean().alias("avg_taste")]
                      if taste_col else []),
                ])
                .to_dicts()
            )
            for r in all_stores_rows:
                s_name = str(r.get(store_col) or "")
                reviews_n = int(r["reviews"])
                avg_s = float(r["avg_star"] or 0.0)
                avg_t = float(r.get("avg_taste") or 0.0) if taste_col else 0.0
                quality_eligible = (
                    avg_s >= bench.QUALITY_RANKING_MIN_STAR
                    and reviews_n >= bench.QUALITY_RANKING_MIN_REVIEWS
                )
                popular_eligible = (
                    avg_s >= bench.POPULAR_RANKING_MIN_STAR
                    and reviews_n >= bench.QUALITY_RANKING_MIN_REVIEWS
                )
                must_eat_candidate = (
                    quality_eligible
                    and (avg_t >= bench.MUST_EAT_TASTE_THRESHOLD if taste_col else False)
                )
                # 黑珍珠 candidate (per 黑珍珠.docx — 3-钻 approximation)
                black_pearl_candidate = (
                    avg_s >= bench.BLACK_PEARL_MIN_STAR
                    and reviews_n >= bench.BLACK_PEARL_MIN_REVIEWS
                    and (avg_t >= bench.BLACK_PEARL_MIN_TASTE if taste_col else False)
                )
                ranking_qualified.append({
                    "store": s_name,
                    "reviews": reviews_n,
                    "avg_star": round(avg_s, 2),
                    "avg_taste": round(avg_t, 2) if taste_col else None,
                    "quality_ranking_eligible": quality_eligible,
                    "popular_ranking_eligible": popular_eligible,
                    "must_eat_candidate": must_eat_candidate,
                    "black_pearl_candidate": black_pearl_candidate,
                })

        quality_eligible_count = sum(1 for x in ranking_qualified if x["quality_ranking_eligible"])
        popular_eligible_count = sum(1 for x in ranking_qualified if x["popular_ranking_eligible"])
        must_eat_candidate_count = sum(1 for x in ranking_qualified if x["must_eat_candidate"])
        black_pearl_candidate_count = sum(1 for x in ranking_qualified if x["black_pearl_candidate"])

        # Insight
        parts = [f"期内共 {total:,} 条评价，平均星级 {avg_star:.2f}。"]
        if avg_taste and avg_env and avg_svc:
            parts.append(f"口味 {avg_taste}/环境 {avg_env}/服务 {avg_svc}。")
        if worst_stores and top_stores and worst_stores[0]["avg_star"] < top_stores[0]["avg_star"]:
            w = worst_stores[0]
            parts.append(
                f"最低评分门店:{w['store']} {w['avg_star']:.2f} 星 ({w['reviews']} 条)。"
            )
        if complaint_rate_pct is not None and complaint_rate_pct > 0:
            parts.append(f"投诉率 {complaint_rate_pct:.1f}% ({complaint_count} 条)。")
        if vip_rate_pct is not None:
            parts.append(f"VIP 评价占比 {vip_rate_pct:.1f}%。")
        if ranking_qualified:
            parts.append(
                f"📊 点评榜单:{quality_eligible_count} 家达到好评榜准入门槛 (≥4.0 星 & ≥50 条),"
                f"{popular_eligible_count} 家达到热门榜门槛 (≥3.5 星)"
            )
            if must_eat_candidate_count > 0:
                parts[-1] += f",{must_eat_candidate_count} 家口味出众可冲必吃榜"
            if black_pearl_candidate_count > 0:
                parts[-1] += f",💎 {black_pearl_candidate_count} 家已达黑珍珠 3-钻初选资格"
            parts[-1] += "。"
        if top_dish_tags:
            top3 = top_dish_tags[:3]
            parts.append(
                "🥘 评价中最常提及的菜品: "
                + "、".join(f"{t['dish']} ({t['mentions']} 条)" for t in top3)
                + "。"
            )
        parts.append(bench.industry_footer_by_context("review"))
        # Spec §4.3: drive worst store / complaint / dish into specific service action
        if worst_stores and worst_stores[0]["avg_star"] < 4.0:
            w = worst_stores[0]
            action_rec = format_action_rec(
                object_target=f"低评分门店「{w['store']}」 (仅 {w['avg_star']:.2f} 星 / {w['reviews']} 条)",
                benefit_range="客诉清单 + 服务 SOP 培训后,评分提升 0.2-0.5 分,影响外卖排名 / 必吃榜资格",
                prerequisite="抽样 10 条差评分析根因 + 店长 / 服务员培训 + 30 天内回访低分顾客",
                timeline="本月内",
            )
        elif complaint_rate_pct is not None and complaint_rate_pct >= 3:
            action_rec = format_action_rec(
                object_target=f"投诉率 {complaint_rate_pct:.1f}% ({complaint_count} 条)",
                benefit_range="投诉根因复盘 + SOP 修订可降投诉率 0.5-1.5 个百分点",
                prerequisite="投诉清单分类 (菜品 / 服务 / 环境) + 周例会复盘 + SOP 修订",
                timeline="本月内",
            )
        elif top_dish_tags and top_dish_tags[0]["mentions"] >= 50:
            top_d = top_dish_tags[0]
            action_rec = format_action_rec(
                object_target=f"口碑菜品「{top_d['dish']}」 ({top_d['mentions']} 条提及)",
                benefit_range="主推位 + 招牌菜营销可放大口碑效应,带动客流 5-10%",
                prerequisite="POS 推荐位置顶 + 包装升级 + 大众点评 / 美团置顶推广",
                timeline="本月内",
            )
        else:
            action_rec = format_action_rec(
                object_target=f"评价整体 (平均 {avg_star:.2f} 星 / {total} 条)",
                benefit_range="持续监控差评 + 30 天内回访可稳定评分 + 外卖排名",
                prerequisite="周期性差评跟踪 + 客户回访 + 必吃榜 / 黑珍珠资格申报",
                timeline="本月内",
            )
        insight_text = " ".join(parts) + " " + action_rec

        # Chart — star distribution pie
        chart_config = None
        if star_dist:
            chart_config = {
                "type": "pie",
                "title": {"text": "评价星级分布", "left": "center"},
                "tooltip": {"trigger": "item", "formatter": "{b} 星: {c} 条 ({d}%)"},
                "series": [{
                    "name": "评价", "type": "pie", "radius": ["30%", "65%"],
                    "data": [
                        {"name": f"{s['star']} 星", "value": s["count"]}
                        for s in star_dist if s["count"] > 0
                    ],
                }],
            }

        return TemplateResult(
            code=self.code, title=self.title,
            data={
                "total_reviews": total,
                "avg_star": avg_star, "avg_taste": avg_taste,
                "avg_env": avg_env, "avg_service": avg_svc,
                "star_distribution": star_dist,
                "top_stores": top_stores, "worst_stores": worst_stores,
                "complaint_count": complaint_count,
                "complaint_rate_pct": complaint_rate_pct,
                "vip_rate_pct": vip_rate_pct,
                "by_month": by_month, "by_platform": by_platform,
                "top_dish_tags": top_dish_tags,
                "ranking_eligibility": ranking_qualified,
                "quality_ranking_eligible_count": quality_eligible_count,
                "popular_ranking_eligible_count": popular_eligible_count,
                "must_eat_candidate_count": must_eat_candidate_count,
                "black_pearl_candidate_count": black_pearl_candidate_count,
            },
            chart_config=chart_config,
            kpis={
                "评价总数": total,
                "平均星级": avg_star,
                "投诉率": complaint_rate_pct,
                "最低评分门店": worst_stores[0]["store"] if worst_stores else None,
                "最低评分门店星级": worst_stores[0]["avg_star"] if worst_stores else None,
                "最常提及菜品": top_dish_tags[0]["dish"] if top_dish_tags else None,
                "最常提及菜品次数": top_dish_tags[0]["mentions"] if top_dish_tags else None,
                "好评榜达标门店数": quality_eligible_count,
                "必吃榜候选门店数": must_eat_candidate_count,
                "黑珍珠候选门店数": black_pearl_candidate_count,
            },
            insight_text=insight_text,
        )
