/**
 * 导航类型定义
 * 定义应用中所有导航器的参数类型
 */

import { NavigatorScreenParams } from '@react-navigation/native';
import { NativeStackScreenProps } from '@react-navigation/native-stack';
import { SmartBIStackParamList } from './smartbi';

// ==================== Root导航参数 ====================

export type RootStackParamList = {
  // 认证流程
  Login: undefined;
  EnhancedLogin: undefined;                                       // 增强登录页面
  LoginScreen: undefined;                                         // 登录屏幕（新的命名）
  RegisterScreen: undefined;                                      // 注册屏幕（新API）
  RegisterPhaseOne: undefined;                                    // 旧的注册流程（已弃用）
  RegisterPhaseTwo: { phoneNumber: string; tempToken?: string };  // 旧的注册流程（已弃用）
  ForgotPassword: undefined;                                      // Phase 3 P2: 忘记密码

  // 主应用
  Main: NavigatorScreenParams<MainTabParamList>;
};

// ==================== 主Tab导航参数 ====================

export type MainTabParamList = {
  HomeTab: undefined;
  ProcessingTab: NavigatorScreenParams<ProcessingStackParamList>;
  AttendanceTab: NavigatorScreenParams<AttendanceStackParamList>;  // ✅ 统一命名：TimeClockTab → AttendanceTab
  AdminTab: NavigatorScreenParams<AdminStackParamList>;
  ManagementTab: NavigatorScreenParams<ManagementStackParamList>;
  PlatformTab: NavigatorScreenParams<PlatformStackParamList>;  // 平台管理
  ProfileTab: NavigatorScreenParams<ProfileStackParamList>;  // 个人中心
  ReportTab: NavigatorScreenParams<ReportStackParamList>;  // 报表中心
  // 物流 & 溯源模块
  LogisticsTab: NavigatorScreenParams<LogisticsStackParamList>;
  TraceTab: NavigatorScreenParams<TraceStackParamList>;
  // OA 待办中心 (finance_manager / cashier 专用)
  OATodoTab: NavigatorScreenParams<OATodoStackParamList>;
};

// ==================== OA 待办模块导航参数 ====================
// 访问角色: finance_manager / cashier

export type OATodoStackParamList = {
  /** 待办列表（tab 入口） */
  MyTodoList: undefined;
  /** 大额单详情+审批（needDetail=true 跳转） */
  TodoDetail: {
    refId: string;
    type: string;
    title: string;
  };
};

// ==================== 生产模块导航参数 ====================

export type YieldStepReportParams = {
  batchId: number;
  batchNumber?: string;
  assignedWorkProcessTaskId?: number;
  assignedProcessOrder?: number;
  autoAssigned?: boolean;
};

