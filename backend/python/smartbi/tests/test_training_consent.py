"""Unit tests for the training-consent foundation (data-sovereignty enforcement).

Covers:
  1. anonymize_for_shared — value bucketing of absolute ¥ + label pseudonymization
     + KEEPS %/ratios/shape intact (analytical meaning preserved).
  2. export shared-gating — synthetic ALWAYS in (unconditional); consented
     shared_anon real rows in (anonymized); none/private real rows EXCLUDED
     (default-deny).
  3. export private mode — consented 'private' → RAW; non-consented → refuse.
  4. migration version > frontier + table/policy present.

🔒 Default-deny + synthetic-always + analytical-meaning-preserved are asserted.
"""
from __future__ import annotations

import ast
import os
import re
import sys

TESTS_DIR = os.path.dirname(__file__)
PYTHON_ROOT = os.path.normpath(os.path.join(TESTS_DIR, "..", ".."))  # backend/python
if PYTHON_ROOT not in sys.path:
    sys.path.insert(0, PYTHON_ROOT)

import pytest

from smartbi.services.corpus_anonymize import (
    anonymize_for_shared,
    _bucket_amount,
    _bucket_amounts_in_text,
)

# Import the export script as a module (path: backend/python/scripts).
import importlib.util

_EXPORT_PATH = os.path.join(PYTHON_ROOT, "scripts", "export_distillation_dataset.py")
_spec = importlib.util.spec_from_file_location("export_distillation_dataset", _EXPORT_PATH)
export_mod = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(export_mod)


# ===========================================================================
# 1. Anonymization: bucket absolute ¥, pseudonymize labels, KEEP %/shape
# ===========================================================================

class TestAnonymizeBucketing:
    def test_bucket_amount_ranges(self):
        assert _bucket_amount(500) == "¥1千以内"
        assert _bucket_amount(5_000) == "¥1千-1万"
        assert _bucket_amount(50_000) == "¥1-10万"
        assert _bucket_amount(300_000) == "¥10-50万"
        assert _bucket_amount(2_000_000) == "¥100-500万"
        assert _bucket_amount(200_000_000) == "¥1亿以上"

    def test_absolute_yuan_bucketed(self):
        txt = "本月营收¥1234567，成本¥456789。"
        out = _bucket_amounts_in_text(txt)
        # exact numbers gone, magnitude buckets present
        assert "1234567" not in out
        assert "456789" not in out
        assert "¥100-500万" in out  # 1,234,567
        assert "¥10-50万" in out    # 456,789

    def test_wan_unit_bucketed(self):
        txt = "毛利123.4万元"
        out = _bucket_amounts_in_text(txt)
        assert "123.4" not in out
        assert "¥100-500万" in out  # 123.4万 = 1,234,000

    def test_percent_and_ratio_preserved(self):
        """🔒 analytical SHAPE must survive: %/ratios are NOT bucketed."""
        txt = "毛利率为45.6%，环比增长12%，是去年的2.3倍。"
        out = _bucket_amounts_in_text(txt)
        assert "45.6%" in out
        assert "12%" in out
        assert "2.3倍" in out

    def test_bare_number_without_currency_not_bucketed(self):
        # "前5名" / "3个门店" are counts/ranks, not financial amounts → keep.
        txt = "前5名门店贡献了60%的销售额"
        out = _bucket_amounts_in_text(txt)
        assert "前5名" in out
        assert "60%" in out


class TestAnonymizePseudonymize:
    def test_store_dish_supplier_pseudonymized(self):
        in_txt = "和府捞面门店销量第一，麻辣香锅菜品最畅销，鲜丰水果供应商供货稳定。"
        out_txt = "建议和府捞面门店加大备货。"
        a_in, a_out = anonymize_for_shared(in_txt, out_txt, "restaurant")
        # tenant entity names gone
        assert "和府捞面" not in a_in
        assert "麻辣香锅" not in a_in
        assert "鲜丰水果" not in a_in
        # placeholders present
        assert re.search(r"门店[A-Z]", a_in)
        assert re.search(r"菜品[A-Z]", a_in)
        assert re.search(r"供应商[A-Z]", a_in)

    def test_consistent_mapping_within_sample(self):
        """Same entity name → same placeholder across input AND output."""
        in_txt = "甲乙丙门店表现最好"
        out_txt = "甲乙丙门店应继续投入"
        a_in, a_out = anonymize_for_shared(in_txt, out_txt, "restaurant")
        m_in = re.search(r"门店([A-Z])", a_in)
        m_out = re.search(r"门店([A-Z])", a_out)
        assert m_in and m_out
        assert m_in.group(1) == m_out.group(1)

    def test_shape_preserved_end_to_end(self):
        """🔒 Combined: names → placeholders, ¥ → buckets, %/ranking kept."""
        in_txt = "星巴克门店本月营收¥2500000，占比35%，排名第1。"
        out_txt = "星巴克门店环比增长18%。"
        a_in, a_out = anonymize_for_shared(in_txt, out_txt, "restaurant")
        assert "星巴克" not in a_in and "星巴克" not in a_out
        assert "2500000" not in a_in
        assert "¥100-500万" in a_in   # 2.5M bucketed
        assert "35%" in a_in          # ratio kept
        assert "第1" in a_in          # ranking kept
        assert "18%" in a_out         # trend kept


