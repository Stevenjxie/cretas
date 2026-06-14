"""Phase 0: domain inference from mapped canonical fields."""
from smartbi.services.domain_inference import infer_domain


class _M:
    def __init__(self, standard):
        self.standard = standard


def test_infer_finance():
    assert infer_domain([_M("revenue"), _M("cost"), _M("period")]) == "finance"


def test_infer_production():
    assert infer_domain([_M("output_quantity"), _M("yield_rate"), _M("category")]) == "production"


def test_infer_sales_dict_input():
    assert infer_domain([{"standard": "sales_amount"}, {"standard": "unit_price"}]) == "sales"


def test_infer_none_for_shared_dims_only():
    assert infer_domain([_M("period"), _M("category"), _M(None)]) is None


def test_infer_majority_wins():
    assert infer_domain([_M("revenue"), _M("budget_amount"), _M("yield_rate")]) == "finance"
