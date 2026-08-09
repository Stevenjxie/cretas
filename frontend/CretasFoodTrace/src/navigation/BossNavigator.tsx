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
import OATodoStackNavigator from './OATodoStackNavigator';
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
      <Tab.Screen
        name="BossApprovalTab"
        component={OATodoStackNavigator}
        options={{ title: '审批', tabBarIcon: ({ color, size }) => <Icon source="clipboard-check-outline" color={color} size={size} /> }}
      />
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
