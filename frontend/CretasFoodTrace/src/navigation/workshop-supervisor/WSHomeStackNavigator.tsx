/**
 * Workshop Supervisor 首页 Stack 导航器
 * 包含: 首页Dashboard、批次详情、员工详情、设备详情、通知、任务引导
 */

import React from "react";
import { createNativeStackNavigator } from "@react-navigation/native-stack";
import { WSHomeStackParamList } from "../../types/navigation";

// 导入首页相关页面组件
import WSHomeScreen from "../../screens/workshop-supervisor/home/WSHomeScreen";

// 复用现有详情页
import BatchDetailScreen from "../../screens/processing/BatchDetailScreen";
import EquipmentDetailScreen from "../../screens/processing/EquipmentDetailScreen";

// 任务引导流程 (可复用或新建)
import TaskGuideScreen from "../../screens/workshop-supervisor/home/TaskGuideScreen";
import TaskGuideStep2Screen from "../../screens/workshop-supervisor/home/TaskGuideStep2Screen";
import TaskGuideStep3Screen from "../../screens/workshop-supervisor/home/TaskGuideStep3Screen";

// 通知页面 (新建或复用)
import NotificationsScreen from "../../screens/workshop-supervisor/home/NotificationsScreen";

// 员工详情 (复用或新建)
import WorkerDetailScreen from "../../screens/workshop-supervisor/workers/WorkerDetailScreen";

// 快捷操作入口页面
import TeamBatchReportScreen from "../../screens/processing/TeamBatchReportScreen";
import DraftReportsScreen from "../../screens/processing/DraftReportsScreen";

// 生产报工
import DynamicReportScreen from "../../screens/processing/DynamicReportScreen";
import NfcCheckinScreen from "../../screens/processing/NfcCheckinScreen";
import MyWorkReportsScreen from "../../screens/processing/MyWorkReportsScreen";
import ProcessTaskListScreen from "../../screens/processing/ProcessTaskListScreen";
import ProcessTaskDetailScreen from "../../screens/processing/ProcessTaskDetailScreen";
import ProcessTaskReportScreen from "../../screens/processing/ProcessTaskReportScreen";
import ProcessRunOverviewScreen from "../../screens/processing/ProcessRunOverviewScreen";
import ThreeStepReportScreen from "../../screens/processing/ThreeStepReportScreen";
import ProcessOperationScreen from "../../screens/processing/ProcessOperationScreen";

const Stack = createNativeStackNavigator<WSHomeStackParamList>();

export function WSHomeStackNavigator() {
  return (
    <Stack.Navigator
      initialRouteName="ProcessTaskList"
      screenOptions={{
        headerShown: false,
      }}
    >
      {/* 首页Dashboard */}
      <Stack.Screen name="WSHome" component={WSHomeScreen} />

      {/* 批次详情 (复用现有) */}
      <Stack.Screen
        name="BatchDetail"
        component={BatchDetailScreen}
        options={{ title: "批次详情" }}
      />

      {/* 员工详情 */}
      <Stack.Screen
        name="WorkerDetail"
        component={WorkerDetailScreen}
        options={{ title: "员工详情" }}
      />

      {/* 设备详情 (复用现有) */}
      <Stack.Screen
        name="EquipmentDetail"
        component={EquipmentDetailScreen}
        options={{ title: "设备详情" }}
      />

      {/* 通知 */}
      <Stack.Screen
        name="Notifications"
        component={NotificationsScreen}
        options={{ title: "通知" }}
      />

      {/* 任务引导流程 - 步骤1: 前往工位 */}
      <Stack.Screen
        name="TaskGuide"
        component={TaskGuideScreen}
        options={{ title: "任务引导" }}
      />

      {/* 任务引导流程 - 步骤2: 确认设备 */}
      <Stack.Screen
        name="TaskGuideStep2"
        component={TaskGuideStep2Screen}
        options={{ title: "确认设备" }}
      />

      {/* 任务引导流程 - 步骤3: 召集人员 */}
      <Stack.Screen
        name="TaskGuideStep3"
        component={TaskGuideStep3Screen}
        options={{ title: "召集人员" }}
      />

      {/* 快捷操作入口 */}
      <Stack.Screen name="TeamBatchReport" component={TeamBatchReportScreen} options={{ title: "班组报工" }} />
      <Stack.Screen name="DraftReports" component={DraftReportsScreen} options={{ title: "草稿管理" }} />

      {/* 生产报工 */}
      <Stack.Screen name="DynamicReport" component={DynamicReportScreen} options={{ title: "生产报工" }} />
      <Stack.Screen name="NfcCheckin" component={NfcCheckinScreen} options={{ title: "扫码签到" }} />
      <Stack.Screen name="MyWorkReports" component={MyWorkReportsScreen} options={{ title: "我的报工" }} />
      <Stack.Screen name="ProcessTaskList" component={ProcessTaskListScreen} options={{ title: "工序任务" }} />
      <Stack.Screen name="ProcessTaskDetail" component={ProcessTaskDetailScreen} options={{ title: "任务详情" }} />
      <Stack.Screen name="ProcessTaskReport" component={ProcessTaskReportScreen} options={{ title: "报工" }} />
      <Stack.Screen name="ProcessRunOverview" component={ProcessRunOverviewScreen} options={{ title: "生产单总览" }} />
      <Stack.Screen name="ThreeStepReport" component={ThreeStepReportScreen} options={{ title: "三步报工" }} />
      <Stack.Screen name="ProcessOperation" component={ProcessOperationScreen} options={{ title: "工序操作" }} />
    </Stack.Navigator>
  );
}

export default WSHomeStackNavigator;
