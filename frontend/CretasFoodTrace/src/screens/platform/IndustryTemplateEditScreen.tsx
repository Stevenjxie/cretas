/**
 * 行业模板编辑界面
 *
 * 平台管理员用于创建/编辑行业模板包:
 * - 设置行业基础信息 (代码、名称、描述)
 * - 添加/编辑实体类型 Schema
 * - 支持 AI 辅助生成 Schema
 * - 预览和保存模板
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-29
 */

import React, { useState, useEffect, useCallback } from 'react';
import {
  View,
  StyleSheet,
  ScrollView,
  Alert,
  KeyboardAvoidingView,
  Platform,
} from 'react-native';
import {
  Appbar,
  Text,
  TextInput,
  Button,
  Card,
  Chip,
  IconButton,
  Divider,
  ActivityIndicator,
  Portal,
  Modal,
  List,
  Switch,
  FAB,
  HelperText,
  Menu,
} from 'react-native-paper';
import { useNavigation, useRoute, RouteProp } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useTranslation } from 'react-i18next';
import { PlatformStackParamList } from '../../navigation/PlatformStackNavigator';
import {
  templatePackageApiClient,
  IndustryTemplatePackage,
  TemplatePackageDetail,
  CreateTemplatePackageRequest,
  UpdateTemplatePackageRequest,
} from '../../services/api/templatePackageApiClient';
import { EntityType } from '../../services/api/formTemplateApiClient';
import { handleError } from '../../utils/errorHandler';
import { logger } from '../../utils/logger';
import { VisualSchemaEditor } from '../../components/schema/VisualSchemaEditor';
import { FormilySchema } from '../../utils/schemaUtils';

// 创建专用 logger
const editLogger = logger.createContextLogger('IndustryTemplateEdit');

type NavigationProp = NativeStackNavigationProp<PlatformStackParamList>;
type RouteProps = RouteProp<PlatformStackParamList, 'IndustryTemplateEdit'>;

// 预定义的行业类型
const INDUSTRY_PRESETS = [
  { code: 'seafood_processing', name: '水产加工', icon: 'fish' },
  { code: 'prepared_food', name: '预制菜加工', icon: 'food-variant' },
  { code: 'meat_processing', name: '肉类加工', icon: 'food-steak' },
  { code: 'dairy_processing', name: '乳制品加工', icon: 'cup' },
  { code: 'beverage', name: '饮料生产', icon: 'bottle-wine' },
  { code: 'bakery', name: '烘焙食品', icon: 'bread-slice' },
  { code: 'frozen_food', name: '冷冻食品', icon: 'snowflake' },
  { code: 'canned_food', name: '罐头食品', icon: 'food-variant' },
  { code: 'custom', name: '自定义行业', icon: 'factory' },
];

// 实体类型信息
const ENTITY_TYPE_INFO: Record<EntityType, { name: string; description: string; icon: string }> = {
  QUALITY_CHECK: { name: '质检记录', description: '质量检测相关字段', icon: 'clipboard-check' },
  MATERIAL_BATCH: { name: '原料批次', description: '原材料入库相关字段', icon: 'package-variant' },
  PROCESSING_BATCH: { name: '生产批次', description: '生产加工相关字段', icon: 'cog' },
  SHIPMENT: { name: '出货记录', description: '出货物流相关字段', icon: 'truck-delivery' },
  EQUIPMENT: { name: '设备管理', description: '设备信息相关字段', icon: 'tools' },
  DISPOSAL_RECORD: { name: '处置记录', description: '废弃处置相关字段', icon: 'delete' },
  PRODUCT_TYPE: { name: '产品类型', description: '产品类型配置字段', icon: 'barcode' },
  PRODUCTION_PLAN: { name: '生产计划', description: '生产计划相关字段', icon: 'calendar-clock' },
  SCALE_DEVICE: { name: '电子秤设备', description: 'IoT电子秤设备相关字段', icon: 'scale' },
  SCALE_PROTOCOL: { name: '电子秤协议', description: '电子秤协议文档相关字段', icon: 'file-document-outline' },
  ISAPI_DEVICE: { name: 'ISAPI设备', description: 'ISAPI摄像头设备相关字段', icon: 'video' },
  RAW_MATERIAL_TYPE: { name: '原料类型', description: '原料类型配置字段', icon: 'package-variant-closed' },
  PURCHASE_ORDER: { name: '采购订单', description: '采购订单表单字段', icon: 'file-document-edit' },
};

