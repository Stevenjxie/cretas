"""活库对账: PG CHECK 白名单 vs Java 枚举常量 —— 找「枚举加了值, 约束没跟上」的漂移。

这个漂移类在本仓库已发作 8 次 (V20261027_15/_29/_34/_35/_37、V20260822_04, 以及
V20261029_33 一次扫出的 3 条)。前 6 次都是客户先撞到 500 才发现。本脚本把它变成
可主动扫的一次性对账。

与 `EnumCheckConstraintDriftTest` 的分工:
  * 那个 Java 测试是 **CI 门禁**, 静态读 Flyway 语料 —— 挡「今后新增枚举值忘了写
    migration」。但看不见只存在于 `database/*.sql` (未纳入版本管理的 bootstrap
    脚本) 的约束, ck_iti_type / ck_it_status 就是这么漏掉的。
  * 本脚本直查 `pg_constraint`, **没有那个盲区**, 是「线上真实状态」的权威口径。
    代价是要连到目标库, 所以进不了 CI。**发布前 / 排查 500 时手动跑一次。**

用法 (两步, 凭证不落盘、不进仓库):

  # 1) 在服务器上导出约束定义 (密码从 .env.prod 取, 见 CREDENTIAL-MANAGEMENT.md)
  ssh root@<host> 'bash -s' <<'EOF' > /tmp/prod_checks.txt
  export PGPASSWORD=$(grep -oP '(?<=^DB_PASSWORD=).*' /www/wwwroot/cretas/.env.prod | tr -d '\r')
  psql -h localhost -U cretas_user -d cretas_prod_db -tAF'|' -c "
    select c.conrelid::regclass::text, c.conname, pg_get_constraintdef(c.oid)
    from pg_constraint c
    join pg_class t on t.oid = c.conrelid
    join pg_namespace n on n.oid = t.relnamespace
    where c.contype = 'c' and n.nspname = 'public'
      and pg_get_constraintdef(c.oid) like '%ANY %ARRAY[%'
    order by 1, 2;"
  EOF

  # 2) 本机对账 (无三方依赖, 标准库即可)
  python scripts/audit/enum_check_constraint_drift.py <repo-root> /tmp/prod_checks.txt

⚠️ 库名是 `cretas_prod_db` (`DB_NAME` in .env.prod), **不是** `cretas_db` —— 同一台
   机器上两个库都存在, 查错了会得出「列不存在」这类完全误导的结论。

判据说明:
  * 只判「纯白名单」: CHECK body 除了一个 `col IN/= ANY (…)` (可带 `col IS NULL OR`
    守卫) 之外没有别的条件。复合业务规则里的 ARRAY 是子句而非列的值域, 判它必假阳性,
    一律归入 `conditional` 不做判定。
  * 嵌套枚举按 (文件, 简名) 定位, **不能**按 `pkg.Name` —— 多个文件都嵌套了
    `Status`/`State`, 按包名撞车会把 BomRecipe.Status 对成别人的 Status (v1 实测踩过)。
  * `MISSING IN DB` = 代码写得进但 PG 会拒 → 潜在 500。
    `dead in enum`  = 约束留着枚举已删的值 → 无害, 仅提示。
  * `unmapped` 多数是 String 列 (无枚举), 不代表漂移, 但也**不受门禁保护** —— 需要人看。
"""
import os
import re
import sys
import json

if len(sys.argv) < 3:
    sys.exit(__doc__)

REPO = sys.argv[1]
DUMP = sys.argv[2]
SRC = os.path.join(REPO, "backend/java/cretas-api/src/main/java")
OUT = os.path.dirname(os.path.abspath(DUMP))

# ------------------------------------------------------------------ 1. SQL ---

LIT = re.compile(r"'((?:[^']|'')*)'")
ANY_OCCUR = re.compile(r"\(?\b([a-z_][a-z0-9_]*)\)?::text\s*=\s*ANY\s*\(", re.I)


def balanced(s, i, opener="(", closer=")"):
    depth = 0
    for j in range(i, len(s)):
        if s[j] == opener:
            depth += 1
        elif s[j] == closer:
            depth -= 1
            if depth == 0:
                return s[i + 1:j], j
    return s[i + 1:], len(s)