export type ProcessingStackParamList = {
  ProcessingDashboard: undefined;

  // 批次管理
  BatchList: { status?: string; search?: string };
  BatchDetail: { batchId: string; readonly?: boolean };
  CreateBatch: undefined;
  EditBatch: { batchId: string };

  // 质检管理
  QualityInspectionList: { batchId?: string };
  CreateQualityRecord: { batchId: string; inspectionType: 'raw_material' | 'process' | 'final_product' };
  QualityInspectionDetail: { inspectionId: string };

  // 设备监控
  EquipmentMonitoring: undefined;
  EquipmentManagement: undefined;  // P3-设备: 设备管理（CRUD、搜索、状态）
  EquipmentDetail: { equipmentId: number };
  EquipmentAlerts: { equipmentId?: string };

  // 成本分析
  CostAnalysisDashboard: { batchId?: string };
  TimeRangeCostAnalysis: undefined;
  CostComparison: { batchIds: string[] };
  AIAnalysis: { batchId: string };

  // AI智能分析
  AIReportList: undefined;
  AIAnalysisDetail: { reportId: number; reportType: string; title: string };
  BatchComparison: undefined;
  AIConversationHistory: { sessionId?: string };

  // 数据导出 - 已移至ProfileStackParamList，避免类型冲突
  // DataExport: { reportType: 'batch' | 'cost' | 'quality' | 'equipment' };

  // 生产计划管理
  ProductionPlanManagement: {
    mode?: 'create';
    initialValues?: Record<string, any>;
  } | undefined;

  // 原材料管理
  MaterialReceipt: undefined;
  MaterialReceiptAI: undefined;
  MaterialBatchManagement: undefined;
  MaterialBatchSuccess: {
    batchNumber: string;
    materialName: string;
    quantity: number;
    supplierName: string;
  };

  // Phase 3 P2: 质检统计分析
  QualityAnalytics: undefined;

  // Phase 3 P2: 库存盘点
  InventoryCheck: undefined;

  // Phase 3 P2: 异常预警
  ExceptionAlert: undefined;
  CreateException: undefined;

  // 包装管理
  CreatePackaging: undefined;

  // 原材料消耗记录
  MaterialConsumptionHistory: { productionBatchId?: string };

  // 溯源功能 - Phase 3 完整溯源链路
  Traceability: undefined;                             // 溯源查询入口
  TraceabilityDetail: { batchNumber: string };         // 完整溯源链路
  PublicTrace: { batchNumber?: string; traceCode?: string };  // 公开溯源（消费者）

  // AI语音质检 - Phase 4
  VoiceInspection: undefined;                          // 语音质检主屏幕

  // 报工 - Work Reporting
  ScanReport: undefined;
  TeamBatchReport: undefined;
  DynamicReport: { reportType: 'PROGRESS' | 'HOURS' };
  NfcCheckin: undefined;
  YieldBatchSelect: undefined;
  YieldStepReport: YieldStepReportParams;

  // 标签扫描 - 通用组件
  LabelScan: {
    workstationId: string;
    batchNumber?: string; // 期望的批次号（用于匹配验证）
  };

  // 工位监控 - 生产模块
  WorkstationMonitor: {
    workstationId?: string;
    cameraId?: string;
    scaleDeviceId?: string;
    productionBatchId?: string;
  };

  // 工序制生产 - Process Mode
  ProcessTaskList: { status?: string };
  ProcessTaskDetail: { taskId: string };
  ProcessTaskHistory: undefined;
  ProcessRunOverview: { productionRunId: string };
};

// ==================== 考勤模块导航参数 ====================
// ✅ 统一命名：TimeClockStackParamList → AttendanceStackParamList

export type AttendanceStackParamList = {
  TimeClockScreen: undefined;
  ClockHistory: { employeeId?: string };
  TimeStatistics: { employeeId?: string; period?: 'day' | 'week' | 'month' };
  WorkRecords: { employeeId?: string };

  // Phase 3 P2: 工时查询
  AttendanceHistory: undefined;

  // ✅ 添加缺失的路由定义
  DepartmentAttendance: undefined;
};

// ==================== 平台管理模块导航参数 ====================

export type PlatformStackParamList = {
  PlatformDashboard: undefined;
  FactoryManagement: undefined;
  AIQuotaManagement: undefined;
  UserManagement: undefined;
  WhitelistManagement: undefined;
  SystemMonitoring?: undefined;      // 可选：系统监控
  PlatformReports?: undefined;       // 可选：平台报表
};

// ==================== 工厂管理模块导航参数 ====================

export type ManagementStackParamList = {
  ManagementHome: undefined;
  ProductTypeManagement: undefined;
  MaterialTypeManagement: undefined;
  ConversionRate: undefined;
  MaterialConversionDetail: { materialTypeId: string; materialName: string };  // 原料转换率详情页
  WorkTypeManagement: undefined;
  AISettings: undefined;
  IntentConfig: undefined;  // AI意图配置管理
  DepartmentManagement: undefined;
  UserManagement: undefined;
  WhitelistManagement: undefined;
  SupplierManagement: undefined;
  SupplierAdmission: undefined; // 供应商准入规则管理
  CustomerManagement: undefined;
  ShipmentManagement: undefined;  // 出货管理
  FactorySettings: undefined;
  MaterialSpecManagement: undefined; // Phase 4: 物料规格管理（已实现但暂未启用）

  // M-BOM-1 (Track D1, 2026-05-14): BOM 配方主子表
  BomConfigList: undefined;                       // BOM 配方列表
  BomConfigEdit: { recipeId?: string };           // BOM 配方编辑 (无 id = 新建)

  DisposalRecordManagement: undefined; // 报废记录管理
  WorkSessionManagement: undefined; // 工作会话管理

  // HR管理员模块 (permission_admin角色专用)
  HRDashboard: undefined;  // HR管理员仪表板
  HREmployeeAI: { employeeId?: number };  // 员工AI分析
  UserCreate: undefined;   // 添加员工
  AttendanceStats: undefined;  // 考勤统计

  // S4-2 SOP配置 (factory_super_admin/management角色)
  SopConfig: undefined;

  // Track D2 — 工序管理 (M-WP-1) + 产品工序配置 (M-WP-2)
  WorkProcessList: undefined;
  WorkProcessCreate: { mode?: 'create' | 'edit'; id?: string } | undefined;
  ProductWorkProcessConfig: { productTypeId?: string } | undefined;
};

