import React, { useState, useEffect } from 'react';
import { View, StyleSheet, ScrollView, Alert, TouchableOpacity } from 'react-native';
import {
  Text,
  Appbar,
  FAB,
  Card,
  Chip,
  IconButton,
  Portal,
  Modal,
  TextInput,
  Button,
  ActivityIndicator,
  Divider,
  List,
  SegmentedButtons,
} from 'react-native-paper';
import { useNavigation, useRoute, CommonActions, RouteProp } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { Picker } from '@react-native-picker/picker';
import { useTranslation } from 'react-i18next';
import * as DocumentPicker from 'expo-document-picker';
import { productionPlanApiClient, ProductionPlan as ApiProductionPlan, StockWithConversion, ProductionPlanMaterialAdvisory } from '../../services/api/productionPlanApiClient';
import { productTypeApiClient } from '../../services/api/productTypeApiClient';
import { customerApiClient } from '../../services/api/customerApiClient';
import { conversionApiClient } from '../../services/api/conversionApiClient';
import { useAuthStore } from '../../store/authStore';
import { ProcessingStackParamList } from '../../types/navigation';
import { ProductTypeSelector } from '../../components/common/ProductTypeSelector';
import { CustomerSelector } from '../../components/common/CustomerSelector';
import { MaterialBatchSelector, SelectedBatch, AvailableBatch } from '../../components/common/MaterialBatchSelector';
import { MaterialBatch } from '../../services/api/materialBatchApiClient';
import { handleError, getErrorMsg } from '../../utils/errorHandler';
import { logger } from '../../utils/logger';

// 创建ProductionPlanManagement专用logger
const productionPlanLogger = logger.createContextLogger('ProductionPlanManagement');

// Extended ProductionPlan with populated fields from backend
interface ExtendedProductionPlan extends ApiProductionPlan {
  productType?: {
    id: string;
    name: string;
    productCode: string;
  };
  customer?: {
    id: string;
    name: string;
  };
  estimatedMaterialUsage?: number;
  // 未来计划匹配相关字段
  allocatedQuantity?: number;
  isFullyMatched?: boolean;
  matchingProgress?: number;
}

type ProductionPlan = ExtendedProductionPlan;
type NavigationProp = NativeStackNavigationProp<ProcessingStackParamList>;
type ProductionPlanManagementRouteProp = RouteProp<ProcessingStackParamList, 'ProductionPlanManagement'>;

/**
 * 生产计划管理页面
 */
