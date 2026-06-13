/**
 * 表单模版列表页面
 * 按实体类型分组显示所有表单模版，支持查看详情和版本历史
 */
import React, { useState, useCallback, useMemo } from 'react';
import {
  View,
  Text,
  StyleSheet,
  ScrollView,
  TouchableOpacity,
  RefreshControl,
  Alert,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useNavigation } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { Icon, ActivityIndicator, Chip, Divider } from 'react-native-paper';
import { useTranslation } from 'react-i18next';
import { useFocusEffect } from '@react-navigation/native';

import { FAManagementStackParamList } from '../../../types/navigation';
import {
  formTemplateApiClient,
  FormTemplate,
  EntityType,
  TemplateStatistics,
} from '../../../services/api/formTemplateApiClient';

type NavigationProp = NativeStackNavigationProp<FAManagementStackParamList, 'FormTemplateList'>;

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

// 所有支持的实体类型
const ALL_ENTITY_TYPES: EntityType[] = [
  'QUALITY_CHECK',
  'MATERIAL_BATCH',
  'PROCESSING_BATCH',
  'SHIPMENT',
  'EQUIPMENT',
  'DISPOSAL_RECORD',
  'PRODUCT_TYPE',
  'PRODUCTION_PLAN',
  'SCALE_DEVICE',
  'SCALE_PROTOCOL',
  'ISAPI_DEVICE',
  'RAW_MATERIAL_TYPE',
  'PURCHASE_ORDER',
];

interface EntityCardProps {
  entityType: EntityType;
  template: FormTemplate | null;
  onPress: () => void;
}

function EntityCard({ entityType, template, onPress }: EntityCardProps) {
  const { i18n } = useTranslation();
  const isZh = i18n.language.startsWith('zh');
  const config = ENTITY_TYPE_LABELS[entityType];

  const hasCustom = template !== null;
  const fieldCount = useMemo(() => {
    if (!template?.schemaJson) return 0;
    try {
      const schema = JSON.parse(template.schemaJson);
      return Object.keys(schema.properties || {}).length;
    } catch {
      return 0;
    }
  }, [template?.schemaJson]);

  return (
    <TouchableOpacity style={styles.entityCard} onPress={onPress}>
      <View style={[styles.entityIconContainer, { backgroundColor: config.color + '15' }]}>
        <Icon source={config.icon} size={28} color={config.color} />
      </View>
      <View style={styles.entityContent}>
        <Text style={styles.entityTitle}>{isZh ? config.zh : config.en}</Text>
        {hasCustom ? (
          <View style={styles.entityMeta}>
            <Chip
              style={[styles.statusChip, { backgroundColor: '#e6f7ff' }]}
              textStyle={{ fontSize: 11, color: '#1890ff' }}
            >
              {isZh ? `${fieldCount} 个自定义字段` : `${fieldCount} custom fields`}
            </Chip>
            <Text style={styles.versionText}>v{template.version}</Text>
          </View>
        ) : (
          <Text style={styles.entitySubtitle}>
            {isZh ? '使用默认配置' : 'Using default config'}
          </Text>
        )}
      </View>
      <Icon source="chevron-right" size={24} color="#bbb" />
    </TouchableOpacity>
  );
}

interface StatisticsCardProps {
  stats: TemplateStatistics | null;
  loading: boolean;
}

function StatisticsCard({ stats, loading }: StatisticsCardProps) {
  const { t, i18n } = useTranslation('home');
  const isZh = i18n.language.startsWith('zh');

  if (loading) {
    return (
      <View style={styles.statsCard}>
        <ActivityIndicator size="small" color="#667eea" />
      </View>
    );
  }

  if (!stats) return null;

  return (
    <View style={styles.statsCard}>
      <View style={styles.statsRow}>
        <View style={styles.statItem}>
          <Text style={styles.statValue}>{stats.totalCount}</Text>
          <Text style={styles.statLabel}>{isZh ? '自定义模版' : 'Custom Templates'}</Text>
        </View>
        <View style={[styles.statItem, styles.statItemBorder]}>
          <Text style={styles.statValue}>{stats.aiGeneratedCount}</Text>
          <Text style={styles.statLabel}>{isZh ? 'AI 生成' : 'AI Generated'}</Text>
        </View>
        <View style={styles.statItem}>
          <Text style={[styles.statValue, { color: '#52c41a' }]}>
            {Math.round(stats.coverageRate * 100)}%
          </Text>
          <Text style={styles.statLabel}>{isZh ? '配置覆盖率' : 'Coverage'}</Text>
        </View>
      </View>
    </View>
  );
}

