"""闸 —— 每次 OTA manifest 拉取都必须留下一条可数的记录。

<h2>为什么有这道闸</h2>
2026-08-17 实测: 要回答「工人手机上的 App 是不是活的 / 有没有设备拉过新 bundle」,
**手上没有任何读数**。当时能拿到的只有 uvicorn 的访问日志:

    INFO:  139.196.165.140:49150 - "GET /api/ota/manifest HTTP/1.1" 200 OK

82 条请求全部显示同一个 IP —— 那是反代前端, 不是设备; 既没有 runtime 版本,
也分不出「有更新」还是「已是最新」。于是「现场有没有人在用」只能靠去现场问。

这道闸守的是: 四条成功返回路径 **每一条** 都打一条 `OTA_PULL` 记录。
⛔ 少打一条, 那一类拉取就在统计里消失, 而**统计看起来仍然完全正常** ——
这正是「机制在、没接上」最难发现的形状: 读数不为零, 只是少了一类。

<h2>口径</h2>
这里数的是**拉取次数**, 不是设备数 —— 没有采集任何设备唯一标识 (有意如此)。
「几台设备」只能从 (ip, runtime) 的组合粗略估, 报的时候必须带这个口径。
"""
from __future__ import annotations

import logging
from pathlib import Path


def _default_headers(**overrides) -> dict:
    base = {
        "expo-protocol-version": "1",
        "expo-platform": "android",
        "expo-runtime-version": "1.0.0",
    }
    base.update(overrides)
    return base


def _pull_lines(caplog) -> list[str]:
    return [r.getMessage() for r in caplog.records if "OTA_PULL" in r.getMessage()]


def test_normal_update_logs_a_pull(client, populated_bundle_dir: Path, caplog):
    with caplog.at_level(logging.INFO, logger="ota.api.endpoints"):
        r = client.get("/api/ota/manifest", headers=_default_headers())

    assert r.status_code == 200
    lines = _pull_lines(caplog)
    assert len(lines) == 1, f"正常下发这条路径没留下记录: {caplog.text[:500]}"
    # 逐字段断言 —— 只断言「有一行 OTA_PULL」不够: 字段少一个, 统计就废一个维度。
    assert "outcome=update" in lines[0]
    assert "platform=android" in lines[0]
    assert "runtime=1.0.0" in lines[0]
    assert "channel=" in lines[0]
    assert "ip=" in lines[0]


def test_no_update_available_also_logs_a_pull(client, populated_bundle_dir: Path, caplog):
    """已是最新的那次也要记 —— 它才是**日常绝大多数**的形态。

    🔴 如果只记「有更新」那一条, 日志里就只剩发版当天的几条,
    「平时有没有设备在拉」永远读不出来 —— 而那正是我们要问的问题。
    """
    first = client.get("/api/ota/manifest", headers=_default_headers())
    assert first.status_code == 200
    # 从上一次响应里取出 update id, 再作为 expo-current-update-id 发回去
    body = first.content.decode("utf-8", errors="replace")
    import json
    import re

    m = re.search(r'"id"\s*:\s*"([0-9a-fA-F-]{36})"', body)
    assert m, f"没能从 manifest 里取到 id, 阳性对照失败: {body[:300]}"
    update_id = m.group(1)
    assert json.loads('"%s"' % update_id) == update_id  # 纯粹确认它是个合法字符串

    caplog.clear()
    with caplog.at_level(logging.INFO, logger="ota.api.endpoints"):
        r = client.get(
            "/api/ota/manifest",
            headers=_default_headers(**{"expo-current-update-id": update_id}),
        )

    assert r.status_code == 200
    lines = _pull_lines(caplog)
    assert len(lines) == 1, f"「已是最新」这条路径没留下记录: {caplog.text[:500]}"
    assert "outcome=no-update" in lines[0]
    assert f"current={update_id[:8]}" in lines[0]


def test_rejected_requests_do_not_log_a_pull(client, populated_bundle_dir: Path, caplog):
    """阴性对照 —— 400 不是「设备拉了一次」, 不该进统计。

    没有这一条, 「拉取数」会被扫描器 / 探活 / 拼错头的请求灌水,
    而灌进来的水**和真实拉取长得一模一样**。
    """
    with caplog.at_level(logging.INFO, logger="ota.api.endpoints"):
        r = client.get(
            "/api/ota/manifest",
            headers=_default_headers(**{"expo-platform": "windows"}),
        )

    assert r.status_code == 400
    assert _pull_lines(caplog) == []


def test_client_ip_prefers_x_forwarded_for(client, populated_bundle_dir: Path, caplog):
    """反代场景: 真实客户端在 X-Forwarded-For 的第一段。

    ⚠️ 这正是当初读不出设备的原因 —— 不取这个头, 每一条记录的 ip 都是反代自己,
    读数看起来齐全, 而那一列的信息量是 0。
    """
    with caplog.at_level(logging.INFO, logger="ota.api.endpoints"):
        r = client.get(
            "/api/ota/manifest",
            headers=_default_headers(**{"x-forwarded-for": "203.0.113.7, 10.0.0.1"}),
        )

    assert r.status_code == 200
    lines = _pull_lines(caplog)
    assert len(lines) == 1
    assert "ip=203.0.113.7" in lines[0], lines[0]
