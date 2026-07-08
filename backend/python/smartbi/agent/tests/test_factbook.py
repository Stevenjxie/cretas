"""Unit tests for FactBook (spec §4.2 + §6 Task 1).

Asserts: honest labels rendered inline (口径自然语言 / 口味/品质标签，非菜名 / 小样本);
facts_index flattened correctly; empty review → review段省略 + next-action note;
sensitive name collection for the egress redactor.
"""
from smartbi.agent.factbook import (
    FactBook,
    NOTE_DISH_TAG_NOT_NAME,
    NOTE_REVIEW_ABSENT_NEXTACTION,
    NOTE_VIP_SIGNAL,
    factbook_to_dataframe,
)


def _full_review():
    return {
        "summary": {
            "total_reviews": 19845, "avg_star": 4.79, "avg_service": 4.80,
            "avg_env": 4.79, "avg_taste": 4.79, "high_star_count": 18139,
            "low_star_count": 396, "vip_count": 2485,
        },
        "vip": {"groups": [
            {"group": "VIP", "review_count": 2485, "avg_star": 4.50},
            {"group": "非VIP", "review_count": 17360, "avg_star": 4.83},
        ]},
        "platform": {"platforms": [
            {"platform": "点评", "review_count": 19189, "avg_star": 4.80},
            {"platform": "美团", "review_count": 656, "avg_star": 4.57},
        ]},
        "good_tags": {"high_star_count": 18139, "tags": [
            {"tag": "味道好", "count": 5998}, {"tag": "实惠", "count": 1791},
            {"tag": "鲜嫩", "count": 1394},
        ]},
        "dish_issues": {"low_star_count": 396, "tags": [
            {"tag": "味道差", "count": 79}, {"tag": "份量太小", "count": 54},
        ]},
        "worst_stores": {"stores": [
            {"store": "鲜行者X顺德小馆 (虹口龙之梦店)", "low_star_count": 64, "review_count": 800},
        ]},
    }


def _full_finance():
    return {
        "start_date": "2025-01-01", "end_date": "2025-12-31",
        "total_revenue": 20640000.0, "bill_count": 141000,
        "avg_bill_value": 146.4, "store_count": 8, "day_count": 365,
        "top_stores": [
            {"store_id": 1, "store_name": "青花椒大融城店", "revenue": 3500000.0, "bill_count": 24000},
            {"store_id": 2, "store_name": "青花椒人民广场店", "revenue": 2800000.0, "bill_count": 19000},
        ],
    }


def _full_sales():
    return {
        "top_products": [
            {"product_name": "藤椒鱼", "revenue": 1200000.0, "qty_sold": 30000},
            {"product_name": "黄油鸡", "revenue": 900000.0, "qty_sold": 18000},
        ],
        "channels": [
            {"channel_name": "微信", "amount": 12000000.0, "share_pct": 58.1},
            {"channel_name": "美团", "amount": 5000000.0, "share_pct": 24.2},
        ],
        "discounts": [
            {"discount_name": "满减券", "amount": 800000.0},
        ],
    }


class TestRendering:
    def test_review_honest_labels(self):
        fb = FactBook(review=_full_review(), period="2025-01-01~2025-12-31",
                      notes=[NOTE_DISH_TAG_NOT_NAME, NOTE_VIP_SIGNAL])
        text = fb.to_prompt_text()
        # 口味/品质标签 label inline on tags
        assert "口味/品质标签，非菜名" in text
        # small sample annotation on 差评
        assert "小样本" in text
        # honest notes section
        assert NOTE_DISH_TAG_NOT_NAME in text
        assert NOTE_VIP_SIGNAL in text
        # VIP groups rendered
        assert "VIP 评价" in text and "非VIP 评价" in text

    def test_finance_money_labels(self):
        fb = FactBook(finance=_full_finance())
        text = fb.to_prompt_text()
        # 口径用自然语言, 不再用方括号标记 (#5)
        assert "应收口径" in text
        assert "[毛" not in text and "[净" not in text
        assert "客单价" in text
        assert "青花椒大融城店" in text

    def test_sales_basis_labels(self):
        fb = FactBook(sales=_full_sales())
        text = fb.to_prompt_text()
        assert "按营业额" in text  # channel share basis (自然语言, 非方括号)
        assert "[按" not in text
        assert "藤椒鱼" in text

    def test_empty_review_omitted_with_nextaction(self):
        fb = FactBook(review=None, finance=_full_finance(),
                      notes=[NOTE_REVIEW_ABSENT_NEXTACTION])
        text = fb.to_prompt_text()
        # review section absent
        assert "去重评价" not in text
        # but finance present + next-action note present
        assert "总营业额" in text
        assert NOTE_REVIEW_ABSENT_NEXTACTION in text

    def test_cross_hints_relation_not_causal(self):
        fb = FactBook(cross_hints=[
            {"description": "VIP 平均星级 4.50 低于 非VIP 4.83（数据关系）",
             "values": {"VIP星级": 4.50, "非VIP星级": 4.83}},
        ])
        text = fb.to_prompt_text()
        assert "相关≠因果" in text
        assert "VIP 平均星级 4.50" in text


