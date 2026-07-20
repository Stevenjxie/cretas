"""PDF rendering helpers — Chinese-font registration, 5 单据生成函数.

reportlab + qrcode (用于采购单).

@author Cretas Team — Track C
@since 2026-05-15 (C-PRT-1)
"""
from __future__ import annotations

import io
import logging
from typing import Any, Optional

logger = logging.getLogger(__name__)


# ==================== Font 注册 (单次) ====================

_CHINESE_FONT: Optional[str] = None  # cached after first registration


def _register_chinese_font() -> str:
    """注册中文字体 — 与 smartbi/services/pdf_generator.py 模式一致.

    返回字体名 (如 'ChineseFont' 或 'Helvetica' fallback).
    """
    global _CHINESE_FONT
    if _CHINESE_FONT is not None:
        return _CHINESE_FONT

    from reportlab.pdfbase import pdfmetrics
    from reportlab.pdfbase.ttfonts import TTFont

    candidate_paths = [
        "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
        "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
        "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
        "/System/Library/Fonts/PingFang.ttc",
        "C:/Windows/Fonts/msyh.ttc",  # Windows MS YaHei (dev only)
        "C:/Windows/Fonts/simhei.ttf",
    ]
    for path in candidate_paths:
        try:
            pdfmetrics.registerFont(TTFont("ChineseFont", path))
            _CHINESE_FONT = "ChineseFont"
            logger.info("Registered Chinese font: %s", path)
            return _CHINESE_FONT
        except Exception:
            continue
    logger.warning("无中文字体可用, 中文将显示为 □ — 部署机器请安装 wqy-zenhei 或 Noto-CJK")
    _CHINESE_FONT = "Helvetica"
    return _CHINESE_FONT


# ==================== Shared styles ====================

def _get_styles() -> dict[str, Any]:
    from reportlab.lib.colors import HexColor
    from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet

    font = _register_chinese_font()
    base = getSampleStyleSheet()
    return {
        "font": font,
        "title": ParagraphStyle("Title", parent=base["Title"], fontName=font, fontSize=20,
                                textColor=HexColor("#1f2937"), spaceAfter=14, alignment=1),
        "h2": ParagraphStyle("H2", parent=base["Heading2"], fontName=font, fontSize=13,
                             textColor=HexColor("#1f2937"), spaceBefore=8, spaceAfter=6),
        "body": ParagraphStyle("Body", parent=base["Normal"], fontName=font, fontSize=10,
                               textColor=HexColor("#1f2937"), leading=14),
        "small": ParagraphStyle("Small", parent=base["Normal"], fontName=font, fontSize=8,
                                textColor=HexColor("#6b7280"), leading=11),
    }


# ==================== Common helpers ====================

def _fmt_money(v: Any) -> str:
    if v is None:
        return "-"
    try:
        return f"¥{float(v):,.2f}"
    except (TypeError, ValueError):
        return str(v)


def _fmt_qty(v: Any) -> str:
    if v is None:
        return "-"
    try:
        return f"{float(v):,.2f}"
    except (TypeError, ValueError):
        return str(v)


def _build_qr_image(content: str, size_cm: float = 2.5) -> Any:
    """生成二维码 reportlab Image — 用于采购单扫码入库.

    返回 reportlab Image 或 None (qrcode lib 缺失时).
    """
    try:
        import qrcode
        from reportlab.lib.units import cm
        from reportlab.platypus import Image as RLImage

        img = qrcode.make(content)
        bio = io.BytesIO()
        img.save(bio, format="PNG")
        bio.seek(0)
        return RLImage(bio, width=size_cm * cm, height=size_cm * cm)
    except ImportError:
        logger.warning("qrcode lib 未装, 采购单将无二维码")
        return None
    except Exception as e:
        logger.warning("二维码生成失败: %s", e)
        return None


def _make_doc(buffer: io.BytesIO) -> Any:
    from reportlab.lib.pagesizes import A4
    from reportlab.lib.units import cm
    from reportlab.platypus import SimpleDocTemplate

    return SimpleDocTemplate(
        buffer, pagesize=A4,
        leftMargin=1.5 * cm, rightMargin=1.5 * cm,
        topMargin=1.5 * cm, bottomMargin=1.5 * cm,
    )


def _render_items_table(items: list[dict], columns: list[tuple[str, str, str]], font: str) -> Any:
    """通用明细表渲染.

    columns: list of (header_label, key, align) e.g. [("产品", "name", "LEFT"), ("数量", "qty", "RIGHT")]
    """
    from reportlab.lib import colors
    from reportlab.platypus import Table, TableStyle

    headers = [c[0] for c in columns]
    keys = [c[1] for c in columns]
    aligns = [c[2] for c in columns]

    rows: list[list[str]] = [headers]
    for item in items or []:
        rows.append([str(item.get(k, "-") or "-") for k in keys])

    table = Table(rows, repeatRows=1, splitByRow=1)
    style_cmds: list[tuple] = [
        ("FONT", (0, 0), (-1, -1), font, 9),
        ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#f3f4f6")),
        ("TEXTCOLOR", (0, 0), (-1, 0), colors.HexColor("#1f2937")),
        ("LINEBELOW", (0, 0), (-1, 0), 0.5, colors.HexColor("#9ca3af")),
        ("GRID", (0, 1), (-1, -1), 0.25, colors.HexColor("#e5e7eb")),
        ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
        ("LEFTPADDING", (0, 0), (-1, -1), 6),
        ("RIGHTPADDING", (0, 0), (-1, -1), 6),
        ("TOPPADDING", (0, 0), (-1, -1), 4),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 4),
    ]
    for col_idx, align in enumerate(aligns):
        style_cmds.append(("ALIGN", (col_idx, 0), (col_idx, -1), align))
    table.setStyle(TableStyle(style_cmds))
    return table


# ==================== 5 单据 generator ====================

def render_sales_order(data: dict) -> bytes:
    """销售订单 PDF.

    expected data: { orderNumber, orderDate, customerName, salesperson, totalAmount,
    remark, items: [{name, qty, unit, unitPrice, subtotal}], factory }
    """
    from reportlab.lib.units import cm
    from reportlab.platypus import Paragraph, Spacer

    s = _get_styles()
    buffer = io.BytesIO()
    doc = _make_doc(buffer)
    story: list[Any] = [
        Paragraph(data.get("factoryName") or "白垩纪食品", s["small"]),
        Paragraph("销售订单 · Sales Order", s["title"]),
        _kv_table([
            ("订单号", data.get("orderNumber", "-")),
            ("下单日期", data.get("orderDate", "-")),
            ("客户", data.get("customerName", "-")),
            ("销售员", data.get("salesperson", "-")),
            ("总金额", _fmt_money(data.get("totalAmount"))),
        ], s["font"]),
        Spacer(1, 0.5 * cm),
        Paragraph("订单明细", s["h2"]),
        _render_items_table(
            data.get("items") or [],
            [("产品", "name", "LEFT"), ("规格", "spec", "LEFT"),
             ("数量", "qty", "RIGHT"), ("单位", "unit", "CENTER"),
             ("单价", "unitPriceFormatted", "RIGHT"), ("小计", "subtotalFormatted", "RIGHT")],
            s["font"],
        ),
    ]
    if data.get("remark"):
        story.extend([Spacer(1, 0.5 * cm), Paragraph("备注", s["h2"]), Paragraph(str(data["remark"]), s["body"])])
    doc.build(story)
    return buffer.getvalue()


