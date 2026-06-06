/**
 * Factory Admin 管理 Stack 导航器
 * 包含: 管理中心、员工管理、设备管理、各类基础数据管理
 */

import React from "react";
import { createNativeStackNavigator } from "@react-navigation/native-stack";
import { FAManagementStackParamList } from "../../types/navigation";
import { useAuthStore } from "../../store/authStore";
import { isRestaurant } from "../../utils/factoryType";

// 导入页面组件
import FAManagementScreen from "../../screens/factory-admin/management/FAManagementScreen";

// 复用现有管理页面
import UserManagementScreen from "../../screens/management/UserManagementScreen";
import DepartmentManagementScreen from "../../screens/management/DepartmentManagementScreen";
import ProductTypeManagementScreen from "../../screens/management/ProductTypeManagementScreen";
import MaterialTypeManagementScreen from "../../screens/management/MaterialTypeManagementScreen";
import SupplierManagementScreen from "../../screens/management/SupplierManagementScreen";
import CustomerManagementScreen from "../../screens/management/CustomerManagementScreen";
import ShipmentManagementScreen from "../../screens/management/ShipmentManagementScreen";
import ConversionRateScreen from "../../screens/management/ConversionRateScreen";
import MaterialConversionDetailScreen from "../../screens/management/MaterialConversionDetailScreen";
import DisposalRecordManagementScreen from "../../screens/management/DisposalRecordManagementScreen";
import EquipmentManagementScreen from "../../screens/processing/EquipmentManagementScreen";
import EquipmentDetailScreen from "../../screens/processing/EquipmentDetailScreen";
import SchemaConfigScreen from "../../screens/factory-admin/config/SchemaConfigScreen";
import { FormTemplateListScreen } from "../../screens/factory-admin/config/FormTemplateListScreen";
import { FormTemplateDetailScreen } from "../../screens/factory-admin/config/FormTemplateDetailScreen";
import { PageEditorScreen } from "../../screens/factory-admin/pageeditor/PageEditorScreen";
import RuleConfigurationScreen from "../../screens/management/RuleConfigurationScreen";
import AIBusinessInitScreen from "../../screens/factory-admin/config/AIBusinessInitScreen";
import EncodingRuleConfigScreen from "../../screens/factory-admin/config/EncodingRuleConfigScreen";
import { CodeRuleDetailScreen } from "../../screens/factory-admin/config/CodeRuleDetailScreen";
import QualityCheckItemConfigScreen from "../../screens/factory-admin/config/QualityCheckItemConfigScreen";
import QualityCheckItemDetailScreen from "../../screens/factory-admin/management/QualityCheckItemDetailScreen";
import SopConfigScreen from "../../screens/management/SopConfigScreen";
import { FAIntentViewScreen } from "../../screens/factory-admin/management/FAIntentViewScreen";
import { IntentConfigDetailScreen } from "../../screens/factory-admin/management/IntentConfigDetailScreen";

// IoT 电子秤设备管理
import { IotDeviceListScreen } from "../../screens/factory-admin/iot/IotDeviceListScreen";
import { IotDeviceDetailScreen } from "../../screens/factory-admin/iot/IotDeviceDetailScreen";
import { IotDeviceCreateScreen } from "../../screens/factory-admin/iot/IotDeviceCreateScreen";
import { ScaleTestScreen } from "../../screens/factory-admin/iot/ScaleTestScreen";

// ISAPI 海康威视摄像头设备管理
import { IsapiDeviceListScreen } from "../../screens/factory-admin/isapi/IsapiDeviceListScreen";
import { IsapiDeviceDetailScreen } from "../../screens/factory-admin/isapi/IsapiDeviceDetailScreen";
import { IsapiDeviceCreateScreen } from "../../screens/factory-admin/isapi/IsapiDeviceCreateScreen";
// IsapiDeviceDiscoveryScreen removed - now using UnifiedDeviceDiscoveryScreen
import { IsapiSmartConfigScreen } from "../../screens/factory-admin/isapi/IsapiSmartConfigScreen";
import { DeviceSetupWizardScreen } from "../../screens/factory-admin/isapi/DeviceSetupWizardScreen";

