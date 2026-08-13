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


def test_expired_entries_do_not_flip_exit_code(monkeypatch):
    """到期已过的条目探针失败是**预期内**的(同一时刻 `_refuse_reason` 已经把
    它硬拒出链外了), 不能计入退出码 —— 否则 2026-08-13 那批 14 条一次性到期
    后, main() 会从那天起**每天**返回 1, 直到有人手工把它们从 _SAFE_MODELS
    删掉, 正是 spec §5.5 明确要防的告警疲劳(与飞轮日报静默坏 5 天是同一种
    "天天炸=没人看"死法)。"""
    from datetime import date
    from common import llm_router
    from scripts import probe_llm_registry

    async def mock_run():
        return {("expired_acct", "expired_model"): ("quota", "403")}

    mock_today = date(2026, 8, 20)
    mock_registry = {("expired_acct", "expired_model"): date(2026, 8, 13)}  # 7 天前已过期

    monkeypatch.setattr("scripts.probe_llm_registry._run", mock_run)
    monkeypatch.setattr("common.llm_router._today", lambda: mock_today)
    monkeypatch.setattr(llm_router, "_SAFE_MODELS", mock_registry)

    result = probe_llm_registry.main()
    assert result == 0, f"expected exit 0 (only expired-by-design failure), got {result}"


def test_expired_entries_are_reported_separately_not_as_dead(monkeypatch, capsys):
    """已过期的失败条目要落进「已过期, 待清理」, 不能出现在「注册表说活、实测
    不可用」那节 —— 否则运维读到 dead 非空会当成真实漂移去排查, 排查了个寂寞。"""
    from datetime import date
    from common import llm_router
    from scripts import probe_llm_registry

    async def mock_run():
        return {("expired_acct", "expired_model"): ("quota", "403")}

    mock_today = date(2026, 8, 20)
    mock_registry = {("expired_acct", "expired_model"): date(2026, 8, 13)}

    monkeypatch.setattr("scripts.probe_llm_registry._run", mock_run)
    monkeypatch.setattr("common.llm_router._today", lambda: mock_today)
    monkeypatch.setattr(llm_router, "_SAFE_MODELS", mock_registry)

    probe_llm_registry.main()
    captured = capsys.readouterr()
    assert "已过期, 待清理" in captured.out
    dead_section, _, rest = captured.out.partition("已过期, 待清理")
    assert "expired_acct/expired_model" not in dead_section
    assert "expired_acct/expired_model" in rest


def test_already_expired_entry_does_not_appear_under_expiring_soon(monkeypatch, capsys):
    """原判据 `(expiry - today).days <= 7` 对负数同样为真, 已经过期的条目会在
    「7 天内到期」下永久出现, 跟「已过期, 待清理」表达的是同一件事却混进了
    "即将"的语气。修复后 soon 必须严格未来(0 < delta <= 7)。"""
    from datetime import date
    from common import llm_router
    from scripts import probe_llm_registry

    async def mock_run():
        return {}

    mock_today = date(2026, 8, 20)
    mock_registry = {("stale_acct", "stale_model"): date(2026, 8, 13)}  # 7 天前已过期

    monkeypatch.setattr("scripts.probe_llm_registry._run", mock_run)
    monkeypatch.setattr("common.llm_router._today", lambda: mock_today)
    monkeypatch.setattr(llm_router, "_SAFE_MODELS", mock_registry)

    result = probe_llm_registry.main()
    captured = capsys.readouterr()
    assert result == 0
    _, _, soon_section = captured.out.partition("7 天内到期")
    assert "stale_acct/stale_model" not in soon_section


def test_prompt_for_uses_json_prompt_only_for_json_slots():
    """CHART/MAPPER 的 profile 带 json=True: 生产会给
    response_format={"type":"json_object"}, 但那个分支只有在 prompt 里出现
    "json" 字样时才会打开(`_payload_mentions_json`, DashScope 硬性要求)。
    探针的 prompt 必须按槽区分, 否则 CHART/MAPPER 探的从来不是生产真正发的
    请求形状。"""
    from common import llm_router
    from scripts.probe_llm_registry import _prompt_for

    assert "json" in _prompt_for(llm_router.SLOT.CHART).lower()
    assert "json" in _prompt_for(llm_router.SLOT.MAPPER).lower()
    assert "json" not in _prompt_for(llm_router.SLOT.CHAT).lower()
    assert "json" not in _prompt_for(llm_router.SLOT.REVIEW).lower()


