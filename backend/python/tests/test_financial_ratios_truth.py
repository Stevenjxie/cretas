import asyncio
import json

from smartbi.api import financial_ratios


class _Cursor:
    def __init__(self, row):
        self.row = row
        self.query = ""
        self.params = []
        self.closed = False

    def execute(self, query, params):
        self.query = query
        self.params = params

    def fetchone(self):
        return self.row

    def close(self):
        self.closed = True


class _Connection:
    def __init__(self, row):
        self.cursor_instance = _Cursor(row)

    def cursor(self):
        return self.cursor_instance


def _ratio(result: dict, name: str) -> dict:
    return next(
        ratio
        for category in result["categories"]
        for ratio in category["ratios"]
        if ratio["name"] == name
    )


def test_missing_cost_keeps_gross_margin_unavailable() -> None:
    connection = _Connection((100.0, None))

    result = financial_ratios._compute_ratios_from_db(connection, "F006", None, None)

    assert "COALESCE(SUM" not in connection.cursor_instance.query
    assert "record_type = 'REVENUE'" in connection.cursor_instance.query
    assert "metric_type" not in connection.cursor_instance.query
    gross_margin = _ratio(result, "毛利率")
    assert gross_margin["value"] is None
    assert gross_margin["status"] == "unavailable"
    assert gross_margin["available"] is False
    assert "真实财务指标" in gross_margin["unavailableReason"]


def test_real_revenue_and_cost_compute_gross_margin() -> None:
    result = financial_ratios._compute_ratios_from_db(
        _Connection((100.0, 60.0)),
        "F006",
        None,
        None,
    )

    gross_margin = _ratio(result, "毛利率")
    assert gross_margin["value"] == 40
    assert gross_margin["status"] == "good"
    assert gross_margin["available"] is True


def test_database_failure_never_returns_demo_success(monkeypatch) -> None:
    monkeypatch.setattr(financial_ratios, "_get_db_connection", lambda: None)

    response = asyncio.run(financial_ratios.get_financial_ratios(factory_id="F006"))
    payload = json.loads(response.body)

    assert response.status_code == 503
    assert payload["success"] is False
    assert payload["data"] is None
    assert "数据库不可用" in payload["message"]


def test_missing_factory_id_is_an_explicit_error() -> None:
    response = asyncio.run(financial_ratios.get_financial_ratios(factory_id=None))
    payload = json.loads(response.body)

    assert response.status_code == 400
    assert payload["success"] is False
    assert payload["data"] is None