def render_purchase_order(data: dict) -> bytes:
    """采购订单 PDF — 含二维码 (供仓管员扫码入库).

    expected data: { orderNumber, orderDate, supplierName, expectedDeliveryDate, totalAmount, remark, items, qrPayload }
    """
    from reportlab.lib.units import cm
    from reportlab.platypus import Paragraph, Spacer, Table

    s = _get_styles()
    buffer = io.BytesIO()
    doc = _make_doc(buffer)

    qr_payload = data.get("qrPayload") or f"PO:{data.get('orderNumber', '-')}"
    qr_img = _build_qr_image(qr_payload, size_cm=2.5)

    header_kv = _kv_table([
        ("采购单号", data.get("orderNumber", "-")),
        ("下单日期", data.get("orderDate", "-")),
        ("供应商", data.get("supplierName", "-")),
        ("期望交货", data.get("expectedDeliveryDate", "-")),
        ("总金额", _fmt_money(data.get("totalAmount"))),
    ], s["font"], col_widths=(3.0 * cm, 9.0 * cm))

    if qr_img is not None:
        header_with_qr = Table(
            [[header_kv, qr_img]],
            colWidths=(12.0 * cm, 4.5 * cm),
        )
        header_with_qr.setStyle([("VALIGN", (0, 0), (-1, -1), "TOP")])
        story_header: list[Any] = [header_with_qr]
    else:
        story_header = [header_kv]

    story: list[Any] = [
        Paragraph(data.get("factoryName") or "白垩纪食品", s["small"]),
        Paragraph("采购订单 · Purchase Order", s["title"]),
        *story_header,
        Spacer(1, 0.5 * cm),
        Paragraph("采购明细", s["h2"]),
        _render_items_table(
            data.get("items") or [],
            [("原料", "name", "LEFT"), ("规格", "spec", "LEFT"),
             ("数量", "qty", "RIGHT"), ("单位", "unit", "CENTER"),
             ("单价", "unitPriceFormatted", "RIGHT"), ("小计", "subtotalFormatted", "RIGHT")],
            s["font"],
        ),
    ]
    if data.get("remark"):
        story.extend([Spacer(1, 0.5 * cm), Paragraph("备注", s["h2"]), Paragraph(str(data["remark"]), s["body"])])
    story.extend([
        Spacer(1, 1.0 * cm),
        Paragraph("仓管员收货确认: ____________________________", s["body"]),
        Paragraph("送货员签名: ________________________________", s["body"]),
    ])
    doc.build(story)
    return buffer.getvalue()


def render_quotation(data: dict) -> bytes:
    """报价单 PDF.

    expected data: { quotationNumber, quotationDate, customerName, salesperson, validUntil, totalAmount, items, remark }
    """
    from reportlab.lib.units import cm
    from reportlab.platypus import Paragraph, Spacer

    s = _get_styles()
    buffer = io.BytesIO()
    doc = _make_doc(buffer)
    story: list[Any] = [
        Paragraph(data.get("factoryName") or "白垩纪食品", s["small"]),
        Paragraph("报价单 · Quotation", s["title"]),
        _kv_table([
            ("报价编号", data.get("quotationNumber", "-")),
            ("报价日期", data.get("quotationDate", "-")),
            ("客户", data.get("customerName", "-")),
            ("有效期至", data.get("validUntil", "-")),
            ("销售员", data.get("salesperson", "-")),
            ("报价合计", _fmt_money(data.get("totalAmount"))),
        ], s["font"]),
        Spacer(1, 0.5 * cm),
        Paragraph("报价明细", s["h2"]),
        _render_items_table(
            data.get("items") or [],
            [("产品", "name", "LEFT"), ("规格", "spec", "LEFT"),
             ("数量", "qty", "RIGHT"), ("单位", "unit", "CENTER"),
             ("单价", "unitPriceFormatted", "RIGHT"), ("小计", "subtotalFormatted", "RIGHT")],
            s["font"],
        ),
    ]
    if data.get("remark"):
        story.extend([Spacer(1, 0.5 * cm), Paragraph("条款 / 备注", s["h2"]), Paragraph(str(data["remark"]), s["body"])])
    doc.build(story)
    return buffer.getvalue()


def render_production_task(data: dict) -> bytes:
    """生产任务单 PDF.

    expected data: { taskNumber, productName, plannedQuantity, unit, startDate, endDate,
    workshopName, supervisor, processes: [{seq, name, duration}] }
    """
    from reportlab.lib.units import cm
    from reportlab.platypus import Paragraph, Spacer

    s = _get_styles()
    buffer = io.BytesIO()
    doc = _make_doc(buffer)
    story: list[Any] = [
        Paragraph(data.get("factoryName") or "白垩纪食品", s["small"]),
        Paragraph("生产任务单 · Production Task", s["title"]),
        _kv_table([
            ("任务单号", data.get("taskNumber", "-")),
            ("产品", data.get("productName", "-")),
            ("计划数量", f'{_fmt_qty(data.get("plannedQuantity"))} {data.get("unit", "")}'),
            ("生产车间", data.get("workshopName", "-")),
            ("开始时间", data.get("startDate", "-")),
            ("计划完成", data.get("endDate", "-")),
            ("责任人", data.get("supervisor", "-")),
        ], s["font"]),
        Spacer(1, 0.5 * cm),
        Paragraph("工序流水", s["h2"]),
        _render_items_table(
            data.get("processes") or [],
            [("序号", "seq", "CENTER"), ("工序", "name", "LEFT"),
             ("预计耗时", "duration", "RIGHT"), ("操作人", "operator", "LEFT")],
            s["font"],
        ),
    ]
    doc.build(story)
    return buffer.getvalue()


