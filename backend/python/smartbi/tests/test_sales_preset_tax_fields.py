from decimal import Decimal

import pytest

from smartbi.api.sales_preset import _money_fields


def test_money_fields_exposes_tax_breakdown():
    fields = _money_fields(Decimal("100.00"), Decimal("13.00"))

    assert fields == {
        "revenue": 100.0,
        "taxableAmount": 100.0,
        "taxAmount": 13.0,
        "totalAmountWithTax": 113.0,
    }


def test_money_fields_treats_null_as_zero():
    fields = _money_fields(None, None)

    assert fields["taxableAmount"] == pytest.approx(0.0)
    assert fields["taxAmount"] == pytest.approx(0.0)
    assert fields["totalAmountWithTax"] == pytest.approx(0.0)