def strip_outer(s):
    s = s.strip()
    while s.startswith("(") and balanced(s, 0)[1] == len(s) - 1:
        s = s[1:-1].strip()
    return s


pure, conditional = [], []
with open(DUMP, encoding="utf-8") as fh:
    for raw in fh:
        raw = raw.strip()
        if not raw or raw.count("|") < 2:
            continue
        table, conname, cdef = raw.split("|", 2)
        body = cdef.strip()
        assert body.upper().startswith("CHECK"), body[:40]
        body = strip_outer(body[len("CHECK"):])

        occurrences = []
        for m in ANY_OCCUR.finditer(body):
            col = m.group(1).lower()
            inner, _ = balanced(body, m.end() - 1)
            ab = inner.find("ARRAY[")
            if ab < 0:
                continue
            arr, _ = balanced(inner, inner.index("[", ab), "[", "]")
            vals = frozenset(x.group(1) for x in LIT.finditer(arr))
            if vals:
                occurrences.append((col, vals))
        if not occurrences:
            continue

        # "pure" = exactly one ANY-clause, and once we remove the clause plus an
        # optional `col IS NULL OR` guard, nothing substantive remains.
        residue = ANY_OCCUR.sub("", body)
        residue = re.sub(r"ARRAY\[[^\]]*\]", "", residue)
        residue = re.sub(r"::\s*(text|character varying)(\[\])?", "", residue)
        residue = re.sub(r"\b[a-z_][a-z0-9_]*\s+IS\s+NULL\b", "", residue, flags=re.I)
        residue = re.sub(r"\bOR\b", "", residue, flags=re.I)
        residue = re.sub(r"[()\s]", "", residue)

        rec = (table, conname, occurrences)
        if len(occurrences) == 1 and residue == "":
            pure.append(rec)
        else:
            conditional.append((table, conname, occurrences, cdef))

# ---------------------------------------------------------------- 2. enums ---

JAVA = []
for dirpath, _subdirs, files in os.walk(SRC):
    for f in files:
        if f.endswith(".java"):
            JAVA.append(os.path.join(dirpath, f))

PKG = re.compile(r"^\s*package\s+([\w.]+)\s*;", re.M)
ENUM_DECL = re.compile(r"\b(?:public|private|protected|static|\s)*enum\s+(\w+)\s*\{", re.M)
CONST = re.compile(r"^\s*([A-Z][A-Z0-9_]*)\s*(?:\(|,|;|$)", re.M)

# A declaration is identified by (file, simple name). Nested enums MUST NOT be
# keyed as pkg.Name — several files in the same package nest a `Status`/`State`
# enum and that collision silently mismatched bom_recipes.status in v1 of this
# script (BomRecipe.Status{DRAFT,ACTIVE,ARCHIVED} got another file's Status).
enum_decls = []  # {path, pkg, name, consts, top_level}
for path in JAVA:
    src = open(path, encoding="utf-8", errors="replace").read()
    pm = PKG.search(src)
    pkg = pm.group(1) if pm else ""
    for m in ENUM_DECL.finditer(src):
        name = m.group(1)
        body, _ = balanced(src, src.index("{", m.end() - 1), "{", "}")
        head = body.split(";")[0]
        consts = [c.group(1) for c in CONST.finditer(head)]
        if not consts:
            continue
        enum_decls.append({
            "path": path, "pkg": pkg, "name": name, "consts": consts,
            "top_level": os.path.basename(path) == f"{name}.java",
        })


def resolve_enum(ftype, path, pkg, imports):
    """Java-ish name resolution, nearest scope first."""
    for d in enum_decls:                                    # nested in same file
        if d["path"] == path and d["name"] == ftype:
            return d
    if ftype in imports:                                    # explicit import
        want = imports[ftype]
        for d in enum_decls:
            if d["top_level"] and f"{d['pkg']}.{d['name']}" == want:
                return d
    for d in enum_decls:                                    # same package
        if d["top_level"] and d["pkg"] == pkg and d["name"] == ftype:
            return d
    cands = [d for d in enum_decls if d["top_level"] and d["name"] == ftype]
    return cands[0] if len(cands) == 1 else None

