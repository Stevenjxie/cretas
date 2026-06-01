from smartbi.services.field_promotion import (
    is_promotable, consult_promoted, _load_promoted,
)


def _cand(col="本月实际", std="actual_amount", conf=0.95, factories=("F1", "F2"), occ=4):
    return {"column_name": col, "standard_field": std, "max_confidence": conf,
            "factory_count": len(set(factories)), "occurrences": occ}


def test_promotable_high_conf_multi_factory():
    ok, reason = is_promotable(_cand(), curated={}, promoted={})
    assert ok is True


def test_reject_low_confidence():
    ok, reason = is_promotable(_cand(conf=0.85), curated={}, promoted={})
    assert ok is False and "置信" in reason


def test_reject_single_factory():
    ok, reason = is_promotable(_cand(factories=("F1",)), curated={}, promoted={})
    assert ok is False and "工厂" in reason


def test_reject_conflict_with_curated():
    ok, reason = is_promotable(_cand(), curated={"本月实际": "budget_amount"}, promoted={})
    assert ok is False and "冲突" in reason


def test_already_promoted_is_not_a_candidate():
    ok, reason = is_promotable(_cand(), curated={}, promoted={"本月实际": "actual_amount"})
    assert ok is False and "已毕业" in reason


def test_consult_hit(monkeypatch):
    monkeypatch.setattr("smartbi.services.field_promotion._PROMOTED",
                        {"本月实际": "actual_amount"})
    assert consult_promoted("本月实际") == "actual_amount"


def test_consult_miss(monkeypatch):
    monkeypatch.setattr("smartbi.services.field_promotion._PROMOTED", {})
    assert consult_promoted("未知列") is None


def test_load_promoted_bad_file_is_empty(tmp_path, monkeypatch):
    bad = tmp_path / "x.json"
    bad.write_text("not json", encoding="utf-8")
    monkeypatch.setattr("smartbi.services.field_promotion.PROMOTED_FILE", bad)
    assert _load_promoted() == {}
