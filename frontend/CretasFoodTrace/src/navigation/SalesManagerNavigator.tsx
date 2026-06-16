/**
 * Sales Manager 导航器
 * 仅 sales_manager 角色使用
 * 4个Tab: 首页 | 销售 | 客户 | 我的
 */

import React from "react";
import { createBottomTabNavigator } from "@react-navigation/bottom-tabs";
import { createNativeStackNavigator } from "@react-navigation/native-stack";
import { Icon } from "react-native-paper";

// 首页 - 复用通用首页
import HomeScreen from "../screens/main/HomeScreen";

// 销售相关页面 - 复用 factory-admin 已有页面
import SalesOrderListScreen from "../screens/factory-admin/inventory/SalesOrderListScreen";
import SalesOrderCreateScreen from "../screens/factory-admin/inventory/SalesOrderCreateScreen";
import SalesOrderDetailScreen from "../screens/factory-admin/inventory/SalesOrderDetailScreen";
import SalesOrderShortageReviewScreen from "../screens/factory-admin/inventory/SalesOrderShortageReviewScreen";
import ReturnOrderListScreen from "../screens/factory-admin/inventory/ReturnOrderListScreen";
import ReturnOrderDetailScreen from "../screens/factory-admin/inventory/ReturnOrderDetailScreen";
import PriceListScreen from "../screens/factory-admin/inventory/PriceListScreen";
import ArApOverviewScreen from "../screens/factory-admin/inventory/ArApOverviewScreen";

// 客户管理
import CustomerManagementScreen from "../screens/management/CustomerManagementScreen";

// 个人中心
import ProfileStackNavigator from "./ProfileStackNavigator";

const Tab = createBottomTabNavigator<any>();
const SalesStack = createNativeStackNavigator<any>();
const CustomerStack = createNativeStackNavigator<any>();

const TAB_COLORS = {
  active: "#FF6B35",
  inactive: "#757575",
};

function SalesStackNavigator() {
  return (
    <SalesStack.Navigator screenOptions={{ headerShown: false }}>
      <SalesStack.Screen name="SalesOrderList" component={SalesOrderListScreen} />
      <SalesStack.Screen name="SalesOrderCreate" component={SalesOrderCreateScreen} />
      <SalesStack.Screen name="SalesOrderDetail" component={SalesOrderDetailScreen} />
      <SalesStack.Screen name="SalesOrderShortageReview" component={SalesOrderShortageReviewScreen} />
      <SalesStack.Screen name="ReturnOrderList" component={ReturnOrderListScreen} />
      <SalesStack.Screen name="ReturnOrderDetail" component={ReturnOrderDetailScreen} />
      <SalesStack.Screen name="PriceList" component={PriceListScreen} />
      <SalesStack.Screen name="ArApOverview" component={ArApOverviewScreen} />
    </SalesStack.Navigator>
  );
}

function CustomerStackNavigator() {
  return (
    <CustomerStack.Navigator screenOptions={{ headerShown: false }}>
      <CustomerStack.Screen name="CustomerManagement" component={CustomerManagementScreen} />
    </CustomerStack.Navigator>
  );
}

export function SalesManagerNavigator() {
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
        name="SMHomeTab"
        component={HomeScreen}
        options={{
          tabBarButtonTestID: "sm-tab-home",
          title: "首页",
          tabBarIcon: ({ color, size }) => (
            <Icon source="home" size={size} color={color} />
          ),
        }}
      />

      <Tab.Screen
        name="SMSalesTab"
        component={SalesStackNavigator}
        options={{
          tabBarButtonTestID: "sm-tab-sales",
          title: "销售",
          tabBarIcon: ({ color, size }) => (
            <Icon source="cart-outline" size={size} color={color} />
          ),
        }}
      />

      <Tab.Screen
        name="SMCustomerTab"
        component={CustomerStackNavigator}
        options={{
          tabBarButtonTestID: "sm-tab-customer",
          title: "客户",
          tabBarIcon: ({ color, size }) => (
            <Icon source="account-group-outline" size={size} color={color} />
          ),
        }}
      />

      <Tab.Screen
        name="SMProfileTab"
        component={ProfileStackNavigator}
        options={{
          tabBarButtonTestID: "sm-tab-profile",
          title: "我的",
          tabBarIcon: ({ color, size }) => (
            <Icon source="account" size={size} color={color} />
          ),
        }}
      />
    </Tab.Navigator>
  );
}

export default SalesManagerNavigator;
