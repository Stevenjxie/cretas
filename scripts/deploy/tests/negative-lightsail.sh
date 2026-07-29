#!/usr/bin/env bash
# Mutation tests for the Tokyo uploader: every rejection path must fire for the
# RIGHT reason. A test that passes because the tool broke elsewhere is worthless,
# so each case asserts the specific error token.
set -uo pipefail

TOOL="sudo -n /usr/local/sbin/oss-put-artifact"
GOOD_HOST="cretas-media.oss-cn-shanghai.aliyuncs.com"
SHA_A=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
pass=0; fail=0

check() { # name expected_token args... <<< url
  local name=$1 expect=$2; shift 2
  local url out
  IFS= read -r url
  out=$(printf '%s\n' "$url" | $TOOL "$@" 2>&1)
  if printf '%s' "$out" | grep -q "$expect"; then
    echo "  PASS  $name"; pass=$((pass+1))
  else
    echo "  FAIL  $name -- expected '$expect', got: $(printf '%s' "$out" | head -1)"; fail=$((fail+1))
  fi
}

echo "== URL allow-list =="
check "plain http rejected"          url_not_allowed --sha256 $SHA_A --size 100 <<< "http://$GOOD_HOST/codex-network-test/a.jar"
check "foreign host rejected"        url_not_allowed --sha256 $SHA_A --size 100 <<< "https://evil.example.com/codex-network-test/a.jar"
check "unapproved prefix rejected"   url_not_allowed --sha256 $SHA_A --size 100 <<< "https://$GOOD_HOST/somewhere-else/a.jar"
check "credentials in url rejected"  url_not_allowed --sha256 $SHA_A --size 100 <<< "https://u:p@$GOOD_HOST/codex-network-test/a.jar"
check "odd port rejected"            url_not_allowed --sha256 $SHA_A --size 100 <<< "https://$GOOD_HOST:8443/codex-network-test/a.jar"
check "path traversal rejected"      url_not_allowed --sha256 $SHA_A --size 100 <<< "https://$GOOD_HOST/codex-network-test/../deploy/backend/a.jar"
check "bucket-prefix lookalike host" url_not_allowed --sha256 $SHA_A --size 100 <<< "https://$GOOD_HOST.evil.com/codex-network-test/a.jar"

echo "== argument validation =="
check "uppercase sha rejected"       usage --sha256 AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA --size 100 <<< "https://$GOOD_HOST/codex-network-test/a.jar"
check "short sha rejected"           usage --sha256 abc --size 100 <<< "https://$GOOD_HOST/codex-network-test/a.jar"
check "zero size rejected"           usage --sha256 $SHA_A --size 0 <<< "https://$GOOD_HOST/codex-network-test/a.jar"

echo "== empty stdin =="
out=$(printf '' | $TOOL --sha256 $SHA_A --size 100 2>&1)
if printf '%s' "$out" | grep -q url_missing; then echo "  PASS  empty stdin rejected"; pass=$((pass+1));
else echo "  FAIL  empty stdin -- got: $out"; fail=$((fail+1)); fi

echo "== cache integrity (last-moment re-verification) =="
check "missing cache entry"          cache_miss --sha256 $SHA_A --size 100 <<< "https://$GOOD_HOST/codex-network-test/a.jar"

# Plant a file whose content does NOT hash to its name: size will match, so the
# only thing that can catch it is the SHA-256 re-check right before upload.
FAKE=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
sudo -n bash -c "head -c 100 /dev/zero > /var/cache/github-artifacts/$FAKE.bin"
check "tampered content rejected"    sha256_mismatch --sha256 $FAKE --size 100 <<< "https://$GOOD_HOST/codex-network-test/a.jar"
check "size mismatch rejected"       size_mismatch --sha256 $FAKE --size 999 <<< "https://$GOOD_HOST/codex-network-test/a.jar"
sudo -n rm -f "/var/cache/github-artifacts/$FAKE.bin"

echo
echo "lightsail_negative_pass=$pass lightsail_negative_fail=$fail"
[ "$fail" -eq 0 ]
