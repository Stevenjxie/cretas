"""Dish-classification self-learning — LLM-enrich the '其他' bucket.

Background
----------
``dish_classifier.classify_dish`` is a curated keyword-dict scan: any dish the
dict doesn't recognise (and that hasn't GRADUATED via the learning framework)
returns ``'其他'``. Left alone, new / uncommon dishes pile into '其他' forever
and the dictionary never grows from usage.

This module closes the learning loop's *capture* side for dish classification:

  1. ``collect_unknown_dishes`` — pulls the DISTINCT dish names from an upload's
     parsed ``商品信息`` items that classify to '其他' (i.e. static dict AND any
     graduated rule both miss). Pure / SYNC.
  2. ``enrich_unknown_dishes`` — ONE LLM call classifying every unknown dish into
     one of the KNOWN categories (``get_categories()`` — the LLM may NOT invent
     new categories; any out-of-set answer is skipped), then captures each valid
     ``dish → category`` as a learning candidate via
     ``capture_candidate(pool, "classification", dish, category, factory_id,
     "llm", confidence, business_type="restaurant")``.

Graduation is NOT done here — candidates only graduate via the human ``--apply``
promote CLI. With the framework's gate (conf>=0.9 OR correction, AND ≥2
factories), a single LLM guess at conf 0.85 cannot auto-graduate; it needs
cross-factory agreement. That is intentional: we do not auto-trust one LLM
classification.

⛔ Fire-and-forget: this is a learning *side-effect* of materialization. It must
NEVER block or fail the materialization. The public orchestrator
``maybe_enrich_dish_classifications`` swallows every error (logs only) and the
underlying ``capture_candidate`` is itself fail-open.
"""
from __future__ import annotations

import logging
from typing import Any, Dict, List, Optional

from .dish_classifier import classify_dish, get_categories
from .dish_name_normalizer import normalize_dish_name
from .item_parser import parse_items

logger = logging.getLogger(__name__)

# The column restaurant uploads carry their itemised dish/qty/price string in.
# Same column dish_category_breakdown parses.
_ITEM_COLUMN = "商品信息"

# Bound the LLM call: at most this many DISTINCT unknown dishes per upload.
# One materialization = at most one LLM call. If an upload surfaces more
# unknowns than this we classify the first N (alphabetical for determinism)
# and log the truncation — never silently cap.
_MAX_UNKNOWN_DISHES = 100

# Cap rows scanned for dish extraction (mirrors dish_category_breakdown's
# _SAMPLE_ROWS so we don't iterate a 200K-row column unbounded twice).
_SAMPLE_ROWS = 500_000

# Single-LLM-guess confidence. Deliberately < the graduation threshold (0.9):
# one LLM classification corroborated by only one factory must NOT auto-graduate;
# it needs cross-factory agreement. See module docstring.
_LLM_CONFIDENCE = 0.85

_MAX_TOKENS = 1500


def collect_unknown_dishes(backend: Any, schema: Any) -> List[str]:
    """Return DISTINCT dish names from the upload that classify to '其他'.

    A dish is "unknown" when BOTH the static keyword dict and any graduated
    classification rule miss (``classify_dish`` returns '其他'). Names are
    normalised (variant suffixes stripped) exactly as dish_category_breakdown
    does, so we learn the parent dish name not per-SKU variants.

    Pure / SYNC. Returns ``[]`` if the upload has no ``商品信息`` column or no
    parseable unknown items. Never raises on a normal upload — callers should
    still guard, but this is defensive.
    """
    df = getattr(backend, "_df", None)
    if df is None:
        return []
    try:
        columns = list(df.columns)
    except Exception:  # noqa: BLE001
        return []
    if _ITEM_COLUMN not in columns:
        return []

    unknown: set[str] = set()
    try:
        series = df.get_column(_ITEM_COLUMN).head(_SAMPLE_ROWS)
        for raw_value in series.to_list():
            if not raw_value:
                continue
            for item in parse_items(str(raw_value)):
                name = normalize_dish_name(item.get("name"))
                if not name:
                    continue
                # is_signature dishes always classify to 招牌菜 — never unknown.
                if item.get("is_signature", False):
                    continue
                if classify_dish(name, False) == "其他":
                    unknown.add(name)
    except Exception as e:  # noqa: BLE001
        logger.warning("[dish-learn] collect_unknown_dishes failed (ignored): %s", e)
        return []

    return sorted(unknown)


def _build_prompt(dish_names: List[str], categories: List[str]) -> str:
    """Tight prompt: classify each dish into EXACTLY one of `categories`."""
    cats = "、".join(categories)
    dishes = "\n".join(f"- {n}" for n in dish_names)
    return (
        f"你是餐饮菜品分类助手。请把下面每个菜品名归入且仅归入以下类别之一: {cats}。\n"
        f"只能从给定类别里选, 不要发明新类别; 实在无法判断就归到「其他」。\n\n"
        f"菜品列表:\n{dishes}\n\n"
        f'只返回一个 JSON 对象, 形如 {{"菜品名": "类别"}}, 不要任何额外文字或解释。'
    )