// ==================== 个人中心模块导航参数 ====================

export type ProfileStackParamList = {
  ProfileHome: undefined;

  // Phase 3 P2: 意见反馈
  Feedback: undefined;

  // Phase 3 P2: 数据导出 (可选，也可以放在ProcessingStackParamList)
  DataExport: { reportType?: 'production' | 'cost' | 'attendance' };

  // 通知中心 - 所有角色可访问
  NotificationCenter: undefined;

  // 开发者工具: 服务器连接测试
  ServerConnectivityTest: undefined;

  // 开发者工具: 意图执行测试
  IntentExecutionTest: undefined;
};

// ==================== 报表中心模块导航参数 ====================

export type ReportStackParamList = {
  ReportDashboard: undefined;
  ProductionReport: undefined;
  QualityReport: undefined;
  CostReport: undefined;
  EfficiencyReport: undefined;
  TrendReport: undefined;
  PersonnelReport: undefined;
  KPIReport: undefined;
  ForecastReport: undefined;
  AnomalyReport: undefined;
  RealtimeReport: undefined;
  DataExport: { reportType?: 'production' | 'cost' | 'attendance' };
  // 新增报表 (2026-01-14)
  OeeReport: undefined;
  CostVarianceReport: undefined;
  CapacityUtilizationReport: undefined;
  OnTimeDeliveryReport: undefined;
  // 独立生产分析 & 人效分析 (2026-02-13)
  ProductionAnalysis: undefined;
  EfficiencyAnalysis: undefined;
};

// ==================== Admin模块导航参数 ====================
// ⚠️ 注意：AdminStackParamList已定义但暂未使用
// 相关功能已集成到PlatformStackNavigator和ManagementStackNavigator中
// 保留此定义供未来扩展使用，或在确认不需要后可删除

export type AdminStackParamList = {
  UserManagement: undefined;
  UserDetail: { userId: string };
  CreateUser: undefined;
  EditUser: { userId: string };
  UserRoleHistory: { userId: string };

  WhitelistManagement: undefined;
  AddToWhitelist: undefined;
  WhitelistDetail: { whitelistId: string };

  PermissionManagement: undefined;
  RolePermissions: { role: string };
  PermissionSettings: undefined;
};

// ==================== Factory Admin 模块导航参数 ====================
// 仅 factory_super_admin 角色使用的新界面

export type FactoryAdminTabParamList = {
  FAHomeTab: NavigatorScreenParams<FAHomeStackParamList>;
  FAAITab: NavigatorScreenParams<FAAIStackParamList>;
  FAReportsTab: NavigatorScreenParams<ReportStackParamList>;
  FASmartBITab: NavigatorScreenParams<SmartBIStackParamList>;
  FAManagementTab: NavigatorScreenParams<FAManagementStackParamList>;
  FAProfileTab: NavigatorScreenParams<FAProfileStackParamList>;
};

export type FAHomeStackParamList = {
  FAHome: undefined;
  TodayProduction: undefined;
  TodayBatches: undefined;
  MaterialBatch: { statusFilter?: string } | undefined;
  AIAlerts: undefined;
  SalesOrderList: { statusFilter?: string } | undefined;
  PurchaseOrderList: { statusFilter?: string } | undefined;
  ProductionPlanManagement: { statusFilter?: string } | undefined;
  FinanceAnalysis: { statusFilter?: string } | undefined;
  AIChat: {
    entryContext?: {
      module: 'sales' | 'purchase' | 'production' | 'finance' | 'inventory' | string;
      node?: string;
      factoryId?: string;
    };
  } | undefined;
  // 详情页
  BatchDetail: { batchId: string; readonly?: boolean };
  MaterialBatchDetail: { batchId: string };
  // 批次详情关联页面 - 从BatchDetail跳转
  EditBatch: { batchId: string };
  CreateQualityRecord: { batchId: string; inspectionType?: 'raw_material' | 'process' | 'final_product' };
  CostAnalysisDashboard: { batchId?: string };
  // Formily 演示页面
  FormilyDemo: undefined;
  // 首页布局编辑器
  HomeLayoutEditor: undefined;
};

