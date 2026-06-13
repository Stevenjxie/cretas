/**
 * Workshop Supervisor 批次管理
 * 包含: 搜索、筛选标签、批次列表
 */
import React, { useState, useCallback, useEffect } from 'react';
import {
  View,
  Text,
  StyleSheet,
  ScrollView,
  TouchableOpacity,
  TextInput,
  RefreshControl,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useNavigation } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { Icon } from 'react-native-paper';
import { useTranslation } from 'react-i18next';
import { useFactoryFeatureStore } from '../../../store/factoryFeatureStore';
import { TutorialOverlay } from '../../../components/common/TutorialOverlay';
import { useTutorialStore, TUTORIAL_BATCHES, TUTORIAL_ENABLED, useTutorialTarget } from '../../../store/tutorialStore';
import { WSBatchesStackParamList } from '../../../types/navigation';

type NavigationProp = NativeStackNavigationProp<WSBatchesStackParamList, 'WSBatches'>;

// 批次数据类型
interface Batch {
  id: string;
  batchNumber: string;
  productName: string;
  targetQuantity: number;
  currentQuantity: number;
  progress: number;
  status: 'pending' | 'in_progress' | 'completed';
  stage?: string;
  estimatedTime?: string;
  completedTime?: string;
  isUrgent?: boolean;
}

// 筛选标签
const FILTER_TABS = [
  { key: 'all', label: '全部', count: 11 },
  { key: 'in_progress', label: '进行中', count: 3 },
  { key: 'pending', label: '待开始', count: 4 },
  { key: 'completed', label: '已完成', count: 4 },
];

