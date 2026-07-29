/**
 * MaterialReceiptAIScreen - AI 智能原材料入库页面
 *
 * 使用 Formily DynamicForm + AI 表单助手
 * 功能:
 * 1. **动态Schema加载** - 从后端加载自定义字段配置
 * 2. Schema 驱动的动态表单渲染
 * 3. 字段联动 (总金额自动计算、冻货温度显示)
 * 4. AI 助手 (语音/文本/OCR 自动填表)
 * 5. 实时验证
 */

import React, { useRef, useState, useEffect, useCallback } from 'react';
import { View, StyleSheet, Alert } from 'react-native';
import {
  Text,
  Appbar,
  Card,
  Banner,
  Chip,
  ActivityIndicator,
  Badge,
} from 'react-native-paper';
import { useNavigation } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useTranslation } from 'react-i18next';
import {
  DynamicForm,
  DynamicFormRef,
  materialBatchSchema,
  schemaService,
  type FormSchema,
} from '../../formily';
import { EntityType } from '../../services/api/formTemplateApiClient';
import { materialBatchApiClient } from '../../services/api/materialBatchApiClient';
import { supplierApiClient } from '../../services/api/supplierApiClient';
import { useAuthStore } from '../../store/authStore';
import { getErrorMsg } from '../../utils/errorHandler';
import { logger } from '../../utils/logger';
import type { MaterialBatchFormData } from '../../formily/schemas/materialBatch.schema';
import { ProcessingStackParamList } from '../../types/navigation';

const screenLogger = logger.createContextLogger('MaterialReceiptAI');

/**
 * AI 智能原材料入库页面
 */
type NavProp = NativeStackNavigationProp<ProcessingStackParamList, 'MaterialReceiptAI'>;

