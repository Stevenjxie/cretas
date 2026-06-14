"""Clone engine configuration: connections, id strategy, table + mask registries."""
import os

# --- Connections ---
CRETAS_DSN = os.environ.get(
    "CLONE_CRETAS_DSN",
    "postgresql://cretas_user:cretas123@127.0.0.1:5432/cretas_prod_db",
)
# smartbi needs BYPASSRLS (FORCE RLS on dim/fact). Prefer postgres superuser DSN.
SMARTBI_SUPER_DSN = os.environ.get("CLONE_SMARTBI_SUPER_DSN")  # required for smartbi clone

# --- Source -> Target tenants ---
TENANTS = {
    "rest":    {"source": "RES_3101_009", "target": "DEMO_REST",    "name": "白垩纪示范餐厅", "type": "RESTAURANT"},
    "factory": {"source": "F001",         "target": "DEMO_FACTORY", "name": "白垩纪示范食品厂", "type": "FACTORY"},
}

# --- Bigint-serial ID offsets (collision-safe: >> any plausible future growth) ---
# cretas_prod_db max serial ~32k (smart_bi_finance_data); smartbi max ~3.8M (fact_pos_item).
OFFSET_CRETAS = 500_000_000
OFFSET_SMARTBI = 1_000_000_000

# Short codes for generated varchar PKs (kept within varchar(64) limit).
TARGET_SHORTCODE = {"DEMO_REST": "DR", "DEMO_FACTORY": "DF", "DEMO_REST_SCRATCH": "DRS"}

# TABLE_REGISTRY + MASK_REGISTRY filled in Task 4.
TABLE_REGISTRY = []   # ordered parent->child; see Task 4
MASK_REGISTRY = {}    # table -> {column: masker_name}; see Task 4
