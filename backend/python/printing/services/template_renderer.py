"""Schema-driven PDF renderer for C-PRT-EDITOR-1 (Sprint 3 Track-J).

Reads a saved print template (from cretas_db.form_templates) or accepts an
inline schema, resolves {{}} bindings against supplied entity data, and
renders to a PDF via reportlab.

Architecture (per design doc 2026-05-16-c-prt-editor-design.md §3.7):
  Java owns entity (CRUD + RBAC) — passes factoryId + templateId + entityData
  Python owns render — reads form_templates via get_cretas_pool() singleton,
    applies app.factory_id GUC per request for RLS defense, parses schema,
    walks elements, draws on reportlab Canvas (absolute positioning, A4).

Schema shape (unwrapped from Formily layer; the wire format has
{type:"object", properties:{_printSchema: ...}} — unwrap before passing):
  {
    version: 1,
    canvas: { width, height, orientation, marginTop, marginBottom, ... },
    elements: [
      {type:"text",    id, x, y, text, fontSize, bold, color, align},
      {type:"field",   id, x, y, binding, fontSize, color, align, format, emptyText},
      {type:"table",   id, x, y, width, binding, columns:[{header,binding,width,align,format}], rowHeight, headerBg},
      {type:"qr",      id, x, y, size, content},
      {type:"barcode", id, x, y, width, height, content, format},
      {type:"image",   id, x, y, width, height, src},
      {type:"stamp",   id, x, y, size, stampId, opacity},
    ],
  }

Coordinate system: schema uses pt with origin at TOP-LEFT (matches editor UX).
reportlab Canvas origin is BOTTOM-LEFT — Y is flipped at draw time.
"""
from __future__ import annotations

import io
import json
import logging
import re
from datetime import date, datetime
from decimal import Decimal
from typing import Any, Optional

from fastapi import HTTPException

logger = logging.getLogger(__name__)


# ============================================================
#  Binding resolver — mirror of frontend templateRenderer.ts
# ============================================================

_BINDING_RE = re.compile(r"\{\{\s*([^}]+?)\s*\}\}")


def _get_path(obj: Any, path: str) -> Any:
    """Dotted-path access. None / non-dict returns None."""
    if obj is None:
        return None
    current = obj
    for key in path.split("."):
        if isinstance(current, dict):
            current = current.get(key)
        else:
            return None
        if current is None:
            return None
    return current


def _fmt_currency(v: Any) -> str:
    if v is None:
        return "-"
    try:
        n = float(v) if not isinstance(v, (int, float, Decimal)) else float(v)
        return f"¥{n:,.2f}"
    except (TypeError, ValueError):
        return str(v)


def _fmt_qty(v: Any) -> str:
    if v is None:
        return "-"
    try:
        n = float(v) if not isinstance(v, (int, float, Decimal)) else float(v)
        # Strip trailing zeros for clean qty display
        if n == int(n):
            return f"{int(n):,}"
        return f"{n:,.2f}"
    except (TypeError, ValueError):
        return str(v)


def _fmt_percent(v: Any) -> str:
    if v is None:
        return "-"
    try:
        n = float(v) if not isinstance(v, (int, float, Decimal)) else float(v)
        return f"{n * 100:.2f}%"
    except (TypeError, ValueError):
        return str(v)


def _fmt_date(v: Any, fmt: str = "YYYY-MM-DD") -> str:
    if v is None:
        return "-"
    if isinstance(v, (date, datetime)):
        d = v
    else:
        try:
            d = datetime.fromisoformat(str(v).replace("Z", "+00:00"))
        except (ValueError, TypeError):
            return str(v)
    py_fmt = fmt.replace("YYYY", "%Y").replace("MM", "%m").replace("DD", "%d")
    return d.strftime(py_fmt)


_FMT_FN_RE = re.compile(
    r"^format\.(currency|date|percent|qty)\(\s*([^,)]+?)\s*(?:,\s*['\"]([^'\"]+)['\"]\s*)?\)$"
)


