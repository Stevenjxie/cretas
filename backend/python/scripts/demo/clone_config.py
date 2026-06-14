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
    "rest":    {"source": "RES_3101_009", "target": "DEMO_REST",    "name": "白垩纪AI示范餐厅", "type": "RESTAURANT"},
    "factory": {"source": "F001",         "target": "DEMO_FACTORY", "name": "白垩纪AI示范食品厂", "type": "FACTORY"},
}

# --- Bigint-serial ID offsets (collision-safe: >> any plausible future growth) ---
# cretas_prod_db max serial ~32k (smart_bi_finance_data); smartbi max ~3.8M (fact_pos_item).
OFFSET_CRETAS = 500_000_000
OFFSET_SMARTBI = 1_000_000_000

# Short codes for generated varchar PKs (kept within varchar(64) limit).
TARGET_SHORTCODE = {"DEMO_REST": "DR", "DEMO_FACTORY": "DF", "DEMO_REST_SCRATCH": "DRS"}

# pk_type: "varchar" -> new short id via map; "bigint" -> offset.
# fk: column -> parent table (rewrite via that parent's map).
TABLE_REGISTRY = [
  # --- cretas_prod_db (parent -> child) ---
  {"db":"cretas","table":"factory_warehouses","pk":"id","pk_type":"varchar","factory_col":"factory_id","fk":{}},
  {"db":"cretas","table":"users","pk":"id","pk_type":"bigint","factory_col":"factory_id","fk":{},
     "rename_username":True},  # username is GLOBALLY unique -> prefix in masker step
  {"db":"cretas","table":"suppliers","pk":"id","pk_type":"varchar","factory_col":"factory_id",
     "fk":{"created_by":"users"}},
  {"db":"cretas","table":"customers","pk":"id","pk_type":"varchar","factory_col":"factory_id",
     "fk":{"created_by":"users"}},
  {"db":"cretas","table":"raw_material_types","pk":"id","pk_type":"varchar","factory_col":"factory_id",
     "fk":{"created_by":"users"}},
  {"db":"cretas","table":"product_types","pk":"id","pk_type":"varchar","factory_col":"factory_id",
     "fk":{"created_by":"users"}},
  {"db":"cretas","table":"bom_recipes","pk":"id","pk_type":"varchar","factory_col":"factory_id",
     "fk":{"product_type_id":"product_types"}},
  {"db":"cretas","table":"bom_recipe_items","pk":"id","pk_type":"bigint","factory_col":"factory_id",
     "fk":{"recipe_id":"bom_recipes","material_type_id":"raw_material_types"}},
  {"db":"cretas","table":"bom_versions","pk":"id","pk_type":"varchar","factory_col":"factory_id",
     "fk":{"bom_recipe_id":"bom_recipes"}},
  {"db":"cretas","table":"recipes","pk":"id","pk_type":"varchar","factory_col":"factory_id",
     "fk":{"product_type_id":"product_types","raw_material_type_id":"raw_material_types","created_by":"users"}},  # restaurant recipe lines (qhj 383)
  {"db":"cretas","table":"material_batches","pk":"id","pk_type":"varchar","factory_col":"factory_id",
     "fk":{"supplier_id":"suppliers","warehouse_id":"factory_warehouses","created_by":"users",
           "material_type_id":"raw_material_types"}},
  {"db":"cretas","table":"sales_orders","pk":"id","pk_type":"varchar","factory_col":"factory_id",
     "fk":{"customer_id":"customers","created_by":"users","salesperson_id":"users"}},
  {"db":"cretas","table":"sales_order_items","pk":"id","pk_type":"bigint","factory_col":None,
     "fk":{"sales_order_id":"sales_orders","product_type_id":"product_types"},
     "parent_filter":("sales_order_id","sales_orders")},  # no factory_id -> filter by cloned parent (Gap 2)
  {"db":"cretas","table":"purchase_orders","pk":"id","pk_type":"varchar","factory_col":"factory_id",
     "fk":{"supplier_id":"suppliers","created_by":"users"}},
  {"db":"cretas","table":"purchase_order_items","pk":"id","pk_type":"bigint","factory_col":None,
     "fk":{"purchase_order_id":"purchase_orders","material_type_id":"raw_material_types"},
     "parent_filter":("purchase_order_id","purchase_orders")},  # no factory_id -> filter by cloned parent (Gap 2)
  {"db":"cretas","table":"production_plans","pk":"id","pk_type":"varchar","factory_col":"factory_id",
     "fk":{"product_type_id":"product_types","created_by":"users"}},
  {"db":"cretas","table":"production_batches","pk":"id","pk_type":"bigint","factory_col":"factory_id",
     "fk":{"product_type_id":"product_types"}},  # production_plan_id NOT FK-constrained; remap if present (see Step 3)
  {"db":"cretas","table":"finished_goods_batches","pk":"id","pk_type":"varchar","factory_col":"factory_id",
     "fk":{"warehouse_id":"factory_warehouses","product_type_id":"product_types"}},
  {"db":"cretas","table":"shipment_records","pk":"id","pk_type":"varchar","factory_col":"factory_id",
     "fk":{"customer_id":"customers","recorded_by":"users"}},
  {"db":"cretas","table":"material_requisitions","pk":"id","pk_type":"varchar","factory_col":"factory_id","fk":{}},
  {"db":"cretas","table":"wastage_records","pk":"id","pk_type":"varchar","factory_col":"factory_id",
     "fk":{"raw_material_type_id":"raw_material_types"}},
  {"db":"cretas","table":"stocktaking_records","pk":"id","pk_type":"varchar","factory_col":"factory_id",
     "fk":{"raw_material_type_id":"raw_material_types"}},
  {"db":"cretas","table":"return_orders","pk":"id","pk_type":"varchar","factory_col":"factory_id","fk":{}},
  {"db":"cretas","table":"smart_bi_sales_data","pk":"id","pk_type":"bigint","factory_col":"factory_id","fk":{}},
  {"db":"cretas","table":"smart_bi_finance_data","pk":"id","pk_type":"bigint","factory_col":"factory_id","fk":{}},
  {"db":"cretas","table":"smart_bi_department_data","pk":"id","pk_type":"bigint","factory_col":"factory_id","fk":{}},
  # --- smartbi_prod_db (parent -> child) ---
  {"db":"smartbi","table":"dim_store","pk":"store_id","pk_type":"bigint","factory_col":"factory_id","fk":{}},
  {"db":"smartbi","table":"dim_product","pk":"product_id","pk_type":"bigint","factory_col":"factory_id","fk":{}},
  {"db":"smartbi","table":"dim_payment_channel","pk":"channel_id","pk_type":"bigint","factory_col":"factory_id","fk":{}},
  {"db":"smartbi","table":"dim_discount","pk":"discount_id","pk_type":"bigint","factory_col":"factory_id","fk":{}},
  {"db":"smartbi","table":"dim_ingredient","pk":"ingredient_id","pk_type":"bigint","factory_col":"factory_id","fk":{}},
  {"db":"smartbi","table":"fact_pos_transaction","pk":"id","pk_type":"bigint","factory_col":"factory_id",
     "fk":{"store_id":"dim_store"}},  # staff_id -> dim_staff: see Step 3 (dim_staff not in clone set -> null it)
  {"db":"smartbi","table":"fact_pos_item","pk":"id","pk_type":"bigint","factory_col":"factory_id",
     "fk":{"transaction_id":"fact_pos_transaction","product_id":"dim_product"}},
  {"db":"smartbi","table":"fact_pos_payment","pk":"id","pk_type":"bigint","factory_col":"factory_id",
     "fk":{"transaction_id":"fact_pos_transaction","channel_id":"dim_payment_channel"}},
  {"db":"smartbi","table":"fact_pos_discount","pk":"id","pk_type":"bigint","factory_col":"factory_id",
     "fk":{"transaction_id":"fact_pos_transaction","discount_id":"dim_discount"}},
]

