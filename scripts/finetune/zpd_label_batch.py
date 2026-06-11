#!/usr/bin/env python3
"""
ZPD MKO Labeling Script

Queries intent_match_records where zpd_boundary = true (Zone of Proximal
Development boundary samples) and uses a qwen3.5-plus LLM via DashScope
to label each record with the correct intent code.

These are samples that fell outside the classifier's confident zone and
required full LLM fallback or were flagged as OOD — exactly the cases
where a "More Knowledgeable Other" (the LLM) can provide the ground-truth
label for incremental fine-tuning.

Output format (JSONL):
    {"text": "...", "label": "INTENT_CODE", "source": "zpd_mko"}

Usage:
    python zpd_label_batch.py --days 7 --output data/zpd_labeled.jsonl
    python zpd_label_batch.py --days 30 --limit 500 --dry-run
"""

import argparse
import json
import logging
import os
import sys
import time
from pathlib import Path
from typing import Dict, List, Optional, Tuple

import psycopg2
import psycopg2.extras
import requests

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Configuration defaults
# ---------------------------------------------------------------------------
DEFAULT_DAYS = 7
DEFAULT_OUTPUT = "data/zpd_labeled.jsonl"
DASHSCOPE_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"
LLM_MODEL = "qwen3.7-max-2026-06-08"  # free quota ⚠️ (qwen3-max-2026-01-23 was PAID — billing incident 2026-06-11)
LLM_TIMEOUT = 60  # seconds
LLM_MAX_RETRIES = 2
LLM_RETRY_DELAY = 2  # seconds


def get_db_connection() -> psycopg2.extensions.connection:
    """
    Create a PostgreSQL connection using environment variables.

    Env vars (with defaults for local dev):
        POSTGRES_HOST     (default: localhost)
        POSTGRES_PORT     (default: 5432)
        POSTGRES_DB       (default: cretas_db)
        POSTGRES_USER     (default: cretas_user)
        POSTGRES_PASSWORD (default: cretas_pass)
    """
    conn = psycopg2.connect(
        host=os.getenv("POSTGRES_HOST", "localhost"),
        port=int(os.getenv("POSTGRES_PORT", "5432")),
        dbname=os.getenv("POSTGRES_DB", "cretas_db"),
        user=os.getenv("POSTGRES_USER", "cretas_user"),
        password=os.getenv("POSTGRES_PASSWORD", "cretas_pass"),
    )
    conn.set_client_encoding("UTF8")
    return conn


def fetch_valid_intent_codes(conn) -> List[Dict[str, str]]:
    """
    Query ai_intent_configs for all active intent codes.

    Returns a list of dicts with intent_code, intent_name, and description
    so the LLM has full context for labeling.
    """
    query = """
        SELECT intent_code, intent_name, description, intent_category
        FROM ai_intent_configs
        WHERE is_active = true
        ORDER BY intent_code
    """
    with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
        cur.execute(query)
        rows = cur.fetchall()
    logger.info("Loaded %d active intent configs from ai_intent_configs", len(rows))
    return [dict(r) for r in rows]


def fetch_zpd_records(
    conn, days: int, limit: Optional[int] = None
) -> List[Dict]:
    """
    Query intent_match_records where zpd_boundary = true within the last
    N days.
    """
    query = """
        SELECT id, user_input, normalized_input,
               matched_intent_code, matched_intent_name,
               confidence_score, match_method,
               llm_intent_code, llm_confidence,
               shadow_intent_code, shadow_confidence,
               created_at
        FROM intent_match_records
        WHERE zpd_boundary = true
          AND created_at >= NOW() - INTERVAL '%s days'
        ORDER BY created_at DESC
    """
    params: list = [days]

    if limit:
        query += " LIMIT %s"
        params.append(limit)

    with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
        cur.execute(query, params)
        rows = cur.fetchall()
    logger.info(
        "Fetched %d ZPD boundary records from the last %d days", len(rows), days
    )
    return [dict(r) for r in rows]


