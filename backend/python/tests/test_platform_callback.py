"""回调端点三层校验: IP 白名单 / HMAC 验签 / 时间窗+nonce 防重放。

⚠️ 这个端点路径里没有 factoryId。本仓 2026-07-29 出过匿名访问事故 ——
登录校验挂在「URL 能否解析出 factoryId」上, 导致 /ai/* /upload/* 整类路径
对公网无鉴权。回调端点必须独立鉴权, 绝不重蹈。
"""
import time

import pytest

from smartbi.api.platform_callback import (
    CallbackRejected, check_replay, prune_nonces, verify_signature,
)

SECRET = "test-secret"


def _sig(body: bytes, ts: str, nonce: str, secret: str = SECRET) -> str:
    import hashlib
    import hmac
    payload = ts.encode("ascii") + nonce.encode("ascii") + body
    return hmac.new(secret.encode(), payload, hashlib.sha256).hexdigest().lower()


def test_验签算法与模拟端一致(monkeypatch):
    # syspath_prepend 而非裸 sys.path.insert: 后者会永久污染整个 pytest 会话的
    # 模块搜索路径, mock-platform/ 将来多一个 main.py/config.py 就会盖掉后端同名模块。
    import pathlib
    monkeypatch.syspath_prepend(
        str(pathlib.Path(__file__).resolve().parents[3] / "mock-platform"))
    from mock_platform.callback import build_signature

    body, ts, nonce = b'{"maxSeq":9}', "1785300000", "abc"
    assert build_signature(body, ts, nonce, SECRET) == _sig(body, ts, nonce)


@pytest.mark.parametrize("ts,nonce,sig", [
    ("\xff", "n", "deadbeef"),      # 非 ASCII timestamp → .encode("ascii") 会炸
    ("123", "\xff", "deadbeef"),    # 非 ASCII nonce → 同上
    ("123", "n", "\xff"),           # 非 ASCII signature → compare_digest 抛 TypeError
])
def test_非ASCII请求头判否而非抛异常(ts, nonce, sig):
    """Starlette 按 latin-1 解 header, 塞个 0xFF 字节就能造出非 ASCII 字符串。

    不拦就是一个「谁都能打出来的公网 500 + traceback」—— 本端点默认不校验来源。
    """
    assert verify_signature(b"{}", ts, nonce, sig, SECRET) is False


def test_正确签名通过():
    body, ts, nonce = b'{"maxSeq":1}', str(int(time.time())), "n1"
    assert verify_signature(body, ts, nonce, _sig(body, ts, nonce), SECRET) is True


def test_篡改body签名失败():
    ts, nonce = str(int(time.time())), "n1"
    good = _sig(b'{"maxSeq":1}', ts, nonce)
    assert verify_signature(b'{"maxSeq":999}', ts, nonce, good, SECRET) is False


def test_错误密钥签名失败():
    body, ts, nonce = b'{"maxSeq":1}', str(int(time.time())), "n1"
    bad = _sig(body, ts, nonce, "wrong-secret")
    assert verify_signature(body, ts, nonce, bad, SECRET) is False


def test_过期时间戳被拒():
    old = str(int(time.time()) - 600)          # 10 分钟前
    with pytest.raises(CallbackRejected, match="timestamp"):
        check_replay(old, "n-old", seen=set())


def test_未来时间戳也被拒():
    future = str(int(time.time()) + 600)
    with pytest.raises(CallbackRejected, match="timestamp"):
        check_replay(future, "n-future", seen=set())


def test_重放的nonce被拒():
    ts = str(int(time.time()))
    seen = set()
    check_replay(ts, "n-once", seen=seen)
    with pytest.raises(CallbackRejected, match="nonce"):
        check_replay(ts, "n-once", seen=seen)


def test_非法时间戳格式被拒():
    with pytest.raises(CallbackRejected, match="timestamp"):
        check_replay("not-a-number", "n", seen=set())


def test_空nonce被拒():
    ts = str(int(time.time()))
    with pytest.raises(CallbackRejected, match="nonce"):
        check_replay(ts, "", seen=set())


# ── nonce 池不能无限增长 ────────────────────────────────────────────
# 端点常驻运行, 每分钟一次回调 = 一年 50 万条。窗口只有 300s, 过期条目
# 永远不可能再被重放命中(时间戳先被拒), 必须清出去。

