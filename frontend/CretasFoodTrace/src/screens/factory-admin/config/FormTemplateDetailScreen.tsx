/**
 * 表单模版详情页面
 * 查看/编辑 Schema、版本历史、回滚功能、表单预览
 */
import React, { useState, useCallback, useMemo, useEffect } from 'react';
import {
  View,
  Text,
  StyleSheet,
  ScrollView,
  TouchableOpacity,
  RefreshControl,
  Alert,
  Modal,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useNavigation, useRoute, RouteProp } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { Icon, ActivityIndicator, Chip, Button, Divider } from 'react-native-paper';
import { useTranslation } from 'react-i18next';

import { FAManagementStackParamList } from '../../../types/navigation';
import {
  formTemplateApiClient,
  FormTemplate,
  EntityType,
  TemplateVersion,
} from '../../../services/api/formTemplateApiClient';
import DynamicForm from '../../../formily/core/DynamicForm';
import type { FormSchema } from '../../../formily';

type NavigationProp = NativeStackNavigationProp<FAManagementStackParamList, 'FormTemplateDetail'>;
type RouteProps = RouteProp<FAManagementStackParamList, 'FormTemplateDetail'>;

// 实体类型显示名称映射
const ENTITY_TYPE_LABELS: Record<EntityType, { zh: string; en: string; icon: string; color: string }> = {
  QUALITY_CHECK: { zh: '质检表单', en: 'Quality Check', icon: 'clipboard-check', color: '#52c41a' },
  MATERIAL_BATCH: { zh: '原料批次', en: 'Material Batch', icon: 'package-variant', color: '#1890ff' },
  PROCESSING_BATCH: { zh: '生产批次', en: 'Processing Batch', icon: 'cog', color: '#722ed1' },
  SHIPMENT: { zh: '出货记录', en: 'Shipment', icon: 'truck-delivery', color: '#fa8c16' },
  EQUIPMENT: { zh: '设备信息', en: 'Equipment', icon: 'tools', color: '#13c2c2' },
  DISPOSAL_RECORD: { zh: '报废记录', en: 'Disposal Record', icon: 'delete', color: '#f5222d' },
  PRODUCT_TYPE: { zh: '产品类型', en: 'Product Type', icon: 'barcode', color: '#2196f3' },
  PRODUCTION_PLAN: { zh: '生产计划', en: 'Production Plan', icon: 'calendar-clock', color: '#ff9800' },
  SCALE_DEVICE: { zh: '电子秤设备', en: 'Scale Device', icon: 'scale', color: '#607d8b' },
  SCALE_PROTOCOL: { zh: '电子秤协议', en: 'Scale Protocol', icon: 'file-document-outline', color: '#795548' },
  ISAPI_DEVICE: { zh: 'ISAPI设备', en: 'ISAPI Device', icon: 'video', color: '#9c27b0' },
  RAW_MATERIAL_TYPE: { zh: '原料类型', en: 'Raw Material Type', icon: 'package-variant-closed', color: '#00897b' },
  PURCHASE_ORDER: { zh: '采购订单', en: 'Purchase Order', icon: 'file-document-edit', color: '#5c6bc0' },
};

type TabType = 'fields' | 'preview' | 'history';

// 生成示例数据
function generateSampleData(schema: FormSchema): Record<string, unknown> {
  const data: Record<string, unknown> = {};
  const properties = schema.properties || {};

  Object.entries(properties).forEach(([key, fieldConfig]) => {
    const config = fieldConfig as { type?: string; 'x-component'?: string; title?: string; enum?: unknown[] };
    const component = config['x-component'] || '';
    const fieldType = config.type;

    if (component === 'Select' || component === 'Radio') {
      const enumValues = config.enum;
      if (enumValues && enumValues.length > 0) {
        data[key] = enumValues[0];
      }
    } else if (component === 'DatePicker') {
      data[key] = new Date().toISOString().split('T')[0];
    } else if (component === 'Switch') {
      data[key] = true;
    } else if (component === 'NumberPicker' || fieldType === 'number') {
      data[key] = 100;
    } else if (fieldType === 'boolean') {
      data[key] = true;
    } else {
      data[key] = config.title || '示例文本';
    }
  });

  return data;
}

