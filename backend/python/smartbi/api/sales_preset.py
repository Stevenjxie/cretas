"""Sprint 4 W2 S-REPORTS-PRESETS — preset sales reports (top 5 active + 9 Sprint 5 stubs).

Routes (all under /api/smartbi/{factory_id}/sales-preset/):
  GET /daily-report?date=YYYY-MM-DD            日报 (active)
  GET /monthly-report?yearMonth=YYYY-MM        月报 (active)
  GET /yearly-report?year=YYYY                 年报 (active)
  GET /customer-rank?startDate&endDate&limit   客户销售排行 top N (active)
  GET /product-rank?startDate&endDate&limit    产品销售排行 top N (active)
  GET /salesperson-performance ...             业绩 by salesperson (Sprint 5 stub)
  GET /aging                                   应收账龄 (Sprint 5 stub)
  GET /collection-rate                         客户回款率 (Sprint 5 stub)
  GET /return-rate                             退货率 (Sprint 5 stub)
  GET /gross-margin                            销售毛利率 by product (Sprint 5 stub)
  GET /purchase-frequency                      客户购买频次 (Sprint 5 stub)
  GET /growth-rate                             月度增长率 (Sprint 5 stub)
  GET /regional-comparison                     区域销售对比 (Sprint 5 stub)
  GET /customer-tier                           客户分级销售贡献 (Sprint 5 stub)

All endpoints enforce factory_id match via _enforce_factory_match.
Read from cretas_db (sales_orders / sales_order_items / customers / product_types).

Phase 2A direction: prefers gold table reads when available; for MVP top-5
the data freshness is real-time critical so we read business tables directly.
"""
from __future__ import annotations

import logging
from datetime import date, datetime, timedelta
from typing import Optional

from fastapi import APIRouter, HTTPException, Query, Request

from smartbi.api._revenue_report_helpers import _enforce_factory_match

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/smartbi/{factory_id}/sales-preset")


async def _get_pool():
    from smartbi.config import get_cretas_pool
    pool = await get_cretas_pool()
    if pool is None:
        raise HTTPException(503, detail="cretas pool unavailable")
    return pool


# ─── Helpers ─────────────────────────────────────────────────────────────


def _parse_date(s: str, field: str) -> date:
    try:
        return datetime.strptime(s, "%Y-%m-%d").date()
    except ValueError as e:
        raise HTTPException(400, detail=f"invalid {field}: {s!r}, want YYYY-MM-DD") from e


def _date_range(start: Optional[str], end: Optional[str]) -> tuple[date, date]:
    if start is None or end is None:
        # default last 30 days
        today = date.today()
        return today - timedelta(days=30), today
    return _parse_date(start, "startDate"), _parse_date(end, "endDate")


def _stub(name: str) -> dict:
    return {
        "success": True,
        "data": None,
        "message": f"{name} 留 Sprint 5 (Phase II) 实现",
        "deferred": True,
    }


def _money_fields(total_amount, tax_amount=None) -> dict[str, float]:
    """Expose report money fields from persisted untaxed and tax totals.

    Keep ``revenue`` as the legacy untaxed alias.
    """
    taxable = float(total_amount or 0)
    tax = float(tax_amount or 0)
    gross = taxable + tax
    return {
        "revenue": taxable,
        "taxableAmount": taxable,
        "taxAmount": tax,
        "totalAmountWithTax": gross,
    }


# ─── Active endpoints (top 5) ─────────────────────────────────────────────