export type FAAIStackParamList = {
  AIAnalysisCenter: undefined;
  AICostAnalysis: undefined;
  AIReport: undefined;
  AIChat: {
    entityType?: string;
    initialMessage?: string;
    /** U-NAV-1 (Sprint 2 Track G): workflow node 长按 / AI 入口触发携带 */
    entryContext?: {
      module: 'sales' | 'purchase' | 'production' | 'finance' | 'inventory' | string;
      node?: string;
      factoryId?: string;
    };
  } | undefined;  // 支持从其他页面传入上下文
  QualityAnalysis: undefined;
  CreatePlan: undefined;
  // 详情页
  AIReportDetail: { reportId: number; reportType: string; title: string };
  // 意图审批
  IntentSuggestionsList: undefined;
  IntentSuggestionDetail: { suggestionId: string };
};

export type FAManagementStackParamList = {
  FAManagement: undefined;
  EmployeeList: undefined;
  EquipmentList: undefined;
  // 设备智能分析
  EquipmentAnalysis: undefined;
  // 复用现有详情页
  EmployeeDetail: { employeeId: number };
  EquipmentDetail: { equipmentId: number };
  // 其他管理页面 (可复用现有)
  ProductTypeManagement: undefined;
  MaterialTypeManagement: undefined;
  MaterialConversionDetail: { materialTypeId?: string; materialName?: string } | undefined;
  DepartmentManagement: undefined;
  SupplierManagement: undefined;
  CustomerManagement: undefined;
  ShipmentManagement: undefined;
  ConversionRate: undefined;
  DisposalRecordManagement: undefined;
  // Schema 配置 (AI 创建字段)
  SchemaConfig: undefined;
  // 低代码页面编辑器 (Track A C-CANVAS-PAGE)
  PageEditor: {
    pageId?: string;
    pageType?: 'home' | 'dashboard' | 'list' | 'detail' | 'form';
  } | undefined;
  // 规则配置 (Drools 规则 + 状态机)
  RuleConfiguration: undefined;
  // AI 智能初始化业务数据 (P1.5)
  AIBusinessInit: undefined;
  // 编码规则配置
  EncodingRuleConfig: undefined;
  // 编码规则详情/编辑
  CodeRuleDetail: { ruleId?: string; isNew?: boolean };
  // 质检项配置
  QualityCheckItemConfig: undefined;
  // 质检项详情
  QualityCheckItemDetail: { itemId: string };
  // SOP 流程配置
  SopConfig: undefined;
  // 意图配置查看 (只读)
  IntentView: undefined;
  // 意图配置详情 (编辑)
  IntentConfigDetail: { intentCode: string };
  // 统一设备管理中心
  UnifiedDeviceManagement: undefined;
  // 表单模版管理
  FormTemplateList: undefined;
  FormTemplateDetail: { entityType: string };
  // IoT 电子秤设备管理
  IotDeviceList: undefined;
  IotDeviceDetail: { deviceId: number };
  IotDeviceCreate: undefined;
  ScaleTest: { equipmentId?: number; protocolId?: string };
  // ISAPI 海康威视摄像头设备管理
  IsapiDeviceList: undefined;
  IsapiDeviceDetail: { deviceId: string };
  IsapiDeviceCreate: undefined;
  IsapiDeviceDiscovery: undefined;
  IsapiSmartConfig: { deviceId: string; channelId?: number };
  DeviceSetupWizard: undefined;
  // 智能设备添加
  SmartDeviceAdd: undefined;
  CameraAddMethod: undefined;
  AIDeviceInput: { deviceType: 'CAMERA' | 'SCALE' };
  // 标签自动识别监控
  LabelRecognitionMonitor: undefined;
  // 统一多品牌设备发现
  UnifiedDeviceDiscovery: undefined;
  // NFC 标签管理 (factory_admin)
  NfcTagManagement: undefined;
  // 进销存管理
  PurchaseOrderList: undefined;
  PurchaseOrderCreate: undefined;
  PurchaseOrderDetail: { orderId: string };
  // Sprint2-J P-FIN-1: 财务审核 (PENDING_FINANCE_REVIEW 列表 + 详情审核页)
  PurchaseOrderFinanceReviewList: undefined;
  PurchaseOrderFinanceReview: { orderId: string };
  ProcessTaskApproval: undefined;
  SalesOrderList: undefined;
  SalesOrderDetail: { orderId: string };
  /** Sprint 2 Track E (S-MRP-1 / N31) — 销售单财务审核通过后的缺料分析 chain-card. Day 4 接入 navigator. */
  SalesOrderShortageReview: { orderId: string };
  FinishedGoodsList: undefined;
  TransferList: undefined;
  TransferDetail: { transferId: string };
  ArApOverview: undefined;
  PriceList: undefined;
  PaymentRecord: undefined;
  /** SP6 Tier1 #36 — 出纳付款列表（已审批待付款） */
  CashierPaymentList: undefined;
  /** SP6 Tier1 #36 — 采购异常决策列表 */
  PurchaseExceptionList: undefined;
  RdRequestCreate: undefined;
  ReturnOrderList: undefined;
  ReturnOrderDetail: { returnId: string };
  // 报工审批
  WorkReportApproval: undefined;
  // 工序制生产页面
  ProcessTaskList: { status?: string };
  ProcessTaskDetail: { taskId: string };
  ProcessTaskHistory: undefined;
  ProcessRunOverview: { productionRunId: string };
  YieldBatchSelect: undefined;
  YieldStepReport: YieldStepReportParams;
  // 餐饮模块 (restaurant mode)
  RecipeList: undefined;
  RecipeDetail: { productTypeId: string; dishName: string };
  RecipeEdit: { recipeId?: string } | undefined;
  RequisitionList: undefined;
  RequisitionCreate: undefined;
  ChefRequisitionList: undefined;
  ChefRequisitionCreate: undefined;
  RequisitionApproval: undefined;
  RequisitionDetail: { requisitionId: string };
  WastageList: undefined;
  WastageCreate: undefined;
  StocktakingList: undefined;
  StocktakingExecute: { recordId?: string } | undefined;
  StocktakingSummary: undefined;
  SupplierDeliveryList: undefined;
  SupplierDeliveryCreate: undefined;
  SupplierDeliveryDetail: { noteId: string };
  PriceAnomalyApproval: undefined;

  // Track D2 — 工序管理 (M-WP-1) + 产品工序配置 (M-WP-2)
  WorkProcessList: undefined;
  WorkProcessCreate: { mode?: 'create' | 'edit'; id?: string } | undefined;
  ProductWorkProcessConfig: { productTypeId?: string } | undefined;
};

