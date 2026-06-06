/**
 * Workshop Supervisor 批次管理 Stack 导航器
 * 包含: 批次列表、批次详情、开始生产、工艺环节录入、完成生产、
 *       原料消耗记录、质检创建、质检详情
 */

import React from "react";
import { createNativeStackNavigator } from "@react-navigation/native-stack";
import { WSBatchesStackParamList } from "../../types/navigation";

// 导入批次相关页面组件
import WSBatchesScreen from "../../screens/workshop-supervisor/batches/WSBatchesScreen";
import BatchStartScreen from "../../screens/workshop-supervisor/batches/BatchStartScreen";
import BatchStageScreen from "../../screens/workshop-supervisor/batches/BatchStageScreen";
import BatchCompleteScreen from "../../screens/workshop-supervisor/batches/BatchCompleteScreen";
import MaterialConsumptionScreen from "../../screens/workshop-supervisor/batches/MaterialConsumptionScreen";

// 复用现有详情页
import BatchDetailScreen from "../../screens/processing/BatchDetailScreen";

// 报工页面
import ScanReportScreen from "../../screens/processing/ScanReportScreen";
import TeamBatchReportScreen from "../../screens/processing/TeamBatchReportScreen";
import ScanReportSuccessScreen from "../../screens/processing/ScanReportSuccessScreen";
import DraftReportsScreen from "../../screens/processing/DraftReportsScreen";

// 标签扫描 + AI分析
import LabelScanScreen from "../../screens/shared/LabelScanScreen";
import AIAnalysisScreen from "../../screens/processing/AIAnalysisScreen";

// 工序制生产页面
import ProcessTaskListScreen from "../../screens/processing/ProcessTaskListScreen";
import ProcessTaskDetailScreen from "../../screens/processing/ProcessTaskDetailScreen";
import ProcessTaskHistoryScreen from "../../screens/processing/ProcessTaskHistoryScreen";
import ProcessRunOverviewScreen from "../../screens/processing/ProcessRunOverviewScreen";
import YieldBatchSelectScreen from "../../screens/processing/YieldBatchSelectScreen";
import YieldStepReportScreen from "../../screens/processing/YieldStepReportScreen";

// 质检相关 - 已迁移至 QualityInspectorNavigator
// import CreateQualityRecordScreen from "../../screens/processing/CreateQualityRecordScreen";
// import QualityInspectionDetailScreen from "../../screens/processing/QualityInspectionDetailScreen";

const Stack = createNativeStackNavigator<WSBatchesStackParamList>();

export function WSBatchesStackNavigator() {
  return (
    <Stack.Navigator
      screenOptions={{
        headerShown: false,
      }}
    >
      {/* 批次列表主页 */}
      <Stack.Screen name="WSBatches" component={WSBatchesScreen} />

      {/* 批次详情 (复用现有) */}
      <Stack.Screen
        name="BatchDetail"
        component={BatchDetailScreen}
        options={{ title: "批次详情" }}
      />

      {/* 开始生产 - 从生产计划创建批次 */}
      <Stack.Screen
        name="BatchStart"
        component={BatchStartScreen}
        options={{ title: "开始生产" }}
      />

      {/* 工艺环节录入 */}
      <Stack.Screen
        name="BatchStage"
        component={BatchStageScreen}
        options={{ title: "工艺环节" }}
      />

      {/* 完成生产 */}
      <Stack.Screen
        name="BatchComplete"
        component={BatchCompleteScreen}
        options={{ title: "完成生产" }}
      />

      {/* 原料消耗记录 */}
      <Stack.Screen
        name="MaterialConsumption"
        component={MaterialConsumptionScreen}
        options={{ title: "原料消耗" }}
      />

      {/* 调拨签收 */}
      <Stack.Screen
        name="TransferList"
        component={require("../../screens/factory-admin/inventory/TransferListScreen").default}
        options={{ title: "物料调拨" }}
      />
      <Stack.Screen
        name="TransferDetail"
        component={require("../../screens/factory-admin/inventory/TransferDetailScreen").default}
        options={{ title: "调拨详情" }}
      />

      {/* 报工 */}
      <Stack.Screen
        name="ScanReport"
        component={ScanReportScreen}
        options={{ title: "扫码报工" }}
      />
      <Stack.Screen
        name="TeamBatchReport"
        component={TeamBatchReportScreen}
        options={{ title: "班组报工" }}
      />
      <Stack.Screen
        name="ScanReportSuccess"
        component={ScanReportSuccessScreen}
        options={{ title: "报工成功" }}
      />
      <Stack.Screen
        name="DraftReports"
        component={DraftReportsScreen}
        options={{ title: "草稿管理" }}
      />
      <Stack.Screen
        name="LabelScan"
        component={LabelScanScreen}
        options={{ title: "标签扫描" }}
      />
      <Stack.Screen
        name="AIAnalysis"
        component={AIAnalysisScreen}
        options={{ title: "AI效率分析" }}
      />

      {/* 工序制生产 */}
      <Stack.Screen
        name="ProcessTaskList"
        component={ProcessTaskListScreen}
        options={{ title: "工序任务" }}
      />
      <Stack.Screen
        name="ProcessTaskDetail"
        component={ProcessTaskDetailScreen}
        options={{ title: "任务详情" }}
      />
      <Stack.Screen
        name="ProcessTaskHistory"
        component={ProcessTaskHistoryScreen}
        options={{ title: "任务历史" }}
      />
      <Stack.Screen
        name="ProcessRunOverview"
        component={ProcessRunOverviewScreen}
        options={{ title: "生产运行概览" }}
      />
      <Stack.Screen
        name="YieldBatchSelect"
        component={YieldBatchSelectScreen}
        options={{ title: "逐道报工" }}
      />
      <Stack.Screen
        name="YieldStepReport"
        component={YieldStepReportScreen}
        options={{ title: "逐道报工" }}
      />

      {/* 质检已迁移至 QualityInspectorNavigator */}
      {/* <Stack.Screen
        name="QualityCreate"
        component={CreateQualityRecordScreen}
        options={{ title: "创建质检" }}
      />
      <Stack.Screen
        name="QualityDetail"
        component={QualityInspectionDetailScreen}
        options={{ title: "质检详情" }}
      /> */}
    </Stack.Navigator>
  );
}

export default WSBatchesStackNavigator;
