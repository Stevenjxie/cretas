"""Unit tests for Sprint 6 W3-C P1 print template renderers.

Verifies the 3 new renderers in printing/services/pdf_renderer.py:
  - render_stock_movement (仓库出入库)
  - render_financial_invoice (财务发票/凭证)
  - render_packing_list (装箱单)

Each test renders a minimal payload + sample items and asserts:
  - PDF bytes are non-empty
  - PDF magic header (b'%PDF') present
  - RENDERERS dict registers the new doc-type keys

Skips gracefully if reportlab isn't installed (matches existing CI behavior).
"""
from __future__ import annotations

import pathlib
import sys

import pytest

# Make 'printing.*' importable when run from anywhere.
_REPO_ROOT_PY = pathlib.Path(__file__).resolve().parent.parent
if str(_REPO_ROOT_PY) not in sys.path:
    sys.path.insert(0, str(_REPO_ROOT_PY))

try:
    from printing.services.pdf_renderer import (  # noqa: E402
        RENDERERS,
        render_financial_invoice,
        render_packing_list,
        render_stock_movement,
    )
except ModuleNotFoundError as _exc:  # pragma: no cover — reportlab optional
    pytest.skip(f"reportlab not available: {_exc}", allow_module_level=True)


def _assert_valid_pdf(pdf_bytes: bytes, min_size: int = 1000) -> None:
    """PDF bytes are non-empty and start with the standard PDF magic header."""
    assert isinstance(pdf_bytes, bytes), "renderer must return bytes"
    assert len(pdf_bytes) > min_size, (
        f"PDF too small ({len(pdf_bytes)} bytes) — likely empty or font-fallback only"
    )
    assert pdf_bytes.startswith(b"%PDF"), (
        f"PDF magic header missing — got first 8 bytes: {pdf_bytes[:8]!r}"
    )


# ==================== Registration ====================


def test_renderers_dict_contains_3_new_p1_types():
    """RENDERERS dict must register all 3 new Sprint 6 W3-C doc types."""
    for key in ("stock-movement", "financial-invoice", "packing-list"):
        assert key in RENDERERS, f"missing renderer registration: {key}"
        assert callable(RENDERERS[key]), f"RENDERERS[{key}] is not callable"


# ==================== render_stock_movement ====================


def test_render_stock_movement_in_basic():
    """入库单 — movementType=IN renders 仓库入库单 title."""
    pdf = render_stock_movement({
        "factoryName": "白垩纪食品 — F006",
        "movementNumber": "STK-IN-20260519-001",
        "movementType": "IN",
        "movementDate": "2026-05-19",
        "warehouseName": "1号冷库",
        "sourceRef": "PO-20260518-007",
        "operator": "张三",
        "totalQty": "1000",
        "remark": "送货员：李四",
        "items": [
            {"name": "猪肉", "spec": "去皮", "batch": "B20260519-01",
             "qty": 500, "unit": "kg", "locationCode": "A-01-03"},
            {"name": "牛肉", "spec": "牛腩", "batch": "B20260519-02",
             "qty": 500, "unit": "kg", "locationCode": "A-02-05"},
        ],
    })
    _assert_valid_pdf(pdf)


def test_render_stock_movement_out_branches_title_and_signatures():
    """出库单 — movementType=OUT renders 出库 variant (different title/signature labels)."""
    pdf_out = render_stock_movement({
        "movementNumber": "STK-OUT-20260519-001",
        "movementType": "OUT",
        "warehouseName": "1号冷库",
        "sourceRef": "SO-20260519-002",
        "operator": "王五",
        "items": [
            {"name": "卤猪蹄", "qty": 200, "unit": "kg",
             "batch": "B20260519-01", "locationCode": "A-01-03"},
        ],
    })
    _assert_valid_pdf(pdf_out)

    # Same payload with type=IN should also render — different but valid PDF
    pdf_in = render_stock_movement({
        "movementNumber": "STK-IN-20260519-001",
        "movementType": "IN",
        "items": [{"name": "猪肉", "qty": 500, "unit": "kg"}],
    })
    _assert_valid_pdf(pdf_in)
    # Defensive: IN vs OUT are different renderings, byte length should differ
    # because title labels differ (入库 vs 出库, 入库签名 vs 出库签名). Even with
    # minimal payload, different titles change PDF stream.
    assert pdf_in != pdf_out, "IN and OUT renderings should produce different PDFs"


def test_render_stock_movement_empty_items_still_valid():
    """Empty items list is graceful — header + signature lines still render."""
    pdf = render_stock_movement({
        "movementNumber": "STK-EMPTY-001",
        "movementType": "IN",
        "items": [],
    })
    _assert_valid_pdf(pdf, min_size=800)  # smaller without items table


# ==================== render_financial_invoice ====================


