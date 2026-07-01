from __future__ import annotations

"""Boss-facing final decision brief for restaurant SmartBI.

This section does not introduce a new score. It synthesizes the sections we
already have into plain actions a restaurant owner can decide on today, this
week, and this month.
"""

import time
from typing import Any

from smartbi.services.restaurant.sections.base import (
    AbstractSectionHandler,
    SectionRequest,
    SectionResponse,
)


class BossDecisionBriefHandler(AbstractSectionHandler):
    """Convert multi-source restaurant analysis into owner decisions."""

    section_name = "boss_decision_brief"

    def compute(self, request: SectionRequest, context: dict[str, Any]) -> SectionResponse:
        started = time.time()
        params = request.params or {}
        sections = (
            params.get("sections")
            or context.get("sections")
            or params.get("existing_sections")
            or {}
        )
        if not isinstance(sections, dict):
            sections = {}

        store_name = request.store_name or params.get("store_name") or "当前门店"
        sub_sector = request.sub_sector or params.get("sub_sector") or "餐饮"
        period = request.period or params.get("period") or "current"
        city = params.get("city") or context.get("city") or "上海"
        district = params.get("business_district") or context.get("business_district")
        mall = params.get("mall_name") or context.get("mall_name")

        readiness = self._build_readiness(params, context, sections)
        decisions = self._build_decisions(
            store_name=str(store_name),
            sub_sector=str(sub_sector),
            city=str(city),
            district=str(district) if district else None,
            mall=str(mall) if mall else None,
            period=str(period),
            readiness=readiness,
            sections=sections,
            params=params,
        )

        data = {
            "moduleName": "老板最终决策简报",
            "plainPurpose": "把月盘点、POS、菜品、大众点评和外部活动天气合成老板能直接拍板的动作。",
            "storeContext": {
                "storeName": store_name,
                "subSector": sub_sector,
                "period": period,
                "city": city,
                "businessDistrict": district,
                "mallName": mall,
            },
            "finalAnswer": self._final_answer(readiness, decisions),
            "ownerDecisionPage": self._owner_decision_page(
                store_name=str(store_name),
                readiness=readiness,
                decisions=decisions,
                params=params,
                sections=sections,
            ),
            "ownerDecisionNow": decisions["ownerDecisionNow"],
            "decisionCards": decisions["decisionCards"],
            "sourceDecisionMap": self._source_decision_map(readiness),
            "dataReadiness": readiness,
            "crossPlatformComparison": self._cross_platform_comparison(readiness, params, sections),
            "whatEachSourceAnswers": self._what_each_source_answers(),
            "dataGapForRealOperation": self._data_gap(readiness),
            "nextDataToAskCustomer": self._next_data_to_ask(readiness),
            "decisionPrinciples": [
                "先判断问题是外部客流、门店转化、菜品结构、成本浪费，还是评价体验，不要把所有波动都归因到店长。",
                "月盘点回答钱有没有漏掉；大众点评回答顾客为什么不满意；菜品/POS 回答应该推什么或砍什么；外部信号回答当天异常是不是外部原因。",
                "公开活动和公众号只能解释外部可能性，不能替代商场真实客流、楼层客流和客户授权经营数据。",
            ],
        }
        return self.ok(request, data=data, started=started)

    def _build_readiness(
        self,
        params: dict[str, Any],
        context: dict[str, Any],
        sections: dict[str, Any],
    ) -> dict[str, Any]:
        pos_ready = bool(
            context.get("pos_df") is not None
            or params.get("pos_summary")
            or "channelMargin" in sections
            or "diningHeatmap" in sections
            or "longTailSku" in sections
        )
        finance_ready = bool(
            params.get("financial_summary")
            or "financialMetrics" in sections
            or "storePnlOnePager" in sections
            or "diagnostics" in sections
        )
        stocktake_ready = bool(
            params.get("monthly_stocktake")
            or "shrinkageAnalysis" in sections
            or "shrinkage_analysis" in sections
            or "bomVariance" in sections
            or "bom_variance" in sections
            or "calibrationHistory" in sections
        )
        review_ready = bool(
            params.get("review_summary")
            or params.get("reviews")
            or "reviewAnalysis" in sections
            or "review_analysis" in sections
            or "reviewCompetitive" in sections
        )
        menu_ready = bool(
            params.get("menu_summary")
            or "menuEngineering" in sections
            or "menu_engineering" in sections
            or "menuNormalization" in sections
            or "longTailSku" in sections
        )
        external_ready = bool(
            params.get("external_signals")
            or params.get("store_geo_profile")
            or "advancedTrafficPersona" in sections
            or "advanced_traffic_persona" in sections
        )
        chain_ready = bool(
            params.get("chain_summary")
            or "multiStoreComparison" in sections
            or "crossChainBenchmark" in sections
        )

        source_statuses = [
            self._source_status(
                "POS/订单",
                pos_ready,
                "能判断堂食/外卖、时段、客单和转化变化。",
                "没有 POS 就只能讲环境，不能判断门店自己有没有接住机会。",
            ),
            self._source_status(
                "月盘点/库存/BOM",
                stocktake_ready,
                "能判断食材浪费、漏记、耗用异常和采购备货问题。",
                "没有月盘点就不能下结论说毛利掉是菜卖错了还是货漏了。",
            ),
            self._source_status(
                "财务/P&L",
                finance_ready,
                "能判断这个月到底赚没赚钱，食材、人力、房租哪个拖累利润。",
                "没有财务就不能承诺利润影响，只能做运营方向判断。",
            ),
            self._source_status(
                "大众点评/顾客评价",
                review_ready,
                "能判断顾客抱怨的是排队、服务、出品、价格还是环境。",
                "没有评论原文就不能把体验问题定位到具体动作。",
            ),
            self._source_status(
                "菜品/POS SKU",
                menu_ready,
                "能判断哪些菜该主推、改价、下架，哪些高毛利菜需要被看见。",
                "没有菜品和成本就无法做菜单工程，只能泛泛说提升销售。",
            ),
            self._source_status(
                "外部活动/天气/商圈",
                external_ready,
                "能解释今天或本周异常波动是不是天气、展会、商场活动或商圈变化。",
                "没有外部信号容易把节日、暴雨、演唱会等外部原因误判成门店能力。",
            ),
            self._source_status(
                "连锁门店对比",
                chain_ready,
                "能判断这家店在同城/同品牌里是个别问题还是整体问题。",
                "没有连锁对比就不能判断是不是店长问题、商圈问题还是品牌共性。",
            ),
        ]

        enough_for_direction = sum(1 for item in source_statuses if item["available"]) >= 3
        enough_for_hard_roi = all(
            item["available"]
            for item in source_statuses
            if item["source"] in {"POS/订单", "月盘点/库存/BOM", "财务/P&L"}
        )
        return {
            "enoughForBossDirection": enough_for_direction,
            "enoughForHardRoiPromise": enough_for_hard_roi,
            "plainVerdict": (
                "现有数据足够给老板一个先后顺序和试点动作；"
                if enough_for_direction
                else "现有数据只能做 demo 方向展示；"
            )
            + (
                "但要承诺利润金额，还必须有 POS、月盘点和财务同时闭环。"
                if not enough_for_hard_roi
                else "并且已经具备做利润影响估算的基础。"
            ),
            "sources": source_statuses,
        }

    @staticmethod
    def _source_status(
        source: str,
        available: bool,
        when_available: str,
        when_missing: str,
    ) -> dict[str, Any]:
        return {
            "source": source,
            "available": available,
            "decisionUse": when_available if available else when_missing,
        }

    def _build_decisions(
        self,
        store_name: str,
        sub_sector: str,
        city: str,
        district: str | None,
        mall: str | None,
        period: str,
        readiness: dict[str, Any],
        sections: dict[str, Any],
        params: dict[str, Any],
    ) -> dict[str, Any]:
        location = " / ".join([part for part in [city, district, mall] if part])
        has_external = self._has_source(readiness, "外部活动/天气/商圈")
        has_reviews = self._has_source(readiness, "大众点评/顾客评价")
        has_stocktake = self._has_source(readiness, "月盘点/库存/BOM")
        has_menu = self._has_source(readiness, "菜品/POS SKU")
        has_finance = self._has_source(readiness, "财务/P&L")
        has_chain = self._has_source(readiness, "连锁门店对比")

        external_text = self._external_plain_text(sections, params)
        cards: list[dict[str, Any]] = []

        cards.append({
            "priority": "P0",
            "decision": "今天先不要因为一天波动就直接打折或怪店长。",
            "recommendation": (
                "先给当天打外部标签：天气、商场活动、展会、节假日、周边交通；"
                "再看堂食、外卖、排队和评价有没有同步变化。"
            ),
            "why": external_text if has_external else "现在缺外部标签，最容易把外部原因误判成门店经营问题。",
            "evidence": [
                "外部信号用于解释异常天，不直接证明门店经营好坏。",
                "POS 只看到已买单的人，看不到路过但没进店的人。",
            ],
            "sourceInputs": ["外部活动/天气/商圈", "POS/订单", "大众点评/顾客评价"],
            "ownerAction": "今天先复盘异常原因，明天再决定是否上促销。",
        })

        cards.append({
            "priority": "P0",
            "decision": "本周先解决顾客体验里最容易影响转化的点。",
            "recommendation": self._review_action_text(sections, params),
            "why": (
                "已有顾客评价，可以把问题落到排队、服务、出品、价格等具体场景。"
                if has_reviews
                else "缺少大众点评原文时，只能猜体验问题；需要补评论文本或平台授权数据。"
            ),
            "evidence": self._review_evidence(sections, params),
            "sourceInputs": ["大众点评/顾客评价", "POS/订单", "外部活动/天气/商圈"],
            "ownerAction": "店长本周只抓一个体验问题，连续 7 天复盘差评关键词和高峰现场。",
        })

        cards.append({
            "priority": "P0",
            "decision": "本月必须把月盘点、BOM 和采购耗用对上。",
            "recommendation": (
                "用月盘点核对高耗用食材、报损、退菜、赠品和采购价格；"
                "先找利润漏点，再谈新品和投放。"
            ),
            "why": (
                "已有月盘点或 BOM 校准，可以判断毛利差是浪费、漏记、采购价，还是菜品结构造成。"
                if has_stocktake
                else "现在缺月盘点，无法判断利润差到底是卖得不好，还是食材耗用和采购出了问题。"
            ),
            "evidence": self._stocktake_evidence(sections, params),
            "sourceInputs": ["月盘点/库存/BOM", "财务/P&L", "菜品/POS SKU"],
            "ownerAction": "财务和厨师长本月先对 TOP 20 食材，找出金额最大的 3 个漏点。",
        })

        cards.append({
            "priority": "P1",
            "decision": "菜单动作要按菜品角色做，不要所有菜一起促销。",
            "recommendation": self._menu_action_text(sections, params),
            "why": (
                "已有菜品/POS 数据，可以把动作拆成主推、改价、下架、改出品。"
                if has_menu
                else "缺菜品毛利和销量时，菜单建议只能停留在经验判断。"
            ),
            "evidence": self._menu_evidence(sections, params),
            "sourceInputs": ["菜品/POS SKU", "大众点评/顾客评价", "月盘点/库存/BOM"],
            "ownerAction": "本周挑 3 个主推菜、3 个观察菜、3 个待下架菜，做两周 A/B。",
        })

        cards.append({
            "priority": "P1",
            "decision": "如果是连锁店，要先判断这是单店问题还是区域共性。",
            "recommendation": (
                "把本店放到同商圈、同城市、同品牌门店里比较；"
                "如果只有本店差，查店内执行；如果同商圈都差，查商场和外部客流。"
            ),
            "why": (
                "已有连锁对比，可以避免把区域问题错罚给单店。"
                if has_chain
                else "现在缺连锁门店对比，老板还不能判断是单店店长问题还是品牌/商圈共性。"
            ),
            "evidence": [
                f"当前门店: {store_name}",
                f"分析位置: {location or '未提供商圈位置'}",
                f"分析周期: {period}",
            ],
            "sourceInputs": ["连锁门店对比", "外部活动/天气/商圈", "POS/订单"],
            "ownerAction": "区域经理补同城门店同周期数据后，再决定是否换店长、调商场资源或做区域活动。",
        })

        owner_now = {
            "today": "今天只做异常归因：外部活动/天气/商场变化 + 堂食外卖 + 排队评价，先不急着打折。",
            "thisWeek": "本周只抓一个最影响转化的体验问题，同时定 3 个主推菜和 3 个待观察菜。",
            "thisMonth": "月底用月盘点、BOM、采购和财务把利润漏点对上，再决定菜单、备货和供应商动作。",
            "notReadyYet": [] if has_finance and has_stocktake else [
                "没有月盘点和财务闭环前，不要承诺具体节省金额或 ROI。",
            ],
        }
        return {"ownerDecisionNow": owner_now, "decisionCards": cards}

    @staticmethod
    def _has_source(readiness: dict[str, Any], source: str) -> bool:
        for item in readiness.get("sources", []):
            if item.get("source") == source:
                return bool(item.get("available"))
        return False

    @staticmethod
    def _external_plain_text(sections: dict[str, Any], params: dict[str, Any]) -> str:
        advanced = sections.get("advancedTrafficPersona") or sections.get("advanced_traffic_persona") or {}
        external = params.get("external_signals") or advanced.get("externalSignals") or {}
        weather = external.get("weather") if isinstance(external, dict) else None
        activities = external.get("activities") if isinstance(external, dict) else None
        parts = []
        if isinstance(weather, dict):
            desc = weather.get("text") or weather.get("weather") or weather.get("summary")
            if desc:
                parts.append(f"天气信号: {desc}")
        if isinstance(activities, list) and activities:
            title = activities[0].get("title") if isinstance(activities[0], dict) else None
            if title:
                parts.append(f"活动信号: {title}")
        return "；".join(parts) or "已有外部信号，可以先判断当天异常是否受天气、商场活动、展会或节假日影响。"

    @staticmethod
    def _review_evidence(sections: dict[str, Any], params: dict[str, Any]) -> list[str]:
        review = params.get("review_summary") or sections.get("reviewAnalysis") or sections.get("review_analysis") or {}
        evidence: list[str] = []
        if isinstance(review, dict):
            for key in (
                "riskAlerts",
                "negativeThemes",
                "topComplaints",
                "dishTags",
                "positiveDishMentions",
                "negativeDishMentions",
            ):
                value = review.get(key)
                if isinstance(value, list) and value:
                    evidence.append(f"{key}: {value[:3]}")
            rating = review.get("averageRating") or review.get("rating")
            if rating:
                evidence.append(f"当前评分/均分: {rating}")
        return evidence or ["需要补充大众点评/美团/抖音评论原文，才能定位顾客不满意的具体原因。"]

    @staticmethod
    def _stocktake_evidence(sections: dict[str, Any], params: dict[str, Any]) -> list[str]:
        stocktake = params.get("monthly_stocktake") or {}
        evidence: list[str] = []
        if isinstance(stocktake, dict):
            for key in ("topLossItems", "varianceItems", "purchaseAlerts"):
                value = stocktake.get(key)
                if value:
                    evidence.append(f"{key}: {value}")
        for section_key in ("shrinkageAnalysis", "bomVariance", "calibrationHistory", "bomLayerStatus"):
            if section_key in sections:
                evidence.append(f"已接入 {section_key}")
        return evidence or ["需要上传月底盘点、采购、报损、BOM 或理论耗用，才能判断利润漏点。"]

    @staticmethod
    def _menu_evidence(sections: dict[str, Any], params: dict[str, Any]) -> list[str]:
        menu = params.get("menu_summary") or sections.get("menuEngineering") or sections.get("menu_engineering") or {}
        evidence: list[str] = []
        if isinstance(menu, dict):
            for key in ("stars", "cashCows", "puzzles", "dogs", "recommendations"):
                value = menu.get(key)
                if value:
                    evidence.append(f"{key}: {value if isinstance(value, str) else value[:3]}")
        if "longTailSku" in sections:
            long_tail = sections["longTailSku"] or {}
            if isinstance(long_tail, dict):
                count = long_tail.get("recommendedDelistCount")
                if count is not None:
                    evidence.append(f"建议下架长尾 SKU 数: {count}")
        return evidence or ["需要菜品销量、售价、食材成本和评论菜品关键词，才能做主推/改价/下架。"]

    @staticmethod
    def _review_action_text(sections: dict[str, Any], params: dict[str, Any]) -> str:
        review = params.get("review_summary") or sections.get("reviewAnalysis") or sections.get("review_analysis") or {}
        if not isinstance(review, dict):
            return (
                "如果点评集中说排队久、上菜慢或服务不稳，先改预约、分流、等位话术和高峰排班；"
                "不要先把动作放到全店降价。"
            )

        themes = BossDecisionBriefHandler._theme_names(review.get("negativeThemes") or [])
        low_count = BossDecisionBriefHandler._safe_int(review.get("lowRatingCount"))
        review_count = BossDecisionBriefHandler._safe_int(review.get("reviewCount") or review.get("totalReviews"))
        negative_dishes = BossDecisionBriefHandler._item_names(
            review.get("negativeDishMentions") or review.get("complaintDishes") or []
        )
        positive_dishes = BossDecisionBriefHandler._item_names(
            review.get("positiveDishMentions") or review.get("positiveDishes") or []
        )

        actions: list[str] = []
        if any(token in themes for token in ("排队", "等位", "上菜慢", "出餐慢")):
            actions.append("高峰先改预约、等位告知和出餐节奏")
        if any(token in themes for token in ("服务", "态度", "环境")):
            actions.append("店长本周盯服务话术、桌边响应和门店环境")
        if any(token in themes for token in ("味道差", "不好吃", "油", "咸", "干", "预制", "肉少")):
            target = f"，重点复盘 {'、'.join(negative_dishes[:3])}" if negative_dishes else ""
            actions.append(f"厨师长抽查出品稳定性{target}")
        if not actions:
            actions.append("先从低分评论里找最集中的一个体验问题，连续 7 天复盘")

        scale = ""
        if review_count and low_count is not None:
            scale = f"这批点评 {review_count} 条里低分 {low_count} 条，"
        positive = (
            f"点评正向菜品可借力 {'、'.join(positive_dishes[:3])}；"
            if positive_dishes
            else ""
        )
        return (
            f"{scale}{positive}本周动作不是全店降价，而是："
            f"{'；'.join(actions)}。"
        )

    @staticmethod
    def _menu_action_text(sections: dict[str, Any], params: dict[str, Any]) -> str:
        menu = params.get("menu_summary") or sections.get("menuEngineering") or sections.get("menu_engineering") or {}
        if not isinstance(menu, dict):
            return (
                "高毛利且评价好的菜要明确主推，放到门口、团购页和服务员推荐；"
                "销量低又低毛利的菜先缩 SKU 或下架；差评集中的菜先改出品稳定性。"
            )

        top_products = menu.get("topProducts") or []
        low_sales = menu.get("lowSalesProducts") or []
        top_categories = menu.get("topCategories") or []
        review = params.get("review_summary") or sections.get("reviewAnalysis") or sections.get("review_analysis") or {}
        if not isinstance(review, dict):
            review = {}

        lead_names = [
            str(item.get("name"))
            for item in top_products
            if isinstance(item, dict) and item.get("name")
        ][:3]
        observe_names = [
            str(item.get("name"))
            for item in low_sales
            if isinstance(item, dict) and item.get("name")
        ][:3]
        category_names = [
            str(item.get("category"))
            for item in top_categories
            if isinstance(item, dict) and item.get("category")
        ][:2]
        positive_review_names = BossDecisionBriefHandler._item_names(
            review.get("positiveDishMentions") or review.get("positiveDishes") or []
        )[:3]
        negative_review_names = BossDecisionBriefHandler._item_names(
            review.get("negativeDishMentions") or review.get("complaintDishes") or []
        )[:3]

        if lead_names:
            lead_text = "、".join(lead_names)
            category_text = f"；当前收入大类优先看 {'、'.join(category_names)}" if category_names else ""
            observe_text = (
                f"；低销量低金额项先看 {'、'.join(observe_names)}，不要占菜单黄金位置"
                if observe_names
                else ""
            )
            positive_text = (
                f"；点评里也认可 {'、'.join(positive_review_names)}，适合和 POS 热卖菜互相验证"
                if positive_review_names
                else ""
            )
            negative_text = (
                f"；其中点评点名不稳定的 {'、'.join(negative_review_names)} 先查出品，通过后再加大投放"
                if negative_review_names
                else ""
            )
            return (
                f"本周先把 {lead_text} 作为主推候选，放到菜单首屏、团购页和服务员推荐话术里"
                f"{category_text}{positive_text}{negative_text}{observe_text}。"
                "不要全店打折，先用爆品带套餐和加购。"
            )

        return (
            "高毛利且评价好的菜要明确主推，放到门口、团购页和服务员推荐；"
            "销量低又低毛利的菜先缩 SKU 或下架；差评集中的菜先改出品稳定性。"
        )

    @staticmethod
    def _final_answer(readiness: dict[str, Any], decisions: dict[str, Any]) -> str:
        cards = decisions.get("decisionCards") or []
        first = cards[0]["decision"] if cards else "先做数据补齐，再做经营动作。"
        return (
            f"{first} "
            f"{readiness.get('plainVerdict')} "
            "老板看这个模块时，重点不是看分数，而是按今天、本周、本月三层动作拍板。"
        )

    @staticmethod
    def _owner_decision_page(
        store_name: str,
        readiness: dict[str, Any],
        decisions: dict[str, Any],
        params: dict[str, Any],
        sections: dict[str, Any],
    ) -> dict[str, Any]:
        pos = params.get("pos_summary") or {}
        menu = params.get("menu_summary") or sections.get("menuEngineering") or sections.get("menu_engineering") or {}
        review = params.get("review_summary") or sections.get("reviewAnalysis") or sections.get("review_analysis") or {}
        if not isinstance(pos, dict):
            pos = {}
        if not isinstance(menu, dict):
            menu = {}
        if not isinstance(review, dict):
            review = {}

        headline = BossDecisionBriefHandler._owner_headline(store_name, pos, menu, review)
        actions = BossDecisionBriefHandler._owner_action_items(pos, menu, review, readiness)
        return {
            "title": "老板决策页",
            "headline": headline,
            "plainDiagnosis": BossDecisionBriefHandler._plain_diagnosis(pos, menu, review),
            "doFirst": actions["doFirst"],
            "doNotDo": actions["doNotDo"],
            "decisionPlan": {
                "today": decisions.get("ownerDecisionNow", {}).get("today"),
                "thisWeek": actions["thisWeek"],
                "thisMonth": decisions.get("ownerDecisionNow", {}).get("thisMonth"),
            },
            "expectedImpact": BossDecisionBriefHandler._expected_impact(pos),
            "keyEvidence": BossDecisionBriefHandler._owner_key_evidence(pos, menu, review),
            "analysisDimensions": BossDecisionBriefHandler._analysis_dimensions(readiness, pos, menu, review, sections),
            "dataStillMissing": BossDecisionBriefHandler._data_gap(readiness),
        }

    @staticmethod
    def _owner_headline(store_name: str, pos: dict[str, Any], menu: dict[str, Any], review: dict[str, Any]) -> str:
        weekday_weekend = pos.get("weekdayWeekend") or {}
        if isinstance(weekday_weekend, dict):
            gap_pct = BossDecisionBriefHandler._safe_float(weekday_weekend.get("gapPct"))
            if gap_pct is not None and gap_pct >= 30:
                return (
                    f"{store_name} 不是先看全店打折，优先解决工作日承接。"
                    f"周末日均比工作日高 {round(gap_pct, 1)}%，说明周末需求能接住，周一到周四要补客流和加购。"
                )

        chain_rank = pos.get("chainRank") or {}
        if isinstance(chain_rank, dict):
            revenue_rank = BossDecisionBriefHandler._safe_int(chain_rank.get("revenueRank"))
            daily_rank = BossDecisionBriefHandler._safe_int(chain_rank.get("dailyRank"))
            store_count = BossDecisionBriefHandler._safe_int(chain_rank.get("storeCount"))
            if revenue_rank and daily_rank and store_count and daily_rank < revenue_rank:
                return (
                    f"{store_name} 总额排名不靠前，但日均能力更强："
                    f"总额第 {revenue_rank}/{store_count}，日均第 {daily_rank}/{store_count}。"
                    "先别按总额误判门店，应该看开业天数、工作日和商场动线。"
                )

        top_products = BossDecisionBriefHandler._item_names(menu.get("topProducts") or [])
        positive_dishes = BossDecisionBriefHandler._item_names(review.get("positiveDishMentions") or [])
        if top_products and positive_dishes:
            return (
                f"{store_name} 的主线是用 {top_products[0]} 做入口，"
                f"再用点评认可的 {positive_dishes[0]} 做加购和复购。"
            )
        return f"{store_name} 需要把 POS、点评、菜品、外部信号和成本闭环放在同一页看，先定动作再看图表。"

    @staticmethod
    def _plain_diagnosis(pos: dict[str, Any], menu: dict[str, Any], review: dict[str, Any]) -> str:
        parts: list[str] = []
        weekly = pos.get("weeklyTrend") or []
        if isinstance(weekly, list) and weekly:
            last = weekly[-1]
            if isinstance(last, dict):
                wow_revenue = BossDecisionBriefHandler._safe_float(last.get("wowRevenuePct"))
                wow_orders = BossDecisionBriefHandler._safe_float(last.get("wowOrdersPct"))
                aov = BossDecisionBriefHandler._safe_float(last.get("aov"))
                if wow_revenue is not None and wow_orders is not None:
                    parts.append(
                        f"最近一周营收环比 {round(wow_revenue, 1)}%，订单环比 {round(wow_orders, 1)}%"
                        + (f"，客单约 {round(aov, 2)} 元" if aov is not None else "")
                    )

        dayparts = pos.get("daypartRevenue") or []
        top_daypart = BossDecisionBriefHandler._top_named_share(dayparts, ("name", "daypart"))
        if top_daypart:
            parts.append(f"{top_daypart['name']}贡献约 {top_daypart['sharePercent']}%，排班和备货要优先围绕这个时段")

        customer_segment = BossDecisionBriefHandler._top_named_share(
            pos.get("customerSegments") or pos.get("topGuestSegments") or [],
            ("segment", "name"),
        )
        if customer_segment:
            parts.append(f"核心桌型/客群是 {customer_segment['name']}，贡献约 {customer_segment['sharePercent']}%")

        channel = BossDecisionBriefHandler._top_named_share(pos.get("channelGroups") or [], ("channel", "name"))
        if channel:
            parts.append(f"最大渠道是 {channel['name']}，贡献约 {channel['sharePercent']}%，渠道动作要优先服务这个入口")

        top_products = BossDecisionBriefHandler._item_names(menu.get("topProducts") or [])
        if top_products:
            parts.append(f"菜品入口先看 {top_products[0]}，但要和点评风险一起判断，不能只看销量")

        themes = BossDecisionBriefHandler._theme_names(review.get("negativeThemes") or [])
        if themes:
            parts.append(f"点评风险集中在 {'、'.join(themes[:3])}，本周先改最集中的一个体验问题")

        return "；".join(parts) or "当前数据足够做经营方向判断，但还需要日级订单、点评原文、BOM 和月盘点才能做利润闭环。"

    @staticmethod
    def _owner_action_items(
        pos: dict[str, Any],
        menu: dict[str, Any],
        review: dict[str, Any],
        readiness: dict[str, Any],
    ) -> dict[str, list[str]]:
        do_first: list[str] = []
        do_not_do = ["不要先做全店打折，先判断问题是客流、转化、菜品、体验还是成本。"]

        weekday_weekend = pos.get("weekdayWeekend") or {}
        if isinstance(weekday_weekend, dict):
            gap_pct = BossDecisionBriefHandler._safe_float(weekday_weekend.get("gapPct"))
            if gap_pct is not None and gap_pct >= 30:
                do_first.append("周一到周四先做轻套餐和门口/团购页导流，把工作日空档补起来。")

        top_products = BossDecisionBriefHandler._item_names(menu.get("topProducts") or [])
        basket_pairs = menu.get("basketPairs") or menu.get("topPairs") or []
        pair_names = BossDecisionBriefHandler._pair_names(basket_pairs)
        if top_products:
            if pair_names:
                do_first.append(f"用 {top_products[0]} 做入口，固定搭配 {pair_names[0]}，不要让服务员临场随便推荐。")
            else:
                do_first.append(f"把 {top_products[0]} 放到菜单首屏、团购页和服务员话术里，先用爆品带加购。")

        negative_dishes = BossDecisionBriefHandler._item_names(review.get("negativeDishMentions") or [])
        if negative_dishes:
            do_first.append(f"厨师长本周抽查 {'、'.join(negative_dishes[:3])}，先稳出品再放大投放。")
            do_not_do.append(f"不要盲目加推 {'、'.join(negative_dishes[:3])}，这些菜点评里有不稳定信号。")

        channel = BossDecisionBriefHandler._top_named_share(pos.get("channelGroups") or [], ("channel", "name"))
        if channel:
            do_first.append(f"优先优化 {channel['name']} 的团购页、套餐名、评价回复和主图，因为它是当前最大入口。")

        if not BossDecisionBriefHandler._has_source(readiness, "月盘点/库存/BOM"):
            do_not_do.append("没有月盘点、采购和 BOM 前，不要承诺毛利改善金额，也不要把利润问题全归因到销售。")

        if not do_first:
            do_first.append("先补近 90 天订单明细和点评原文，把周趋势、时段、桌型、菜品和差评原因合到一页。")

        return {
            "doFirst": do_first[:5],
            "doNotDo": do_not_do[:4],
            "thisWeek": [
                do_first[0],
                "每天复盘一次订单数、客单、差评关键词和主推菜转化，不等月底才看报表。",
            ],
        }

    @staticmethod
    def _expected_impact(pos: dict[str, Any]) -> dict[str, str]:
        aov = (
            BossDecisionBriefHandler._safe_float(pos.get("aov"))
            or BossDecisionBriefHandler._safe_float(pos.get("avgOrderRevenue"))
            or BossDecisionBriefHandler._safe_float(pos.get("averageOrderValue"))
        )
        if aov:
            weekly_uplift = round(aov * 10 * 5, 0)
            return {
                "plainText": (
                    f"保守看，工作日每天多接 10 单，按当前客单约 {round(aov, 2)} 元，"
                    f"一周可多约 {int(weekly_uplift)} 元营收。"
                ),
                "caveat": "这是营收机会估算，不是利润承诺；利润还要等 BOM、采购、损耗和人工排班闭环。",
            }
        return {
            "plainText": "当前缺少可用客单，暂不估算金额，只给动作优先级。",
            "caveat": "补齐订单明细后，可按每日多接单数直接估算周营收机会。",
        }

    @staticmethod
    def _owner_key_evidence(pos: dict[str, Any], menu: dict[str, Any], review: dict[str, Any]) -> list[str]:
        evidence: list[str] = []
        orders = pos.get("orders")
        revenue = pos.get("revenue") or pos.get("periodRevenue")
        customers = pos.get("customers")
        if orders and revenue:
            evidence.append(f"POS: {orders} 单，实收/收入约 {revenue}" + (f"，客流 {customers}" if customers else ""))

        weekday_weekend = pos.get("weekdayWeekend") or {}
        if isinstance(weekday_weekend, dict):
            weekday = BossDecisionBriefHandler._safe_float(weekday_weekend.get("weekdayAvgDailyRevenue"))
            weekend = BossDecisionBriefHandler._safe_float(weekday_weekend.get("weekendAvgDailyRevenue"))
            gap = BossDecisionBriefHandler._safe_float(weekday_weekend.get("gapPct"))
            if weekday is not None and weekend is not None and gap is not None:
                evidence.append(f"工作日日均 {round(weekday, 2)}，周末日均 {round(weekend, 2)}，周末高 {round(gap, 1)}%")

        chain_rank = pos.get("chainRank") or {}
        if isinstance(chain_rank, dict):
            revenue_rank = chain_rank.get("revenueRank")
            daily_rank = chain_rank.get("dailyRank")
            store_count = chain_rank.get("storeCount")
            if revenue_rank and daily_rank and store_count:
                evidence.append(f"连锁对比: 总额第 {revenue_rank}/{store_count}，日均第 {daily_rank}/{store_count}")

        top_products = BossDecisionBriefHandler._item_names(menu.get("topProducts") or [])
        if top_products:
            evidence.append(f"菜品入口: {'、'.join(top_products[:3])}")

        positive = BossDecisionBriefHandler._item_names(review.get("positiveDishMentions") or [])
        negative = BossDecisionBriefHandler._item_names(review.get("negativeDishMentions") or [])
        if positive:
            evidence.append(f"点评认可: {'、'.join(positive[:3])}")
        if negative:
            evidence.append(f"点评风险: {'、'.join(negative[:3])}")
        return evidence or ["当前证据不足，先补 POS、点评、菜品和外部信号。"]

    @staticmethod
    def _analysis_dimensions(
        readiness: dict[str, Any],
        pos: dict[str, Any],
        menu: dict[str, Any],
        review: dict[str, Any],
        sections: dict[str, Any],
    ) -> list[dict[str, str]]:
        return [
            BossDecisionBriefHandler._dimension(
                "营收趋势",
                "看日/周/月变化，判断问题是短期波动还是持续下滑。",
                "已接周趋势，可做周环比。" if pos.get("weeklyTrend") else "缺连续日级订单时，只能看汇总，无法解释哪天掉了。",
                "补近 90 天日订单，固定输出最近 4 周环比。",
            ),
            BossDecisionBriefHandler._dimension(
                "时段结构",
                "判断午市、晚市、低峰分别该怎么排班、备货和促销。",
                "已接时段收入。" if pos.get("daypartRevenue") else "缺开单时间聚合，无法判断午晚市哪个时段弱。",
                "按小时聚合订单、营收、客流和客单。",
            ),
            BossDecisionBriefHandler._dimension(
                "桌型/人数结构",
                "判断该推单人餐、双人餐还是多人聚餐套餐。",
                "已接桌型/客群结构。" if (pos.get("customerSegments") or pos.get("topGuestSegments")) else "缺人数结构，套餐建议容易拍脑袋。",
                "按客流人数聚合订单、营收和客单。",
            ),
            BossDecisionBriefHandler._dimension(
                "菜品结构",
                "判断引流菜、加购菜、风险菜、低动销菜分别怎么处理。",
                "已接菜品销量。" if menu.get("topProducts") else "缺 SKU 明细时无法做菜单工程。",
                "补菜品销量、售价、成本、搭配关系。",
            ),
            BossDecisionBriefHandler._dimension(
                "顾客评价",
                "判断顾客不满意在服务、出品、环境、价格还是排队。",
                "已接点评主题。" if (review.get("negativeThemes") or review.get("positiveDishMentions")) else "缺评论原文时只能猜体验问题。",
                "继续拉点评/美团/抖音评论原文和低分原因。",
            ),
            BossDecisionBriefHandler._dimension(
                "渠道结构",
                "判断美团点评、抖音、微信、会员、商场券各自该承担什么任务。",
                "已接渠道贡献。" if pos.get("channelGroups") else "缺渠道拆分时无法判断预算该投哪里。",
                "按渠道聚合订单、券核销、实收和复购。",
            ),
            BossDecisionBriefHandler._dimension(
                "连锁对比",
                "判断是单店执行问题，还是商圈/区域/品牌共性。",
                "已接连锁排名。" if pos.get("chainRank") else "缺同品牌门店对比，容易误罚单店。",
                "补同城同品牌同周期门店数据。",
            ),
            BossDecisionBriefHandler._dimension(
                "利润闭环",
                "判断利润漏在采购、BOM、报损、赠品、盘点差异还是菜品结构。",
                "已有成本/盘点入口。" if BossDecisionBriefHandler._has_source(readiness, "月盘点/库存/BOM") else "缺 BOM、采购和月盘点，不能承诺利润改善金额。",
                "补采购入库、月底盘点、理论耗用/BOM、报损赠品。",
            ),
        ]

    @staticmethod
    def _dimension(name: str, question: str, current: str, next_step: str) -> dict[str, str]:
        return {
            "dimension": name,
            "bossQuestion": question,
            "currentFinding": current,
            "nextStep": next_step,
        }

    @staticmethod
    def _cross_platform_comparison(
        readiness: dict[str, Any],
        params: dict[str, Any],
        sections: dict[str, Any],
    ) -> list[dict[str, Any]]:
        menu = params.get("menu_summary") or sections.get("menuEngineering") or sections.get("menu_engineering") or {}
        review = params.get("review_summary") or sections.get("reviewAnalysis") or sections.get("review_analysis") or {}
        pos = params.get("pos_summary") or {}
        external = params.get("external_signals") or sections.get("advancedTrafficPersona") or {}

        menu_top = BossDecisionBriefHandler._item_names((menu or {}).get("topProducts") or []) if isinstance(menu, dict) else []
        review_positive = BossDecisionBriefHandler._item_names((review or {}).get("positiveDishMentions") or []) if isinstance(review, dict) else []
        review_negative = BossDecisionBriefHandler._item_names((review or {}).get("negativeDishMentions") or []) if isinstance(review, dict) else []
        themes = BossDecisionBriefHandler._theme_names((review or {}).get("negativeThemes") or []) if isinstance(review, dict) else []
        pos_parts: list[str] = []
        if isinstance(pos, dict):
            revenue = pos.get("periodRevenue") or pos.get("revenue")
            orders = pos.get("orders")
            if revenue and orders:
                pos_parts.append(f"收入片段 {revenue}，订单 {orders}")
            segments = pos.get("topGuestSegments") or []
            if isinstance(segments, list) and segments:
                first_segment = segments[0]
                if isinstance(first_segment, dict):
                    segment_name = first_segment.get("segment") or first_segment.get("name")
                    share = first_segment.get("share")
                    if segment_name and share is not None:
                        try:
                            share_percent = round(float(share) * 100, 1)
                        except (TypeError, ValueError):
                            share_percent = None
                        if share_percent is not None:
                            pos_parts.append(f"主要桌型/客群是 {segment_name}，占比约 {share_percent}%")

        return [
            {
                "platform": "POS/销量",
                "whatItSays": "；".join(
                    [
                        *(
                            [f"热卖菜优先看 {'、'.join(menu_top[:3])}"]
                            if menu_top
                            else ["当前没有菜品热卖明细，无法判断该主推什么。"]
                        ),
                        *pos_parts,
                    ]
                ),
                "decisionUse": "决定菜单首屏、团购套餐和服务员推荐顺序。",
            },
            {
                "platform": "大众点评/美团评论",
                "whatItSays": (
                    f"好评菜 {'、'.join(review_positive[:3])}；风险菜/主题 {'、'.join((review_negative + themes)[:4])}"
                    if review_positive or review_negative or themes
                    else "当前只有评分或评论量，没有足够主题拆解。"
                ),
                "decisionUse": "验证热卖菜是不是也被顾客认可，并把差评主题落到出品/服务动作。",
            },
            {
                "platform": "商圈/活动/天气",
                "whatItSays": "已有外部信号可解释异常日。" if external else "缺少当天商场活动、天气、节假日和周边活动标签。",
                "decisionUse": "决定当天波动是否先按外部原因处理，避免误判店长执行。",
            },
            {
                "platform": "月盘点/BOM/采购",
                "whatItSays": (
                    "已有盘点或 BOM，可继续判断毛利漏点。"
                    if any(
                        item.get("source") == "月盘点/库存/BOM" and item.get("available")
                        for item in readiness.get("sources", [])
                    )
                    else "缺少月底盘点、理论耗用和采购价，不能判断利润差是不是食材漏损。"
                ),
                "decisionUse": "决定是否调供应商、改备货、查报损赠品，而不是只做销售动作。",
            },
            {
                "platform": "连锁门店对比",
                "whatItSays": (
                    "已有连锁对比，可判断单店问题还是区域共性。"
                    if any(
                        item.get("source") == "连锁门店对比" and item.get("available")
                        for item in readiness.get("sources", [])
                    )
                    else "缺同城同品牌门店同周期数据，无法判断本店是否异常。"
                ),
                "decisionUse": "决定是抓单店执行，还是做区域活动、商圈资源和门店分流。",
            },
        ]

    @staticmethod
    def _item_names(items: Any) -> list[str]:
        if not isinstance(items, list):
            return []
        names: list[str] = []
        for item in items:
            name: Any = None
            if isinstance(item, dict):
                name = item.get("name") or item.get("dish") or item.get("tag") or item.get("product")
            elif isinstance(item, (list, tuple)) and item:
                name = item[0]
            elif isinstance(item, str):
                name = item
            if name is None:
                continue
            text = str(name).strip()
            if text:
                names.append(text)
        return names

    @staticmethod
    def _theme_names(items: Any) -> list[str]:
        if not isinstance(items, list):
            return []
        names: list[str] = []
        for item in items:
            name: Any = None
            if isinstance(item, dict):
                name = item.get("theme") or item.get("name") or item.get("tag") or item.get("keyword")
            elif isinstance(item, (list, tuple)) and item:
                name = item[0]
            elif isinstance(item, str):
                name = item
            if name is None:
                continue
            text = str(name).strip()
            if text:
                names.append(text)
        return names

    @staticmethod
    def _safe_int(value: Any) -> int | None:
        if value is None or value == "":
            return None
        try:
            return int(float(value))
        except (TypeError, ValueError):
            return None

    @staticmethod
    def _safe_float(value: Any) -> float | None:
        if value is None or value == "":
            return None
        try:
            return float(value)
        except (TypeError, ValueError):
            return None

    @staticmethod
    def _top_named_share(items: Any, name_keys: tuple[str, ...]) -> dict[str, Any] | None:
        if not isinstance(items, list):
            return None
        best: dict[str, Any] | None = None
        best_share = -1.0
        for item in items:
            if not isinstance(item, dict):
                continue
            name = next((item.get(key) for key in name_keys if item.get(key)), None)
            share = BossDecisionBriefHandler._safe_float(item.get("share"))
            if share is None:
                share = BossDecisionBriefHandler._safe_float(item.get("revenueShare"))
            if name is None or share is None:
                continue
            if share > best_share:
                best = {"name": str(name), "share": share, "sharePercent": round(share * 100, 1)}
                best_share = share
        return best

    @staticmethod
    def _pair_names(items: Any) -> list[str]:
        if not isinstance(items, list):
            return []
        names: list[str] = []
        for item in items:
            if isinstance(item, dict):
                pair = item.get("pair") or item.get("name")
                if pair:
                    names.append(str(pair))
                    continue
                left = item.get("left") or item.get("a") or item.get("productA")
                right = item.get("right") or item.get("b") or item.get("productB")
                if left and right:
                    names.append(f"{left}+{right}")
            elif isinstance(item, (list, tuple)) and len(item) >= 2:
                names.append(f"{item[0]}+{item[1]}")
            elif isinstance(item, str):
                names.append(item)
        return [name for name in names if name.strip()]

    @staticmethod
    def _source_decision_map(readiness: dict[str, Any]) -> list[dict[str, Any]]:
        return [
            {
                "source": item["source"],
                "available": item["available"],
                "answers": item["decisionUse"],
            }
            for item in readiness.get("sources", [])
        ]

    @staticmethod
    def _what_each_source_answers() -> list[dict[str, str]]:
        return [
            {
                "source": "月盘点/库存/BOM",
                "bossQuestion": "钱是不是漏在食材、采购、报损、赠品或盘点差异里？",
                "decision": "决定先查哪些食材、是否调供应商、是否改备货阈值。",
            },
            {
                "source": "大众点评/顾客评价",
                "bossQuestion": "顾客为什么不满意，问题在排队、服务、出品、价格还是环境？",
                "decision": "决定先改等位、排班、服务话术、菜品稳定性，还是价格表达。",
            },
            {
                "source": "菜品/POS SKU",
                "bossQuestion": "哪些菜该主推、改价、下架或改出品？",
                "decision": "决定菜单工程、团购页展示、服务员推荐和 SKU 精简。",
            },
            {
                "source": "外部活动/天气/商圈",
                "bossQuestion": "某天突然好/差，是门店原因，还是活动、天气、展会、商场动线原因？",
                "decision": "决定是否复盘门店执行，还是把动作改成导流、备货、排班和活动承接。",
            },
            {
                "source": "连锁门店对比",
                "bossQuestion": "这家店是在品牌里个别掉队，还是整个区域都受影响？",
                "decision": "决定处理单店执行，还是处理区域商圈、品牌活动和门店分流。",
            },
        ]

    @staticmethod
    def _data_gap(readiness: dict[str, Any]) -> list[str]:
        gaps = []
        for item in readiness.get("sources", []):
            if not item.get("available"):
                gaps.append(f"缺 {item['source']}: {item['decisionUse']}")
        return gaps or ["当前核心数据足够进入试点复盘；下一步补齐外部真实客流后再做精确 ROI。"]

    @staticmethod
    def _next_data_to_ask(readiness: dict[str, Any]) -> list[dict[str, str]]:
        source_priority = {
            "POS/订单": "先要近 90 天订单明细，含渠道、菜品、金额、时间、门店。",
            "月盘点/库存/BOM": "再要月底盘点、采购入库、报损、赠品、理论耗用/BOM。",
            "财务/P&L": "再要月度收入、食材、人力、房租、平台费、营销费。",
            "大众点评/顾客评价": "再要点评/美团/抖音评论原文和评分时间线。",
            "菜品/POS SKU": "再要菜品售价、销量、成本和出品分类。",
            "外部活动/天气/商圈": "配置商场活动源、公众号单篇源、和风天气、节假日、周边活动。",
            "连锁门店对比": "如果是连锁，再要同城同品牌门店同周期数据。",
        }
        result = []
        for item in readiness.get("sources", []):
            if not item.get("available"):
                result.append({
                    "source": item["source"],
                    "askFor": source_priority.get(item["source"], "补齐该来源的结构化数据。"),
                })
        return result[:5]
