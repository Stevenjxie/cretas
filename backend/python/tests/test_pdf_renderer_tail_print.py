"""Unit tests for tail-print补齐: C-051 双单号 + 调拨指示单.

C-051: render_consolidated_material_requisition 现展示
  - salesOrderNumbers (关联销售单号)
  - planNumber       (生产计划单号)
  两者同时出现在 KV 头部 (双单号).

调拨指示单: render_transfer_instruction — 全新打印类型.

Each test renders minimal payload and asserts:
  - PDF bytes non-empty + %PDF magic header
  - RENDERERS dict registers the new key (transfer-instruction)

Skips gracefully if reportlab isn't installed.
"""
from __future__ import annotations

import pathlib
import sys

import pytest

_REPO_ROOT_PY = pathlib.Path(__file__).resolve().parent.parent
if str(_REPO_ROOT_PY) not in sys.path:
    sys.path.insert(0, str(_REPO_ROOT_PY))

try:
    from printing.services.pdf_renderer import (  # noqa: E402
        RENDERERS,
        render_consolidated_material_requisition,
        render_transfer_instruction,
    )
except ModuleNotFoundError as _exc:  # pragma: no cover — reportlab optional
    pytest.skip(f"reportlab not available: {_exc}", allow_module_level=True)


def _assert_valid_pdf(pdf_bytes: bytes, min_size: int = 800) -> None:
    """PDF bytes non-empty and start with standard PDF magic header."""
    assert isinstance(pdf_bytes, bytes), "renderer must return bytes"
    assert len(pdf_bytes) > min_size, (
        f"PDF too small ({len(pdf_bytes)} bytes) — likely empty or font-fallback only"
    )
    assert pdf_bytes.startswith(b"%PDF"), (
        f"PDF magic header missing — got first 8 bytes: {pdf_bytes[:8]!r}"
    )


# ==================== Registration ====================


def test_renderers_dict_contains_transfer_instruction():
    """RENDERERS dict must register 'transfer-instruction'."""
    assert "transfer-instruction" in RENDERERS, (
        "missing renderer registration: transfer-instruction"
    )
    assert callable(RENDERERS["transfer-instruction"])


def test_renderers_dict_still_has_consolidated_material_requisition():
    """existing consolidated-material-requisition key still registered."""
    assert "consolidated-material-requisition" in RENDERERS
    assert callable(RENDERERS["consolidated-material-requisition"])


# ==================== C-051 双单号渲染 ====================


def test_consolidated_material_requisition_dual_order_numbers_single_so():
    """C-051: 单 SO 场景 — salesOrderNumbers + planNumber 同时出现在头部."""
    pdf = render_consolidated_material_requisition({
        "factoryName": "白垩纪食品 — F006",
        "planId": "plan-001",
        "planNumber": "PP-20260601-001",
        "productName": "叮咚好食光卤猪舌",
        "printDate": "2026-06-11",
        "salesOrderNumbers": "SO-20260601-0012",    # C-051: 关联销售单号
        "sourceOrderId": "SO-20260601-0012",        # C-051: 单 SO ID
        "requisitionCount": 2,
        "items": [
            {"materialName": "猪舌", "spec": "鲜", "unit": "kg",
             "totalQty": "580", "batchRefs": "B20260601-01, B20260601-02"},
            {"materialName": "食盐", "spec": "精制", "unit": "kg",
             "totalQty": "12.5", "batchRefs": "B20260601-03"},
        ],
        "remark": "六扇门 6.1 批次",
    })
    _assert_valid_pdf(pdf, min_size=1200)


def test_consolidated_material_requisition_dual_order_numbers_multi_so():
    """C-051: 多 SO 合并场景 — salesOrderNumbers 包含多个 SO (逗号分隔)."""
    pdf = render_consolidated_material_requisition({
        "factoryName": "白垩纪食品 — F006",
        "planId": "plan-multi-001",
        "planNumber": "PP-20260601-MULTI",
        "productName": "叮咚好食光卤牛腱",
        "printDate": "2026-06-11",
        "salesOrderNumbers": "SO-20260601-0010, SO-20260601-0011",  # 多 SO
        "sourceOrderId": "SO-20260601-0010",
        "requisitionCount": 3,
        "items": [
            {"materialName": "牛腱", "spec": "鲜", "unit": "kg",
             "totalQty": "1200", "batchRefs": "B20260601-10"},
        ],
    })
    _assert_valid_pdf(pdf, min_size=1000)