def _resolve_expression(expr: str, data: dict) -> str:
    """Resolve one {{...}} expression body (without the braces)."""
    trimmed = expr.strip()

    fmt_match = _FMT_FN_RE.match(trimmed)
    if fmt_match:
        kind, path, arg = fmt_match.group(1), fmt_match.group(2), fmt_match.group(3)
        v = _get_path(data, path.strip())
        if kind == "currency":
            return _fmt_currency(v)
        if kind == "date":
            return _fmt_date(v, arg or "YYYY-MM-DD")
        if kind == "percent":
            return _fmt_percent(v)
        if kind == "qty":
            return _fmt_qty(v)

    if trimmed.startswith("computed."):
        # Server-side computed bindings — not implemented yet, return placeholder.
        return f"[{trimmed}]"

    v = _get_path(data, trimmed)
    if v is None:
        return "-"
    return str(v)


def _render_binding(template: str, data: dict) -> str:
    """Replace all {{...}} in `template` with resolved values from `data`."""
    if not template or "{{" not in template:
        return template
    return _BINDING_RE.sub(lambda m: _resolve_expression(m.group(1), data), template)


def _resolve_array(binding: str, data: dict) -> list:
    """Strip wrapping {{}} from a table binding and resolve to a list."""
    inner = _BINDING_RE.sub(lambda m: m.group(1).strip(), binding)
    v = _get_path(data, inner)
    return v if isinstance(v, list) else []


# ============================================================
#  Canvas drawing — one function per element type
# ============================================================

def _y_flip(canvas_height: float, top_y: float, element_height: float = 0) -> float:
    """Convert top-left-origin Y (schema) to bottom-left-origin Y (reportlab),
    placing the element's bottom on the page so that its top sits at top_y."""
    return canvas_height - top_y - element_height


def _hex_to_color(hex_str: Optional[str]) -> Any:
    """Convert '#1f2937' to a reportlab HexColor. Falls back to dark gray."""
    from reportlab.lib.colors import HexColor

    if not hex_str:
        return HexColor("#1f2937")
    try:
        return HexColor(hex_str)
    except (ValueError, KeyError):
        return HexColor("#1f2937")


def _draw_text_element(c, el: dict, font: str, canvas_h: float, _data: dict) -> None:
    x = float(el.get("x", 0))
    y = float(el.get("y", 0))
    text = str(el.get("text", "") or "")
    font_size = float(el.get("fontSize", 12))
    bold = bool(el.get("bold"))
    align = el.get("align", "left")
    color = _hex_to_color(el.get("color"))

    # No real bold-CJK font registered; emulate bold via stroke width.
    effective_font = font
    c.setFont(effective_font, font_size)
    c.setFillColor(color)

    # Baseline-relative Y; flip + add font_size so the *visual top* aligns.
    flipped_y = _y_flip(canvas_h, y, font_size)
    # For center/right alignment, treat width as the layout box; default 200
    # when the element has none (text elements often omit explicit width).
    box_width = float(el.get("width") or 200)

    if align == "center":
        c.drawCentredString(x + box_width / 2, flipped_y, text)
    elif align == "right":
        c.drawRightString(x + box_width, flipped_y, text)
    else:
        c.drawString(x, flipped_y, text)

    if bold:
        # Re-stroke for fake bold (reportlab CJK fonts often lack bold variants).
        c.setLineWidth(0.5)
        if align == "center":
            c.drawCentredString(x + box_width / 2, flipped_y, text)
        elif align == "right":
            c.drawRightString(x + box_width, flipped_y, text)
        else:
            c.drawString(x, flipped_y, text)


def _draw_field_element(c, el: dict, font: str, canvas_h: float, data: dict) -> None:
    """Same as text but with binding resolved from data."""
    binding = str(el.get("binding", "") or "")
    resolved = _render_binding(binding, data)
    if resolved == "-" and el.get("emptyText"):
        resolved = str(el["emptyText"])
    inner = dict(el)
    inner["text"] = resolved
    _draw_text_element(c, inner, font, canvas_h, data)


