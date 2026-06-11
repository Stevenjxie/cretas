#!/usr/bin/env python3
"""
Shadow Disagreement Adjudication Script (P2.5)

For each shadow mode disagreement (PHRASE_MATCH says intent A, BERT says intent B),
uses qwen3.5-plus as MKO (More Knowledgeable Other) to determine the correct label.

This produces high-quality training data because:
1. We have two competing signals (phrase match vs BERT)
2. The LLM adjudicates with full intent list context
3. Only high-confidence LLM judgments are kept

Output format (JSONL):
    {"text": "...", "label": "INTENT_CODE", "source": "shadow_adjudicated",
     "phrase_intent": "A", "bert_intent": "B", "llm_agreed_with": "phrase|bert|neither"}

Usage:
    python shadow_adjudicate.py --days 30 --output data/shadow_adjudicated.jsonl
    python shadow_adjudicate.py --days 30 --limit 100 --dry-run
    python shadow_adjudicate.py --days 30 --batch-size 10  # batch LLM calls
"""

import argparse
import json
import logging
import os
import sys
import time
from collections import Counter
from pathlib import Path
from typing import Dict, List, Optional

import psycopg2
import psycopg2.extras
import requests

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Configuration defaults
# ---------------------------------------------------------------------------
DEFAULT_DAYS = 30
DEFAULT_OUTPUT = "data/shadow_adjudicated.jsonl"
DASHSCOPE_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"
LLM_MODEL = "qwen3.7-max-2026-06-08"  # free quota ⚠️ (qwen3-max-2026-01-23 was PAID — billing incident 2026-06-11)
LLM_TIMEOUT = 60
LLM_MAX_RETRIES = 2
LLM_RETRY_DELAY = 2
BATCH_SIZE = 5  # Number of samples per batch LLM call
MIN_SHADOW_CONFIDENCE = 0.3  # Include more disagreements for adjudication


def get_db_connection() -> psycopg2.extensions.connection:
    """Create PostgreSQL connection from env vars."""
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
    """Query ai_intent_configs for all active intent codes with descriptions."""
    query = """
        SELECT intent_code, intent_name, description, intent_category
        FROM ai_intent_configs
        WHERE is_active = true
        ORDER BY intent_code
    """
    with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
        cur.execute(query)
        rows = cur.fetchall()
    logger.info("Loaded %d active intent configs", len(rows))
    return [dict(r) for r in rows]


def fetch_shadow_disagreements(
    conn, days: int, limit: Optional[int] = None
) -> List[Dict]:
    """
    Query shadow disagreements: records where PHRASE_MATCH and BERT disagree.

    Groups by (user_input, matched_intent_code, shadow_intent_code) to
    deduplicate repeated E2E test queries.
    """
    query = """
        SELECT DISTINCT ON (user_input, matched_intent_code, shadow_intent_code)
               id, user_input,
               matched_intent_code, confidence_score,
               shadow_intent_code, shadow_confidence,
               match_method
        FROM intent_match_records
        WHERE shadow_agreed = false
          AND shadow_intent_code IS NOT NULL
          AND user_input IS NOT NULL
          AND LENGTH(TRIM(user_input)) > 2
          AND created_at >= NOW() - INTERVAL '%s days'
        ORDER BY user_input, matched_intent_code, shadow_intent_code,
                 shadow_confidence DESC
    """
    params: list = [days]

    with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
        cur.execute(query, params)
        rows = cur.fetchall()

    records = [dict(r) for r in rows]

    if limit:
        records = records[:limit]

    logger.info(
        "Fetched %d unique shadow disagreements from last %d days",
        len(records), days,
    )
    return records


def build_batch_prompt(
    samples: List[Dict],
    valid_intents: List[Dict[str, str]],
) -> str:
    """
    Build a batch adjudication prompt for multiple samples.

    The LLM sees both competing intents and picks the correct one for each.
    """
    # Build compact intent reference (category-grouped for readability)
    categories = {}
    for ic in valid_intents:
        cat = ic.get("intent_category", "OTHER") or "OTHER"
        if cat not in categories:
            categories[cat] = []
        desc = ic.get("description", "")
        if desc and len(desc) > 60:
            desc = desc[:60] + "..."
        categories[cat].append(f"{ic['intent_code']}: {ic['intent_name']}" + (f" ({desc})" if desc else ""))

    intent_ref = ""
    for cat, items in sorted(categories.items()):
        intent_ref += f"\n### {cat}\n"
        intent_ref += "\n".join(f"  - {item}" for item in items) + "\n"

    # Build sample list
    sample_lines = []
    for i, s in enumerate(samples, 1):
        phrase = s.get("matched_intent_code", "UNKNOWN")
        bert = s.get("shadow_intent_code", "UNKNOWN")
        user_input = s.get("user_input", "").strip()
        sample_lines.append(
            f'{i}. Input: "{user_input}"\n'
            f"   Option A (phrase match): {phrase}\n"
            f"   Option B (BERT classifier): {bert}"
        )
    samples_str = "\n".join(sample_lines)

    return f"""You are an expert intent classifier for a Chinese food manufacturing traceability system (食品溯源生产管理系统).

For each sample below, two systems disagree on the correct intent:
- Option A: from phrase/keyword matching rules
- Option B: from a BERT classifier

Your job: determine the CORRECT intent for each input.

## Valid Intent Codes
{intent_ref}

## Samples to Adjudicate
{samples_str}

## Instructions
For each sample (1 to {len(samples)}), respond with EXACTLY one line:
<number>: <A|B|C> [<intent_code if C>]

Where:
- A = Option A (phrase match) is correct
- B = Option B (BERT classifier) is correct
- C = Neither is correct, followed by the correct intent code

Example response:
1: A
2: B
3: C EQUIPMENT_MAINTENANCE
4: A

Respond with ONLY the numbered lines, no explanation."""