def render_material_requisition(data: dict) -> bytes:
    """领料单 PDF.

    expected data: { requisitionNumber, productName, plannedQuantity, requestDate,
    workshop, requester, items: [{name, plannedQty, actualQty, unit, batch}] }
    """
    from reportlab.lib.units import cm
    from reportlab.platypus import Paragraph, Spacer

    s = _get_styles()
    buffer = io.BytesIO()
    doc = _make_doc(buffer)
    story: list[Any] = [
        Paragraph(data.get("factoryName") or "白垩纪食品", s["small"]),
        Paragraph("领料单 · Material Requisition", s["title"]),
        _kv_table([
            ("领料单号", data.get("requisitionNumber", "-")),
            ("生产产品", data.get("productName", "-")),
            ("计划生产", f'{_fmt_qty(data.get("plannedQuantity"))} {data.get("unit", "")}'),
            ("领料日期", data.get("requestDate", "-")),
            ("领料车间", data.get("workshop", "-")),
            ("领料人", data.get("requester", "-")),
        ], s["font"]),
        Spacer(1, 0.5 * cm),
        Paragraph("领料明细", s["h2"]),
        _render_items_table(
            data.get("items") or [],
            [("原料", "name", "LEFT"), ("批次", "batch", "LEFT"),
             ("应领", "plannedQty", "RIGHT"), ("实领", "actualQty", "RIGHT"),
             ("单位", "unit", "CENTER")],
            s["font"],
        ),
        Spacer(1, 1.0 * cm),
        Paragraph("仓管员发料签名: ____________________________", s["body"]),
        Paragraph("领料人签名: ________________________________", s["body"]),
    ]
    doc.build(story)
    return buffer.getvalue()


# ==================== Internal helper: KV table ====================

def _kv_table(pairs: list[tuple[str, str]], font: str, col_widths: tuple = None) -> Any:
    from reportlab.lib import colors
    from reportlab.lib.units import cm
    from reportlab.platypus import Table, TableStyle

    rows = [[label, value] for label, value in pairs]
    widths = col_widths or (3.0 * cm, 13.5 * cm)
    table = Table(rows, colWidths=widths)
    table.setStyle(TableStyle([
        ("FONT", (0, 0), (-1, -1), font, 10),
        ("TEXTCOLOR", (0, 0), (0, -1), colors.HexColor("#6b7280")),
        ("TEXTCOLOR", (1, 0), (1, -1), colors.HexColor("#1f2937")),
        ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
        ("LINEBELOW", (0, 0), (-1, -1), 0.25, colors.HexColor("#e5e7eb")),
        ("LEFTPADDING", (0, 0), (-1, -1), 4),
        ("TOPPADDING", (0, 0), (-1, -1), 5),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
    ]))
    return table


# ==================== Sprint 6 W3-C: 3 P1 templates (P1) ====================
#
# Sprint 6 W3-C ships 3 new P1 print template categories per Round 13 §13:
#   - stock-movement (仓库出入库) — F006 高频 仓管员场景
#   - financial-invoice (财务发票/凭证) — 大客户记账, 跟数电票协同
#   - packing-list (装箱单) — 跟 N13 W-ABA-1 抄码品配合
#
# All 3 follow the existing pattern: data dict in → PDF bytes out, KV header +
# 明细 table + optional signature line. Java side applies price masking on
# invoice/stock-movement BEFORE calling here (per PrintController.applyPriceMask).


def render_stock_movement(data: dict) -> bytes:
    """仓库出入库 PDF — 入库单 / 出库单 通用 (movementType 决定标题).

    expected data: {
      movementNumber, movementType ("IN"|"OUT"), movementDate, warehouseName,
      sourceRef (源单号 e.g. PO-123 / SO-456),  operator, totalQty,
      remark, items: [{name, spec, batch, qty, unit, locationCode}]
    }

    入库 (IN): "仓库入库单" — Receive (source = 采购单 / 生产入库).
    出库 (OUT): "仓库出库单" — Issue (source = 销售单 / 领料单).
    """
    from reportlab.lib.units import cm
    from reportlab.platypus import Paragraph, Spacer

    s = _get_styles()
    buffer = io.BytesIO()
    doc = _make_doc(buffer)

    is_in = (data.get("movementType") or "").upper() == "IN"
    title_zh = "仓库入库单" if is_in else "仓库出库单"
    title_en = "Stock Receive" if is_in else "Stock Issue"
    source_label = "源采购单" if is_in else "源销售单 / 领料单"
    signature_label = "仓管员入库签名" if is_in else "仓管员出库签名"
    counterparty_label = "送货员签名" if is_in else "领料人签名"

    story: list[Any] = [
        Paragraph(data.get("factoryName") or "白垩纪食品", s["small"]),
        Paragraph(f"{title_zh} · {title_en}", s["title"]),
        _kv_table([
            ("单号", data.get("movementNumber", "-")),
            ("日期", data.get("movementDate", "-")),
            ("仓库", data.get("warehouseName", "-")),
            (source_label, data.get("sourceRef", "-")),
            ("操作员", data.get("operator", "-")),
            ("总数量", _fmt_qty(data.get("totalQty"))),
        ], s["font"]),
        Spacer(1, 0.5 * cm),
        Paragraph("出入库明细", s["h2"]),
        _render_items_table(
            data.get("items") or [],
            [("物料", "name", "LEFT"), ("规格", "spec", "LEFT"),
             ("批次", "batch", "LEFT"), ("数量", "qty", "RIGHT"),
             ("单位", "unit", "CENTER"), ("库位", "locationCode", "LEFT")],
            s["font"],
        ),
    ]
    if data.get("remark"):
        story.extend([Spacer(1, 0.5 * cm), Paragraph("备注", s["h2"]),
                      Paragraph(str(data["remark"]), s["body"])])
    story.extend([
        Spacer(1, 1.0 * cm),
        Paragraph(f"{signature_label}: ____________________________", s["body"]),
        Paragraph(f"{counterparty_label}: ____________________________", s["body"]),
    ])
    doc.build(story)
    return buffer.getvalue()


