import React from 'react';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { Icon } from 'react-native-paper';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import OperationsHomeScreen from '../screens/operations/OperationsHomeScreen';
import OperationsBusinessOverviewScreen from '../screens/operations/OperationsBusinessOverviewScreen';
import ProductionPlanManagementScreen from '../screens/processing/ProductionPlanManagementScreen';
import SalesOrderListScreen from '../screens/factory-admin/inventory/SalesOrderListScreen';
import SalesOrderDetailScreen from '../screens/factory-admin/inventory/SalesOrderDetailScreen';
import PurchaseOrderListScreen from '../screens/factory-admin/inventory/PurchaseOrderListScreen';
import PurchaseOrderDetailScreen from '../screens/factory-admin/inventory/PurchaseOrderDetailScreen';
import FinishedGoodsListScreen from '../screens/factory-admin/inventory/FinishedGoodsListScreen';
import MobileAccountScreen from '../screens/common/MobileAccountScreen';

const Tab = createBottomTabNavigator();
const BusinessStack = createNativeStackNavigator();

function OperationsBusinessStack() {
  return (
    <BusinessStack.Navigator screenOptions={{ headerShown: false }}>
      <BusinessStack.Screen name="OperationsBusinessOverview" component={OperationsBusinessOverviewScreen} />
      <BusinessStack.Screen name="OperationsProductionPlans" component={ProductionPlanManagementScreen} />
      <BusinessStack.Screen name="OperationsSalesOrders" component={SalesOrderListScreen} />
      <BusinessStack.Screen name="SalesOrderDetail" component={SalesOrderDetailScreen} />
      <BusinessStack.Screen name="OperationsPurchaseOrders" component={PurchaseOrderListScreen} />
      <BusinessStack.Screen name="PurchaseOrderDetail" component={PurchaseOrderDetailScreen} />
      <BusinessStack.Screen name="OperationsFinishedGoods" component={FinishedGoodsListScreen} />
    </BusinessStack.Navigator>
  );
}

export default function OperationsNavigator() {
  const insets = useSafeAreaInsets();

  return (
    <Tab.Navigator
      initialRouteName="OperationsBusinessTab"
      screenOptions={{
        headerShown: false,
        tabBarActiveTintColor: '#2E7D32',
        tabBarInactiveTintColor: '#757575',
        tabBarStyle: {
          height: 60 + insets.bottom,
          paddingTop: 5,
          paddingBottom: insets.bottom + 5,
        },
      }}
    >
      <Tab.Screen
        name="OperationsBusinessTab"
        component={OperationsBusinessStack}
        options={{
          title: '业务查看',
          tabBarButtonTestID: 'operations-tab-business',
          tabBarIcon: ({ color, size }) => (
            <Icon source="view-dashboard-outline" color={color} size={size} />
          ),
        }}
      />
      <Tab.Screen
        name="OperationsWorkTab"
        component={OperationsHomeScreen}
        options={{
          title: '来料预告',
          tabBarButtonTestID: 'operations-tab-work',
          tabBarIcon: ({ color, size }) => (
            <Icon source="truck-delivery-outline" color={color} size={size} />
          ),
        }}
      />
      <Tab.Screen
        name="OperationsProfileTab"
        component={MobileAccountScreen}
        options={{
          title: '我的',
          tabBarButtonTestID: 'operations-tab-profile',
          tabBarIcon: ({ color, size }) => (
            <Icon source="account-outline" color={color} size={size} />
          ),
        }}
      />
    </Tab.Navigator>
  );
}