# ===========================================================================
# 2. Export shared-gating: synthetic always; shared_anon in; none/private out
# ===========================================================================

def _row(factory_id, *, metadata=None, input_text="问题X", teacher_output="答案Y",
         business_type="restaurant", system_prompt=None):
    return {
        "id": 1,
        "source": "chat_qa",
        "business_type": business_type,
        "factory_id": factory_id,
        "task_type": "qa",
        "template_codes": None,
        "system_prompt": system_prompt,
        "input_text": input_text,
        "teacher_model": "qwen3-max",
        "teacher_output": teacher_output,
        "created_at": None,
        "metadata": metadata,
    }


class TestSharedGating:
    def test_synthetic_unconditional_by_sentinel_factory(self):
        rows = [_row("SEED_R"), _row("SEED_F")]
        gated = export_mod.gate_shared_rows(rows, consent={})
        assert len(gated) == 2
        assert all(g["consent_class"] == "synthetic" for g in gated)
        # raw text untouched for synthetic
        assert all(g["input_text"] is None for g in gated)

    def test_synthetic_by_metadata_source_kind(self):
        rows = [_row("F001", metadata={"source_kind": "synthetic_seed"})]
        gated = export_mod.gate_shared_rows(rows, consent={})  # no consent at all
        assert len(gated) == 1
        assert gated[0]["consent_class"] == "synthetic"

    def test_real_none_excluded_default_deny(self):
        rows = [_row("F001")]  # no metadata, real tenant
        gated = export_mod.gate_shared_rows(rows, consent={})  # absent → 'none'
        assert gated == []  # default-deny

    def test_real_private_excluded_from_shared(self):
        rows = [_row("F001")]
        gated = export_mod.gate_shared_rows(rows, consent={"F001": "private"})
        assert gated == []  # private does NOT go into shared export

    def test_real_shared_anon_included_and_anonymized(self):
        rows = [_row("F001",
                     input_text="星巴克门店营收¥2500000，占比35%",
                     teacher_output="星巴克门店环比增长18%")]
        gated = export_mod.gate_shared_rows(rows, consent={"F001": "shared_anon"})
        assert len(gated) == 1
        item = gated[0]
        assert item["consent_class"] == "shared_anon"
        # anonymized text present (not None) + transformed
        assert item["input_text"] is not None
        assert "星巴克" not in item["input_text"]
        assert "2500000" not in item["input_text"]
        assert "35%" in item["input_text"]   # shape kept
        assert "18%" in item["teacher_output"]

    def test_mixed_population(self):
        rows = [
            _row("SEED_R"),                                    # synthetic → in
            _row("F001", metadata={"seeded": True}),           # synthetic → in
            _row("F002"),                                      # none → out
            _row("F003"),                                      # private → out
            _row("F004"),                                      # shared_anon → in
        ]
        consent = {"F002": "none", "F003": "private", "F004": "shared_anon"}
        gated = export_mod.gate_shared_rows(rows, consent)
        classes = sorted(g["consent_class"] for g in gated)
        assert classes == ["shared_anon", "synthetic", "synthetic"]
        assert len(gated) == 3


# ===========================================================================
# 3. Private export mode: consented private → raw; non-consented → refuse
# ===========================================================================

class _FakeConn:
    def __init__(self, consent_rows, sample_rows):
        self._consent = consent_rows
        self._samples = sample_rows

    async def fetch(self, sql, *args):
        if "training_consent" in sql:
            return self._consent
        return self._samples

    async def __aenter__(self):
        return self

    async def __aexit__(self, *a):
        return False


class _FakePool:
    def __init__(self, conn):
        self._conn = conn

    def acquire(self):
        return self._conn