def _draw_table_element(c, el: dict, font: str, canvas_h: float, data: dict) -> None:
    from reportlab.lib import colors

    x = float(el.get("x", 0))
    y = float(el.get("y", 0))
    width = float(el.get("width", 495))
    row_h = float(el.get("rowHeight", 24))
    header_font_size = float(el.get("headerFontSize", 11))
    body_font_size = float(el.get("bodyFontSize", 10))
    header_bg = _hex_to_color(el.get("headerBg", "#f3f4f6"))
    columns = el.get("columns") or []

    # Resolve row data
    rows = _resolve_array(str(el.get("binding", "") or ""), data)

    # Header row
    header_y = _y_flip(canvas_h, y, row_h)
    c.setFillColor(header_bg)
    c.rect(x, header_y, width, row_h, fill=1, stroke=0)
    c.setFillColor(colors.HexColor("#1f2937"))
    c.setFont(font, header_font_size)

    col_x = x
    for col in columns:
        col_w = float(col.get("width", 80))
        header_text = str(col.get("header", "") or "")
        align = col.get("align", "left")
        text_y = header_y + (row_h - header_font_size) / 2 + 2
        if align == "center":
            c.drawCentredString(col_x + col_w / 2, text_y, header_text)
        elif align == "right":
            c.drawRightString(col_x + col_w - 4, text_y, header_text)
        else:
            c.drawString(col_x + 4, text_y, header_text)
        col_x += col_w

    # Body rows
    c.setFont(font, body_font_size)
    c.setStrokeColor(colors.HexColor("#e5e7eb"))
    c.setLineWidth(0.25)
    current_y = y + row_h
    for row in rows:
        row_flipped_y = _y_flip(canvas_h, current_y, row_h)
        # Draw row borders
        c.rect(x, row_flipped_y, width, row_h, fill=0, stroke=1)

        col_x = x
        for col in columns:
            col_w = float(col.get("width", 80))
            cell_binding = str(col.get("binding", "") or "")
            cell_value = _render_binding(cell_binding, {"item": row})
            # Column-level format takes effect only when binding is a plain
            # path (no inline `format.X()` wrapper) — otherwise we'd try to
            # re-resolve "format.currency(item.x)" as a path and lose the value.
            fmt = col.get("format")
            if fmt and "format." not in cell_binding:
                raw_path = _BINDING_RE.sub(lambda m: m.group(1).strip(), cell_binding)
                raw_val = _get_path({"item": row}, raw_path)
                if fmt == "currency":
                    cell_value = _fmt_currency(raw_val)
                elif fmt == "qty":
                    cell_value = _fmt_qty(raw_val)
                elif fmt == "percent":
                    cell_value = _fmt_percent(raw_val)
                elif fmt == "date":
                    cell_value = _fmt_date(raw_val)

            align = col.get("align", "left")
            text_y = row_flipped_y + (row_h - body_font_size) / 2 + 2
            if align == "center":
                c.drawCentredString(col_x + col_w / 2, text_y, cell_value)
            elif align == "right":
                c.drawRightString(col_x + col_w - 4, text_y, cell_value)
            else:
                c.drawString(col_x + 4, text_y, cell_value)
            col_x += col_w

        current_y += row_h

    # If no rows, draw a placeholder
    if not rows:
        c.setFillColor(colors.HexColor("#9ca3af"))
        c.setFont(font, body_font_size)
        ph_y = _y_flip(canvas_h, y + row_h + row_h / 2, body_font_size / 2)
        c.drawCentredString(x + width / 2, ph_y, f"(无数据 — 实打印由 {el.get('binding')} 填充)")
        c.setFillColor(colors.HexColor("#1f2937"))


def _draw_qr_element(c, el: dict, _font: str, canvas_h: float, data: dict) -> None:
    """Draws a QR code by rasterising via the qrcode lib."""
    try:
        import qrcode
        from reportlab.lib.utils import ImageReader
    except ImportError:
        logger.warning("qrcode lib not installed; QR element skipped")
        return

    x = float(el.get("x", 0))
    y = float(el.get("y", 0))
    size = float(el.get("size", 80))
    content = _render_binding(str(el.get("content", "") or ""), data)
    if not content or "{{" in content:
        # Binding not resolved — skip rather than emit a bogus QR
        return
    try:
        img = qrcode.make(content)
        bio = io.BytesIO()
        img.save(bio, format="PNG")
        bio.seek(0)
        c.drawImage(ImageReader(bio), x, _y_flip(canvas_h, y, size), width=size, height=size)
    except Exception as e:
        logger.warning(f"QR render failed: {e}")


