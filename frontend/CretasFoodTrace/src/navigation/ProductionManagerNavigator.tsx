import React from 'react';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { Icon } from 'react-native-paper';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import ProductionPlanManagementScreen from '../screens/processing/ProductionPlanManagementScreen';
import MobileAccountScreen from '../screens/common/MobileAccountScreen';

const Tab = createBottomTabNavigator();

/**
 * 兼容仍持有 deprecated production_manager 角色的账号。
 * 手机端只查看生产计划与进度；排产、结单和入库留在 PC/对应岗位。
 */
export default function ProductionManagerNavigator() {
  const insets = useSafeAreaInsets();

  return (
    <Tab.Navigator
      initialRouteName="ProductionOverviewTab"
      screenOptions={{
        headerShown: false,
        tabBarActiveTintColor: '#1565C0',
        tabBarInactiveTintColor: '#757575',
        tabBarStyle: {
          height: 60 + insets.bottom,
          paddingTop: 5,
          paddingBottom: insets.bottom + 5,
        },
      }}
    >
      <Tab.Screen
        name="ProductionOverviewTab"
        component={ProductionPlanManagementScreen}
        options={{
          title: '生产',
          tabBarButtonTestID: 'production-manager-tab-overview',
          tabBarIcon: ({ color, size }) => (
            <Icon source="clipboard-text-clock-outline" color={color} size={size} />
          ),
        }}
      />
      <Tab.Screen
        name="ProductionManagerProfileTab"
        component={MobileAccountScreen}
        options={{
          title: '我的',
          tabBarButtonTestID: 'production-manager-tab-profile',
          tabBarIcon: ({ color, size }) => (
            <Icon source="account-outline" color={color} size={size} />
          ),
        }}
      />
    </Tab.Navigator>
  );
}
