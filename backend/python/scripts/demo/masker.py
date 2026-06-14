"""Deterministic zh_CN identity masking. Same source value -> same fake value,
so relationships stay consistent across rows. Numbers/dates are NOT handled here
(the clone engine only routes identity columns through the masker)."""
import hashlib
from faker import Faker

# Tokens that identify the real source brand/clients — scrubbed from free text.
BRAND_TOKENS = ["青花椒"]  # extend with any real client/brand strings found during rehearsal

# Brand token -> neutral cuisine substitute. For dish/ingredient/menu names we replace the brand
# word (which is also a Sichuan spice) with a neutral pepper, keeping the dish authentic-looking
# (青花椒鱼 -> 藤椒鱼) while removing the brand from menus everywhere.
BRAND_SUBSTITUTE = {"青花椒": "藤椒"}


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
        """Dish/ingredient/menu names: substitute brand tokens with a neutral cuisine word
        (青花椒 -> 藤椒). Keeps the dish authentic while removing the brand from menus."""
        if v is None:
            return None
        s = str(v)
        for tok, sub in BRAND_SUBSTITUTE.items():
            s = s.replace(tok, sub)
        return s

    def freetext(self, v):
        """Scrub free-text remark/notes: if it contains any brand/real token, replace whole field."""
        if v is None:
            return None
        s = str(v)
        if any(tok in s for tok in BRAND_TOKENS):
            return "(示例备注)"
        return s  # otherwise keep (operational note, no identity)