def _draw_barcode_element(c, el: dict, font: str, canvas_h: float, data: dict) -> None:
    """Lightweight barcode placeholder — Code128 stripe-render approximation.

    For Day 4 MVP this draws a clean pattern of bars proportional to content
    length plus a human-readable caption underneath. A full Code128 encoder
    can be wired in Day 5+ if Sales needs scannable barcodes on quotations.
    """
    from reportlab.lib import colors

    x = float(el.get("x", 0))
    y = float(el.get("y", 0))
    width = float(el.get("width", 160))
    height = float(el.get("height", 40))
    content = _render_binding(str(el.get("content", "") or ""), data)
    flipped_y = _y_flip(canvas_h, y, height)

    # Border + caption strip
    c.setStrokeColor(colors.HexColor("#d1d5db"))
    c.setLineWidth(0.5)
    c.rect(x, flipped_y, width, height, fill=0, stroke=1)

    # Stripe pattern from content hash
    bar_y = flipped_y + height * 0.3
    bar_h = height * 0.5
    bar_x = x + 4
    pattern = "".join(["1101" if ch.isdigit() else "1011" for ch in (content or "x")])[: int(width * 0.9 / 2)]
    c.setFillColor(colors.HexColor("#1f2937"))
    for ch in pattern:
        if ch == "1":
            c.rect(bar_x, bar_y, 1.5, bar_h, fill=1, stroke=0)
        bar_x += 2
        if bar_x > x + width - 4:
            break

    # Caption
    c.setFont(font, 8)
    c.setFillColor(colors.HexColor("#6b7280"))
    c.drawCentredString(x + width / 2, flipped_y + 4, content[:40] if content else "(条码内容)")
    c.setFillColor(colors.HexColor("#1f2937"))


def _draw_image_element(c, el: dict, font: str, canvas_h: float, _data: dict) -> None:
    """Draws an inline image from data URI or HTTPS URL.

    Day 4 MVP: supports data URIs only (no external fetch — security + latency).
    Networked images can be added with httpx if customers complain.
    """
    from reportlab.lib import colors
    from reportlab.lib.utils import ImageReader

    x = float(el.get("x", 0))
    y = float(el.get("y", 0))
    width = float(el.get("width", 120))
    height = float(el.get("height", 60))
    src = str(el.get("src", "") or "")
    flipped_y = _y_flip(canvas_h, y, height)

    if src.startswith("data:image/"):
        try:
            header, b64 = src.split(",", 1)
            import base64
            raw = base64.b64decode(b64)
            c.drawImage(ImageReader(io.BytesIO(raw)), x, flipped_y, width=width, height=height)
            return
        except Exception as e:
            logger.warning(f"image data-URI decode failed: {e}")

    # Placeholder
    c.setStrokeColor(colors.HexColor("#d1d5db"))
    c.setFillColor(colors.HexColor("#fafafa"))
    c.rect(x, flipped_y, width, height, fill=1, stroke=1)
    c.setFillColor(colors.HexColor("#9ca3af"))
    c.setFont(font, 9)
    c.drawCentredString(x + width / 2, flipped_y + height / 2, "(图片占位)")
    c.setFillColor(colors.HexColor("#1f2937"))


def _draw_stamp_element(c, el: dict, font: str, canvas_h: float, _data: dict) -> None:
    """Draws a circular stamp (red ring + star + caption).

    Day 4 MVP: schematic only. Day 5+ can load actual factory stamps from
    FactorySetting.stampUrl when the stampId resolution is wired.
    """
    x = float(el.get("x", 0))
    y = float(el.get("y", 0))
    size = float(el.get("size", 100))
    opacity = float(el.get("opacity", 0.8))
    flipped_y = _y_flip(canvas_h, y, size)

    c.saveState()
    try:
        c.setFillColorRGB(0.86, 0.15, 0.15, alpha=opacity)
        c.setStrokeColorRGB(0.86, 0.15, 0.15, alpha=opacity)
        ring_width = max(2, size / 30)
        c.setLineWidth(ring_width)
        c.circle(x + size / 2, flipped_y + size / 2, size / 2 - ring_width / 2, stroke=1, fill=0)

        # Star at center-top
        c.setFont(font, size / 8)
        c.drawCentredString(x + size / 2, flipped_y + size / 2 + size / 12, "★")

        # Caption
        c.setFont(font, size / 10)
        c.drawCentredString(x + size / 2, flipped_y + size / 2 - size / 6, "企业印章")
    finally:
        c.restoreState()


