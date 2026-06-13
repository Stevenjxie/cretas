/**
 * Schema 配置屏幕
 *
 * AI 对话创建字段 + 版本管理 + 模板预览
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-29
 */
import React, { useEffect, useState, useCallback } from 'react';
import {
  View,
  Text,
  StyleSheet,
  ScrollView,
  TouchableOpacity,
  TextInput,
  Alert,
  ActivityIndicator,
  RefreshControl,
  Modal,
  KeyboardAvoidingView,
  Platform,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { Icon } from 'react-native-paper';
import { useNavigation } from '@react-navigation/native';
import { useTranslation } from 'react-i18next';
import {
  formTemplateApiClient,
  FormTemplate,
  EntityType,
  TemplateStatistics,
  TemplateVersion,
} from '../../../services/api/formTemplateApiClient';
import formAssistantApiClient, {
  SchemaFieldDefinition,
  SchemaGenerateRequest,
  SchemaAnalyzeRequest,
  SchemaAnalyzeResponse,
  SchemaAnalyzeSuggestion,
  SchemaOptimizeRequest,
} from '../../../services/api/formAssistantApiClient';
import { DynamicForm, FormSchema } from '../../../formily/core/DynamicForm';
import { aiService } from '../../../services/ai';

// 实体类型代码列表（用于映射翻译key）
const ENTITY_TYPE_CODES: EntityType[] = [
  'QUALITY_CHECK',
  'MATERIAL_BATCH',
  'PROCESSING_BATCH',
  'SHIPMENT',
  'EQUIPMENT',
  'DISPOSAL_RECORD',
  'RAW_MATERIAL_TYPE',
  'PURCHASE_ORDER',
];

// 实体类型图标映射
const ENTITY_TYPE_ICONS: Record<EntityType, string> = {
  QUALITY_CHECK: 'clipboard-check-outline',
  MATERIAL_BATCH: 'package-variant',
  PROCESSING_BATCH: 'cog-outline',
  SHIPMENT: 'truck-delivery-outline',
  EQUIPMENT: 'wrench-outline',
  DISPOSAL_RECORD: 'delete-outline',
  PRODUCT_TYPE: 'barcode',
  PRODUCTION_PLAN: 'calendar-clock',
  SCALE_DEVICE: 'scale',
  SCALE_PROTOCOL: 'file-document-outline',
  ISAPI_DEVICE: 'video',
  RAW_MATERIAL_TYPE: 'package-variant-closed',
  PURCHASE_ORDER: 'file-document-edit',
};

// 实体类型颜色映射
const ENTITY_TYPE_COLORS: Record<EntityType, string> = {
  QUALITY_CHECK: '#1890ff',
  MATERIAL_BATCH: '#52c41a',
  PROCESSING_BATCH: '#fa8c16',
  SHIPMENT: '#eb2f96',
  EQUIPMENT: '#722ed1',
  DISPOSAL_RECORD: '#f5222d',
  PRODUCT_TYPE: '#2196f3',
  PRODUCTION_PLAN: '#ff9800',
  SCALE_DEVICE: '#607d8b',
  SCALE_PROTOCOL: '#795548',
  ISAPI_DEVICE: '#9c27b0',
  RAW_MATERIAL_TYPE: '#00897b',
  PURCHASE_ORDER: '#5c6bc0',
};

interface FieldPreviewProps {
  field: SchemaFieldDefinition;
  onRemove: () => void;
}

function FieldPreview({ field, onRemove }: FieldPreviewProps) {
  return (
    <View style={styles.fieldPreviewItem}>
      <View style={styles.fieldPreviewContent}>
        <Text style={styles.fieldPreviewTitle}>{field.title}</Text>
        <Text style={styles.fieldPreviewName}>{field.name}</Text>
        <View style={styles.fieldPreviewMeta}>
          <Text style={styles.fieldPreviewType}>{field.type}</Text>
          <Text style={styles.fieldPreviewComponent}>{field['x-component']}</Text>
        </View>
      </View>
      <TouchableOpacity onPress={onRemove} style={styles.fieldPreviewRemove}>
        <Icon source="close" size={20} color="#ff4d4f" />
      </TouchableOpacity>
    </View>
  );
}

// 获取实体类型翻译key
const getEntityTypeKey = (entityType: EntityType): string => {
  const keys: Record<EntityType, string> = {
    QUALITY_CHECK: 'schemaConfig.entityTypes.qualityCheck',
    MATERIAL_BATCH: 'schemaConfig.entityTypes.materialBatch',
    PROCESSING_BATCH: 'schemaConfig.entityTypes.processingBatch',
    SHIPMENT: 'schemaConfig.entityTypes.shipment',
    EQUIPMENT: 'schemaConfig.entityTypes.equipment',
    DISPOSAL_RECORD: 'schemaConfig.entityTypes.disposalRecord',
    PRODUCT_TYPE: 'schemaConfig.entityTypes.productType',
    PRODUCTION_PLAN: 'schemaConfig.entityTypes.productionPlan',
    SCALE_DEVICE: 'schemaConfig.entityTypes.scaleDevice',
    SCALE_PROTOCOL: 'schemaConfig.entityTypes.scaleProtocol',
    ISAPI_DEVICE: 'schemaConfig.entityTypes.isapiDevice',
    RAW_MATERIAL_TYPE: 'schemaConfig.entityTypes.rawMaterialType',
    PURCHASE_ORDER: 'schemaConfig.entityTypes.purchaseOrder',
  };
  return keys[entityType] || 'schemaConfig.entityTypes.unknown';
};

export function SchemaConfigScreen() {
  const navigation = useNavigation();
  const { t } = useTranslation('home');

  // 状态
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [statistics, setStatistics] = useState<TemplateStatistics | null>(null);
  const [templates, setTemplates] = useState<FormTemplate[]>([]);

  // AI 生成对话
  const [dialogVisible, setDialogVisible] = useState(false);
  const [selectedEntityType, setSelectedEntityType] = useState<EntityType | null>(null);
  const [userInput, setUserInput] = useState('');
  const [generating, setGenerating] = useState(false);
  const [generatedFields, setGeneratedFields] = useState<SchemaFieldDefinition[]>([]);
  const [saving, setSaving] = useState(false);

  // AI 拒绝状态 - 当AI认为字段与实体类型不相关时
  const [rejectionState, setRejectionState] = useState<{
    rejected: boolean;
    reason: string;
    suggestedEntityType?: string;
  } | null>(null);
  const [userJustification, setUserJustification] = useState('');

  // 版本历史
  const [versionModalVisible, setVersionModalVisible] = useState(false);
  const [selectedTemplate, setSelectedTemplate] = useState<FormTemplate | null>(null);
  const [versions, setVersions] = useState<TemplateVersion[]>([]);
  const [loadingVersions, setLoadingVersions] = useState(false);

  // 字段预览
  const [fieldsModalVisible, setFieldsModalVisible] = useState(false);
  const [previewEntityType, setPreviewEntityType] = useState<EntityType | null>(null);
  const [currentSchemaFields, setCurrentSchemaFields] = useState<Array<{
    name: string;
    title: string;
    type: string;
    component: string;
    required?: boolean;
  }>>([]);

  // 表单预览 Tab 状态
  const [previewTab, setPreviewTab] = useState<'fields' | 'form' | 'analyze'>('fields');
  const [currentSchema, setCurrentSchema] = useState<FormSchema | null>(null);

  // AI 分析状态
  const [analyzing, setAnalyzing] = useState(false);
  // Note: aiService.analyzeSchema returns AIResult<T> where data doesn't include 'success'
  const [analyzeResult, setAnalyzeResult] = useState<Omit<SchemaAnalyzeResponse, 'success' | 'message'> | null>(null);
  const [optimizing, setOptimizing] = useState(false);
  const [selectedSuggestions, setSelectedSuggestions] = useState<string[]>([]);

  // 字段编辑状态
  const [editingFieldIndex, setEditingFieldIndex] = useState<number | null>(null);
  const [editFieldTitle, setEditFieldTitle] = useState('');
  const [editFieldRequired, setEditFieldRequired] = useState(false);

  // AI 修改字段状态
  const [aiModifyModalVisible, setAiModifyModalVisible] = useState(false);
  const [aiModifyInput, setAiModifyInput] = useState('');
  const [aiModifying, setAiModifying] = useState(false);

  // 加载数据
  const loadData = useCallback(async () => {
    try {
      const [stats, templateList] = await Promise.all([
        formTemplateApiClient.getStatistics(),
        formTemplateApiClient.getTemplateList({ page: 1, size: 20 }),
      ]);
      setStatistics(stats);
      setTemplates(templateList.content || []);
    } catch (error) {
      console.error('加载数据失败:', error);
      Alert.alert(t('schemaConfig.loadFailed'), t('schemaConfig.loadFailedMessage'));
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  useEffect(() => {
    loadData();
  }, [loadData]);

  const onRefresh = useCallback(() => {
    setRefreshing(true);
    loadData();
  }, [loadData]);

  // 打开 AI 对话
  const openAIDialog = (entityType: EntityType) => {
    setSelectedEntityType(entityType);
    setUserInput('');
    setGeneratedFields([]);
    setRejectionState(null);
    setUserJustification('');
    setDialogVisible(true);
  };

  // AI 生成字段
  const handleGenerateFields = async (withJustification = false) => {
    if (!userInput.trim() || !selectedEntityType) {
      Alert.alert(t('common.tip'), t('schemaConfig.enterFieldDescription'));
      return;
    }

    setGenerating(true);
    // 如果是重试且有理由，先清除拒绝状态
    if (withJustification) {
      setRejectionState(null);
    }

    try {
      // 获取现有字段名
      const template = templates.find((tp) => tp.entityType === selectedEntityType);
      let existingFields: string[] = [];
      if (template?.schemaJson) {
        try {
          const schema = JSON.parse(template.schemaJson);
          existingFields = Object.keys(schema.properties || {});
        } catch {
          // 忽略解析错误
        }
      }

      const request: SchemaGenerateRequest = {
        userInput: userInput.trim(),
        entityType: selectedEntityType,
        existingFields,
        // 如果带有用户解释，传递给后端
        userJustification: withJustification ? userJustification.trim() : undefined,
      };

      const response = await formAssistantApiClient.generateSchema(request);

      // 检查 AI 相关性判断
      if (response.relevant === false) {
        // AI 认为字段与当前实体类型不相关
        setRejectionState({
          rejected: true,
          reason: response.rejectionReason || t('schemaConfig.rejection.defaultReason'),
          suggestedEntityType: response.suggestedEntityType,
        });
        setGeneratedFields([]);
        return;
      }

      // 清除可能存在的拒绝状态
      setRejectionState(null);
      setUserJustification('');

      if (response.success && response.fields.length > 0) {
        setGeneratedFields(response.fields);
      } else {
        Alert.alert(t('schemaConfig.generateFailed'), response.message || t('schemaConfig.aiNoValidFields'));
      }
    } catch (error) {
      console.error('AI生成失败:', error);
      Alert.alert(t('schemaConfig.generateFailed'), t('schemaConfig.aiServiceError'));
    } finally {
      setGenerating(false);
    }
  };

  // 带解释重试
  const handleRetryWithJustification = () => {
    if (!userJustification.trim()) {
      Alert.alert(t('common.tip'), t('schemaConfig.rejection.enterJustification'));
      return;
    }
    handleGenerateFields(true);
  };

  // 移除预览字段
  const removePreviewField = (index: number) => {
    setGeneratedFields((prev) => prev.filter((_, i) => i !== index));
  };

  // 保存生成的字段
  const handleSaveFields = async () => {
    if (generatedFields.length === 0 || !selectedEntityType) {
      Alert.alert(t('common.tip'), t('schemaConfig.noFieldsToSave'));
      return;
    }

    setSaving(true);
    try {
      // 获取现有模板
      const template = templates.find((t) => t.entityType === selectedEntityType);
      let existingSchema: { type: string; properties: Record<string, unknown> } = {
        type: 'object',
        properties: {},
      };

      if (template?.schemaJson) {
        try {
          existingSchema = JSON.parse(template.schemaJson);
        } catch {
          // 使用默认空schema
        }
      }

      // 合并新字段
      for (const field of generatedFields) {
        existingSchema.properties[field.name] = {
          type: field.type,
          title: field.title,
          'x-component': field['x-component'],
          'x-component-props': field['x-component-props'],
          'x-decorator': field['x-decorator'] || 'FormItem',
          'x-validator': field['x-validator'],
          'x-reactions': field['x-reactions'],
          description: field.description,
          required: field.required,
          enum: field.enum,
        };
      }

      // 保存到后端
      await formTemplateApiClient.createOrUpdateTemplate(selectedEntityType, {
        name: t(getEntityTypeKey(selectedEntityType)) + ' (AI)',
        schemaJson: JSON.stringify(existingSchema),
        description: `AI: ${generatedFields.map((f) => f.title).join(', ')}`,
      });

      Alert.alert(t('schemaConfig.saveSuccess'), t('schemaConfig.fieldsAdded', { count: generatedFields.length, type: t(getEntityTypeKey(selectedEntityType)) }));
      setDialogVisible(false);
      setGeneratedFields([]);
      setUserInput('');
      loadData();
    } catch (error) {
      console.error('保存失败:', error);
      Alert.alert(t('schemaConfig.saveFailed'), t('schemaConfig.saveFailedMessage'));
    } finally {
      setSaving(false);
    }
  };

  // 查看版本历史
  const openVersionHistory = async (template: FormTemplate) => {
    setSelectedTemplate(template);
    setVersionModalVisible(true);
    setLoadingVersions(true);

    try {
      const history = await formTemplateApiClient.getVersionHistory(template.id);
      setVersions(history.content || []);
    } catch (error) {
      console.error('加载版本历史失败:', error);
      Alert.alert(t('schemaConfig.loadFailed'), t('schemaConfig.versionHistoryLoadFailed'));
    } finally {
      setLoadingVersions(false);
    }
  };

  // 回滚版本
  const handleRollback = async (version: number) => {
    if (!selectedTemplate) return;

    Alert.alert(
      t('schemaConfig.confirmRollback'),
      t('schemaConfig.rollbackConfirmMessage', { version }),
      [
        { text: t('common.cancel'), style: 'cancel' },
        {
          text: t('schemaConfig.confirmRollbackBtn'),
          style: 'destructive',
          onPress: async () => {
            try {
              await formTemplateApiClient.rollbackToVersion(
                selectedTemplate.id,
                version,
                t('schemaConfig.userManualRollback')
              );
              Alert.alert(t('schemaConfig.rollbackSuccess'), t('schemaConfig.rolledBackTo', { version }));
              setVersionModalVisible(false);
              loadData();
            } catch (error) {
              console.error('回滚失败:', error);
              Alert.alert(t('schemaConfig.rollbackFailed'), t('schemaConfig.rollbackFailedMessage'));
            }
          },
        },
      ]
    );
  };

  // 查看当前字段
  const openFieldsPreview = (entityType: EntityType) => {
    const template = templates.find((tp) => tp.entityType === entityType);
    if (!template?.schemaJson) {
      Alert.alert(t('common.tip'), t('schemaConfig.noFieldsConfigured'));
      return;
    }

    try {
      const schema = JSON.parse(template.schemaJson);
      const properties = schema.properties || {};
      const fields = Object.entries(properties).map(([name, config]: [string, any]) => ({
        name,
        title: config.title || name,
        type: config.type || 'string',
        component: config['x-component'] || 'Input',
        required: config.required,
      }));

      setCurrentSchemaFields(fields);
      setCurrentSchema(schema as FormSchema);
      setPreviewEntityType(entityType);
      setPreviewTab('fields'); // 默认显示字段列表
      setFieldsModalVisible(true);
    } catch (error) {
      console.error('解析 Schema 失败:', error);
      Alert.alert(t('schemaConfig.loadFailed'), t('schemaConfig.parseSchemaFailed'));
    }
  };

  // 生成示例数据用于表单预览
  const generateSampleData = (schema: FormSchema): Record<string, any> => {
    const data: Record<string, any> = {};
    const properties = schema.properties || {};

    Object.entries(properties).forEach(([key, config]: [string, any]) => {
      const fieldType = config.type || 'string';
      const component = config['x-component'] || 'Input';
      const title = config.title || key;

      // 根据组件类型生成示例数据
      switch (component) {
        case 'DatePicker':
          data[key] = new Date().toISOString().split('T')[0];
          break;
        case 'NumberInput':
        case 'InputNumber':
          data[key] = 100;
          break;
        case 'Switch':
          data[key] = true;
          break;
        case 'Select':
          // 如果有 enum，取第一个值
          if (config.enum && config.enum.length > 0) {
            data[key] = config.enum[0];
          } else {
            data[key] = t('schemaConfig.preview.sampleOption');
          }
          break;
        case 'TextArea':
          data[key] = t('schemaConfig.preview.sampleText', { field: title });
          break;
        default:
          // 默认 string 类型
          if (fieldType === 'number') {
            data[key] = 100;
          } else if (fieldType === 'boolean') {
            data[key] = true;
          } else {
            data[key] = title || t('schemaConfig.preview.sampleValue');
          }
      }
    });

    return data;
  };

  // 获取优先级颜色
  const getPriorityColor = (priority: string): string => {
    switch (priority) {
      case 'high':
        return '#f5222d';
      case 'medium':
        return '#fa8c16';
      case 'low':
        return '#52c41a';
      default:
        return '#666';
    }
  };

  // 切换建议选择
  const toggleSuggestionSelection = (suggestionId: string) => {
    setSelectedSuggestions((prev) =>
      prev.includes(suggestionId)
        ? prev.filter((id) => id !== suggestionId)
        : [...prev, suggestionId]
    );
  };

  // AI 分析 Schema
  const handleAnalyzeSchema = async () => {
    if (!previewEntityType || !currentSchema) {
      Alert.alert(t('common.tip'), t('schemaConfig.analyze.noSchema'));
      return;
    }

    setAnalyzing(true);
    setAnalyzeResult(null);
    setSelectedSuggestions([]);

    try {
      const aiResult = await aiService.analyzeSchema({
        entityType: previewEntityType as 'MATERIAL_BATCH' | 'PRODUCT_TYPE' | 'QUALITY_CHECK',
        schemaJson: JSON.stringify(currentSchema),
      });
      if (aiResult.success && aiResult.data) {
        // Cast to match the state type (aiService returns data without 'success' and 'message')
        setAnalyzeResult(aiResult.data as Omit<SchemaAnalyzeResponse, 'success' | 'message'>);
      } else {
        Alert.alert(t('schemaConfig.analyze.failed'), aiResult.errorMessage || t('schemaConfig.analyze.failedMessage'));
      }
    } catch (error) {
      console.error('AI 分析失败:', error);
      Alert.alert(t('schemaConfig.analyze.failed'), t('schemaConfig.analyze.failedMessage'));
    } finally {
      setAnalyzing(false);
    }
  };

  // 应用优化建议
  const handleApplyOptimizations = async () => {
    if (!previewEntityType || !currentSchema || selectedSuggestions.length === 0) {
      return;
    }

    setOptimizing(true);

    try {
      const aiResult = await aiService.optimizeSchema({
        entityType: previewEntityType as 'MATERIAL_BATCH' | 'PRODUCT_TYPE' | 'QUALITY_CHECK',
        schemaJson: JSON.stringify(currentSchema),
        suggestionIds: selectedSuggestions,
      });

      if (aiResult.success && aiResult.data.optimizedSchema) {
        // 保存优化后的 Schema
        const optimizedSchema = JSON.parse(aiResult.data.optimizedSchema);

        await formTemplateApiClient.createOrUpdateTemplate(previewEntityType, {
          name: t(getEntityTypeKey(previewEntityType)) + ' (AI优化)',
          schemaJson: aiResult.data.optimizedSchema,
          description: `AI优化: 应用了 ${selectedSuggestions.length} 条建议`,
        });

        Alert.alert(
          t('schemaConfig.analyze.optimizeSuccess'),
          t('schemaConfig.analyze.optimizeSuccessMessage', { count: selectedSuggestions.length })
        );

        // 更新当前 Schema 并重置状态
        setCurrentSchema(optimizedSchema);
        setSelectedSuggestions([]);
        setAnalyzeResult(null);
        loadData();

        // 重新解析字段列表
        const properties = optimizedSchema.properties || {};
        const fields = Object.entries(properties).map(([name, config]: [string, any]) => ({
          name,
          title: config.title || name,
          type: config.type || 'string',
          component: config['x-component'] || 'Input',
          required: config.required,
        }));
        setCurrentSchemaFields(fields);
      } else {
        Alert.alert(t('schemaConfig.analyze.optimizeFailed'), aiResult.errorMessage || t('schemaConfig.analyze.optimizeFailedMessage'));
      }
    } catch (error) {
      console.error('应用优化失败:', error);
      Alert.alert(t('schemaConfig.analyze.optimizeFailed'), t('schemaConfig.analyze.optimizeFailedMessage'));
    } finally {
      setOptimizing(false);
    }
  };

  // 打开字段编辑 Modal
  const openFieldEditModal = (index: number, field: { name: string; title: string; required?: boolean }) => {
    setEditingFieldIndex(index);
    setEditFieldTitle(field.title);
    setEditFieldRequired(field.required || false);
  };

  // 保存字段编辑
  const handleSaveFieldEdit = async () => {
    if (editingFieldIndex === null || !previewEntityType || !currentSchema) {
      return;
    }

    const fieldName = currentSchemaFields[editingFieldIndex]?.name;
    if (!fieldName) return;

    try {
      const updatedSchema = { ...currentSchema };
      if (updatedSchema.properties && updatedSchema.properties[fieldName]) {
        updatedSchema.properties[fieldName].title = editFieldTitle;
        updatedSchema.properties[fieldName].required = editFieldRequired;
      }

      await formTemplateApiClient.createOrUpdateTemplate(previewEntityType, {
        name: t(getEntityTypeKey(previewEntityType)),
        schemaJson: JSON.stringify(updatedSchema),
        description: `手动编辑字段: ${fieldName}`,
      });

      Alert.alert(t('common.success'), t('schemaConfig.editField.saveSuccess'));

      // 更新本地状态
      setCurrentSchema(updatedSchema);
      setCurrentSchemaFields((prev) =>
        prev.map((f, i) =>
          i === editingFieldIndex
            ? { ...f, title: editFieldTitle, required: editFieldRequired }
            : f
        )
      );
      setEditingFieldIndex(null);
      loadData();
    } catch (error) {
      console.error('保存字段编辑失败:', error);
      Alert.alert(t('schemaConfig.editField.saveFailed'), t('schemaConfig.editField.saveFailedMessage'));
    }
  };

  // 删除字段
  const handleDeleteField = async () => {
    if (editingFieldIndex === null || !previewEntityType || !currentSchema) {
      return;
    }

    const fieldName = currentSchemaFields[editingFieldIndex]?.name;
    if (!fieldName) return;

    Alert.alert(
      t('schemaConfig.editField.confirmDelete'),
      t('schemaConfig.editField.confirmDeleteMessage', { field: fieldName }),
      [
        { text: t('common.cancel'), style: 'cancel' },
        {
          text: t('schemaConfig.editField.delete'),
          style: 'destructive',
          onPress: async () => {
            try {
              const updatedSchema = { ...currentSchema };
              if (updatedSchema.properties) {
                delete updatedSchema.properties[fieldName];
              }

              await formTemplateApiClient.createOrUpdateTemplate(previewEntityType, {
                name: t(getEntityTypeKey(previewEntityType)),
                schemaJson: JSON.stringify(updatedSchema),
                description: `删除字段: ${fieldName}`,
              });

              Alert.alert(t('common.success'), t('schemaConfig.editField.deleteSuccess'));

              // 更新本地状态
              setCurrentSchema(updatedSchema);
              setCurrentSchemaFields((prev) => prev.filter((_, i) => i !== editingFieldIndex));
              setEditingFieldIndex(null);
              loadData();
            } catch (error) {
              console.error('删除字段失败:', error);
              Alert.alert(t('schemaConfig.editField.deleteFailed'), t('schemaConfig.editField.deleteFailedMessage'));
            }
          },
        },
      ]
    );
  };

  // 打开 AI 修改对话框
  const openAIModifyDialog = (entityType: EntityType) => {
    setAiModifyInput('');
    setAiModifyModalVisible(true);
  };

  // AI 修改字段
  const handleAIModifyField = async () => {
    if (!aiModifyInput.trim() || !previewEntityType || !currentSchema) {
      Alert.alert(t('common.tip'), t('schemaConfig.aiModify.enterDescription'));
      return;
    }

    setAiModifying(true);

    try {
      // 使用 generateSchema API，但传入修改指令
      const request: SchemaGenerateRequest = {
        userInput: aiModifyInput.trim(),
        entityType: previewEntityType,
        existingFields: Object.keys(currentSchema.properties || {}),
        modifyMode: true, // 标记为修改模式
      };

      const response = await formAssistantApiClient.generateSchema(request);

      if (response.success && response.fields.length > 0) {
        // 合并修改后的字段到现有 Schema
        const updatedSchema = { ...currentSchema };
        for (const field of response.fields) {
          updatedSchema.properties = updatedSchema.properties || {};
          updatedSchema.properties[field.name] = {
            type: field.type,
            title: field.title,
            'x-component': field['x-component'],
            'x-component-props': field['x-component-props'],
            'x-decorator': field['x-decorator'] || 'FormItem',
            'x-validator': field['x-validator'],
            description: field.description,
            required: field.required,
            enum: field.enum,
          };
        }

        await formTemplateApiClient.createOrUpdateTemplate(previewEntityType, {
          name: t(getEntityTypeKey(previewEntityType)) + ' (AI修改)',
          schemaJson: JSON.stringify(updatedSchema),
          description: `AI修改: ${aiModifyInput.trim()}`,
        });

        Alert.alert(
          t('schemaConfig.aiModify.success'),
          t('schemaConfig.aiModify.successMessage', { count: response.fields.length })
        );

        // 更新本地状态
        setCurrentSchema(updatedSchema);
        const properties = updatedSchema.properties || {};
        const fields = Object.entries(properties).map(([name, config]: [string, any]) => ({
          name,
          title: config.title || name,
          type: config.type || 'string',
          component: config['x-component'] || 'Input',
          required: config.required,
        }));
        setCurrentSchemaFields(fields);
        setAiModifyModalVisible(false);
        setAiModifyInput('');
        loadData();
      } else {
        Alert.alert(t('schemaConfig.aiModify.failed'), response.message || t('schemaConfig.aiModify.failedMessage'));
      }
    } catch (error) {
      console.error('AI 修改失败:', error);
      Alert.alert(t('schemaConfig.aiModify.failed'), t('schemaConfig.aiModify.failedMessage'));
    } finally {
      setAiModifying(false);
    }
  };

  // 渲染实体类型卡片
  const renderEntityCard = (entityType: EntityType) => {
    const template = templates.find((tp) => tp.entityType === entityType);
    const isConfigured = statistics?.configuredEntityTypes.includes(entityType);
    const color = ENTITY_TYPE_COLORS[entityType] || '#666666'; // 默认颜色防止undefined

    return (
      <View key={entityType} style={styles.entityCard}>
        <View style={styles.entityHeader}>
          <View style={[styles.entityIcon, { backgroundColor: color + '20' }]}>
            <Icon source={ENTITY_TYPE_ICONS[entityType]} size={24} color={color} />
          </View>
          <View style={styles.entityInfo}>
            <Text style={styles.entityTitle}>{t(getEntityTypeKey(entityType))}</Text>
            <Text style={styles.entityStatus}>
              {isConfigured ? t('schemaConfig.version', { version: template?.version || 1 }) : t('schemaConfig.usingDefault')}
            </Text>
          </View>
          {isConfigured && (
            <View style={styles.configuredBadge}>
              <Icon source="check-circle" size={16} color="#52c41a" />
            </View>
          )}
        </View>

        <View style={styles.entityActions}>
          <TouchableOpacity
            style={[styles.entityButton, { backgroundColor: color + '15' }]}
            onPress={() => openAIDialog(entityType)}
          >
            <Icon source="robot" size={18} color={color} />
            <Text style={[styles.entityButtonText, { color }]}>{t('schemaConfig.aiAddFields')}</Text>
          </TouchableOpacity>

          <TouchableOpacity
            style={[styles.entityButton, { backgroundColor: isConfigured ? '#e6f7ff' : '#f5f5f5' }]}
            onPress={() => openFieldsPreview(entityType)}
          >
            <Icon source="format-list-bulleted" size={18} color={isConfigured ? '#1890ff' : '#999'} />
            <Text style={[styles.entityButtonText, { color: isConfigured ? '#1890ff' : '#999' }]}>{t('schemaConfig.viewFields')}</Text>
          </TouchableOpacity>
        </View>

        {template && (
          <TouchableOpacity
            style={[styles.entityButton, styles.historyButton]}
            onPress={() => openVersionHistory(template)}
          >
            <Icon source="history" size={18} color="#666" />
            <Text style={[styles.entityButtonText, { color: '#666' }]}>{t('schemaConfig.versionHistory')}</Text>
          </TouchableOpacity>
        )}
      </View>
    );
  };

  if (loading) {
    return (
      <SafeAreaView style={styles.container}>
        <View style={styles.loadingContainer}>
          <ActivityIndicator size="large" color="#667eea" />
          <Text style={styles.loadingText}>{t('common.loading')}</Text>
        </View>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={styles.container}>
      <ScrollView
        style={styles.scrollView}
        refreshControl={
          <RefreshControl refreshing={refreshing} onRefresh={onRefresh} colors={['#667eea']} />
        }
      >
        {/* Header */}
        <View style={styles.header}>
          <Text style={styles.title}>{t('schemaConfig.title')}</Text>
          <Text style={styles.subtitle}>{t('schemaConfig.subtitle')}</Text>
        </View>

        {/* 统计卡片 */}
        {statistics && (
          <View style={styles.statsCard}>
            <View style={styles.statsRow}>
              <View style={styles.statItem}>
                <Text style={styles.statValue}>{statistics.totalCount}</Text>
                <Text style={styles.statLabel}>{t('schemaConfig.stats.configured')}</Text>
              </View>
              <View style={styles.statItem}>
                <Text style={styles.statValue}>{statistics.aiGeneratedCount}</Text>
                <Text style={styles.statLabel}>{t('schemaConfig.stats.aiGenerated')}</Text>
              </View>
              <View style={styles.statItem}>
                <Text style={styles.statValue}>{Math.round(statistics.coverageRate * 100)}%</Text>
                <Text style={styles.statLabel}>{t('schemaConfig.stats.coverage')}</Text>
              </View>
            </View>
          </View>
        )}

        {/* 实体类型列表 */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>{t('schemaConfig.formTypes')}</Text>
          {statistics?.supportedEntityTypes.map((entityType) =>
            renderEntityCard(entityType as EntityType)
          )}
        </View>

        <View style={{ height: 32 }} />
      </ScrollView>

      {/* AI 生成对话框 */}
      <Modal visible={dialogVisible} animationType="slide" transparent>
        <KeyboardAvoidingView
          style={styles.modalOverlay}
          behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
        >
          <View style={styles.modalContent}>
            <View style={styles.modalHeader}>
              <Text style={styles.modalTitle}>
                {t('schemaConfig.aiAddFieldsFor', { entityType: selectedEntityType ? t(getEntityTypeKey(selectedEntityType)) : '' })}
              </Text>
              <TouchableOpacity onPress={() => setDialogVisible(false)}>
                <Icon source="close" size={24} color="#666" />
              </TouchableOpacity>
            </View>

            {/* 输入区域 */}
            <View style={styles.inputSection}>
              <Text style={styles.inputLabel}>{t('schemaConfig.describeFields')}</Text>
              <TextInput
                style={styles.textInput}
                placeholder={t('schemaConfig.fieldDescriptionPlaceholder')}
                placeholderTextColor="#999"
                value={userInput}
                onChangeText={setUserInput}
                multiline
                numberOfLines={3}
                textAlignVertical="top"
              />
              <TouchableOpacity
                style={[styles.generateButton, generating && styles.buttonDisabled]}
                onPress={() => handleGenerateFields(false)}
                disabled={generating}
              >
                {generating ? (
                  <ActivityIndicator size="small" color="#fff" />
                ) : (
                  <>
                    <Icon source="robot" size={20} color="#fff" />
                    <Text style={styles.generateButtonText}>{t('schemaConfig.aiGenerate')}</Text>
                  </>
                )}
              </TouchableOpacity>
            </View>

            {/* AI 拒绝区域 - 当字段与实体类型不相关时显示 */}
            {rejectionState?.rejected && (
              <View style={styles.rejectionSection}>
                <View style={styles.rejectionHeader}>
                  <Icon source="alert-circle" size={24} color="#fa8c16" />
                  <Text style={styles.rejectionTitle}>{t('schemaConfig.rejection.title')}</Text>
                </View>
                <Text style={styles.rejectionReason}>{rejectionState.reason}</Text>
                {rejectionState.suggestedEntityType && (
                  <View style={styles.suggestionBox}>
                    <Icon source="lightbulb-outline" size={16} color="#667eea" />
                    <Text style={styles.suggestionText}>
                      {t('schemaConfig.rejection.suggestion', {
                        entityType: t(getEntityTypeKey(rejectionState.suggestedEntityType as EntityType)),
                      })}
                    </Text>
                  </View>
                )}
                <View style={styles.justificationSection}>
                  <Text style={styles.justificationLabel}>{t('schemaConfig.rejection.justificationLabel')}</Text>
                  <TextInput
                    style={styles.justificationInput}
                    placeholder={t('schemaConfig.rejection.justificationPlaceholder')}
                    placeholderTextColor="#999"
                    value={userJustification}
                    onChangeText={setUserJustification}
                    multiline
                    numberOfLines={3}
                    textAlignVertical="top"
                  />
                  <TouchableOpacity
                    style={[styles.retryButton, generating && styles.buttonDisabled]}
                    onPress={handleRetryWithJustification}
                    disabled={generating}
                  >
                    {generating ? (
                      <ActivityIndicator size="small" color="#fff" />
                    ) : (
                      <>
                        <Icon source="refresh" size={20} color="#fff" />
                        <Text style={styles.retryButtonText}>{t('schemaConfig.rejection.retryWithExplanation')}</Text>
                      </>
                    )}
                  </TouchableOpacity>
                </View>
              </View>
            )}

            {/* 预览区域 */}
            {generatedFields.length > 0 && (
              <View style={styles.previewSection}>
                <Text style={styles.previewTitle}>{t('schemaConfig.generatedFields', { count: generatedFields.length })}</Text>
                <ScrollView style={styles.previewList}>
                  {generatedFields.map((field, index) => (
                    <FieldPreview
                      key={field.name + index}
                      field={field}
                      onRemove={() => removePreviewField(index)}
                    />
                  ))}
                </ScrollView>
                <TouchableOpacity
                  style={[styles.saveButton, saving && styles.buttonDisabled]}
                  onPress={handleSaveFields}
                  disabled={saving}
                >
                  {saving ? (
                    <ActivityIndicator size="small" color="#fff" />
                  ) : (
                    <>
                      <Icon source="content-save" size={20} color="#fff" />
                      <Text style={styles.saveButtonText}>{t('schemaConfig.saveFields')}</Text>
                    </>
                  )}
                </TouchableOpacity>
              </View>
            )}
          </View>
        </KeyboardAvoidingView>
      </Modal>

      {/* 版本历史模态框 */}
      <Modal visible={versionModalVisible} animationType="slide" transparent>
        <View style={styles.modalOverlay}>
          <View style={styles.modalContent}>
            <View style={styles.modalHeader}>
              <Text style={styles.modalTitle}>
                {t('schemaConfig.versionHistoryFor', { entityType: selectedTemplate ? t(getEntityTypeKey(selectedTemplate.entityType as EntityType)) : '' })}
              </Text>
              <TouchableOpacity onPress={() => setVersionModalVisible(false)}>
                <Icon source="close" size={24} color="#666" />
              </TouchableOpacity>
            </View>

            {loadingVersions ? (
              <View style={styles.loadingContainer}>
                <ActivityIndicator size="small" color="#667eea" />
              </View>
            ) : versions.length === 0 ? (
              <View style={styles.emptyContainer}>
                <Icon source="history" size={48} color="#ccc" />
                <Text style={styles.emptyText}>{t('schemaConfig.noVersionHistory')}</Text>
              </View>
            ) : (
              <ScrollView style={styles.versionList}>
                {versions.map((ver) => (
                  <View key={ver.id} style={styles.versionItem}>
                    <View style={styles.versionInfo}>
                      <Text style={styles.versionNumber}>{t('schemaConfig.versionLabel', { version: ver.version })}</Text>
                      <Text style={styles.versionDate}>
                        {new Date(ver.createdAt).toLocaleString('zh-CN')}
                      </Text>
                      {ver.changeSummary && (
                        <Text style={styles.versionSummary}>{ver.changeSummary}</Text>
                      )}
                    </View>
                    {ver.version !== selectedTemplate?.version && (
                      <TouchableOpacity
                        style={styles.rollbackButton}
                        onPress={() => handleRollback(ver.version)}
                      >
                        <Icon source="restore" size={16} color="#667eea" />
                        <Text style={styles.rollbackButtonText}>{t('schemaConfig.rollback')}</Text>
                      </TouchableOpacity>
                    )}
                    {ver.version === selectedTemplate?.version && (
                      <View style={styles.currentBadge}>
                        <Text style={styles.currentBadgeText}>{t('schemaConfig.current')}</Text>
                      </View>
                    )}
                  </View>
                ))}
              </ScrollView>
            )}
          </View>
        </View>
      </Modal>

      {/* 字段预览 Modal - 带 Tab 切换 */}
      <Modal
        visible={fieldsModalVisible}
        transparent
        animationType="slide"
        onRequestClose={() => setFieldsModalVisible(false)}
      >
        <View style={styles.modalOverlay}>
          <View style={styles.modalContent}>
            <View style={styles.modalHeader}>
              <Text style={styles.modalTitle}>
                {t('schemaConfig.fieldsFor', { entityType: previewEntityType ? t(getEntityTypeKey(previewEntityType)) : '' })}
              </Text>
              <TouchableOpacity onPress={() => setFieldsModalVisible(false)}>
                <Icon source="close" size={24} color="#666" />
              </TouchableOpacity>
            </View>

            {/* Tab 切换栏 - 增加 AI分析 Tab */}
            <View style={styles.tabContainer}>
              <TouchableOpacity
                style={[styles.tabItem, previewTab === 'fields' && styles.tabItemActive]}
                onPress={() => setPreviewTab('fields')}
              >
                <Icon source="format-list-bulleted" size={18} color={previewTab === 'fields' ? '#667eea' : '#666'} />
                <Text style={[styles.tabText, previewTab === 'fields' && styles.tabTextActive]}>
                  {t('schemaConfig.tabs.fields')}
                </Text>
              </TouchableOpacity>
              <TouchableOpacity
                style={[styles.tabItem, previewTab === 'form' && styles.tabItemActive]}
                onPress={() => setPreviewTab('form')}
              >
                <Icon source="form-select" size={18} color={previewTab === 'form' ? '#667eea' : '#666'} />
                <Text style={[styles.tabText, previewTab === 'form' && styles.tabTextActive]}>
                  {t('schemaConfig.tabs.formPreview')}
                </Text>
              </TouchableOpacity>
              <TouchableOpacity
                style={[styles.tabItem, previewTab === 'analyze' && styles.tabItemActive]}
                onPress={() => {
                  setPreviewTab('analyze');
                  if (!analyzeResult && previewEntityType && currentSchema) {
                    handleAnalyzeSchema();
                  }
                }}
              >
                <Icon source="robot" size={18} color={previewTab === 'analyze' ? '#667eea' : '#666'} />
                <Text style={[styles.tabText, previewTab === 'analyze' && styles.tabTextActive]}>
                  {t('schemaConfig.tabs.aiAnalyze')}
                </Text>
              </TouchableOpacity>
            </View>

            {currentSchemaFields.length === 0 ? (
              <View style={styles.emptyContainer}>
                <Icon source="format-list-bulleted" size={48} color="#ccc" />
                <Text style={styles.emptyText}>{t('schemaConfig.noFields')}</Text>
              </View>
            ) : (
              <>
                {/* 字段列表 Tab - 增加编辑功能 */}
                {previewTab === 'fields' && (
                  <>
                    <View style={styles.fieldCountContainer}>
                      <Text style={styles.fieldCountText}>
                        {t('schemaConfig.fieldCount', { count: currentSchemaFields.length })}
                      </Text>
                      <TouchableOpacity
                        style={styles.aiModifyButton}
                        onPress={() => previewEntityType && openAIModifyDialog(previewEntityType)}
                      >
                        <Icon source="robot" size={16} color="#667eea" />
                        <Text style={styles.aiModifyButtonText}>{t('schemaConfig.aiModifyField')}</Text>
                      </TouchableOpacity>
                    </View>
                    <ScrollView style={styles.fieldsList}>
                      {currentSchemaFields.map((field, index) => (
                        <TouchableOpacity
                          key={index}
                          style={styles.fieldPreviewItem}
                          onPress={() => openFieldEditModal(index, field)}
                        >
                          <View style={styles.fieldPreviewContent}>
                            <View style={styles.fieldTitleRow}>
                              <Text style={styles.fieldPreviewTitle}>{field.title}</Text>
                              {field.required && (
                                <View style={styles.fieldRequiredBadge}>
                                  <Text style={styles.fieldRequiredText}>{t('schemaConfig.required')}</Text>
                                </View>
                              )}
                            </View>
                            <Text style={styles.fieldPreviewName}>{field.name}</Text>
                            <View style={styles.fieldPreviewMeta}>
                              <Text style={styles.fieldPreviewType}>{field.type}</Text>
                              <Text style={styles.fieldPreviewComponent}>{field.component}</Text>
                            </View>
                          </View>
                          <Icon source="chevron-right" size={20} color="#ccc" />
                        </TouchableOpacity>
                      ))}
                    </ScrollView>
                  </>
                )}

                {/* 表单预览 Tab */}
                {previewTab === 'form' && currentSchema && (
                  <View style={styles.formPreviewContainer}>
                    <View style={styles.formPreviewHeader}>
                      <Icon source="eye" size={16} color="#667eea" />
                      <Text style={styles.formPreviewHint}>{t('schemaConfig.preview.hint')}</Text>
                    </View>
                    <ScrollView style={styles.formPreviewScroll}>
                      <DynamicForm
                        schema={currentSchema}
                        initialValues={generateSampleData(currentSchema)}
                        readOnly={true}
                        showSubmitButton={false}
                      />
                    </ScrollView>
                  </View>
                )}

                {/* AI 分析 Tab */}
                {previewTab === 'analyze' && (
                  <View style={styles.analyzeContainer}>
                    {analyzing ? (
                      <View style={styles.analyzeLoading}>
                        <ActivityIndicator size="large" color="#667eea" />
                        <Text style={styles.analyzeLoadingText}>{t('schemaConfig.analyze.loading')}</Text>
                      </View>
                    ) : analyzeResult ? (
                      <ScrollView style={styles.analyzeResultScroll}>
                        {/* 总体评分 */}
                        <View style={styles.analyzeScoreCard}>
                          <View style={styles.analyzeScoreMain}>
                            <Text style={styles.analyzeScoreValue}>{analyzeResult.overallScore}</Text>
                            <Text style={styles.analyzeScoreLabel}>{t('schemaConfig.analyze.overallScore')}</Text>
                          </View>
                          <View style={styles.analyzeScoreDimensions}>
                            <View style={styles.analyzeScoreDim}>
                              <Text style={styles.analyzeScoreDimValue}>{analyzeResult.dimensionScores.usability}</Text>
                              <Text style={styles.analyzeScoreDimLabel}>{t('schemaConfig.analyze.usability')}</Text>
                            </View>
                            <View style={styles.analyzeScoreDim}>
                              <Text style={styles.analyzeScoreDimValue}>{analyzeResult.dimensionScores.validation}</Text>
                              <Text style={styles.analyzeScoreDimLabel}>{t('schemaConfig.analyze.validation')}</Text>
                            </View>
                            <View style={styles.analyzeScoreDim}>
                              <Text style={styles.analyzeScoreDimValue}>{analyzeResult.dimensionScores.completeness}</Text>
                              <Text style={styles.analyzeScoreDimLabel}>{t('schemaConfig.analyze.completeness')}</Text>
                            </View>
                            <View style={styles.analyzeScoreDim}>
                              <Text style={styles.analyzeScoreDimValue}>{analyzeResult.dimensionScores.structure}</Text>
                              <Text style={styles.analyzeScoreDimLabel}>{t('schemaConfig.analyze.structure')}</Text>
                            </View>
                          </View>
                        </View>

                        {/* 总结评价 */}
                        <View style={styles.analyzeSummaryCard}>
                          <Text style={styles.analyzeSummaryTitle}>{t('schemaConfig.analyze.summary')}</Text>
                          <Text style={styles.analyzeSummaryText}>{analyzeResult.summary}</Text>
                        </View>

                        {/* 优化建议列表 */}
                        {analyzeResult.suggestions.length > 0 && (
                          <View style={styles.analyzeSuggestionsCard}>
                            <Text style={styles.analyzeSuggestionsTitle}>
                              {t('schemaConfig.analyze.suggestions', { count: analyzeResult.suggestions.length })}
                            </Text>
                            {analyzeResult.suggestions.map((suggestion) => (
                              <TouchableOpacity
                                key={suggestion.id}
                                style={[
                                  styles.analyzeSuggestionItem,
                                  selectedSuggestions.includes(suggestion.id) && styles.analyzeSuggestionSelected,
                                ]}
                                onPress={() => toggleSuggestionSelection(suggestion.id)}
                              >
                                <View style={styles.analyzeSuggestionHeader}>
                                  <View style={[
                                    styles.analyzeSuggestionPriority,
                                    { backgroundColor: getPriorityColor(suggestion.priority) + '20' },
                                  ]}>
                                    <Text style={[styles.analyzeSuggestionPriorityText, { color: getPriorityColor(suggestion.priority) }]}>
                                      {t(`schemaConfig.analyze.priority.${suggestion.priority}`)}
                                    </Text>
                                  </View>
                                  <Text style={styles.analyzeSuggestionType}>
                                    {t(`schemaConfig.analyze.type.${suggestion.type}`)}
                                  </Text>
                                  {selectedSuggestions.includes(suggestion.id) && (
                                    <Icon source="check-circle" size={18} color="#52c41a" />
                                  )}
                                </View>
                                <Text style={styles.analyzeSuggestionTitle}>{suggestion.title}</Text>
                                <Text style={styles.analyzeSuggestionDesc}>{suggestion.description}</Text>
                                {suggestion.expectedBenefit && (
                                  <Text style={styles.analyzeSuggestionBenefit}>
                                    {t('schemaConfig.analyze.expectedBenefit')}: {suggestion.expectedBenefit}
                                  </Text>
                                )}
                              </TouchableOpacity>
                            ))}

                            {/* 应用优化按钮 */}
                            {selectedSuggestions.length > 0 && (
                              <TouchableOpacity
                                style={[styles.applyOptimizeButton, optimizing && styles.buttonDisabled]}
                                onPress={handleApplyOptimizations}
                                disabled={optimizing}
                              >
                                {optimizing ? (
                                  <ActivityIndicator size="small" color="#fff" />
                                ) : (
                                  <>
                                    <Icon source="auto-fix" size={20} color="#fff" />
                                    <Text style={styles.applyOptimizeButtonText}>
                                      {t('schemaConfig.analyze.applySelected', { count: selectedSuggestions.length })}
                                    </Text>
                                  </>
                                )}
                              </TouchableOpacity>
                            )}
                          </View>
                        )}

                        {/* 重新分析按钮 */}
                        <TouchableOpacity
                          style={styles.reanalyzeButton}
                          onPress={handleAnalyzeSchema}
                        >
                          <Icon source="refresh" size={18} color="#667eea" />
                          <Text style={styles.reanalyzeButtonText}>{t('schemaConfig.analyze.reanalyze')}</Text>
                        </TouchableOpacity>
                      </ScrollView>
                    ) : (
                      <View style={styles.analyzeEmpty}>
                        <Icon source="robot" size={48} color="#ccc" />
                        <Text style={styles.analyzeEmptyText}>{t('schemaConfig.analyze.empty')}</Text>
                        <TouchableOpacity
                          style={styles.startAnalyzeButton}
                          onPress={handleAnalyzeSchema}
                        >
                          <Icon source="magnify" size={20} color="#fff" />
                          <Text style={styles.startAnalyzeButtonText}>{t('schemaConfig.analyze.start')}</Text>
                        </TouchableOpacity>
                      </View>
                    )}
                  </View>
                )}
              </>
            )}
          </View>
        </View>
      </Modal>

      {/* 字段编辑 Modal */}
      <Modal
        visible={editingFieldIndex !== null}
        transparent
        animationType="fade"
        onRequestClose={() => setEditingFieldIndex(null)}
      >
        <View style={styles.editModalOverlay}>
          <View style={styles.editModalContent}>
            <View style={styles.editModalHeader}>
              <Text style={styles.editModalTitle}>{t('schemaConfig.editField.title')}</Text>
              <TouchableOpacity onPress={() => setEditingFieldIndex(null)}>
                <Icon source="close" size={24} color="#666" />
              </TouchableOpacity>
            </View>

            <View style={styles.editModalBody}>
              <Text style={styles.editFieldLabel}>{t('schemaConfig.editField.fieldTitle')}</Text>
              <TextInput
                style={styles.editFieldInput}
                value={editFieldTitle}
                onChangeText={setEditFieldTitle}
                placeholder={t('schemaConfig.editField.fieldTitlePlaceholder')}
                placeholderTextColor="#999"
              />

              <View style={styles.editFieldRow}>
                <Text style={styles.editFieldLabel}>{t('schemaConfig.editField.required')}</Text>
                <TouchableOpacity
                  style={[styles.editFieldSwitch, editFieldRequired && styles.editFieldSwitchActive]}
                  onPress={() => setEditFieldRequired(!editFieldRequired)}
                >
                  <View style={[styles.editFieldSwitchThumb, editFieldRequired && styles.editFieldSwitchThumbActive]} />
                </TouchableOpacity>
              </View>

              <View style={styles.editFieldActions}>
                <TouchableOpacity
                  style={styles.editFieldDeleteButton}
                  onPress={handleDeleteField}
                >
                  <Icon source="delete" size={18} color="#ff4d4f" />
                  <Text style={styles.editFieldDeleteText}>{t('schemaConfig.editField.delete')}</Text>
                </TouchableOpacity>

                <TouchableOpacity
                  style={styles.editFieldSaveButton}
                  onPress={handleSaveFieldEdit}
                >
                  <Icon source="check" size={18} color="#fff" />
                  <Text style={styles.editFieldSaveText}>{t('schemaConfig.editField.save')}</Text>
                </TouchableOpacity>
              </View>
            </View>
          </View>
        </View>
      </Modal>

      {/* AI 修改字段 Modal */}
      <Modal
        visible={aiModifyModalVisible}
        transparent
        animationType="slide"
        onRequestClose={() => setAiModifyModalVisible(false)}
      >
        <KeyboardAvoidingView
          style={styles.modalOverlay}
          behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
        >
          <View style={styles.modalContent}>
            <View style={styles.modalHeader}>
              <Text style={styles.modalTitle}>{t('schemaConfig.aiModify.title')}</Text>
              <TouchableOpacity onPress={() => setAiModifyModalVisible(false)}>
                <Icon source="close" size={24} color="#666" />
              </TouchableOpacity>
            </View>

            <View style={styles.inputSection}>
              <Text style={styles.inputLabel}>{t('schemaConfig.aiModify.description')}</Text>
              <TextInput
                style={styles.textInput}
                placeholder={t('schemaConfig.aiModify.placeholder')}
                placeholderTextColor="#999"
                value={aiModifyInput}
                onChangeText={setAiModifyInput}
                multiline
                numberOfLines={3}
                textAlignVertical="top"
              />
              <TouchableOpacity
                style={[styles.generateButton, aiModifying && styles.buttonDisabled]}
                onPress={handleAIModifyField}
                disabled={aiModifying}
              >
                {aiModifying ? (
                  <ActivityIndicator size="small" color="#fff" />
                ) : (
                  <>
                    <Icon source="auto-fix" size={20} color="#fff" />
                    <Text style={styles.generateButtonText}>{t('schemaConfig.aiModify.execute')}</Text>
                  </>
                )}
              </TouchableOpacity>
            </View>

            <View style={styles.aiModifyHintSection}>
              <Text style={styles.aiModifyHintTitle}>{t('schemaConfig.aiModify.examples')}</Text>
              <Text style={styles.aiModifyHintItem}>• {t('schemaConfig.aiModify.example1')}</Text>
              <Text style={styles.aiModifyHintItem}>• {t('schemaConfig.aiModify.example2')}</Text>
              <Text style={styles.aiModifyHintItem}>• {t('schemaConfig.aiModify.example3')}</Text>
            </View>
          </View>
        </KeyboardAvoidingView>
      </Modal>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f5f7fa',
  },
  scrollView: {
    flex: 1,
  },
  loadingContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
  },
  loadingText: {
    marginTop: 12,
    color: '#666',
  },
  header: {
    padding: 20,
    backgroundColor: '#fff',
  },
  title: {
    fontSize: 24,
    fontWeight: 'bold',
    color: '#1a202c',
  },
  subtitle: {
    fontSize: 14,
    color: '#666',
    marginTop: 4,
  },
  statsCard: {
    margin: 16,
    padding: 16,
    backgroundColor: '#fff',
    borderRadius: 12,
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
    color: '#667eea',
  },
  statLabel: {
    fontSize: 12,
    color: '#666',
    marginTop: 4,
  },
  section: {
    marginHorizontal: 16,
  },
  sectionTitle: {
    fontSize: 16,
    fontWeight: '600',
    color: '#1a202c',
    marginBottom: 12,
  },
  entityCard: {
    backgroundColor: '#fff',
    borderRadius: 12,
    padding: 16,
    marginBottom: 12,
  },
  entityHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 12,
  },
  entityIcon: {
    width: 44,
    height: 44,
    borderRadius: 10,
    alignItems: 'center',
    justifyContent: 'center',
  },
  entityInfo: {
    flex: 1,
    marginLeft: 12,
  },
  entityTitle: {
    fontSize: 16,
    fontWeight: '600',
    color: '#1a202c',
  },
  entityStatus: {
    fontSize: 12,
    color: '#666',
    marginTop: 2,
  },
  configuredBadge: {
    marginLeft: 8,
  },
  entityActions: {
    flexDirection: 'row',
    gap: 8,
  },
  entityButton: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    padding: 10,
    borderRadius: 8,
    gap: 6,
  },
  entityButtonText: {
    fontSize: 13,
    fontWeight: '500',
  },
  modalOverlay: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.5)',
    justifyContent: 'flex-end',
  },
  modalContent: {
    backgroundColor: '#fff',
    borderTopLeftRadius: 20,
    borderTopRightRadius: 20,
    maxHeight: '80%',
    paddingBottom: 32,
  },
  modalHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    padding: 16,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#e0e0e0',
  },
  modalTitle: {
    fontSize: 18,
    fontWeight: '600',
    color: '#1a202c',
  },
  inputSection: {
    padding: 16,
  },
  inputLabel: {
    fontSize: 14,
    fontWeight: '500',
    color: '#1a202c',
    marginBottom: 8,
  },
  textInput: {
    borderWidth: 1,
    borderColor: '#e0e0e0',
    borderRadius: 8,
    padding: 12,
    fontSize: 14,
    color: '#1a202c',
    minHeight: 80,
    marginBottom: 12,
  },
  generateButton: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#667eea',
    padding: 14,
    borderRadius: 8,
    gap: 8,
  },
  generateButtonText: {
    color: '#fff',
    fontSize: 16,
    fontWeight: '600',
  },
  buttonDisabled: {
    opacity: 0.6,
  },
  previewSection: {
    paddingHorizontal: 16,
  },
  previewTitle: {
    fontSize: 14,
    fontWeight: '600',
    color: '#1a202c',
    marginBottom: 12,
  },
  previewList: {
    maxHeight: 200,
  },
  fieldPreviewItem: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#f5f7fa',
    padding: 12,
    borderRadius: 8,
    marginBottom: 8,
  },
  fieldPreviewContent: {
    flex: 1,
  },
  fieldPreviewTitle: {
    fontSize: 14,
    fontWeight: '600',
    color: '#1a202c',
  },
  fieldPreviewName: {
    fontSize: 12,
    color: '#666',
    marginTop: 2,
  },
  fieldPreviewMeta: {
    flexDirection: 'row',
    gap: 8,
    marginTop: 4,
  },
  fieldPreviewType: {
    fontSize: 11,
    color: '#667eea',
    backgroundColor: '#667eea20',
    paddingHorizontal: 6,
    paddingVertical: 2,
    borderRadius: 4,
  },
  fieldPreviewComponent: {
    fontSize: 11,
    color: '#52c41a',
    backgroundColor: '#52c41a20',
    paddingHorizontal: 6,
    paddingVertical: 2,
    borderRadius: 4,
  },
  fieldPreviewRemove: {
    padding: 4,
  },
  saveButton: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#52c41a',
    padding: 14,
    borderRadius: 8,
    gap: 8,
    marginTop: 12,
  },
  saveButtonText: {
    color: '#fff',
    fontSize: 16,
    fontWeight: '600',
  },
  emptyContainer: {
    padding: 32,
    alignItems: 'center',
  },
  emptyText: {
    fontSize: 14,
    color: '#999',
    marginTop: 12,
  },
  versionList: {
    padding: 16,
  },
  versionItem: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#f5f7fa',
    padding: 12,
    borderRadius: 8,
    marginBottom: 8,
  },
  versionInfo: {
    flex: 1,
  },
  versionNumber: {
    fontSize: 14,
    fontWeight: '600',
    color: '#1a202c',
  },
  versionDate: {
    fontSize: 12,
    color: '#666',
    marginTop: 2,
  },
  versionSummary: {
    fontSize: 12,
    color: '#999',
    marginTop: 4,
  },
  rollbackButton: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#667eea20',
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: 6,
    gap: 4,
  },
  rollbackButtonText: {
    fontSize: 12,
    color: '#667eea',
    fontWeight: '500',
  },
  currentBadge: {
    backgroundColor: '#52c41a20',
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 6,
  },
  currentBadgeText: {
    fontSize: 12,
    color: '#52c41a',
    fontWeight: '500',
  },
  // AI 拒绝区域样式
  rejectionSection: {
    marginHorizontal: 16,
    marginTop: 16,
    padding: 16,
    backgroundColor: '#fff7e6',
    borderRadius: 12,
    borderWidth: 1,
    borderColor: '#ffd591',
  },
  rejectionHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    marginBottom: 8,
  },
  rejectionTitle: {
    fontSize: 16,
    fontWeight: '600',
    color: '#d46b08',
  },
  rejectionReason: {
    fontSize: 14,
    color: '#ad4e00',
    lineHeight: 20,
    marginBottom: 12,
  },
  suggestionBox: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#f0f5ff',
    padding: 10,
    borderRadius: 8,
    gap: 8,
    marginBottom: 12,
  },
  suggestionText: {
    flex: 1,
    fontSize: 13,
    color: '#1d39c4',
  },
  justificationSection: {
    marginTop: 8,
  },
  justificationLabel: {
    fontSize: 14,
    fontWeight: '500',
    color: '#1a202c',
    marginBottom: 8,
  },
  justificationInput: {
    borderWidth: 1,
    borderColor: '#d9d9d9',
    borderRadius: 8,
    padding: 12,
    fontSize: 14,
    color: '#1a202c',
    backgroundColor: '#fff',
    minHeight: 80,
    marginBottom: 12,
  },
  retryButton: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#fa8c16',
    padding: 14,
    borderRadius: 8,
    gap: 8,
  },
  retryButtonText: {
    color: '#fff',
    fontSize: 16,
    fontWeight: '600',
  },
  // 历史按钮样式
  historyButton: {
    marginTop: 8,
    backgroundColor: '#f5f7fa',
  },
  // 字段预览样式
  fieldCountContainer: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingVertical: 12,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#e0e0e0',
  },
  fieldCountText: {
    fontSize: 14,
    color: '#666',
  },
  aiModifyButton: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#667eea15',
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: 16,
    gap: 4,
  },
  aiModifyButtonText: {
    fontSize: 13,
    color: '#667eea',
    fontWeight: '500',
  },
  fieldsList: {
    padding: 16,
    maxHeight: 400,
  },
  fieldTitleRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  fieldRequiredBadge: {
    backgroundColor: '#ff4d4f20',
    paddingHorizontal: 6,
    paddingVertical: 2,
    borderRadius: 4,
  },
  fieldRequiredText: {
    fontSize: 10,
    color: '#ff4d4f',
    fontWeight: '500',
  },
  // Tab 切换样式
  tabContainer: {
    flexDirection: 'row',
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#e0e0e0',
  },
  tabItem: {
    flex: 1,
    paddingVertical: 12,
    alignItems: 'center',
    borderBottomWidth: 2,
    borderBottomColor: 'transparent',
  },
  tabItemActive: {
    borderBottomColor: '#667eea',
  },
  tabText: {
    fontSize: 14,
    color: '#666',
  },
  tabTextActive: {
    color: '#667eea',
    fontWeight: '600',
  },
  // 表单预览样式
  formPreviewContainer: {
    flex: 1,
    backgroundColor: '#f5f5f5',
  },
  formPreviewHeader: {
    padding: 12,
    backgroundColor: '#f0f5ff',
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#d6e4ff',
  },
  formPreviewHint: {
    fontSize: 13,
    color: '#1d39c4',
    textAlign: 'center',
  },
  formPreviewScroll: {
    padding: 16,
  },
  // AI 分析样式
  analyzeContainer: {
    flex: 1,
    backgroundColor: '#f5f7fa',
  },
  analyzeResultScroll: {
    padding: 16,
  },
  analyzeLoading: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    padding: 40,
  },
  analyzeLoadingText: {
    fontSize: 14,
    color: '#667eea',
    marginTop: 12,
  },
  analyzeScoreMain: {
    alignItems: 'center',
    marginBottom: 16,
  },
  analyzeScoreCard: {
    backgroundColor: '#fff',
    borderRadius: 12,
    padding: 20,
    marginBottom: 16,
    alignItems: 'center',
  },
  analyzeScoreValue: {
    fontSize: 48,
    fontWeight: 'bold',
    color: '#667eea',
  },
  analyzeScoreLabel: {
    fontSize: 14,
    color: '#666',
    marginTop: 4,
  },
  analyzeScoreDimensions: {
    flexDirection: 'row',
    marginTop: 16,
    gap: 12,
  },
  analyzeScoreDim: {
    flex: 1,
    alignItems: 'center',
    backgroundColor: '#f5f7fa',
    padding: 12,
    borderRadius: 8,
  },
  analyzeScoreDimValue: {
    fontSize: 20,
    fontWeight: '600',
    color: '#1a202c',
  },
  analyzeScoreDimLabel: {
    fontSize: 11,
    color: '#666',
    marginTop: 4,
    textAlign: 'center',
  },
  analyzeSummaryCard: {
    backgroundColor: '#fff',
    borderRadius: 12,
    padding: 16,
    marginBottom: 16,
  },
  analyzeSummaryTitle: {
    fontSize: 16,
    fontWeight: '600',
    color: '#1a202c',
    marginBottom: 8,
  },
  analyzeSummaryText: {
    fontSize: 14,
    color: '#666',
    lineHeight: 20,
  },
  analyzeSuggestionsCard: {
    backgroundColor: '#fff',
    borderRadius: 12,
    padding: 16,
    marginBottom: 16,
  },
  analyzeSuggestionsTitle: {
    fontSize: 16,
    fontWeight: '600',
    color: '#1a202c',
    marginBottom: 12,
  },
  analyzeSuggestionItem: {
    backgroundColor: '#f5f7fa',
    borderRadius: 8,
    padding: 12,
    marginBottom: 8,
    borderWidth: 2,
    borderColor: 'transparent',
  },
  analyzeSuggestionSelected: {
    borderColor: '#52c41a',
    backgroundColor: '#f6ffed',
  },
  analyzeSuggestionHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    marginBottom: 8,
  },
  analyzeSuggestionPriority: {
    paddingHorizontal: 8,
    paddingVertical: 3,
    borderRadius: 4,
  },
  analyzeSuggestionPriorityText: {
    fontSize: 11,
    fontWeight: '600',
  },
  analyzeSuggestionType: {
    fontSize: 11,
    color: '#666',
    flex: 1,
  },
  analyzeSuggestionTitle: {
    fontSize: 14,
    fontWeight: '600',
    color: '#1a202c',
    marginBottom: 4,
  },
  analyzeSuggestionDesc: {
    fontSize: 13,
    color: '#666',
    lineHeight: 18,
  },
  analyzeSuggestionBenefit: {
    fontSize: 12,
    color: '#52c41a',
    marginTop: 8,
    fontStyle: 'italic',
  },
  applyOptimizeButton: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#52c41a',
    padding: 14,
    borderRadius: 8,
    gap: 8,
    marginTop: 8,
  },
  applyOptimizeButtonText: {
    color: '#fff',
    fontSize: 15,
    fontWeight: '600',
  },
  reanalyzeButton: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#667eea20',
    padding: 12,
    borderRadius: 8,
    gap: 6,
  },
  reanalyzeButtonText: {
    fontSize: 14,
    color: '#667eea',
    fontWeight: '500',
  },
  analyzeEmpty: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    padding: 40,
  },
  analyzeEmptyText: {
    fontSize: 14,
    color: '#999',
    marginTop: 16,
    marginBottom: 24,
    textAlign: 'center',
  },
  startAnalyzeButton: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#667eea',
    paddingHorizontal: 24,
    paddingVertical: 12,
    borderRadius: 8,
    gap: 8,
  },
  startAnalyzeButtonText: {
    color: '#fff',
    fontSize: 15,
    fontWeight: '600',
  },
  // 字段编辑 Modal 样式
  editModalOverlay: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.5)',
    justifyContent: 'center',
    alignItems: 'center',
  },
  editModalContent: {
    backgroundColor: '#fff',
    borderRadius: 12,
    width: '85%',
    maxWidth: 400,
  },
  editModalHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    padding: 16,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#e0e0e0',
  },
  editModalTitle: {
    fontSize: 16,
    fontWeight: '600',
    color: '#1a202c',
  },
  editModalBody: {
    padding: 16,
  },
  editFieldLabel: {
    fontSize: 14,
    fontWeight: '500',
    color: '#1a202c',
    marginBottom: 8,
  },
  editFieldInput: {
    borderWidth: 1,
    borderColor: '#e0e0e0',
    borderRadius: 8,
    padding: 12,
    fontSize: 14,
    color: '#1a202c',
    marginBottom: 16,
  },
  editFieldRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: 20,
  },
  editFieldSwitch: {
    width: 50,
    height: 28,
    borderRadius: 14,
    backgroundColor: '#e0e0e0',
    justifyContent: 'center',
    padding: 3,
  },
  editFieldSwitchActive: {
    backgroundColor: '#667eea',
  },
  editFieldSwitchThumb: {
    width: 22,
    height: 22,
    borderRadius: 11,
    backgroundColor: '#fff',
  },
  editFieldSwitchThumbActive: {
    alignSelf: 'flex-end',
  },
  editFieldActions: {
    flexDirection: 'row',
    gap: 12,
  },
  editFieldDeleteButton: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    padding: 12,
    borderRadius: 8,
    backgroundColor: '#ff4d4f20',
    gap: 6,
  },
  editFieldDeleteText: {
    fontSize: 14,
    color: '#ff4d4f',
    fontWeight: '500',
  },
  editFieldSaveButton: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    padding: 12,
    borderRadius: 8,
    backgroundColor: '#667eea',
    gap: 6,
  },
  editFieldSaveText: {
    fontSize: 14,
    color: '#fff',
    fontWeight: '500',
  },
  // AI 修改提示样式
  aiModifyHintSection: {
    paddingHorizontal: 16,
    paddingBottom: 16,
  },
  aiModifyHintTitle: {
    fontSize: 13,
    fontWeight: '500',
    color: '#666',
    marginBottom: 8,
  },
  aiModifyHintItem: {
    fontSize: 13,
    color: '#999',
    lineHeight: 20,
  },
});

export default SchemaConfigScreen;
