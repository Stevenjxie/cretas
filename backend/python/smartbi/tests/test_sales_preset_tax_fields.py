from decimal import Decimal

import pytest

from smartbi.api.sales_preset import _money_fields


def test_money_fields_exposes_persisted_tax_breakdown():
    fields = _money_fields(Decimal("100.00"), Decimal("9.00"))

    assert fields["revenue"] == pytest.approx(100.0)
    assert fields["taxableAmount"] == pytest.approx(100.0)
    assert fields["taxAmount"] == pytest.approx(9.0)
    assert fields["totalAmountWithTax"] == pytest.approx(109.0)


def test_money_fields_defaults_missing_tax_amount_to_zero():
    fields = _money_fields(Decimal("100.00"), None)

    assert fields["taxableAmount"] == pytest.approx(100.0)
    assert fields["taxAmount"] == pytest.approx(0.0)
    assert fields["totalAmountWithTax"] == pytest.approx(100.0)


def test_money_fields_treats_null_as_zero():
    fields = _money_fields(None, None)

    assert fields["taxableAmount"] == pytest.approx(0.0)
    assert fields["taxAmount"] == pytest.approx(0.0)
    assert fields["totalAmountWithTax"] == pytest.approx(0.0)