@router.get("/daily-report")
async def daily_report(factory_id: str, request: Request, date_: str | None = Query(default=None, alias="date")):
    """日报: 当日销售订单数 / 件数 / 销售额 / 已收款 / 未收款.

    Query: date=YYYY-MM-DD (defaults to today).
    """
    caller = _enforce_factory_match(factory_id, request)
    d = _parse_date(date_, "date") if date_ else date.today()

    pool = await _get_pool()
    async with pool.acquire() as conn:
        await conn.execute("SELECT set_config('app.factory_id', $1, false)", caller)
        row = await conn.fetchrow(
            """
            SELECT COUNT(*) AS order_count,
                   COALESCE(SUM(total_amount), 0) AS total_amount,
                   COALESCE(SUM(tax_amount), 0) AS tax_amount,
                   COALESCE(SUM(paid_amount), 0) AS paid,
                   COALESCE(SUM(total_amount), 0) + COALESCE(SUM(tax_amount), 0) - COALESCE(SUM(paid_amount), 0) AS unpaid
            FROM sales_orders
            WHERE factory_id = $1 AND order_date = $2
              AND status NOT IN ('DRAFT', 'CANCELLED')
              AND deleted_at IS NULL
            """,
            caller, d,
        )
    return {
        "success": True,
        "data": {
            "date": d.isoformat(),
            "orderCount": row["order_count"],
            **_money_fields(row["total_amount"], row["tax_amount"]),
            "paid": float(row["paid"]),
            "unpaid": float(row["unpaid"]),
        },
        "message": "ok",
    }


@router.get("/monthly-report")
async def monthly_report(factory_id: str, request: Request, yearMonth: str | None = None):
    """月报: 当月销售额 / 已收款 / 应收 / 订单数 + 按日明细.

    Query: yearMonth=YYYY-MM (defaults to current month).
    """
    caller = _enforce_factory_match(factory_id, request)
    if yearMonth is None:
        today = date.today()
        ym_year, ym_month = today.year, today.month
    else:
        try:
            ym_year, ym_month = map(int, yearMonth.split("-"))
        except ValueError as e:
            raise HTTPException(400, detail="yearMonth must be YYYY-MM") from e

    start = date(ym_year, ym_month, 1)
    if ym_month == 12:
        end = date(ym_year + 1, 1, 1) - timedelta(days=1)
    else:
        end = date(ym_year, ym_month + 1, 1) - timedelta(days=1)

    pool = await _get_pool()
    async with pool.acquire() as conn:
        await conn.execute("SELECT set_config('app.factory_id', $1, false)", caller)
        total = await conn.fetchrow(
            """
            SELECT COUNT(*) AS order_count,
                   COALESCE(SUM(total_amount), 0) AS total_amount,
                   COALESCE(SUM(tax_amount), 0) AS tax_amount,
                   COALESCE(SUM(paid_amount), 0) AS paid
            FROM sales_orders
            WHERE factory_id = $1 AND order_date BETWEEN $2 AND $3
              AND status NOT IN ('DRAFT', 'CANCELLED')
              AND deleted_at IS NULL
            """,
            caller, start, end,
        )
        daily = await conn.fetch(
            """
            SELECT order_date,
                   COUNT(*) AS order_count,
                   COALESCE(SUM(total_amount), 0) AS total_amount,
                   COALESCE(SUM(tax_amount), 0) AS tax_amount
            FROM sales_orders
            WHERE factory_id = $1 AND order_date BETWEEN $2 AND $3
              AND status NOT IN ('DRAFT', 'CANCELLED')
              AND deleted_at IS NULL
            GROUP BY order_date
            ORDER BY order_date
            """,
            caller, start, end,
        )
    return {
        "success": True,
        "data": {
            "yearMonth": f"{ym_year:04d}-{ym_month:02d}",
            "orderCount": total["order_count"],
            **_money_fields(total["total_amount"], total["tax_amount"]),
            "paid": float(total["paid"]),
            "unpaid": float(total["total_amount"]) + float(total["tax_amount"]) - float(total["paid"]),
            "daily": [
                {
                    "date": r["order_date"].isoformat(),
                    "orderCount": r["order_count"],
                    **_money_fields(r["total_amount"], r["tax_amount"]),
                }
                for r in daily
            ],
        },
        "message": "ok",
    }


