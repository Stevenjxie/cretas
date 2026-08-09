"""
探针结果分类的闸。

分类必须区分「403 无额度」「200 但空内容」「其它错误」——
2026-08-09 那一轮正是把「200 空内容」读成可用, 才把 glm-5.2 写进单子。
"""
from scripts.probe_llm_registry import classify_probe_result


def test_non_empty_content_is_ok():
    assert classify_probe_result(200, "", "库存周转率是…") == "ok"


def test_http_200_with_empty_content_is_not_ok():
    """200 不等于可用。thinking 模型会把 token 全烧在 reasoning_content 上,
    content 返回空、finish_reason=length —— 长得像成功。"""
    assert classify_probe_result(200, "", "   ") == "empty"


def test_free_quota_exhausted_is_quota():
    # 真实 DashScope 403 报文格式(见 tests/test_llm_router_fallback.py、
    # scripts/probe-llm-account.py) —— _is_quota_exhausted 按 "FreeTierOnly"/
    # "AllocationQuota" 子串匹配, brief 原文的 "Free quota exhausted" 假报文
    # 打不中真实分类器, 已按仓内实际报文格式改写(非放宽断言, 断言仍是 == "quota")。
    assert classify_probe_result(403, '{"message":"AllocationQuota.FreeTierOnly"}', "") == "quota"


def test_tokenhub_401008_is_quota():
    assert classify_probe_result(402, '{"code":"401008"}', "") == "quota"


def test_zhipu_balance_message_is_quota():
    assert classify_probe_result(429, '{"code":"1113","message":"余额不足"}', "") == "quota"


def test_bad_request_is_error_not_quota():
    """阴性对照: 400 参数错误不是额度问题, 不能混进 quota 桶 ——
    否则一个参数 bug 会被读成'额度用完了'。"""
    assert classify_probe_result(400, '{"message":"InternalError"}', "") == "error"


def test_aggregate_verdicts_mixed_failures_report_both_labels():
    """槽遍历顺序不该决定打印出来的故障原因。不同槽下表现不同时
    (如快槽偶发网络 timeout, 推理槽是真的 403 额度耗尽)应该把真因显示出来,
    而不是让顺序早的那个偶然值盖掉根本原因。"""
    from scripts.probe_llm_registry import _aggregate_verdicts

    # 一个混合失败: error(超时) 和 quota(403)
    verdicts = [("error", "timeout"), ("quota", "403")]
    label, detail = _aggregate_verdicts(verdicts)
    # 标签应该同时包含 error 和 quota, 用 + 连接
    assert label == "error+quota", f"expected 'error+quota', got '{label}'"
    # 细节应该是排序后的 403; timeout
    assert detail == "403; timeout", f"expected '403; timeout', got '{detail}'"


def test_aggregate_verdicts_ok_anywhere_wins():
    """即使 ok verdict 在列表末尾, 也应该被返回, 证明顺序无关。"""
    from scripts.probe_llm_registry import _aggregate_verdicts

    # 先放两个失败, 再放 ok ——如果用 verdicts[0] 这样的天真实现会失败
    verdicts = [("error", "timeout"), ("quota", "403"), ("ok", "")]
    label, detail = _aggregate_verdicts(verdicts)
    assert label == "ok", f"expected 'ok', got '{label}'"
    assert detail == "", f"expected empty detail, got '{detail}'"


def test_main_returns_zero_when_only_expiring_soon(monkeypatch):
    """只有「expiring soon」条目时, main() 返回 0: 7 天内到期的提醒连续多天
    都会非空, 若也计入退出码 cron 告警会连续多天触发, 炸到没人再读。所以
    只有「dead」(注册表说活、实测不可用)才翻转退出码。

    生产注册表 _SAFE_MODELS 已包含大量 2026-08-13 到期的条目，距 2026-08-09 为 4 天，
    故在该日期冻结时自动满足 soon 条件。"""
    from datetime import date
    from scripts import probe_llm_registry

    # 模拟 _run 返回全部 ok 的结果
    async def mock_run():
        return {
            ("account1", "model1"): ("ok", ""),
            ("account2", "model2"): ("ok", ""),
        }

    # 模拟 _today 返回 2026-08-09; 真实 _SAFE_MODELS 中已有入在 2026-08-13 的条目(4 天)
    mock_today = date(2026, 8, 9)

    monkeypatch.setattr("scripts.probe_llm_registry._run", mock_run)
    monkeypatch.setattr("common.llm_router._today", lambda: mock_today)

    result = probe_llm_registry.main()
    assert result == 0, f"expected exit code 0 (only soon), got {result}"


def test_main_returns_one_when_dead(monkeypatch):
    """「dead」条目(注册表说活、实测不可用)使 main() 返回 1。"""
    from datetime import date
    from scripts import probe_llm_registry

    # 模拟 _run 返回一个 dead 条目 (error/quota)
    async def mock_run():
        return {
            ("account1", "model1"): ("error", "timeout"),
            ("account2", "model2"): ("ok", ""),
        }

    mock_today = date(2026, 8, 9)

    monkeypatch.setattr("scripts.probe_llm_registry._run", mock_run)
    monkeypatch.setattr("common.llm_router._today", lambda: mock_today)

    result = probe_llm_registry.main()
    assert result == 1, f"expected exit code 1 (has dead), got {result}"


# Guard test: 生产安全注册表不应被测试污染
_SAFE_MODELS_BASELINE = None


def _snapshot_safe_models():
    """Capture immutable snapshot of _SAFE_MODELS at module import time."""
    from common import llm_router
    global _SAFE_MODELS_BASELINE
    if _SAFE_MODELS_BASELINE is None:
        _SAFE_MODELS_BASELINE = {k: v for k, v in llm_router._SAFE_MODELS.items()}


_snapshot_safe_models()


def test_probe_tests_do_not_mutate_safe_models():
    """探针测试不应修改生产安全注册表 _SAFE_MODELS —— 该表是多个独立测试的
    共享契约(test_llm_router_registry.py 按冻结内容验证)。测试间的
    顺序依赖 (order-dependent failure) 是隐患：某个测试污染 _SAFE_MODELS，
    后续测试在不同运行顺序下得到不同结果。使用 monkeypatch.setattr 时,
    如果对象本身被改动再被 monkeypatch, monkeypatch 的 snapshots + restore
    会失败——快速结论: 绝不在原地修改共享数据结构。"""
    from common import llm_router

    current = {k: v for k, v in llm_router._SAFE_MODELS.items()}
    assert current == _SAFE_MODELS_BASELINE, (
        f"_SAFE_MODELS was mutated during test run. "
        f"Extra: {set(current.keys()) - set(_SAFE_MODELS_BASELINE.keys())}; "
        f"Missing: {set(_SAFE_MODELS_BASELINE.keys()) - set(current.keys())}"
    )