# ------------------------------------------------------------- 3. entities ---

TABLE = re.compile(r'@Table\s*\(\s*name\s*=\s*"([^"]+)"')
IMPORT = re.compile(r"^\s*import\s+([\w.]+)\s*;", re.M)
# field, tolerating an initializer:  private Foo bar = Foo.BAZ;
FIELD = re.compile(r"\bprivate\s+([A-Z]\w*)\s+(\w+)\s*(?:=[^;]*)?;")
COLNAME = re.compile(r'@Column\s*\(([^)]*)\)')
NAMEARG = re.compile(r'name\s*=\s*"([^"]+)"')


def snake(s):
    return re.sub(r"(?<!^)(?=[A-Z])", "_", s).lower()


table_col_enum = {}
for path in JAVA:
    src = open(path, encoding="utf-8", errors="replace").read()
    tm = TABLE.search(src)
    if not tm:
        continue
    table = tm.group(1).lower()
    pm = PKG.search(src)
    pkg = pm.group(1) if pm else ""
    imports = {i.rsplit(".", 1)[1]: i for i in IMPORT.findall(src)}

    for fm in FIELD.finditer(src):
        ftype, fname = fm.group(1), fm.group(2)
        decl = resolve_enum(ftype, path, pkg, imports)
        if not decl:
            continue
        # column name: nearest preceding @Column(...) in the annotation block
        head = src[max(0, fm.start() - 500):fm.start()]
        col = None
        for cm in COLNAME.finditer(head):
            nm = NAMEARG.search(cm.group(1))
            if nm:
                col = nm.group(1)
        if not col:
            col = snake(fname)
        label = decl["name"] if decl["top_level"] \
            else f"{os.path.basename(decl['path'])[:-5]}.{decl['name']}"
        table_col_enum[(table, col.lower())] = (label, decl["consts"],
                                                os.path.basename(path), fname)

# ------------------------------------------------------------ 4. correlate ---

drift, ok, unmapped = [], [], []
for table, conname, occ in pure:
    col, vals = occ[0]
    ent = table_col_enum.get((table.lower(), col))
    if not ent:
        unmapped.append({"table": table, "constraint": conname, "column": col,
                         "db_allows": sorted(vals)})
        continue
    label, consts, java_file, field = ent
    missing = [c for c in consts if c not in vals]
    rec = {"table": table, "constraint": conname, "column": col,
           "enum": label, "entity_file": java_file, "field": field,
           "enum_count": len(consts), "db_allows_count": len(vals),
           "missing_in_db": missing,
           "dead_in_enum": sorted(v for v in vals if v not in consts)}
    (drift if missing else ok).append(rec)

print(f"pure whitelist constraints : {len(pure)}")
print(f"conditional (not judged)   : {len(conditional)}")
print(f"enums declared             : {len(enum_decls)}")
print(f"(table,col)->enum mappings : {len(table_col_enum)}")
print(f"  matched & aligned        : {len(ok)}")
print(f"  matched & DRIFTING       : {len(drift)}")
print(f"  unmapped (manual review) : {len(unmapped)}")
print()
print("=" * 78)
print("DRIFT — code can write these values, prod PG will REJECT them")
print("=" * 78)
for d in sorted(drift, key=lambda x: -len(x["missing_in_db"])):
    print(f"\n{d['table']}.{d['column']}  [{d['constraint']}]")
    print(f"  enum {d['enum']} ({d['entity_file']}#{d['field']})"
          f"  enum={d['enum_count']} db={d['db_allows_count']}")
    print(f"  MISSING IN DB : {', '.join(d['missing_in_db'])}")
    if d["dead_in_enum"]:
        print(f"  dead in enum  : {', '.join(d['dead_in_enum'])}")

with open(os.path.join(OUT, "drift.json"), "w", encoding="utf-8") as fh:
    json.dump({"drift": drift, "aligned": ok, "unmapped": unmapped,
               "conditional": [{"table": t, "constraint": c, "def": d}
                               for t, c, _occ, d in conditional]},
              fh, ensure_ascii=False, indent=2)
print("\n-> drift.json (drift / aligned / unmapped / conditional)")
