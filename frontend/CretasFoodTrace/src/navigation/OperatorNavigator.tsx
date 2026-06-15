import React from 'react';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { Icon } from 'react-native-paper';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import ProfileStackNavigator from './ProfileStackNavigator';

import OperatorAssignedProcessScreen from '../screens/processing/OperatorAssignedProcessScreen';
import YieldStepReportScreen from '../screens/processing/YieldStepReportScreen';
import ShortageAlertDetailScreen from '../screens/processing/ShortageAlertDetailScreen';

const Tab = createBottomTabNavigator<any>();
const ReportStack = createNativeStackNavigator<any>();

/**
 * Operator 报工 Stack 导航器
 * 小组长只处理系统分配好的当前工序，不暴露任务列表/三步报工/选批次入口。
 * N1b: ShortageAlertDetail 屏注册在此 Stack，从任务列表缺料 badge 跳入。
 */
function OperatorReportStackNavigator() {
  return (
    <ReportStack.Navigator initialRouteName="OperatorAssignedProcess" screenOptions={{ headerShown: false }}>
      <ReportStack.Screen name="OperatorAssignedProcess" component={OperatorAssignedProcessScreen} />
      <ReportStack.Screen name="YieldStepReport" component={YieldStepReportScreen} />
      {/* N1b: 缺料预警明细屏 (从任务列表的 ⚠缺料 badge 跳入) */}
      <ReportStack.Screen name="ShortageAlertDetail" component={ShortageAlertDetailScreen} />
    </ReportStack.Navigator>
  );
}

/**
 * Operator专用底部Tab导航器
 * 考勤、报工、工作、个人中心 四个tab
 */
export function OperatorNavigator() {
  // C1: Use safe-area insets so the tab bar clears Android gesture navigation bar.
  // insets.bottom is 0 on devices with hardware buttons; positive on gesture-nav devices.
  const insets = useSafeAreaInsets();

  return (
    <Tab.Navigator
      initialRouteName="OperatorReportTab"
      screenOptions={{
        headerShown: false,
        tabBarActiveTintColor: '#2196F3',
        tabBarInactiveTintColor: '#757575',
        tabBarStyle: {
          backgroundColor: "#ffffff",
          borderTopWidth: 1,
          borderTopColor: "#e0e0e0",
          paddingBottom: insets.bottom + 5,
          paddingTop: 5,
          height: 60 + insets.bottom,
        },
        tabBarLabelStyle: {
          fontSize: 12,
          fontWeight: "500",
        },
      }}
    >
      {/* 工序Tab - 小组长/操作员登录后直接进入被分配工序 */}
      <Tab.Screen
        name="OperatorReportTab"
        component={OperatorReportStackNavigator}
        options={{
          title: '工序',
          tabBarIcon: ({ color, size }) => (
            <Icon source="format-list-checks" size={size} color={color} />
          ),
        }}
      />

      {/* 个人中心Tab - 账号信息、退出登录 */}
      <Tab.Screen
        name="OperatorProfileTab"
        component={ProfileStackNavigator}
        options={{
          title: '我的',
          tabBarIcon: ({ color, size }) => (
            <Icon source="account-outline" size={size} color={color} />
          ),
        }}
      />
    </Tab.Navigator>
  );
}

export default OperatorNavigator;