class _Args:
    def __init__(self, **kw):
        self.out = kw.get("out", os.path.join(TESTS_DIR, "_tmp_consent_out"))
        self.source = None
        self.business_type = None
        self.min_quality = None
        self.factory = kw["factory"]


@pytest.mark.asyncio
async def test_private_export_refused_without_consent(tmp_path, caplog):
    conn = _FakeConn(consent_rows=[], sample_rows=[])  # no consent for F001
    pool = _FakePool(conn)
    args = _Args(factory="F001", out=str(tmp_path))
    import logging
    with caplog.at_level(logging.ERROR):
        await export_mod._export_private(args, pool)
    assert any("REFUSED" in r.message for r in caplog.records)
    # no file written
    assert not os.path.exists(os.path.join(str(tmp_path), "dataset_private_F001.jsonl"))


@pytest.mark.asyncio
async def test_private_export_raw_when_consented(tmp_path):
    consent = [{"factory_id": "F001", "consent_scope": "private"}]
    samples = [_row("F001", input_text="星巴克门店营收¥2500000",
                    teacher_output="保持增长")]
    conn = _FakeConn(consent_rows=consent, sample_rows=samples)
    pool = _FakePool(conn)
    args = _Args(factory="F001", out=str(tmp_path))
    await export_mod._export_private(args, pool)
    path = os.path.join(str(tmp_path), "dataset_private_F001.jsonl")
    assert os.path.exists(path)
    content = open(path, encoding="utf-8").read()
    # RAW — tenant's exact values preserved (NOT anonymized) for private model
    assert "星巴克" in content
    assert "2500000" in content
    assert '"consent_class": "private"' in content


@pytest.mark.asyncio
async def test_private_export_refused_when_shared_anon_only(tmp_path, caplog):
    # shared_anon is NOT private → private raw export must refuse
    consent = [{"factory_id": "F001", "consent_scope": "shared_anon"}]
    conn = _FakeConn(consent_rows=consent, sample_rows=[_row("F001")])
    pool = _FakePool(conn)
    args = _Args(factory="F001", out=str(tmp_path))
    import logging
    with caplog.at_level(logging.ERROR):
        await export_mod._export_private(args, pool)
    assert any("REFUSED" in r.message for r in caplog.records)
    assert not os.path.exists(os.path.join(str(tmp_path), "dataset_private_F001.jsonl"))


# ===========================================================================
# 4. Migration: version > frontier + table/policy present
# ===========================================================================

class TestMigration:
    MIGRATIONS_DIR = os.path.join(PYTHON_ROOT, "smartbi", "database", "migrations")

    def _frontier(self):
        versions = []
        for fn in os.listdir(self.MIGRATIONS_DIR):
            m = re.match(r"V(\d{8})_(\d{2})__", fn)
            if m:
                versions.append((m.group(1), m.group(2)))
        return max(versions)

    def test_consent_migration_above_frontier(self):
        target = None
        for fn in os.listdir(self.MIGRATIONS_DIR):
            if "training_consent" in fn:
                target = fn
        assert target is not None, "training_consent migration not found"
        m = re.match(r"V(\d{8})_(\d{2})__", target)
        assert m
        this_ver = (m.group(1), m.group(2))
        # strictly greater than any OTHER migration
        others = []
        for fn in os.listdir(self.MIGRATIONS_DIR):
            if fn == target:
                continue
            mm = re.match(r"V(\d{8})_(\d{2})__", fn)
            if mm:
                others.append((mm.group(1), mm.group(2)))
        assert this_ver > max(others), f"{this_ver} must be > frontier {max(others)}"

    def test_migration_defines_table_and_policy(self):
        target = None
        for fn in os.listdir(self.MIGRATIONS_DIR):
            if "training_consent" in fn:
                target = os.path.join(self.MIGRATIONS_DIR, fn)
        sql = open(target, encoding="utf-8").read()
        assert "CREATE TABLE IF NOT EXISTS training_consent" in sql
        assert "consent_scope" in sql
        assert "DEFAULT 'none'" in sql  # default-deny baked into schema
        assert "'private'" in sql and "'shared_anon'" in sql
        assert "GRANT" in sql and "smartbi_user" in sql


# ===========================================================================
# 5. Scripts parse OK
# ===========================================================================

class TestParse:
    def test_all_scripts_parse(self):
        for rel in (
            os.path.join("scripts", "export_distillation_dataset.py"),
            os.path.join("smartbi", "services", "corpus_anonymize.py"),
            os.path.join("scripts", "set_training_consent.py"),
        ):
            p = os.path.join(PYTHON_ROOT, rel)
            ast.parse(open(p, encoding="utf-8").read())