export default function ProductionPlanManagementScreen() {
  const { t } = useTranslation('processing');
  const navigation = useNavigation<NavigationProp>();
  const route = useRoute<ProductionPlanManagementRouteProp>();
  const { user } = useAuthStore();
  const [plans, setPlans] = useState<ProductionPlan[]>([]);
  const [loading, setLoading] = useState(true);
  const [modalVisible, setModalVisible] = useState(false);
  const [filterStatus, setFilterStatus] = useState('UNFINISHED');

  // 权限控制
  const userType = user?.userType || 'factory';
  // 修复：优先使用 factoryUser.role，然后是 roleCode
  const roleCode = user?.factoryUser?.role || user?.roleCode || 'viewer';

  // 平台管理员只读权限
  const isReadOnly = userType === 'platform';

  // 可以创建生产计划的角色
  const canCreatePlan = ['factory_super_admin', 'department_admin'].includes(roleCode) && !isReadOnly;

  // 调试日志
  productionPlanLogger.debug('权限检查', {
    userType,
    roleCode,
    isReadOnly,
    canCreatePlan,
    factoryUserRole: user?.factoryUser?.role,
  });

  // 下拉选项
  const [productTypes, setProductTypes] = useState<any[]>([]);
  const [customers, setCustomers] = useState<any[]>([]);
  const [availableStock, setAvailableStock] = useState<any[]>([]);

  // 计算明天的日期作为默认预计完成日期
  const getDefaultCompletionDate = () => {
    const tomorrow = new Date();
    tomorrow.setDate(tomorrow.getDate() + 1);
    return tomorrow.toISOString().split('T')[0]; // 格式: YYYY-MM-DD
  };

  const [formData, setFormData] = useState({
    planType: 'FROM_INVENTORY' as 'FUTURE' | 'FROM_INVENTORY',
    productTypeId: '',
    productTypeName: '',
    productTypeCode: '',
    customerId: '',
    customerName: '',
    plannedQuantity: '',
    plannedDate: new Date().toISOString().split('T')[0], // 今天
    expectedCompletionDate: getDefaultCompletionDate(),   // 明天 (默认+1天)
    notes: '',
    // AI建议的新字段
    suggestedProductionLineId: '',
    estimatedWorkers: '',
    assignedSupervisorId: '',
  });

  // 导入loading状态
  const [importLoading, setImportLoading] = useState(false);

  // 批次选择相关状态
  const [materialTypeId, setMaterialTypeId] = useState('');
  const [materialTypeName, setMaterialTypeName] = useState('');
  const [availableBatches, setAvailableBatches] = useState<AvailableBatch[]>([]);
  const [selectedBatches, setSelectedBatches] = useState<SelectedBatch[]>([]);
  const [estimatedUsage, setEstimatedUsage] = useState<number | null>(null);
  const [conversionRate, setConversionRate] = useState<number | null>(null);
  const [wastageRate, setWastageRate] = useState<number | null>(null);

  // 库存加载状态
  const [stockLoading, setStockLoading] = useState(false);
  const [stockError, setStockError] = useState<string | null>(null);

  // 完成生产对话框状态
  const [showCompleteDialog, setShowCompleteDialog] = useState(false);
  const [completingPlan, setCompletingPlan] = useState<ProductionPlan | null>(null);
  const [actualQuantity, setActualQuantity] = useState('');
  const [completeLoading, setCompleteLoading] = useState(false);
  const [materialAdvisories, setMaterialAdvisories] = useState<Record<string, ProductionPlanMaterialAdvisory>>({});

  useEffect(() => {
    loadPlans();
    loadOptions();
  }, []);

  useEffect(() => {
    // 当筛选状态变化时重新加载数据
    loadPlans();
  }, [filterStatus]);

  // 当产品类型变化时，加载对应的原料库存
  useEffect(() => {
    if (formData.productTypeId) {
      loadMaterialStock(formData.productTypeId);
    } else {
      // 清空批次相关数据
      setMaterialTypeId('');
      setMaterialTypeName('');
      setAvailableBatches([]);
      setSelectedBatches([]);
      setAvailableStock([]);
    }
  }, [formData.productTypeId]);

  // 当计划产量变化时，计算预估消耗
  useEffect(() => {
    if (formData.productTypeId && formData.plannedQuantity) {
      calculateRealEstimate();
    } else {
      setEstimatedUsage(null);
    }
  }, [formData.productTypeId, formData.plannedQuantity]);

  // 处理从AI对话或其他页面传入的初始值
  useEffect(() => {
    const params = route.params;
    if (params?.mode === 'create' && params?.initialValues) {
      const iv = params.initialValues;
      setFormData(prev => ({
        ...prev,
        productTypeId: iv.productTypeId || prev.productTypeId,
        productTypeName: iv.productTypeName || prev.productTypeName,
        customerId: iv.customerId || prev.customerId,
        customerName: iv.customerName || prev.customerName,
        plannedQuantity: iv.plannedQuantity ? String(iv.plannedQuantity) : prev.plannedQuantity,
        plannedDate: iv.plannedDate || prev.plannedDate,
        expectedCompletionDate: iv.expectedCompletionDate || prev.expectedCompletionDate,
        notes: iv.notes || prev.notes,
        suggestedProductionLineId: iv.suggestedProductionLineId || prev.suggestedProductionLineId,
        estimatedWorkers: iv.estimatedWorkers ? String(iv.estimatedWorkers) : prev.estimatedWorkers,
        assignedSupervisorId: iv.assignedSupervisorId ? String(iv.assignedSupervisorId) : prev.assignedSupervisorId,
      }));
      setModalVisible(true);
      productionPlanLogger.info('从外部页面预填创建表单', { initialValues: iv });
    }
  }, [route.params]);

  const loadPlans = async () => {
    try {
      setLoading(true);
      const response = await productionPlanApiClient.getProductionPlans({
        page: 1,
        size: 100,
        ...(filterStatus !== 'all' && { status: filterStatus }),
      });

      if (response.success && response.data) {
        // 后端返回分页格式: { content: [...], totalElements, ... }
        const pageData = response.data as { content?: ProductionPlan[] };
        const plansData = pageData.content || [];
        const nextPlans = Array.isArray(plansData) ? plansData : [];
        setPlans(nextPlans);
        await loadMaterialAdvisories(nextPlans);
      } else {
        setPlans([]);
        setMaterialAdvisories({});
      }
    } catch (error) {
      productionPlanLogger.error('加载生产计划失败', error, { filterStatus });
      Alert.alert(t('common.error', { defaultValue: '错误' }), getErrorMsg(error) || t('productionPlan.messages.loadFailed'));
      setPlans([]);
      setMaterialAdvisories({});
    } finally {
      setLoading(false);
    }
  };

  const loadMaterialAdvisories = async (rows: ProductionPlan[]) => {
    const unfinishedRows = rows.filter((plan) =>
      ['pending', 'in_progress'].includes(plan.status?.toLowerCase() || '')
    );
    if (unfinishedRows.length === 0) {
      setMaterialAdvisories({});
      return;
    }

    const results = await Promise.allSettled(
      unfinishedRows.map(async (plan) => {
        const response = await productionPlanApiClient.getMaterialAdvisory(plan.id);
        if (!response.success || !response.data) {
          throw new Error(response.message || '加载原料预警失败');
        }
        return [plan.id, response.data] as const;
      })
    );
    const next: Record<string, ProductionPlanMaterialAdvisory> = {};
    let failed = false;
    results.forEach((result) => {
      if (result.status === 'fulfilled') {
        next[result.value[0]] = result.value[1];
      } else {
        failed = true;
      }
    });
    setMaterialAdvisories(next);
    if (failed) {
      Alert.alert('原料预警加载失败', '部分生产计划的原料参考值加载失败，请刷新后重试');
    }
  };

  const loadOptions = async () => {
    try {
      const [productsRes, customersRes, stockRes] = await Promise.all([
        productTypeApiClient.getProductTypes({ isActive: true, limit: 100 }),
        customerApiClient.getCustomers({ isActive: true }),
        productionPlanApiClient.getAvailableStock(),
      ]);

      // ProductTypes response is { data: ProductType[] }
      if (productsRes.data) {
        const productTypesData = Array.isArray(productsRes.data) ? productsRes.data : [];
        setProductTypes(productTypesData);
      }

      // Customers response is { data: Customer[] }
      if (customersRes.data) {
        const customersData = Array.isArray(customersRes.data) ? customersRes.data : [];
        setCustomers(customersData);
      }

      // StockSummary response
      if (stockRes.success && stockRes.data) {
        const stockData = stockRes.data as { summary?: Array<{ materialTypeName?: string; category?: string; totalQuantity?: number; totalAvailable?: number; batchCount?: number }> };
        if (stockData.summary && Array.isArray(stockData.summary)) {
          const summaryData = stockData.summary.map((item) => ({
            category: item.materialTypeName || item.category,
            available: item.totalQuantity || item.totalAvailable || 0,
            batchCount: item.batchCount || 0,
          }));
          setAvailableStock(summaryData);
        }
      }
    } catch (error) {
      productionPlanLogger.error('加载选项失败', error);
    }
  };

  // 加载原料库存（当产品类型变化时）
  const loadMaterialStock = async (productTypeId: string) => {
    setStockLoading(true);
    setStockError(null);
    try {
      productionPlanLogger.debug('加载产品对应的原料库存', { productTypeId });

      const stockRes = await productionPlanApiClient.getAvailableStock({ productTypeId });

      if (stockRes.success && stockRes.data) {
        const stockData = stockRes.data as StockWithConversion;
        const { materialType, batches, totalAvailable, conversionRate, wastageRate } = stockData;

        if (materialType) {
          setMaterialTypeId(materialType.id);
          setMaterialTypeName(materialType.name);
          setConversionRate(conversionRate);
          setWastageRate(wastageRate);

          // 转换为AvailableBatch格式
          const formattedBatches = (batches as unknown as Array<Record<string, unknown>>).map((b) => ({
            id: String(b.id ?? ''),
            batchNumber: String(b.batchNumber ?? ''),
            materialType: b.materialType as AvailableBatch['materialType'],
            supplier: b.supplier as AvailableBatch['supplier'],
            remainingQuantity: Number(b.remainingQuantity ?? 0),
            reservedQuantity: Number(b.reservedQuantity ?? 0),
            unitPrice: Number(b.unitPrice ?? 0),
            inboundDate: String(b.inboundDate ?? ''),
            expiryDate: b.expiryDate ? String(b.expiryDate) : undefined,
            qualityGrade: b.qualityGrade ? String(b.qualityGrade) : undefined,
          })) as AvailableBatch[];

          setAvailableBatches(formattedBatches);

          // 更新库存显示
          setAvailableStock([{
            category: materialType.name,
            available: totalAvailable,
            batchCount: batches.length,
          }]);

          productionPlanLogger.info('原料库存加载成功', {
            materialTypeName: materialType.name,
            batchCount: batches.length,
            totalAvailable,
          });
        } else {
          // 未配置转换率
          setMaterialTypeId('');
          setMaterialTypeName('');
          setAvailableBatches([]);
          setAvailableStock([]);
          // 不再弹Alert，改为在UI中显示状态
          productionPlanLogger.warn('该产品未配置转换率', { productTypeId });
        }
      }
    } catch (error) {
      productionPlanLogger.error('加载原料库存失败', error, { productTypeId });
      setStockError(getErrorMsg(error) || '加载原料库存失败');
      setMaterialTypeId('');
      setMaterialTypeName('');
      setAvailableBatches([]);
      setAvailableStock([]);
    } finally {
      setStockLoading(false);
    }
  };

  // 计算真实的预估消耗（使用后端API）
  const calculateRealEstimate = async () => {
    if (!formData.productTypeId || !formData.plannedQuantity) {
      return;
    }

    try {
      const result = await conversionApiClient.estimateMaterialUsage({
        productTypeId: formData.productTypeId,
        plannedQuantity: parseFloat(formData.plannedQuantity),
      });

      const estimateResult = result as { success?: boolean; data?: { plannedQuantity?: number; estimatedUsage?: number; conversionRate?: number; wastageRate?: number } };
      if (estimateResult.success && estimateResult.data) {
        setEstimatedUsage(estimateResult.data.estimatedUsage ?? null);
        setConversionRate(estimateResult.data.conversionRate ?? null);
        setWastageRate(estimateResult.data.wastageRate ?? null);

        productionPlanLogger.info('预估计算完成', {
          plannedQuantity: estimateResult.data.plannedQuantity,
          conversionRate: `${estimateResult.data.conversionRate}%`,
          wastageRate: `${estimateResult.data.wastageRate}%`,
          estimatedUsage: `${estimateResult.data.estimatedUsage}kg`,
        });
      }
    } catch (error) {
      productionPlanLogger.error('预估计算失败', error, {
        productTypeId: formData.productTypeId,
        plannedQuantity: formData.plannedQuantity,
      });
      // 降级：使用简单估算
      const quantity = parseFloat(formData.plannedQuantity);
      const fallbackRate = 0.57;
      const fallbackWastage = 0.05;
      const estimated = (quantity / fallbackRate) * (1 + fallbackWastage);
      setEstimatedUsage(parseFloat(estimated.toFixed(2)));
    }
  };

  const handleAdd = () => {
    setFormData({
      planType: 'FROM_INVENTORY',
      productTypeId: '',
      productTypeName: '',
      productTypeCode: '',
      customerId: '',
      customerName: '',
      plannedQuantity: '',
      plannedDate: new Date().toISOString().split('T')[0],
      expectedCompletionDate: getDefaultCompletionDate(),
      notes: '',
      suggestedProductionLineId: '',
      estimatedWorkers: '',
      assignedSupervisorId: '',
    });
    // 清空批次选择相关数据
    setMaterialTypeId('');
    setMaterialTypeName('');
    setAvailableBatches([]);
    setSelectedBatches([]);
    setEstimatedUsage(null);
    setConversionRate(null);
    setWastageRate(null);
    setModalVisible(true);
  };

  // Excel导入处理
  const handleImportExcel = async () => {
    try {
      const result = await DocumentPicker.getDocumentAsync({
        type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      });

      if (result.canceled || !result.assets || result.assets.length === 0) return;

      const file = result.assets[0];
      if (!file) return;
      const formDataUpload = new FormData();
      // @ts-expect-error - React Native FormData accepts {uri, name, type} objects, not Blob
      formDataUpload.append('file', {
        uri: file.uri,
        type: file.mimeType || 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        name: file.name || 'import.xlsx',
      });

      setImportLoading(true);
      const factoryId = user?.factoryUser?.factoryId || user?.factoryId || '';
      const response = await productionPlanApiClient.importFromExcel(factoryId, formDataUpload);

      if (response.success) {
        const importResult = response.data;
        Alert.alert(
          '导入完成',
          `总计: ${importResult.totalCount} 条\n成功: ${importResult.successCount} 条\n失败: ${importResult.failureCount} 条`,
          [{ text: '确定', onPress: () => loadPlans() }]
        );
      } else {
        Alert.alert('导入失败', response.message || '请检查文件格式');
      }
    } catch (error) {
      productionPlanLogger.error('Excel导入失败', error);
      Alert.alert('导入失败', getErrorMsg(error) || '请检查文件格式');
    } finally {
      setImportLoading(false);
    }
  };

  // AI对话创建生产计划
  const handleAIChatCreate = () => {
    navigation.dispatch(CommonActions.navigate('FAAITab', {
      screen: 'AIChat',
      params: { entityType: 'PRODUCTION_PLAN', initialMessage: '我要创建生产计划' },
    }));
  };

  const handleSave = async () => {
    if (!formData.productTypeId || !formData.customerId || !formData.plannedQuantity) {
      Alert.alert(t('common.hint', { defaultValue: '提示' }), t('productionPlan.validation.requiredFields'));
      return;
    }

    // 验证批次选择（可选验证）
    if (estimatedUsage && selectedBatches.length > 0) {
      const totalSelected = selectedBatches.reduce((sum, b) => sum + b.allocatedQuantity, 0);
      if (totalSelected < estimatedUsage) {
        const shortage = estimatedUsage - totalSelected;
        const confirmed = await new Promise<boolean>(resolve => {
          Alert.alert(
            '批次不足',
            `选中批次共${totalSelected.toFixed(1)}kg，还需${shortage.toFixed(1)}kg。\n\n是否继续创建？`,
            [
              { text: '取消', onPress: () => resolve(false), style: 'cancel' },
              { text: '继续创建', onPress: () => resolve(true) },
            ]
          );
        });

        if (!confirmed) return;
      }
    }

    try {
      const response = await productionPlanApiClient.createProductionPlan({
        planType: formData.planType,
        productTypeId: formData.productTypeId,
        customerId: formData.customerId,
        plannedQuantity: parseFloat(formData.plannedQuantity),
        plannedDate: formData.plannedDate,
        expectedCompletionDate: formData.expectedCompletionDate,
        notes: formData.notes || undefined,
        suggestedProductionLineId: formData.suggestedProductionLineId || undefined,
        estimatedWorkers: formData.estimatedWorkers ? parseInt(formData.estimatedWorkers, 10) : undefined,
        assignedSupervisorId: formData.assignedSupervisorId ? parseInt(formData.assignedSupervisorId, 10) : undefined,
      });

      if (response.success) {
        Alert.alert('成功', `生产计划创建成功${selectedBatches.length > 0 ? `\n已预留${selectedBatches.length}个批次的库存` : ''}`);
        setModalVisible(false);
        loadPlans();
      }
    } catch (error) {
      productionPlanLogger.error('创建生产计划失败', error, {
        productTypeId: formData.productTypeId,
        customerId: formData.customerId,
        plannedQuantity: formData.plannedQuantity,
        selectedBatchCount: selectedBatches.length,
      });
      Alert.alert('错误', getErrorMsg(error) || '创建失败');
    }
  };

  const handleStartProduction = async (planId: string) => {
    Alert.alert(
      '确认开始生产',
      '确定要开始生产吗?开始后将无法修改计划信息。',
      [
        { text: '取消', style: 'cancel' },
        {
          text: '开始',
          onPress: async () => {
            try {
              const response = await productionPlanApiClient.startProduction(planId);
              if (response.success) {
                Alert.alert('成功', '生产已开始');
                loadPlans();
              }
            } catch (error) {
              productionPlanLogger.error('开始生产失败', error, { planId });
              Alert.alert('错误', getErrorMsg(error) || '操作失败');
            }
          },
        },
      ]
    );
  };

  // 打开完成生产对话框
  const openCompleteDialog = (plan: ProductionPlan) => {
    setCompletingPlan(plan);
    setActualQuantity(plan.plannedQuantity?.toString() || '');
    setShowCompleteDialog(true);
  };

  // 处理完成生产
  const handleCompleteProduction = async () => {
    if (!completingPlan) return;

    const actualQty = parseFloat(actualQuantity);
    if (isNaN(actualQty) || actualQty <= 0) {
      Alert.alert('错误', '请输入有效的实际产量');
      return;
    }

    try {
      setCompleteLoading(true);
      const response = await productionPlanApiClient.completeProduction(
        completingPlan.id,
        actualQty
      );

      if (response.success) {
        Alert.alert('成功', `生产已完成，实际产量: ${actualQty} kg`);
        setShowCompleteDialog(false);
        setCompletingPlan(null);
        setActualQuantity('');
        loadPlans();
      }
    } catch (error) {
      productionPlanLogger.error('完成生产失败', error, {
        planId: completingPlan.id,
        actualQuantity: actualQty,
      });
      Alert.alert('错误', getErrorMsg(error) || '操作失败');
    } finally {
      setCompleteLoading(false);
    }
  };

  const getStatusColor = (status: string) => {
    const s = status?.toLowerCase();
    switch (s) {
      case 'pending': return '#FFA726';
      case 'in_progress': return '#2196F3';
      case 'completed': return '#66BB6A';
      case 'shipped': return '#9C27B0';
      case 'cancelled': return '#EF5350';
      default: return '#999';
    }
  };

  const getStatusText = (status: string) => {
    const s = status?.toLowerCase();
    switch (s) {
      case 'pending': return t('productionPlan.status.pending');
      case 'in_progress': return t('productionPlan.status.inProgress');
      case 'completed': return t('productionPlan.status.completed');
      case 'shipped': return t('productionPlan.status.shipped');
      case 'cancelled': return t('productionPlan.status.cancelled');
      default: return t('productionPlan.status.unknown');
    }
  };

  const getPlanTypeText = (planType?: string) => {
    switch (planType) {
      case 'FUTURE': return t('productionPlan.planType.future');
      case 'FROM_INVENTORY': return t('productionPlan.planType.fromInventory');
      default: return t('productionPlan.planType.fromInventory');
    }
  };

  const getPlanTypeColor = (planType?: string) => {
    switch (planType) {
      case 'FUTURE': return '#9C27B0';
      case 'FROM_INVENTORY': return '#4CAF50';
      default: return '#4CAF50';
    }
  };

  // 后端已经按filterStatus筛选,不需要前端再次筛选
  const filteredPlans = plans;

  return (
    <View style={styles.container}>
      {/* Header */}
      <Appbar.Header>
        <Appbar.BackAction onPress={() => navigation.goBack()} />
        <Appbar.Content title={t('productionPlan.title')} />
        {canCreatePlan && (
          <Appbar.Action
            icon="file-upload-outline"
            onPress={handleImportExcel}
            disabled={importLoading}
          />
        )}
        {canCreatePlan && (
          <Appbar.Action
            icon="robot-outline"
            onPress={handleAIChatCreate}
          />
        )}
        <Appbar.Action icon="refresh" onPress={loadPlans} />
      </Appbar.Header>

      {/* 导入进度提示 */}
      {importLoading && (
        <View style={styles.importBanner}>
          <ActivityIndicator size="small" color="#1976D2" />
          <Text style={styles.importBannerText}>正在导入Excel文件...</Text>
        </View>
      )}

      <ScrollView style={styles.content}>
        {/* Filter */}
        <Card style={styles.filterCard}>
          <Card.Content>
            <SegmentedButtons
              value={filterStatus}
              onValueChange={setFilterStatus}
              buttons={[
                { value: 'UNFINISHED', label: '未完成' },
                { value: 'all', label: t('productionPlan.filter.all') },
                { value: 'pending', label: t('productionPlan.filter.pending') },
                { value: 'in_progress', label: t('productionPlan.filter.inProgress') },
                { value: 'completed', label: t('productionPlan.filter.completed') },
              ]}
            />
          </Card.Content>
        </Card>

        {/* Stats */}
        <Card style={styles.statsCard}>
          <Card.Content>
            <View style={styles.statsRow}>
              <View style={styles.statItem}>
                <Text style={styles.statValue}>{plans.length}</Text>
                <Text style={styles.statLabel}>{t('productionPlan.stats.totalPlans')}</Text>
              </View>
              <View style={styles.statItem}>
                <Text style={styles.statValue}>
                  {plans.filter(p => p.status?.toLowerCase() === 'in_progress').length}
                </Text>
                <Text style={styles.statLabel}>{t('productionPlan.stats.inProgress')}</Text>
              </View>
              <View style={styles.statItem}>
                <Text style={styles.statValue}>
                  {plans.filter(p => p.status?.toLowerCase() === 'completed').length}
                </Text>
                <Text style={styles.statLabel}>{t('productionPlan.stats.completed')}</Text>
              </View>
            </View>
          </Card.Content>
        </Card>

        {/* Plans List */}
        {loading ? (
          <View style={styles.loadingContainer}>
            <ActivityIndicator size="large" />
            <Text style={styles.loadingText}>{t('common.loading')}</Text>
          </View>
        ) : filteredPlans.length === 0 ? (
          <Card style={styles.emptyCard}>
            <Card.Content style={styles.emptyContent}>
              <List.Icon icon="clipboard-text-outline" color="#999" />
              <Text style={styles.emptyText}>{t('productionPlan.empty.noPlans')}</Text>
              <Text style={styles.emptyHint}>{t('productionPlan.empty.hint')}</Text>
            </Card.Content>
          </Card>
        ) : (
          filteredPlans.map((plan) => {
            const materialAdvisory = materialAdvisories[plan.id];

            return (
            <Card key={plan.id} style={styles.planCard}>
              <Card.Content>
                {/* Header */}
                <View style={styles.planHeader}>
                  <View style={styles.planTitleRow}>
                    <View style={styles.planTitleLeft}>
                      <Text style={styles.planNumber}>{plan.planNumber}</Text>
                      {/* 计划类型标签 */}
                      <Chip
                        mode="flat"
                        compact
                        style={[
                          styles.planTypeChip,
                          { backgroundColor: `${getPlanTypeColor(plan.planType)}15` },
                        ]}
                        textStyle={{ color: getPlanTypeColor(plan.planType), fontSize: 10 }}
                      >
                        {getPlanTypeText(plan.planType)}
                      </Chip>
                    </View>
                    <View style={styles.planTitleRight}>
                      {/* 转换率配置状态 */}
                      <Chip
                        mode="flat"
                        compact
                        icon={plan.conversionRateConfigured ? 'check-circle' : 'alert-circle'}
                        style={[
                          styles.conversionChip,
                          { backgroundColor: plan.conversionRateConfigured ? '#E8F5E920' : '#FFF3E020' },
                        ]}
                        textStyle={{
                          color: plan.conversionRateConfigured ? '#4CAF50' : '#FF9800',
                          fontSize: 11,
                        }}
                      >
                        {plan.conversionRateConfigured
                          ? `${((plan.conversionRate ?? 0) * 100).toFixed(0)}%`
                          : '未配置'}
                      </Chip>
                      {/* 状态标签 */}
                      <Chip
                        mode="flat"
                        compact
                        style={[
                          styles.statusChip,
                          { backgroundColor: `${getStatusColor(plan.status)}20` },
                        ]}
                        textStyle={{ color: getStatusColor(plan.status) }}
                      >
                        {getStatusText(plan.status)}
                      </Chip>
                    </View>
                  </View>
                </View>

                <Divider style={styles.divider} />

                {/* Product & Merchant */}
                <View style={styles.planInfo}>
                  <View style={styles.infoRow}>
                    <List.Icon icon="package-variant" style={styles.icon} />
                    <View style={styles.infoContent}>
                      <Text style={styles.infoLabel}>产品</Text>
                      <Text style={styles.infoValue}>
                        {plan.productName || plan.productType?.name || plan.productTypeId}
                      </Text>
                    </View>
                  </View>

                  <View style={styles.infoRow}>
                    <List.Icon icon="store" style={styles.icon} />
                    <View style={styles.infoContent}>
                      <Text style={styles.infoLabel}>客户</Text>
                      <Text style={styles.infoValue}>{plan.customer?.name || '未指定'}</Text>
                    </View>
                  </View>

                  <View style={styles.infoRow}>
                    <List.Icon icon="calendar" style={styles.icon} />
                    <View style={styles.infoContent}>
                      <Text style={styles.infoLabel}>创建日期</Text>
                      <Text style={styles.infoValue}>
                        {plan.createdAt ? plan.createdAt.split('T')[0] : '-'}
                      </Text>
                    </View>
                  </View>

                  {plan.expectedCompletionDate && (
                    <View style={styles.infoRow}>
                      <List.Icon icon="calendar-check" style={styles.icon} />
                      <View style={styles.infoContent}>
                        <Text style={styles.infoLabel}>预计完成</Text>
                        <Text style={[styles.infoValue, { color: '#4CAF50' }]}>
                          {plan.expectedCompletionDate}
                        </Text>
                      </View>
                    </View>
                  )}
                </View>

                <Divider style={styles.divider} />

                {/* Quantities */}
                <View style={styles.quantitiesRow}>
                  <View style={styles.quantityItem}>
                    <Text style={styles.quantityLabel}>计划产量</Text>
                    <Text style={styles.quantityValue}>
                      {plan.plannedQuantity}kg
                    </Text>
                  </View>

                  {plan.estimatedMaterialUsage && (
                    <View style={styles.quantityItem}>
                      <Text style={styles.quantityLabel}>预估原料</Text>
                      <Text style={styles.quantityValue}>
                        {plan.estimatedMaterialUsage}kg
                      </Text>
                    </View>
                  )}

                  {plan.actualQuantity && (
                    <View style={styles.quantityItem}>
                      <Text style={styles.quantityLabel}>实际产量</Text>
                      <Text style={[styles.quantityValue, { color: '#4CAF50' }]}>
                        {plan.actualQuantity}kg
                      </Text>
                    </View>
                  )}
                </View>

                {/* 未来计划匹配进度 */}
                {plan.planType === 'FUTURE' && plan.status?.toLowerCase() === 'pending' && (
                  <Card style={styles.matchingProgressCard}>
                    <Card.Content>
                      <View style={styles.matchingProgressHeader}>
                        <List.Icon
                          icon={plan.isFullyMatched ? 'check-circle' : 'clock-outline'}
                          color={plan.isFullyMatched ? '#4CAF50' : '#9C27B0'}
                        />
                        <Text style={[
                          styles.matchingProgressTitle,
                          { color: plan.isFullyMatched ? '#4CAF50' : '#9C27B0' }
                        ]}>
                          {plan.isFullyMatched ? '原料已完全匹配' : '等待原料入库'}
                        </Text>
                      </View>
                      <View style={styles.matchingProgressBar}>
                        <View
                          style={[
                            styles.matchingProgressFill,
                            { width: `${plan.matchingProgress ?? 0}%` }
                          ]}
                        />
                      </View>
                      <View style={styles.matchingProgressDetails}>
                        <Text style={styles.matchingProgressText}>
                          已分配: {plan.allocatedQuantity ?? 0}kg / {plan.plannedQuantity}kg
                        </Text>
                        <Text style={[
                          styles.matchingProgressPercent,
                          { color: plan.isFullyMatched ? '#4CAF50' : '#9C27B0' }
                        ]}>
                          {plan.matchingProgress ?? 0}%
                        </Text>
                      </View>
                    </Card.Content>
                  </Card>
                )}

                {materialAdvisory && (
                  <Card style={[
                    styles.matchingProgressCard,
                    materialAdvisory.hasWarning && styles.materialWarningCard,
                  ]}>
                    <Card.Content>
                      <View style={styles.matchingProgressHeader}>
                        <List.Icon
                          icon={materialAdvisory.hasWarning ? 'alert-circle' : 'check-circle'}
                          color={materialAdvisory.hasWarning ? '#F44336' : '#4CAF50'}
                        />
                        <Text style={[
                          styles.matchingProgressTitle,
                          { color: materialAdvisory.hasWarning ? '#F44336' : '#4CAF50' },
                        ]}>
                          {materialAdvisory.hasWarning ? '\u539f\u6599\u7f3a\u6599\u9884\u8b66' : '\u539f\u6599\u5e93\u5b58\u53c2\u8003'}
                        </Text>
                      </View>
                      <Text style={styles.batchWarningHint}>
                        {materialAdvisory.message}
                      </Text>
                    </Card.Content>
                  </Card>
                )}

                {/* Actions */}
                {!isReadOnly && plan.status?.toLowerCase() === 'pending' && (
                  <Button
                    mode="contained"
                    icon="check"
                    onPress={() => openCompleteDialog(plan)}
                    style={styles.actionButton}
                  >
                    完成生产
                  </Button>
                )}

                {!isReadOnly && plan.status?.toLowerCase() === 'in_progress' && (
                  <View style={styles.actionRow}>
                    <Button
                      mode="contained"
                      icon="check"
                      onPress={() => openCompleteDialog(plan)}
                      style={styles.actionButton}
                    >
                      完成生产
                    </Button>
                  </View>
                )}

                {!isReadOnly && plan.status?.toLowerCase() === 'completed' && (
                  <Button
                    mode="contained"
                    icon="truck-delivery"
                    onPress={() => Alert.alert('提示', '记录出货功能')}
                    style={styles.actionButton}
                  >
                    记录出货
                  </Button>
                )}

                {/* 平台管理员只读提示 */}
                {isReadOnly && (
                  <Card style={styles.readOnlyCard}>
                    <Card.Content>
                      <Text style={styles.readOnlyText}>
                        👁️ 平台管理员只读模式 - 无法修改数据
                      </Text>
                    </Card.Content>
                  </Card>
                )}
              </Card.Content>
            </Card>
          );
          })
        )}

        <View style={styles.bottomPadding} />
      </ScrollView>

      {/* Create Modal */}
      <Portal>
        <Modal
          visible={modalVisible}
          onDismiss={() => setModalVisible(false)}
          contentContainerStyle={styles.modalContent}
        >
          <ScrollView>
            <Text style={styles.modalTitle}>创建生产计划</Text>

            {/* 计划类型选择 */}
            <View style={styles.planTypeSection}>
              <Text style={styles.sectionLabel}>计划类型</Text>
              <SegmentedButtons
                value={formData.planType}
                onValueChange={(value) =>
                  setFormData({ ...formData, planType: value as 'FUTURE' | 'FROM_INVENTORY' })
                }
                buttons={[
                  {
                    value: 'FROM_INVENTORY',
                    label: '基于库存',
                    icon: 'package-variant-closed',
                  },
                  {
                    value: 'FUTURE',
                    label: '未来计划',
                    icon: 'calendar-clock',
                  },
                ]}
                style={styles.planTypeButtons}
              />
              <Text style={styles.planTypeHint}>
                {formData.planType === 'FROM_INVENTORY'
                  ? '根据当前可用库存创建计划，需选择原材料批次'
                  : '预先规划的生产计划，可暂不指定具体批次'}
              </Text>
            </View>

            {/* 产品类型选择 */}
            <ProductTypeSelector
              value={formData.productTypeName}
              onSelect={(id, name, code) => {
                setFormData({
                  ...formData,
                  productTypeId: id,
                  productTypeName: name,
                  productTypeCode: code,
                });
              }}
              label="产品类型(SKU)"
              placeholder="选择产品SKU"
            />

            {/* Planned Quantity */}
            <TextInput
              label="计划产量 (kg) *"
              value={formData.plannedQuantity}
              onChangeText={(text) => setFormData({ ...formData, plannedQuantity: text })}
              mode="outlined"
              style={styles.input}
              keyboardType="decimal-pad"
              placeholder="例如: 100"
            />

            {/* Date Fields */}
            <View style={styles.dateRow}>
              <View style={styles.dateField}>
                <TextInput
                  label="计划日期"
                  value={formData.plannedDate}
                  onChangeText={(text) => setFormData({ ...formData, plannedDate: text })}
                  mode="outlined"
                  style={styles.dateInput}
                  placeholder="YYYY-MM-DD"
                  right={<TextInput.Icon icon="calendar" />}
                />
              </View>
              <View style={styles.dateField}>
                <TextInput
                  label="预计完成"
                  value={formData.expectedCompletionDate}
                  onChangeText={(text) => setFormData({ ...formData, expectedCompletionDate: text })}
                  mode="outlined"
                  style={styles.dateInput}
                  placeholder="YYYY-MM-DD"
                  right={<TextInput.Icon icon="calendar-check" />}
                />
              </View>
            </View>
            <Text style={styles.dateHint}>
              💡 预计完成日期默认为计划日期+1天，可手动修改
            </Text>

            {/* Estimated Material Usage */}
            {estimatedUsage !== null && (
              <Card style={styles.estimateCard}>
                <Card.Content>
                  <View style={styles.estimateRow}>
                    <List.Icon icon="calculator" color="#FF9800" />
                    <View style={styles.estimateContent}>
                      <Text style={styles.estimateLabel}>预估原料用量</Text>
                      <Text style={styles.estimateValue}>
                        约 {estimatedUsage.toFixed(1)} kg
                      </Text>
                    </View>
                  </View>
                </Card.Content>
              </Card>
            )}

            {/* Available Stock Display */}
            {availableStock.length > 0 && (
              <Card style={styles.stockCard}>
                <Card.Content>
                  <Text style={styles.stockTitle}>当前可用库存</Text>
                  {availableStock.map((stock, index) => (
                    <View key={index} style={styles.stockRow}>
                      <Text style={styles.stockCategory}>{stock.category}</Text>
                      <Text style={styles.stockValue}>
                        {stock.available}kg ({stock.batchCount}批次)
                      </Text>
                    </View>
                  ))}
                </Card.Content>
              </Card>
            )}

            {/* 目标商家选择 */}
            <CustomerSelector
              value={formData.customerName}
              onSelect={(id, name) => {
                setFormData({
                  ...formData,
                  customerId: id,
                  customerName: name,
                });
              }}
              label="目标商家(客户)"
              placeholder="选择客户"
            />

            {/* AI建议字段: 生产线 / 预估工人数 / 主管 */}
            <Card style={styles.aiSuggestionCard}>
              <Card.Content>
                <Text style={styles.aiSuggestionTitle}>生产安排 (可选)</Text>

                <TextInput
                  label="建议生产线ID"
                  value={formData.suggestedProductionLineId}
                  onChangeText={(text) => setFormData({ ...formData, suggestedProductionLineId: text })}
                  mode="outlined"
                  style={styles.input}
                  placeholder="例如: LINE-01"
                />

                <TextInput
                  label="预估所需工人数"
                  value={formData.estimatedWorkers}
                  onChangeText={(text) => setFormData({ ...formData, estimatedWorkers: text })}
                  mode="outlined"
                  style={styles.input}
                  keyboardType="number-pad"
                  placeholder="例如: 8"
                />

                <TextInput
                  label="指定主管ID"
                  value={formData.assignedSupervisorId}
                  onChangeText={(text) => setFormData({ ...formData, assignedSupervisorId: text })}
                  mode="outlined"
                  style={styles.input}
                  keyboardType="number-pad"
                  placeholder="例如: 15"
                />
              </Card.Content>
            </Card>

            {/* 原材料批次区域 - 仅基于库存类型显示 */}
            {formData.planType === 'FROM_INVENTORY' ? (
            <Card style={styles.batchSectionCard}>
              <Card.Content>
                <Text style={styles.batchSectionTitle}>原材料批次</Text>

                {/* 状态1: 加载中 */}
                {stockLoading && (
                  <View style={styles.batchStatusRow}>
                    <ActivityIndicator size="small" color="#1976D2" />
                    <Text style={styles.batchStatusText}>加载库存中...</Text>
                  </View>
                )}

                {/* 状态2: 加载错误 */}
                {!stockLoading && stockError && (
                  <View style={styles.batchStatusRow}>
                    <List.Icon icon="alert-circle" color="#F44336" />
                    <Text style={styles.batchErrorText}>{stockError}</Text>
                  </View>
                )}

                {/* 状态3: 未选择产品 */}
                {!stockLoading && !stockError && !formData.productTypeId && (
                  <View style={styles.batchStatusRow}>
                    <List.Icon icon="information-outline" color="#9E9E9E" />
                    <Text style={styles.batchHintText}>请先选择产品类型</Text>
                  </View>
                )}

                {/* 状态4: 未配置转换率 */}
                {!stockLoading && !stockError && formData.productTypeId && !materialTypeId && (
                  <View style={styles.batchWarningContainer}>
                    <View style={styles.batchWarningRow}>
                      <List.Icon icon="alert-circle-outline" color="#FF9800" />
                      <View style={styles.batchWarningTextContainer}>
                        <Text style={styles.batchWarningTitle}>未配置转换率</Text>
                        <Text style={styles.batchWarningHint}>
                          请先在转换率管理中配置该产品的转换率
                        </Text>
                      </View>
                    </View>
                    <Button
                      mode="contained"
                      compact
                      onPress={() => {
                        setModalVisible(false);
                        navigation.dispatch(
                          CommonActions.navigate({
                            name: 'Main',
                            params: {
                              screen: 'ManagementTab',
                              params: {
                                screen: 'ConversionRate',
                              },
                            },
                          })
                        );
                      }}
                      style={styles.configButton}
                    >
                      去配置
                    </Button>
                  </View>
                )}

                {/* 状态5: 无可用库存 */}
                {!stockLoading && !stockError && materialTypeId && availableBatches.length === 0 && (
                  <View style={styles.batchWarningRow}>
                    <List.Icon icon="alert" color="#F44336" />
                    <View>
                      <Text style={styles.batchWarningTitle}>
                        无可用{materialTypeName}库存
                      </Text>
                      <Text style={styles.batchWarningHint}>
                        请先入库{materialTypeName}原料
                      </Text>
                    </View>
                  </View>
                )}

                {/* 状态6: 等待输入计划产量 */}
                {!stockLoading && !stockError && materialTypeId && availableBatches.length > 0 && !estimatedUsage && (
                  <View style={styles.batchStatusRow}>
                    <List.Icon icon="calculator-variant-outline" color="#1976D2" />
                    <Text style={styles.batchHintText}>请输入计划产量以计算所需原料</Text>
                  </View>
                )}

                {/* 状态7: 显示批次选择器 */}
                {!stockLoading && !stockError && materialTypeId && availableBatches.length > 0 && estimatedUsage && estimatedUsage > 0 && (
                  <MaterialBatchSelector
                    availableBatches={availableBatches}
                    requiredQuantity={estimatedUsage}
                    selectedBatches={selectedBatches}
                    onSelect={setSelectedBatches}
                    mode="fifo"
                  />
                )}
              </Card.Content>
            </Card>
            ) : (
              /* 未来计划类型 - 显示自动匹配说明 */
              <Card style={styles.futurePlanInfoCard}>
                <Card.Content>
                  <View style={styles.futurePlanInfoRow}>
                    <List.Icon icon="calendar-clock" color="#9C27B0" />
                    <View style={styles.futurePlanInfoContent}>
                      <Text style={styles.futurePlanInfoTitle}>自动匹配原料</Text>
                      <Text style={styles.futurePlanInfoText}>
                        未来计划创建后，当新原料入库时系统将自动匹配到此计划。
                        您可以在计划列表中查看匹配进度。
                      </Text>
                    </View>
                  </View>
                </Card.Content>
              </Card>
            )}

            {/* Notes */}
            <TextInput
              label="备注"
              value={formData.notes}
              onChangeText={(text) => setFormData({ ...formData, notes: text })}
              mode="outlined"
              style={styles.input}
              multiline
              numberOfLines={3}
              placeholder="可选填写生产计划备注"
            />

            <View style={styles.modalActions}>
              <Button
                mode="outlined"
                onPress={() => setModalVisible(false)}
                style={styles.modalButton}
              >
                取消
              </Button>
              <Button
                mode="contained"
                onPress={handleSave}
                style={styles.modalButton}
              >
                创建计划
              </Button>
            </View>
          </ScrollView>
        </Modal>
      </Portal>

      {/* 完成生产对话框 */}
      <Portal>
        <Modal
          visible={showCompleteDialog}
          onDismiss={() => {
            setShowCompleteDialog(false);
            setCompletingPlan(null);
            setActualQuantity('');
          }}
          contentContainerStyle={styles.completeModalContent}
        >
          <Text style={styles.modalTitle}>完成生产</Text>

          {completingPlan && (
            <View style={styles.completeInfo}>
              {(() => {
                const completeAdvisory = materialAdvisories[completingPlan.id];
                return completeAdvisory?.hasWarning ? (
                  <Text style={styles.batchWarningHint}>
                    {completeAdvisory.message}
                  </Text>
                ) : null;
              })()}

              <Text style={styles.completeInfoLabel}>计划单号:</Text>
              <Text style={styles.completeInfoValue}>
                {completingPlan.planNumber || completingPlan.id}
              </Text>

              <Text style={styles.completeInfoLabel}>产品类型:</Text>
              <Text style={styles.completeInfoValue}>
                {completingPlan.productType?.name || completingPlan.productTypeId}
              </Text>

              <Text style={styles.completeInfoLabel}>计划产量:</Text>
              <Text style={styles.completeInfoValue}>
                {completingPlan.plannedQuantity} kg
              </Text>
            </View>
          )}

          <TextInput
            label="实际产量 (kg) *"
            value={actualQuantity}
            onChangeText={setActualQuantity}
            mode="outlined"
            style={styles.input}
            keyboardType="decimal-pad"
            placeholder="输入实际完成的产量"
          />

          {completingPlan && parseFloat(actualQuantity) > 0 && (
            <View style={styles.yieldInfo}>
              <Text style={styles.yieldInfoText}>
                完成率: {((parseFloat(actualQuantity) / (completingPlan.plannedQuantity || 1)) * 100).toFixed(1)}%
              </Text>
            </View>
          )}

          <View style={styles.modalActions}>
            <Button
              mode="outlined"
              onPress={() => {
                setShowCompleteDialog(false);
                setCompletingPlan(null);
                setActualQuantity('');
              }}
              style={styles.modalButton}
              disabled={completeLoading}
            >
              取消
            </Button>
            <Button
              mode="contained"
              onPress={handleCompleteProduction}
              style={styles.modalButton}
              loading={completeLoading}
              disabled={completeLoading || !actualQuantity}
            >
              确认完成
            </Button>
          </View>
        </Modal>
      </Portal>

      {/* FAB - 只有管理员可以创建 */}
      {canCreatePlan && (
        <FAB
          icon="plus"
          style={styles.fab}
          onPress={handleAdd}
          label="创建生产计划"
        />
      )}

      {/* 只读模式提示 */}
      {isReadOnly && (
        <Card style={[styles.fab, styles.readOnlyFab]}>
          <Card.Content style={styles.readOnlyFabContent}>
            <Text style={styles.readOnlyFabText}>👁️ 只读模式</Text>
          </Card.Content>
        </Card>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f5f5f5',
  },
  content: {
    flex: 1,
  },
  loadingContainer: {
    padding: 40,
    alignItems: 'center',
  },
  loadingText: {
    marginTop: 16,
    color: '#666',
  },
  filterCard: {
    margin: 16,
    marginBottom: 8,
  },
  statsCard: {
    margin: 16,
    marginBottom: 8,
  },
  statsRow: {
    flexDirection: 'row',
    justifyContent: 'space-around',
  },
  statItem: {
    alignItems: 'center',
  },
  statValue: {
    fontSize: 24,
    fontWeight: 'bold',
    color: '#2196F3',
  },
  statLabel: {
    fontSize: 12,
    color: '#666',
    marginTop: 4,
  },
  emptyCard: {
    margin: 16,
  },
  emptyContent: {
    alignItems: 'center',
    padding: 40,
  },
  emptyText: {
    fontSize: 16,
    color: '#999',
    marginTop: 8,
  },
  emptyHint: {
    fontSize: 14,
    color: '#bbb',
    marginTop: 8,
  },
  planCard: {
    margin: 16,
    marginBottom: 8,
  },
  planHeader: {
    marginBottom: 12,
  },
  planTitleRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-start',
  },
  planTitleLeft: {
    flexDirection: 'column',
    gap: 4,
    flex: 1,
  },
  planTitleRight: {
    flexDirection: 'column',
    alignItems: 'flex-end',
    gap: 4,
  },
  planNumber: {
    fontSize: 16,
    fontWeight: 'bold',
  },
  planTypeChip: {
    height: 24,
    alignSelf: 'flex-start',
    flexShrink: 0,
    minWidth: 90,
  },
  conversionChip: {
    height: 26,
    minWidth: 70,
    flexShrink: 0,
  },
  statusChip: {
    height: 28,
    minWidth: 80,
  },
  divider: {
    marginVertical: 12,
  },
  planInfo: {
    marginBottom: 12,
  },
  infoRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 8,
    minHeight: 40,
  },
  icon: {
    margin: 0,
    marginRight: 4,
    width: 32,
  },
  infoContent: {
    flex: 1,
  },
  infoLabel: {
    fontSize: 12,
    color: '#999',
  },
  infoValue: {
    fontSize: 15,
    color: '#333',
    fontWeight: '500',
  },
  quantitiesRow: {
    flexDirection: 'row',
    justifyContent: 'space-around',
    marginBottom: 16,
  },
  quantityItem: {
    alignItems: 'center',
  },
  quantityLabel: {
    fontSize: 12,
    color: '#999',
    marginBottom: 4,
  },
  quantityValue: {
    fontSize: 18,
    fontWeight: 'bold',
    color: '#2196F3',
  },
  actionButton: {
    marginTop: 8,
  },
  actionRow: {
    flexDirection: 'row',
    gap: 8,
    marginTop: 8,
  },
  smallActionButton: {
    flex: 1,
  },
  fab: {
    position: 'absolute',
    margin: 16,
    right: 0,
    bottom: 0,
  },
  modalContent: {
    backgroundColor: 'white',
    padding: 20,
    margin: 20,
    borderRadius: 8,
    maxHeight: '90%',
  },
  completeModalContent: {
    backgroundColor: 'white',
    padding: 20,
    margin: 20,
    borderRadius: 8,
  },
  completeInfo: {
    marginBottom: 16,
    padding: 12,
    backgroundColor: '#F5F5F5',
    borderRadius: 8,
  },
  completeInfoLabel: {
    fontSize: 12,
    color: '#666',
    marginBottom: 4,
  },
  completeInfoValue: {
    fontSize: 16,
    fontWeight: '500',
    color: '#333',
    marginBottom: 12,
  },
  yieldInfo: {
    padding: 12,
    backgroundColor: '#E3F2FD',
    borderRadius: 8,
    marginBottom: 16,
    alignItems: 'center',
  },
  yieldInfoText: {
    fontSize: 16,
    fontWeight: '600',
    color: '#1976D2',
  },
  modalTitle: {
    fontSize: 20,
    fontWeight: 'bold',
    marginBottom: 20,
  },
  pickerContainer: {
    marginBottom: 16,
  },
  pickerLabel: {
    fontSize: 14,
    color: '#666',
    marginBottom: 8,
  },
  pickerWrapper: {
    borderWidth: 1,
    borderColor: '#ccc',
    borderRadius: 4,
  },
  picker: {
    height: 50,
  },
  input: {
    marginBottom: 16,
  },
  estimateCard: {
    marginBottom: 16,
    backgroundColor: '#FFF3E0',
  },
  estimateRow: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  estimateContent: {
    flex: 1,
    marginLeft: -8,
  },
  estimateLabel: {
    fontSize: 12,
    color: '#E65100',
  },
  estimateValue: {
    fontSize: 20,
    fontWeight: 'bold',
    color: '#FF9800',
    marginTop: 4,
  },
  stockCard: {
    marginBottom: 16,
    backgroundColor: '#E8F5E9',
  },
  stockTitle: {
    fontSize: 14,
    fontWeight: 'bold',
    marginBottom: 12,
    color: '#2E7D32',
  },
  stockRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginBottom: 4,
  },
  stockCategory: {
    fontSize: 14,
    color: '#666',
  },
  stockValue: {
    fontSize: 14,
    fontWeight: '600',
    color: '#4CAF50',
  },
  modalActions: {
    flexDirection: 'row',
    justifyContent: 'flex-end',
    gap: 12,
    marginTop: 8,
  },
  modalButton: {
    minWidth: 100,
  },
  bottomPadding: {
    height: 80,
  },
  readOnlyCard: {
    marginTop: 12,
    backgroundColor: '#FFF3E0',
  },
  readOnlyText: {
    fontSize: 13,
    color: '#E65100',
    textAlign: 'center',
  },
  readOnlyFab: {
    backgroundColor: '#FFE0B2',
    borderRadius: 28,
    paddingVertical: 8,
    paddingHorizontal: 16,
  },
  readOnlyFabContent: {
    paddingVertical: 0,
    paddingHorizontal: 0,
  },
  readOnlyFabText: {
    fontSize: 14,
    color: '#E65100',
    fontWeight: 'bold',
  },
  batchSelectorPlaceholder: {
    marginVertical: 12,
    backgroundColor: '#FFF8E1',
    borderLeftWidth: 4,
    borderLeftColor: '#FFA000',
  },
  placeholderContent: {
    flexDirection: 'row',
    alignItems: 'flex-start',
  },
  placeholderText: {
    flex: 1,
    marginLeft: 8,
  },
  placeholderTitle: {
    color: '#E65100',
    fontWeight: '600',
    marginBottom: 4,
  },
  placeholderHint: {
    color: '#F57C00',
    marginTop: 2,
  },
  warningCard: {
    marginVertical: 12,
    backgroundColor: '#FFF3E0',
    borderLeftWidth: 4,
    borderLeftColor: '#FF9800',
  },
  warningContent: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
  },
  warningTitle: {
    color: '#E65100',
    fontWeight: '600',
    marginBottom: 4,
  },
  warningHint: {
    color: '#F57C00',
  },
  warningTextContainer: {
    flex: 1,
  },
  configButton: {
    backgroundColor: '#FF9800',
  },
  // 批次区域样式
  batchSectionCard: {
    marginVertical: 12,
    backgroundColor: '#FAFAFA',
    borderWidth: 1,
    borderColor: '#E0E0E0',
  },
  batchSectionTitle: {
    fontSize: 16,
    fontWeight: '600',
    color: '#333',
    marginBottom: 12,
  },
  batchStatusRow: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 12,
    gap: 8,
  },
  batchStatusText: {
    fontSize: 14,
    color: '#1976D2',
  },
  batchErrorText: {
    fontSize: 14,
    color: '#F44336',
    flex: 1,
  },
  batchHintText: {
    fontSize: 14,
    color: '#757575',
  },
  batchWarningContainer: {
    paddingVertical: 8,
  },
  batchWarningRow: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    gap: 8,
    marginBottom: 8,
  },
  batchWarningTextContainer: {
    flex: 1,
  },
  batchWarningTitle: {
    fontSize: 14,
    fontWeight: '600',
    color: '#E65100',
    marginBottom: 4,
  },
  batchWarningHint: {
    fontSize: 13,
    color: '#F57C00',
  },
  // 计划类型选择样式
  planTypeSection: {
    marginBottom: 20,
    backgroundColor: '#F5F5F5',
    padding: 16,
    borderRadius: 8,
  },
  sectionLabel: {
    fontSize: 14,
    fontWeight: '600',
    color: '#333',
    marginBottom: 12,
  },
  planTypeButtons: {
    marginBottom: 8,
  },
  planTypeHint: {
    fontSize: 12,
    color: '#666',
    textAlign: 'center',
    marginTop: 8,
    fontStyle: 'italic',
  },
  // 日期字段样式
  dateRow: {
    flexDirection: 'row',
    gap: 12,
    marginBottom: 4,
  },
  dateField: {
    flex: 1,
  },
  dateInput: {
    fontSize: 14,
  },
  dateHint: {
    fontSize: 12,
    color: '#888',
    marginBottom: 16,
    paddingHorizontal: 4,
  },
  // 未来计划信息卡片样式
  futurePlanInfoCard: {
    marginVertical: 12,
    backgroundColor: '#F3E5F5',
    borderWidth: 1,
    borderColor: '#CE93D8',
  },
  futurePlanInfoRow: {
    flexDirection: 'row',
    alignItems: 'flex-start',
  },
  futurePlanInfoContent: {
    flex: 1,
    marginLeft: 8,
  },
  futurePlanInfoTitle: {
    fontSize: 14,
    fontWeight: '600',
    color: '#7B1FA2',
    marginBottom: 4,
  },
  futurePlanInfoText: {
    fontSize: 13,
    color: '#9C27B0',
    lineHeight: 18,
  },
  // 导入进度提示条
  importBanner: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#E3F2FD',
    paddingHorizontal: 16,
    paddingVertical: 10,
    gap: 10,
  },
  importBannerText: {
    fontSize: 14,
    color: '#1976D2',
  },
  // AI建议字段卡片
  aiSuggestionCard: {
    marginVertical: 12,
    backgroundColor: '#F3E5F5',
    borderWidth: 1,
    borderColor: '#CE93D8',
  },
  aiSuggestionTitle: {
    fontSize: 14,
    fontWeight: '600',
    color: '#7B1FA2',
    marginBottom: 12,
  },
  // 匹配进度卡片样式
  matchingProgressCard: {
    marginTop: 12,
    backgroundColor: '#F3E5F5',
    borderWidth: 1,
    borderColor: '#CE93D8',
  },
  materialWarningCard: {
    backgroundColor: '#FFEBEE',
    borderColor: '#EF9A9A',
  },
  matchingProgressHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 8,
  },
  matchingProgressTitle: {
    fontSize: 14,
    fontWeight: '600',
    marginLeft: 4,
  },
  matchingProgressBar: {
    height: 8,
    backgroundColor: '#E1BEE7',
    borderRadius: 4,
    overflow: 'hidden',
    marginBottom: 8,
  },
  matchingProgressFill: {
    height: '100%',
    backgroundColor: '#9C27B0',
    borderRadius: 4,
  },
  matchingProgressDetails: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  matchingProgressText: {
    fontSize: 12,
    color: '#666',
  },
  matchingProgressPercent: {
    fontSize: 16,
    fontWeight: 'bold',
  },
});
