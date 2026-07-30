import React from 'react';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { ProcessingStackParamList } from '../types/navigation';

// 导入页面组件
import ProcessingDashboard from '../screens/processing/ProcessingDashboard';
import BatchListScreen from '../screens/processing/BatchListScreen';
import BatchDetailScreen from '../screens/processing/BatchDetailScreen';
import CreateBatchScreen from '../screens/processing/CreateBatchScreen';
// Legacy质检页面已移至 QualityInspectorNavigator
// import QualityInspectionListScreen from '../screens/legacy/quality-inspection/QualityInspectionListScreen';
import CostAnalysisDashboard from '../screens/processing/CostAnalysisDashboard';
import TimeRangeCostAnalysisScreen from '../screens/processing/TimeRangeCostAnalysisScreen';
import ProductionPlanManagementScreen from '../screens/processing/ProductionPlanManagementScreen';
import MaterialBatchManagementScreen from '../screens/processing/MaterialBatchManagementScreen';
import MaterialReceiptScreen from '../screens/processing/MaterialReceiptScreen';
import MaterialReceiptAIScreen from '../screens/processing/MaterialReceiptAIScreen';
import MaterialBatchSuccessScreen from '../screens/processing/MaterialBatchSuccessScreen';
// 2026-07-30 客户反馈新增: 物料需求单 (只读)
import MaterialRequisitionListScreen from '../screens/processing/MaterialRequisitionListScreen';
import MaterialRequisitionDetailScreen from '../screens/processing/MaterialRequisitionDetailScreen';

// AI智能分析页面 - Phase 3
import AIReportListScreen from '../screens/processing/AIReportListScreen';
import AIAnalysisDetailScreen from '../screens/processing/AIAnalysisDetailScreen';
import BatchComparisonScreen from '../screens/processing/BatchComparisonScreen';
import AIConversationHistoryScreen from '../screens/processing/AIConversationHistoryScreen';

// 设备监控页面 - Phase 3 P0-001
import EquipmentMonitoringScreen from '../screens/processing/EquipmentMonitoringScreen';

// 设备管理页面 - Phase 3 P3-设备: CRUD、搜索、状态
import EquipmentManagementScreen from '../screens/processing/EquipmentManagementScreen';

// AI分析详情页 - Phase 3 P1-001
import AIAnalysisScreen from '../screens/processing/AIAnalysisScreen';

// 质检记录页面 - 已迁移至 QualityInspectorNavigator
// import CreateQualityRecordScreen from '../screens/legacy/quality-inspection/CreateQualityRecordScreen';
// import QualityInspectionDetailScreen from '../screens/legacy/quality-inspection/QualityInspectionDetailScreen';

// 成本对比分析 - Phase 3 P1-003
import CostComparisonScreen from '../screens/processing/CostComparisonScreen';

// 设备告警系统 - Phase 3 P1-004
import EquipmentAlertsScreen from '../screens/processing/EquipmentAlertsScreen';

// 设备详情页 - Phase 3 P1-005
import EquipmentDetailScreen from '../screens/processing/EquipmentDetailScreen';

// Phase 3 P2 - 质检统计分析 (已迁移至 QualityInspectorNavigator)
// import QualityAnalyticsScreen from '../screens/processing/QualityAnalyticsScreen';

// Phase 3 P2 - 库存盘点 (使用仓库模块的实现)
import WHInventoryCheckScreen from '../screens/warehouse/inventory/WHInventoryCheckScreen';
// 2026-07-30 客户反馈修复: 发起盘点成功后的续录/提交 + 记录列表/详情 (见下方 Stack.Screen 注释)
import StocktakeEntryScreen from '../screens/warehouse/inventory/StocktakeEntryScreen';
import WHStocktakeListScreen from '../screens/warehouse/inventory/WHStocktakeListScreen';
import WHStocktakeDetailScreen from '../screens/warehouse/inventory/WHStocktakeDetailScreen';

// Phase 3 P2 - 异常预警
import ExceptionAlertScreen from '../screens/alerts/ExceptionAlertScreen';
import CreateExceptionScreen from '../screens/alerts/CreateExceptionScreen';

// 包装管理
import CreatePackagingScreen from '../screens/processing/CreatePackagingScreen';

// 原材料消耗记录
import MaterialConsumptionHistoryScreen from '../screens/processing/MaterialConsumptionHistoryScreen';

// 工位监控
import { WorkstationMonitorScreen } from '../screens/production/WorkstationMonitorScreen';