export type FAProfileStackParamList = {
  FAProfile: undefined;
  PersonalInfo: undefined;
  ChangePassword: undefined;
  NotificationSettings: undefined;
  SystemSettings: undefined;
  HelpCenter: undefined;
  About: undefined;
  Feedback: undefined;
  Membership: undefined;
  DataExport: { reportType?: 'production' | 'cost' | 'attendance' };
};

// Restaurant module type aliases (screens live inside FAManagementStack)
export type RRecipeStackParamList = FAManagementStackParamList;
export type RWastageStackParamList = FAManagementStackParamList;
export type RStocktakingStackParamList = FAManagementStackParamList;
export type RRequisitionStackParamList = FAManagementStackParamList;

// Factory Admin 屏幕Props
export type FAHomeScreenProps<T extends keyof FAHomeStackParamList> =
  NativeStackScreenProps<FAHomeStackParamList, T>;

export type FAAIScreenProps<T extends keyof FAAIStackParamList> =
  NativeStackScreenProps<FAAIStackParamList, T>;

export type FAManagementScreenProps<T extends keyof FAManagementStackParamList> =
  NativeStackScreenProps<FAManagementStackParamList, T>;

export type FAProfileScreenProps<T extends keyof FAProfileStackParamList> =
  NativeStackScreenProps<FAProfileStackParamList, T>;

// ==================== Workshop Supervisor 模块导航参数 ====================
// 车间主任(department_admin)角色专用界面
// 5个Tab: 首页 | 批次 | 人员 | 设备 | 我的

