/**
 * Warehouse 入库 Stack 导航器
 * 包含: 入库列表、入库详情、新建入库、质检、上架、扫码作业
 */

import React from "react";
import { createNativeStackNavigator } from "@react-navigation/native-stack";
import { WHInboundStackParamList } from "../../types/navigation";

// 导入入库相关页面组件
import WHInboundListScreen from "../../screens/warehouse/inbound/WHInboundListScreen";
import WHInboundDetailScreen from "../../screens/warehouse/inbound/WHInboundDetailScreen";
import WHInboundCreateScreen from "../../screens/warehouse/inbound/WHInboundCreateScreen";
import WHInspectScreen from "../../screens/warehouse/inbound/WHInspectScreen";
import WHPutawayScreen from "../../screens/warehouse/inbound/WHPutawayScreen";
import WHReceiptCreateScreen from "../../screens/warehouse/inbound/WHReceiptCreateScreen";
import WHScanOperationScreen from "../../screens/warehouse/shared/WHScanOperationScreen";
import SupplierDeliveryListScreen from "../../screens/restaurant/supplier-delivery/SupplierDeliveryListScreen";
import SupplierDeliveryCreateScreen from "../../screens/restaurant/supplier-delivery/SupplierDeliveryCreateScreen";
import SupplierDeliveryDetailScreen from "../../screens/restaurant/supplier-delivery/SupplierDeliveryDetailScreen";
import PriceAnomalyApprovalScreen from "../../screens/restaurant/supplier-delivery/PriceAnomalyApprovalScreen";

const Stack = createNativeStackNavigator<WHInboundStackParamList>();

export function WHInboundStackNavigator() {
  return (
    <Stack.Navigator
      screenOptions={{
        headerShown: false,
      }}
    >
      {/* 入库列表 */}
      <Stack.Screen name="WHInboundList" component={WHInboundListScreen} />

      {/* 入库详情 */}
      <Stack.Screen
        name="WHInboundDetail"
        component={WHInboundDetailScreen}
        options={{ title: "入库详情" }}
      />

      {/* 新建入库 */}
      <Stack.Screen
        name="WHInboundCreate"
        component={WHInboundCreateScreen}
        options={{ title: "新建入库" }}
      />

      {/* 质检 */}
      <Stack.Screen
        name="WHInspect"
        component={WHInspectScreen}
        options={{ title: "质检" }}
      />

      {/* 上架 */}
      <Stack.Screen
        name="WHPutaway"
        component={WHPutawayScreen}
        options={{ title: "上架入库" }}
      />

      {/* 扫码作业 */}
      <Stack.Screen
        name="WHScanOperation"
        component={WHScanOperationScreen}
        options={{ title: "扫码作业" }}
      />

      {/* W-ABA-1 Day 5: PDF 扫码 → 2-字段收货页 */}
      <Stack.Screen
        name="WHReceiptCreate"
        component={WHReceiptCreateScreen}
        options={{ title: "扫码入库录入" }}
      />

      <Stack.Screen name="SupplierDeliveryList" component={SupplierDeliveryListScreen} options={{ title: "待验收入库" }} />
      <Stack.Screen name="SupplierDeliveryCreate" component={SupplierDeliveryCreateScreen} options={{ title: "新建送货单" }} />
      <Stack.Screen name="SupplierDeliveryDetail" component={SupplierDeliveryDetailScreen} options={{ title: "送货单详情" }} />
      <Stack.Screen name="PriceAnomalyApproval" component={PriceAnomalyApprovalScreen} options={{ title: "价格异常审批" }} />
    </Stack.Navigator>
  );
}

export default WHInboundStackNavigator;