def build_labeling_prompt(
    user_input: str,
    matched_intent_code: Optional[str],
    llm_intent_code: Optional[str],
    shadow_intent_code: Optional[str],
    valid_intents: List[Dict[str, str]],
) -> str:
    """
    Build the LLM prompt for intent labeling.

    The prompt gives the LLM:
    - The user's original input text
    - What the system matched (if anything)
    - The full list of valid intent codes with descriptions
    - Instructions to pick exactly one or flag as OOD
    """
    intent_list_str = "\n".join(
        f"  - {ic['intent_code']}: {ic['intent_name']}"
        + (f" ({ic['description'][:80]}...)" if ic.get("description") and len(ic["description"]) > 80 else f" ({ic.get('description', '')})" if ic.get("description") else "")
        for ic in valid_intents
    )

    context_parts = []
    if matched_intent_code:
        context_parts.append(f"System matched intent: {matched_intent_code}")
    if llm_intent_code:
        context_parts.append(f"LLM fallback suggested: {llm_intent_code}")
    if shadow_intent_code:
        context_parts.append(f"Shadow classifier suggested: {shadow_intent_code}")
    context_str = "\n".join(context_parts) if context_parts else "No prior match available."

    return f"""You are an intent classification expert for a food traceability manufacturing system.

Given a user's natural language input, classify it into exactly ONE intent code from the list below.
If the input genuinely does not match any intent (out-of-domain), respond with "OOD".

## User Input
"{user_input}"

## System Context
{context_str}

## Valid Intent Codes
{intent_list_str}

## Instructions
1. Analyze the user input carefully. Consider the business context (food manufacturing, quality control, warehouse, production, equipment, etc.).
2. Pick the single best-matching intent code from the list above.
3. If the input is truly out-of-domain (casual chat, unrelated questions), respond with "OOD".
4. Respond with ONLY the intent code string (e.g., "MATERIAL_BATCH_QUERY") or "OOD". No explanation needed."""


def call_llm(
    prompt: str,
    api_key: str,
) -> Optional[str]:
    """
    Call DashScope qwen3.5-plus to label one sample.

    Returns the raw text response (should be an intent code or "OOD").
    """
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }
    payload = {
        "model": LLM_MODEL,
        "messages": [{"role": "user", "content": prompt}],
        "max_tokens": 100,
        "temperature": 0.1,
        "enable_thinking": False,
    }

    for attempt in range(LLM_MAX_RETRIES + 1):
        try:
            resp = requests.post(
                f"{DASHSCOPE_BASE_URL}/chat/completions",
                headers=headers,
                json=payload,
                timeout=LLM_TIMEOUT,
            )
            if resp.status_code == 429:
                wait = LLM_RETRY_DELAY * (attempt + 1)
                logger.warning("Rate limited (429), waiting %ds before retry", wait)
                time.sleep(wait)
                continue

            resp.raise_for_status()
            data = resp.json()
            content = data["choices"][0]["message"]["content"].strip()
            return content

        except requests.exceptions.Timeout:
            logger.warning(
                "LLM timeout (attempt %d/%d)", attempt + 1, LLM_MAX_RETRIES + 1
            )
        except requests.exceptions.RequestException as e:
            logger.warning(
                "LLM request error (attempt %d/%d): %s",
                attempt + 1,
                LLM_MAX_RETRIES + 1,
                e,
            )
        except (KeyError, IndexError, json.JSONDecodeError) as e:
            logger.warning("LLM response parse error: %s", e)
            return None

        if attempt < LLM_MAX_RETRIES:
            time.sleep(LLM_RETRY_DELAY)

    return None


def validate_label(label: str, valid_codes: set) -> Optional[str]:
    """
    Validate and normalize the LLM's response into a clean intent code.

    Returns None if the label cannot be validated.
    """
    if not label:
        return None

    # Strip quotes, whitespace, markdown formatting
    cleaned = label.strip().strip('"').strip("'").strip("`").strip()

    # Direct match
    if cleaned in valid_codes:
        return cleaned

    # Case-insensitive match
    upper = cleaned.upper()
    for code in valid_codes:
        if code.upper() == upper:
            return code

    # OOD is a valid response
    if upper == "OOD":
        return "OOD"

    # The LLM might have returned extra text; try to find a code in it
    for code in valid_codes:
        if code in cleaned:
            return code

    logger.debug("Could not validate label: '%s'", label)
    return None


