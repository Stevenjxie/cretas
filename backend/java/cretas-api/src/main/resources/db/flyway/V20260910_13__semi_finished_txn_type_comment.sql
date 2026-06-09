-- SP2: 补充 semi_finished_inventory_transactions.txn_type 注释 (无 DDL 变更)
-- SECONDARY_CONSUME 在 SP1 已由 Java enum 定义, 此处仅补文档
COMMENT ON COLUMN semi_finished_inventory_transactions.txn_type IS
    'IN=生产产出入库 | OUT=同单续工序领用 | SECONDARY_CONSUME=跨单二次加工领用 | REVERSE=撤回 | ADJUST=盘点调整';
