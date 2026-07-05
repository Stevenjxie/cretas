/**
 * Warehouse 出货 Stack 导航器
 * 包含: 出货列表、出货详情、打包作业、装车管理、发货确认、物流跟踪、订单详情、扫码作业
 */

import React from "react";
import { createNativeStackNavigator } from "@react-navigation/native-stack";
import { WHOutboundStackParamList } from "../../types/navigation";

// 导入出货相关页面组件
import WHDeliveryConfirmListScreen from "../../screens/warehouse/outbound/WHDeliveryConfirmListScreen";
import WHDeliveryConfirmScreen from "../../screens/warehouse/outbound/WHDeliveryConfirmScreen";
import WHOutboundListScreen from "../../screens/warehouse/outbound/WHOutboundListScreen";
import WHOutboundDetailScreen from "../../screens/warehouse/outbound/WHOutboundDetailScreen";
import WHPackingScreen from "../../screens/warehouse/outbound/WHPackingScreen";
import WHLoadingScreen from "../../screens/warehouse/outbound/WHLoadingScreen";
import WHShippingConfirmScreen from "../../screens/warehouse/outbound/WHShippingConfirmScreen";
import WHTrackingDetailScreen from "../../screens/warehouse/outbound/WHTrackingDetailScreen";
import WHOrderDetailScreen from "../../screens/warehouse/outbound/WHOrderDetailScreen";
import WHOutboundIssueScreen from "../../screens/warehouse/outbound/WHOutboundIssueScreen";
import WHScanOperationScreen from "../../screens/warehouse/shared/WHScanOperationScreen";

const Stack = createNativeStackNavigator<WHOutboundStackParamList>();

export function WHOutboundStackNavigator() {
  return (
    <Stack.Navigator
      initialRouteName="WHDeliveryConfirmList"
      screenOptions={{
        headerShown: false,
      }}
    >
      {/* 真发货 (DLV-*) — 出货 tab 主入口: 销售发货单待仓库确认 → 确认扣减成品库存 */}
      <Stack.Screen name="WHDeliveryConfirmList" component={WHDeliveryConfirmListScreen} />

      {/* 发货确认 (填实际数量, 扣库存) */}
      <Stack.Screen
        name="WHDeliveryConfirm"
        component={WHDeliveryConfirmScreen}
        options={{ title: "发货确认" }}
      />

      {/* 手工出货登记 (旧 SH-* 流程, 不扣库存) — 降级为二级入口 */}
      <Stack.Screen name="WHOutboundList" component={WHOutboundListScreen} />

      {/* 出货详情 */}
      <Stack.Screen
        name="WHOutboundDetail"
        component={WHOutboundDetailScreen}
        options={{ title: "出货详情" }}
      />

      {/* 打包作业 */}
      <Stack.Screen
        name="WHPacking"
        component={WHPackingScreen}
        options={{ title: "打包作业" }}
      />

      {/* 装车管理 */}
      <Stack.Screen
        name="WHLoading"
        component={WHLoadingScreen}
        options={{ title: "装车管理" }}
      />

      {/* 发货确认 */}
      <Stack.Screen
        name="WHShippingConfirm"
        component={WHShippingConfirmScreen}
        options={{ title: "发货确认" }}
      />

      {/* 物流跟踪 */}
      <Stack.Screen
        name="WHTrackingDetail"
        component={WHTrackingDetailScreen}
        options={{ title: "物流跟踪" }}
      />

      {/* 订单详情 */}
      <Stack.Screen
        name="WHOrderDetail"
        component={WHOrderDetailScreen}
        options={{ title: "订单详情" }}
      />

      {/* 扫码作业 */}
      <Stack.Screen
        name="WHScanOperation"
        component={WHScanOperationScreen}
        options={{ title: "扫码作业" }}
      />

      {/* 扫码出库确认 (领料/出库, 扫物料批次标签后 2 字段闭环) */}
      <Stack.Screen
        name="WHOutboundIssue"
        component={WHOutboundIssueScreen}
        options={{ title: "扫码出库确认" }}
      />
    </Stack.Navigator>
  );
}

export default WHOutboundStackNavigator;