// 标签扫描 (shared模块)
import LabelScanScreen from '../screens/shared/LabelScanScreen';

// 工序制生产页面
import ProcessTaskListScreen from '../screens/processing/ProcessTaskListScreen';
import ProcessTaskDetailScreen from '../screens/processing/ProcessTaskDetailScreen';
import ProcessTaskHistoryScreen from '../screens/processing/ProcessTaskHistoryScreen';
import ProcessRunOverviewScreen from '../screens/processing/ProcessRunOverviewScreen';
import YieldBatchSelectScreen from '../screens/processing/YieldBatchSelectScreen';
import YieldStepReportScreen from '../screens/processing/YieldStepReportScreen';

// 报工页面
import ScanReportScreen from '../screens/processing/ScanReportScreen';
import TeamBatchReportScreen from '../screens/processing/TeamBatchReportScreen';
import DynamicReportScreen from '../screens/processing/DynamicReportScreen';
import NfcCheckinScreen from '../screens/processing/NfcCheckinScreen';

// 溯源功能 - Phase 3
import {
  TraceabilityScreen,
  TraceabilityDetailScreen,
  PublicTraceScreen,
} from '../screens/traceability';

// AI语音质检 - Phase 4 (已迁移至 QualityInspectorNavigator)
// import { VoiceInspectionScreen } from '../screens/processing/VoiceInspectionScreen';

const Stack = createNativeStackNavigator<ProcessingStackParamList>();

/**
 * 生产模块堆栈导航器
 * 包含所有生产相关页面的导航
 */
