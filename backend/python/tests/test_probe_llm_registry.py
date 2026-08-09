"""
探针结果分类的闸。

分类必须区分「403 无额度」「200 但空内容」「其它错误」——
2026-08-09 那一轮正是把「200 空内容」读成可用, 才把 glm-5.2 写进单子。
"""
import pytest
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


def test_main_returns_zero_when_only_expiring_soon(monkeypatch, capsys):
    """只有「expiring soon」条目时, main() 返回 0: 7 天内到期的提醒连续多天
    都会非空, 若也计入退出码 cron 告警会连续多天触发, 炸到没人再读。所以
    只有「dead」(注册表说活、实测不可用)才翻转退出码。

    防止衰变: 不借用生产注册表中的真实条目(会因例行维护而消失), 而是注入
    一个新鲜的测试用条目, 并验证 soon 输出确实包含它。这样一旦 soon 变空,
    测试就会红, 而不是继续绿却无法区分「soon 为空」和「soon 非空但被正确忽略」。
    """
    from datetime import date
    from common import llm_router
    from scripts import probe_llm_registry

    # 模拟 _run 返回全部 ok 的结果
    async def mock_run():
        return {
            ("account1", "model1"): ("ok", ""),
            ("account2", "model2"): ("ok", ""),
        }

    mock_today = date(2026, 8, 9)
    mock_registry = {("test_acct", "test_model"): date(2026, 8, 14)}  # 5 天后

    monkeypatch.setattr("scripts.probe_llm_registry._run", mock_run)
    monkeypatch.setattr("common.llm_router._today", lambda: mock_today)
    # 注意: 这里用 monkeypatch.setattr 替换对象引用, 不是原地修改;
    # autouse 夹具在 teardown 时恢复原始绑定, 故不会污染全局状态。
    monkeypatch.setattr(llm_router, "_SAFE_MODELS", mock_registry)

    result = probe_llm_registry.main()
    assert result == 0, f"expected exit code 0 (only soon), got {result}"

    # 验证输出确实包含 soon 条目 (不只是假设 soon 非空)
    captured = capsys.readouterr()
    assert "7 天内到期" in captured.out, "expected '7 天内到期' section in output"
    assert "test_acct/test_model" in captured.out, "expected injected entry in soon output"
    assert "2026-08-14" in captured.out, "expected expiry date in soon output"


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


@pytest.fixture(autouse=True)
def _assert_safe_models_untouched():
    """每个测试前后验证 _SAFE_MODELS 未被修改。

    生产安全注册表不应被测试污染 —— 该表是多个独立测试的共享契约
    (test_llm_router_registry.py 按冻结内容验证)。测试间的顺序依赖
    (order-dependent failure) 是隐患：某个测试污染 _SAFE_MODELS，
    后续测试在不同运行顺序下得到不同结果。

    此夹具在**每个**测试前后运行，自动捕获 before/after 状态。即使新测试
    添加在文件末尾，也会被检测到。测试失败时直接指名是哪个测试改动了注册表。
    """
    from common import llm_router

    before = dict(llm_router._SAFE_MODELS)
    yield
    after = dict(llm_router._SAFE_MODELS)
    assert after == before, (
        f"_SAFE_MODELS was mutated during test. "
        f"Extra: {set(after.keys()) - set(before.keys())}; "
        f"Missing: {set(before.keys()) - set(after.keys())}"
    )