def main():
    parser = argparse.ArgumentParser(
        description="ZPD MKO labeling: use LLM to label ZPD boundary samples for fine-tuning"
    )
    parser.add_argument(
        "--days",
        type=int,
        default=DEFAULT_DAYS,
        help=f"Look back N days for ZPD records (default: {DEFAULT_DAYS})",
    )
    parser.add_argument(
        "--output",
        type=str,
        default=DEFAULT_OUTPUT,
        help=f"Output JSONL file path (default: {DEFAULT_OUTPUT})",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=None,
        help="Max number of records to process (default: no limit)",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Fetch records and show prompts but do not call LLM or write output",
    )
    args = parser.parse_args()

    # Setup logging
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )

    # Validate API key
    api_key = os.getenv("DASHSCOPE_API_KEY", "")
    if not api_key and not args.dry_run:
        logger.error(
            "DASHSCOPE_API_KEY environment variable is required (set --dry-run to skip LLM calls)"
        )
        sys.exit(1)

    # Resolve output path relative to this script's directory
    script_dir = Path(__file__).resolve().parent
    output_path = script_dir / args.output
    output_path.parent.mkdir(parents=True, exist_ok=True)

    # Connect to database
    logger.info("Connecting to PostgreSQL...")
    try:
        conn = get_db_connection()
    except psycopg2.Error as e:
        logger.error("Failed to connect to PostgreSQL: %s", e)
        sys.exit(1)

    try:
        # Load valid intent codes
        valid_intents = fetch_valid_intent_codes(conn)
        if not valid_intents:
            logger.error("No active intent configs found in ai_intent_configs")
            sys.exit(1)
        valid_codes = {ic["intent_code"] for ic in valid_intents}

        # Fetch ZPD boundary records
        records = fetch_zpd_records(conn, args.days, args.limit)
        if not records:
            logger.info("No ZPD boundary records found in the last %d days", args.days)
            sys.exit(0)

        # Process each record
        labeled_count = 0
        skipped_count = 0
        ood_count = 0
        error_count = 0
        results = []

        for i, record in enumerate(records, 1):
            user_input = record["user_input"]
            if not user_input or not user_input.strip():
                skipped_count += 1
                continue

            logger.info(
                "[%d/%d] Processing: '%s' (matched=%s)",
                i,
                len(records),
                user_input[:60],
                record.get("matched_intent_code", "none"),
            )

            prompt = build_labeling_prompt(
                user_input=user_input,
                matched_intent_code=record.get("matched_intent_code"),
                llm_intent_code=record.get("llm_intent_code"),
                shadow_intent_code=record.get("shadow_intent_code"),
                valid_intents=valid_intents,
            )

            if args.dry_run:
                logger.info("  [DRY RUN] Prompt length: %d chars", len(prompt))
                logger.info("  [DRY RUN] First 200 chars of prompt: %s", prompt[:200])
                continue

            # Call LLM
            raw_label = call_llm(prompt, api_key)
            if raw_label is None:
                logger.warning("  LLM returned no response, skipping")
                error_count += 1
                continue

            # Validate
            label = validate_label(raw_label, valid_codes)
            if label is None:
                logger.warning(
                    "  Could not validate LLM response: '%s'", raw_label[:100]
                )
                error_count += 1
                continue

            if label == "OOD":
                logger.info("  Labeled as OOD (out-of-domain)")
                ood_count += 1
                # Still write OOD records so they can be used as negative examples
                results.append(
                    {
                        "text": user_input.strip(),
                        "label": "OOD",
                        "source": "zpd_mko",
                    }
                )
                continue

            logger.info("  Labeled: %s", label)
            results.append(
                {
                    "text": user_input.strip(),
                    "label": label,
                    "source": "zpd_mko",
                }
            )
            labeled_count += 1

            # Small delay to avoid rate limiting
            if i < len(records):
                time.sleep(0.5)

        # Write output
        if not args.dry_run and results:
            with open(output_path, "w", encoding="utf-8") as f:
                for item in results:
                    f.write(json.dumps(item, ensure_ascii=False) + "\n")
            logger.info("Wrote %d records to %s", len(results), output_path)

        # Summary
        logger.info("=" * 50)
        logger.info("ZPD MKO Labeling Summary")
        logger.info("=" * 50)
        logger.info("  Total ZPD records fetched: %d", len(records))
        logger.info("  Successfully labeled:      %d", labeled_count)
        logger.info("  Labeled as OOD:            %d", ood_count)
        logger.info("  Skipped (empty input):     %d", skipped_count)
        logger.info("  Errors (LLM/validation):   %d", error_count)
        if not args.dry_run:
            logger.info("  Output file:               %s", output_path)

    finally:
        conn.close()
        logger.info("Database connection closed")


if __name__ == "__main__":
    main()
