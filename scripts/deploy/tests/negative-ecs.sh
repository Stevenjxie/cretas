#!/usr/bin/env bash
# Mutation tests for the ECS signer and verifier. Each case asserts a specific
# error token so a test cannot pass merely because the tool failed elsewhere.
set -uo pipefail

SIGN=/usr/local/sbin/oss-sign-put.py
VERIFY=/usr/local/sbin/oss-verify-artifact.sh
TREE=05fc3ba1397faf12db80d333b6a42afdb9f6a828
SHA=53f6bca41dc59153f30a9fca69a2a9ae1be6086cc8c592dca178cd80c59c7ab9
UP=53F6BCA41DC59153F30A9FCA69A2A9AE1BE6086CC8C592DCA178CD80C59C7AB9
pass=0; fail=0

expect() { # name token cmd...
  local name=$1 token=$2; shift 2
  local out
  out=$("$@" 2>&1)
  if printf '%s' "$out" | grep -q -- "$token"; then
    echo "  PASS  $name"; pass=$((pass+1))
  else
    echo "  FAIL  $name -- expected '$token', got: $(printf '%s' "$out" | head -1)"; fail=$((fail+1))
  fi
}

echo "== signer =="
expect "unapproved prefix"   prefix_not_approved  $SIGN --prefix deploy/evil/ --tree-sha $TREE --jar-sha256 $SHA --size 100
expect "traversal prefix"    prefix_not_approved  $SIGN --prefix ../deploy/backend/ --tree-sha $TREE --jar-sha256 $SHA --size 100
expect "uppercase sha"       jar_sha256_invalid   $SIGN --prefix codex-network-test/ --tree-sha $TREE --jar-sha256 $UP --size 100
expect "short sha"           jar_sha256_invalid   $SIGN --prefix codex-network-test/ --tree-sha $TREE --jar-sha256 abc --size 100
expect "bad tree sha"        tree_sha_invalid     $SIGN --prefix codex-network-test/ --tree-sha 'x;rm -rf /' --jar-sha256 $SHA --size 100
expect "zero size"           size_invalid         $SIGN --prefix codex-network-test/ --tree-sha $TREE --jar-sha256 $SHA --size 0
expect "expiry over 900s"    expires_too_long     $SIGN --prefix codex-network-test/ --tree-sha $TREE --jar-sha256 $SHA --size 100 --expires-seconds 901

echo "== verifier =="
expect "unapproved prefix"   prefix_not_approved  $VERIFY --prefix deploy/evil/ --tree-sha $TREE --jar-sha256 $SHA --size 100
expect "purge on real prefix refused" refusing_to_purge_non_acceptance_prefix \
  $VERIFY --prefix deploy/backend/ --tree-sha $TREE --jar-sha256 $SHA --size 100 --purge-acceptance
expect "absent object"       oss_stat_failed      $VERIFY --prefix codex-network-test/ --tree-sha $TREE --jar-sha256 $SHA --size 100

echo "== signer must not leak on refusal =="
out=$($SIGN --prefix deploy/evil/ --tree-sha $TREE --jar-sha256 $SHA --size 100 2>&1)
if printf '%s' "$out" | grep -qiE 'Signature=|OSSAccessKeyId=|accessKeySecret'; then
  echo "  FAIL  refusal output leaked credential material"; fail=$((fail+1))
else
  echo "  PASS  refusal output carries no credential material"; pass=$((pass+1))
fi

echo
echo "ecs_negative_pass=$pass ecs_negative_fail=$fail"
[ "$fail" -eq 0 ]