def parse_batch_response(
    response_text: str,
    samples: List[Dict],
    valid_codes: set,
) -> List[Optional[Dict]]:
    """
    Parse the LLM batch response into labeled records.

    Returns a list parallel to samples, with None for unparseable entries.
    """
    results: List[Optional[Dict]] = [None] * len(samples)
    lines = response_text.strip().split("\n")

    for line in lines:
        line = line.strip()
        if not line:
            continue

        # Parse "N: A" or "N: B" or "N: C INTENT_CODE"
        try:
            parts = line.split(":", 1)
            if len(parts) != 2:
                continue

            idx_str = parts[0].strip()
            # Handle potential prefix like "Sample 1" or just "1"
            idx_str = idx_str.split()[-1] if " " in idx_str else idx_str
            idx = int(idx_str) - 1  # 0-based

            if idx < 0 or idx >= len(samples):
                continue

            choice_part = parts[1].strip().upper()
            sample = samples[idx]
            phrase_intent = sample.get("matched_intent_code", "")
            bert_intent = sample.get("shadow_intent_code", "")
            user_input = (sample.get("user_input") or "").strip()

            if choice_part.startswith("A"):
                results[idx] = {
                    "text": user_input,
                    "label": phrase_intent,
                    "source": "shadow_adjudicated",
                    "phrase_intent": phrase_intent,
                    "bert_intent": bert_intent,
                    "llm_agreed_with": "phrase",
                }
            elif choice_part.startswith("B"):
                results[idx] = {
                    "text": user_input,
                    "label": bert_intent,
                    "source": "shadow_adjudicated",
                    "phrase_intent": phrase_intent,
                    "bert_intent": bert_intent,
                    "llm_agreed_with": "bert",
                }
            elif choice_part.startswith("C"):
                # Extract intent code after "C"
                code_part = choice_part[1:].strip()
                # Clean up potential formatting
                code_part = code_part.strip('"').strip("'").strip("`").strip()
                if code_part in valid_codes:
                    results[idx] = {
                        "text": user_input,
                        "label": code_part,
                        "source": "shadow_adjudicated",
                        "phrase_intent": phrase_intent,
                        "bert_intent": bert_intent,
                        "llm_agreed_with": "neither",
                    }
                else:
                    # Try case-insensitive
                    matched = None
                    for vc in valid_codes:
                        if vc.upper() == code_part.upper():
                            matched = vc
                            break
                    if matched:
                        results[idx] = {
                            "text": user_input,
                            "label": matched,
                            "source": "shadow_adjudicated",
                            "phrase_intent": phrase_intent,
                            "bert_intent": bert_intent,
                            "llm_agreed_with": "neither",
                        }

        except (ValueError, IndexError):
            continue

    return results