def render_financial_invoice(data: dict) -> bytes:
    """财务发票/凭证 PDF — 跟 PR #52 数电票 协同.

    expected data: {
      invoiceNumber, invoiceType ("VAT_NORMAL"|"VAT_SPECIAL"|"VOUCHER"),
      invoiceDate, partyName (购买方/收款方), partyTaxId,
      sellerName, sellerTaxId,
      items: [{name, spec, qty, unit, unitPrice, taxRate, subtotal, taxAmount}],
      subtotal, taxAmount, totalAmount, remark, drawer (开票人)
    }

    invoiceType:
      VAT_NORMAL  — 增值税普通发票 (普票)
      VAT_SPECIAL — 增值税专用发票 (专票, 可抵扣)
      VOUCHER     — 记账凭证 (内部财务用)
    """
    from reportlab.lib.units import cm
    from reportlab.platypus import Paragraph, Spacer

    s = _get_styles()
    buffer = io.BytesIO()
    doc = _make_doc(buffer)

    inv_type = (data.get("invoiceType") or "VAT_NORMAL").upper()
    title_map = {
        "VAT_NORMAL": ("增值税普通发票", "VAT Invoice"),
        "VAT_SPECIAL": ("增值税专用发票", "VAT Special Invoice"),
        "VOUCHER": ("记账凭证", "Accounting Voucher"),
    }
    title_zh, title_en = title_map.get(inv_type, ("发票", "Invoice"))

    story: list[Any] = [
        Paragraph(data.get("factoryName") or "白垩纪食品", s["small"]),
        Paragraph(f"{title_zh} · {title_en}", s["title"]),
        _kv_table([
            ("发票号", data.get("invoiceNumber", "-")),
            ("开票日期", data.get("invoiceDate", "-")),
            ("购买方 / 收款方", data.get("partyName", "-")),
            ("税号", data.get("partyTaxId", "-")),
            ("销售方", data.get("sellerName", "-")),
            ("销方税号", data.get("sellerTaxId", "-")),
        ], s["font"]),
        Spacer(1, 0.5 * cm),
        Paragraph("发票明细", s["h2"]),
        _render_items_table(
            data.get("items") or [],
            [("货物 / 服务", "name", "LEFT"), ("规格", "spec", "LEFT"),
             ("数量", "qty", "RIGHT"), ("单价", "unitPrice", "RIGHT"),
             ("税率", "taxRate", "CENTER"), ("税额", "taxAmount", "RIGHT"),
             ("小计", "subtotal", "RIGHT")],
            s["font"],
        ),
        Spacer(1, 0.5 * cm),
        _kv_table([
            ("不含税合计", _fmt_money(data.get("subtotal"))),
            ("税额合计", _fmt_money(data.get("taxAmount"))),
            ("价税合计", _fmt_money(data.get("totalAmount"))),
        ], s["font"]),
    ]
    if data.get("remark"):
        story.extend([Spacer(1, 0.5 * cm), Paragraph("备注", s["h2"]),
                      Paragraph(str(data["remark"]), s["body"])])
    story.extend([
        Spacer(1, 1.0 * cm),
        Paragraph(f"开票人: {data.get('drawer', '-')}", s["body"]),
        Paragraph("收款人: ____________  复核: ____________", s["body"]),
    ])
    doc.build(story)
    return buffer.getvalue()


def render_packing_list(data: dict) -> bytes:
    """装箱单 PDF — 跟 N13 W-ABA-1 抄码品 配合.

    expected data: {
      packingNumber, packingDate, salesOrderRef (关联销售单),
      shipToName, shipToAddress, shipper (发货员),
      totalCartons, totalNetWeight, totalGrossWeight, weightUnit,
      cartons: [{cartonNo, productName, spec, qty, unit, netWeight,
                 grossWeight, abacaCode (抄码)}],
      remark
    }
    """
    from reportlab.lib.units import cm
    from reportlab.platypus import Paragraph, Spacer

    s = _get_styles()
    buffer = io.BytesIO()
    doc = _make_doc(buffer)

    weight_unit = data.get("weightUnit") or "kg"

    story: list[Any] = [
        Paragraph(data.get("factoryName") or "白垩纪食品", s["small"]),
        Paragraph("装箱单 · Packing List", s["title"]),
        _kv_table([
            ("装箱单号", data.get("packingNumber", "-")),
            ("装箱日期", data.get("packingDate", "-")),
            ("关联销售单", data.get("salesOrderRef", "-")),
            ("收货方", data.get("shipToName", "-")),
            ("收货地址", data.get("shipToAddress", "-")),
            ("发货员", data.get("shipper", "-")),
            ("总箱数", str(data.get("totalCartons", "-"))),
            (f"总净重 ({weight_unit})", _fmt_qty(data.get("totalNetWeight"))),
            (f"总毛重 ({weight_unit})", _fmt_qty(data.get("totalGrossWeight"))),
        ], s["font"]),
        Spacer(1, 0.5 * cm),
        Paragraph("装箱明细", s["h2"]),
        _render_items_table(
            data.get("cartons") or [],
            [("箱号", "cartonNo", "CENTER"), ("产品", "productName", "LEFT"),
             ("规格", "spec", "LEFT"), ("数量", "qty", "RIGHT"),
             ("单位", "unit", "CENTER"), (f"净重({weight_unit})", "netWeight", "RIGHT"),
             (f"毛重({weight_unit})", "grossWeight", "RIGHT"),
             ("抄码", "abacaCode", "LEFT")],
            s["font"],
        ),
    ]
    if data.get("remark"):
        story.extend([Spacer(1, 0.5 * cm), Paragraph("备注", s["h2"]),
                      Paragraph(str(data["remark"]), s["body"])])
    story.extend([
        Spacer(1, 1.0 * cm),
        Paragraph("发货员签名: ____________________________", s["body"]),
        Paragraph("承运司机签名: ____________________________", s["body"]),
        Paragraph("收货方签收: ____________________________", s["body"]),
    ])
    doc.build(story)
    return buffer.getvalue()


