"""Section contract tests - every section handler must satisfy this interface."""
import pytest
from smartbi.services.restaurant.sections.base import (
    SectionRequest, SectionResponse, AbstractSectionHandler,
    SectionStatus,
)


def test_section_request_accepts_factory_and_upload():
    req = SectionRequest(
        factory_id="F001",
        upload_id="u-123",
        sub_sector="火锅",
        store_id=None,
        store_name=None,
        params={},
    )
    assert req.factory_id == "F001"
    assert req.upload_id == "u-123"
    assert req.sub_sector == "火锅"


def test_section_response_status_enum():
    resp = SectionResponse(
        section_name="cost_rigidity",
        status=SectionStatus.OK,
        data={"costRigidity": 0.561},
        warnings=[],
        cache_key="cost_rigidity:F001:u-123",
        computed_at_ms=42,
    )
    assert resp.status == SectionStatus.OK
    assert resp.data["costRigidity"] == 0.561


def test_abstract_handler_enforces_compute_method():
    class BrokenHandler(AbstractSectionHandler):
        section_name = "broken"
        # Missing compute() implementation

    with pytest.raises(TypeError):
        BrokenHandler()


def test_all_phase_3_5_sections_registered():
    """Regression: 4 new sections from P3.5B-D must be in the router.

    Prevents accidental deregistration during future refactors. These
    4 sections were added in:
      - expense_breakdown (P3.5B F6)
      - shrinkage_analysis (P3.5C B4)
      - department_pnl (P3.5D P1)
      - monthly_ppt_export (P3.5D P3)

    Total handler count should be >=19 (15 P1 originals + 4 P3.5 new).
    """
    from smartbi.api.restaurant_sections import HANDLERS

    required = {
        "expense_breakdown",
        "shrinkage_analysis",
        "department_pnl",
        "monthly_ppt_export",
    }
    missing = required - set(HANDLERS.keys())
    assert not missing, f"Missing section handlers: {missing}"

    # Total count: P1 had 15, P3.5 adds 4 = at least 19
    assert len(HANDLERS) >= 19, (
        f"Expected >=19 handlers after P3.5, got {len(HANDLERS)}: "
        f"{sorted(HANDLERS.keys())}"
    )