def test_nonce池按时间窗修剪():
    now = int(time.time())
    seen = set()
    check_replay(str(now), "n-fresh", seen=seen)
    seen.add(f"{now - 10_000}:n-stale")     # 模拟一条远超窗口的旧条目
    assert len(seen) == 2
    prune_nonces(seen, now=now)
    assert len(seen) == 1
    # 修剪掉的是旧的那条, 窗口内的必须留着(否则重放检测形同虚设)
    assert any(entry.endswith(":n-fresh") for entry in seen)


def test_修剪边界_窗口内保留窗口外清除():
    """把 prune 的比较符钉死: `>` 改成 `>=` 必须变红。

    修剪谓词若比 check_replay 的接受谓词更宽, 就会出现「条目已清掉但时间戳
    仍被接受」的缝隙 —— 重放检测在那一瞬间形同虚设。
    """
    from smartbi.api.platform_callback import TIMESTAMP_WINDOW_SECONDS as W

    now = int(time.time())
    seen = {f"{now - W}:边界内", f"{now - W - 1}:边界外"}
    prune_nonces(seen, now=now)
    assert seen == {f"{now - W}:边界内"}


def test_修剪后同ts的nonce仍被拒():
    """修剪不能误伤窗口内条目 —— 否则重放会通过。"""
    now = int(time.time())
    seen = set()
    check_replay(str(now), "n-keep", seen=seen)
    prune_nonces(seen, now=now)
    with pytest.raises(CallbackRejected, match="nonce"):
        check_replay(str(now), "n-keep", seen=seen)


def test_不同时间戳的同名nonce互不干扰():
    """条目按 ts:nonce 存 —— 同名 nonce 配不同 ts 时签名必然不同, 不是重放。"""
    now = int(time.time())
    seen = set()
    check_replay(str(now), "same-name", seen=seen)
    check_replay(str(now - 60), "same-name", seen=seen)   # 仍在窗口内, 不应报错
    assert len(seen) == 2


# ── 端点级: 三层校验的顺序与 HTTP 语义 ──────────────────────────────

@pytest.fixture
def client(monkeypatch):
    from fastapi import FastAPI
    from fastapi.testclient import TestClient

    from smartbi.api import platform_callback

    monkeypatch.setenv("PLATFORM_CALLBACK_SECRET", SECRET)
    monkeypatch.setenv("PLATFORM_CALLBACK_ALLOWED_IPS", "")
    platform_callback._SEEN_NONCES.clear()
    platform_callback._WARNED_NO_ALLOWLIST = False

    app = FastAPI()
    app.include_router(platform_callback.router)
    return TestClient(app)


def _headers(body: bytes, ts=None, nonce="n-http", secret=SECRET):
    ts = ts or str(int(time.time()))
    return {
        "Content-Type": "application/json",
        "X-Mock-Timestamp": ts,
        "X-Mock-Nonce": nonce,
        "X-Mock-Signature": _sig(body, ts, nonce, secret),
    }


def test_端点_合法回调200(client):
    body = b'{"platform":"keruyun","maxSeq":7}'
    resp = client.post("/api/platform-callback/keruyun", content=body,
                       headers=_headers(body))
    assert resp.status_code == 200
    assert resp.json()["success"] is True


def test_端点_未知平台404(client):
    body = b"{}"
    resp = client.post("/api/platform-callback/meituan", content=body,
                       headers=_headers(body))
    assert resp.status_code == 404


def test_端点_无签名401(client):
    resp = client.post("/api/platform-callback/keruyun", content=b"{}")
    assert resp.status_code == 401


def test_端点_错误签名401(client):
    body = b"{}"
    resp = client.post("/api/platform-callback/keruyun", content=body,
                       headers=_headers(body, secret="wrong"))
    assert resp.status_code == 401


def test_端点_重放同一请求401(client):
    body = b'{"maxSeq":1}'
    headers = _headers(body, nonce="replay-me")
    assert client.post("/api/platform-callback/keruyun", content=body,
                       headers=headers).status_code == 200
    second = client.post("/api/platform-callback/keruyun", content=body,
                         headers=headers)
    assert second.status_code == 401
    assert "nonce" in second.json()["message"]