// 智能设备添加
import { SmartDeviceAddScreen } from "../../screens/factory-admin/device/SmartDeviceAddScreen";
import { CameraAddMethodScreen } from "../../screens/factory-admin/device/CameraAddMethodScreen";
import { AIDeviceInputScreen } from "../../screens/factory-admin/device/AIDeviceInputScreen";

// 统一设备管理中心
import { UnifiedDeviceManagementScreen } from "../../screens/factory-admin/device/UnifiedDeviceManagementScreen";

// 标签自动识别监控
import { LabelRecognitionMonitorScreen } from "../../screens/production/LabelRecognitionMonitorScreen";

// 设备智能分析
import { EquipmentAnalysisScreen } from "../../screens/factory-admin/equipment/EquipmentAnalysisScreen";

// 统一多品牌设备发现
import { UnifiedDeviceDiscoveryScreen } from "../../screens/factory-admin/camera/UnifiedDeviceDiscoveryScreen";

// NFC 标签管理
import NfcTagManagementScreen from "../../screens/processing/NfcTagManagementScreen";

// 工序制生产页面
import ProcessTaskListScreen from "../../screens/processing/ProcessTaskListScreen";
import ProcessTaskDetailScreen from "../../screens/processing/ProcessTaskDetailScreen";
import ProcessTaskHistoryScreen from "../../screens/processing/ProcessTaskHistoryScreen";
import ProcessRunOverviewScreen from "../../screens/processing/ProcessRunOverviewScreen";
import ProcessTaskApprovalScreen from "../../screens/processing/ProcessTaskApprovalScreen";
import YieldBatchSelectScreen from "../../screens/processing/YieldBatchSelectScreen";
import YieldStepReportScreen from "../../screens/processing/YieldStepReportScreen";

// 进销存管理
import PurchaseOrderListScreen from "../../screens/factory-admin/inventory/PurchaseOrderListScreen";
import PurchaseOrderCreateScreen from "../../screens/factory-admin/inventory/PurchaseOrderCreateScreen";
import PurchaseOrderDetailScreen from "../../screens/factory-admin/inventory/PurchaseOrderDetailScreen";
import PurchaseOrderFinanceReviewListScreen from "../../screens/factory-admin/inventory/PurchaseOrderFinanceReviewListScreen";
import PurchaseOrderFinanceReviewScreen from "../../screens/factory-admin/inventory/PurchaseOrderFinanceReviewScreen";
import SalesOrderListScreen from "../../screens/factory-admin/inventory/SalesOrderListScreen";
import SalesOrderDetailScreen from "../../screens/factory-admin/inventory/SalesOrderDetailScreen";
import SalesOrderShortageReviewScreen from "../../screens/factory-admin/inventory/SalesOrderShortageReviewScreen";
import FinishedGoodsListScreen from "../../screens/factory-admin/inventory/FinishedGoodsListScreen";
import TransferListScreen from "../../screens/factory-admin/inventory/TransferListScreen";
import TransferDetailScreen from "../../screens/factory-admin/inventory/TransferDetailScreen";
import ArApOverviewScreen from "../../screens/factory-admin/inventory/ArApOverviewScreen";
import PaymentRecordScreen from "../../screens/factory-admin/finance/PaymentRecordScreen";
import RdRequestCreateScreen from "../../screens/factory-admin/rd/RdRequestCreateScreen";
import PriceListScreen from "../../screens/factory-admin/inventory/PriceListScreen";
import ReturnOrderListScreen from "../../screens/factory-admin/inventory/ReturnOrderListScreen";
import ReturnOrderDetailScreen from "../../screens/factory-admin/inventory/ReturnOrderDetailScreen";
import WorkReportApprovalScreen from "../../screens/factory-admin/management/WorkReportApprovalScreen";

