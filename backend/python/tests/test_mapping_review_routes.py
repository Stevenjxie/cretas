"""Phase 0: mapping_review router import + route registration smoke."""
def test_router_imports_and_registers_routes():
    from smartbi_compat.api.mapping_review import router, ConfirmRequest, ConfirmItem
    paths = [r.path for r in router.routes]
    assert any(p.endswith("/mapping-review/pending") for p in paths), paths
    assert any(p.endswith("/confirm") for p in paths), paths


def test_confirm_request_model():
    from smartbi_compat.api.mapping_review import ConfirmRequest
    req = ConfirmRequest(items=[{"column_name": "营业额", "confirmed_standard": "sales_amount"}])
    assert req.items[0].should_pin is True


def test_canonical_validation_set_available():
    # the confirm endpoint validates against this set
    from smartbi.services.domain_standard_fields import ALL_CANONICAL_FIELDS
    assert "sales_amount" in ALL_CANONICAL_FIELDS
    assert "数量金额_2" not in ALL_CANONICAL_FIELDS
