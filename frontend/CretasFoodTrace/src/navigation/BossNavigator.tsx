import React from 'react';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { Icon } from 'react-native-paper';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import BossOverviewScreen from '../screens/factory-admin/home/BossOverviewScreen';
import ProductionPlanManagementScreen from '../screens/processing/ProductionPlanManagementScreen';
import SalesOrderListScreen from '../screens/factory-admin/inventory/SalesOrderListScreen';
import SalesOrderDetailScreen from '../screens/factory-admin/inventory/SalesOrderDetailScreen';
import PurchaseOrderListScreen from '../screens/factory-admin/inventory/PurchaseOrderListScreen';
import PurchaseOrderDetailScreen from '../screens/factory-admin/inventory/PurchaseOrderDetailScreen';
import FinishedGoodsListScreen from '../screens/factory-admin/inventory/FinishedGoodsListScreen';
import SmartBIStackNavigator from './SmartBIStackNavigator';
import MobileAccountScreen from '../screens/common/MobileAccountScreen';
import { useFactoryFeatureStore } from '../store/factoryFeatureStore';

const Tab = createBottomTabNavigator();
const OverviewStack = createNativeStackNavigator();

function BossOverviewStack() {
  return (
    <OverviewStack.Navigator screenOptions={{ headerShown: false }}>
      <OverviewStack.Screen name="BossOverview" component={BossOverviewScreen} />
      <OverviewStack.Screen name="BossProductionPlans" component={ProductionPlanManagementScreen} />
      <OverviewStack.Screen name="BossSalesOrders" component={SalesOrderListScreen} />
      <OverviewStack.Screen name="SalesOrderDetail" component={SalesOrderDetailScreen} />
      <OverviewStack.Screen name="BossPurchaseOrders" component={PurchaseOrderListScreen} />
      <OverviewStack.Screen name="PurchaseOrderDetail" component={PurchaseOrderDetailScreen} />
      <OverviewStack.Screen name="BossFinishedGoods" component={FinishedGoodsListScreen} />
    </OverviewStack.Navigator>
  );
}

export default function BossNavigator() {
  const insets = useSafeAreaInsets();
  const smartBiEnabled = useFactoryFeatureStore((state) => state.isScreenEnabled('SmartBI'));

  return (
    <Tab.Navigator
      initialRouteName="BossOverviewTab"
      screenOptions={{
        headerShown: false,
        tabBarActiveTintColor: '#174EA6',
        tabBarInactiveTintColor: '#757575',
        tabBarStyle: { height: 60 + insets.bottom, paddingTop: 5, paddingBottom: insets.bottom + 5 },
      }}
    >
      <Tab.Screen
        name="BossOverviewTab"
        component={BossOverviewStack}
        options={{ title: '总览', tabBarIcon: ({ color, size }) => <Icon source="view-dashboard-outline" color={color} size={size} /> }}
      />
      {/*
        2026-08-15 移除「审批」tab —— 它对工厂超管**永远是空的**。

        后端 MyTodoAggregatorServiceImpl.ROLE_TYPES 只有 finance_manager / cashier 两个 key,
        factory_super_admin 不在其中 → getOrDefault 返空集 → 空列表。
        而这个角色在权限矩阵里是全模块 read_write, **过得了** MyTodoController 上的
        @RequirePermission({"finance:read", ...}) —— 所以不是 403, 是 **HTTP 200 + 空列表**,
        界面显示「暂无待办」, 长得像「你没有待办」而不是「这里查不到你的待办」。

        prod 实测 (2026-08-15) 还查到更根本的一层: approval_workflow_instances **0 行**,
        approval_history **0 行** —— 61 条审批链只是**配置**, 一条实例都没产生过。
        配了采购审批链的两家 (F006 / LIUSHANMEN) **从来没有过任何采购单**(含软删除计 0),
        而有采购单的三家一条链都没配。所以就算把角色加进 ROLE_TYPES, 这个 tab 依然是空的。

        ⇒ 留一个永远空的入口比没有入口更糟, 先摘掉。
        OA 待办中心当前定位: **财务/出纳专用**(MainNavigator / FinanceNavigator 已按角色守住)。
        采购审批走 web-admin 或 OA 审批中心 (POST /workflow/instances/{id}/actions)。
      */}
      {smartBiEnabled && (
        <Tab.Screen
          name="BossAnalysisTab"
          component={SmartBIStackNavigator}
          options={{ title: '分析', tabBarIcon: ({ color, size }) => <Icon source="chart-line" color={color} size={size} /> }}
        />
      )}
      <Tab.Screen
        name="BossProfileTab"
        component={MobileAccountScreen}
        options={{ title: '我的', tabBarIcon: ({ color, size }) => <Icon source="account-outline" color={color} size={size} /> }}
      />
    </Tab.Navigator>
  );
}