MASK_REGISTRY = {
  "factories": {"name":"company","contact_name":"person","contact_phone":"phone","contact_email":"email","address":"address"},
  "factory_settings": {"contact_email":"email","contactemail":"email","contact_phone":"phone","contactphone":"phone",
                       "factory_address":"address","factoryaddress":"address","factory_name":"company","factoryname":"company"},
  "users": {"full_name":"person","phone":"phone","email":"email","position":"freetext","department":"freetext","avatar_url":"freetext"},
  "suppliers": {"name":"company","contact_name":"person","contact_person":"person","contact_phone":"phone",
                "contact_email":"email","email":"email","phone":"phone","address":"address","tax_number":"idnum",
                "bank_account":"idnum","bank_name":"company","notes":"freetext","rating_notes":"freetext"},
  "customers": {"name":"company","contact_name":"person","contact_person":"person","contact_phone":"phone",
                "contact_email":"email","email":"email","phone":"phone","billing_address":"address",
                "shipping_address":"address","tax_number":"idnum","bank_name":"company","bank_account":"idnum",
                "notes":"freetext","rating_notes":"freetext"},
  "raw_material_types": {"notes":"freetext"},
  "recipes": {"notes":"freetext"},
  "product_types": {"related_customer":"company","notes":"freetext","brand":"company"},
  "bom_recipes": {"notes":"freetext"}, "bom_recipe_items": {"remark":"freetext"}, "bom_versions": {"rejection_reason":"freetext"},
  "material_batches": {"notes":"freetext","origin_place":"freetext","factory_number":"freetext"},
  "sales_orders": {"delivery_address":"address","remark":"freetext","salesperson":"person",
                   "finance_review_notes":"freetext","external_order_title":"freetext"},
  "sales_order_items": {"remark":"freetext","dest_warehouse_name":"freetext"},
  "purchase_orders": {"remark":"freetext","finance_review_notes":"freetext","contract_number":"idnum"},
  "purchase_order_items": {"remark":"freetext"},
  "production_plans": {"notes":"freetext","source_customer_name":"company","approval_comment":"freetext","process_name":"freetext"},
  "production_batches": {"notes":"freetext","supervisor_name":"person","equipment_name":"freetext"},
  "finished_goods_batches": {"remark":"freetext","inbound_remark":"freetext","storage_location":"freetext"},
  "shipment_records": {"delivery_address":"address","notes":"freetext","driver_name":"person",
                       "driver_phone":"phone","vehicle_number":"freetext","logistics_company":"company"},
  "material_requisitions": {"notes":"freetext"},
  "wastage_records": {"reason":"freetext","notes":"freetext"},
  "stocktaking_records": {"adjustment_reason":"freetext","notes":"freetext"},
  "return_orders": {"reason":"freetext","remark":"freetext"},
  "smart_bi_sales_data": {"customer_name":"company","salesperson_name":"person","city":"freetext","province":"freetext","region":"freetext"},
  "smart_bi_finance_data": {"customer_name":"company","supplier_name":"company"},
  "smart_bi_department_data": {"manager_name":"person"},
  "dim_store": {"name":"store","brand":"company"},
  # dim_product.name NOT masked: dish names (e.g. 青花椒鱼) are generic Sichuan cuisine vocabulary,
  # not client identity. Brand identity is removed from store/company/contact fields instead.
  "dim_discount": {"name":"freetext"},
  "fact_pos_item": {"source_item_raw":"freetext"},
}

# RUNTIME CAVEATS (engine handles; verify during rehearsal):
# 1. production_batches.production_plan_id is NOT FK-constrained but should be remapped to the
#    production_plans map if the column exists and is non-null. Add to fk dynamically if column present.
# 2. fact_pos_transaction.staff_id references dim_staff (NOT in clone set) -> set to NULL on clone.
# 3. return_orders.counterparty_id is polymorphic (customer OR supplier) -> remap by trying customers
#    map then suppliers map; if neither, keep (will be a dangling ref but column not FK-constrained).
# 4. sales_order_items / purchase_order_items have factory_col=None -> if the column exists, set it to
#    target from the parent; the engine inherits factory_id from the parent row.
# 5. Any column NOT in MASK_REGISTRY and NOT a PK/FK/factory_col is COPIED VERBATIM (numbers/dates/status).