export type WorkshopSupervisorTabParamList = {
  WSHomeTab: NavigatorScreenParams<WSHomeStackParamList>;
  WSBatchesTab: NavigatorScreenParams<WSBatchesStackParamList>;
  WSWorkersTab: NavigatorScreenParams<WSWorkersStackParamList>;
  WSEquipmentTab: NavigatorScreenParams<WSEquipmentStackParamList>;
  WSProfileTab: NavigatorScreenParams<WSProfileStackParamList>;
};

export type WSHomeStackParamList = {
  WSHome: undefined;
  // 从首页进入的详情页
  BatchDetail: { batchId: string; readonly?: boolean };
  WorkerDetail: { workerId: number };
  EquipmentDetail: { equipmentId: number };
  Notifications: undefined;
  // 任务引导流程
  TaskGuide: { batchId: string; batchNumber: string };
  TaskGuideStep2: { batchId: string; batchNumber: string };
  TaskGuideStep3: { batchId: string; batchNumber: string };
  // 快捷操作入口
  TeamBatchReport: undefined;
  DraftReports: undefined;
  // 生产报工
  DynamicReport: { reportType: 'PROGRESS' | 'HOURS' };
  NfcCheckin: undefined;
  ProcessTaskList: undefined;
  ProcessTaskDetail: { taskId: string };
  ProcessRunOverview: { productionRunId: string };
  YieldBatchSelect: undefined;
  YieldStepReport: YieldStepReportParams;
  ProcessOperation: undefined;
  MyWorkReports: undefined;
  EquipmentMonitoring: undefined;
  ProductionPlanManagement: { statusFilter?: string } | undefined;
  AIChat: {
    entryContext?: {
      module: 'sales' | 'purchase' | 'production' | 'finance' | 'inventory' | string;
      node?: string;
      factoryId?: string;
    };
  } | undefined;
};

export type WSBatchesStackParamList = {
  WSBatches: undefined;
  BatchDetail: { batchId: string; readonly?: boolean };
  BatchStart: { planId?: string };
  BatchStage: { batchId: string; stageType: string; stageName: string };
  BatchComplete: { batchId: string };
  MaterialConsumption: { batchId: string };
  TransferList: undefined;
  TransferDetail: { transferId: string };
  QualityCreate: { batchId: string };
  QualityDetail: { inspectionId: string };
  // 报工 - Work Reporting
  ScanReport: undefined;
  TeamBatchReport: undefined;
  // 报工成功页
  ScanReportSuccess: {
    batchNumber: string;
    outputQuantity: number;
    goodQuantity: number;
    defectQuantity: number;
  };
  // 草稿管理
  DraftReports: undefined;
  // 标签扫描 (from WSBatches context)
  LabelScan: {
    workstationId: string;
    batchNumber?: string;
  };
  // AI效率分析 (from BatchDetail context)
  AIAnalysis: { batchId: string };
  // 工序制生产页面
  ProcessTaskList: { status?: string };
  ProcessTaskDetail: { taskId: string };
  ProcessTaskHistory: undefined;
  ProcessRunOverview: { productionRunId: string };
  YieldBatchSelect: undefined;
  YieldStepReport: YieldStepReportParams;
};

export type WSWorkersStackParamList = {
  WSWorkers: undefined;
  WorkerDetail: { workerId: number };
  WorkerAssign: { batchId?: string; batchNumber?: string };
  ClockIn: undefined;
  AttendanceHistory: { workerId?: number };
  EmployeeProcessSegment: undefined;
};

export type WSEquipmentStackParamList = {
  WSEquipment: undefined;
  EquipmentDetail: { equipmentId: number };
  EquipmentAlert: { alertId: string };
  EquipmentMaintenance: { equipmentId: number };
};

export type WSProfileStackParamList = {
  WSProfile: undefined;
  PersonalInfo: undefined;
  ChangePassword: undefined;
  NotificationSettings: undefined;
  Settings: undefined;
  About: undefined;
  Feedback: undefined;
  Membership: undefined;
};

// Workshop Supervisor 屏幕Props
export type WSHomeScreenProps<T extends keyof WSHomeStackParamList> =
  NativeStackScreenProps<WSHomeStackParamList, T>;

export type WSBatchesScreenProps<T extends keyof WSBatchesStackParamList> =
  NativeStackScreenProps<WSBatchesStackParamList, T>;

export type WSWorkersScreenProps<T extends keyof WSWorkersStackParamList> =
  NativeStackScreenProps<WSWorkersStackParamList, T>;

