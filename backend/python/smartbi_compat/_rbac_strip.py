"""RBAC price-strip helper for SmartBI analysis responses.

Mirrors the Java ``PriceFieldResponseAdvice`` (PR #423) + the role white-list in
``PermissionServiceImpl.PRICE_VIEW_ROLES`` for endpoints that have moved fully
to the Python side (T6.5 Phase C — Java handlers deleted, Python is the sole
server). Java's ``@PriceSensitive`` annotation cannot reach Python responses
through Jackson reflection, so the strip happens here on the Python dict
before the standard envelope is applied.

Behavior
--------
* Role in ``PRICE_VIEW_ROLES`` → response passes through unchanged.
* Role not in the white-list (or missing) → response is walked depth-first;
  every leaf-valued key whose name matches the money pattern is set to
  ``None``; every "money KPI card" (identified by its ``key``/``title``/
  ``unit`` etc.) has its ``value`` / ``rawValue`` / ``change`` / ``targetValue``
  fields nulled.

White-list mirrors Java
-----------------------
The Java role set in ``PermissionServiceImpl.java:254-313`` is the source of
truth — keep ``PRICE_VIEW_ROLES`` below in sync if Java changes.

Idempotent
----------
Re-applying the strip on an already-stripped payload is a no-op (everything
sensitive is already ``None``).

Phase 2A Rule 4 invariant
-------------------------
Sensitive fields become ``None`` (JSON ``null``), never ``0`` or ``""`` — that
preserves the "missing vs. zero" distinction the frontend relies on and stays
out of ``_decimal_to_number``'s territory (see Rule 4 in
``.claude/rules/python-java-port.md``).
"""
from __future__ import annotations

import re
from typing import Any, Optional

# ──────────────────────────────────────────────────────────────────────────
# Role white-list — mirror of Java PermissionServiceImpl.PRICE_VIEW_ROLES
# (backend/java/cretas-api/.../service/impl/PermissionServiceImpl.java:303-320)
# Keep in sync when Java PRICE_VIEW_ROLES changes.
# ──────────────────────────────────────────────────────────────────────────
PRICE_VIEW_ROLES: frozenset[str] = frozenset({
    "factory_super_admin",
    "platform_admin",
    "procurement_manager",
    "finance_manager",
    "sales_manager",
    "dispatcher",
    "production_manager",
    "restaurant_manager",
    # PR #547 fix (2026-06-09): restaurant_owner was missing — owner's own revenue
    # was being stripped to None by _apply_rbac_strip, showing ¥0 on the AI gold
    # "本月营业收入" query. Java PermissionServiceImpl.PRICE_VIEW_ROLES line 315
    # already includes restaurant_owner; Python mirror was incomplete.
    # restaurant_purchaser also carried by Java PRICE_VIEW_ROLES (line 316) —
    # add here for consistency so the two mirrors stay in sync.
    "restaurant_owner",
    "restaurant_purchaser",
    # Backward-compatibility aliases (Java line 246-247 / 276-277)
    "permission_admin",
    "department_admin",
})


# ──────────────────────────────────────────────────────────────────────────
# Money detection patterns
#
# Two distinct uses:
#   1. As a key-name predicate — match the *key* itself to decide whether
#      the leaf value at that key is monetary (e.g. "totalAmount", "unitPrice").
#   2. As a KPI-card identity predicate — match a card's
#      ``key``/``title``/``label``/``unit`` to decide whether to null the
#      generic ``value`` / ``rawValue`` carriers inside that card.
#
# Conservative pattern: multi-character tokens only.  Single Chinese
# characters like 额 are too ambiguous (账单数 / 客单价 / 总额 all contain
# 额-shaped chars but only some are monetary). Stick to compound tokens.
# ──────────────────────────────────────────────────────────────────────────
_MONEY_PATTERN = re.compile(
    # English (case-insensitive)
    r"price|amount|revenue|profit|sales|income|expense|spending|payment"
    r"|receivable|payable|budget|payroll|salary|wage|cost|gmv|turnover"
    # Chinese — only compounds that unambiguously denote currency / amounts.
    # NOTE: bare 额/价/元 omitted on purpose (too generic — 客单价 / 门店数
    # carry "数/家" units and we don't want to strip them here).
    r"|金额|单价|总价|总额|销售额|采购额|营业额|应收账|应付账|应收|应付"
    r"|收入|支出|成本|利润|营收|预算|毛利|净利|薪资|工资|流水|总营收",
    re.IGNORECASE,
)