def test_consolidated_material_requisition_fallback_no_sales_order():
    """C-051: 无 salesOrderNumbers (旧数据/stub fallback) — 渲染不报错, '-' 占位."""
    pdf = render_consolidated_material_requisition({
        "planId": "plan-stub",
        "planNumber": "PP-STUB",
        "productName": "测试产品",
        "printDate": "2026-06-11",
        # salesOrderNumbers 缺失 → renderer 应 fallback 到 sourceOrderId 或 "-"
        "requisitionCount": 0,
        "items": [],
    })
    _assert_valid_pdf(pdf, min_size=600)


def test_consolidated_material_requisition_dual_order_numbers_override_sourceOrderId():
    """C-051: 只有 sourceOrderId 无 salesOrderNumbers — 仍可渲染."""
    pdf = render_consolidated_material_requisition({
        "planId": "plan-002",
        "planNumber": "PP-20260601-002",
        "productName": "掌中宝",
        "printDate": "2026-06-11",
        # salesOrderNumbers 缺失但有 sourceOrderId
        "sourceOrderId": "SO-20260601-0020",
        "requisitionCount": 1,
        "items": [
            {"materialName": "鸡掌", "spec": "鲜冻", "unit": "kg",
             "totalQty": "240", "batchRefs": "B20260601-20"},
        ],
    })
    _assert_valid_pdf(pdf, min_size=900)


# ==================== 调拨指示单 ====================


def test_render_transfer_instruction_basic():
    """调拨指示单基本渲染 — 全字段输入."""
    pdf = render_transfer_instruction({
        "factoryName": "白垩纪食品 — F006",
        "transferId": "tr-001",
        "transferNumber": "TF-20260611-001",
        "transferDate": "2026-06-11",
        "sourceWarehouseId": "物流仓A",
        "targetWarehouseId": "工厂鲜棉仓",
        "status": "已审批",
        "requestedBy": "张权",
        "expectedArrivalDate": "2026-06-12",
        "remark": "六扇门6.1批次原料调拨",
        "items": [
            {"itemName": "猪舌", "spec": "鲜", "qty": "580", "unit": "kg",
             "batchId": "B20260601-01"},
            {"itemName": "食盐", "spec": "精制", "qty": "12.5", "unit": "kg",
             "batchId": "-"},
        ],
    })
    _assert_valid_pdf(pdf, min_size=1200)


def test_render_transfer_instruction_empty_items():
    """调拨指示单 — 空明细列表仍渲染 KV 头 + 签名区."""
    pdf = render_transfer_instruction({
        "transferNumber": "TF-EMPTY-001",
        "transferDate": "2026-06-11",
        "sourceWarehouseId": "(调出仓)",
        "targetWarehouseId": "(调入仓)",
        "status": "草稿",
        "items": [],
    })
    _assert_valid_pdf(pdf, min_size=600)


def test_render_transfer_instruction_stub_only():
    """调拨指示单 — 最小 stub payload (service 未注入时 fallback)."""
    pdf = render_transfer_instruction({
        "factoryName": "白垩纪食品",
        "transferId": "tr-stub",
        "printDate": "2026-06-11",
    })
    _assert_valid_pdf(pdf, min_size=500)


def test_render_transfer_instruction_status_variants():
    """各种状态字段渲染正常."""
    for status in ("草稿", "待审批", "已审批", "已发货", "已签收", "已确认", "已取消"):
        pdf = render_transfer_instruction({
            "transferNumber": f"TF-{status}",
            "transferDate": "2026-06-11",
            "status": status,
            "items": [],
        })
        _assert_valid_pdf(pdf, min_size=500)


def test_render_transfer_instruction_registered_via_renderers_dict():
    """RENDERERS['transfer-instruction'] 指向正确的 render 函数."""
    renderer_fn = RENDERERS.get("transfer-instruction")
    assert renderer_fn is not None
    # Call via RENDERERS dict (same path as Java PrintController proxyToPython)
    pdf = renderer_fn({
        "transferNumber": "TF-VIA-DICT",
        "transferDate": "2026-06-11",
        "sourceWarehouseId": "仓A",
        "targetWarehouseId": "仓B",
        "items": [{"itemName": "物料X", "qty": "100", "unit": "kg", "batchId": "-"}],
    })
    _assert_valid_pdf(pdf, min_size=900)
