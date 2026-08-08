/**
 * Factory Admin 首页 Stack 导航器
 * 包含: 首页Dashboard、今日生产、今日批次、原材料批次、AI预警详情
 */

import React from "react";
import { createNativeStackNavigator } from "@react-navigation/native-stack";
import { FAHomeStackParamList } from "../../types/navigation";

// 导入首页相关页面组件
import FAHomeScreen from "../../screens/factory-admin/home/FAHomeScreen";
import TodayProductionScreen from "../../screens/factory-admin/home/TodayProductionScreen";
import TodayBatchesScreen from "../../screens/factory-admin/home/TodayBatchesScreen";
import MaterialBatchScreen from "../../screens/factory-admin/home/MaterialBatchScreen";
import AIAlertsScreen from "../../screens/factory-admin/home/AIAlertsScreen";
import ProductionPlanManagementScreen from "../../screens/processing/ProductionPlanManagementScreen";

// 复用现有详情页
import BatchDetailScreen from "../../screens/processing/BatchDetailScreen";
import MaterialBatchDetailScreen from "../../screens/factory-admin/home/MaterialBatchDetailScreen";

// 批次详情关联页面 - 从BatchDetail跳转
import CreateBatchScreen from "../../screens/processing/CreateBatchScreen";
// CreateQualityRecordScreen 已迁移至 QualityInspectorNavigator
// import CreateQualityRecordScreen from "../../screens/processing/CreateQualityRecordScreen";
import CostAnalysisDashboard from "../../screens/processing/CostAnalysisDashboard";

// Formily 演示页面
import { FormilyDemoScreen } from "../../screens/demo";

// 首页布局编辑器
import HomeLayoutEditorScreen from "../../screens/factory-admin/home/HomeLayoutEditorScreen";

const Stack = createNativeStackNavigator<FAHomeStackParamList>();

export function FAHomeStackNavigator() {
  return (
    <Stack.Navigator
      screenOptions={{
        headerShown: false,
      }}
    >
      {/* 首页Dashboard */}
      <Stack.Screen name="FAHome" component={FAHomeScreen} />

      {/* 今日生产统计 */}
      <Stack.Screen
        name="TodayProduction"
        component={TodayProductionScreen}
        options={{ title: "今日生产" }}
      />

      {/* 今日批次列表 */}
      <Stack.Screen
        name="TodayBatches"
        component={TodayBatchesScreen}
        options={{ title: "今日批次" }}
      />

      {/* 原材料批次管理 */}
      <Stack.Screen
        name="MaterialBatch"
        component={MaterialBatchScreen}
        options={{ title: "原材料批次" }}
      />

      {/* AI预警详情 */}
      <Stack.Screen
        name="AIAlerts"
        component={AIAlertsScreen}
        options={{ title: "AI预警" }}
      />

      {/* 首页生产工作台入口必须落在当前 FA Home stack 内，否则导航动作无人处理。 */}
      <Stack.Screen
        name="ProductionPlanManagement"
        component={ProductionPlanManagementScreen}
        options={{ title: "生产计划管理" }}
      />

      {/* 批次详情 (复用现有) */}
      <Stack.Screen
        name="BatchDetail"
        component={BatchDetailScreen}
        options={{ title: "批次详情" }}
      />

      {/* 原材料批次详情 (复用现有) */}
      <Stack.Screen
        name="MaterialBatchDetail"
        component={MaterialBatchDetailScreen}
        options={{ title: "原材料详情" }}
      />

      {/* 批次详情关联页面 - 从BatchDetail跳转 */}
      <Stack.Screen
        name="EditBatch"
        component={CreateBatchScreen}
        options={{ title: "编辑批次" }}
      />
      {/* 质检已迁移至 QualityInspectorNavigator */}
      {/* <Stack.Screen
        name="CreateQualityRecord"
        component={CreateQualityRecordScreen}
        options={{ title: "创建质检记录" }}
      /> */}
      <Stack.Screen
        name="CostAnalysisDashboard"
        component={CostAnalysisDashboard}
        options={{ title: "成本分析" }}
      />

      {/* Formily 演示页面 - 用于验证动态表单功能 */}
      <Stack.Screen
        name="FormilyDemo"
        component={FormilyDemoScreen}
        options={{ title: "Formily演示" }}
      />

      {/* 首页布局编辑器 */}
      <Stack.Screen
        name="HomeLayoutEditor"
        component={HomeLayoutEditorScreen}
        options={{
          title: "首页布局编辑",
          headerShown: false,
          presentation: "modal",
        }}
      />
    </Stack.Navigator>
  );
}

export default FAHomeStackNavigator;