# Subkeys we null inside a money KPI card. Conservative: only carriers of
# absolute monetary quantities. ``changeRate`` / ``completionRate`` are
# percentages and stay visible (they convey trend direction without leaking
# the underlying amount).
_KPI_VALUE_SUBKEYS: tuple[str, ...] = (
    "value",
    "rawValue",
    "change",
    "targetValue",
)

# Identity keys we consult to decide whether a dict is a "money KPI card".
_KPI_IDENTITY_KEYS: tuple[str, ...] = (
    "key",
    "title",
    "label",
    "name",
    "metric",
    "metricCode",
    "metricName",
)

# Unit strings that signal a money KPI card regardless of the title — once we
# see ``unit: "元"`` we treat ``value`` / ``rawValue`` as monetary.
_MONEY_UNIT_TOKENS: tuple[str, ...] = ("元", "￥", "¥", "RMB", "万元", "千元")


def _is_money_key(key: Any) -> bool:
    """True when ``key`` is a string that looks like a money-named field."""
    return isinstance(key, str) and bool(_MONEY_PATTERN.search(key))


def _is_money_kpi_card(node: Any) -> bool:
    """Detect a money KPI card.

    A node qualifies when:
      * it is a ``dict`` with **at least one** of the carrier subkeys
        (``value`` / ``rawValue`` / ``change`` / ``targetValue``), **and**
      * either its ``unit`` field contains a money token (元 / ¥ / RMB / …)
        **or** any of the identity keys (``key`` / ``title`` / ``label`` / …)
        contains a money-suggesting substring.
    """
    if not isinstance(node, dict):
        return False
    if not any(k in node for k in _KPI_VALUE_SUBKEYS):
        return False

    unit = node.get("unit")
    if isinstance(unit, str) and any(tok in unit for tok in _MONEY_UNIT_TOKENS):
        return True

    return any(_is_money_key(node.get(k)) for k in _KPI_IDENTITY_KEYS)


def _walk(node: Any) -> None:
    """Depth-first in-place strip.

    Order of operations matters:
      1. If the current dict *is* a money KPI card, null its value carriers
         first — this catches the common ``[{"key":"total_revenue",
         "value":..., "rawValue":...}, ...]`` shape.
      2. Then iterate items. For money-named keys with primitive values, set
         them to ``None`` directly. For money-named keys with container
         values, recurse (nested KPI cards inside, e.g. ``budgetMetrics``).
      3. Non-money keys: recurse into their values regardless.
    """
    if isinstance(node, dict):
        if _is_money_kpi_card(node):
            for sk in _KPI_VALUE_SUBKEYS:
                if sk in node:
                    node[sk] = None

        for k, v in list(node.items()):
            if _is_money_key(k):
                if isinstance(v, (dict, list)):
                    _walk(v)
                else:
                    node[k] = None
            else:
                _walk(v)
        return

    if isinstance(node, list):
        for item in node:
            _walk(item)
        return

    # Leaf (str / int / float / Decimal / None / bool) — nothing to do
    # at this level; the parent dict/list visit nulls money-keyed leaves.


def strip_price_for_role(body: Any, role: Optional[str]) -> Any:
    """Mutate ``body`` in place, removing monetary content for roles outside
    :data:`PRICE_VIEW_ROLES`. Returns the same ``body`` for caller convenience.

    Parameters
    ----------
    body
        The response payload (dict, list, or primitive) to strip. The standard
        envelope from :func:`smartbi_compat.schema_compat.wrap_response` is NOT
        the right argument — pass the inner ``data`` payload so envelope keys
        (``code`` / ``message`` / ``timestamp`` / …) survive untouched.
    role
        JWT role string. ``None`` / empty / unknown roles are treated as
        ineligible (strip applied).

    Returns
    -------
    The same ``body`` reference, after in-place mutation when applicable.

    Notes
    -----
    * Decimals, dates, and other non-container leaves are not introspected.
    * Stripped values become ``None`` (Rule 4: never ``0`` / ``""`` — keep the
      missing-vs-zero distinction).
    * The walk is idempotent: a second pass over a stripped payload is a
      no-op because every sensitive key has already been set to ``None``.
    """
    if role and role in PRICE_VIEW_ROLES:
        return body
    if body is None:
        return body
    _walk(body)
    return body


__all__ = [
    "PRICE_VIEW_ROLES",
    "strip_price_for_role",
]