// 默认 Schema 模板
const DEFAULT_SCHEMAS: Record<EntityType, object> = {
  QUALITY_CHECK: {
    type: 'object',
    properties: {
      temperature: {
        type: 'number',
        title: '温度(°C)',
        'x-component': 'NumberPicker',
        'x-component-props': { min: -50, max: 50, step: 0.1 },
      },
      humidity: {
        type: 'number',
        title: '湿度(%)',
        'x-component': 'NumberPicker',
        'x-component-props': { min: 0, max: 100, step: 1 },
      },
      result: {
        type: 'string',
        title: '检测结果',
        enum: ['合格', '不合格', '待复检'],
        'x-component': 'Select',
      },
      remarks: {
        type: 'string',
        title: '备注',
        'x-component': 'TextArea',
      },
    },
  },
  MATERIAL_BATCH: {
    type: 'object',
    properties: {
      supplier: {
        type: 'string',
        title: '供应商',
        'x-component': 'Input',
      },
      quantity: {
        type: 'number',
        title: '数量',
        'x-component': 'NumberPicker',
      },
      unit: {
        type: 'string',
        title: '单位',
        enum: ['kg', 'g', '件', '箱'],
        'x-component': 'Select',
      },
      productionDate: {
        type: 'string',
        title: '生产日期',
        'x-component': 'DatePicker',
      },
      expiryDate: {
        type: 'string',
        title: '保质期至',
        'x-component': 'DatePicker',
      },
    },
  },
  PROCESSING_BATCH: {
    type: 'object',
    properties: {
      productType: {
        type: 'string',
        title: '产品类型',
        'x-component': 'Select',
      },
      plannedQuantity: {
        type: 'number',
        title: '计划产量',
        'x-component': 'NumberPicker',
      },
      actualQuantity: {
        type: 'number',
        title: '实际产量',
        'x-component': 'NumberPicker',
      },
      startTime: {
        type: 'string',
        title: '开始时间',
        'x-component': 'DatePicker',
      },
      endTime: {
        type: 'string',
        title: '结束时间',
        'x-component': 'DatePicker',
      },
    },
  },
  SHIPMENT: {
    type: 'object',
    properties: {
      customer: {
        type: 'string',
        title: '客户名称',
        'x-component': 'Input',
      },
      destination: {
        type: 'string',
        title: '目的地',
        'x-component': 'Input',
      },
      quantity: {
        type: 'number',
        title: '出货数量',
        'x-component': 'NumberPicker',
      },
      carrier: {
        type: 'string',
        title: '承运商',
        'x-component': 'Input',
      },
      trackingNumber: {
        type: 'string',
        title: '物流单号',
        'x-component': 'Input',
      },
    },
  },
  EQUIPMENT: {
    type: 'object',
    properties: {
      equipmentName: {
        type: 'string',
        title: '设备名称',
        'x-component': 'Input',
      },
      model: {
        type: 'string',
        title: '型号',
        'x-component': 'Input',
      },
      status: {
        type: 'string',
        title: '状态',
        enum: ['正常', '维护中', '故障', '停用'],
        'x-component': 'Select',
      },
      lastMaintenanceDate: {
        type: 'string',
        title: '上次维护日期',
        'x-component': 'DatePicker',
      },
    },
  },
  DISPOSAL_RECORD: {
    type: 'object',
    properties: {
      disposalType: {
        type: 'string',
        title: '处置类型',
        enum: ['销毁', '返工', '降级使用', '其他'],
        'x-component': 'Select',
      },
      quantity: {
        type: 'number',
        title: '处置数量',
        'x-component': 'NumberPicker',
      },
      reason: {
        type: 'string',
        title: '处置原因',
        'x-component': 'TextArea',
      },
      handler: {
        type: 'string',
        title: '处理人',
        'x-component': 'Input',
      },
    },
  },
  PRODUCT_TYPE: {
    type: 'object',
    properties: {
      code: {
        type: 'string',
        title: '产品代码',
        'x-component': 'Input',
      },
      name: {
        type: 'string',
        title: '产品名称',
        'x-component': 'Input',
      },
      category: {
        type: 'string',
        title: '产品分类',
        'x-component': 'Select',
      },
      specifications: {
        type: 'string',
        title: '规格说明',
        'x-component': 'TextArea',
      },
    },
  },
  PRODUCTION_PLAN: {
    type: 'object',
    properties: {
      planCode: {
        type: 'string',
        title: '计划编号',
        'x-component': 'Input',
      },
      plannedDate: {
        type: 'string',
        title: '计划日期',
        'x-component': 'DatePicker',
      },
      targetQuantity: {
        type: 'number',
        title: '目标产量',
        'x-component': 'NumberPicker',
      },
      priority: {
        type: 'string',
        title: '优先级',
        enum: ['高', '中', '低'],
        'x-component': 'Select',
      },
    },
  },
  SCALE_DEVICE: {
    type: 'object',
    properties: {
      deviceName: {
        type: 'string',
        title: '设备名称',
        'x-component': 'Input',
      },
      deviceCode: {
        type: 'string',
        title: '设备编号',
        'x-component': 'Input',
      },
      ipAddress: {
        type: 'string',
        title: 'IP地址',
        'x-component': 'Input',
      },
      port: {
        type: 'number',
        title: '端口',
        'x-component': 'NumberPicker',
      },
      status: {
        type: 'string',
        title: '状态',
        enum: ['在线', '离线', '故障'],
        'x-component': 'Select',
      },
    },
  },
  SCALE_PROTOCOL: {
    type: 'object',
    properties: {
      protocolName: {
        type: 'string',
        title: '协议名称',
        'x-component': 'Input',
      },
      protocolVersion: {
        type: 'string',
        title: '协议版本',
        'x-component': 'Input',
      },
      dataFormat: {
        type: 'string',
        title: '数据格式',
        enum: ['ASCII', 'HEX', 'Binary'],
        'x-component': 'Select',
      },
      baudRate: {
        type: 'number',
        title: '波特率',
        'x-component': 'NumberPicker',
      },
    },
  },
  ISAPI_DEVICE: {
    type: 'object',
    properties: {
      deviceName: {
        type: 'string',
        title: '设备名称',
        'x-component': 'Input',
      },
      deviceIp: {
        type: 'string',
        title: 'IP地址',
        'x-component': 'Input',
      },
      username: {
        type: 'string',
        title: '用户名',
        'x-component': 'Input',
      },
      channelNumber: {
        type: 'number',
        title: '通道号',
        'x-component': 'NumberPicker',
      },
      status: {
        type: 'string',
        title: '状态',
        enum: ['在线', '离线', '故障'],
        'x-component': 'Select',
      },
    },
  },
  RAW_MATERIAL_TYPE: {
    type: 'object',
    properties: {
      name: {
        type: 'string',
        title: '原料名称',
        'x-component': 'Input',
      },
      unit: {
        type: 'string',
        title: '单位',
        'x-component': 'Input',
      },
      storageCondition: {
        type: 'string',
        title: '存储条件',
        'x-component': 'Input',
      },
    },
  },
  PURCHASE_ORDER: {
    type: 'object',
    properties: {
      supplierName: {
        type: 'string',
        title: '供应商',
        'x-component': 'Input',
      },
      orderDate: {
        type: 'string',
        title: '采购日期',
        'x-component': 'DatePicker',
      },
      remarks: {
        type: 'string',
        title: '备注',
        'x-component': 'TextArea',
      },
    },
  },
};