// 餐饮模块页面
import RecipeListScreen from "../../screens/restaurant/recipes/RecipeListScreen";
import RecipeDetailScreen from "../../screens/restaurant/recipes/RecipeDetailScreen";
import RecipeEditScreen from "../../screens/restaurant/recipes/RecipeEditScreen";
import RequisitionCreateScreen from "../../screens/restaurant/requisition/RequisitionCreateScreen";
import ChefRequisitionListScreen from "../../screens/restaurant/requisition/ChefRequisitionListScreen";
import ChefRequisitionCreateScreen from "../../screens/restaurant/requisition/ChefRequisitionCreateScreen";
import RequisitionApprovalScreen from "../../screens/restaurant/requisition/RequisitionApprovalScreen";
import RequisitionDetailScreen from "../../screens/restaurant/requisition/RequisitionDetailScreen";
import WastageListScreen from "../../screens/restaurant/wastage/WastageListScreen";
import WastageCreateScreen from "../../screens/restaurant/wastage/WastageCreateScreen";
import StocktakingListScreen from "../../screens/restaurant/stocktaking/StocktakingListScreen";
import StocktakingExecuteScreen from "../../screens/restaurant/stocktaking/StocktakingExecuteScreen";
import StocktakingSummaryScreen from "../../screens/restaurant/stocktaking/StocktakingSummaryScreen";
import SupplierDeliveryListScreen from "../../screens/restaurant/supplier-delivery/SupplierDeliveryListScreen";
import SupplierDeliveryCreateScreen from "../../screens/restaurant/supplier-delivery/SupplierDeliveryCreateScreen";
import SupplierDeliveryDetailScreen from "../../screens/restaurant/supplier-delivery/SupplierDeliveryDetailScreen";
import PriceAnomalyApprovalScreen from "../../screens/restaurant/supplier-delivery/PriceAnomalyApprovalScreen";

// Track D2 — 工序管理 (M-WP-1) + 产品工序配置 (M-WP-2)
import WorkProcessListScreen from "../../screens/factory/workprocess/WorkProcessListScreen";
import WorkProcessCreateScreen from "../../screens/factory/workprocess/WorkProcessCreateScreen";
import ProductWorkProcessConfigScreen from "../../screens/factory/workprocess/ProductWorkProcessConfigScreen";

const Stack = createNativeStackNavigator<FAManagementStackParamList>();