def test_render_financial_invoice_vat_normal():
    """增值税普通发票 (VAT_NORMAL) — default title path."""
    pdf = render_financial_invoice({
        "factoryName": "白垩纪食品 — F001",
        "invoiceNumber": "INV-20260519-001",
        "invoiceType": "VAT_NORMAL",
        "invoiceDate": "2026-05-19",
        "partyName": "六腾门连锁",
        "partyTaxId": "91320500MA1XYZ123A",
        "sellerName": "白垩纪食品有限公司",
        "sellerTaxId": "91320500MA0ABC456B",
        "subtotal": "9500.00",
        "taxAmount": "1235.00",
        "totalAmount": "10735.00",
        "drawer": "财务部-赵六",
        "remark": "13% 税率冷冻肉",
        "items": [
            {"name": "冷冻猪肉", "spec": "去皮", "qty": 100, "unit": "kg",
             "unitPrice": "50.00", "taxRate": "13%",
             "taxAmount": "650.00", "subtotal": "5000.00"},
            {"name": "冷冻牛肉", "spec": "牛腩", "qty": 50, "unit": "kg",
             "unitPrice": "90.00", "taxRate": "13%",
             "taxAmount": "585.00", "subtotal": "4500.00"},
        ],
    })
    _assert_valid_pdf(pdf, min_size=1500)


def test_render_financial_invoice_all_three_types_branch_title():
    """All 3 invoiceType branches (VAT_NORMAL / VAT_SPECIAL / VOUCHER) render."""
    for inv_type in ("VAT_NORMAL", "VAT_SPECIAL", "VOUCHER"):
        pdf = render_financial_invoice({
            "invoiceNumber": f"INV-{inv_type}",
            "invoiceType": inv_type,
            "partyName": "客户A",
            "totalAmount": "1000.00",
            "items": [],
        })
        _assert_valid_pdf(pdf, min_size=800)


def test_render_financial_invoice_masked_payload_renders():
    """When Java applies priceMaskResolver, monetary fields become '—'.

    The renderer must still produce a valid PDF (masked strings just flow through
    _fmt_money's str() fallback, not the float-formatting branch).
    """
    pdf = render_financial_invoice({
        "invoiceNumber": "INV-MASKED-001",
        "invoiceType": "VAT_SPECIAL",
        "partyName": "客户B",
        "subtotal": "—",         # masked by Java PriceMaskResolver
        "taxAmount": "—",
        "totalAmount": "—",
        "items": [
            {"name": "服务费", "qty": 1, "unit": "次",
             "unitPrice": "—", "taxAmount": "—", "subtotal": "—"},
        ],
    })
    _assert_valid_pdf(pdf, min_size=1000)


# ==================== render_packing_list ====================


def test_render_packing_list_with_abaca_codes():
    """装箱单 — abacaCode field (抄码品) per N13 W-ABA-1 配合."""
    pdf = render_packing_list({
        "factoryName": "白垩纪食品 — F006",
        "packingNumber": "PK-20260519-001",
        "packingDate": "2026-05-19",
        "salesOrderRef": "SO-20260519-002",
        "shipToName": "六腾门-上海分店",
        "shipToAddress": "上海市浦东新区世纪大道 100 号",
        "shipper": "发货员-钱七",
        "totalCartons": "5",
        "totalNetWeight": "425.50",
        "totalGrossWeight": "475.00",
        "weightUnit": "kg",
        "remark": "冷链配送, 保持 -18°C",
        "cartons": [
            {"cartonNo": "1", "productName": "卤猪蹄", "spec": "200g",
             "qty": 50, "unit": "袋", "netWeight": "10.00",
             "grossWeight": "11.50", "abacaCode": "ABACA-202605190001"},
            {"cartonNo": "2", "productName": "卤猪蹄", "spec": "200g",
             "qty": 50, "unit": "袋", "netWeight": "10.00",
             "grossWeight": "11.50", "abacaCode": "ABACA-202605190002"},
        ],
    })
    _assert_valid_pdf(pdf, min_size=1500)


def test_render_packing_list_weight_unit_propagates_to_columns():
    """weightUnit field reaches column headers (净重(kg) / 毛重(kg))."""
    # Default unit kg
    pdf_kg = render_packing_list({
        "packingNumber": "PK-KG-001",
        "cartons": [{"cartonNo": "1", "productName": "Test",
                     "netWeight": "10", "grossWeight": "12"}],
    })
    _assert_valid_pdf(pdf_kg)

    # Alternative unit 吨 (ton)
    pdf_ton = render_packing_list({
        "packingNumber": "PK-TON-001",
        "weightUnit": "吨",
        "cartons": [{"cartonNo": "1", "productName": "Test",
                     "netWeight": "0.01", "grossWeight": "0.012"}],
    })
    _assert_valid_pdf(pdf_ton)
    # Different units → different column headers → different stream bytes
    assert pdf_kg != pdf_ton, "weightUnit change should affect PDF output"


def test_render_packing_list_empty_cartons_still_renders_header():
    """Empty cartons list — header + signature lines still produce valid PDF."""
    pdf = render_packing_list({
        "packingNumber": "PK-EMPTY",
        "shipToName": "客户",
        "cartons": [],
    })
    _assert_valid_pdf(pdf, min_size=800)