def render_production_work_order(data: dict) -> bytes:
    """生产工单 (公单) PDF — SP12 T8 / N5 完整字段.

    N5 新增抬头字段: customerName, deliveryDate, createdByName/createdBy (制单人).
    N5 新增投料列: unitCost (单位成本, 无数据时留空).
    N5 扩展签字栏: 制单人 / 审核人 / 领料人 / 生产负责人 四联.
    N5 半成品分类: materialItems.category="半成品" 列入 plannedSemiFinishedQty 列.

    expected data: {
      factoryName, planId, planNumber, productionOrderNumber, salesOrderNumbers,
      customerName,          # N5: 客户名称 (from SO)
      productName, productUnit, plannedQuantity, expectedOutput, status,
      plannedDate, productionDate,
      expectedCompletionDate, deliveryDate,  # N5: deliveryDate = expectedCompletionDate alias
      printDate,
      createdByName, createdBy,  # N5: 制单人
      printedBy, printedAccount,
      materialItems: [{materialName, category, unit, plannedRawQty,
                      plannedAuxiliaryQty, plannedSemiFinishedQty,
                      unitCost,           # N5: 单位成本 (str or None → blank)
                      actualUsedQty, batchRefs}],
      processes: [{seq, name, standardHours, operator}],
      remark
    }
    """
    from reportlab.lib.units import cm
    from reportlab.platypus import Paragraph, Spacer

    s = _get_styles()
    buffer = io.BytesIO()
    doc = _make_doc(buffer)

    delivery_date = (
        data.get("deliveryDate")
        or data.get("expectedCompletionDate")
        or "-"
    )

    story: list[Any] = [
        Paragraph(data.get("factoryName") or "白垩纪食品", s["small"]),
        Paragraph("生产工单 · Production Work Order", s["title"]),
        _kv_table([
            ("销售订单号", data.get("salesOrderNumbers") or data.get("sourceOrderId", "-")),
            ("生产订单号", data.get("productionOrderNumber") or data.get("planNumber") or data.get("planId", "-")),
            ("客户名称", data.get("customerName", "-")),       # N5
            ("产品名称", data.get("productName", "-")),
            ("预计产量", f'{_fmt_qty(data.get("expectedOutput") or data.get("plannedQuantity"))} {data.get("productUnit", "kg")}'),  # noqa: E501
            # 状态行已删 — 打印工单不需要显示内部状态 (PENDING 等对操作员无意义)
            ("生产日期", data.get("productionDate") or data.get("plannedDate", "-")),
            ("交货日期", delivery_date),                        # N5
            ("打印日期", data.get("printDate", "-")),
            ("制单人", data.get("createdByName") or data.get("preparedBy", "-")),   # N5
            ("打印人", data.get("printedBy", "-")),
            # 预计完成: 空/"-"时不渲染此行
            *([("预计完成", data.get("expectedCompletionDate"))]
              if data.get("expectedCompletionDate") and data.get("expectedCompletionDate") != "-"
              else []),
        ], s["font"]),
        Spacer(1, 0.5 * cm),
        Paragraph("投料 / 领用明细", s["h2"]),
        _render_items_table(
            data.get("materialItems") or [],
            [("物料名称", "materialName", "LEFT"),
             ("分类", "category", "CENTER"),
             ("单位", "unit", "CENTER"),
             ("原料计划量", "plannedRawQty", "RIGHT"),
             ("辅料计划量", "plannedAuxiliaryQty", "RIGHT"),
             ("半成品计划量", "plannedSemiFinishedQty", "RIGHT"),
             ("单位成本", "unitCost", "RIGHT"),               # N5
             ("实际领用(结单填)", "actualUsedQty", "RIGHT"),
             ("批次", "batchRefs", "LEFT")],
            s["font"],
        ),
    ]
    if not data.get("materialItems"):
        story.extend([Spacer(1, 0.2 * cm),
                      Paragraph("暂无真实物料需求数据，待领料单生成后显示。", s["body"])])
    # 工序列表: 有数据才显示，空则隐藏整段 (PENDING 计划无批次时空列表不渲染)
    if data.get("processes"):
        story.extend([
            Spacer(1, 0.5 * cm),
            Paragraph("工序列表", s["h2"]),
            _render_items_table(
                data.get("processes"),
                [("序号", "seq", "CENTER"), ("工序名称", "name", "LEFT"),
                 ("标准工时(h)", "standardHours", "RIGHT"), ("负责人", "operator", "LEFT")],
                s["font"],
            ),
        ])
    if data.get("remark"):
        story.extend([Spacer(1, 0.5 * cm), Paragraph("备注", s["h2"]),
                      Paragraph(str(data["remark"]), s["body"])])
    # N5 签字栏 — 四联
    story.extend([
        Spacer(1, 1.0 * cm),
        Paragraph("制单人: ________________________  "
                  "审核人: ________________________", s["body"]),
        Spacer(1, 0.4 * cm),
        Paragraph("领料人: ________________________  "
                  "生产负责人: ____________________", s["body"]),
    ])
    doc.build(story)
    return buffer.getvalue()


def render_consolidated_material_requisition(data: dict) -> bytes:
    """汇总领料单 PDF — SP12 T8.

    跨批次汇总同一计划下所有领料需求.

    C-051 双单号渲染: KV 头部同时显示 salesOrderNumbers (销售单号) 和 planNumber (生产计划单号).

    expected data: {
      factoryName, planId, planNumber, productName, printDate,
      salesOrderNumbers,   # C-051 新增: 关联销售单号 (多 SO 逗号分隔)
      sourceOrderId,       # C-051 新增: 单 SO 时的销售单 ID
      requisitionCount,
      printedBy, printedAccount,
      items: [{materialName, category, unit, transactedQty(成交/应需),
               plannedIssueQty(打算/已拣), deliveredQty(送到/已发),
               actualUsedQty(实际领用), batchRefs}],
      remark
    }
    """
    from reportlab.lib.units import cm
    from reportlab.platypus import Paragraph, Spacer

    s = _get_styles()
    buffer = io.BytesIO()
    doc = _make_doc(buffer)

    # C-051: 双单号 — 销售单号 + 生产计划单号均展示
    sales_order_numbers = data.get("salesOrderNumbers") or data.get("sourceOrderId") or "-"
    plan_number = data.get("planNumber") or data.get("planId", "-")

    story: list[Any] = [
        Paragraph(data.get("factoryName") or "白垩纪食品", s["small"]),
        Paragraph("汇总领料单 · Consolidated Material Requisition", s["title"]),
        _kv_table([
            ("关联销售单号", sales_order_numbers),    # C-051 双单号第一行
            ("生产计划单号", plan_number),            # C-051 双单号第二行
            ("生产产品", data.get("productName", "-")),
            ("打印日期", data.get("printDate", "-")),
            ("打印人", data.get("printedBy", "-")),
            ("打印账号", data.get("printedAccount", "-")),
            ("涉及批次数", str(data.get("requisitionCount", 0))),
        ], s["font"]),
        Spacer(1, 0.5 * cm),
        Paragraph("汇总领料明细", s["h2"]),
        _render_items_table(
            data.get("items") or [],
            # 转录行2902-2904 [86:53-55]: 预领量公单含 成交/打算/送到 三列。
            # 成交(应需)=requiredQty, 打算(已拣)=pickedQty, 送到(已发)=issuedQty, 实际领用=consumedQty。
            # 替换原 原料/辅料/半成品报名值 内部拆分列 (与"分类"列冗余), 改为客户要的拣发追踪列。
            [("物料名称", "materialName", "LEFT"), ("分类", "category", "CENTER"),
             ("单位", "unit", "CENTER"),
             ("成交(应需)", "transactedQty", "RIGHT"),
             ("打算(已拣)", "plannedIssueQty", "RIGHT"),
             ("送到(已发)", "deliveredQty", "RIGHT"),
             ("实际领用(结单填)", "actualUsedQty", "RIGHT"),
             ("批次关联", "batchRefs", "LEFT")],
            s["font"],
        ),
    ]
    if data.get("remark"):
        story.extend([Spacer(1, 0.5 * cm), Paragraph("备注", s["h2"]),
                      Paragraph(str(data["remark"]), s["body"])])
    story.extend([
        Spacer(1, 1.0 * cm),
        Paragraph("仓管员签名: ____________________________", s["body"]),
        Paragraph("领料经手人签名: __________________________", s["body"]),
        Paragraph("生产计划员签名: __________________________", s["body"]),
    ])
    doc.build(story)
    return buffer.getvalue()


