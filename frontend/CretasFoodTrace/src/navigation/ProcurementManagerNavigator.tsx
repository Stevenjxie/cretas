/**
 * Procurement Manager 导航器
 * 仅 procurement_manager 角色使用
 * 4个Tab: 首页 | 采购 | 供应商 | 我的
 */

import React from "react";
import { createBottomTabNavigator } from "@react-navigation/bottom-tabs";
import { createNativeStackNavigator } from "@react-navigation/native-stack";
import { Icon } from "react-native-paper";

// 首页
import HomeScreen from "../screens/main/HomeScreen";

// 采购相关页面 - 复用 factory-admin 已有页面
import PurchaseOrderListScreen from "../screens/factory-admin/inventory/PurchaseOrderListScreen";
import PurchaseOrderCreateScreen from "../screens/factory-admin/inventory/PurchaseOrderCreateScreen";
import PurchaseOrderDetailScreen from "../screens/factory-admin/inventory/PurchaseOrderDetailScreen";
import ReturnOrderListScreen from "../screens/factory-admin/inventory/ReturnOrderListScreen";
import ReturnOrderDetailScreen from "../screens/factory-admin/inventory/ReturnOrderDetailScreen";
import PriceListScreen from "../screens/factory-admin/inventory/PriceListScreen";
import ArApOverviewScreen from "../screens/factory-admin/inventory/ArApOverviewScreen";
import ProcurementDeliveryConfirmScreen from "../screens/restaurant/procurement/ProcurementDeliveryConfirmScreen";
import SupplierDeliveryDetailScreen from "../screens/restaurant/supplier-delivery/SupplierDeliveryDetailScreen";
import SupplierDeliveryListScreen from "../screens/restaurant/supplier-delivery/SupplierDeliveryListScreen";
import PurchaseExceptionListScreen from "../screens/factory-admin/inventory/PurchaseExceptionListScreen";

// 供应商管理
import SupplierManagementScreen from "../screens/management/SupplierManagementScreen";

// 个人中心
import ProfileStackNavigator from "./ProfileStackNavigator";

const Tab = createBottomTabNavigator<any>();
const PurchaseStack = createNativeStackNavigator<any>();
const SupplierStack = createNativeStackNavigator<any>();

const TAB_COLORS = {
  active: "#2E7D32",
  inactive: "#757575",
};

function PurchaseStackNavigator() {
  return (
    <PurchaseStack.Navigator screenOptions={{ headerShown: false }}>
      <PurchaseStack.Screen name="PurchaseOrderList" component={PurchaseOrderListScreen} />
      <PurchaseStack.Screen name="PurchaseOrderCreate" component={PurchaseOrderCreateScreen} />
      <PurchaseStack.Screen name="PurchaseOrderDetail" component={PurchaseOrderDetailScreen} />
      <PurchaseStack.Screen name="ReturnOrderList" component={ReturnOrderListScreen} />
      <PurchaseStack.Screen name="ReturnOrderDetail" component={ReturnOrderDetailScreen} />
      <PurchaseStack.Screen name="PriceList" component={PriceListScreen} />
      <PurchaseStack.Screen name="ArApOverview" component={ArApOverviewScreen} />
      <PurchaseStack.Screen name="ProcurementDeliveryConfirm" component={ProcurementDeliveryConfirmScreen} />
      <PurchaseStack.Screen name="SupplierDeliveryList" component={SupplierDeliveryListScreen} />
      <PurchaseStack.Screen name="SupplierDeliveryDetail" component={SupplierDeliveryDetailScreen} />
      <PurchaseStack.Screen name="PurchaseExceptionList" component={PurchaseExceptionListScreen} />
    </PurchaseStack.Navigator>
  );
}

function SupplierStackNavigator() {
  return (
    <SupplierStack.Navigator screenOptions={{ headerShown: false }}>
      <SupplierStack.Screen name="SupplierManagement" component={SupplierManagementScreen} />
    </SupplierStack.Navigator>
  );
}

export function ProcurementManagerNavigator() {
  return (
    <Tab.Navigator
      screenOptions={{
        headerShown: false,
        tabBarActiveTintColor: TAB_COLORS.active,
        tabBarInactiveTintColor: TAB_COLORS.inactive,
        tabBarStyle: {
          backgroundColor: "#ffffff",
          borderTopWidth: 1,
          borderTopColor: "#e0e0e0",
          paddingBottom: 5,
          paddingTop: 5,
          height: 60,
        },
        tabBarLabelStyle: {
          fontSize: 12,
          fontWeight: "500",
        },
      }}
    >
      <Tab.Screen
        name="PMHomeTab"
        component={HomeScreen}
        options={{
          tabBarButtonTestID: "pm-tab-home",
          title: "首页",
          tabBarIcon: ({ color, size }) => (
            <Icon source="home" size={size} color={color} />
          ),
        }}
      />

      <Tab.Screen
        name="PMPurchaseTab"
        component={PurchaseStackNavigator}
        options={{
          tabBarButtonTestID: "pm-tab-purchase",
          title: "采购",
          tabBarIcon: ({ color, size }) => (
            <Icon source="package-down" size={size} color={color} />
          ),
        }}
      />

      <Tab.Screen
        name="PMSupplierTab"
        component={SupplierStackNavigator}
        options={{
          tabBarButtonTestID: "pm-tab-supplier",
          title: "供应商",
          tabBarIcon: ({ color, size }) => (
            <Icon source="truck-delivery-outline" size={size} color={color} />
          ),
        }}
      />

      <Tab.Screen
        name="PMProfileTab"
        component={ProfileStackNavigator}
        options={{
          tabBarButtonTestID: "pm-tab-profile",
          title: "我的",
          tabBarIcon: ({ color, size }) => (
            <Icon source="account" size={size} color={color} />
          ),
        }}
      />
    </Tab.Navigator>
  );
}

export default ProcurementManagerNavigator;
