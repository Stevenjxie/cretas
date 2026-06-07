# 角色 × 屏幕 测试矩阵

## 优先级说明

| 优先级 | 含义 | PoC 阶段 |
|--------|------|---------|
| P0 | 所有角色必经路径 | ✅ 必须覆盖 |
| P1 | 核心业务路径 | ✅ 必须覆盖 |
| P2 | 重要功能 | 短期覆盖 |
| P3 | 次要功能 | 长期覆盖 |

## P0: 通用路径（所有角色）

| 屏幕 | 文件 | 测试内容 | Flow |
|------|------|---------|------|
| 登录 (Landing) | `auth/EnhancedLoginScreen.tsx` | Landing 视图加载、点击进入登录 | `_login.yaml` |
| 登录 (Form) | 同上（同组件状态切换） | 填写凭证、提交、等待首页 | `_login.yaml` |
| 退出 | 个人中心 | 点击退出、确认、回到登录页 | `_logout.yaml` |

## P1: 工厂管理员 (factory_admin)

| 屏幕 | 文件 | 测试内容 | 优先级 |
|------|------|---------|--------|
| 首页仪表盘 | `factory-admin/home/FAHomeScreen.tsx` | 核心卡片可见性（SmartBI/生产/库存/质量） | P1 |
| SmartBI 入口 | `smartbi/SmartBIHomeScreen.tsx` | 14 个分析入口卡片加载 | P1 |
| 经营驾驶舱 | `smartbi/ExecutiveDashboardScreen.tsx` | KPI 数据加载 | P2 |
| 今日生产 | `factory-admin/home/TodayProductionScreen.tsx` | 生产数据可见 | P2 |
| 采购订单列表 | `factory-admin/inventory/PurchaseOrderListScreen.tsx` | 列表加载 | P2 |
| 销售订单列表 | `factory-admin/inventory/SalesOrderListScreen.tsx` | 列表加载 | P3 |
| 原材料批次 | `factory-admin/home/MaterialBatchScreen.tsx` | 批次列表 | P3 |

## P1: 调度员 (dispatcher)

| 屏幕 | 文件 | 测试内容 | 优先级 |
|------|------|---------|--------|
| 首页 | `dispatcher/home/DSHomeScreen.tsx` | 核心卡片可见性 | P1 |
| 计划列表 | `dispatcher/plan/PlanListScreen.tsx` | 列表加载、筛选 | P1 |
| 创建计划 | `dispatcher/plan/PlanCreateScreen.tsx` | 表单填写、提交 | P1 |
| 计划详情 | `dispatcher/plan/PlanDetailScreen.tsx` | 详情加载 | P2 |

## P1: 质检员 (quality_inspector)

| 屏幕 | 文件 | 测试内容 | 优先级 |
|------|------|---------|--------|
| 首页 | `quality-inspector/QIHomeScreen.tsx` | 核心卡片可见性 | P1 |
| 扫码页面 | `quality-inspector/QIScanScreen.tsx` | 扫码界面加载 | P2 |
| 检验表单 | `quality-inspector/QIFormScreen.tsx` | 表单填写 | P2 |

## P1: 仓储主管 (warehouse_mgr)

| 屏幕 | 文件 | 测试内容 | 优先级 |
|------|------|---------|--------|
| 首页 | `warehouse/home/WHHomeScreen.tsx` | 核心卡片可见性 | P1 |
| 入库列表 | `warehouse/inbound/WHInboundListScreen.tsx` | 列表加载 | P2 |
| 出库列表 | `warehouse/outbound/WHOutboundListScreen.tsx` | 列表加载 | P2 |

## P2: HR 管理员 (hr_admin)

| 屏幕 | 文件 | 测试内容 | 优先级 |
|------|------|---------|--------|
| 首页 | `hr/HRHomeScreen.tsx` | 核心功能可见性 | P2 |

## P2: 车间主管 (workshop_sup)

| 屏幕 | 文件 | 测试内容 | 优先级 |
|------|------|---------|--------|
| 首页 | `workshop/WSHomeScreen.tsx` | 核心功能可见性 | P2 |

## P2: 平台管理员 (platform_admin)

| 屏幕 | 文件 | 测试内容 | 优先级 |
|------|------|---------|--------|
| 首页 | 默认 MainNavigator | 工厂列表可见 | P2 |

## 角色 Navigator 映射

| 角色 | userRole 值 | Navigator | 首页 testID |
|------|------------|-----------|------------|
| 工厂管理员 | `factory_super_admin` | FactoryAdminNavigator | `fa-home-root` |
| 平台管理员 | `platform_admin` | MainNavigator | `pa-home-root` |
| 车间主管 | `workshop_supervisor` | WorkshopSupervisorNavigator | `ws-home-root` |
| 仓储主管 | `warehouse_manager` | WarehouseManagerNavigator | `wh-home-root` |
| HR 管理员 | `hr_admin` | HRNavigator | `hr-home-root` |
| 调度员 | `dispatcher` | DispatcherNavigator | `ds-home-root` |
| 质检员 | `quality_inspector` | QualityInspectorNavigator | `qi-home-root` |
| 操作员 | `operator` | OperatorNavigator | `op-home-root` |

## PoC 阶段覆盖范围

**目标**: 覆盖至少一条完整业务流 — 登录 → 导航 → 操作 → 验证

**选定路径**: factory_admin 登录 → 首页 → SmartBI → 经营驾驶舱

| 步骤 | 屏幕 | testID 数量 |
|------|------|------------|
| 1. 登录 | EnhancedLoginScreen | 7 个 |
| 2. 首页 | FAHomeScreen | 6 个 |
| 3. SmartBI | SmartBIHomeScreen | 4 个 |
| 4. 驾驶舱 | ExecutiveDashboardScreen | 3 个 |
| **合计** | **4 屏幕** | **~20 个 testID** |