def render_batching_sheet(data: dict) -> bytes:
    """配料单 PDF — 六扇门 配料员按锅配料 (转录 [87:50-88:00]).

    锅数 = ceil(计划产量 / 单锅产能[ProductType.singlePotCapacity]); 每锅料量 = 物料总需求 / 锅数。
    单锅产能未配置 (potCount=None) → 显提示, 不伪造每锅用量。配料员当前用现有生产/报工角色兼任。

    expected data: {
      factoryName, planId, planNumber, productName, printDate, salesOrderNumbers,
      plannedQuantity, unit, singlePotCapacity, potCount,
      items: [{materialName, unit, totalQty, ...}], printedBy, printedAccount, remark
    }
    """
    from reportlab.lib.units import cm
    from reportlab.platypus import Paragraph, Spacer

    s = _get_styles()
    buffer = io.BytesIO()
    doc = _make_doc(buffer)

    pot_count = data.get("potCount")
    pot_capacity = data.get("singlePotCapacity")
    plan_qty = data.get("plannedQuantity", "-")
    unit = data.get("unit", "-")
    sales_order_numbers = data.get("salesOrderNumbers") or "-"
    plan_number = data.get("planNumber") or data.get("planId", "-")
    pot_count_display = (str(pot_count) + " 锅") if pot_count else "请先在产品配置单锅产能"

    def _per_pot(total_str: Any) -> str:
        """每锅用量 = 物料总需求 / 锅数; 锅数缺失或非正 → '—' (不伪造)。"""
        if not pot_count or pot_count <= 0:
            return "—"
        try:
            total = float(str(total_str).replace(",", ""))
        except (TypeError, ValueError):
            return "—"
        v = total / pot_count
        return str(int(v)) if v == int(v) else f"{v:.3f}".rstrip("0").rstrip(".")

    rendered_items = [
        {
            "materialName": it.get("materialName", "-"),
            "unit": it.get("unit", "-"),
            "totalQty": it.get("totalQty", "-"),
            "perPotQty": _per_pot(it.get("totalQty")),
        }
        for it in (data.get("items") or [])
    ]

    story: list[Any] = [
        Paragraph(data.get("factoryName") or "白垩纪食品", s["small"]),
        Paragraph("配料单 · Batching Sheet (按锅配料)", s["title"]),
        _kv_table([
            ("关联销售单号", sales_order_numbers),
            ("生产计划单号", plan_number),
            ("生产产品", data.get("productName", "-")),
            ("计划产量", f"{plan_qty} {unit}"),
            ("单锅产能", f"{pot_capacity} {unit}" if pot_capacity else "未配置"),
            ("配料锅数", pot_count_display),
            ("打印日期", data.get("printDate", "-")),
            ("打印人", data.get("printedBy", "-")),
            ("打印账号", data.get("printedAccount", "-")),
        ], s["font"]),
        Spacer(1, 0.5 * cm),
        Paragraph("每锅配料明细", s["h2"]),
        _render_items_table(
            rendered_items,
            [("物料名称", "materialName", "LEFT"), ("单位", "unit", "CENTER"),
             ("物料总需求", "totalQty", "RIGHT"), ("每锅用量", "perPotQty", "RIGHT")],
            s["font"],
        ),
    ]
    if not pot_count:
        story.extend([
            Spacer(1, 0.4 * cm),
            Paragraph("注意: 单锅产能未配置, 无法计算每锅用量。请先在 产品管理 → 该产品 设置「单锅产能」后重新打印。", s["body"]),
        ])
    if data.get("remark"):
        story.extend([Spacer(1, 0.5 * cm), Paragraph("备注", s["h2"]),
                      Paragraph(str(data["remark"]), s["body"])])
    story.extend([
        Spacer(1, 1.0 * cm),
        Paragraph("配料员签名: ____________________________", s["body"]),
        Paragraph("生产计划员签名: __________________________", s["body"]),
    ])
    doc.build(story)
    return buffer.getvalue()


def render_production_work_order_multi(data: dict) -> bytes:
    """多 SO 合并生产工单 PDF — P1 #37.

    expected data: {
      factoryName, printDate,
      orders: [{planId, planNumber, sourceOrderId, productName, productUnit,
                plannedQuantity, customerName, plannedDate, expectedCompletionDate}],
      processes: [{seq, name, standardHours, operator}],
      totalOrders: int,
      totalQuantityByProduct: [{productName, unit, totalQty}],
      remark
    }

    渲染逻辑:
      1. 页眉: 工厂名 + 标题 "多订单合并生产工单"
      2. 汇总信息: 打印日期 + 订单数 + 合计数量明细表
      3. 订单明细表: 每行一个 SO/计划
      4. 工序列表 (去重合并)
      5. 签名区
    """
    from reportlab.lib.units import cm
    from reportlab.platypus import Paragraph, Spacer

    s = _get_styles()
    buffer = io.BytesIO()
    doc = _make_doc(buffer)

    orders = data.get("orders") or []
    total_orders = data.get("totalOrders") or len(orders)
    print_date = data.get("printDate") or "-"
    total_qty_by_product = data.get("totalQuantityByProduct") or []

    # ── 1. 页眉 ────────────────────────────────────────────────────────────────
    story: list[Any] = [
        Paragraph(data.get("factoryName") or "白垩纪食品", s["small"]),
        Paragraph("多订单合并生产工单 · Multi-SO Production Work Order", s["title"]),
        _kv_table([
            ("打印日期", print_date),
            ("订单总数", str(total_orders)),
        ], s["font"]),
        Spacer(1, 0.3 * cm),
    ]

    # ── 2. 合计数量汇总 (按产品) ───────────────────────────────────────────────
    if total_qty_by_product:
        story.append(Paragraph("合计数量 (按产品)", s["h2"]))
        story.append(
            _render_items_table(
                total_qty_by_product,
                [("产品名称", "productName", "LEFT"),
                 ("单位", "unit", "CENTER"),
                 ("合计计划量", "totalQty", "RIGHT")],
                s["font"],
            )
        )
        story.append(Spacer(1, 0.4 * cm))

    # ── 3. 订单明细 ────────────────────────────────────────────────────────────
    story.append(Paragraph("订单明细", s["h2"]))
    story.append(
        _render_items_table(
            orders,
            [
                ("计划编号", "planNumber", "LEFT"),
                ("关联销售单", "sourceOrderId", "LEFT"),
                ("产品", "productName", "LEFT"),
                ("计划数量", "plannedQuantity", "RIGHT"),
                ("单位", "productUnit", "CENTER"),
                ("客户", "customerName", "LEFT"),
                ("预计完成", "expectedCompletionDate", "LEFT"),
            ],
            s["font"],
        )
    )
    story.append(Spacer(1, 0.5 * cm))

    # ── 4. 工序列表 (去重合并) ─────────────────────────────────────────────────
    processes = data.get("processes") or []
    story.append(Paragraph("工序列表 (合并去重)", s["h2"]))
    if processes:
        story.append(
            _render_items_table(
                processes,
                [("序号", "seq", "CENTER"), ("工序名称", "name", "LEFT"),
                 ("标准工时(h)", "standardHours", "RIGHT"), ("负责人", "operator", "LEFT")],
                s["font"],
            )
        )
    else:
        story.append(Paragraph("(工序任务尚未分配)", s["body"]))

    # ── 5. 备注 + 签名 ─────────────────────────────────────────────────────────
    if data.get("remark"):
        story.extend([Spacer(1, 0.5 * cm), Paragraph("备注", s["h2"]),
                      Paragraph(str(data["remark"]), s["body"])])
    story.extend([
        Spacer(1, 1.0 * cm),
        Paragraph("生产主管签名: ____________________________", s["body"]),
        Paragraph("领班签名: ________________________________", s["body"]),
    ])
    doc.build(story)
    return buffer.getvalue()


