"""卡死检测: 把「瞬时故障」和「永久卡死」分开。

## 为什么需要

connector 的失败语义是**宁可卡住也不漏**: 一条记录写失败 → 整页回滚 →
`write_cursor` 被跳过 → 游标不动 → 下轮重拉同一页。一条**永久性**坏记录会让
那一类数据**永远停在那一页**, 后续数据全部再也进不来。

问题不在这个取舍本身, 而在于**它是隐性的**: `sync_all` 现在只打一行
`logger.error("[platform-sync] %s", exc)` —— 网络抖一下和数据永久卡死打出来的
东西**一模一样**。真卡了没人会知道: 游标静默停住、数据悄悄断流, 而唯一的补救
(手工改游标行)的前提是先有人发现。

所以在动「隔离后继续」那套整完整性语义之前, 先让卡死**可见**:
同一游标连续失败到阈值 → 打一条带稳定标记的 ERROR, 含卡住的游标值、连续轮次、
持续时长。稳定标记是为了能直接挂告警/grep。

这也是死信表那一步的前提 —— 真出坏记录时得先有证据知道它长什么样, 才能确定
隔离逻辑对不对, 而不是照着假想的失败模式写。

## 判据

- 游标**变了**就不算卡死(数据在推进, 那只是某轮抖动)
- 同一游标连续失败 >= 阈值才升级, 低于阈值不刷屏
- 成功一次就清零 —— 卡死解除必须能被观测到
"""
from __future__ import annotations

import pytest

from smartbi.ingestion.platforms import framework as F


@pytest.fixture(autouse=True)
def _clear_tracker():
    F.reset_jam_tracker()
    yield
    F.reset_jam_tracker()


def _fail(cursor, key="MOCK_REST/keruyun"):
    """记一次失败, 返回是否应当升级为卡死告警。"""
    return F.record_sync_failure(key, cursor)


def test_单次失败不算卡死():
    """网络抖一下不该告警, 否则告警会被噪声淹没。"""
    assert _fail("c1") is False


def test_同一游标连续失败到阈值才升级():
    for _ in range(F.JAM_ALERT_THRESHOLD - 1):
        assert _fail("c1") is False
    assert _fail("c1") is True, (
        f"同一游标连续失败 {F.JAM_ALERT_THRESHOLD} 轮仍未升级 —— 卡死不可见"
    )


def test_游标推进过就不算卡死():
    """每轮游标都在变 = 数据在流, 只是偶发失败。"""
    for i in range(F.JAM_ALERT_THRESHOLD * 2):
        assert _fail(f"c{i}") is False


def test_成功一次清零():
    for _ in range(F.JAM_ALERT_THRESHOLD - 1):
        _fail("c1")
    F.record_sync_success("MOCK_REST/keruyun")
    assert _fail("c1") is False, "成功后计数没清零, 会拿旧账凑出假告警"


def test_不同游标键各记各的():
    """领料卡住不该让订单也被算成卡死(反之亦然)。"""
    for _ in range(F.JAM_ALERT_THRESHOLD):
        _fail("c1", key="MOCK_REST/keruyun")
    assert _fail("c1", key="MOCK_REST/keruyun:wastage") is False


def test_升级后持续失败仍然告警():
    """卡死没解除就该一直可见, 不能只报一次然后沉默。"""
    for _ in range(F.JAM_ALERT_THRESHOLD):
        _fail("c1")
    assert _fail("c1") is True


def test_卡死状态可被运维直接读取():
    """探针: 不必翻日志就能回答「现在有没有卡住的、卡了多久」。"""
    for _ in range(F.JAM_ALERT_THRESHOLD):
        _fail("c1")
    state = F.jam_state()
    assert "MOCK_REST/keruyun" in state
    entry = state["MOCK_REST/keruyun"]
    assert entry["cursor"] == "c1"
    assert entry["consecutive_failures"] >= F.JAM_ALERT_THRESHOLD
    assert "stuck_seconds" in entry


@pytest.mark.asyncio
async def test_sync_all_失败时把游标记进卡死跟踪(monkeypatch):
    """端到端: 真走 sync_all 的失败路径, 卡死跟踪必须被喂到。

    只断言「失败被记录且游标带上了」—— 不复制 sync_all 的内部结构。
    """
    class _Adapter:
        platform = "keruyun"

        async def fetch_page(self, cursor, limit):
            raise RuntimeError("平台超时")

    monkeypatch.setattr(F, "read_cursor", _async_return("c-stuck"))
    results = await F.sync_all(None, [_Adapter()], factory_id="MOCK_REST",
                               write_orders=None)
    assert "ERROR" in str(results["keruyun"])
    state = F.jam_state()
    assert state, "sync_all 失败了却没进卡死跟踪 —— 卡死仍然不可见"
    entry = next(iter(state.values()))
    assert entry["cursor"] == "c-stuck"


@pytest.mark.asyncio
async def test_ops三类各自独立记卡死(monkeypatch):
    """领料/损耗/盘点各走各的游标键 —— 一类卡住不该把另一类也算成卡死。

    ops 侧最容易藏卡死: 三条游标独立推进, 其中一条停了从总量上看不出来。
    """
    class _Page:
        def __init__(self, cursor):
            self.items, self.next_cursor, self.has_more = [], cursor, False

    class _OpsAdapter:
        platform = "keruyun"

        async def fetch_page(self, kind, cursor, limit):
            if kind == "wastage":
                raise RuntimeError("损耗接口挂了")
            return _Page(cursor)

    monkeypatch.setattr(F, "read_cursor", _async_return("c-ops"))
    monkeypatch.setattr(F, "write_cursor", _async_return(None))
    await F.sync_ops_all(None, _OpsAdapter(), factory_id="MOCK_REST", write_ops=None)

    state = F.jam_state()
    stuck = [k for k in state if "wastage" in k]
    assert stuck, f"损耗卡住没被记录; state={state}"
    assert not [k for k in state if "requisition" in k], (
        "领料是成功的却被算进卡死 —— 游标键没分开"
    )


def _async_return(value):
    async def _f(*a, **kw):
        return value
    return _f
