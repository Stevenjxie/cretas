/**
 * Warehouse Manager Tab 导航器
 * 5个主Tab: 首页、入库、出货、库存、我的
 * 用于仓储管理员 (warehouse_manager) 和仓储员工 (warehouse_worker) 角色
 */

import React from "react";
import { createBottomTabNavigator } from "@react-navigation/bottom-tabs";
import { Icon } from "react-native-paper";
import { WarehouseManagerTabParamList } from "../types/navigation";
import { useFactoryFeatureStore } from "../store/factoryFeatureStore";

// 导入5个Stack导航器
import WHHomeStackNavigator from "./warehouse/WHHomeStackNavigator";
import WHInboundStackNavigator from "./warehouse/WHInboundStackNavigator";
import WHOutboundStackNavigator from "./warehouse/WHOutboundStackNavigator";
import WHInventoryStackNavigator from "./warehouse/WHInventoryStackNavigator";
import WHProfileStackNavigator from "./warehouse/WHProfileStackNavigator";

const Tab = createBottomTabNavigator<WarehouseManagerTabParamList>();

// Tab栏配色 - 仓储主题色 (绿色)
const TAB_COLORS = {
  active: "#4CAF50",   // 仓储绿色
  inactive: "#757575",
};

export function WarehouseManagerTabNavigator() {
  const { isScreenEnabled } = useFactoryFeatureStore();

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
      {/* 首页Tab */}
      <Tab.Screen
        name="WHHomeTab"
        component={WHHomeStackNavigator}
        options={{
          title: "首页",
          tabBarButtonTestID: "wh-tab-home",
          tabBarIcon: ({ color, size }) => (
            <Icon source="home" size={size} color={color} />
          ),
        }}
      />

      {/* 入库Tab */}
      {isScreenEnabled('InboundManagement') && (
      <Tab.Screen
        name="WHInboundTab"
        component={WHInboundStackNavigator}
        options={{
          title: "入库",
          tabBarButtonTestID: "wh-tab-inbound",
          tabBarIcon: ({ color, size }) => (
            <Icon source="package-down" size={size} color={color} />
          ),
        }}
      />
      )}

      {/* 出货Tab */}
      {isScreenEnabled('OutboundManagement') && (
      <Tab.Screen
        name="WHOutboundTab"
        component={WHOutboundStackNavigator}
        options={{
          title: "出货",
          tabBarButtonTestID: "wh-tab-outbound",
          tabBarIcon: ({ color, size }) => (
            <Icon source="package-up" size={size} color={color} />
          ),
        }}
      />
      )}

      {/* 库存Tab */}
      <Tab.Screen
        name="WHInventoryTab"
        component={WHInventoryStackNavigator}
        options={{
          title: "库存",
          tabBarButtonTestID: "wh-tab-inventory",
          tabBarIcon: ({ color, size }) => (
            <Icon source="warehouse" size={size} color={color} />
          ),
        }}
      />

      {/* 我的Tab */}
      <Tab.Screen
        name="WHProfileTab"
        component={WHProfileStackNavigator}
        options={{
          title: "我的",
          tabBarButtonTestID: "wh-tab-profile",
          tabBarIcon: ({ color, size }) => (
            <Icon source="account" size={size} color={color} />
          ),
        }}
      />
    </Tab.Navigator>
  );
}

export default WarehouseManagerTabNavigator;