class TestFactsIndex:
    def test_flatten_all(self):
        fb = FactBook(review=_full_review(), finance=_full_finance())
        idx = fb.to_facts_index()
        assert idx["平均星级"] == 4.79
        assert idx["差评"] == 396
        assert idx["好评"] == 18139
        assert idx["总营业额"] == 20640000.0
        assert idx["客单价"] == 146.4
        assert idx["VIP平均星级"] == 4.50
        assert idx["非VIP平均星级"] == 4.83

    def test_partial_only_present(self):
        fb = FactBook(finance=_full_finance())
        idx = fb.to_facts_index()
        assert "平均星级" not in idx  # no review
        assert idx["总营业额"] == 20640000.0


class TestSensitiveNames:
    def test_collect_stores_and_dishes(self):
        fb = FactBook(review=_full_review(), finance=_full_finance(), sales=_full_sales())
        names = fb.collect_sensitive_names()
        assert "青花椒大融城店" in names["门店"]
        # worst-store name also collected
        assert any("鲜行者" in n for n in names["门店"])
        assert "藤椒鱼" in names["菜品"]

    def test_known_store_names_dedup(self):
        fin = _full_finance()
        rv = _full_review()
        # duplicate a store across top & worst
        rv["worst_stores"]["stores"][0]["store"] = "青花椒大融城店"
        fb = FactBook(review=rv, finance=fin)
        stores = fb.known_store_names()
        assert stores.count("青花椒大融城店") == 1

    def test_tags_not_in_sensitive(self):
        # 口味词 (味道好/鲜嫩) are generic quality words — must NOT be redacted.
        fb = FactBook(review=_full_review())
        names = fb.collect_sensitive_names()
        flat = [v for vals in names.values() for v in vals]
        assert "味道好" not in flat
        assert "鲜嫩" not in flat


class TestDataframe:
    def test_dataframe_numeric_columns(self):
        fb = FactBook(review=_full_review(), finance=_full_finance(), cross_hints=[
            {"description": "x", "values": {"VIP星级": 4.50, "非VIP星级": 4.83}},
        ])
        df = factbook_to_dataframe(fb)
        assert not df.empty
        assert df.shape[0] == 1
        assert "平均星级" in df.columns
        assert "VIP星级" in df.columns

    def test_dataframe_empty_factbook(self):
        df = factbook_to_dataframe(FactBook())
        assert df.empty


# --------------------------------------------------------------------------
# compute_store_attribution — 客流×客单价 拖后腿拆解 (2026-07-08)
# --------------------------------------------------------------------------
from smartbi.agent.factbook import compute_store_attribution  # noqa: E402


def _stores(*triples):
    """triples: (name, revenue, orderCount) — avgTicket derived."""
    return [
        {"name": n, "revenue": float(r), "orderCount": int(b),
         "avgTicket": (r / b if b else 0.0)}
        for (n, r, b) in triples
    ]


class TestStoreAttribution:
    def test_laggard_is_below_benchmark_and_cause_is_ticket(self):
        # B: normal traffic, low ticket → laggard, cause 客单价.
        att = compute_store_attribution(_stores(
            ("A", 1_000_000, 8000),   # ticket 125
            ("B", 600_000, 6000),     # ticket 100  ← low ticket
            ("C", 1_400_000, 7000),   # ticket 200
        ))
        assert att["laggard"]["store_name"] == "B"
        assert att["laggard"]["delta_revenue"] < 0      # genuinely below peers
        assert att["primary_cause"] == "客单价"
        # decomposition sign: ticket effect is the dominant negative
        assert att["laggard"]["ticket_effect"] < att["laggard"]["traffic_effect"]

    def test_cause_is_traffic_when_traffic_drives_the_gap(self):
        # B: normal ticket, low traffic → cause 客流.
        att = compute_store_attribution(_stores(
            ("A", 1_000_000, 8000),   # ticket 125
            ("B", 500_000, 4000),     # ticket 125, but half the traffic
            ("C", 1_100_000, 8800),   # ticket 125
        ))
        assert att["laggard"]["store_name"] == "B"
        assert att["primary_cause"] == "客流"

    def test_low_traffic_anomaly_excluded_and_benchmark_consistent(self):
        # Z has near-zero traffic → excluded from the peer comparison, reported
        # separately. Without exclusion the benchmark would be dragged down and
        # the laggard could show a POSITIVE delta (the bug this guards).
        att = compute_store_attribution(_stores(
            ("A", 1_000_000, 8000),
            ("B", 900_000, 7000),
            ("C", 1_100_000, 7500),
            ("Z", 5_000, 30),         # anomaly
        ))
        assert "Z" in att["anomalies"]
        assert all(s["store_name"] != "Z" for s in att["stores"])
        assert att["n_stores"] == 3
        assert att["laggard"]["delta_revenue"] < 0

    def test_needs_two_comparable_stores(self):
        assert compute_store_attribution([]).get("no_data")
        assert compute_store_attribution(_stores(("A", 100, 1))).get("no_data")

    def test_render_and_index_and_names(self):
        att = compute_store_attribution(_stores(
            ("甲店", 1_000_000, 8000),
            ("乙店", 600_000, 6000),
            ("丙店", 1_400_000, 7000),
        ))
        fb = FactBook(period="2025", attribution=att)
        text = fb.to_prompt_text()
        assert "门店拖后腿归因" in text
        assert "乙店" in text and "客流效应" in text and "客单价效应" in text
        idx = fb.to_facts_index()
        for k in ("门店平均客流", "全链客单价", "客流效应", "客单价效应", "拖后腿门店营收"):
            assert k in idx
        assert "乙店" in fb.known_store_names()  # reconciler + redactor see it
