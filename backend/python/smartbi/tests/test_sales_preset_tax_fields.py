from decimal import Decimal

import pytest

from smartbi.api.sales_preset import _money_fields


def test_money_fields_exposes_tax_breakdown_from_tax_included_amount():
    fields = _money_fields(Decimal("113.00"))

    assert fields["revenue"] == pytest.approx(100.0)
    assert fields["taxableAmount"] == pytest.approx(100.0)
    assert fields["taxAmount"] == pytest.approx(13.0)
    assert fields["totalAmountWithTax"] == pytest.approx(113.0)


def test_money_fields_treats_null_as_zero():
    fields = _money_fields(None)

    assert fields["taxableAmount"] == pytest.approx(0.0)
    assert fields["taxAmount"] == pytest.approx(0.0)
    assert fields["totalAmountWithTax"] == pytest.approx(0.0)
