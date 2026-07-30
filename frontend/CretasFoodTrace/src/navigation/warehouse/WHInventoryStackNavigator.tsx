/**
 * Warehouse 库存 Stack 导航器
 * 包含: 库存列表、库存详情、批次详情、盘点、调拨、库位管理、过期处理、温控监测、出入库统计、批次溯源
 */

import React from "react";
import { createNativeStackNavigator } from "@react-navigation/native-stack";
import { WHInventoryStackParamList } from "../../types/navigation";

// 导入库存相关页面组件
import WHInventoryListScreen from "../../screens/warehouse/inventory/WHInventoryListScreen";
import WHInventoryDetailScreen from "../../screens/warehouse/inventory/WHInventoryDetailScreen";
import WHBatchDetailScreen from "../../screens/warehouse/inventory/WHBatchDetailScreen";
import WHInventoryCheckScreen from "../../screens/warehouse/inventory/WHInventoryCheckScreen";
import WHInventoryTransferScreen from "../../screens/warehouse/inventory/WHInventoryTransferScreen";
import WHLocationManageScreen from "../../screens/warehouse/inventory/WHLocationManageScreen";
import WHExpireHandleScreen from "../../screens/warehouse/inventory/WHExpireHandleScreen";
import WHTempMonitorScreen from "../../screens/warehouse/inventory/WHTempMonitorScreen";
import WHIOStatisticsScreen from "../../screens/warehouse/inventory/WHIOStatisticsScreen";
import WHBatchTraceScreen from "../../screens/warehouse/shared/WHBatchTraceScreen";
// 库存预警相关页面
import InventoryAlertScreen from "../../screens/warehouse/alerts/InventoryAlertScreen";
import AlertDetailScreen from "../../screens/warehouse/alerts/AlertDetailScreen";
import InventoryWarningsScreen from "../../screens/warehouse/inventory/InventoryWarningsScreen";
import WHTransitLedgerScreen from "../../screens/warehouse/inventory/WHTransitLedgerScreen";
// SP7: 盘点录入 / 报损提交 / 调拨接收 (R-C)
import StocktakeEntryScreen from "../../screens/warehouse/inventory/StocktakeEntryScreen";
import WastageReportScreen from "../../screens/warehouse/inventory/WastageReportScreen";
import TransferReceiveScreen from "../../screens/warehouse/inventory/TransferReceiveScreen";
// 2026-07-30 客户反馈修复: 盘点记录列表 + 只读详情
import WHStocktakeListScreen from "../../screens/warehouse/inventory/WHStocktakeListScreen";
import WHStocktakeDetailScreen from "../../screens/warehouse/inventory/WHStocktakeDetailScreen";

const Stack = createNativeStackNavigator<WHInventoryStackParamList>();

export function WHInventoryStackNavigator() {
  return (
    <Stack.Navigator
      screenOptions={{
        headerShown: false,
      }}
    >
      {/* 库存列表 */}
      <Stack.Screen name="WHInventoryList" component={WHInventoryListScreen} />

      {/* 库存详情 */}
      <Stack.Screen
        name="WHInventoryDetail"
        component={WHInventoryDetailScreen}
        options={{ title: "库存详情" }}
      />

      {/* 批次详情 */}
      <Stack.Screen
        name="WHBatchDetail"
        component={WHBatchDetailScreen}
        options={{ title: "批次详情" }}
      />

      {/* 盘点 */}
      <Stack.Screen
        name="WHInventoryCheck"
        component={WHInventoryCheckScreen}
        options={{ title: "库存盘点" }}
      />

      {/* 调拨 */}
      <Stack.Screen
        name="WHInventoryTransfer"
        component={WHInventoryTransferScreen}
        options={{ title: "库存调拨" }}
      />

      {/* 库位管理 */}
      <Stack.Screen
        name="WHLocationManage"
        component={WHLocationManageScreen}
        options={{ title: "库位管理" }}
      />

      {/* 过期处理 */}
      <Stack.Screen
        name="WHExpireHandle"
        component={WHExpireHandleScreen}
        options={{ title: "过期处理" }}
      />

      {/* 温控监测 */}
      <Stack.Screen
        name="WHTempMonitor"
        component={WHTempMonitorScreen}
        options={{ title: "温控监测" }}
      />

      {/* 出入库统计 */}
      <Stack.Screen
        name="WHIOStatistics"
        component={WHIOStatisticsScreen}
        options={{ title: "出入库统计" }}
      />

      {/* 批次溯源 */}
      <Stack.Screen
        name="WHBatchTrace"
        component={WHBatchTraceScreen}
        options={{ title: "批次溯源" }}
      />

      {/* 库存预警列表 */}
      <Stack.Screen
        name="WHInventoryAlert"
        component={InventoryAlertScreen}
        options={{ title: "库存预警" }}
      />

      {/* 预警详情处理 */}
      <Stack.Screen
        name="WHAlertDetail"
        component={AlertDetailScreen}
        options={{ title: "预警处理" }}
      />

      {/* 库存预警综合管理（即将过期 / 已过期 / 低库存 三 Tab） */}
      <Stack.Screen
        name="WHInventoryWarnings"
        component={InventoryWarningsScreen}
        options={{ title: "库存预警管理" }}
      />

      {/* SP7: 盘点录入（每屏一品，F4 防呆）*/}
      <Stack.Screen
        name="WHTransitLedger"
        component={WHTransitLedgerScreen}
        options={{ title: "\u4e2d\u8f6c\u5b9e\u6536\u786e\u8ba4" }}
      />

      <Stack.Screen
        name="StocktakeEntry"
        component={StocktakeEntryScreen}
        options={{ title: "盘点录入" }}
      />

      {/* SP7: 报损提交（必须照片，双轨审批）*/}
      <Stack.Screen
        name="WastageReport"
        component={WastageReportScreen}
        options={{ title: "报损单" }}
      />

      {/* SP7: 调拨接收（差异自动记录）*/}
      <Stack.Screen
        name="TransferReceive"
        component={TransferReceiveScreen}
        options={{ title: "调拨接收" }}
      />

      {/* 2026-07-30: 盘点记录列表（今日/历史提交记录）*/}
      <Stack.Screen
        name="WHStocktakeList"
        component={WHStocktakeListScreen}
        options={{ title: "盘点记录" }}
      />

      {/* 2026-07-30: 盘点记录只读详情 */}
      <Stack.Screen
        name="WHStocktakeDetail"
        component={WHStocktakeDetailScreen}
        options={{ title: "盘点详情" }}
      />
    </Stack.Navigator>
  );
}

export default WHInventoryStackNavigator;