def _parse_llm_map(raw: str) -> Dict[str, str]:
    """Parse the LLM's dish→category map. Returns {} on any failure."""
    if not raw or not raw.strip():
        return {}
    try:
        from common.utils.json_parser import robust_json_parse
        parsed = robust_json_parse(raw, fallback=None)
    except Exception as e:  # noqa: BLE001
        logger.warning("[dish-learn] JSON parse raised (%s) — no captures", e)
        return {}
    if not isinstance(parsed, dict):
        return {}
    out: Dict[str, str] = {}
    for k, v in parsed.items():
        if isinstance(k, str) and isinstance(v, str) and k.strip() and v.strip():
            out[k.strip()] = v.strip()
    return out


async def enrich_unknown_dishes(
    pool: Any,
    factory_id: Optional[str],
    dish_names: List[str],
) -> int:
    """LLM-classify `dish_names` into known categories + capture each candidate.

    ONE LLM call for the whole batch. The LLM output is constrained to
    ``get_categories()``; any dish mapped to a category NOT in that set (or to
    '其他') is skipped — we only capture a learnable classification. Each valid
    ``dish → category`` is captured via ``capture_candidate(..., method="llm",
    business_type="restaurant")``.

    Returns the number of candidates captured. NEVER raises — an LLM / pool
    failure is logged and yields 0 (fire-and-forget).
    """
    if not dish_names:
        return 0

    categories = get_categories()
    # Constrain target set: every known category EXCEPT '其他' (capturing a
    # '其他' classification teaches nothing — it's the default).
    valid_targets = {c for c in categories if c != "其他"}

    if len(dish_names) > _MAX_UNKNOWN_DISHES:
        logger.info(
            "[dish-learn] %d unknown dishes > cap %d — classifying first %d (truncated)",
            len(dish_names), _MAX_UNKNOWN_DISHES, _MAX_UNKNOWN_DISHES,
        )
        dish_names = dish_names[:_MAX_UNKNOWN_DISHES]

    prompt = _build_prompt(dish_names, categories)

    try:
        from smartbi.services.insights import llm_client as llm
        raw = await llm.call_llm(
            prompt,
            system_role="你是餐饮菜品分类助手, 只输出 JSON。",
            enable_thinking=False,
            max_tokens=_MAX_TOKENS,
        )
    except Exception as e:  # noqa: BLE001 — call_llm already swallows, belt-and-suspenders
        logger.warning("[dish-learn] LLM call raised (%s) — no captures", e)
        return 0

    dish_to_cat = _parse_llm_map(raw)
    if not dish_to_cat:
        logger.info("[dish-learn] LLM produced no usable classifications")
        return 0

    requested = set(dish_names)
    captured = 0
    skipped_out_of_set = 0
    for dish, category in dish_to_cat.items():
        # Only accept dishes we actually asked about, mapped to a valid
        # (non-'其他') category from the known set. Out-of-set = skip.
        if dish not in requested:
            continue
        if category not in valid_targets:
            skipped_out_of_set += 1
            continue
        try:
            from smartbi.services.learning_promotion import capture_candidate
            await capture_candidate(
                pool,
                "classification",
                dish,
                category,
                factory_id,
                "llm",
                _LLM_CONFIDENCE,
                business_type="restaurant",
            )
            captured += 1
        except Exception as e:  # noqa: BLE001 — capture_candidate is fail-open already
            logger.warning("[dish-learn] capture_candidate raised (%s) — skip dish", e)

    logger.info(
        "[dish-learn] captured %d classification candidates (%d skipped out-of-set) "
        "from %d unknown dishes",
        captured, skipped_out_of_set, len(dish_names),
    )
    return captured


async def maybe_enrich_dish_classifications(
    pool: Any,
    factory_id: Optional[str],
    backend: Any,
    schema: Any,
) -> int:
    """Fire-and-forget orchestrator: collect unknown dishes → LLM-enrich → capture.

    Only runs for restaurant uploads (schema.domain == restaurant) that actually
    carry dish data. Returns the number of candidates captured (0 on any skip /
    failure). NEVER raises — wraps the whole thing so a learning side-effect can
    never block or fail materialization.
    """
    try:
        domain = getattr(getattr(schema, "domain", None), "value", None)
        if domain != "restaurant":
            return 0

        unknowns = collect_unknown_dishes(backend, schema)
        if not unknowns:
            return 0  # nothing to learn — every dish already classified

        logger.info(
            "[dish-learn] upload factory=%s: %d distinct unknown dishes → LLM enrich",
            factory_id, len(unknowns),
        )
        return await enrich_unknown_dishes(pool, factory_id, unknowns)
    except Exception as e:  # noqa: BLE001 — fire-and-forget, learning never blocks
        logger.warning(
            "[dish-learn] enrichment failed (non-blocking): %s", e, exc_info=True
        )
        return 0