export function ProcessingStackNavigator() {
  return (
    <Stack.Navigator
      screenOptions={{
        headerShown: false, // 使用自定义Appbar
      }}
    >
      {/* 生产仪表板 - 入口页 */}
      <Stack.Screen
        name="ProcessingDashboard"
        component={ProcessingDashboard}
      />

      {/* 批次管理 */}
      <Stack.Screen
        name="BatchList"
        component={BatchListScreen}
      />
      <Stack.Screen
        name="BatchDetail"
        component={BatchDetailScreen}
      />
      <Stack.Screen
        name="CreateBatch"
        component={CreateBatchScreen}
      />
      <Stack.Screen
        name="EditBatch"
        component={CreateBatchScreen}
        options={{ title: '编辑批次' }}
      />

      {/* 质检管理 - 已迁移至 QualityInspectorNavigator */}
      {/* <Stack.Screen
        name="QualityInspectionList"
        component={QualityInspectionListScreen}
      />
      <Stack.Screen
        name="CreateQualityRecord"
        component={CreateQualityRecordScreen}
      />
      <Stack.Screen
        name="QualityInspectionDetail"
        component={QualityInspectionDetailScreen}
      /> */}

      {/* 成本分析 */}
      <Stack.Screen
        name="CostAnalysisDashboard"
        component={CostAnalysisDashboard}
      />
      <Stack.Screen
        name="TimeRangeCostAnalysis"
        component={TimeRangeCostAnalysisScreen}
      />
      <Stack.Screen
        name="CostComparison"
        component={CostComparisonScreen}
      />

      {/* 生产计划管理 */}
      <Stack.Screen
        name="ProductionPlanManagement"
        component={ProductionPlanManagementScreen}
      />

      {/* 原材料管理 - Phase 2新增 */}
      <Stack.Screen
        name="MaterialBatchManagement"
        component={MaterialBatchManagementScreen}
      />
      <Stack.Screen
        name="MaterialReceipt"
        component={MaterialReceiptScreen}
      />
      <Stack.Screen
        name="MaterialReceiptAI"
        component={MaterialReceiptAIScreen}
      />
      <Stack.Screen
        name="MaterialBatchSuccess"
        component={MaterialBatchSuccessScreen}
      />

      {/* 2026-07-30 客户反馈新增: 物料需求单 (只读) */}
      <Stack.Screen
        name="MaterialRequisitionList"
        component={MaterialRequisitionListScreen}
      />
      <Stack.Screen
        name="MaterialRequisitionDetail"
        component={MaterialRequisitionDetailScreen}
      />

      {/* AI智能分析 - Phase 3新增 */}
      <Stack.Screen
        name="AIReportList"
        component={AIReportListScreen}
      />
      <Stack.Screen
        name="AIAnalysisDetail"
        component={AIAnalysisDetailScreen}
      />
      <Stack.Screen
        name="BatchComparison"
        component={BatchComparisonScreen}
      />
      <Stack.Screen
        name="AIConversationHistory"
        component={AIConversationHistoryScreen}
      />

      {/* 设备监控 - Phase 3 P0-001 */}
      <Stack.Screen
        name="EquipmentMonitoring"
        component={EquipmentMonitoringScreen}
      />
      <Stack.Screen
        name="EquipmentManagement"
        component={EquipmentManagementScreen}
      />
      <Stack.Screen
        name="EquipmentAlerts"
        component={EquipmentAlertsScreen}
      />
      <Stack.Screen
        name="EquipmentDetail"
        component={EquipmentDetailScreen}
      />

      {/* AI分析详情 - Phase 3 P1-001 */}
      <Stack.Screen
        name="AIAnalysis"
        component={AIAnalysisScreen}
      />

      {/* Phase 3 P2 - 质检统计分析 (已迁移至 QualityInspectorNavigator) */}
      {/* <Stack.Screen
        name="QualityAnalytics"
        component={QualityAnalyticsScreen}
      /> */}

      {/* Phase 3 P2 - 库存盘点 */}
      <Stack.Screen
        name="InventoryCheck"
        component={WHInventoryCheckScreen}
      />

      {/* 2026-07-30 客户反馈修复: 发起盘点成功后的续录/提交屏 + 记录列表/详情 —
          之前完全没注册, 生产角色从本 Tab 发起盘点会在 navigate("StocktakeEntry")
          处卡死 (crash/dead-end)，永远进不了提交审核 */}
      <Stack.Screen
        name="StocktakeEntry"
        component={StocktakeEntryScreen}
      />
      <Stack.Screen
        name="WHStocktakeList"
        component={WHStocktakeListScreen}
      />
      <Stack.Screen
        name="WHStocktakeDetail"
        component={WHStocktakeDetailScreen}
      />

      {/* Phase 3 P2 - 异常预警系统 */}
      <Stack.Screen
        name="ExceptionAlert"
        component={ExceptionAlertScreen}
      />
      <Stack.Screen
        name="CreateException"
        component={CreateExceptionScreen}
      />

      {/* 包装管理 */}
      <Stack.Screen
        name="CreatePackaging"
        component={CreatePackagingScreen}
      />

      {/* 原材料消耗记录 */}
      <Stack.Screen
        name="MaterialConsumptionHistory"
        component={MaterialConsumptionHistoryScreen}
      />

      {/* 溯源功能 - Phase 3 */}
      <Stack.Screen
        name="Traceability"
        component={TraceabilityScreen}
      />
      <Stack.Screen
        name="TraceabilityDetail"
        component={TraceabilityDetailScreen}
      />
      <Stack.Screen
        name="PublicTrace"
        component={PublicTraceScreen}
      />

      {/* AI语音质检 - Phase 4 (已迁移至 QualityInspectorNavigator) */}
      {/* <Stack.Screen
        name="VoiceInspection"
        component={VoiceInspectionScreen}
      /> */}

      {/* 工位监控 */}
      <Stack.Screen
        name="WorkstationMonitor"
        component={WorkstationMonitorScreen}
      />

      {/* 标签扫描 - 通用组件 */}
      <Stack.Screen
        name="LabelScan"
        component={LabelScanScreen}
      />

      {/* 工序制生产 - Process Mode */}
      <Stack.Screen
        name="ProcessTaskList"
        component={ProcessTaskListScreen}
      />
      <Stack.Screen
        name="ProcessTaskDetail"
        component={ProcessTaskDetailScreen}
      />
      <Stack.Screen
        name="ProcessTaskHistory"
        component={ProcessTaskHistoryScreen}
      />
      <Stack.Screen
        name="ProcessRunOverview"
        component={ProcessRunOverviewScreen}
      />
      <Stack.Screen
        name="YieldBatchSelect"
        component={YieldBatchSelectScreen}
      />
      <Stack.Screen
        name="YieldStepReport"
        component={YieldStepReportScreen}
      />

      {/* 报工 */}
      <Stack.Screen
        name="ScanReport"
        component={ScanReportScreen}
      />
      <Stack.Screen
        name="TeamBatchReport"
        component={TeamBatchReportScreen}
      />
      <Stack.Screen
        name="DynamicReport"
        component={DynamicReportScreen}
      />
      <Stack.Screen
        name="NfcCheckin"
        component={NfcCheckinScreen}
      />
    </Stack.Navigator>
  );
}

export default ProcessingStackNavigator;