export type WSEquipmentScreenProps<T extends keyof WSEquipmentStackParamList> =
  NativeStackScreenProps<WSEquipmentStackParamList, T>;

export type WSProfileScreenProps<T extends keyof WSProfileStackParamList> =
  NativeStackScreenProps<WSProfileStackParamList, T>;

// ==================== Warehouse Manager 模块导航参数 ====================
// 仓储管理员 (warehouse_manager) 和仓储员工 (warehouse_worker) 角色专用界面
// 5个Tab: 首页 | 入库 | 出货 | 库存 | 我的

export type WarehouseManagerTabParamList = {
  WHHomeTab: NavigatorScreenParams<WHHomeStackParamList>;
  WHInboundTab: NavigatorScreenParams<WHInboundStackParamList>;
  WHOutboundTab: NavigatorScreenParams<WHOutboundStackParamList>;
  WHInventoryTab: NavigatorScreenParams<WHInventoryStackParamList>;
  WHProfileTab: NavigatorScreenParams<WHProfileStackParamList>;
};

export type WHHomeStackParamList = {
  WHHome: undefined;
  // 从首页进入的详情页
  OutboundDetail: { orderId: string };
  InboundDetail: { batchId: string };
  AlertHandle: { alertId: string };
  TempMonitor: undefined;
  // 调拨（领料审批）
  TransferList: undefined;
  TransferDetail: { transferId: string };
  WHInventoryList: { statusFilter?: string } | undefined;
  AIChat: {
    entryContext?: {
      module: 'sales' | 'purchase' | 'production' | 'finance' | 'inventory' | string;
      node?: string;
      factoryId?: string;
    };
  } | undefined;
};

export type WHInboundStackParamList = {
  WHInboundList: undefined;
  WHInboundDetail: { batchId: string };
  WHInboundCreate: undefined;
  SupplierDeliveryList: undefined;
  SupplierDeliveryCreate: undefined;
  SupplierDeliveryDetail: { noteId: string };
  PriceAnomalyApproval: undefined;
  WHInspect: { batchId: string };
  WHPutaway: { batchId: string };
  WHScanOperation: { type: 'inbound' | 'outbound'; orderId?: string; mode?: string };
  // W-ABA-1 Day 5: PDF 扫码 → 2-字段收货页 (扫 QR 跳本路由, purchaseOrderId 必传)
  WHReceiptCreate: { purchaseOrderId: string; orderNumber?: string };
  // SP6 Tier1 #25: 无扫码直接选单收货列表页
  WHPurchaseReceiveList: undefined;
};

export type WHOutboundStackParamList = {
  WHOutboundList: undefined;
  WHOutboundDetail: { shipmentId: string };
  WHPacking: { orderId: string };
  WHLoading: { vehicleId?: string };
  WHShippingConfirm: { orderId: string };
  WHTrackingDetail: { shipmentId: string };
  WHOrderDetail: { orderId: string };
  WHScanOperation: { type: 'inbound' | 'outbound'; orderId?: string; mode?: string };
};

export type WHInventoryStackParamList = {
  WHInventoryList: undefined;
  WHInventoryDetail: { materialId?: string; inventoryId?: string };
  WHBatchDetail: { batchId?: string; batchNumber?: string };
  WHInventoryCheck: undefined;
  WHInventoryTransfer: { batchId?: string };
  WHLocationManage: undefined;
  WHExpireHandle: { batchId?: string; batchNumber?: string };
  WHTempMonitor: undefined;
  WHIOStatistics: undefined;
  WHBatchTrace: { batchNumber?: string; batchId?: string };
  // 库存预警管理
  WHInventoryAlert: undefined;  // 库存预警列表
  WHAlertDetail: { alertId: string; alertType: 'low_stock' | 'expiring' | 'expired' | 'quality'; batchNumber: string };  // 预警详情处理
  WHInventoryWarnings: undefined;  // 库存预警/过期综合管理（3 Tab）
  WHTransitLedger: undefined;
  // SP7: 盘点 / 报损 / 调拨接收 (R-C)
  StocktakeEntry: { stocktakeId: string };
  WastageReport: { batchId?: string };
  TransferReceive: { transferId: string };
};