def test_json_prompt_actually_triggers_response_format_json_object():
    """端到端确认, 不只验证 prompt 含关键词: 把 `_prompt_for` 的输出真的喂给
    `_apply_slot_params`, 必须产出 response_format=json_object —— 万一
    `_payload_mentions_json` 的匹配逻辑跟这里的措辞对不上, 光验 prompt 仍然
    是假绿。"""
    from common import llm_router
    from scripts.probe_llm_registry import _prompt_for

    payload = llm_router._apply_slot_params(
        llm_router.SLOT.CHART, "aliyun_c", "some-model",
        {
            "model": "some-model",
            "messages": [{"role": "user", "content": _prompt_for(llm_router.SLOT.CHART)}],
            "max_tokens": 200,
        },
    )
    assert payload.get("response_format") == {"type": "json_object"}


def test_slots_for_text_tail_member_covers_every_slot_that_appends_it():
    """_TEXT_TAIL 成员(如 tencent/minimax-m2.7)是**每一个非 VL 槽**共用的地板
    (_build_chain 逐槽追加), 但它们不出现在任何 _SLOT_POOLS 里 —— 旧版
    `_slots_for` 只看 _SLOT_POOLS, 于是这类条目只会被探成 REVIEW 一档, 探不到
    它在 CHART/MAPPER 下才会触发的 json_object + `_TOKENHUB_MIN_MAX_TOKENS`
    地板交互(_apply_slot_params 里 json 分支先弹出 max_tokens 又强制补回
    1600, 只在这两个槽发生)。"""
    from common import llm_router
    from scripts.probe_llm_registry import _slots_for

    pair = ("tencent", "minimax-m2.7")
    assert pair in llm_router._TEXT_TAIL  # 前提: 它确实是地板成员
    slots = set(_slots_for(pair))
    expected = {s for s in llm_router.SLOT if s not in llm_router._NO_TEXT_TAIL_SLOTS}
    assert slots == expected
    assert llm_router.SLOT.VL not in slots


def test_slots_for_pool_only_member_is_unaffected():
    """非地板成员(只在某个池里出现)行为不变 —— 不能因为这次改动被顺带塞进
    所有槽。"""
    from common import llm_router
    from scripts.probe_llm_registry import _slots_for

    # 2026-08-13: 原来用的 ("aliyun_c", "MiniMax-M2.5") 当天实测 403 并已退出
    # 注册表, 于是 _slots_for 返回空、断言拿到的是别的槽。换成当天仍然只在
    # REASONING 池里的一条(它是 _THINKING_ONLY, 进不了任何关思考的槽)。
    # 📌 与夹具模型名同源的问题: **测试里写死的 (账号,模型) 也是会过期的数据**,
    #    它挑的是"当时恰好只属于一个槽"的那一条, 而"属于哪些槽"每轮都在变。
    pair = ("aliyun_b", "kimi-k2.7-code")
    assert pair not in llm_router._TEXT_TAIL
    assert _slots_for(pair) == [llm_router.SLOT.REASONING]


def test_probe_normalizes_before_applying_slot_params(monkeypatch):
    """`_probe` 必须按生产的顺序调用两层: normalize → apply_slot_params(见
    llm_router.call_chain 的 req_payload 构造)。用调用顺序断言而不是只断言
    "都调用过" —— 顺序接反了在今天(normalize 是纯 passthrough)不会产生任何
    可观察的 payload 差异, 只有顺序断言能抓住"接反了"这件事本身。"""
    import asyncio

    from common import llm_router
    from scripts import probe_llm_registry

    call_order = []
    real_normalize = llm_router._normalize_payload_for_provider
    real_apply = llm_router._apply_slot_params

    def spy_normalize(payload, account):
        call_order.append("normalize")
        return real_normalize(payload, account)

    def spy_apply(slot, account, model, payload):
        call_order.append("apply")
        return real_apply(slot, account, model, payload)

    monkeypatch.setattr(probe_llm_registry.r, "_normalize_payload_for_provider", spy_normalize)
    monkeypatch.setattr(probe_llm_registry.r, "_apply_slot_params", spy_apply)

    class _FakeResponse:
        status_code = 200
        text = '{"choices":[{"message":{"content":"ok"}}]}'

    class _FakeClient:
        async def post(self, *args, **kwargs):
            return _FakeResponse()

    asyncio.run(probe_llm_registry._probe(
        _FakeClient(), "aliyun_c", "some-model", llm_router.SLOT.CHAT,
    ))
    assert call_order == ["normalize", "apply"]


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
