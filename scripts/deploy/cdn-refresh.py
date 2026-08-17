"""阿里云 CDN RefreshObjectCaches —— 不依赖 aliyun CLI, 用 RPC v1.0 签名直发。

用法: python cdn_refresh.py <ObjectPath> [File|Directory]
AK/SK 从 ~/.ossutilconfig-apk 读 (账号 A/C, 与 CDN 同账号), ⛔ 不打印。
退出码三态: 0 提交成功 / 1 接口报错 / 2 这次没量到(读不到凭证/网络不通)
"""
import base64
import configparser
import datetime
import hashlib
import hmac
import json
import os
import sys
import urllib.parse
import urllib.request
import uuid

CONF = os.path.expanduser("~/.ossutilconfig-apk")


def percent_encode(s):
    return (
        urllib.parse.quote(str(s), safe="~")
        .replace("+", "%20")
        .replace("*", "%2A")
        .replace("%7E", "~")
    )


def main():
    if len(sys.argv) < 2:
        print("CDN=NO_MEASUREMENT reason=未传 ObjectPath", file=sys.stderr)
        return 2
    object_path = sys.argv[1]
    object_type = sys.argv[2] if len(sys.argv) > 2 else "File"

    if not os.path.isfile(CONF):
        print(f"CDN=NO_MEASUREMENT reason=找不到 {CONF}", file=sys.stderr)
        return 2
    cp = configparser.ConfigParser()
    cp.read(CONF, encoding="utf-8")
    sec = "Credentials" if cp.has_section("Credentials") else cp.sections()[0]
    ak = cp.get(sec, "accessKeyID", fallback=None) or cp.get(sec, "accesskeyid", fallback=None)
    sk = cp.get(sec, "accessKeySecret", fallback=None) or cp.get(sec, "accesskeysecret", fallback=None)
    if not ak or not sk:
        print("CDN=NO_MEASUREMENT reason=配置里没有 AK/SK", file=sys.stderr)
        return 2

    params = {
        "Action": "RefreshObjectCaches",
        "ObjectPath": object_path,
        "ObjectType": object_type,
        "Version": "2018-05-10",
        "AccessKeyId": ak,
        "SignatureMethod": "HMAC-SHA1",
        "SignatureVersion": "1.0",
        "SignatureNonce": uuid.uuid4().hex,
        "Timestamp": datetime.datetime.now(datetime.timezone.utc).strftime(
            "%Y-%m-%dT%H:%M:%SZ"
        ),
        "Format": "JSON",
    }
    canonical = "&".join(
        f"{percent_encode(k)}={percent_encode(params[k])}" for k in sorted(params)
    )
    string_to_sign = "GET&%2F&" + percent_encode(canonical)
    sig = base64.b64encode(
        hmac.new((sk + "&").encode("utf-8"), string_to_sign.encode("utf-8"), hashlib.sha1).digest()
    ).decode("ascii")
    url = "https://cdn.aliyuncs.com/?Signature=" + percent_encode(sig) + "&" + canonical

    try:
        with urllib.request.urlopen(url, timeout=30) as r:
            body = json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", errors="replace")[:400]
        print(f"CDN=FAIL http={e.code} {detail}", file=sys.stderr)
        return 1
    except Exception as e:  # noqa: BLE001 — 网络不通属于「没量到」
        print(f"CDN=NO_MEASUREMENT reason={type(e).__name__}: {e}", file=sys.stderr)
        return 2

    # 阳性对照: 返回体里必须有 RefreshTaskId, 否则「成功」只是 HTTP 200 的假象
    task = body.get("RefreshTaskId")
    if not task:
        print(f"CDN=FAIL 返回体没有 RefreshTaskId: {body}", file=sys.stderr)
        return 1
    print(f"CDN=SUBMITTED path={object_path} type={object_type} task={task}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