export type WHProfileStackParamList = {
  WHProfile: undefined;
  WHProfileEdit: undefined;
  WHSettings: undefined;
  WHOperationLog: undefined;
  WHIOStatistics: undefined;
  WHInventoryCheck: undefined;
  WHExpireHandle: { batchId?: string; batchNumber?: string };
  WastageReport: { batchId?: string };
  WHAlertList: undefined;
  WHAlertHandle: { alertId: string };
  WHRecallManage: undefined;
  WHConversionAnalysis: undefined;
  Feedback: undefined;
  Membership: undefined;
};

// Warehouse Manager 屏幕Props
export type WHHomeScreenProps<T extends keyof WHHomeStackParamList> =
  NativeStackScreenProps<WHHomeStackParamList, T>;

export type WHInboundScreenProps<T extends keyof WHInboundStackParamList> =
  NativeStackScreenProps<WHInboundStackParamList, T>;

export type WHOutboundScreenProps<T extends keyof WHOutboundStackParamList> =
  NativeStackScreenProps<WHOutboundStackParamList, T>;

export type WHInventoryScreenProps<T extends keyof WHInventoryStackParamList> =
  NativeStackScreenProps<WHInventoryStackParamList, T>;

export type WHProfileScreenProps<T extends keyof WHProfileStackParamList> =
  NativeStackScreenProps<WHProfileStackParamList, T>;

// ==================== 未来模块导航参数(预留) ====================

export type LogisticsStackParamList = {
  LogisticsDashboard: undefined;
  ShipmentList: { status?: string };
  ShipmentDetail: { shipmentId: string };
  CreateShipment: undefined;
  TrackingDetail: { trackingNumber: string };
};

export type TraceStackParamList = {
  TraceDashboard: undefined;
  TraceQuery: undefined;
  TraceDetail: { batchNumber: string };
  TraceScan: undefined;
  PublicTrace: { batchNumber?: string; traceCode?: string };
};

export type WorkStackParamList = {
  WorkTypeList: undefined;
  WorkTypeForm: {
    workTypeCode: string;
    workTypeName: string;
  };
};

// ==================== 屏幕Props类型 ====================

// Root导航屏幕Props
export type RootStackScreenProps<T extends keyof RootStackParamList> =
  NativeStackScreenProps<RootStackParamList, T>;

// 主Tab屏幕Props
export type MainTabScreenProps<T extends keyof MainTabParamList> =
  NativeStackScreenProps<MainTabParamList, T>;

// 生产模块屏幕Props
export type ProcessingScreenProps<T extends keyof ProcessingStackParamList> =
  NativeStackScreenProps<ProcessingStackParamList, T>;

// 考勤模块屏幕Props
// ✅ 统一命名：TimeClockScreenProps → AttendanceScreenProps
export type AttendanceScreenProps<T extends keyof AttendanceStackParamList> =
  NativeStackScreenProps<AttendanceStackParamList, T>;

// 管理模块屏幕Props
export type AdminScreenProps<T extends keyof AdminStackParamList> =
  NativeStackScreenProps<AdminStackParamList, T>;

// 个人中心模块屏幕Props
export type ProfileScreenProps<T extends keyof ProfileStackParamList> =
  NativeStackScreenProps<ProfileStackParamList, T>;

// 工厂管理模块屏幕Props
export type ManagementScreenProps<T extends keyof ManagementStackParamList> =
  NativeStackScreenProps<ManagementStackParamList, T>;

// 报表中心模块屏幕Props
export type ReportScreenProps<T extends keyof ReportStackParamList> =
  NativeStackScreenProps<ReportStackParamList, T>;

// ==================== 导航配置 ====================

/**
 * 路由配置接口
 */
export interface NavigationRoute {
  screen: string;
  params?: Record<string, any>;
}

/**
 * 模块状态
 */
export type ModuleStatus = 'available' | 'coming_soon' | 'locked';

/**
 * 模块配置
 */
export interface ModuleConfig {
  id: string;
  name: string;
  icon: string;
  description: string;
  status: ModuleStatus;
  progress?: number; // 开发进度百分比
  requiredPermissions: string[];
  route?: string;
  color: string;
}

/**
 * 导航守卫配置
 */
export interface NavigationGuardConfig {
  requireAuth: boolean;
  requiredPermissions?: string[];
  requiredRole?: string[];
  fallbackRoute?: string;
}

declare global {
  namespace ReactNavigation {
    interface RootParamList extends RootStackParamList { }
  }
}
