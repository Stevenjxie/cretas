import React, { useState, useEffect, useCallback } from 'react';
import {
  View,
  StyleSheet,
  FlatList,
  TouchableOpacity,
  RefreshControl,
  Alert,
} from 'react-native';
import {
  Text,
  Card,
  Searchbar,
  ActivityIndicator,
  Chip,
  useTheme,
} from 'react-native-paper';
import { useNavigation, CommonActions } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { SafeAreaView } from 'react-native-safe-area-context';
import { MaterialCommunityIcons as Icon } from '@expo/vector-icons';
import { isAxiosError } from 'axios';
import { WorkStackParamList } from '../../types/navigation';
import { workTypeApiClient, WorkType as ApiWorkType } from '../../services/api/workTypeApiClient';

/**
 * 工作类型接口 (UI display format)
 */
interface WorkType {
  code: string;
  name: string;
  description: string;
  color: string;
  department: string | null;
}

// Default colors for work types (fallback when not provided by API)
const workTypeColors: Record<string, string> = {
  'WT-RECEIVE': '#4CAF50',
  'WT-INSPECT': '#2196F3',
  'WT-PROCESS': '#FF9800',
  'WT-PACKAGE': '#9C27B0',
  'WT-STORAGE': '#00BCD4',
};

const defaultColor = '#667eea';

// Transform API work type to UI format
function transformWorkType(apiType: ApiWorkType): WorkType {
  return {
    code: apiType.code,
    name: apiType.name,
    description: apiType.description || apiType.name,
    color: workTypeColors[apiType.code] || defaultColor,
    department: apiType.department || null,
  };
}

/**
 * 工作类型图标映射
 */
const getWorkTypeIcon = (code: string): keyof typeof Icon.glyphMap => {
  const iconMap: Record<string, keyof typeof Icon.glyphMap> = {
    'WT-RECEIVE': 'truck-delivery',
    'WT-INSPECT': 'magnify-scan',
    'WT-PROCESS': 'cog-outline',
    'WT-PACKAGE': 'package-variant',
    'WT-STORAGE': 'fridge-outline',
  };
  return iconMap[code] || 'briefcase-outline';
};

/**
 * 工作类型列表页面
 * operator专用 - 显示所有可用的工作类型
 */
type WorkNavigationProp = NativeStackNavigationProp<WorkStackParamList>;

