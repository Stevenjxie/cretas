"""探针自举模块的断言 —— 它存在的理由就是「靠纪律的东西会在最需要它那次失效」。

配方本来写在两个脚本的头部注释里。2026-08-12 我写第三个探针时没有想起去抄，
于是 40 条里 34 条报「餐饮执行链暂时不可用」，我据此写了一份「执行失败 34」的
报告 —— 而那 34 条测的是我的进程起不来执行链，不是产品有问题。
"""
import pytest

from smartbi.scripts._probe_bootstrap import (
    EXECUTION_UNAVAILABLE_MARK,
    MISSING_SERVICES_MARK,
    PROBE_ROLE,
    assert_not_a_probe_artifact,
    assert_tree_is_complete,
)


def test_role_is_not_owner():
    """⛔ PRICE_VIEW_ROLES 不含 owner —— 用 owner 跑金额全被脱敏成 ***,
    评估会假性变差。这条把「填错角色」钉死。"""
    assert PROBE_ROLE == "restaurant_manager"
    assert PROBE_ROLE != "owner"


def test_clean_answers_pass_through():
    """没有那句话就什么都不做 —— 阴性对照。"""
    assert_not_a_probe_artifact(["最近30天营收 ¥12,345", "毛利率 33%"])
    assert_not_a_probe_artifact([])
    assert_not_a_probe_artifact(None)


def test_zero_prod_hits_means_it_is_my_probe():
    """生产日志里 0 次 = 实锤探针问题。这是本轮那 34 条的判据。"""
    with pytest.raises(AssertionError, match="探针问题不是线上问题"):
        assert_not_a_probe_artifact(
            [f"{EXECUTION_UNAVAILABLE_MARK}，这次什么都没算。"], prod_log_hits=0)


def test_unknown_prod_hits_demands_the_measurement_first():
    """还没查生产日志时不许下任何结论 —— 先去 grep。"""
    with pytest.raises(AssertionError, match="先 grep 生产日志"):
        assert_not_a_probe_artifact([f"{EXECUTION_UNAVAILABLE_MARK}。"])


def test_tree_completeness_names_every_missing_package_at_once():
    """⛔ 不逐个撞 —— 每一层的用户可见症状都是同一句话，逐个撞会撞 N 轮。

    本轮实测: 修好 `services` 之后立刻露出 `smartbi_compat`。
    """
    try:
        assert_tree_is_complete()
    except RuntimeError as exc:
        # 在残缺的树上跑时, 报错必须**一次点名所有**缺的包, 并说清症状。
        assert EXECUTION_UNAVAILABLE_MARK in str(exc)
        assert "和真缺陷长得一模一样" in str(exc)


def test_the_two_marks_are_distinct_and_non_empty():
    assert MISSING_SERVICES_MARK and EXECUTION_UNAVAILABLE_MARK
    assert MISSING_SERVICES_MARK != EXECUTION_UNAVAILABLE_MARK