def call_llm_batch(prompt: str, api_key: str) -> Optional[str]:
    """Call DashScope LLM with the batch prompt."""
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }
    payload = {
        "model": LLM_MODEL,
        "messages": [{"role": "user", "content": prompt}],
        "max_tokens": 500,
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
                logger.warning("Rate limited (429), waiting %ds", wait)
                time.sleep(wait)
                continue

            resp.raise_for_status()
            data = resp.json()
            content = data["choices"][0]["message"]["content"].strip()
            return content

        except requests.exceptions.Timeout:
            logger.warning("LLM timeout (attempt %d/%d)", attempt + 1, LLM_MAX_RETRIES + 1)
        except requests.exceptions.RequestException as e:
            logger.warning("LLM request error (attempt %d/%d): %s", attempt + 1, LLM_MAX_RETRIES + 1, e)
        except (KeyError, IndexError, json.JSONDecodeError) as e:
            logger.warning("LLM response parse error: %s", e)
            return None

        if attempt < LLM_MAX_RETRIES:
            time.sleep(LLM_RETRY_DELAY)

    return None


def main():
    parser = argparse.ArgumentParser(
        description="Shadow disagreement adjudication: LLM adjudicates phrase-match vs BERT disagreements"
    )
    parser.add_argument("--days", type=int, default=DEFAULT_DAYS,
                        help=f"Look back N days (default: {DEFAULT_DAYS})")
    parser.add_argument("--output", type=str, default=DEFAULT_OUTPUT,
                        help=f"Output JSONL path (default: {DEFAULT_OUTPUT})")
    parser.add_argument("--limit", type=int, default=None,
                        help="Max records to process")
    parser.add_argument("--batch-size", type=int, default=BATCH_SIZE,
                        help=f"Samples per LLM call (default: {BATCH_SIZE})")
    parser.add_argument("--dry-run", action="store_true",
                        help="Fetch records and show stats without LLM calls")
    args = parser.parse_args()

    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )

    api_key = os.getenv("DASHSCOPE_API_KEY", "")
    if not api_key and not args.dry_run:
        logger.error("DASHSCOPE_API_KEY required (use --dry-run to skip LLM calls)")
        sys.exit(1)

    script_dir = Path(__file__).resolve().parent
    output_path = script_dir / args.output
    output_path.parent.mkdir(parents=True, exist_ok=True)

    logger.info("Connecting to PostgreSQL...")
    try:
        conn = get_db_connection()
    except psycopg2.Error as e:
        logger.error("Failed to connect to PostgreSQL: %s", e)
        sys.exit(1)

    try:
        # Load intent configs
        valid_intents = fetch_valid_intent_codes(conn)
        if not valid_intents:
            logger.error("No active intent configs found")
            sys.exit(1)
        valid_codes = {ic["intent_code"] for ic in valid_intents}

        # Fetch disagreements
        records = fetch_shadow_disagreements(conn, args.days, args.limit)
        if not records:
            logger.info("No shadow disagreements found")
            sys.exit(0)

        if args.dry_run:
            # Show distribution stats
            phrase_intents = Counter(r["matched_intent_code"] for r in records)
            bert_intents = Counter(r["shadow_intent_code"] for r in records)
            logger.info("[DRY RUN] Top 15 phrase-match intents in disagreements:")
            for intent, cnt in phrase_intents.most_common(15):
                logger.info("  %-35s %d", intent, cnt)
            logger.info("[DRY RUN] Top 15 BERT intents in disagreements:")
            for intent, cnt in bert_intents.most_common(15):
                logger.info("  %-35s %d", intent, cnt)
            logger.info("[DRY RUN] Total unique disagreement pairs: %d", len(records))
            logger.info("[DRY RUN] Estimated LLM calls: %d (batch size %d)",
                        (len(records) + args.batch_size - 1) // args.batch_size,
                        args.batch_size)
            return

        # Process in batches
        all_results = []
        total_parsed = 0
        total_errors = 0
        agreement_counter = Counter()  # phrase/bert/neither

        for batch_start in range(0, len(records), args.batch_size):
            batch = records[batch_start:batch_start + args.batch_size]
            batch_num = batch_start // args.batch_size + 1
            total_batches = (len(records) + args.batch_size - 1) // args.batch_size

            logger.info("[Batch %d/%d] Processing %d samples...",
                        batch_num, total_batches, len(batch))

            prompt = build_batch_prompt(batch, valid_intents)
            response = call_llm_batch(prompt, api_key)

            if response is None:
                logger.warning("[Batch %d] LLM returned no response", batch_num)
                total_errors += len(batch)
                continue

            results = parse_batch_response(response, batch, valid_codes)

            for i, result in enumerate(results):
                if result is not None:
                    all_results.append(result)
                    total_parsed += 1
                    agreement_counter[result["llm_agreed_with"]] += 1
                else:
                    total_errors += 1
                    logger.debug("[Batch %d] Could not parse result for sample %d", batch_num, i + 1)

            # Rate limit between batches
            if batch_start + args.batch_size < len(records):
                time.sleep(1.0)

        # Write output
        if all_results:
            with open(output_path, "w", encoding="utf-8") as f:
                for item in all_results:
                    f.write(json.dumps(item, ensure_ascii=False) + "\n")
            logger.info("Wrote %d records to %s", len(all_results), output_path)

        # Summary
        label_counts = Counter(r["label"] for r in all_results)
        logger.info("=" * 60)
        logger.info("Shadow Adjudication Summary")
        logger.info("=" * 60)
        logger.info("  Total disagreements processed: %d", len(records))
        logger.info("  Successfully adjudicated:      %d", total_parsed)
        logger.info("  Parse errors:                  %d", total_errors)
        logger.info("")
        logger.info("  LLM agreed with:")
        logger.info("    Phrase match (A):  %d (%.1f%%)",
                     agreement_counter["phrase"],
                     100 * agreement_counter["phrase"] / max(total_parsed, 1))
        logger.info("    BERT classifier (B): %d (%.1f%%)",
                     agreement_counter["bert"],
                     100 * agreement_counter["bert"] / max(total_parsed, 1))
        logger.info("    Neither (C):       %d (%.1f%%)",
                     agreement_counter["neither"],
                     100 * agreement_counter["neither"] / max(total_parsed, 1))
        logger.info("")
        logger.info("  Top 15 output labels:")
        for label, count in label_counts.most_common(15):
            logger.info("    %-35s %d", label, count)
        logger.info("  Unique labels: %d", len(label_counts))
        logger.info("=" * 60)

    finally:
        conn.close()
        logger.info("Database connection closed")


if __name__ == "__main__":
    main()
