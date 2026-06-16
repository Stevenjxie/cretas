-- 修复: 仓库员 (warehouse_worker) 扫码收货 403
--
-- 背景 (2026-06-17, F006 六膳门实测复现):
--   仓管员扫 PO 二维码入库时, GET /purchase/orders/by-number/{orderNumber} 返回 403:
--   "您的角色 [仓库员] 缺少 采购管理 或 仓储管理 模块的 [读写 / 读取] 权限 (需任一)"
--
-- 根因 (权限解析三层 L2→L1→matrix, L1 platform_role_permissions 为权威源):
--   warehouse_worker.warehouse = 'w' (write-only)。
--   checkAction("warehouse:read", "w") = "w".contains("read") = false;
--   checkAction("warehouse:read_write", "w") = "w".equals("read_write") = false。
--   → 即便 PR #961 已让扫码端点接受 warehouse:read, 仓库员的 'w' 权限也无法满足 read。
--   这与 Issue #812 (quality_inspector quality 'write'→'read_write') 是同一 bug 类:
--   一个角色对它本职模块只有 write 没有 read → 所有 GET (read/read_write) 端点全 403。
--   #812 当时改的是硬编码 PERMISSION_MATRIX, 对 warehouse_worker 无效, 因为本角色在
--   L1 DB 有实际行 ('w'), matrix fallback 永远不会被命中 → 必须改 DB。
--
-- 修复: 仓库员对 warehouse + inventory 两个本职模块授予 'rw' (读写)。
--   - warehouse 'w'→'rw': 满足扫码端点的 warehouse:read → 扫码收货全链路打通
--     (后续 POST /purchase/receives + /confirm 已走 inventory:write, 仓库员本就有)。
--   - inventory  'w'→'rw': 仓库员 出库/盘点/查库存 等本职操作需要读库存, 同一 bug 类一并修。
--   procurement 保持 '-' (仓库员经 warehouse 模块访问收货, 不需要采购模块列表权限,
--     与 #961 的设计意图一致: 扫码端点接受 warehouse:read 而非给仓库员 procurement)。
--
-- 影响范围: platform_role_permissions 是平台全局默认 (L1), 改动作用于所有工厂的
--   warehouse_worker 角色 —— 这是一个全局正确性修复 (任何工厂的仓库员都应能读本职模块),
--   非 F006 专属。同因排查 (write-only on warehouse/procurement/inventory) 仅命中本角色。

UPDATE platform_role_permissions
   SET permission_level = 'rw',
       updated_at = NOW()
 WHERE role_code = 'warehouse_worker'
   AND module_code IN ('warehouse', 'inventory')
   AND permission_level = 'w'
   AND deleted_at IS NULL;

-- 若历史上某工厂在 L2 (factory_module_configs.role_module_override) 写死了 warehouse_worker
-- 的 warehouse/inventory = 'w', 该 override 会覆盖本 L1 修复。F006 实测无此 override,
-- 其他工厂如出现同症状需单独清理对应 L2 override (本迁移不动 L2, 避免误伤工厂自定义配置)。