def test_端点_验签失败不消费nonce(client):
    """顺序契约: 先验签再记 nonce。

    若反过来, 任何能触达端点的人都能用猜到的 nonce 抢先烧掉它, 让随后
    真实的那次回调被误判成重放。也让未鉴权流量污染 nonce 池。
    """
    from smartbi.api import platform_callback

    body = b'{"maxSeq":1}'
    bad = _headers(body, nonce="contested", secret="wrong")
    assert client.post("/api/platform-callback/keruyun", content=body,
                       headers=bad).status_code == 401
    assert platform_callback._SEEN_NONCES == set(), "验签失败不该写入 nonce 池"

    good = _headers(body, ts=bad["X-Mock-Timestamp"], nonce="contested")
    assert client.post("/api/platform-callback/keruyun", content=body,
                       headers=good).status_code == 200


def test_端点_非白名单IP403(client, monkeypatch):
    monkeypatch.setenv("PLATFORM_CALLBACK_ALLOWED_IPS", "10.0.0.1, 10.0.0.2")
    body = b"{}"
    resp = client.post("/api/platform-callback/keruyun", content=body,
                       headers=_headers(body, nonce="ip-test"))
    assert resp.status_code == 403


def test_端点_白名单命中放行(client, monkeypatch):
    # TestClient 的 client.host 是 "testclient"
    monkeypatch.setenv("PLATFORM_CALLBACK_ALLOWED_IPS", "testclient")
    body = b"{}"
    resp = client.post("/api/platform-callback/keruyun", content=body,
                       headers=_headers(body, nonce="ip-ok"))
    assert resp.status_code == 200


def test_端点_未配密钥500而非放行(client, monkeypatch):
    """禁降级: 没配密钥就拒绝服务, 绝不「没配就放行」。"""
    monkeypatch.delenv("PLATFORM_CALLBACK_SECRET", raising=False)
    body = b"{}"
    resp = client.post("/api/platform-callback/keruyun", content=body,
                       headers=_headers(body, nonce="no-secret"))
    assert resp.status_code == 500
    assert "PLATFORM_CALLBACK_SECRET" not in resp.json()["message"], \
        "不该把服务端配置细节回显给外部调用方"


def test_端点_非ASCII请求头401不500(client):
    """公网可达 + 默认不校验来源 → 任何未捕获异常都是谁都能打出来的 500。"""
    body = b"{}"
    headers = _headers(body, nonce="n-bad")
    # 必须发原始字节: httpx 不让 str 里带非 ASCII。真实链路上这个字节来自
    # socket, Starlette 按 latin-1 解成 "\xff" 再交给我们。
    headers["X-Mock-Signature"] = b"\xff"
    resp = client.post("/api/platform-callback/keruyun", content=body, headers=headers)
    assert resp.status_code == 401


def test_白名单为空必须喊出来(client, caplog):
    """禁降级: 层① 失效不能静默 —— 日志里没这句就没人知道只剩两层。"""
    import logging

    body = b"{}"
    with caplog.at_level(logging.WARNING, logger="smartbi.api.platform_callback"):
        client.post("/api/platform-callback/keruyun", content=body,
                    headers=_headers(body, nonce="warn-1"))
        client.post("/api/platform-callback/keruyun", content=body,
                    headers=_headers(body, nonce="warn-2"))
    hits = [r for r in caplog.records if "PLATFORM_CALLBACK_ALLOWED_IPS" in r.message]
    assert len(hits) == 1, "应当只喊一次, 不能每个请求刷屏"


def test_回调路径在PUBLIC_PATHS里():
    """PUBLIC_PATHS 缺这条 → 外部平台带不了我们的 JWT, 回调永远 401。

    但它**不是**无鉴权: 端点自身做三层校验(见上面那批测试)。
    """
    from auth_middleware import PUBLIC_PATHS
    assert "/api/platform-callback/keruyun" in PUBLIC_PATHS


def test_main_挂了回调router():
    """接线冒烟: 路由真的注册进了 app, 不只是文件存在。"""
    import main
    paths = {r.path for r in main.app.routes if hasattr(r, "path")}
    assert "/api/platform-callback/{platform}" in paths
