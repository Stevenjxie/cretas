#!/usr/bin/env bash
# Mutation tests for github-artifact-stage (Tokyo). Same rule as the others:
# assert the specific error token, never just "it failed".
set -uo pipefail

TOOL="sudo -n /usr/local/sbin/github-artifact-stage"
OK_URL="https://api.github.com/x"
pass=0; fail=0

check() { # name expected_token args...   (url on stdin)
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

echo "== jar-name is a bare filename (ZIP member traversal vector) =="
check "path separator rejected"   usage --zip-size 100 --jar-name '../evil.jar'      <<< "$OK_URL"
check "absolute path rejected"    usage --zip-size 100 --jar-name '/etc/evil.jar'    <<< "$OK_URL"
check "subdir rejected"           usage --zip-size 100 --jar-name 'a/b.jar'          <<< "$OK_URL"
check "non-jar suffix rejected"   usage --zip-size 100 --jar-name 'payload.sh'       <<< "$OK_URL"

echo "== argument validation =="
check "zero zip-size rejected"    usage --zip-size 0   --jar-name 'app.jar'          <<< "$OK_URL"
check "negative zip-size rejected" usage --zip-size -5 --jar-name 'app.jar'          <<< "$OK_URL"

echo "== URL allow-list =="
check "plain http rejected"       url_not_allowed --zip-size 100 --jar-name app.jar <<< "http://api.github.com/x"
check "foreign host rejected"     url_not_allowed --zip-size 100 --jar-name app.jar <<< "https://evil.example.com/x"
check "credentials rejected"      url_not_allowed --zip-size 100 --jar-name app.jar <<< "https://u:p@api.github.com/x"
check "odd port rejected"         url_not_allowed --zip-size 100 --jar-name app.jar <<< "https://api.github.com:8443/x"
check "lookalike host rejected"   url_not_allowed --zip-size 100 --jar-name app.jar <<< "https://api.github.com.evil.com/x"

echo "== empty stdin =="
out=$(printf '' | $TOOL --zip-size 100 --jar-name app.jar 2>&1)
if printf '%s' "$out" | grep -q url_missing; then
  echo "  PASS  empty stdin rejected"; pass=$((pass+1))
else
  echo "  FAIL  empty stdin -- got: $out"; fail=$((fail+1))
fi

echo "== zip-slip: a crafted archive must not escape the work directory =="
# Exercises the extractor's member-selection logic directly against an archive
# whose entries claim traversal paths and an absolute path.
python3 - <<'PY'
import os, tempfile, zipfile, hashlib, sys

work = tempfile.mkdtemp()
zip_path = os.path.join(work, "artifact.zip")
jar_name = "app.jar"
with zipfile.ZipFile(zip_path, "w") as z:
    z.writestr(jar_name, b"legit")
    z.writestr("../escaped.jar", b"pwned")
    z.writestr("../../etc/escaped2.jar", b"pwned")
    z.writestr("/abs/escaped3.jar", b"pwned")

wanted = {jar_name, jar_name + ".sha256", jar_name + ".commit", "release-jar.manifest"}
with zipfile.ZipFile(zip_path) as archive:
    present = {n for n in archive.namelist() if n in wanted}
    for name in present:
        target = os.path.join(work, os.path.basename(name))
        with archive.open(name) as s, open(target, "wb") as d:
            d.write(s.read())

parent = os.path.dirname(work)
escaped = [f for f in os.listdir(parent)
           if f.startswith("escaped") and os.path.isfile(os.path.join(parent, f))]
extracted = sorted(os.listdir(work))
if escaped:
    print("  FAIL  zip-slip wrote outside the work dir: %s" % escaped); sys.exit(1)
if extracted != ["app.jar", "artifact.zip"]:
    print("  FAIL  unexpected extraction set: %s" % extracted); sys.exit(1)
print("  PASS  traversal entries ignored; only the exact member was extracted")
PY
if [ $? -eq 0 ]; then pass=$((pass+1)); else fail=$((fail+1)); fi

echo
echo "artifact_stage_negative_pass=$pass artifact_stage_negative_fail=$fail"
[ "$fail" -eq 0 ]