def render_production_document_package(data: dict) -> bytes:
    """Render cover + selected production documents as one continuous PDF.

    This is a print composition only. It does not merge the underlying work
    order, material requisition, or batching-sheet business models.
    """
    from reportlab.lib.pagesizes import A4
    from reportlab.lib.units import cm
    from reportlab.pdfgen import canvas
    from reportlab.platypus import PageBreak, Paragraph, SimpleDocTemplate, Spacer

    selected = data.get("sections") or [
        "work-order", "material-requisition", "batching-sheet"
    ]
    allowed = {"work-order", "material-requisition", "batching-sheet"}
    if not selected or any(section not in allowed for section in selected):
        raise ValueError("生产单据包章节选择无效")

    section_payloads = {
        "work-order": ("workOrder", "生产工单"),
        "material-requisition": ("materialRequisition", "领料单"),
        "batching-sheet": ("batchingSheet", "配料单"),
    }
    missing = [
        title for section, (key, title) in section_payloads.items()
        if section in selected and not data.get(key)
    ]
    if missing:
        raise ValueError("生产单据包缺少章节数据: " + "、".join(missing))

    s = _get_styles()
    font = s["font"]
    if font == "Helvetica":
        raise ValueError("服务器未安装可嵌入的中文字体，拒绝生成乱码单据包")

    buffer = io.BytesIO()
    doc = SimpleDocTemplate(
        buffer,
        pagesize=A4,
        leftMargin=1.35 * cm,
        rightMargin=1.35 * cm,
        topMargin=2.25 * cm,
        bottomMargin=1.8 * cm,
        title=f"生产单据包 {data.get('planNumber') or data.get('planId', '')}",
        author="Cretas",
    )

    plan_number = str(data.get("planNumber") or data.get("planId") or "-")
    sku = str(data.get("sku") or data.get("productTypeId") or "-")
    product_name = str(data.get("productName") or "-")
    batch_date = str(data.get("batchDate") or "-")
    bom_version = str(data.get("bomVersion") or "-")
    workflow_version = str(data.get("workflowVersion") or "-")
    generated_at = str(data.get("generatedAt") or "-")

    class PackageNumberedCanvas(canvas.Canvas):
        """Two-pass canvas for a shared header/footer and total page count."""

        def __init__(self, *args: Any, **kwargs: Any) -> None:
            super().__init__(*args, **kwargs)
            self._saved_page_states: list[dict[str, Any]] = []

        def showPage(self) -> None:  # noqa: N802 - reportlab API
            self._saved_page_states.append(dict(self.__dict__))
            self._startPage()

        def save(self) -> None:
            page_count = len(self._saved_page_states)
            for state in self._saved_page_states:
                self.__dict__.update(state)
                self._draw_package_header_footer(page_count)
                canvas.Canvas.showPage(self)
            canvas.Canvas.save(self)

        def _draw_package_header_footer(self, page_count: int) -> None:
            width, height = A4
            self.saveState()
            self.setFont(font, 7.5)
            self.setFillColorRGB(0.30, 0.34, 0.40)
            header = (
                f"计划 {plan_number}  |  SKU {sku} / {product_name}  |  "
                f"批次 {batch_date}  |  BOM v{bom_version}  |  Workflow v{workflow_version}"
            )
            self.drawString(1.35 * cm, height - 1.15 * cm, header)
            self.line(1.35 * cm, height - 1.30 * cm, width - 1.35 * cm, height - 1.30 * cm)
            self.drawString(1.35 * cm, 0.85 * cm, f"生成时间 {generated_at}")
            self.drawRightString(
                width - 1.35 * cm,
                0.85 * cm,
                f"第 {self._pageNumber} 页 / 共 {page_count} 页",
            )
            self.restoreState()

    labels = {
        "work-order": "生产工单",
        "material-requisition": "领料单",
        "batching-sheet": "配料单",
    }
    story: list[Any] = [
        Spacer(1, 1.2 * cm),
        Paragraph(data.get("factoryName") or "白垩纪食品", s["small"]),
        Paragraph("生产单据包", s["title"]),
        _kv_table([
            ("生产计划号", plan_number),
            ("SKU / 产品", f"{sku} / {product_name}"),
            ("批次日期", batch_date),
            ("BOM 快照", f"{data.get('bomRecipeId') or '-'} / v{bom_version}"),
            ("Workflow 快照", f"{data.get('workflowId') or '-'} / v{workflow_version}"),
            ("包含章节", "、".join(labels[section] for section in selected)),
            ("生成时间", generated_at),
        ], font),
        Spacer(1, 1.2 * cm),
        Paragraph(
            "本 PDF 仅统一打印载体；生产工单、领料单、配料单仍保持各自编号、状态、责任与审计边界。",
            s["body"],
        ),
    ]

    if "work-order" in selected:
        work = data["workOrder"]
        story.extend([
            PageBreak(),
            Paragraph("第 1 部分　生产工单", s["title"]),
            _kv_table([
                ("生产工单号", work.get("productionOrderNumber") or plan_number),
                ("销售订单号", work.get("salesOrderNumbers") or work.get("sourceOrderId") or "-"),
                ("产品", work.get("productName") or product_name),
                ("计划产量", f"{_fmt_qty(work.get('plannedQuantity'))} {work.get('productUnit') or '-'}"),
                ("生产日期", work.get("productionDate") or work.get("plannedDate") or "-"),
                ("状态", work.get("status") or "-"),
            ], font),
            Spacer(1, 0.4 * cm),
            Paragraph("工序", s["h2"]),
            _render_items_table(
                work.get("processes") or [],
                [("序号", "seq", "CENTER"), ("工序名称", "name", "LEFT"),
                 ("标准工时(h)", "standardHours", "RIGHT"), ("负责人", "operator", "LEFT")],
                font,
            ),
            Spacer(1, 0.7 * cm),
            Paragraph("制单人: ____________________　审核人: ____________________　生产负责人: ____________________", s["body"]),
        ])

    if "material-requisition" in selected:
        requisition = data["materialRequisition"]
        story.extend([
            PageBreak(),
            Paragraph("第 2 部分　领料单", s["title"]),
            _kv_table([
                ("生产计划号", requisition.get("planNumber") or plan_number),
                ("销售订单号", requisition.get("salesOrderNumbers") or "-"),
                ("产品", requisition.get("productName") or product_name),
                ("单据状态", requisition.get("status") or "待领料"),
                ("责任部门", "仓库 / 生产车间"),
            ], font),
            Spacer(1, 0.4 * cm),
            _render_items_table(
                requisition.get("items") or [],
                [("物料名称", "materialName", "LEFT"), ("分类", "category", "CENTER"),
                 ("单位", "unit", "CENTER"), ("应需", "transactedQty", "RIGHT"),
                 ("已拣", "plannedIssueQty", "RIGHT"), ("已发", "deliveredQty", "RIGHT"),
                 ("实际领用", "actualUsedQty", "RIGHT"), ("批次", "batchRefs", "LEFT")],
                font,
            ),
            Spacer(1, 0.7 * cm),
            Paragraph("仓管员: ____________________　领料人: ____________________　计划员: ____________________", s["body"]),
        ])

    if "batching-sheet" in selected:
        batching = data["batchingSheet"]
        pot_count = batching.get("potCount")

        def per_pot(total: Any) -> str:
            try:
                value = float(str(total).replace(",", "")) / float(pot_count)
                return f"{value:.3f}".rstrip("0").rstrip(".")
            except (TypeError, ValueError, ZeroDivisionError):
                return "—"

        batching_rows = [
            {**item, "perPotQty": per_pot(item.get("totalQty"))}
            for item in (batching.get("items") or [])
        ]
        story.extend([
            PageBreak(),
            Paragraph("第 3 部分　配料单", s["title"]),
            _kv_table([
                ("生产计划号", batching.get("planNumber") or plan_number),
                ("产品", batching.get("productName") or product_name),
                ("计划产量", f"{batching.get('plannedQuantity') or '-'} {batching.get('unit') or batching.get('productUnit') or '-'}"),
                ("单锅产能", f"{batching.get('singlePotCapacity') or '-'} {batching.get('unit') or '-'}"),
                ("配料锅数", f"{pot_count} 锅"),
                ("责任部门", "生产车间 / 配料岗位"),
            ], font),
            Spacer(1, 0.4 * cm),
            _render_items_table(
                batching_rows,
                [("物料名称", "materialName", "LEFT"), ("单位", "unit", "CENTER"),
                 ("总需求", "totalQty", "RIGHT"), ("每锅用量", "perPotQty", "RIGHT")],
                font,
            ),
            Spacer(1, 0.7 * cm),
            Paragraph("配料员: ____________________　复核人: ____________________　生产负责人: ____________________", s["body"]),
        ])

    doc.build(story, canvasmaker=PackageNumberedCanvas)
    return buffer.getvalue()


