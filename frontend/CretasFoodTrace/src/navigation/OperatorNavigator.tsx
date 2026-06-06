import React from 'react';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { Icon } from 'react-native-paper';
import ProfileStackNavigator from './ProfileStackNavigator';

import OperatorAssignedProcessScreen from '../screens/processing/OperatorAssignedProcessScreen';
import ThreeStepReportScreen from '../screens/processing/ThreeStepReportScreen';

const Tab = createBottomTabNavigator<any>();
const ReportStack = createNativeStackNavigator<any>();

/**
 * Operator 报工 Stack 导航器
 * 小组长只处理系统分配好的当前工序，不暴露任务列表/三步报工/选批次入口。
 */
function OperatorReportStackNavigator() {
  return (
    <ReportStack.Navigator initialRouteName="OperatorAssignedProcess" screenOptions={{ headerShown: false }}>
      <ReportStack.Screen name="OperatorAssignedProcess" component={OperatorAssignedProcessScreen} />
      <ReportStack.Screen name="ThreeStepReport" component={ThreeStepReportScreen} />
    </ReportStack.Navigator>
  );
}

/**
 * Operator专用底部Tab导航器
 * 考勤、报工、工作、个人中心 四个tab
 */
export function OperatorNavigator() {
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