export default function MaterialReceiptAIScreen() {
  const navigation = useNavigation<NavProp>();
  const { user } = useAuthStore();
  const formRef = useRef<DynamicFormRef>(null);
  const { t } = useTranslation('processing');

  const [loading, setLoading] = useState(false);
  const [loadingSchema, setLoadingSchema] = useState(true);
  const [loadingSuppliers, setLoadingSuppliers] = useState(true);
  const [suppliers, setSuppliers] = useState<Array<{ label: string; value: string }>>([]);
  const [aiEnabled, setAiEnabled] = useState(true);
  const [aiFillInfo, setAiFillInfo] = useState<{
    filled: boolean;
    fieldCount: number;
    confidence: number;
  } | null>(null);

  // 🔥 动态Schema状态 - 真正的"动态"在这里！
  const [dynamicSchema, setDynamicSchema] = useState<FormSchema>(materialBatchSchema);
  const [schemaInfo, setSchemaInfo] = useState<{
    isCustomized: boolean;
    customFieldCount: number;
    customFieldNames: string[];
    source: string | null;
    version: number | null;
  } | null>(null);

  // 🔥 动态加载Schema - 核心"动态"逻辑
  const loadDynamicSchema = useCallback(async () => {
    try {
      setLoadingSchema(true);
      screenLogger.info('开始加载动态Schema', { factoryId: user?.factoryId });

      // 调用 schemaService 获取合并后的 Schema
      const result = await schemaService.getMergedSchema(
        'MATERIAL_BATCH',
        materialBatchSchema,
        user?.factoryId
      );

      // 保存Schema信息
      setSchemaInfo({
        isCustomized: result.isCustomized,
        customFieldCount: result.customFieldCount,
        customFieldNames: result.customFieldNames,
        source: result.source,
        version: result.version,
      });

      // 设置动态Schema
      setDynamicSchema(result.schema);

      if (result.isCustomized) {
        screenLogger.info('加载自定义Schema成功', {
          customFieldCount: result.customFieldCount,
          customFieldNames: result.customFieldNames,
          source: result.source,
          version: result.version,
        });
      } else {
        screenLogger.info('使用默认Schema (无自定义字段)');
      }
    } catch (error) {
      screenLogger.warn('加载动态Schema失败，使用默认Schema', error as Error);
      // 失败时使用默认Schema
      setDynamicSchema(materialBatchSchema);
      setSchemaInfo(null);
    } finally {
      setLoadingSchema(false);
    }
  }, [user?.factoryId]);

  // 加载供应商列表并更新Schema
  const loadSuppliers = useCallback(async () => {
    try {
      setLoadingSuppliers(true);
      const response = await supplierApiClient.getActiveSuppliers(user?.factoryId);
      const supplierOptions = response.map((s: any) => ({
        label: `${s.name} (${s.supplierCode})`,
        value: s.id,
      }));
      setSuppliers(supplierOptions);

      // 更新 schema 中的供应商枚举 (在动态Schema基础上)
      setDynamicSchema(prev => {
        const updatedSchema: FormSchema = {
          ...prev,
          properties: {
            ...prev.properties,
            supplierId: {
              ...prev.properties.supplierId,
              type: prev.properties.supplierId?.type || 'string',
              enum: supplierOptions,
            },
          },
        };
        return updatedSchema;
      });

      screenLogger.info('供应商列表加载成功', { count: supplierOptions.length });
    } catch (error) {
      screenLogger.error('加载供应商失败', error as Error);
      Alert.alert('错误', '加载供应商列表失败，请稍后重试');
    } finally {
      setLoadingSuppliers(false);
    }
  }, [user?.factoryId]);

  // 初始化：先加载动态Schema，再加载供应商
  useEffect(() => {
    const init = async () => {
      await loadDynamicSchema();
    };
    init();
  }, [loadDynamicSchema]);

  // Schema加载完成后加载供应商
  //
  // 🔴 2026-07-30 修复死循环: 之前这里把 dynamicSchema 放进依赖数组,
  // 而 loadSuppliers 成功后会 setDynamicSchema(prev => ({...prev, ...})) 产生新的对象引用,
  // 触发本 effect 重新执行 → 再次 setDynamicSchema → 无限重复调用 /suppliers/active,
  // 页面卡在"加载供应商列表..."永不进入表单 (客户反馈的"白屏"/只能清后台重进)。
  // loadSuppliers 内部只用 setState 的函数式更新，不读取传入的 schema 参数，
  // 因此这里不需要也不应该依赖 dynamicSchema —— 只在 loadingSchema 由 true 变 false 的
  // 那一次触发即可。
  useEffect(() => {
    if (!loadingSchema) {
      loadSuppliers();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- dynamicSchema 故意排除, 见上方注释
  }, [loadingSchema, loadSuppliers]);

  // 初始值
  const initialValues: Partial<MaterialBatchFormData> = {
    storageType: 'fresh',
    qualityStatus: 'qualified',
  };

  // 提交处理
  const handleSubmit = async (values: Record<string, any>) => {
    try {
      setLoading(true);

      // 生成批次号
      const factoryId = user?.factoryId || 'F001';
      const timestamp = Date.now().toString(36).toUpperCase();
      const batchNumber = `MB-${factoryId}-${timestamp}`;

      // 计算到期日期
      const expiryDate = new Date();
      expiryDate.setDate(expiryDate.getDate() + (values.shelfLife || 30));
      const expiryDateStr = expiryDate.toISOString().split('T')[0];

      const batchData = {
        batchNumber,
        supplierId: values.supplierId,
        materialTypeId: values.materialTypeId,
        receiptQuantity: values.quantity,
        unitPrice: values.unitPrice,
        totalCost: values.totalCost || (values.quantity * values.unitPrice),
        storageType: values.storageType,
        storageLocation: values.storageLocation,
        freezeTemperature: values.freezeTemperature,
        shelfLife: values.shelfLife,
        expiryDate: expiryDateStr,
        qualityGrade: values.inspector,
        qualityStatus: values.qualityStatus,
        qualityScore: values.qualityScore,
        qualityNotes: values.unqualifiedReason || values.qualityNotes,
        notes: values.notes,
      };

      await materialBatchApiClient.createBatch(batchData, factoryId);

      screenLogger.info('AI 智能入库成功', {
        batchNumber,
        aiFilled: aiFillInfo?.filled || false,
        aiConfidence: aiFillInfo?.confidence,
      });

      // Resolve supplier display name from the loaded options
      const matchedSupplier = suppliers.find(s => s.value === values.supplierId);
      const supplierDisplayName = matchedSupplier ? matchedSupplier.label : '';

      // Navigate to success screen instead of showing Alert
      navigation.replace('MaterialBatchSuccess', {
        batchNumber,
        materialName: typeof values.materialTypeId === 'string' ? values.materialTypeId : '',
        quantity: values.quantity ?? 0,
        supplierName: supplierDisplayName,
      });
    } catch (error) {
      screenLogger.error('入库失败', error as Error);
      Alert.alert(t('materialReceiptAI.inboundFailed'), getErrorMsg(error) || t('materialReceiptAI.retryLater'));
    } finally {
      setLoading(false);
    }
  };

  // AI 填充成功回调
  const handleAIFillSuccess = (values: Record<string, unknown>, confidence: number) => {
    const fieldCount = Object.keys(values).length;
    setAiFillInfo({
      filled: true,
      fieldCount,
      confidence,
    });

    screenLogger.info('AI 填充成功', { fieldCount, confidence });

    Alert.alert(
      t('materialReceiptAI.aiSuccess'),
      t('materialReceiptAI.aiSuccessMessage', {
        count: fieldCount,
        confidence: (confidence * 100).toFixed(0),
      }),
      [{ text: t('materialReceiptAI.ok') }]
    );
  };

  // AI 填充失败回调
  const handleAIFillError = (error: string) => {
    screenLogger.warn('AI 填充失败', { error });
    Alert.alert(t('materialReceiptAI.aiFailed'), error);
  };

  // 取消处理
  const handleCancel = () => {
    Alert.alert(t('materialReceiptAI.confirmCancel'), t('materialReceiptAI.confirmCancelMessage'), [
      { text: t('materialReceiptAI.continueEditing'), style: 'cancel' },
      { text: t('materialReceiptAI.cancelInbound'), style: 'destructive', onPress: () => navigation.goBack() },
    ]);
  };

  // 切换 AI 助手
  const toggleAI = () => {
    setAiEnabled(!aiEnabled);
    if (aiEnabled) {
      setAiFillInfo(null);
    }
  };

  // 加载中状态
  if (loadingSchema || loadingSuppliers) {
    return (
      <SafeAreaView style={styles.container} edges={['bottom']}>
        <Appbar.Header>
          <Appbar.BackAction onPress={() => navigation.goBack()} />
          <Appbar.Content title={t('materialReceiptAI.title')} />
        </Appbar.Header>
        <View style={styles.loadingContainer}>
          <ActivityIndicator size="large" />
          <Text style={styles.loadingText}>
            {loadingSchema ? t('materialReceiptAI.loadingFormConfig') : t('materialReceiptAI.loadingSuppliers')}
          </Text>
        </View>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={styles.container} edges={['bottom']}>
      {/* Header */}
      <Appbar.Header>
        <Appbar.BackAction onPress={handleCancel} />
        <Appbar.Content title={t('materialReceiptAI.title')} subtitle={t('materialReceiptAI.subtitle')} />
        <Appbar.Action
          icon={aiEnabled ? 'robot' : 'robot-off'}
          onPress={toggleAI}
        />
      </Appbar.Header>

      {/* AI 助手提示 */}
      {aiEnabled && (
        <Banner
          visible={true}
          icon="robot"
          actions={[
            { label: t('materialReceiptAI.closeAI'), onPress: toggleAI },
          ]}
          style={styles.aiBanner}
        >
          {t('materialReceiptAI.aiEnabled')}
        </Banner>
      )}

      {/* AI 填充状态 */}
      {aiFillInfo?.filled && (
        <Card style={styles.aiStatusCard}>
          <Card.Content style={styles.aiStatusContent}>
            <Chip icon="check-circle" style={styles.aiSuccessChip}>
              {t('materialReceiptAI.aiFilled')} {aiFillInfo.fieldCount} {t('materialReceiptAI.fields')}
            </Chip>
            <Text style={styles.aiConfidence}>
              {t('materialReceiptAI.confidence')}: {(aiFillInfo.confidence * 100).toFixed(0)}%
            </Text>
          </Card.Content>
        </Card>
      )}

      {/* 🔥 动态Schema状态 - 显示自定义字段信息 */}
      {schemaInfo?.isCustomized && (
        <Card style={styles.customSchemaCard}>
          <Card.Content style={styles.customSchemaContent}>
            <View style={styles.customSchemaHeader}>
              <Chip icon="puzzle" style={styles.customSchemaChip}>
                +{schemaInfo.customFieldCount} {t('materialReceiptAI.customFields')}
              </Chip>
              {schemaInfo.version && (
                <Badge style={styles.versionBadge}>{`v${schemaInfo.version}`}</Badge>
              )}
            </View>
            <Text style={styles.customFieldNames}>
              {schemaInfo.customFieldNames.join('、')}
            </Text>
          </Card.Content>
        </Card>
      )}

      {/* 动态表单 */}
      <DynamicForm
        ref={formRef}
        schema={dynamicSchema}
        initialValues={initialValues}
        onSubmit={handleSubmit}
        submitText={loading ? t('materialReceiptAI.inbounding') : t('materialReceiptAI.confirmInbound')}
        disabled={loading}
        scrollable={true}
        // AI 助手配置
        enableAIAssistant={aiEnabled}
        entityType={'MATERIAL_BATCH' as EntityType}
        aiContext={{
          formType: 'material_receipt',
          factoryId: user?.factoryId,
          suppliers: suppliers.map(s => s.label),
        }}
        onAIFillSuccess={handleAIFillSuccess}
        onAIFillError={handleAIFillError}
      />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f5f5f5',
  },
  loadingContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
  },
  loadingText: {
    marginTop: 16,
    fontSize: 14,
    color: '#666',
  },
  aiBanner: {
    backgroundColor: '#e3f2fd',
  },
  aiStatusCard: {
    marginHorizontal: 16,
    marginTop: 8,
    backgroundColor: '#e8f5e9',
  },
  aiStatusContent: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  aiSuccessChip: {
    backgroundColor: '#c8e6c9',
  },
  aiConfidence: {
    fontSize: 12,
    color: '#2e7d32',
  },
  // 🔥 动态Schema自定义字段样式
  customSchemaCard: {
    marginHorizontal: 16,
    marginTop: 8,
    backgroundColor: '#fff3e0',
    borderLeftWidth: 4,
    borderLeftColor: '#ff9800',
  },
  customSchemaContent: {
    paddingVertical: 4,
  },
  customSchemaHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  customSchemaChip: {
    backgroundColor: '#ffe0b2',
  },
  versionBadge: {
    backgroundColor: '#ff9800',
  },
  customFieldNames: {
    marginTop: 8,
    fontSize: 12,
    color: '#e65100',
    fontStyle: 'italic',
  },
});
