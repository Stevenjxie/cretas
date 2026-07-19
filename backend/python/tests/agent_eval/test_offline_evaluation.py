import re
from pathlib import Path

from smartbi.agent.eval.runner import EVALUATOR_BUILD, compute_evaluator_build
from smartbi.agent.runtime import evaluation as runtime_evaluation
from smartbi.agent.runtime.evaluation import (
    OfflineCaseExpectation,
    OfflineCaseSnapshot,
    evaluate_offline_case,
)


def test_evaluator_build_is_the_lowercase_source_artifact_sha256_and_drifts():
    artifacts = {
        "runner.py": Path(__file__).parents[2].joinpath(
            "smartbi/agent/eval/runner.py"
        ).read_bytes(),
        "runtime/evaluation.py": Path(runtime_evaluation.__file__).read_bytes(),
    }
    assert EVALUATOR_BUILD == compute_evaluator_build(artifacts)
    assert re.fullmatch(r"[0-9a-f]{64}", EVALUATOR_BUILD)
    changed = {**artifacts, "runner.py": artifacts["runner.py"] + b"\n# drift"}
    assert compute_evaluator_build(changed) != EVALUATOR_BUILD


def expectation() -> OfflineCaseExpectation:
    return OfflineCaseExpectation(
        route_code="GROSS_MARGIN_DECLINE_ATTRIBUTION",
        required_tools_in_order=("margin", "cost"),
        numeric_truth_refs={"e1:f1": "10.00"},
        max_rounds=2,
        max_tool_calls=4,
    )


def test_route_trajectory_and_numeric_truth_all_pass():
    result = evaluate_offline_case(
        OfflineCaseSnapshot(
            "GROSS_MARGIN_DECLINE_ATTRIBUTION",
            ("margin", "other", "cost"),
            {"e1:f1": "10"},
            2,
            3,
        ),
        expectation(),
    )
    assert result.passed
    assert (result.route_ok, result.trajectory_ok, result.numeric_truth_ok) == (True, True, True)


def test_each_evaluation_axis_reports_a_controlled_failure():
    result = evaluate_offline_case(
        OfflineCaseSnapshot("OTHER_ROUTE", ("cost",), {"e1:f1": "9"}, 2, 1),
        expectation(),
    )
    assert result.failures == (
        "ROUTE_MISMATCH",
        "TRAJECTORY_MISMATCH",
        "NUMERIC_TRUTH_FAILED",
    )


def test_missing_numeric_reference_is_not_treated_as_zero():
    result = evaluate_offline_case(
        OfflineCaseSnapshot("GROSS_MARGIN_DECLINE_ATTRIBUTION", ("margin", "cost"), {}, 1, 2),
        expectation(),
    )
    assert not result.numeric_truth_ok
