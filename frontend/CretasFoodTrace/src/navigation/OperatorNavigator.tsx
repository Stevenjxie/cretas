import React from 'react';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { Icon } from 'react-native-paper';
import AttendanceStackNavigator from './AttendanceStackNavigator';
import WorkStackNavigator from './WorkStackNavigator';
import ProfileStackNavigator from './ProfileStackNavigator';

// 报工页面 - operator 仅限个人扫码报工（不含团队报工）
import ScanReportScreen from '../screens/processing/ScanReportScreen';
import ScanReportSuccessScreen from '../screens/processing/ScanReportSuccessScreen';
import DraftReportsScreen from '../screens/processing/DraftReportsScreen';
import YieldBatchSelectScreen from '../screens/processing/YieldBatchSelectScreen';
import YieldStepReportScreen from '../screens/processing/YieldStepReportScreen';

const Tab = createBottomTabNavigator<any>();
const ReportStack = createNativeStackNavigator<any>();

/**
 * Operator 报工 Stack 导航器
 * 仅包含个人扫码报工（ScanReport），不含 TeamBatchReport/DynamicReport
 */
function OperatorReportStackNavigator() {
  return (
    <ReportStack.Navigator screenOptions={{ headerShown: false }}>
      <ReportStack.Screen name="ScanReport" component={ScanReportScreen} />
      <ReportStack.Screen name="ScanReportSuccess" component={ScanReportSuccessScreen} />
      <ReportStack.Screen name="DraftReports" component={DraftReportsScreen} />
      <ReportStack.Screen name="YieldBatchSelect" component={YieldBatchSelectScreen} />
      <ReportStack.Screen name="YieldStepReport" component={YieldStepReportScreen} />
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
      {/* 考勤Tab - 打卡、工时查询等 */}
      <Tab.Screen
        name="OperatorAttendanceTab"
        component={AttendanceStackNavigator}
        options={{
          title: '考勤',
          tabBarIcon: ({ color, size }) => (
            <Icon source="clock-outline" size={size} color={color} />
          ),
        }}
      />

      {/* 报工Tab - 个人扫码报工 */}
      <Tab.Screen
        name="OperatorReportTab"
        component={OperatorReportStackNavigator}
        options={{
          title: '报工',
          tabBarIcon: ({ color, size }) => (
            <Icon source="qrcode-scan" size={size} color={color} />
          ),
        }}
      />

      {/* 工作Tab - 工作类型、工作记录 */}
      <Tab.Screen
        name="OperatorWorkTab"
        component={WorkStackNavigator}
        options={{
          title: '工作',
          tabBarIcon: ({ color, size }) => (
            <Icon source="briefcase-outline" size={size} color={color} />
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