export function WorkTypeListScreen() {
  const theme = useTheme();
  const navigation = useNavigation<WorkNavigationProp>();

  const [workTypes, setWorkTypes] = useState<WorkType[]>([]);
  const [filteredWorkTypes, setFilteredWorkTypes] = useState<WorkType[]>([]);
  const [searchQuery, setSearchQuery] = useState('');
  const [loading, setLoading] = useState(false);
  const [refreshing, setRefreshing] = useState(false);

  // 初始化加载工作类型
  useEffect(() => {
    loadWorkTypes();
  }, []);

  // 搜索过滤
  useEffect(() => {
    if (searchQuery.trim() === '') {
      setFilteredWorkTypes(workTypes);
    } else {
      const filtered = workTypes.filter(
        (wt) =>
          wt.code.toLowerCase().includes(searchQuery.toLowerCase()) ||
          wt.description.toLowerCase().includes(searchQuery.toLowerCase())
      );
      setFilteredWorkTypes(filtered);
    }
  }, [searchQuery, workTypes]);

  /**
   * 加载工作类型列表
   */
  const loadWorkTypes = useCallback(async () => {
    setLoading(true);
    try {
      const response = await workTypeApiClient.getActiveWorkTypes();

      // Handle both array response and wrapped response
      const apiWorkTypes = Array.isArray(response) ? response : (response && typeof response === 'object' && 'data' in response) ? (response as { data: ApiWorkType[] }).data : [];
      const transformed = apiWorkTypes.map(transformWorkType);

      setWorkTypes(transformed);
      setFilteredWorkTypes(transformed);
    } catch (error) {
      if (isAxiosError(error)) {
        const status = error.response?.status;
        if (status === 401) {
          Alert.alert('会话过期', '请重新登录');
        } else if (status === 404 || status === 501) {
          // API not implemented yet - show empty state
          console.warn('工作类型API未实现，显示空列表');
          setWorkTypes([]);
          setFilteredWorkTypes([]);
        } else {
          console.error('加载工作类型失败:', error.response?.data?.message || error.message);
          Alert.alert('加载失败', '无法加载工作类型列表');
        }
      }
    } finally {
      setLoading(false);
    }
  }, []);

  /**
   * 下拉刷新
   */
  const handleRefresh = async () => {
    setRefreshing(true);
    await loadWorkTypes();
    setRefreshing(false);
  };

  /**
   * 点击工作类型 → 进入工作记录表单
   */
  const handleWorkTypePress = (workType: WorkType) => {
    console.log('导航到工作表单:', workType.code);

    // Route to specific Wizards based on Work Type Code
    switch (workType.code) {
      case 'WT-RECEIVE':
        // 原「原料入库向导」(CreateBatch 新建模式)已停用 —— 后端 POST /material-batches
        // 抛 409「普通批次页面已关闭无来源入库与续入」。按防呆 Rule 5 给出去处,
        // 不留一个点了必失败的入口。
        Alert.alert(
          '原料入库已改流程',
          '原料入库请由仓储角色从「待收货 / 无单入库」进入；本页的旧入库向导已停用。',
        );
        break;
      case 'WT-INSPECT':
        // 质量检验 -> 质检记录向导
        navigation.dispatch(CommonActions.navigate('CreateQualityRecord', {
          batchId: 'BATCH_MOCK', // In real app, user might select batch first
          inspectionType: 'process'
        }));
        break;
      case 'WT-PACKAGE':
        // 产品包装 -> 包装向导
        navigation.dispatch(CommonActions.navigate('CreatePackaging'));
        break;
      default:
        // 其他类型 (加工、存储等) -> 通用工作向导
        navigation.navigate('WorkTypeForm', {
          workTypeCode: workType.code,
          workTypeName: workType.name,
        });
    }
  };

  /**
   * 渲染工作类型卡片
   */
  const renderWorkTypeItem = ({ item }: { item: WorkType }) => (
    <TouchableOpacity
      onPress={() => handleWorkTypePress(item)}
      activeOpacity={0.7}
    >
      <Card style={styles.card} mode="elevated">
        <Card.Content style={styles.cardContent}>
          {/* 图标 */}
          <View
            style={[
              styles.iconContainer,
              { backgroundColor: item.color },
            ]}
          >
            <Icon name={getWorkTypeIcon(item.code)} size={32} color="#fff" />
          </View>

          {/* 信息 */}
          <View style={styles.infoContainer}>
            <Text variant="titleMedium" style={styles.title}>
              {item.name}
            </Text>
            <Text variant="bodySmall" style={styles.code}>
              {item.code}
            </Text>
            {item.department && (
              <Chip
                mode="outlined"
                compact
                style={styles.chip}
                textStyle={styles.chipText}
              >
                {item.department === 'processing' ? '生产部' : item.department}
              </Chip>
            )}
          </View>

          {/* 箭头 */}
          <Icon name="chevron-right" size={24} color={theme.colors.outline} />
        </Card.Content>
      </Card>
    </TouchableOpacity>
  );

  /**
   * 空状态
   */
  const renderEmptyState = () => (
    <View style={styles.emptyContainer}>
      <Icon
        name="briefcase-off-outline"
        size={64}
        color={theme.colors.outline}
      />
      <Text variant="titleMedium" style={styles.emptyTitle}>
        暂无可用的工作类型
      </Text>
      <Text variant="bodySmall" style={styles.emptyDescription}>
        请联系管理员分配工作类型
      </Text>
    </View>
  );

  if (loading && !refreshing) {
    return (
      <View style={styles.loadingContainer}>
        <ActivityIndicator size="large" />
        <Text variant="bodyMedium" style={styles.loadingText}>
          加载中...
        </Text>
      </View>
    );
  }

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      {/* 标题 */}
      <View style={styles.header}>
        <Text variant="headlineMedium" style={styles.headerTitle}>
          我的工作
        </Text>
        <Text variant="bodyMedium" style={styles.headerSubtitle}>
          选择一个工作类型开始记录
        </Text>
      </View>

      {/* 搜索栏 */}
      <View style={styles.searchContainer}>
        <Searchbar
          placeholder="搜索工作类型..."
          value={searchQuery}
          onChangeText={setSearchQuery}
          style={styles.searchBar}
        />
      </View>

      {/* 工作类型列表 */}
      <FlatList
        data={filteredWorkTypes}
        renderItem={renderWorkTypeItem}
        keyExtractor={(item) => item.code}
        contentContainerStyle={styles.listContent}
        ListEmptyComponent={renderEmptyState}
        refreshControl={
          <RefreshControl refreshing={refreshing} onRefresh={handleRefresh} />
        }
        showsVerticalScrollIndicator={false}
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
    backgroundColor: '#f5f5f5',
  },
  loadingText: {
    marginTop: 16,
    color: '#666',
  },
  header: {
    paddingHorizontal: 20,
    paddingVertical: 16,
    backgroundColor: '#fff',
    borderBottomWidth: 1,
    borderBottomColor: '#e0e0e0',
  },
  headerTitle: {
    fontWeight: 'bold',
    color: '#212121',
  },
  headerSubtitle: {
    marginTop: 4,
    color: '#666',
  },
  searchContainer: {
    paddingHorizontal: 16,
    paddingVertical: 12,
    backgroundColor: '#fff',
  },
  searchBar: {
    elevation: 0,
    backgroundColor: '#f5f5f5',
  },
  listContent: {
    padding: 16,
    flexGrow: 1,
  },
  card: {
    marginBottom: 12,
    backgroundColor: '#fff',
  },
  cardContent: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 16,
  },
  iconContainer: {
    width: 56,
    height: 56,
    borderRadius: 28,
    justifyContent: 'center',
    alignItems: 'center',
    marginRight: 16,
  },
  infoContainer: {
    flex: 1,
  },
  title: {
    fontWeight: '600',
    color: '#212121',
    marginBottom: 4,
  },
  code: {
    color: '#666',
    marginBottom: 8,
  },
  chip: {
    alignSelf: 'flex-start',
    height: 24,
  },
  chipText: {
    fontSize: 11,
    marginVertical: 0,
  },
  emptyContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    paddingVertical: 80,
  },
  emptyTitle: {
    marginTop: 16,
    color: '#666',
    fontWeight: '600',
  },
  emptyDescription: {
    marginTop: 8,
    color: '#999',
  },
});

export default WorkTypeListScreen;