export function FormTemplateListScreen() {
  const navigation = useNavigation<NavigationProp>();
  const { t, i18n } = useTranslation('home');
  const isZh = i18n.language.startsWith('zh');

  const [templates, setTemplates] = useState<FormTemplate[]>([]);
  const [statistics, setStatistics] = useState<TemplateStatistics | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [statsLoading, setStatsLoading] = useState(true);

  // 按实体类型映射模版
  const templateMap = useMemo(() => {
    const map: Record<EntityType, FormTemplate | null> = {} as Record<EntityType, FormTemplate | null>;
    ALL_ENTITY_TYPES.forEach((type) => {
      map[type] = templates.find((t) => t.entityType === type) || null;
    });
    return map;
  }, [templates]);

  const loadData = useCallback(async (showRefreshing = false) => {
    try {
      if (showRefreshing) {
        setRefreshing(true);
      } else {
        setLoading(true);
      }

      // 并行加载
      const [listResponse, statsResponse] = await Promise.all([
        formTemplateApiClient.getTemplateList({ page: 1, size: 100 }),
        formTemplateApiClient.getStatistics(),
      ]);

      setTemplates(listResponse.content || []);
      setStatistics(statsResponse);
    } catch (error) {
      console.error('[FormTemplateListScreen] Load error:', error);
      Alert.alert(
        isZh ? '加载失败' : 'Load Failed',
        isZh ? '无法加载模版列表，请稍后重试' : 'Failed to load templates, please try again'
      );
    } finally {
      setLoading(false);
      setRefreshing(false);
      setStatsLoading(false);
    }
  }, [isZh]);

  useFocusEffect(
    useCallback(() => {
      loadData();
    }, [loadData])
  );

  const handleEntityPress = (entityType: EntityType) => {
    navigation.navigate('FormTemplateDetail', { entityType });
  };

  const handleGoBack = () => {
    navigation.goBack();
  };

  if (loading && !refreshing) {
    return (
      <SafeAreaView style={styles.container}>
        <View style={styles.loadingContainer}>
          <ActivityIndicator size="large" color="#667eea" />
          <Text style={styles.loadingText}>
            {isZh ? '加载中...' : 'Loading...'}
          </Text>
        </View>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={styles.container}>
      {/* Header */}
      <View style={styles.header}>
        <TouchableOpacity style={styles.backButton} onPress={handleGoBack}>
          <Icon source="arrow-left" size={24} color="#333" />
        </TouchableOpacity>
        <View style={styles.headerContent}>
          <Text style={styles.headerTitle}>
            {t('formTemplate.title', isZh ? '表单模版管理' : 'Form Templates')}
          </Text>
          <Text style={styles.headerSubtitle}>
            {t('formTemplate.subtitle', isZh ? '管理各业务表单的自定义字段' : 'Manage custom fields for business forms')}
          </Text>
        </View>
      </View>

      <ScrollView
        style={styles.scrollView}
        refreshControl={
          <RefreshControl refreshing={refreshing} onRefresh={() => loadData(true)} />
        }
      >
        {/* 统计卡片 */}
        <StatisticsCard stats={statistics} loading={statsLoading} />

        {/* 提示信息 */}
        <View style={styles.hintContainer}>
          <Icon source="information-outline" size={16} color="#666" />
          <Text style={styles.hintText}>
            {isZh
              ? '点击任意表单类型可查看详情、版本历史和回滚操作'
              : 'Tap any form type to view details, version history and rollback options'}
          </Text>
        </View>

        {/* 实体类型列表 */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>
            {isZh ? '业务表单' : 'Business Forms'}
          </Text>
          <View style={styles.entityList}>
            {ALL_ENTITY_TYPES.map((entityType) => (
              <EntityCard
                key={entityType}
                entityType={entityType}
                template={templateMap[entityType]}
                onPress={() => handleEntityPress(entityType)}
              />
            ))}
          </View>
        </View>

        {/* 底部说明 */}
        <View style={styles.footerNote}>
          <Text style={styles.footerNoteText}>
            {isZh
              ? '自定义字段会与系统默认字段合并显示在表单中'
              : 'Custom fields will be merged with default fields in forms'}
          </Text>
        </View>
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
  loadingText: {
    marginTop: 12,
    fontSize: 14,
    color: '#666',
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 16,
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
  headerTitle: {
    fontSize: 20,
    fontWeight: 'bold',
    color: '#333',
  },
  headerSubtitle: {
    fontSize: 13,
    color: '#666',
    marginTop: 2,
  },
  scrollView: {
    flex: 1,
  },
  statsCard: {
    backgroundColor: '#fff',
    marginHorizontal: 16,
    marginTop: 16,
    borderRadius: 12,
    padding: 16,
  },
  statsRow: {
    flexDirection: 'row',
    justifyContent: 'space-around',
  },
  statItem: {
    flex: 1,
    alignItems: 'center',
  },
  statItemBorder: {
    borderLeftWidth: StyleSheet.hairlineWidth,
    borderRightWidth: StyleSheet.hairlineWidth,
    borderColor: '#e0e0e0',
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
  hintContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    marginHorizontal: 16,
    marginTop: 16,
    paddingHorizontal: 12,
    paddingVertical: 8,
    backgroundColor: '#f0f5ff',
    borderRadius: 8,
  },
  hintText: {
    flex: 1,
    fontSize: 13,
    color: '#666',
    marginLeft: 8,
  },
  section: {
    marginTop: 16,
    paddingHorizontal: 16,
  },
  sectionTitle: {
    fontSize: 14,
    fontWeight: '600',
    color: '#666',
    marginBottom: 12,
    marginLeft: 4,
  },
  entityList: {
    backgroundColor: '#fff',
    borderRadius: 12,
    overflow: 'hidden',
  },
  entityCard: {
    flexDirection: 'row',
    alignItems: 'center',
    padding: 16,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#f0f0f0',
  },
  entityIconContainer: {
    width: 48,
    height: 48,
    borderRadius: 12,
    justifyContent: 'center',
    alignItems: 'center',
  },
  entityContent: {
    flex: 1,
    marginLeft: 12,
  },
  entityTitle: {
    fontSize: 15,
    fontWeight: '600',
    color: '#333',
  },
  entitySubtitle: {
    fontSize: 13,
    color: '#999',
    marginTop: 2,
  },
  entityMeta: {
    flexDirection: 'row',
    alignItems: 'center',
    marginTop: 4,
  },
  statusChip: {
    height: 22,
    marginRight: 8,
  },
  versionText: {
    fontSize: 12,
    color: '#999',
  },
  footerNote: {
    marginHorizontal: 16,
    marginTop: 16,
    marginBottom: 32,
    padding: 12,
    backgroundColor: '#fafafa',
    borderRadius: 8,
  },
  footerNoteText: {
    fontSize: 12,
    color: '#999',
    textAlign: 'center',
  },
});

export default FormTemplateListScreen;
