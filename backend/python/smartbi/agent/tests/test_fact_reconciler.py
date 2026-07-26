"""Unit tests for FactReconciler (spec §4.4 / §5.5 / §6 Task 3).

Asserts:
  - LLM says "平均星级 4.5" / factbook 4.79 → annotated "(实际 4.79)" + lower confidence.
  - LLM fabricates "招牌牛肉店" not in top_stores → annotated [未在数据中找到该名称].
  - Correct number → no-op (no annotation).
  - No matching fact name → no-op (宁漏不错).
  - 万/亿 hallucination sentinel reused.
"""
from smartbi.agent.factbook import FactBook
from smartbi.services.llm_guard import FactReconciler


def _fb():
    return FactBook(
        review={
            "summary": {"total_reviews": 19845, "avg_star": 4.79, "low_star_count": 396,
                        "high_star_count": 18139},
            "vip": {"groups": [
                {"group": "VIP", "review_count": 2485, "avg_star": 4.50},
                {"group": "非VIP", "review_count": 17360, "avg_star": 4.83},
            ]},
            "good_tags": {"tags": [{"tag": "味道好", "count": 5998}]},
        },
        finance={
            "start_date": "2025-01-01", "end_date": "2025-12-31",
            "total_revenue": 20640000.0, "bill_count": 141000, "avg_bill_value": 146.4,
            "store_count": 8,
            "top_stores": [
                {"store_id": 1, "store_name": "青花椒大融城店", "revenue": 3500000.0, "bill_count": 24000},
            ],
        },
    )


class TestNumericReconcile:
    def test_wrong_avg_star_annotated(self):
        rec = FactReconciler()
        ans = "青花椒的平均星级 4.5 还不错。"
        out, meta = rec.reconcile(ans, _fb())
        assert "实际 4.79" in out
        assert meta["reconciled"] is True
        assert meta["confidence_adj"] < 0

    def test_correct_avg_star_noop(self):
        rec = FactReconciler()
        ans = "青花椒的平均星级 4.79 很高。"
        out, meta = rec.reconcile(ans, _fb())
        assert "实际" not in out  # no annotation
        assert out == ans
        assert meta["reconciled"] is False

    def test_within_tolerance_noop(self):
        rec = FactReconciler()
        # 4.80 vs 4.79 → 0.2% < 5% tol → no annotation
        ans = "平均星级 4.80。"
        out, meta = rec.reconcile(ans, _fb())
        assert "实际" not in out

    def test_vip_star_longest_name_match(self):
        rec = FactReconciler()
        # "VIP平均星级" must match before bare "平均星级"
        ans = "VIP平均星级 4.2，非VIP平均星级 4.83。"
        out, meta = rec.reconcile(ans, _fb())
        # VIP one is wrong (4.2 vs 4.50) → annotated; 非VIP correct → not.
        assert "实际 4.50" in out
        assert meta["reconciled"] is True

    def test_vip_correct_not_falsely_corrected_to_overall(self):
        # F3 substring collision: 平均星级(4.79) ⊂ VIP平均星级(4.50). When the VIP
        # number is CORRECT (within tol of 4.50), the bare overall 平均星级 must
        # NOT re-match INSIDE "VIP平均星级" and falsely "correct" it to 4.79.
        rec = FactReconciler()
        ans = "VIP平均星级4.504，低于非VIP平均星级4.83。"
        out, meta = rec.reconcile(ans, _fb())
        assert "4.79" not in out          # no false overall-rating correction
        assert "实际" not in out           # both numbers within tol → no annotation
        assert meta["reconciled"] is False

    def test_wrong_overall_still_caught_when_vip_present(self):
        # Over-suppression guard (F3 audit): the span-guard must skip ONLY the
        # inside-longer-name occurrences — a STANDALONE wrong overall 平均星级 in
        # the SAME answer where VIP/非VIP names co-occur must STILL be corrected.
        rec = FactReconciler()
        ans = "平均星级4.2，其中VIP平均星级4.50，非VIP平均星级4.83。"
        out, meta = rec.reconcile(ans, _fb())
        assert "实际 4.79" in out          # standalone wrong overall STILL annotated
        assert meta["reconciled"] is True
        assert "实际 4.50" not in out      # correct VIP not falsely touched
        assert "实际 4.83" not in out      # correct 非VIP not falsely touched

    def test_no_matching_fact_noop(self):
        rec = FactReconciler()
        # "翻台率" is not a fact name → must not annotate (宁漏不错).
        ans = "翻台率达到 3.5 次，非常优秀。"
        out, meta = rec.reconcile(ans, _fb())
        assert out == ans
        assert meta["reconciled"] is False

    def test_revenue_wan_scale_normalized(self):
        rec = FactReconciler()
        # true 总营业额 = 20,640,000; LLM says "1000万" = 10,000,000 → 52% off → annotate
        ans = "总营业额 1000万元。"
        out, meta = rec.reconcile(ans, _fb())
        assert "数据核对" in out
        assert meta["reconciled"] is True

    def test_revenue_wan_scale_correct_noop(self):
        rec = FactReconciler()
        # 2064万 = 20,640,000 == true → no annotation
        ans = "总营业额 2064万元。"
        out, meta = rec.reconcile(ans, _fb())
        assert "数据核对" not in out


class TestFabricatedNames:
    def test_fabricated_store_flagged(self):
        rec = FactReconciler()
        # Fabricated-name handling is deliberately metadata-only: the answer is
        # not polluted with a possibly false inline warning.  Use a known brand
        # prefix so this is an actual store-name candidate rather than a generic
        # dish/shop phrase.
        ans = "建议关注“青花椒未来中心店”的表现。"
        out, meta = rec.reconcile(ans, _fb())
        assert out == ans
        assert any("青花椒未来中心店" in v for v in meta["violations"])

    def test_known_store_not_flagged(self):
        rec = FactReconciler()
        ans = "青花椒大融城店表现最好。"
        out, meta = rec.reconcile(ans, _fb())
        assert "未在数据中找到该名称" not in out

    def test_known_store_substring_not_flagged(self):
        rec = FactReconciler()
        # "大融城店" is a substring of the known "青花椒大融城店" → treated as known.
        ans = "大融城店营收最高。"
        out, meta = rec.reconcile(ans, _fb())
        assert "未在数据中找到该名称" not in out

    def test_no_known_stores_no_fabrication_check(self):
        rec = FactReconciler()
        # FactBook without finance → no known store universe → don't judge店 names.
        fb = FactBook(review={"summary": {"total_reviews": 100, "avg_star": 4.5}})
        ans = "某某店表现一般。"
        out, meta = rec.reconcile(ans, fb)
        assert "未在数据中找到该名称" not in out


class TestHonestyLabel:
    def test_dish_mislabel_annotated(self):
        rec = FactReconciler()
        # "招牌菜" applied near a 口味标签 (味道好) → annotate honesty note.
        ans = "味道好 是顾客最喜欢的招牌菜。"
        out, meta = rec.reconcile(ans, _fb())
        assert "口味/品质标签，非菜名" in out

    def test_no_mislabel_noop(self):
        rec = FactReconciler()
        ans = "顾客最满意味道好这一点。"
        out, meta = rec.reconcile(ans, _fb())
        assert "口味/品质标签，非菜名" not in out


class TestEmpty:
    def test_empty_answer(self):
        rec = FactReconciler()
        out, meta = rec.reconcile("", _fb())
        assert out == ""
        assert meta["reconciled"] is False
