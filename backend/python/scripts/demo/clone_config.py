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
# factory source switched F001 -> F006 (2026-06-14): F001 was a mixed test factory whose gold/POS
# layer carried restaurant-flavored data (门店/美团/dishes) and whose finance + production-reporting
# tables were empty. F006 (六扇门 卤味加工厂) is a genuine manufacturing tenant with 365 production
# reports / 143 work-process tasks / 104 batches / 133 plans / 30 finished goods → the 报工/工序/人效
# modules light up. Cockpit agg_daily is generated post-clone from sales_orders (F006 has no POS).
TENANTS = {
    "rest":    {"source": "RES_3101_009", "target": "DEMO_REST",     "name": "白垩纪AI示范餐厅",   "type": "RESTAURANT"},
    "factory": {"source": "F006",         "target": "DEMO_FACTORY2",  "name": "白垩纪AI示范食品厂", "type": "FACTORY"},
}

# --- Bigint-serial ID offsets (collision-safe: >> any plausible future growth) ---
# cretas_prod_db max serial ~32k (smart_bi_finance_data); smartbi max ~3.8M (fact_pos_item).
OFFSET_CRETAS = 500_000_000
OFFSET_SMARTBI = 1_000_000_000

# Short codes for generated varchar PKs (kept within varchar(64) limit).
TARGET_SHORTCODE = {"DEMO_REST": "DR", "DEMO_FACTORY": "DF", "DEMO_FACTORY2": "DF2", "DEMO_REST_SCRATCH": "DRS"}

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
  # --- F006 manufacturing: work-process catalog + per-product process + tasks + reports (报工/工序/人效) ---
  {"db":"cretas","table":"work_processes","pk":"id","pk_type":"varchar","factory_col":"factory_id","fk":{}},
  {"db":"cretas","table":"product_work_processes","pk":"id","pk_type":"bigint","factory_col":"factory_id",
     "fk":{"product_type_id":"product_types","work_process_id":"work_processes","responsible_worker_id":"users"}},
  {"db":"cretas","table":"work_process_tasks","pk":"id","pk_type":"bigint","factory_col":"factory_id",
     "fk":{"production_batch_id":"production_batches","product_work_process_id":"product_work_processes",
           "work_process_id":"work_processes","product_type_id":"product_types",
           "assigned_to":"users","completed_by":"users"}},
  {"db":"cretas","table":"production_reports","pk":"id","pk_type":"bigint","factory_col":"factory_id",
     "fk":{"batch_id":"production_batches","work_process_task_id":"work_process_tasks",
           "product_type_id":"product_types","worker_id":"users","approved_by":"users",
           "reversal_of_id":"production_reports"}},  # self-ref: resolves to cloned report or nulls (safe)
  {"db":"cretas","table":"product_work_process_assignees","pk":"id","pk_type":"bigint","factory_col":None,
     "fk":{"product_work_process_id":"product_work_processes","worker_id":"users"},
     "parent_filter":("product_work_process_id","product_work_processes")},  # no factory_id
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
  # NOTE: dim_ingredient is NOT cloned — restaurant_ops_etl regenerates it from raw_material_types
  # (cloning it collides with the ETL's upsert on uq_dim_ingredient_factory_normname).
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
                "bank_account":"idnum","bank_name":"company","notes":"freetext","rating_notes":"freetext",
                "supplied_materials":"cuisine"},
  "customers": {"name":"company","contact_name":"person","contact_person":"person","contact_phone":"phone",
                "contact_email":"email","email":"email","phone":"phone","billing_address":"address",
                "shipping_address":"address","tax_number":"idnum","bank_name":"company","bank_account":"idnum",
                "notes":"freetext","rating_notes":"freetext"},
  "raw_material_types": {"name":"cuisine","notes":"freetext"},
  "recipes": {"notes":"freetext"},
  "product_types": {"name":"cuisine","base_product_name":"cuisine","code":"cuisine","product_category":"cuisine",
                    "specification":"cuisine","package_spec":"cuisine","related_customer":"company","notes":"cuisine","brand":"cuisine"},
  "bom_recipes": {"notes":"freetext"}, "bom_recipe_items": {"remark":"freetext"}, "bom_versions": {"rejection_reason":"freetext"},
  "material_batches": {"notes":"freetext","origin_place":"freetext","factory_number":"freetext"},
  "sales_orders": {"delivery_address":"address","remark":"cuisine","salesperson":"person",
                   "finance_review_notes":"freetext","external_order_title":"cuisine"},
  # product_name 是反范式产品名副本 (工作台/排产读它), 必须脱敏 (历史泄露源).
  "sales_order_items": {"product_name":"cuisine","remark":"cuisine","dest_warehouse_name":"freetext"},
  "purchase_orders": {"remark":"freetext","finance_review_notes":"freetext","contract_number":"idnum"},
  "purchase_order_items": {"material_name":"cuisine","remark":"freetext"},
  "production_plans": {"notes":"cuisine","source_customer_name":"company","approval_comment":"freetext","process_name":"freetext"},
  "production_batches": {"product_name":"cuisine","notes":"cuisine","supervisor_name":"person","equipment_name":"freetext"},
  # F006 manufacturing tables: process_name (油炸/焯水) is generic, keep; mask identity + product-name copies.
  "work_processes": {"description":"freetext"},
  "work_process_tasks": {"notes":"freetext"},
  "production_reports": {"product_name":"cuisine","reporter_name":"person","notes":"freetext","rejection_reason":"freetext","rejected_reason":"freetext"},
  "finished_goods_batches": {"product_name":"cuisine","remark":"cuisine","inbound_remark":"cuisine","storage_location":"freetext"},
  "shipment_records": {"product_name":"cuisine","delivery_address":"address","notes":"cuisine","driver_name":"person",
                       "driver_phone":"phone","vehicle_number":"freetext","logistics_company":"company"},
  "material_requisitions": {"notes":"freetext"},
  "wastage_records": {"reason":"freetext","notes":"freetext"},
  "stocktaking_records": {"adjustment_reason":"freetext","notes":"freetext"},
  "return_orders": {"reason":"freetext","remark":"freetext"},
  "smart_bi_sales_data": {"customer_name":"company","salesperson_name":"person","city":"freetext","province":"freetext","region":"freetext"},
  "smart_bi_finance_data": {"customer_name":"company","supplier_name":"company"},
  "smart_bi_department_data": {"manager_name":"person"},
  "dim_store": {"name":"store","brand":"company"},
  # dish names: substitute brand token (青花椒 -> 藤椒) so menus carry no brand, stay authentic.
  "dim_product": {"name":"cuisine","normalized_name":"cuisine"},
  "dim_discount": {"name":"freetext"},
  "fact_pos_item": {"source_item_raw":"cuisine"},
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