export function FAManagementStackNavigator() {
  const user = useAuthStore(state => state.user);
  const isRestaurantMode = isRestaurant(user);

  return (
    <Stack.Navigator
      screenOptions={{
        headerShown: false,
      }}
    >
      {/* 管理中心主页 */}
      <Stack.Screen name="FAManagement" component={FAManagementScreen} />

      {/* 员工管理 (复用现有) */}
      <Stack.Screen
        name="EmployeeList"
        component={UserManagementScreen}
        options={{ title: "员工管理" }}
      />

      {/* 员工详情 - 临时使用员工列表 */}
      <Stack.Screen
        name="EmployeeDetail"
        component={UserManagementScreen}
        options={{ title: "员工详情" }}
      />

      {/* 工厂专属路由 - 餐饮版不注册，防止路由泄露 */}
      {!isRestaurantMode && (
        <>
          {/* 设备管理 (复用现有) */}
          <Stack.Screen
            name="EquipmentList"
            component={EquipmentManagementScreen}
            options={{ title: "设备管理" }}
          />

          {/* 设备智能分析 */}
          <Stack.Screen
            name="EquipmentAnalysis"
            component={EquipmentAnalysisScreen}
            options={{ title: "设备智能分析" }}
          />

          {/* 设备详情 (复用现有) */}
          <Stack.Screen
            name="EquipmentDetail"
            component={EquipmentDetailScreen}
            options={{ title: "设备详情" }}
          />

          {/* 统一设备管理中心 */}
          <Stack.Screen
            name="UnifiedDeviceManagement"
            component={UnifiedDeviceManagementScreen}
            options={{ title: "设备中心" }}
          />

          {/* IoT 电子秤设备管理 */}
          <Stack.Screen
            name="IotDeviceList"
            component={IotDeviceListScreen}
            options={{ title: "IoT 设备管理" }}
          />
          <Stack.Screen
            name="IotDeviceDetail"
            component={IotDeviceDetailScreen}
            options={{ title: "设备详情" }}
          />
          <Stack.Screen
            name="IotDeviceCreate"
            component={IotDeviceCreateScreen}
            options={{ title: "添加设备" }}
          />
          <Stack.Screen
            name="ScaleTest"
            component={ScaleTestScreen}
            options={{ title: "秤数据测试" }}
          />

          {/* ISAPI 海康威视摄像头设备管理 */}
          <Stack.Screen
            name="IsapiDeviceList"
            component={IsapiDeviceListScreen}
            options={{ title: "摄像头管理" }}
          />
          <Stack.Screen
            name="IsapiDeviceDetail"
            component={IsapiDeviceDetailScreen}
            options={{ title: "摄像头详情" }}
          />
          <Stack.Screen
            name="IsapiDeviceCreate"
            component={IsapiDeviceCreateScreen}
            options={{ title: "添加摄像头" }}
          />
          {/* 旧路由重定向到统一发现页面，保持兼容性 */}
          <Stack.Screen
            name="IsapiDeviceDiscovery"
            component={UnifiedDeviceDiscoveryScreen}
            options={{ title: "设备发现" }}
          />
          <Stack.Screen
            name="IsapiSmartConfig"
            component={IsapiSmartConfigScreen}
            options={{ title: "智能分析配置" }}
          />
          <Stack.Screen
            name="DeviceSetupWizard"
            component={DeviceSetupWizardScreen}
            options={{ title: "设备配置向导" }}
          />

          {/* 智能设备添加 */}
          <Stack.Screen
            name="SmartDeviceAdd"
            component={SmartDeviceAddScreen}
            options={{ title: "添加设备" }}
          />
          <Stack.Screen
            name="CameraAddMethod"
            component={CameraAddMethodScreen}
            options={{ title: "添加摄像头" }}
          />
          <Stack.Screen
            name="AIDeviceInput"
            component={AIDeviceInputScreen}
            options={{ title: "AI识别添加" }}
          />

          {/* 标签自动识别监控 */}
          <Stack.Screen
            name="LabelRecognitionMonitor"
            component={LabelRecognitionMonitorScreen}
            options={{ title: "标签识别监控" }}
          />

          {/* 统一多品牌设备发现 */}
          <Stack.Screen
            name="UnifiedDeviceDiscovery"
            component={UnifiedDeviceDiscoveryScreen}
            options={{ title: "设备发现" }}
          />

          {/* NFC 标签管理 */}
          <Stack.Screen
            name="NfcTagManagement"
            component={NfcTagManagementScreen}
            options={{ title: "NFC标签管理" }}
          />

          {/* 转换率管理 (工厂专属) */}
          <Stack.Screen
            name="ConversionRate"
            component={ConversionRateScreen}
            options={{ title: "转换率配置" }}
          />

          {/* 单原料换算配置详情 (工厂专属) — FA 栈此前漏注册, 点原料打不开, 2026-06-04 补 */}
          <Stack.Screen
            name="MaterialConversionDetail"
            component={MaterialConversionDetailScreen}
            options={{ title: "换算配置" }}
          />

          {/* 质检项配置 (工厂专属) */}
          <Stack.Screen
            name="QualityCheckItemConfig"
            component={QualityCheckItemConfigScreen}
            options={{ title: "质检项配置" }}
          />

          {/* 质检项详情 (工厂专属) */}
          <Stack.Screen
            name="QualityCheckItemDetail"
            component={QualityCheckItemDetailScreen}
            options={{ title: "质检项详情" }}
          />

          {/* SOP 流程配置 (工厂专属) */}
          <Stack.Screen
            name="SopConfig"
            component={SopConfigScreen}
            options={{ title: "SOP配置" }}
          />

          {/* 报工审批 (工厂专属) */}
          <Stack.Screen
            name="WorkReportApproval"
            component={WorkReportApprovalScreen}
            options={{ title: "报工审批" }}
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
            name="ProcessTaskApproval"
            component={ProcessTaskApprovalScreen}
            options={{ title: "报工审批" }}
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
        </>
      )}

      {/* 产品类型管理 (复用现有) */}
      <Stack.Screen
        name="ProductTypeManagement"
        component={ProductTypeManagementScreen}
        options={{ title: "产品类型" }}
      />

      {/* Track D2 — 工序管理 + 产品工序配置 (六扇门第四次会议) */}
      <Stack.Screen
        name="WorkProcessList"
        component={WorkProcessListScreen}
        options={{ title: "工序管理" }}
      />
      <Stack.Screen
        name="WorkProcessCreate"
        component={WorkProcessCreateScreen}
        options={{ title: "新增工序" }}
      />
      <Stack.Screen
        name="ProductWorkProcessConfig"
        component={ProductWorkProcessConfigScreen}
        options={{ title: "产品工序配置" }}
      />

      {/* 原材料类型管理 (复用现有) */}
      <Stack.Screen
        name="MaterialTypeManagement"
        component={MaterialTypeManagementScreen}
        options={{ title: "原材料类型" }}
      />

      {/* 部门管理 (复用现有) */}
      <Stack.Screen
        name="DepartmentManagement"
        component={DepartmentManagementScreen}
        options={{ title: "部门管理" }}
      />

      {/* 供应商管理 (复用现有) */}
      <Stack.Screen
        name="SupplierManagement"
        component={SupplierManagementScreen}
        options={{ title: "供应商管理" }}
      />

      {/* 客户管理 (复用现有) */}
      <Stack.Screen
        name="CustomerManagement"
        component={CustomerManagementScreen}
        options={{ title: "客户管理" }}
      />

      {/* 出货管理 (复用现有) */}
      <Stack.Screen
        name="ShipmentManagement"
        component={ShipmentManagementScreen}
        options={{ title: "出货管理" }}
      />

      {/* 报废记录管理 (复用现有) */}
      <Stack.Screen
        name="DisposalRecordManagement"
        component={DisposalRecordManagementScreen}
        options={{ title: "报废记录" }}
      />

      {/* Schema 配置 (AI 创建字段) */}
      <Stack.Screen
        name="SchemaConfig"
        component={SchemaConfigScreen}
        options={{ title: "表单配置" }}
      />

      {/* 低代码页面编辑器 (Track A C-CANVAS-PAGE) */}
      <Stack.Screen
        name="PageEditor"
        component={PageEditorScreen}
        options={{ title: "页面编辑器", headerShown: true }}
      />

      {/* 表单模版管理 */}
      <Stack.Screen
        name="FormTemplateList"
        component={FormTemplateListScreen}
        options={{ title: "表单模版" }}
      />
      <Stack.Screen
        name="FormTemplateDetail"
        component={FormTemplateDetailScreen}
        options={{ title: "模版详情" }}
      />

      {/* 规则配置 (Drools 规则 + 状态机) */}
      <Stack.Screen
        name="RuleConfiguration"
        component={RuleConfigurationScreen}
        options={{ title: "规则配置" }}
      />

      {/* AI 智能初始化业务数据 (P1.5) */}
      <Stack.Screen
        name="AIBusinessInit"
        component={AIBusinessInitScreen}
        options={{ title: "AI 智能初始化" }}
      />

      {/* 编码规则配置 */}
      <Stack.Screen
        name="EncodingRuleConfig"
        component={EncodingRuleConfigScreen}
        options={{ title: "编码规则" }}
      />

      {/* 编码规则详情/编辑 */}
      <Stack.Screen
        name="CodeRuleDetail"
        component={CodeRuleDetailScreen}
        options={{ title: "编码规则详情" }}
      />

      {/* 意图配置查看 (只读) */}
      <Stack.Screen
        name="IntentView"
        component={FAIntentViewScreen}
        options={{ title: "意图配置" }}
      />

      {/* 意图配置详情 (编辑) */}
      <Stack.Screen
        name="IntentConfigDetail"
        component={IntentConfigDetailScreen}
        options={{ title: "意图详情" }}
      />

      {/* 进销存管理 (工厂版 + 餐饮版通用) */}
      <Stack.Screen
        name="PurchaseOrderList"
        component={PurchaseOrderListScreen}
        options={{ title: "采购订单" }}
      />
      <Stack.Screen
        name="PurchaseOrderCreate"
        component={PurchaseOrderCreateScreen}
        options={{ title: "新建采购订单" }}
      />
      <Stack.Screen
        name="PurchaseOrderDetail"
        component={PurchaseOrderDetailScreen}
        options={{ title: "采购单详情" }}
      />
      {/* Sprint2-J P-FIN-1: 财务审核 (PENDING_FINANCE_REVIEW) */}
      <Stack.Screen
        name="PurchaseOrderFinanceReviewList"
        component={PurchaseOrderFinanceReviewListScreen}
        options={{ title: "财务待审" }}
      />
      <Stack.Screen
        name="PurchaseOrderFinanceReview"
        component={PurchaseOrderFinanceReviewScreen}
        options={{ title: "财务审核" }}
      />
      <Stack.Screen
        name="SalesOrderList"
        component={SalesOrderListScreen}
        options={{ title: "销售订单" }}
      />
      <Stack.Screen
        name="SalesOrderDetail"
        component={SalesOrderDetailScreen}
        options={{ title: "销售单详情" }}
      />
      <Stack.Screen
        name="SalesOrderShortageReview"
        component={SalesOrderShortageReviewScreen}
        options={{ title: "销售单缺料分析" }}
      />
      <Stack.Screen
        name="FinishedGoodsList"
        component={FinishedGoodsListScreen}
        options={{ title: "成品库存" }}
      />
      <Stack.Screen
        name="TransferList"
        component={TransferListScreen}
        options={{ title: "调拨管理" }}
      />
      <Stack.Screen
        name="TransferDetail"
        component={TransferDetailScreen}
        options={{ title: "调拨详情" }}
      />
      <Stack.Screen
        name="ArApOverview"
        component={ArApOverviewScreen}
        options={{ title: "应收应付" }}
      />
      <Stack.Screen
        name="PaymentRecord"
        component={PaymentRecordScreen}
        options={{ title: "录入收款" }}
      />
      <Stack.Screen
        name="RdRequestCreate"
        component={RdRequestCreateScreen}
        options={{ title: "新建研发需求" }}
      />
      <Stack.Screen
        name="PriceList"
        component={PriceListScreen}
        options={{ title: "价格表" }}
      />
      <Stack.Screen
        name="ReturnOrderList"
        component={ReturnOrderListScreen}
        options={{ title: "退货管理" }}
      />
      <Stack.Screen
        name="ReturnOrderDetail"
        component={ReturnOrderDetailScreen}
        options={{ title: "退货详情" }}
      />

      {/* 餐饮模块 (restaurant mode only in UI, but routes registered for all) */}
      {isRestaurantMode && (
        <>
          <Stack.Screen name="RecipeList" component={RecipeListScreen} options={{ title: "配方管理" }} />
          <Stack.Screen name="RecipeDetail" component={RecipeDetailScreen} options={{ title: "配方详情" }} />
          <Stack.Screen name="RecipeEdit" component={RecipeEditScreen} options={{ title: "编辑配方" }} />
          <Stack.Screen name="RequisitionCreate" component={RequisitionCreateScreen} options={{ title: "新建领料" }} />
          <Stack.Screen name="ChefRequisitionList" component={ChefRequisitionListScreen} options={{ title: "报货追踪" }} />
          <Stack.Screen name="ChefRequisitionCreate" component={ChefRequisitionCreateScreen} options={{ title: "档口报货" }} />
          <Stack.Screen name="RequisitionApproval" component={RequisitionApprovalScreen} options={{ title: "领料审批" }} />
          <Stack.Screen name="RequisitionDetail" component={RequisitionDetailScreen} options={{ title: "领料详情" }} />
          <Stack.Screen name="WastageList" component={WastageListScreen} options={{ title: "损耗记录" }} />
          <Stack.Screen name="WastageCreate" component={WastageCreateScreen} options={{ title: "记录损耗" }} />
          <Stack.Screen name="StocktakingList" component={StocktakingListScreen} options={{ title: "盘点管理" }} />
          <Stack.Screen name="StocktakingExecute" component={StocktakingExecuteScreen} options={{ title: "执行盘点" }} />
          <Stack.Screen name="StocktakingSummary" component={StocktakingSummaryScreen} options={{ title: "盘点汇总" }} />
          <Stack.Screen name="SupplierDeliveryList" component={SupplierDeliveryListScreen} options={{ title: "待验收入库" }} />
          <Stack.Screen name="SupplierDeliveryCreate" component={SupplierDeliveryCreateScreen} options={{ title: "新建送货单" }} />
          <Stack.Screen name="SupplierDeliveryDetail" component={SupplierDeliveryDetailScreen} options={{ title: "送货单详情" }} />
          <Stack.Screen name="PriceAnomalyApproval" component={PriceAnomalyApprovalScreen} options={{ title: "价格异常审批" }} />
        </>
      )}
    </Stack.Navigator>
  );
}

export default FAManagementStackNavigator;
