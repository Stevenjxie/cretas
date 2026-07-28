from __future__ import annotations

import inspect

from smartbi.gold.restaurant import restaurant_ops_etl


def test_requisition_est_cost_recomputed_even_when_existing_value_is_non_null():
    source = inspect.getsource(restaurant_ops_etl.sync_fact_requisition)

    assert "SET est_cost = ROUND" in source
    assert "AND r.est_cost IS NULL" not in source


def test_ingredient_prices_are_sanity_checked_before_cost_rollup():
    dim_source = inspect.getsource(restaurant_ops_etl.sync_dim_ingredient)
    real_price_source = inspect.getsource(
        restaurant_ops_etl._get_latest_real_purchase_prices
    )

    assert dim_source.count("_is_sane_unit_price") >= 2
    assert "unit_price < $2" in real_price_source
    assert "MAX_SANE_UNIT_PRICE" in real_price_source


def test_recipe_line_cost_is_cleared_before_recomputation():
    source = inspect.getsource(restaurant_ops_etl.sync_fact_recipe)

    assert source.index("SET line_cost = NULL") < source.index("SET line_cost = ROUND")


def test_recipe_etl_snapshots_product_name_for_historical_cost_resolution():
    source = inspect.getsource(restaurant_ops_etl.sync_fact_recipe)

    assert "p.name AS product_name" in source
    assert "INSERT INTO dim_restaurant_cost_product" in source
    assert "ON CONFLICT (factory_id, product_source_pk) DO UPDATE" in source