_DISPATCH = {
    "text":    _draw_text_element,
    "field":   _draw_field_element,
    "table":   _draw_table_element,
    "qr":      _draw_qr_element,
    "barcode": _draw_barcode_element,
    "image":   _draw_image_element,
    "stamp":   _draw_stamp_element,
}


# ============================================================
#  Public entry — render schema to PDF bytes
# ============================================================

def render_schema_to_pdf(schema: dict, entity_data: dict) -> bytes:
    """Render an unwrapped print schema to a PDF byte string.

    schema:  unwrapped (NOT Formily-wrapped) — see module docstring shape.
    entity_data: dict resolved against {{}} bindings. For editor preview the
                  frontend supplies a per-entityType mock; for production
                  printing the Java controller fetches real entity data.
    """
    from reportlab.pdfgen.canvas import Canvas

    from printing.services.pdf_renderer import _register_chinese_font

    canvas_spec = schema.get("canvas") or {}
    width = float(canvas_spec.get("width", 595))
    height = float(canvas_spec.get("height", 842))
    elements = schema.get("elements") or []
    font = _register_chinese_font()

    buffer = io.BytesIO()
    c = Canvas(buffer, pagesize=(width, height))
    # Default text colour
    from reportlab.lib import colors
    c.setFillColor(colors.HexColor("#1f2937"))

    for el in elements:
        kind = el.get("type")
        draw_fn = _DISPATCH.get(kind)
        if draw_fn is None:
            logger.warning(f"unknown print element type: {kind}")
            continue
        try:
            draw_fn(c, el, font, height, entity_data)
        except Exception as e:
            logger.warning(f"element {kind} render failed: {e}")

    c.showPage()
    c.save()
    return buffer.getvalue()


def unwrap_schema(raw: str) -> dict:
    """Unwrap the Formily envelope on a saved schemaJson string.

    Storage format: {type:"object", properties:{_printSchema: {...}}}
    Raises HTTPException on malformed input.
    """
    if not raw:
        raise HTTPException(400, "empty schemaJson")
    try:
        wrapped = json.loads(raw)
    except json.JSONDecodeError as e:
        raise HTTPException(400, f"invalid schemaJson: {e}")
    schema = wrapped.get("properties", {}).get("_printSchema") if isinstance(wrapped, dict) else None
    if not isinstance(schema, dict):
        raise HTTPException(400, "schemaJson missing properties._printSchema")
    return schema


# ============================================================
#  DB read — load template from cretas_db.form_templates
# ============================================================

async def load_template_from_db(factory_id: str, template_id: str) -> dict:
    """Load schema from form_templates table, RLS-scoped via app.factory_id GUC.

    Returns the unwrapped schema dict. Raises 404 if no row matches.

    Scoping rules:
      - factory_id matches the request's factory, OR factory_id IS NULL
        (system-level template available to all factories, per FormTemplate.java:51)
      - is_active = true
      - deleted_at IS NULL (soft-delete filter, mirror of @Where on entity)
    """
    from smartbi.config import get_cretas_pool

    pool = await get_cretas_pool()
    if pool is None:
        raise HTTPException(503, "cretas_db pool unavailable")

    async with pool.acquire() as conn:
        async with conn.transaction():
            # Defence-in-depth: set the GUC even though we have explicit WHERE.
            await conn.execute(
                "SELECT set_config('app.factory_id', $1, false)", factory_id
            )
            row = await conn.fetchrow(
                """
                SELECT schema_json, name, version
                FROM form_templates
                WHERE id = $1
                  AND (factory_id = $2 OR factory_id IS NULL)
                  AND is_active = true
                  AND deleted_at IS NULL
                LIMIT 1
                """,
                template_id, factory_id,
            )
    if not row:
        raise HTTPException(404, f"template not found or not active: {template_id}")
    return unwrap_schema(row["schema_json"])