// 实体类型 Schema 配置
interface EntitySchemaConfig {
  entityType: EntityType;
  enabled: boolean;
  schema: object;
  customFields: number;
}

export function IndustryTemplateEditScreen() {
  const navigation = useNavigation<NavigationProp>();
  const route = useRoute<RouteProps>();
  const { t } = useTranslation('platform');
  const templateId = route.params?.templateId;
  const isEditMode = !!templateId;

  // 基础信息
  const [industryCode, setIndustryCode] = useState('');
  const [industryName, setIndustryName] = useState('');
  const [description, setDescription] = useState('');
  const [isDefault, setIsDefault] = useState(false);

  // 实体类型配置
  const [entityConfigs, setEntityConfigs] = useState<EntitySchemaConfig[]>([]);

  // UI 状态
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [presetMenuVisible, setPresetMenuVisible] = useState(false);
  const [schemaEditorVisible, setSchemaEditorVisible] = useState(false);
  const [currentEditEntity, setCurrentEditEntity] = useState<EntityType | null>(null);
  const [schemaText, setSchemaText] = useState('');
  const [visualEditorMode, setVisualEditorMode] = useState(true); // true = visual, false = raw JSON
  const [currentSchema, setCurrentSchema] = useState<FormilySchema | null>(null);

  // 验证状态
  const [errors, setErrors] = useState<Record<string, string>>({});

  // 初始化实体类型配置
  useEffect(() => {
    const initConfigs: EntitySchemaConfig[] = (Object.keys(ENTITY_TYPE_INFO) as EntityType[]).map(
      (entityType) => ({
        entityType,
        enabled: false,
        schema: DEFAULT_SCHEMAS[entityType],
        customFields: 0,
      })
    );
    setEntityConfigs(initConfigs);
  }, []);

  // 加载现有模板数据
  useEffect(() => {
    if (isEditMode && templateId) {
      loadTemplateData();
    }
  }, [templateId, isEditMode]);

  const loadTemplateData = async () => {
    if (!templateId) return;

    setLoading(true);
    try {
      const detail = await templatePackageApiClient.getTemplatePackageDetail(templateId);
      if (detail) {
        setIndustryCode(detail.industryCode);
        setIndustryName(detail.industryName);
        setDescription(detail.description || '');
        setIsDefault(detail.isDefault);

        // 解析 templatesJson
        if (detail.templatesJson) {
          try {
            const templates = JSON.parse(detail.templatesJson);
            const updatedConfigs = entityConfigs.map((config) => {
              if (templates[config.entityType]) {
                return {
                  ...config,
                  enabled: true,
                  schema: templates[config.entityType],
                  customFields: Object.keys(templates[config.entityType].properties || {}).length,
                };
              }
              return config;
            });
            setEntityConfigs(updatedConfigs);
          } catch (e) {
            editLogger.error('解析 templatesJson 失败', e);
          }
        }
      }
    } catch (error) {
      handleError(error, { title: t('industryTemplate.edit.loadFailed') });
    } finally {
      setLoading(false);
    }
  };

  // 选择预设行业
  const handleSelectPreset = (preset: typeof INDUSTRY_PRESETS[0]) => {
    setIndustryCode(preset.code);
    setIndustryName(preset.name);
    setPresetMenuVisible(false);

    // 根据行业预设启用相关实体类型
    if (preset.code !== 'custom') {
      const defaultEnabled: EntityType[] = ['QUALITY_CHECK', 'MATERIAL_BATCH', 'PROCESSING_BATCH', 'SHIPMENT'];
      setEntityConfigs((prev) =>
        prev.map((config) => ({
          ...config,
          enabled: defaultEnabled.includes(config.entityType),
        }))
      );
    }
  };

  // 切换实体类型启用状态
  const toggleEntityEnabled = (entityType: EntityType) => {
    setEntityConfigs((prev) =>
      prev.map((config) =>
        config.entityType === entityType ? { ...config, enabled: !config.enabled } : config
      )
    );
  };

  // 打开 Schema 编辑器
  const openSchemaEditor = (entityType: EntityType) => {
    const config = entityConfigs.find((c) => c.entityType === entityType);
    if (config) {
      setCurrentEditEntity(entityType);
      const schema = config.schema as FormilySchema;
      setCurrentSchema(schema);
      setSchemaText(JSON.stringify(schema, null, 2));
      setVisualEditorMode(true); // Default to visual mode
      setSchemaEditorVisible(true);
    }
  };

  // 处理可视化编辑器的 Schema 变更
  const handleVisualSchemaChange = (newSchema: FormilySchema) => {
    setCurrentSchema(newSchema);
    setSchemaText(JSON.stringify(newSchema, null, 2));
  };

  // 切换编辑模式
  const toggleEditorMode = () => {
    if (visualEditorMode) {
      // 切换到 JSON 模式: 同步当前 schema 到文本
      if (currentSchema) {
        setSchemaText(JSON.stringify(currentSchema, null, 2));
      }
    } else {
      // 切换到可视化模式: 验证并解析 JSON
      try {
        const parsed = JSON.parse(schemaText) as FormilySchema;
        setCurrentSchema(parsed);
      } catch (e) {
        Alert.alert(t('industryTemplate.edit.jsonFormatError'), t('industryTemplate.edit.jsonFormatErrorMessage'));
        return;
      }
    }
    setVisualEditorMode(!visualEditorMode);
  };

  // 保存 Schema 编辑
  const saveSchemaEdit = () => {
    if (!currentEditEntity) return;

    let schemaToSave: FormilySchema;

    if (visualEditorMode) {
      // 可视化模式: 使用 currentSchema
      if (!currentSchema) {
        Alert.alert(t('errors.loadFailed'), t('industryTemplate.edit.schemaDataLost'));
        return;
      }
      schemaToSave = currentSchema;
    } else {
      // JSON 模式: 解析文本
      try {
        schemaToSave = JSON.parse(schemaText) as FormilySchema;
      } catch (e) {
        Alert.alert(t('industryTemplate.edit.jsonFormatError'), t('industryTemplate.edit.jsonFormatCheckError'));
        return;
      }
    }

    setEntityConfigs((prev) =>
      prev.map((config) =>
        config.entityType === currentEditEntity
          ? {
              ...config,
              schema: schemaToSave,
              customFields: Object.keys(schemaToSave.properties || {}).length,
            }
          : config
      )
    );
    setSchemaEditorVisible(false);
    setCurrentEditEntity(null);
    setCurrentSchema(null);
  };

  // 验证表单
  const validate = (): boolean => {
    const newErrors: Record<string, string> = {};

    if (!industryCode.trim()) {
      newErrors.industryCode = t('industryTemplate.edit.industryCodeRequired');
    } else if (!/^[a-z_]+$/.test(industryCode)) {
      newErrors.industryCode = t('industryTemplate.edit.industryCodeFormat');
    }

    if (!industryName.trim()) {
      newErrors.industryName = t('industryTemplate.edit.industryNameRequired');
    }

    const enabledCount = entityConfigs.filter((c) => c.enabled).length;
    if (enabledCount === 0) {
      newErrors.entityTypes = t('industryTemplate.edit.entityTypesRequired');
    }

    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  // 保存模板
  const handleSave = async () => {
    if (!validate()) {
      Alert.alert(t('industryTemplate.edit.validationFailed'), t('industryTemplate.edit.validationFailedMessage'));
      return;
    }

    setSaving(true);
    try {
      // 构建 templatesJson
      const templates: Record<string, object> = {};
      entityConfigs
        .filter((c) => c.enabled)
        .forEach((c) => {
          templates[c.entityType] = c.schema;
        });

      if (isEditMode && templateId) {
        // 更新
        const request: UpdateTemplatePackageRequest = {
          industryName,
          description: description || undefined,
          templatesJson: JSON.stringify(templates),
          isDefault,
        };
        await templatePackageApiClient.updateTemplatePackage(templateId, request);
        Alert.alert(t('industryTemplate.edit.success'), t('industryTemplate.edit.updateSuccess'), [
          { text: t('aiQuota.confirmAction'), onPress: () => navigation.goBack() },
        ]);
      } else {
        // 创建
        const request: CreateTemplatePackageRequest = {
          industryCode,
          industryName,
          description: description || undefined,
          templatesJson: JSON.stringify(templates),
          isDefault,
        };
        await templatePackageApiClient.createTemplatePackage(request);
        Alert.alert(t('industryTemplate.edit.success'), t('industryTemplate.edit.createSuccess'), [
          { text: t('aiQuota.confirmAction'), onPress: () => navigation.goBack() },
        ]);
      }
    } catch (error) {
      handleError(error, { title: t('industryTemplate.edit.saveFailed') });
    } finally {
      setSaving(false);
    }
  };

  // 统计信息
  const enabledEntities = entityConfigs.filter((c) => c.enabled);
  const totalFields = enabledEntities.reduce((sum, c) => sum + c.customFields, 0);

  if (loading) {
    return (
      <SafeAreaView style={styles.container} edges={['top']}>
        <Appbar.Header>
          <Appbar.BackAction onPress={() => navigation.goBack()} />
          <Appbar.Content title={t('industryTemplate.edit.loading')} />
        </Appbar.Header>
        <View style={styles.loadingContainer}>
          <ActivityIndicator size="large" />
        </View>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      <Appbar.Header>
        <Appbar.BackAction onPress={() => navigation.goBack()} />
        <Appbar.Content title={isEditMode ? t('industryTemplate.edit.title') : t('industryTemplate.edit.createTitle')} />
        <Appbar.Action icon="content-save" onPress={handleSave} disabled={saving} />
      </Appbar.Header>

      <KeyboardAvoidingView
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
        style={styles.flex}
      >
        <ScrollView style={styles.content} contentContainerStyle={styles.scrollContent}>
          {/* 基础信息卡片 */}
          <Card style={styles.card}>
            <Card.Title
              title={t('industryTemplate.edit.basicInfo')}
              subtitle={t('industryTemplate.edit.basicInfoSubtitle')}
              left={(props) => <List.Icon {...props} icon="information" />}
            />
            <Card.Content>
              {/* 行业代码 */}
              <View style={styles.fieldRow}>
                <TextInput
                  mode="outlined"
                  label={t('industryTemplate.edit.industryCode')}
                  value={industryCode}
                  onChangeText={setIndustryCode}
                  placeholder={t('industryTemplate.edit.industryCodePlaceholder')}
                  disabled={isEditMode}
                  error={!!errors.industryCode}
                  style={styles.input}
                  right={
                    !isEditMode && (
                      <TextInput.Icon
                        icon="menu-down"
                        onPress={() => setPresetMenuVisible(true)}
                      />
                    )
                  }
                />
                {errors.industryCode && (
                  <HelperText type="error">{errors.industryCode}</HelperText>
                )}
              </View>

              {/* 预设选择菜单 */}
              <Menu
                visible={presetMenuVisible}
                onDismiss={() => setPresetMenuVisible(false)}
                anchor={{ x: 200, y: 200 }}
                style={styles.presetMenu}
              >
                {INDUSTRY_PRESETS.map((preset) => (
                  <Menu.Item
                    key={preset.code}
                    onPress={() => handleSelectPreset(preset)}
                    title={preset.name}
                    leadingIcon={preset.icon}
                  />
                ))}
              </Menu>

              {/* 行业名称 */}
              <View style={styles.fieldRow}>
                <TextInput
                  mode="outlined"
                  label={t('industryTemplate.edit.industryName')}
                  value={industryName}
                  onChangeText={setIndustryName}
                  placeholder={t('industryTemplate.edit.industryNamePlaceholder')}
                  error={!!errors.industryName}
                  style={styles.input}
                />
                {errors.industryName && (
                  <HelperText type="error">{errors.industryName}</HelperText>
                )}
              </View>

              {/* 描述 */}
              <TextInput
                mode="outlined"
                label={t('industryTemplate.edit.description')}
                value={description}
                onChangeText={setDescription}
                placeholder={t('industryTemplate.edit.descriptionPlaceholder')}
                multiline
                numberOfLines={3}
                style={styles.input}
              />

              {/* 设为推荐 */}
              <View style={styles.switchRow}>
                <View style={styles.switchLabel}>
                  <Text variant="bodyMedium">{t('industryTemplate.edit.setAsDefault')}</Text>
                  <Text variant="bodySmall" style={styles.switchHint}>
                    {t('industryTemplate.edit.defaultHint')}
                  </Text>
                </View>
                <Switch value={isDefault} onValueChange={setIsDefault} />
              </View>
            </Card.Content>
          </Card>

          {/* 实体类型配置卡片 */}
          <Card style={styles.card}>
            <Card.Title
              title={t('industryTemplate.edit.entityTypeConfig')}
              subtitle={t('industryTemplate.edit.entityTypeSubtitle', { count: enabledEntities.length, fields: totalFields })}
              left={(props) => <List.Icon {...props} icon="form-select" />}
            />
            <Card.Content>
              {errors.entityTypes && (
                <HelperText type="error" style={styles.entityError}>
                  {errors.entityTypes}
                </HelperText>
              )}

              {entityConfigs.map((config) => {
                const info = ENTITY_TYPE_INFO[config.entityType];
                return (
                  <View key={config.entityType} style={styles.entityItem}>
                    <View style={styles.entityHeader}>
                      <View style={styles.entityInfo}>
                        <List.Icon icon={info.icon} />
                        <View>
                          <Text variant="titleSmall">{info.name}</Text>
                          <Text variant="bodySmall" style={styles.entityDesc}>
                            {info.description}
                          </Text>
                        </View>
                      </View>
                      <Switch
                        value={config.enabled}
                        onValueChange={() => toggleEntityEnabled(config.entityType)}
                      />
                    </View>

                    {config.enabled && (
                      <View style={styles.entityActions}>
                        <Chip icon="code-json" style={styles.fieldCountChip}>
                          {t('industryTemplate.edit.fieldsCount', { count: config.customFields })}
                        </Chip>
                        <Button
                          mode="outlined"
                          compact
                          icon="pencil"
                          onPress={() => openSchemaEditor(config.entityType)}
                        >
                          {t('industryTemplate.edit.editSchema')}
                        </Button>
                      </View>
                    )}

                    <Divider style={styles.entityDivider} />
                  </View>
                );
              })}
            </Card.Content>
          </Card>

          {/* 预览统计 */}
          <Card style={styles.card}>
            <Card.Title
              title={t('industryTemplate.edit.templatePreview')}
              left={(props) => <List.Icon {...props} icon="eye" />}
            />
            <Card.Content>
              <View style={styles.previewStats}>
                <View style={styles.statItem}>
                  <Text variant="headlineMedium" style={styles.statValue}>
                    {enabledEntities.length}
                  </Text>
                  <Text variant="bodySmall">{t('industryTemplate.edit.entityTypes')}</Text>
                </View>
                <View style={styles.statItem}>
                  <Text variant="headlineMedium" style={styles.statValue}>
                    {totalFields}
                  </Text>
                  <Text variant="bodySmall">{t('industryTemplate.edit.totalFields')}</Text>
                </View>
                <View style={styles.statItem}>
                  <Text variant="headlineMedium" style={styles.statValue}>
                    v{isEditMode ? '2' : '1'}
                  </Text>
                  <Text variant="bodySmall">{t('industryTemplate.edit.version')}</Text>
                </View>
              </View>

              {enabledEntities.length > 0 && (
                <View style={styles.enabledList}>
                  <Text variant="labelMedium" style={styles.enabledLabel}>
                    {t('industryTemplate.edit.includedEntityTypes')}
                  </Text>
                  <View style={styles.chipRow}>
                    {enabledEntities.map((config) => (
                      <Chip
                        key={config.entityType}
                        icon={ENTITY_TYPE_INFO[config.entityType].icon}
                        style={styles.entityChip}
                      >
                        {ENTITY_TYPE_INFO[config.entityType].name}
                      </Chip>
                    ))}
                  </View>
                </View>
              )}
            </Card.Content>
          </Card>
        </ScrollView>
      </KeyboardAvoidingView>

      {/* 保存按钮 */}
      <FAB
        icon="content-save"
        label={saving ? t('industryTemplate.edit.saving') : t('industryTemplate.edit.saveTemplate')}
        style={styles.fab}
        onPress={handleSave}
        loading={saving}
        disabled={saving}
      />

      {/* Schema 编辑器 Modal */}
      <Portal>
        <Modal
          visible={schemaEditorVisible}
          onDismiss={() => setSchemaEditorVisible(false)}
          contentContainerStyle={styles.schemaModal}
        >
          <View style={styles.schemaModalHeader}>
            <View style={styles.schemaModalTitleRow}>
              <Text variant="titleLarge">
                {t('industryTemplate.edit.editSchemaTitle', { name: currentEditEntity && ENTITY_TYPE_INFO[currentEditEntity]?.name })}
              </Text>
              <View style={styles.editorModeToggle}>
                <Chip
                  mode={visualEditorMode ? 'flat' : 'outlined'}
                  selected={visualEditorMode}
                  onPress={() => visualEditorMode || toggleEditorMode()}
                  icon="palette"
                  compact
                >
                  {t('industryTemplate.edit.visualMode')}
                </Chip>
                <Chip
                  mode={!visualEditorMode ? 'flat' : 'outlined'}
                  selected={!visualEditorMode}
                  onPress={() => !visualEditorMode || toggleEditorMode()}
                  icon="code-json"
                  compact
                >
                  {t('industryTemplate.edit.jsonMode')}
                </Chip>
              </View>
            </View>
            <IconButton icon="close" onPress={() => setSchemaEditorVisible(false)} />
          </View>

          <Divider />

          {visualEditorMode ? (
            /* 可视化编辑器 */
            <View style={styles.visualEditorContainer}>
              {currentSchema && (
                <VisualSchemaEditor
                  schema={currentSchema}
                  onChange={handleVisualSchemaChange}
                />
              )}
            </View>
          ) : (
            /* JSON 编辑器 */
            <ScrollView style={styles.schemaEditorScroll}>
              <TextInput
                mode="outlined"
                value={schemaText}
                onChangeText={setSchemaText}
                multiline
                numberOfLines={20}
                style={styles.schemaInput}
                placeholder={t('industryTemplate.edit.inputJsonPlaceholder')}
              />
            </ScrollView>
          )}

          <Divider />

          <View style={styles.schemaModalFooter}>
            <Button mode="outlined" onPress={() => setSchemaEditorVisible(false)}>
              {t('industryTemplate.edit.cancel')}
            </Button>
            <Button mode="contained" onPress={saveSchemaEdit}>
              {t('industryTemplate.edit.save')}
            </Button>
          </View>
        </Modal>
      </Portal>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f5f5f5',
  },
  flex: {
    flex: 1,
  },
  content: {
    flex: 1,
  },
  scrollContent: {
    padding: 16,
    paddingBottom: 100,
  },
  loadingContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
  },
  card: {
    marginBottom: 16,
    borderRadius: 12,
  },
  fieldRow: {
    marginBottom: 12,
  },
  input: {
    backgroundColor: '#fff',
  },
  presetMenu: {
    marginTop: 60,
  },
  switchRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginTop: 16,
    paddingVertical: 8,
  },
  switchLabel: {
    flex: 1,
  },
  switchHint: {
    color: '#666',
    marginTop: 2,
  },
  entityError: {
    marginBottom: 8,
  },
  entityItem: {
    marginBottom: 8,
  },
  entityHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: 8,
  },
  entityInfo: {
    flexDirection: 'row',
    alignItems: 'center',
    flex: 1,
  },
  entityDesc: {
    color: '#666',
  },
  entityActions: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingLeft: 40,
    paddingVertical: 8,
  },
  fieldCountChip: {
    backgroundColor: '#E3F2FD',
  },
  entityDivider: {
    marginTop: 8,
  },
  previewStats: {
    flexDirection: 'row',
    justifyContent: 'space-around',
    paddingVertical: 16,
  },
  statItem: {
    alignItems: 'center',
  },
  statValue: {
    color: '#1976D2',
    fontWeight: 'bold',
  },
  enabledList: {
    marginTop: 16,
  },
  enabledLabel: {
    color: '#666',
    marginBottom: 8,
  },
  chipRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
  },
  entityChip: {
    marginBottom: 4,
  },
  fab: {
    position: 'absolute',
    right: 16,
    bottom: 32,
    backgroundColor: '#1976D2',
  },
  schemaModal: {
    backgroundColor: '#fff',
    margin: 16,
    borderRadius: 12,
    maxHeight: '90%',
    flex: 1,
  },
  schemaModalHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-start',
    padding: 16,
  },
  schemaModalTitleRow: {
    flex: 1,
  },
  editorModeToggle: {
    flexDirection: 'row',
    gap: 8,
    marginTop: 8,
  },
  visualEditorContainer: {
    flex: 1,
    minHeight: 400,
    maxHeight: 500,
  },
  schemaEditorScroll: {
    maxHeight: 400,
    padding: 16,
  },
  schemaInput: {
    backgroundColor: '#f5f5f5',
    fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
    fontSize: 12,
  },
  schemaModalFooter: {
    flexDirection: 'row',
    justifyContent: 'flex-end',
    gap: 12,
    padding: 16,
  },
});

export default IndustryTemplateEditScreen;