@router.get("/yearly-report")
async def yearly_report(factory_id: str, request: Request, year: int | None = None):
    """年报: 当年销售额 / 应收 + 按月明细."""
    caller = _enforce_factory_match(factory_id, request)
    y = year if year is not None else date.today().year
    start = date(y, 1, 1)
    end = date(y, 12, 31)

    pool = await _get_pool()
    async with pool.acquire() as conn:
        await conn.execute("SELECT set_config('app.factory_id', $1, false)", caller)
        total = await conn.fetchrow(
            """
            SELECT COUNT(*) AS order_count,
                   COALESCE(SUM(total_amount), 0) AS total_amount,
                   COALESCE(SUM(tax_amount), 0) AS tax_amount,
                   COALESCE(SUM(paid_amount), 0) AS paid
            FROM sales_orders
            WHERE factory_id = $1 AND order_date BETWEEN $2 AND $3
              AND status NOT IN ('DRAFT', 'CANCELLED')
              AND deleted_at IS NULL
            """,
            caller, start, end,
        )
        monthly = await conn.fetch(
            """
            SELECT EXTRACT(MONTH FROM order_date)::int AS m,
                   COUNT(*) AS order_count,
                   COALESCE(SUM(total_amount), 0) AS total_amount,
                   COALESCE(SUM(tax_amount), 0) AS tax_amount
            FROM sales_orders
            WHERE factory_id = $1 AND order_date BETWEEN $2 AND $3
              AND status NOT IN ('DRAFT', 'CANCELLED')
              AND deleted_at IS NULL
            GROUP BY 1
            ORDER BY 1
            """,
            caller, start, end,
        )
    return {
        "success": True,
        "data": {
            "year": y,
            "orderCount": total["order_count"],
            **_money_fields(total["total_amount"], total["tax_amount"]),
            "paid": float(total["paid"]),
            "monthly": [
                {"month": r["m"], "orderCount": r["order_count"], **_money_fields(r["total_amount"], r["tax_amount"])}
                for r in monthly
            ],
        },
        "message": "ok",
    }


@router.get("/customer-rank")
async def customer_rank(
    factory_id: str,
    request: Request,
    startDate: str | None = None,
    endDate: str | None = None,
    limit: int = 20,
):
    """客户销售排行 top N: by total revenue."""
    caller = _enforce_factory_match(factory_id, request)
    start, end = _date_range(startDate, endDate)
    if not (1 <= limit <= 100):
        raise HTTPException(400, "limit must be 1..100")

    pool = await _get_pool()
    async with pool.acquire() as conn:
        await conn.execute("SELECT set_config('app.factory_id', $1, false)", caller)
        rows = await conn.fetch(
            """
            SELECT so.customer_id,
                   COALESCE(c.name, so.customer_id) AS customer_name,
                   COUNT(*) AS order_count,
                   COALESCE(SUM(so.total_amount), 0) AS total_amount,
                   COALESCE(SUM(so.tax_amount), 0) AS tax_amount,
                   COALESCE(SUM(so.total_amount), 0) + COALESCE(SUM(so.tax_amount), 0) AS total_amount_with_tax,
                   COALESCE(SUM(so.paid_amount), 0) AS paid
            FROM sales_orders so
            LEFT JOIN customers c ON c.id = so.customer_id
            WHERE so.factory_id = $1 AND so.order_date BETWEEN $2 AND $3
              AND so.status NOT IN ('DRAFT', 'CANCELLED')
              AND so.deleted_at IS NULL
            GROUP BY so.customer_id, c.name
            ORDER BY total_amount_with_tax DESC
            LIMIT $4
            """,
            caller, start, end, limit,
        )
    return {
        "success": True,
        "data": {
            "startDate": start.isoformat(),
            "endDate": end.isoformat(),
            "rank": [
                {
                    "rank": i + 1,
                    "customerId": r["customer_id"],
                    "customerName": r["customer_name"],
                    "orderCount": r["order_count"],
                    **_money_fields(r["total_amount"], r["tax_amount"]),
                    "paid": float(r["paid"]),
                }
                for i, r in enumerate(rows)
            ],
        },
        "message": "ok",
    }


