"""Phase 0: mapping_review router import + route registration smoke."""


def test_router_imports_and_registers_routes():
    from smartbi_compat.api.mapping_review import router
    paths = [r.path for r in router.routes]
    assert any(p.endswith("/mapping-review/pending") for p in paths), paths
    assert any(p.endswith("/confirm") for p in paths), paths
    # Task 1: canonical-fields endpoint
    assert any(p.endswith("/mapping-review/canonical-fields") for p in paths), paths


def test_confirm_request_model():
    from smartbi_compat.api.mapping_review import ConfirmRequest
    req = ConfirmRequest(items=[{"column_name": "营业额", "confirmed_standard": "sales_amount"}])
    assert req.items[0].should_pin is True


def test_canonical_validation_set_available():
    # the confirm endpoint validates against this set
    from smartbi.services.domain_standard_fields import ALL_CANONICAL_FIELDS
    assert "sales_amount" in ALL_CANONICAL_FIELDS
    assert "数量金额_2" not in ALL_CANONICAL_FIELDS


def test_canonical_fields_endpoint_structure():
    """canonical-fields returns per-domain dict with correct entry shape."""
    from smartbi.services.domain_standard_fields import DOMAIN_FIELDS
    expected_domains = {"finance", "production", "sales", "purchase", "inventory"}
    assert expected_domains.issubset(set(DOMAIN_FIELDS.keys()))

    # Simulate what the endpoint builds
    data: dict = {}
    for domain, fields in DOMAIN_FIELDS.items():
        data[domain] = [
            {
                "standard_name": sn,
                "description": meta.get("description", ""),
                "category": meta.get("category", ""),
            }
            for sn, meta in fields.items()
        ]

    for domain in expected_domains:
        assert domain in data, f"{domain} missing from output"
        entries = data[domain]
        assert len(entries) > 0, f"{domain} has no entries"
        # every entry has required keys
        for entry in entries:
            assert "standard_name" in entry
            assert "description" in entry
            assert "category" in entry
        # domain-specific canonical field present
    assert any(e["standard_name"] == "sales_amount" for e in data["sales"])
    assert any(e["standard_name"] == "revenue" for e in data["finance"])
    assert any(e["standard_name"] == "output_quantity" for e in data["production"])
    assert any(e["standard_name"] == "purchase_amount" for e in data["purchase"])
    assert any(e["standard_name"] == "stock_quantity" for e in data["inventory"])


def test_canonical_fields_no_garbage_standard_names():
    """All standard_names in canonical-fields are in ALL_CANONICAL_FIELDS."""
    from smartbi.services.domain_standard_fields import DOMAIN_FIELDS, ALL_CANONICAL_FIELDS
    for domain, fields in DOMAIN_FIELDS.items():
        for sn in fields:
            assert sn in ALL_CANONICAL_FIELDS, (
                f"{sn} in DOMAIN_FIELDS[{domain}] not in ALL_CANONICAL_FIELDS"
            )