export function WSBatchesScreen() {
  const navigation = useNavigation<NavigationProp>();
  const { t } = useTranslation('workshop');
  const insets = useSafeAreaInsets();
  const [searchQuery, setSearchQuery] = useState('');
  const [activeFilter, setActiveFilter] = useState('all');
  const [refreshing, setRefreshing] = useState(false);

  // Tutorial
  const activeTutorial = useTutorialStore(s => s.activeTutorial);
  const activeStep = useTutorialStore(s => s.activeStep);
  const completedBatches = useTutorialStore(s => s.completedTutorials[TUTORIAL_BATCHES.id]);
  const showTutorial = activeTutorial === TUTORIAL_BATCHES.id;
  const tgtFilter = useTutorialTarget('batches-filter');
  const tgtBatchList = useTutorialTarget('batches-first-card');
  const tgtProcessBtn = useTutorialTarget('batches-process-btn');

  useEffect(() => {
    if (TUTORIAL_ENABLED && !completedBatches && activeTutorial === null) {
      const timer = setTimeout(() => {
        const s = useTutorialStore.getState();
        if (!s.activeTutorial && !s.completedTutorials[TUTORIAL_BATCHES.id]) {
          s.startTutorial(TUTORIAL_BATCHES.id);
        }
      }, 800);
      return () => clearTimeout(timer);
    }
    return undefined;
  }, [completedBatches]);

  useEffect(() => {
    if (showTutorial) {
      setTimeout(() => {
        tgtFilter.measure();
        tgtBatchList.measure();
        tgtProcessBtn.measure();
      }, 200);
    }
  }, [showTutorial]);

  // 模拟批次数据
  const [batches] = useState<Batch[]>([
    {
      id: '1',
      batchNumber: 'PB-20251227-001',
      productName: '带鱼片',
      targetQuantity: 80,
      currentQuantity: 52,
      progress: 65,
      status: 'in_progress',
      stage: '切片中',
      estimatedTime: '11:30',
      isUrgent: true,
    },
    {
      id: '2',
      batchNumber: 'PB-20251227-002',
      productName: '鲈鱼片',
      targetQuantity: 50,
      currentQuantity: 15,
      progress: 30,
      status: 'in_progress',
      stage: '解冻中',
      estimatedTime: '14:00',
    },
    {
      id: '3',
      batchNumber: 'PB-20251227-003',
      productName: '黄鱼片',
      targetQuantity: 60,
      currentQuantity: 27,
      progress: 45,
      status: 'in_progress',
      stage: '清洗中',
      estimatedTime: '15:30',
    },
    {
      id: '4',
      batchNumber: 'PB-20251227-004',
      productName: '银鲳鱼片',
      targetQuantity: 70,
      currentQuantity: 0,
      progress: 0,
      status: 'pending',
      estimatedTime: '13:00',
    },
    {
      id: '5',
      batchNumber: 'PB-20251227-005',
      productName: '鳗鱼片',
      targetQuantity: 40,
      currentQuantity: 0,
      progress: 0,
      status: 'pending',
      estimatedTime: '15:00',
    },
    {
      id: '6',
      batchNumber: 'PB-20251226-008',
      productName: '带鱼片',
      targetQuantity: 85,
      currentQuantity: 85,
      progress: 100,
      status: 'completed',
      completedTime: '昨日 16:30',
    },
  ]);

  // 筛选批次
  const filteredBatches = batches.filter(batch => {
    if (activeFilter !== 'all' && batch.status !== activeFilter) return false;
    if (searchQuery && !batch.batchNumber.toLowerCase().includes(searchQuery.toLowerCase())) return false;
    return true;
  });

  const onRefresh = useCallback(() => {
    setRefreshing(true);
    // TODO: 调用API刷新数据
    setTimeout(() => setRefreshing(false), 500);
  }, []);

  // 获取状态样式
  const getStatusStyle = (status: string, isUrgent?: boolean) => {
    if (isUrgent) {
      return { bg: '#fff1f0', color: '#ff4d4f', text: '[急] ' };
    }
    switch (status) {
      case 'in_progress':
        return { bg: '#e6f7ff', color: '#1890ff', text: '' };
      case 'pending':
        return { bg: '#f5f5f5', color: '#8c8c8c', text: '' };
      case 'completed':
        return { bg: '#f6ffed', color: '#52c41a', text: '' };
      default:
        return { bg: '#f5f5f5', color: '#8c8c8c', text: '' };
    }
  };

  return (
    <View style={styles.container}>
      {/* 头部 */}
      <View style={[styles.header, { paddingTop: insets.top + 8 }]}>
        <Text style={styles.headerTitle}>{t('batches.title')}</Text>
        <View ref={tgtProcessBtn.ref} onLayout={tgtProcessBtn.onLayout} style={{ flexDirection: 'row', gap: 8 }}>
          {useFactoryFeatureStore.getState().isProcessMode() && (
            <TouchableOpacity
              testID="ws-process-task-btn"
              style={[styles.addBtn, { backgroundColor: '#0891B2' }]}
              onPress={() => navigation.navigate('ProcessTaskList' as never)}
            >
              <Icon source="format-list-checks" size={24} color="#fff" />
            </TouchableOpacity>
          )}
          <TouchableOpacity
            style={styles.addBtn}
            onPress={() => navigation.navigate('BatchStart', {})}
          >
            <Icon source="plus" size={24} color="#fff" />
          </TouchableOpacity>
        </View>
      </View>

      {/* 搜索栏 */}
      <View style={styles.searchContainer}>
        <View style={styles.searchBar}>
          <Icon source="magnify" size={20} color="#999" />
          <TextInput
            style={styles.searchInput}
            placeholder={t('batches.searchPlaceholder')}
            placeholderTextColor="#999"
            value={searchQuery}
            onChangeText={setSearchQuery}
          />
        </View>
      </View>

      {/* 筛选标签 */}
      <View ref={tgtFilter.ref} onLayout={tgtFilter.onLayout} style={styles.filterContainer}>
        <ScrollView horizontal showsHorizontalScrollIndicator={false}>
          {FILTER_TABS.map(tab => (
            <TouchableOpacity
              key={tab.key}
              style={[styles.filterTab, activeFilter === tab.key && styles.filterTabActive]}
              onPress={() => setActiveFilter(tab.key)}
            >
              <Text style={[styles.filterTabText, activeFilter === tab.key && styles.filterTabTextActive]}>
                {tab.label} ({tab.count})
              </Text>
            </TouchableOpacity>
          ))}
        </ScrollView>
      </View>

      {/* 批次统计 */}
      <View style={styles.statsRow}>
        <View style={styles.statItem}>
          <Text style={[styles.statValue, { color: '#1890ff' }]}>3</Text>
          <Text style={styles.statLabel}>进行中</Text>
        </View>
        <View style={styles.statItem}>
          <Text style={[styles.statValue, { color: '#faad14' }]}>4</Text>
          <Text style={styles.statLabel}>待开始</Text>
        </View>
        <View style={styles.statItem}>
          <Text style={[styles.statValue, { color: '#52c41a' }]}>4</Text>
          <Text style={styles.statLabel}>已完成</Text>
        </View>
      </View>

      {/* 批次列表 */}
      <View ref={tgtBatchList.ref} onLayout={tgtBatchList.onLayout} style={{ flex: 1 }} collapsable={false}>
      <ScrollView
        style={styles.listContainer}
        refreshControl={
          <RefreshControl refreshing={refreshing} onRefresh={onRefresh} colors={['#667eea']} />
        }
      >
        {filteredBatches.map(batch => {
          const statusStyle = getStatusStyle(batch.status, batch.isUrgent);
          return (
            <TouchableOpacity
              key={batch.id}
              style={[styles.batchCard, batch.isUrgent && styles.urgentCard]}
              onPress={() => navigation.navigate('BatchDetail', { batchId: batch.id })}
            >
              <View style={styles.batchHeader}>
                <Text style={styles.batchNumber}>{batch.batchNumber}</Text>
                <View style={[styles.stageBadge, { backgroundColor: statusStyle.bg }]}>
                  <Text style={[styles.stageBadgeText, { color: statusStyle.color }]}>
                    {statusStyle.text}{batch.stage || (batch.status === 'pending' ? '待开始' : '已完成')}
                  </Text>
                </View>
              </View>

              <View style={styles.batchInfo}>
                <View style={styles.batchInfoItem}>
                  <Text style={styles.batchInfoLabel}>产品</Text>
                  <Text style={styles.batchInfoValue}>{batch.productName}</Text>
                </View>
                <View style={styles.batchInfoItem}>
                  <Text style={styles.batchInfoLabel}>{batch.status === 'completed' ? '产量' : '目标'}</Text>
                  <Text style={styles.batchInfoValue}>{batch.targetQuantity}kg</Text>
                </View>
                <View style={styles.batchInfoItem}>
                  <Text style={styles.batchInfoLabel}>
                    {batch.status === 'completed' ? '完成时间' : batch.status === 'pending' ? '计划开始' : '进度'}
                  </Text>
                  <Text style={styles.batchInfoValue}>
                    {batch.status === 'completed' ? batch.completedTime : batch.status === 'pending' ? batch.estimatedTime : `${batch.progress}%`}
                  </Text>
                </View>
              </View>

              {batch.status === 'in_progress' && (
                <View style={styles.progressSection}>
                  <View style={styles.progressBar}>
                    <View style={[styles.progressFill, { width: `${batch.progress}%` }]} />
                  </View>
                  <View style={styles.progressInfo}>
                    <Text style={styles.progressText}>{batch.currentQuantity}kg / {batch.targetQuantity}kg</Text>
                    <Text style={styles.progressTime}>预计 {batch.estimatedTime}</Text>
                  </View>
                </View>
              )}
            </TouchableOpacity>
          );
        })}
        <View style={{ height: 100 }} />
      </ScrollView>
      </View>

      {/* 悬浮添加按钮 */}
      <TouchableOpacity
        style={styles.fab}
        onPress={() => navigation.navigate('BatchStart', {})}
      >
        <Icon source="plus" size={28} color="#fff" />
      </TouchableOpacity>

      {/* Tutorial */}
      <TutorialOverlay
        visible={showTutorial}
        steps={TUTORIAL_BATCHES.steps}
        currentStep={activeStep}
        onNext={() => useTutorialStore.getState().nextStep(TUTORIAL_BATCHES.steps.length)}
        onSkip={() => useTutorialStore.getState().skipTutorial()}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f5f7fa',
  },
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 20,
    paddingVertical: 16,
    backgroundColor: '#667eea',
  },
  headerTitle: {
    fontSize: 20,
    fontWeight: '600',
    color: '#fff',
  },
  addBtn: {
    width: 40,
    height: 40,
    borderRadius: 20,
    backgroundColor: 'rgba(255,255,255,0.2)',
    justifyContent: 'center',
    alignItems: 'center',
  },

  // 搜索栏
  searchContainer: {
    paddingHorizontal: 16,
    paddingVertical: 12,
    backgroundColor: '#fff',
  },
  searchBar: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#f5f5f5',
    borderRadius: 8,
    paddingHorizontal: 12,
    height: 40,
  },
  searchInput: {
    flex: 1,
    marginLeft: 8,
    fontSize: 14,
    color: '#333',
  },

  // 筛选标签
  filterContainer: {
    paddingHorizontal: 16,
    paddingVertical: 12,
    backgroundColor: '#fff',
    borderBottomWidth: 1,
    borderBottomColor: '#f0f0f0',
  },
  filterTab: {
    paddingHorizontal: 16,
    paddingVertical: 8,
    marginRight: 8,
    borderRadius: 16,
    backgroundColor: '#f5f5f5',
  },
  filterTabActive: {
    backgroundColor: '#667eea',
  },
  filterTabText: {
    fontSize: 14,
    color: '#666',
  },
  filterTabTextActive: {
    color: '#fff',
  },

  // 统计
  statsRow: {
    flexDirection: 'row',
    paddingHorizontal: 16,
    paddingVertical: 12,
    backgroundColor: '#fff',
    marginBottom: 8,
  },
  statItem: {
    flex: 1,
    alignItems: 'center',
  },
  statValue: {
    fontSize: 24,
    fontWeight: 'bold',
  },
  statLabel: {
    fontSize: 12,
    color: '#666',
    marginTop: 4,
  },

  // 列表
  listContainer: {
    flex: 1,
    paddingHorizontal: 16,
    paddingTop: 8,
  },
  batchCard: {
    backgroundColor: '#fff',
    borderRadius: 12,
    padding: 16,
    marginBottom: 12,
  },
  urgentCard: {
    borderLeftWidth: 4,
    borderLeftColor: '#ff4d4f',
  },
  batchHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 12,
  },
  batchNumber: {
    fontSize: 16,
    fontWeight: '600',
    color: '#333',
  },
  stageBadge: {
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 12,
  },
  stageBadgeText: {
    fontSize: 12,
    fontWeight: '500',
  },
  batchInfo: {
    flexDirection: 'row',
    justifyContent: 'space-between',
  },
  batchInfoItem: {
    alignItems: 'center',
  },
  batchInfoLabel: {
    fontSize: 12,
    color: '#999',
    marginBottom: 4,
  },
  batchInfoValue: {
    fontSize: 14,
    color: '#333',
    fontWeight: '500',
  },
  progressSection: {
    marginTop: 12,
  },
  progressBar: {
    height: 6,
    backgroundColor: '#f0f0f0',
    borderRadius: 3,
  },
  progressFill: {
    height: '100%',
    backgroundColor: '#667eea',
    borderRadius: 3,
  },
  progressInfo: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginTop: 8,
  },
  progressText: {
    fontSize: 12,
    color: '#666',
  },
  progressTime: {
    fontSize: 12,
    color: '#999',
  },

  // FAB
  fab: {
    position: 'absolute',
    right: 20,
    bottom: 90,
    width: 56,
    height: 56,
    borderRadius: 28,
    backgroundColor: '#667eea',
    justifyContent: 'center',
    alignItems: 'center',
    elevation: 4,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.25,
    shadowRadius: 4,
  },
});

export default WSBatchesScreen;
