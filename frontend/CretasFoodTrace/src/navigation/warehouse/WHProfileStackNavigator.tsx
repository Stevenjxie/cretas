/**
 * Warehouse 个人中心 Stack 导航器
 * 包含: 个人中心、编辑资料、设置、操作记录、预警列表、预警处理、召回管理、转化分析
 */

import React from "react";
import { createNativeStackNavigator } from "@react-navigation/native-stack";
import { WHProfileStackParamList } from "../../types/navigation";

// 导入个人中心相关页面组件
import WHProfileScreen from "../../screens/warehouse/profile/WHProfileScreen";
import WHProfileEditScreen from "../../screens/warehouse/profile/WHProfileEditScreen";
import WHSettingsScreen from "../../screens/warehouse/profile/WHSettingsScreen";
import WHOperationLogScreen from "../../screens/warehouse/profile/WHOperationLogScreen";
import WHIOStatisticsScreen from "../../screens/warehouse/inventory/WHIOStatisticsScreen";
import WHInventoryCheckScreen from "../../screens/warehouse/inventory/WHInventoryCheckScreen";
import WHExpireHandleScreen from "../../screens/warehouse/inventory/WHExpireHandleScreen";
import WastageReportScreen from "../../screens/warehouse/inventory/WastageReportScreen";
import WHAlertListScreen from "../../screens/warehouse/shared/WHAlertListScreen";
import WHAlertHandleScreen from "../../screens/warehouse/shared/WHAlertHandleScreen";
import WHRecallManageScreen from "../../screens/warehouse/shared/WHRecallManageScreen";
import WHConversionAnalysisScreen from "../../screens/warehouse/shared/WHConversionAnalysisScreen";
// 2026-07-30 客户反馈修复: 盘点记录列表 + 只读详情
import WHStocktakeListScreen from "../../screens/warehouse/inventory/WHStocktakeListScreen";
import WHStocktakeDetailScreen from "../../screens/warehouse/inventory/WHStocktakeDetailScreen";
import StocktakeEntryScreen from "../../screens/warehouse/inventory/StocktakeEntryScreen";

// 复用现有Profile页面
import FeedbackScreen from "../../screens/profile/FeedbackScreen";
import MembershipScreen from "../../screens/profile/MembershipScreen";

const Stack = createNativeStackNavigator<WHProfileStackParamList>();

export function WHProfileStackNavigator() {
  return (
    <Stack.Navigator
      screenOptions={{
        headerShown: false,
      }}
    >
      {/* 个人中心 */}
      <Stack.Screen name="WHProfile" component={WHProfileScreen} />

      {/* 编辑资料 */}
      <Stack.Screen
        name="WHProfileEdit"
        component={WHProfileEditScreen}
        options={{ title: "编辑资料" }}
      />

      {/* 设置 */}
      <Stack.Screen
        name="WHSettings"
        component={WHSettingsScreen}
        options={{ title: "设置" }}
      />

      {/* 操作记录 */}
      <Stack.Screen
        name="WHOperationLog"
        component={WHOperationLogScreen}
        options={{ title: "操作记录" }}
      />

      {/* 出入库统计 */}
      <Stack.Screen
        name="WHIOStatistics"
        component={WHIOStatisticsScreen}
        options={{ title: "出入库统计" }}
      />

      {/* 发起盘点 (选仓库 → 生成新盘点任务) */}
      <Stack.Screen
        name="WHInventoryCheck"
        component={WHInventoryCheckScreen}
        options={{ title: "发起盘点" }}
      />

      {/* 2026-07-30: 盘点记录列表 —— "常用功能 > 盘点记录" 菜单项的真正目的地
          (之前一直误跳到上面的 WHInventoryCheck "发起盘点", 客户反馈"无途径查看
          今日提交记录"正是这个文案/跳转不符导致的) */}
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

      {/* 2026-07-30: 未完成盘点续录 (从盘点记录列表跳入) */}
      <Stack.Screen
        name="StocktakeEntry"
        component={StocktakeEntryScreen}
        options={{ title: "盘点录入" }}
      />

      {/* 过期处理 */}
      <Stack.Screen
        name="WHExpireHandle"
        component={WHExpireHandleScreen}
        options={{ title: "过期处理" }}
      />

      {/* 报损提交 */}
      <Stack.Screen
        name="WastageReport"
        component={WastageReportScreen}
        options={{ title: "报损提交" }}
      />

      {/* 预警列表 */}
      <Stack.Screen
        name="WHAlertList"
        component={WHAlertListScreen}
        options={{ title: "预警列表" }}
      />

      {/* 预警处理 */}
      <Stack.Screen
        name="WHAlertHandle"
        component={WHAlertHandleScreen}
        options={{ title: "预警处理" }}
      />

      {/* 召回管理 */}
      <Stack.Screen
        name="WHRecallManage"
        component={WHRecallManageScreen}
        options={{ title: "召回管理" }}
      />

      {/* 转化分析 */}
      <Stack.Screen
        name="WHConversionAnalysis"
        component={WHConversionAnalysisScreen}
        options={{ title: "转化分析" }}
      />

      {/* 意见反馈 */}
      <Stack.Screen
        name="Feedback"
        component={FeedbackScreen}
        options={{ title: "意见反馈" }}
      />

      {/* 会员中心 */}
      <Stack.Screen
        name="Membership"
        component={MembershipScreen}
        options={{ title: "会员中心" }}
      />
    </Stack.Navigator>
  );
}

export default WHProfileStackNavigator;
