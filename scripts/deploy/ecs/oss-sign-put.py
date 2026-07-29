#!/usr/bin/env python3
"""Mint a short-lived, single-object OSS PUT URL on the Shanghai ECS.

Long-lived OSS credentials never leave this host. The presigned URL is the only
thing that travels, and it is deliberately weak on purpose:

  * it expires in at most 900 seconds;
  * it is bound to exactly one key, one method (PUT) and one Content-Type;
  * the signature covers ``x-oss-forbid-overwrite: true``, so a leaked URL still
    cannot replace an object that already exists -- the uploader is forced to
    send that header, and OSS then rejects the write with 409 FileAlreadyExists.

The URL is written to stdout and nothing else is; every diagnostic goes to
stderr so the caller can capture the URL into a variable without scraping.

Exit codes:
  0  signed URL on stdout (upload required), or artifact_status=hit on stderr
     with empty stdout (object already present and consistent -- do not upload)
  2  usage / validation error
  1  refusal: the key exists but disagrees with what was asked for
"""

import argparse
import base64
import configparser
import hashlib
import hmac
import os
import re
import subprocess
import sys
import time
import urllib.parse

# Only these prefixes may ever be signed. codex-network-test/ is the disposable
# acceptance prefix; deploy/backend/ is the real release prefix.
APPROVED_PREFIXES = ("deploy/backend/", "codex-network-test/")
APPROVED_BUCKET = os.environ.get("CRETAS_RELEASE_OSS_BUCKET", "cretas-media")
SIGNING_HOST = "oss-cn-shanghai.aliyuncs.com"
CONTENT_TYPE = "application/java-archive"
MAX_EXPIRES_SECONDS = 900
OSSUTIL = "/usr/local/bin/ossutil64"
OSSUTIL_CONFIG = os.path.expanduser("~/.ossutilconfig")

SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
GIT_SHA_RE = re.compile(r"^[0-9a-f]{7,40}$")


def fail(message, code=2):
    print(message, file=sys.stderr)
    raise SystemExit(code)


def load_credentials():
    parser = configparser.ConfigParser()
    # ossutil config keys are case sensitive; keep them verbatim.
    parser.optionxform = str
    if not parser.read(OSSUTIL_CONFIG):
        fail("error=ossutil_config_unreadable")
    section = parser["Credentials"]
    key_id = section.get("accessKeyID", "").strip()
    key_secret = section.get("accessKeySecret", "").strip()
    if not key_id or not key_secret:
        fail("error=ossutil_config_incomplete")
    return key_id, key_secret


def object_size(key):
    """Return the size of an existing object, or None when it does not exist."""
    result = subprocess.run(
        [OSSUTIL, "stat", "oss://{}/{}".format(APPROVED_BUCKET, key),
         "-c", OSSUTIL_CONFIG],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    )
    combined = (result.stdout + result.stderr).decode("utf-8", "replace")
    if result.returncode != 0:
        lowered = combined.lower()
        if "nosuchkey" in lowered or "not exist" in lowered or "404" in lowered:
            return None
        fail("error=oss_stat_failed rc={}".format(result.returncode), 1)
    for line in combined.splitlines():
        # ossutil prints "Content-Length : 167106178"
        if line.split(":")[0].strip().lower() == "content-length":
            return int(line.split(":", 1)[1].strip())
    fail("error=oss_stat_no_content_length", 1)


def sign(key_secret, key, expires_at, oss_headers):
    canonical_headers = "".join(
        "{}:{}\n".format(name, value) for name, value in sorted(oss_headers.items())
    )
    canonical_resource = "/{}/{}".format(APPROVED_BUCKET, key)
    string_to_sign = "PUT\n\n{}\n{}\n{}{}".format(
        CONTENT_TYPE, expires_at, canonical_headers, canonical_resource
    )
    digest = hmac.new(
        key_secret.encode("utf-8"), string_to_sign.encode("utf-8"), hashlib.sha1
    ).digest()
    return base64.b64encode(digest).decode("ascii")


def main():
    parser = argparse.ArgumentParser(add_help=True)
    parser.add_argument("--prefix", required=True)
    parser.add_argument("--tree-sha", required=True)
    parser.add_argument("--jar-sha256", required=True)
    parser.add_argument("--size", required=True)
    parser.add_argument("--expires-seconds", default="900")
    args = parser.parse_args()

    prefix = args.prefix if args.prefix.endswith("/") else args.prefix + "/"
    if prefix not in APPROVED_PREFIXES:
        fail("error=prefix_not_approved")
    if not GIT_SHA_RE.match(args.tree_sha):
        fail("error=tree_sha_invalid")
    if not SHA256_RE.match(args.jar_sha256):
        fail("error=jar_sha256_invalid")
    if not re.match(r"^[1-9][0-9]*$", args.size):
        fail("error=size_invalid")
    if not re.match(r"^[1-9][0-9]*$", args.expires_seconds):
        fail("error=expires_invalid")
    expires_seconds = int(args.expires_seconds)
    if expires_seconds > MAX_EXPIRES_SECONDS:
        fail("error=expires_too_long max={}".format(MAX_EXPIRES_SECONDS))
    expected_size = int(args.size)

    key = "{}{}/{}.jar".format(prefix, args.tree_sha, args.jar_sha256)

    existing = object_size(key)
    if existing is not None:
        if existing == expected_size:
            # Immutable reuse: the key encodes the artifact SHA-256, and the ECS
            # verifier re-downloads and re-hashes before anything is trusted.
            print("artifact_status=hit bytes={}".format(existing), file=sys.stderr)
            return 0
        fail(
            "error=artifact_conflict key_exists_with_different_size "
            "expected={} actual={}".format(expected_size, existing),
            1,
        )

    key_id, key_secret = load_credentials()
    expires_at = int(time.time()) + expires_seconds
    oss_headers = {"x-oss-forbid-overwrite": "true"}
    signature = sign(key_secret, key, expires_at, oss_headers)

    url = "https://{}.{}/{}?OSSAccessKeyId={}&Expires={}&Signature={}".format(
        APPROVED_BUCKET,
        SIGNING_HOST,
        urllib.parse.quote(key),
        urllib.parse.quote(key_id, safe=""),
        expires_at,
        urllib.parse.quote(signature, safe=""),
    )
    print("artifact_status=absent expires_in={}".format(expires_seconds), file=sys.stderr)
    # stdout is the URL and only the URL.
    sys.stdout.write(url + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
