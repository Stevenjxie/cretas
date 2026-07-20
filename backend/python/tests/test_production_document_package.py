from __future__ import annotations

import re
import sys
from pathlib import Path

import pytest

_PYTHON_ROOT = Path(__file__).resolve().parent.parent
if str(_PYTHON_ROOT) not in sys.path:
    sys.path.insert(0, str(_PYTHON_ROOT))

from printing.services import pdf_renderer  # noqa: E402


def _payload(sections: list[str] | None = None) -> dict:
    return {
        "factoryName": "白垩纪食品 F006",
        "planId": "PLAN-ID",
        "planNumber": "PLAN-20260720-001",
        "productTypeId": "PRODUCT-1",
        "sku": "CPF0060015",
        "productName": "黄油鸡成品800g",
        "batchDate": "2026-07-20",
        "bomRecipeId": "BOM-RECIPE-1",
        "bomVersion": 1,
        "workflowId": 105,
        "workflowVersion": 1,
        "generatedAt": "2026-07-20T12:30:00",
        "sections": sections or [
            "work-order", "material-requisition", "batching-sheet"
        ],
        "workOrder": {
            "productionOrderNumber": "WO-PLAN-001",
            "planNumber": "PLAN-20260720-001",
            "productName": "黄油鸡成品800g",
            "plannedQuantity": "5",
            "productUnit": "box",
            "productionDate": "2026-07-20",
            "status": "COMPLETED",
            "processes": [
                {"seq": 1, "name": "修油", "standardHours": "1", "operator": "张三"},
                {"seq": 2, "name": "定量包装", "standardHours": "0.83", "operator": "李四"},
            ],
        },
        "materialRequisition": {
            "planNumber": "PLAN-20260720-001",
            "productName": "黄油鸡成品800g",
            "status": "已领料",
            "items": [
                {
                    "materialName": f"原料{i}",
                    "category": "原料",
                    "unit": "kg",
                    "transactedQty": "1",
                    "plannedIssueQty": "1",
                    "deliveredQty": "1",
                    "actualUsedQty": "1",
                    "batchRefs": f"MB-{i:03d}",
                }
                for i in range(55)
            ],
        },
        "batchingSheet": {
            "planNumber": "PLAN-20260720-001",
            "productName": "黄油鸡成品800g",
            "plannedQuantity": "5",
            "productUnit": "box",
            "singlePotCapacity": "5",
            "unit": "box",
            "potCount": 1,
            "items": [
                {"materialName": "原料A", "unit": "kg", "totalQty": "5"}
            ],
        },
    }


def _require_chinese_font() -> None:
    if pdf_renderer._get_styles()["font"] == "Helvetica":
        pytest.skip("host has no embeddable Chinese font")


def _page_count(pdf: bytes) -> int:
    return len(re.findall(rb"/Type\s*/Page\b", pdf))


def test_renderer_is_registered_and_long_tables_repeat_headers() -> None:
    assert "production-document-package" in pdf_renderer.RENDERERS
    table = pdf_renderer._render_items_table(
        [{"name": "原料A"}], [("物料", "name", "LEFT")], "Helvetica"
    )
    assert table.repeatRows == 1
    assert table.splitByRow == 1


def test_full_package_is_one_pdf_with_cover_and_three_paginated_sections() -> None:
    _require_chinese_font()

    pdf = pdf_renderer.render_production_document_package(_payload())

    assert pdf.startswith(b"%PDF-")
    # Cover + three sections; the deliberately long requisition may add pages.
    assert _page_count(pdf) >= 4
    assert len(pdf) > 10_000


def test_selected_chapters_keep_one_cover_and_one_selected_section() -> None:
    _require_chinese_font()
    payload = _payload(["work-order"])

    pdf = pdf_renderer.render_production_document_package(payload)

    assert pdf.startswith(b"%PDF-")
    assert _page_count(pdf) == 2


def test_missing_selected_section_fails_closed() -> None:
    payload = _payload(["material-requisition"])
    payload.pop("materialRequisition")

    with pytest.raises(ValueError, match="缺少章节数据"):
        pdf_renderer.render_production_document_package(payload)


def test_unknown_or_empty_chapter_selection_is_rejected() -> None:
    payload = _payload(["unknown"])
    with pytest.raises(ValueError, match="章节选择无效"):
        pdf_renderer.render_production_document_package(payload)

    payload["sections"] = []
    # Empty is the supported default-all contract, not an empty print.
    payload["materialRequisition"] = _payload()["materialRequisition"]
    _require_chinese_font()
    assert _page_count(pdf_renderer.render_production_document_package(payload)) >= 4
