"""Allocate new IDs for cloned rows and remember old->new for FK rewrite.

- bigint serial PKs: new = old + offset (offset >> any plausible growth -> collision-safe).
- varchar PKs: new = '{shortcode}_{table_abbrev}{counter}', stable per run, <=64 chars.
Both strategies record old->new in a per-table map so FK columns can be rewritten.
"""


class IdRemapper:
    def __init__(self, offset: int, shortcode: str):
        self.offset = offset
        self.shortcode = shortcode
        self._maps: dict[str, dict] = {}     # table -> {old_id: new_id}
        self._counters: dict[str, int] = {}  # table -> next counter (varchar)

    def _map(self, table: str) -> dict:
        return self._maps.setdefault(table, {})

    def new_bigint(self, table: str, old_id: int) -> int:
        m = self._map(table)
        if old_id not in m:
            m[old_id] = old_id + self.offset
        return m[old_id]

    def new_varchar(self, table: str, old_id: str) -> str:
        m = self._map(table)
        if old_id not in m:
            n = self._counters.get(table, 0) + 1
            self._counters[table] = n
            abbr = "".join(w[0] for w in table.split("_"))[:6]  # e.g. sales_orders -> 'so'
            m[old_id] = f"{self.shortcode}_{abbr}{n}"
        return m[old_id]

    def lookup(self, table: str, old_id):
        return self._map(table).get(old_id)

    def cloned_old_ids(self, table: str) -> list:
        """Old (source) PK values already cloned for `table` — used to filter child tables
        that lack a factory_id column (clone only children of cloned parents)."""
        return list(self._map(table).keys())

    def drop(self, table: str, old_id):
        """Remove a mapping when an orphan row is dropped, so child rows referencing it resolve
        to None and get dropped too (cascade)."""
        self._map(table).pop(old_id, None)