@router.get("/product-rank")
async def product_rank(
    factory_id: str,
    request: Request,
    startDate: str | None = None,
    endDate: str | None = None,
    limit: int = 20,
):
    """产品销售排行 top N: by total revenue + qty."""
    caller = _enforce_factory_match(factory_id, request)
    start, end = _date_range(startDate, endDate)
    if not (1 <= limit <= 100):
        raise HTTPException(400, "limit must be 1..100")

    pool = await _get_pool()
    async with pool.acquire() as conn:
        await conn.execute("SELECT set_config('app.factory_id', $1, false)", caller)
        rows = await conn.fetch(
            """
            SELECT soi.product_type_id,
                   MAX(soi.product_name) AS product_name,
                   SUM(soi.quantity) AS total_qty,
                   MAX(soi.unit) AS unit,
                   SUM(COALESCE(soi.quantity, 0) * COALESCE(soi.unit_price, 0)) AS total_amount,
                   SUM(COALESCE(soi.quantity, 0) * COALESCE(soi.unit_price, 0) * COALESCE(soi.tax_rate, 0) / 100) AS tax_amount,
                   SUM(COALESCE(soi.quantity, 0) * COALESCE(soi.unit_price, 0) * (1 + COALESCE(soi.tax_rate, 0) / 100)) AS total_amount_with_tax,
                   COUNT(DISTINCT soi.sales_order_id) AS order_count
            FROM sales_order_items soi
            JOIN sales_orders so ON so.id = soi.sales_order_id
            WHERE so.factory_id = $1 AND so.order_date BETWEEN $2 AND $3
              AND so.status NOT IN ('DRAFT', 'CANCELLED')
              AND so.deleted_at IS NULL
              AND soi.deleted_at IS NULL
            GROUP BY soi.product_type_id
            ORDER BY total_amount_with_tax DESC NULLS LAST
            LIMIT $4
            """,
            caller, start, end, limit,
        )
    return {
        "success": True,
        "data": {
            "startDate": start.isoformat(),
            "endDate": end.isoformat(),
            "rank": [
                {
                    "rank": i + 1,
                    "productTypeId": r["product_type_id"],
                    "productName": r["product_name"],
                    "totalQty": float(r["total_qty"]) if r["total_qty"] is not None else 0.0,
                    "unit": r["unit"],
                    **_money_fields(r["total_amount"], r["tax_amount"]),
                    "orderCount": r["order_count"],
                }
                for i, r in enumerate(rows)
            ],
        },
        "message": "ok",
    }


# ─── Sprint 5 stubs (9 reports) ──────────────────────────────────────────


@router.get("/salesperson-performance")
async def salesperson_performance(factory_id: str, request: Request):
    _enforce_factory_match(factory_id, request)
    return _stub("业绩 by salesperson")


@router.get("/aging")
async def aging(factory_id: str, request: Request):
    _enforce_factory_match(factory_id, request)
    return _stub("应收账龄 (30/60/90/120/180+)")


@router.get("/collection-rate")
async def collection_rate(factory_id: str, request: Request):
    _enforce_factory_match(factory_id, request)
    return _stub("客户回款率")


@router.get("/return-rate")
async def return_rate(factory_id: str, request: Request):
    _enforce_factory_match(factory_id, request)
    return _stub("退货率")


@router.get("/gross-margin")
async def gross_margin(factory_id: str, request: Request):
    _enforce_factory_match(factory_id, request)
    return _stub("销售毛利率 by product")


@router.get("/purchase-frequency")
async def purchase_frequency(factory_id: str, request: Request):
    _enforce_factory_match(factory_id, request)
    return _stub("客户购买频次")


@router.get("/growth-rate")
async def growth_rate(factory_id: str, request: Request):
    _enforce_factory_match(factory_id, request)
    return _stub("月度增长率")


@router.get("/regional-comparison")
async def regional_comparison(factory_id: str, request: Request):
    _enforce_factory_match(factory_id, request)
    return _stub("区域销售对比")


@router.get("/customer-tier")
async def customer_tier(factory_id: str, request: Request):
    _enforce_factory_match(factory_id, request)
    return _stub("客户分级销售贡献")
