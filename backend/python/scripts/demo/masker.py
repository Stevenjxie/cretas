"""Deterministic zh_CN identity masking. Same source value -> same fake value,
so relationships stay consistent across rows. Numbers/dates are NOT handled here
(the clone engine only routes identity columns through the masker)."""
import hashlib
import re
from faker import Faker

# Real client/brand tokens that must NOT appear anywhere in a demo tenant. Includes both the
# restaurant brand (青花椒, also a Sichuan spice) and factory F001's B2B customer brands
# (it makes products FOR named clients, so brands leak into product names / codes / remarks).
# The verify gate (FORBIDDEN_TOKENS) mirrors this list — keep them in sync.
BRAND_TOKENS = ["青花椒", "叮咚好食光", "永辉超市", "永辉", "盒马", "海底捞", "上海海壹佰米", "级联测试客户"]

# Brand/cruft token -> replacement, applied in order (longer/more-specific first). 青花椒 is a
# Sichuan spice so it stays a neutral pepper (藤椒); client brands are removed or turned into a
# generic "客户"; test-data cruft is cleaned. The clone engine routes every name/code/remark
# column that can carry these through cuisine() below.
BRAND_SUBSTITUTE = {
    "集成测试产品": "什锦海鲜拼盘",
    "测试产品B4新版": "黄鱼片精装",
    "青花椒": "藤椒",
    "叮咚好食光": "",
    "上海海壹佰米": "",
    "级联测试客户": "客户",
    "永辉超市": "客户",
    "永辉": "客户",
    "盒马": "客户",
    "海底捞": "客户",
    "_updated": "",
}

# 去掉含客户/公司标识的括号 (e.g. "墨鱼圈 (永辉超市)" 的整段, "...(上海海壹佰米网络科技有限公司)").
_CUSTOMER_PAREN = re.compile(r"\s*[（(][^（）()]*(超市|有限公司|客户|科技|测试)[^（）()]*[）)]")


def _seed(value: str) -> int:
    return int(hashlib.sha256(value.encode("utf-8")).hexdigest()[:12], 16)


class Masker:
    def __init__(self):
        self._cache: dict[tuple[str, str], str] = {}
        self._counters: dict[str, int] = {}

    def _unique(self, kind, value, fmt):
        """Like _det but guarantees DISTINCT outputs for distinct inputs (for UNIQUE-constrained
        columns like dim_store.name): assign a sequential counter per new source value."""
        if value is None:
            return None
        key = (kind, str(value))
        if key not in self._cache:
            n = self._counters.get(kind, 0) + 1
            self._counters[kind] = n
            self._cache[key] = fmt(n)
        return self._cache[key]

    def _det(self, kind: str, value, fn) -> str:
        if value is None:
            return None
        key = (kind, str(value))
        if key not in self._cache:
            fk = Faker("zh_CN")
            fk.seed_instance(_seed(f"{kind}:{value}"))
            self._cache[key] = fn(fk)
        return self._cache[key]

    def person(self, v):   return self._det("person", v, lambda f: f.name())
    def company(self, v):  return self._det("company", v, lambda f: f.company())
    def store(self, v):    return self._unique("store", v, lambda n: f"示范门店{n:02d}")
    def phone(self, v):    return self._det("phone", v, lambda f: f.phone_number().replace("-", "")[:11].ljust(11, "0"))
    def address(self, v):  return self._det("address", v, lambda f: f.address().replace("\n", " "))
    def email(self, v):    return self._det("email", v, lambda f: f.email())
    def idnum(self, v):    return self._det("idnum", v, lambda f: f.numerify("##############"))

    def cuisine(self, v):
        """Name/code/remark columns that can carry brands: substitute every brand/cruft token
        (青花椒->藤椒, client brands removed/->客户, test cruft cleaned) and strip customer
        parentheticals. Keeps the dish/remark readable while removing all client identity."""
        if v is None:
            return None
        s = str(v)
        for tok, sub in BRAND_SUBSTITUTE.items():
            s = s.replace(tok, sub)
        s = _CUSTOMER_PAREN.sub("", s)
        return s.strip()

    def freetext(self, v):
        """Scrub free-text remark/notes: if it contains any brand/real token, replace whole field."""
        if v is None:
            return None
        s = str(v)
        if any(tok in s for tok in BRAND_TOKENS):
            return "(示例备注)"
        return s  # otherwise keep (operational note, no identity)