def render_transfer_instruction(data: dict) -> bytes:
    """调拨指示单 PDF — 客户[37:00] "无手机一天打一张纸质指示单".

    expected data: {
      factoryName, transferId, transferNumber, transferDate,
      sourceWarehouseId (调出仓),  targetWarehouseId (调入仓),
      status (中文状态),  requestedBy,  expectedArrivalDate,
      remark,
      items: [{itemName, spec, qty, unit, batchId}]
    }

    纸质指示单设计 (仓管员导向, 防呆):
      - 标题: "调拨指示单" 大字
      - KV 头: 单号/日期/调出仓/调入仓/申请人/期望到货/状态
      - 明细表: 物料 / 规格 / 数量 / 单位 / 批次
      - 签名区: 发料仓管员 + 收料仓管员
    """
    from reportlab.lib.units import cm
    from reportlab.platypus import Paragraph, Spacer

    s = _get_styles()
    buffer = io.BytesIO()
    doc = _make_doc(buffer)

    story: list[Any] = [
        Paragraph(data.get("factoryName") or "白垩纪食品", s["small"]),
        Paragraph("调拨指示单 · Transfer Instruction", s["title"]),
        _kv_table([
            ("调拨单号", data.get("transferNumber") or data.get("transferId", "-")),
            ("调拨日期", data.get("transferDate", "-")),
            ("调出仓", data.get("sourceWarehouseId", "-")),
            ("调入仓", data.get("targetWarehouseId", "-")),
            ("申请人", data.get("requestedBy", "-")),
            ("期望到货", data.get("expectedArrivalDate", "-")),
            ("状态", data.get("status", "-")),
        ], s["font"]),
        Spacer(1, 0.5 * cm),
        Paragraph("调拨明细", s["h2"]),
        _render_items_table(
            data.get("items") or [],
            [("物料名称", "itemName", "LEFT"), ("规格", "spec", "LEFT"),
             ("数量", "qty", "RIGHT"), ("单位", "unit", "CENTER"),
             ("批次", "batchId", "LEFT")],
            s["font"],
        ),
    ]
    if data.get("remark"):
        story.extend([Spacer(1, 0.5 * cm), Paragraph("备注", s["h2"]),
                      Paragraph(str(data["remark"]), s["body"])])
    story.extend([
        Spacer(1, 1.0 * cm),
        Paragraph("发料仓管员签名: ____________________________ 日期: ______", s["body"]),
        Paragraph("收料仓管员签名: ____________________________ 日期: ______", s["body"]),
    ])
    doc.build(story)
    return buffer.getvalue()


RENDERERS: dict[str, Any] = {
    "sales-order": render_sales_order,
    "purchase-order": render_purchase_order,
    "quotation": render_quotation,
    "production-task": render_production_task,
    "material-requisition": render_material_requisition,
    # Sprint 6 W3-C — 3 new P1 templates (Round 13 §13 + Z-4 coverage)
    "stock-movement": render_stock_movement,
    "financial-invoice": render_financial_invoice,
    "packing-list": render_packing_list,
    # SP12 T8 — 2 new templates: 生产工单 + 汇总领料单
    "production-work-order": render_production_work_order,
    "consolidated-material-requisition": render_consolidated_material_requisition,
    # 六扇门 配料单 (配料员按锅配料)
    "batching-sheet": render_batching_sheet,
    "production-document-package": render_production_document_package,
    # P1 #37 — 多 SO 合并公单
    "production-work-order-multi": render_production_work_order_multi,
    # 调拨指示单 (客户[37:00] 纸质指示单)
    "transfer-instruction": render_transfer_instruction,
}