// 解析字段列表
function parseFields(schemaJson: string): Array<{ key: string; title: string; type: string; required: boolean }> {
  try {
    const schema = JSON.parse(schemaJson);
    const properties = schema.properties || {};
    const requiredFields = schema.required || [];

    return Object.entries(properties).map(([key, config]) => {
      const cfg = config as { title?: string; type?: string; 'x-component'?: string };
      return {
        key,
        title: cfg.title || key,
        type: cfg['x-component'] || cfg.type || 'string',
        required: requiredFields.includes(key),
      };
    });
  } catch {
    return [];
  }
}

export function FormTemplateDetailScreen() {
  const navigation = useNavigation<NavigationProp>();
  const route = useRoute<RouteProps>();
  const { entityType } = route.params;
  const { t, i18n } = useTranslation('home');
  const isZh = i18n.language.startsWith('zh');
  const config = ENTITY_TYPE_LABELS[entityType as EntityType];

  const [template, setTemplate] = useState<FormTemplate | null>(null);
  const [versions, setVersions] = useState<TemplateVersion[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [versionsLoading, setVersionsLoading] = useState(false);
  const [activeTab, setActiveTab] = useState<TabType>('fields');
  const [rollbackLoading, setRollbackLoading] = useState(false);

  // 解析 Schema
  const parsedSchema = useMemo<FormSchema | null>(() => {
    if (!template?.schemaJson) return null;
    try {
      return JSON.parse(template.schemaJson) as FormSchema;
    } catch {
      return null;
    }
  }, [template?.schemaJson]);

  // 字段列表
  const fields = useMemo(() => {
    if (!template?.schemaJson) return [];
    return parseFields(template.schemaJson);
  }, [template?.schemaJson]);

  // 示例数据
  const sampleData = useMemo(() => {
    if (!parsedSchema) return {};
    return generateSampleData(parsedSchema);
  }, [parsedSchema]);

  const loadTemplate = useCallback(async () => {
    try {
      const response = await formTemplateApiClient.getByEntityType(entityType as EntityType);
      setTemplate(response.data);
    } catch (error) {
      console.error('[FormTemplateDetailScreen] Load template error:', error);
    }
  }, [entityType]);

  const loadVersions = useCallback(async (templateId: string) => {
    try {
      setVersionsLoading(true);
      const response = await formTemplateApiClient.getVersionHistory(templateId, { page: 1, size: 20 });
      setVersions(response.content || []);
    } catch (error) {
      console.error('[FormTemplateDetailScreen] Load versions error:', error);
    } finally {
      setVersionsLoading(false);
    }
  }, []);

  const loadData = useCallback(async (showRefreshing = false) => {
    try {
      if (showRefreshing) {
        setRefreshing(true);
      } else {
        setLoading(true);
      }
      await loadTemplate();
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [loadTemplate]);

  useEffect(() => {
    loadData();
  }, [loadData]);

  // 切换到历史记录时加载版本
  useEffect(() => {
    if (activeTab === 'history' && template?.id && versions.length === 0) {
      loadVersions(template.id);
    }
  }, [activeTab, template?.id, loadVersions, versions.length]);

  const handleRollback = useCallback(async (version: number) => {
    if (!template?.id) return;

    Alert.alert(
      isZh ? '确认回滚' : 'Confirm Rollback',
      isZh
        ? `确定要回滚到版本 ${version} 吗？当前配置将被覆盖。`
        : `Are you sure to rollback to version ${version}? Current config will be overwritten.`,
      [
        { text: isZh ? '取消' : 'Cancel', style: 'cancel' },
        {
          text: isZh ? '确认回滚' : 'Confirm',
          style: 'destructive',
          onPress: async () => {
            try {
              setRollbackLoading(true);
              await formTemplateApiClient.rollbackToVersion(template.id, version);
              Alert.alert(isZh ? '成功' : 'Success', isZh ? '回滚成功' : 'Rollback successful');
              loadData(true);
            } catch (error) {
              Alert.alert(isZh ? '回滚失败' : 'Rollback Failed', String(error));
            } finally {
              setRollbackLoading(false);
            }
          },
        },
      ]
    );
  }, [template?.id, isZh, loadData]);

  const handleGoBack = () => {
    navigation.goBack();
  };

  if (loading) {
    return (
      <SafeAreaView style={styles.container}>
        <View style={styles.loadingContainer}>
          <ActivityIndicator size="large" color="#667eea" />
        </View>
      </SafeAreaView>
    );
  }

  const hasTemplate = template !== null;

  return (
    <SafeAreaView style={styles.container}>
      {/* Header */}
      <View style={styles.header}>
        <TouchableOpacity style={styles.backButton} onPress={handleGoBack}>
          <Icon source="arrow-left" size={24} color="#333" />
        </TouchableOpacity>
        <View style={styles.headerContent}>
          <View style={styles.headerTitleRow}>
            <View style={[styles.headerIcon, { backgroundColor: config.color + '15' }]}>
              <Icon source={config.icon} size={20} color={config.color} />
            </View>
            <Text style={styles.headerTitle}>{isZh ? config.zh : config.en}</Text>
          </View>
          {hasTemplate && (
            <View style={styles.headerMeta}>
              <Chip
                style={[
                  styles.sourceChip,
                  { backgroundColor: template.source === 'AI_ASSISTANT' ? '#f0f5ff' : '#f6ffed' },
                ]}
                textStyle={{
                  fontSize: 11,
                  color: template.source === 'AI_ASSISTANT' ? '#1890ff' : '#52c41a',
                }}
              >
                {template.source === 'AI_ASSISTANT' ? 'AI' : isZh ? '手动' : 'Manual'}
              </Chip>
              <Text style={styles.versionBadge}>v{template.version}</Text>
            </View>
          )}
        </View>
      </View>

      {/* Tab 切换 */}
      <View style={styles.tabContainer}>
        <TouchableOpacity
          style={[styles.tabItem, activeTab === 'fields' && styles.tabItemActive]}
          onPress={() => setActiveTab('fields')}
        >
          <Text style={[styles.tabText, activeTab === 'fields' && styles.tabTextActive]}>
            {isZh ? '字段列表' : 'Fields'}
          </Text>
        </TouchableOpacity>
        <TouchableOpacity
          style={[styles.tabItem, activeTab === 'preview' && styles.tabItemActive]}
          onPress={() => setActiveTab('preview')}
          disabled={!hasTemplate}
        >
          <Text
            style={[
              styles.tabText,
              activeTab === 'preview' && styles.tabTextActive,
              !hasTemplate && styles.tabTextDisabled,
            ]}
          >
            {isZh ? '表单预览' : 'Preview'}
          </Text>
        </TouchableOpacity>
        <TouchableOpacity
          style={[styles.tabItem, activeTab === 'history' && styles.tabItemActive]}
          onPress={() => setActiveTab('history')}
          disabled={!hasTemplate}
        >
          <Text
            style={[
              styles.tabText,
              activeTab === 'history' && styles.tabTextActive,
              !hasTemplate && styles.tabTextDisabled,
            ]}
          >
            {isZh ? '版本历史' : 'History'}
          </Text>
        </TouchableOpacity>
      </View>

      <ScrollView
        style={styles.scrollView}
        refreshControl={
          <RefreshControl refreshing={refreshing} onRefresh={() => loadData(true)} />
        }
      >
        {/* 无模版提示 */}
        {!hasTemplate && (
          <View style={styles.emptyContainer}>
            <Icon source="file-document-outline" size={64} color="#ccc" />
            <Text style={styles.emptyTitle}>
              {isZh ? '暂无自定义配置' : 'No Custom Config'}
            </Text>
            <Text style={styles.emptySubtitle}>
              {isZh
                ? '该表单使用系统默认字段，您可以通过 AI 助手添加自定义字段'
                : 'This form uses default fields. You can add custom fields via AI Assistant'}
            </Text>
            <Button
              mode="contained"
              style={styles.addButton}
              onPress={() => navigation.navigate('SchemaConfig')}
            >
              {isZh ? '前往 AI 配置' : 'Go to AI Config'}
            </Button>
          </View>
        )}

        {/* 字段列表 Tab */}
        {hasTemplate && activeTab === 'fields' && (
          <View style={styles.fieldListContainer}>
            <View style={styles.fieldListHeader}>
              <Text style={styles.fieldListTitle}>
                {isZh ? `共 ${fields.length} 个自定义字段` : `${fields.length} Custom Fields`}
              </Text>
            </View>
            {fields.map((field, index) => (
              <View key={field.key} style={styles.fieldItem}>
                <View style={styles.fieldInfo}>
                  <Text style={styles.fieldTitle}>{field.title}</Text>
                  <Text style={styles.fieldKey}>{field.key}</Text>
                </View>
                <View style={styles.fieldMeta}>
                  <Chip style={styles.typeChip} textStyle={styles.typeChipText}>
                    {field.type}
                  </Chip>
                  {field.required && (
                    <Text style={styles.requiredBadge}>*</Text>
                  )}
                </View>
              </View>
            ))}
          </View>
        )}

        {/* 表单预览 Tab */}
        {hasTemplate && activeTab === 'preview' && parsedSchema && (
          <View style={styles.previewContainer}>
            <View style={styles.previewHeader}>
              <Icon source="eye" size={16} color="#1d39c4" />
              <Text style={styles.previewHint}>
                {isZh ? '预览模式 - 仅供查看表单布局' : 'Preview mode - View form layout only'}
              </Text>
            </View>
            <View style={styles.previewContent}>
              <DynamicForm
                schema={parsedSchema}
                initialValues={sampleData}
                readOnly={true}
                showSubmitButton={false}
              />
            </View>
          </View>
        )}

        {/* 版本历史 Tab */}
        {hasTemplate && activeTab === 'history' && (
          <View style={styles.historyContainer}>
            {versionsLoading ? (
              <View style={styles.historyLoading}>
                <ActivityIndicator size="small" color="#667eea" />
                <Text style={styles.historyLoadingText}>
                  {isZh ? '加载版本历史...' : 'Loading history...'}
                </Text>
              </View>
            ) : versions.length === 0 ? (
              <View style={styles.historyEmpty}>
                <Icon source="history" size={48} color="#ccc" />
                <Text style={styles.historyEmptyText}>
                  {isZh ? '暂无版本历史' : 'No version history'}
                </Text>
              </View>
            ) : (
              versions.map((version, index) => (
                <View key={version.id} style={styles.versionItem}>
                  <View style={styles.versionTimeline}>
                    <View
                      style={[
                        styles.versionDot,
                        index === 0 && styles.versionDotCurrent,
                      ]}
                    />
                    {index < versions.length - 1 && <View style={styles.versionLine} />}
                  </View>
                  <View style={styles.versionContent}>
                    <View style={styles.versionHeader}>
                      <Text style={styles.versionNumber}>v{version.version}</Text>
                      {index === 0 && (
                        <Chip
                          style={styles.currentChip}
                          textStyle={{ fontSize: 10, color: '#52c41a' }}
                        >
                          {isZh ? '当前' : 'Current'}
                        </Chip>
                      )}
                    </View>
                    <Text style={styles.versionDate}>
                      {new Date(version.createdAt).toLocaleString(isZh ? 'zh-CN' : 'en-US')}
                    </Text>
                    {version.changeSummary && (
                      <Text style={styles.versionSummary}>{version.changeSummary}</Text>
                    )}
                    {index !== 0 && (
                      <TouchableOpacity
                        style={styles.rollbackButton}
                        onPress={() => handleRollback(version.version)}
                        disabled={rollbackLoading}
                      >
                        <Icon source="backup-restore" size={14} color="#667eea" />
                        <Text style={styles.rollbackText}>
                          {isZh ? '回滚到此版本' : 'Rollback'}
                        </Text>
                      </TouchableOpacity>
                    )}
                  </View>
                </View>
              ))
            )}
          </View>
        )}

        <View style={{ height: 40 }} />
      </ScrollView>
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
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 12,
    paddingHorizontal: 16,
    backgroundColor: '#fff',
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#e0e0e0',
  },
  backButton: {
    padding: 8,
    marginRight: 8,
  },
  headerContent: {
    flex: 1,
  },
  headerTitleRow: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  headerIcon: {
    width: 32,
    height: 32,
    borderRadius: 8,
    justifyContent: 'center',
    alignItems: 'center',
    marginRight: 10,
  },
  headerTitle: {
    fontSize: 18,
    fontWeight: 'bold',
    color: '#333',
  },
  headerMeta: {
    flexDirection: 'row',
    alignItems: 'center',
    marginTop: 4,
    marginLeft: 42,
  },
  sourceChip: {
    height: 20,
    marginRight: 8,
  },
  versionBadge: {
    fontSize: 12,
    color: '#999',
  },
  tabContainer: {
    flexDirection: 'row',
    backgroundColor: '#fff',
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
  tabTextDisabled: {
    color: '#ccc',
  },
  scrollView: {
    flex: 1,
  },
  emptyContainer: {
    alignItems: 'center',
    paddingVertical: 60,
    paddingHorizontal: 40,
  },
  emptyTitle: {
    fontSize: 18,
    fontWeight: '600',
    color: '#333',
    marginTop: 16,
  },
  emptySubtitle: {
    fontSize: 14,
    color: '#666',
    textAlign: 'center',
    marginTop: 8,
    lineHeight: 20,
  },
  addButton: {
    marginTop: 24,
    backgroundColor: '#667eea',
  },
  fieldListContainer: {
    backgroundColor: '#fff',
    margin: 16,
    borderRadius: 12,
    overflow: 'hidden',
  },
  fieldListHeader: {
    padding: 16,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#f0f0f0',
  },
  fieldListTitle: {
    fontSize: 14,
    fontWeight: '600',
    color: '#333',
  },
  fieldItem: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    padding: 16,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#f0f0f0',
  },
  fieldInfo: {
    flex: 1,
  },
  fieldTitle: {
    fontSize: 15,
    fontWeight: '500',
    color: '#333',
  },
  fieldKey: {
    fontSize: 12,
    color: '#999',
    marginTop: 2,
  },
  fieldMeta: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  typeChip: {
    height: 24,
    backgroundColor: '#f0f0f0',
  },
  typeChipText: {
    fontSize: 11,
    color: '#666',
  },
  requiredBadge: {
    fontSize: 16,
    color: '#f5222d',
    marginLeft: 8,
  },
  previewContainer: {
    margin: 16,
    backgroundColor: '#fff',
    borderRadius: 12,
    overflow: 'hidden',
  },
  previewHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    padding: 12,
    backgroundColor: '#f0f5ff',
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#d6e4ff',
  },
  previewHint: {
    fontSize: 13,
    color: '#1d39c4',
    marginLeft: 6,
  },
  previewContent: {
    padding: 16,
  },
  historyContainer: {
    margin: 16,
  },
  historyLoading: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    padding: 40,
  },
  historyLoadingText: {
    marginLeft: 8,
    fontSize: 14,
    color: '#666',
  },
  historyEmpty: {
    alignItems: 'center',
    padding: 40,
  },
  historyEmptyText: {
    marginTop: 12,
    fontSize: 14,
    color: '#999',
  },
  versionItem: {
    flexDirection: 'row',
  },
  versionTimeline: {
    width: 24,
    alignItems: 'center',
  },
  versionDot: {
    width: 12,
    height: 12,
    borderRadius: 6,
    backgroundColor: '#d9d9d9',
    marginTop: 4,
  },
  versionDotCurrent: {
    backgroundColor: '#52c41a',
  },
  versionLine: {
    flex: 1,
    width: 2,
    backgroundColor: '#e0e0e0',
    marginVertical: 4,
  },
  versionContent: {
    flex: 1,
    backgroundColor: '#fff',
    borderRadius: 12,
    padding: 16,
    marginLeft: 12,
    marginBottom: 12,
  },
  versionHeader: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  versionNumber: {
    fontSize: 16,
    fontWeight: '600',
    color: '#333',
  },
  currentChip: {
    height: 20,
    marginLeft: 8,
    backgroundColor: '#f6ffed',
  },
  versionDate: {
    fontSize: 12,
    color: '#999',
    marginTop: 4,
  },
  versionSummary: {
    fontSize: 13,
    color: '#666',
    marginTop: 8,
    lineHeight: 18,
  },
  rollbackButton: {
    flexDirection: 'row',
    alignItems: 'center',
    marginTop: 12,
    paddingVertical: 6,
  },
  rollbackText: {
    fontSize: 13,
    color: '#667eea',
    marginLeft: 4,
  },
});

export default FormTemplateDetailScreen;
