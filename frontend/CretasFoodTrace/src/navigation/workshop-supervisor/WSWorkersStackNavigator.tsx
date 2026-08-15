/**
 * Workshop Supervisor 人员管理 Stack 导航器
 * 包含: 人员列表、员工详情、分配员工、打卡、考勤历史
 */

import React from "react";
import { createNativeStackNavigator } from "@react-navigation/native-stack";
import { WSWorkersStackParamList } from "../../types/navigation";

// 导入人员相关页面组件
import WSWorkersScreen from "../../screens/workshop-supervisor/workers/WSWorkersScreen";
import WorkerDetailScreen from "../../screens/workshop-supervisor/workers/WorkerDetailScreen";
import WorkerAssignScreen from "../../screens/workshop-supervisor/workers/WorkerAssignScreen";
import ClockInScreen from "../../screens/workshop-supervisor/workers/ClockInScreen";
import AttendanceHistoryScreen from "../../screens/workshop-supervisor/workers/AttendanceHistoryScreen";

const Stack = createNativeStackNavigator<WSWorkersStackParamList>();

export function WSWorkersStackNavigator() {
  return (
    <Stack.Navigator
      screenOptions={{
        headerShown: false,
      }}
    >
      {/* 人员列表主页 */}
      <Stack.Screen name="WSWorkers" component={WSWorkersScreen} />

      {/* 员工详情 */}
      <Stack.Screen
        name="WorkerDetail"
        component={WorkerDetailScreen}
        options={{ title: "员工详情" }}
      />

      {/* 分配员工到批次 */}
      <Stack.Screen
        name="WorkerAssign"
        component={WorkerAssignScreen}
        options={{ title: "分配员工" }}
      />

      {/* 打卡页面 */}
      <Stack.Screen
        name="ClockIn"
        component={ClockInScreen}
        options={{ title: "打卡" }}
      />

      {/* 考勤历史 */}
      <Stack.Screen
        name="AttendanceHistory"
        component={AttendanceHistoryScreen}
        options={{ title: "考勤历史" }}
      />

      {/* 工序段管理 (P1-1) */}
      {/* EmployeeProcessSegment 已退役: 后端 employee_process_segments 表被 migration_*_segment_retire_20261029_* 删除, GET /workreport/segments/active 在生产上返回 500。屏与 API 客户端一并移除。 */}
    </Stack.Navigator>
  );
}

export default WSWorkersStackNavigator;
