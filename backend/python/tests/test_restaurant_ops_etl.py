from __future__ import annotations

import inspect

from smartbi.gold import restaurant_ops_etl


def test_requisition_est_cost_recomputed_even_when_existing_value_is_non_null():
    source = inspect.getsource(restaurant_ops_etl.sync_fact_requisition)

    assert "SET est_cost = ROUND" in source
    assert "AND r.est_cost IS NULL" not in source
