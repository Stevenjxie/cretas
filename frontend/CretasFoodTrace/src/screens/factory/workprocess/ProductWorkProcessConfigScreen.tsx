/**
 * 产品工序配置 — 给产品绑定多个工序 + 排序
 *
 * 入口: 系统管理 → 产品工序配置
 * Track D2 — M-WP-2
 *
 * 客户场景 (六扇门第四次会议 line 66-104):
 *   "产品工序配置: 先是在工序里面配置有哪些工序, 然后再到产品工序配置里面去添加。"
 *   "工序流程就是拆包, 分割, 卤制, 拆股, 分配"
 *
 * 这个屏幕也是 Day 6 bug "生产工序通用 P 过来"的根因配置入口 —
 * 客户必须先在这里给产品绑工序, 生产计划新建时才能自动出工序下拉
 */

import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Alert,
  FlatList,
  Modal,
  Pressable,
  RefreshControl,
  StyleSheet,
  View,
} from 'react-native';
import {
  ActivityIndicator,
  Appbar,
  Button,
  Card,
  Chip,
  Divider,
  IconButton,
  List,
  Searchbar,
  Switch,
  Text,
} from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useNavigation, useRoute, type RouteProp } from '@react-navigation/native';

import { ProductTypeSelector } from '../../../components/common/ProductTypeSelector';
import { workProcessApiClient } from '../../../services/api/workProcessApiClient';
import { useAuthStore } from '../../../store/authStore';
import { getFactoryId } from '../../../types/auth';
import { logger } from '../../../utils/logger';
import type { ManagementStackParamList } from '../../../types/navigation';
import type {
  ProductWorkProcess,
  WorkProcess,
} from '../../../types/workProcess';

const screenLogger = logger.createContextLogger('ProductWorkProcessConfig');

type RoutePropType = RouteProp<ManagementStackParamList, 'ProductWorkProcessConfig'>;

interface SelectedProduct {
  id: string;
  name: string;
  code: string;
}

export default function ProductWorkProcessConfigScreen() {
  const navigation = useNavigation();
  const route = useRoute<RoutePropType>();
  const user = useAuthStore((state) => state.user);
  const factoryId = getFactoryId(user);

  const initialProductTypeId = route.params?.productTypeId;

  const [selectedProduct, setSelectedProduct] = useState<SelectedProduct | null>(
    initialProductTypeId ? { id: initialProductTypeId, name: '加载中...', code: '' } : null,
  );

  const [bindings, setBindings] = useState<ProductWorkProcess[]>([]);
  const [allWorkProcesses, setAllWorkProcesses] = useState<WorkProcess[]>([]);
  const [loading, setLoading] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [actioning, setActioning] = useState(false);

  // 选择工序的 Modal
  const [pickerVisible, setPickerVisible] = useState(false);
  const [pickerSearch, setPickerSearch] = useState('');

  const loadAllWorkProcesses = useCallback(async () => {
    if (!factoryId) return;
    try {
      const list = await workProcessApiClient.listActiveWorkProcesses(factoryId);
      setAllWorkProcesses(list);
    } catch (error) {
      screenLogger.error('加载启用工序列表失败', error as Error);
      Alert.alert('错误', error instanceof Error ? error.message : '加载启用工序列表失败');
    }
  }, [factoryId]);

  const loadBindings = useCallback(
    async (productTypeId: string, silent = false) => {
      if (!factoryId) return;
      try {
        if (!silent) setLoading(true);
        const list = await workProcessApiClient.listProductWorkProcesses(
          productTypeId,
          factoryId,
        );
        // 按 processOrder 升序 (后端已 sort, 防御性再 sort)
        list.sort((a, b) => (a.processOrder ?? 0) - (b.processOrder ?? 0));
        setBindings(list);
        screenLogger.info('产品工序绑定加载成功', {
          productTypeId,
          count: list.length,
        });
      } catch (error) {
        screenLogger.error('加载产品工序绑定失败', error as Error, { productTypeId });
        Alert.alert('错误', error instanceof Error ? error.message : '加载失败');
      } finally {
        if (!silent) setLoading(false);
        setRefreshing(false);
      }
    },
    [factoryId],
  );

  useEffect(() => {
    loadAllWorkProcesses();
  }, [loadAllWorkProcesses]);

  useEffect(() => {
    if (selectedProduct) {
      loadBindings(selectedProduct.id);
    } else {
      setBindings([]);
    }
  }, [selectedProduct, loadBindings]);

  const handleSelectProduct = (
    productTypeId: string,
    productTypeName: string,
    productTypeCode: string,
  ) => {
    setSelectedProduct({ id: productTypeId, name: productTypeName, code: productTypeCode });
  };

  const handleRefresh = () => {
    if (!selectedProduct) return;
    setRefreshing(true);
    loadBindings(selectedProduct.id, true);
  };

  const handleAddBindings = () => {
    if (!selectedProduct) {
      Alert.alert('提示', '请先选择产品');
      return;
    }
    if (allWorkProcesses.length === 0) {
      Alert.alert('暂无可用工序', '请先到 "工序管理" 创建工序定义', [
        { text: '取消', style: 'cancel' },
        {
          text: '去新增',
          onPress: () => {
            (navigation as any).navigate('WorkProcessList');
          },
        },
      ]);
      return;
    }
    setPickerSearch('');
    setPickerVisible(true);
  };

  const handleRemoveBinding = (binding: ProductWorkProcess) => {
    Alert.alert(
      '移除工序',
      `确认从 "${selectedProduct?.name}" 中移除工序 "${binding.processName ?? binding.workProcessId}"?`,
      [
        { text: '取消', style: 'cancel' },
        {
          text: '移除',
          style: 'destructive',
          onPress: async () => {
            if (!factoryId || !selectedProduct) return;
            try {
              setActioning(true);
              await workProcessApiClient.deleteProductWorkProcess(binding.id, factoryId);
              await loadBindings(selectedProduct.id, true);
            } catch (error) {
              Alert.alert('错误', error instanceof Error ? error.message : '移除失败');
            } finally {
              setActioning(false);
            }
          },
        },
      ],
    );
  };

  const reorderAndPersist = async (newOrder: ProductWorkProcess[]) => {
    if (!factoryId || !selectedProduct) return;
    setActioning(true);
    try {
      // 重新分配 processOrder = index (0..N), 持久化
      const items = newOrder.map((b, index) => ({ id: b.id, processOrder: index }));
      await workProcessApiClient.batchSortProductWorkProcesses({ items }, factoryId);
      // 本地同步
      setBindings(
        newOrder.map((b, index) => ({ ...b, processOrder: index })),
      );
    } catch (error) {
      Alert.alert('错误', error instanceof Error ? error.message : '排序失败');
      // 失败重新拉一次
      await loadBindings(selectedProduct.id, true);
    } finally {
      setActioning(false);
    }
  };

  const handleMoveUp = (index: number) => {
    if (index <= 0) return;
    const next = [...bindings];
    const tmp = next[index - 1];
    next[index - 1] = next[index];
    next[index] = tmp;
    reorderAndPersist(next);
  };

  const handleMoveDown = (index: number) => {
    if (index >= bindings.length - 1) return;
    const next = [...bindings];
    const tmp = next[index + 1];
    next[index + 1] = next[index];
    next[index] = tmp;
    reorderAndPersist(next);
  };

  // Wave2 可配置报工粒度: 切换某道工序是否需报工 (true=逐道报默认; false=免报跳过 spawn)。
  const handleToggleReporting = async (binding: ProductWorkProcess, value: boolean) => {
    if (!selectedProduct || actioning) return;
    const factoryId = getFactoryId(user) ?? undefined;
    // 乐观更新本地, 失败回滚 + 重新拉取
    setBindings((prev) =>
      prev.map((b) => (b.id === binding.id ? { ...b, reportingRequired: value } : b)),
    );
    setActioning(true);
    try {
      await workProcessApiClient.updateProductWorkProcess(
        binding.id,
        { reportingRequired: value },
        factoryId,
      );
    } catch (e) {
      logger.error('切换报工粒度失败', e);
      await loadBindings(selectedProduct.id, true);
    } finally {
      setActioning(false);
    }
  };

  const boundIds = useMemo(
    () => new Set(bindings.map((b) => b.workProcessId)),
    [bindings],
  );

  const availableForPicker = useMemo(() => {
    const filtered = allWorkProcesses.filter((wp) => !boundIds.has(wp.id));
    if (!pickerSearch.trim()) return filtered;
    const q = pickerSearch.trim().toLowerCase();
    return filtered.filter(
      (wp) =>
        wp.processName.toLowerCase().includes(q) ||
        (wp.processCategory ?? '').toLowerCase().includes(q),
    );
  }, [allWorkProcesses, boundIds, pickerSearch]);

  const handlePickWorkProcess = async (wp: WorkProcess) => {
    if (!factoryId || !selectedProduct) return;
    try {
      setActioning(true);
      const nextOrder = bindings.length; // append at end
      await workProcessApiClient.createProductWorkProcess(
        {
          productTypeId: selectedProduct.id,
          workProcessId: wp.id,
          processOrder: nextOrder,
        },
        factoryId,
      );
      setPickerVisible(false);
      await loadBindings(selectedProduct.id, true);
    } catch (error) {
      Alert.alert('错误', error instanceof Error ? error.message : '添加失败');
    } finally {
      setActioning(false);
    }
  };

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      <Appbar.Header>
        <Appbar.BackAction onPress={() => navigation.goBack()} />
        <Appbar.Content
          title="产品工序配置"
          subtitle="给产品绑定工序流程, 生产计划自动带入"
        />
      </Appbar.Header>

      <View style={styles.productSelectorRow}>
        <ProductTypeSelector
          label="产品"
          placeholder="请选择产品"
          value={selectedProduct?.name ?? ''}
          onSelect={handleSelectProduct}
        />
      </View>

      {!selectedProduct ? (
        <View style={styles.centerArea}>
          <Text variant="bodyLarge" style={styles.placeholderText}>
            请先选择产品
          </Text>
          <Text variant="bodySmall" style={styles.placeholderHint}>
            示例: 选择 "猪蹄" → 绑定 [拆包, 分割, 卤制, 分切, 装筐]
          </Text>
        </View>
      ) : loading ? (
        <View style={styles.centerArea}>
          <ActivityIndicator size="large" />
        </View>
      ) : (
        <FlatList
          data={bindings}
          keyExtractor={(item) => String(item.id)}
          contentContainerStyle={bindings.length === 0 ? styles.emptyList : styles.list}
          refreshControl={
            <RefreshControl refreshing={refreshing} onRefresh={handleRefresh} />
          }
          ListHeaderComponent={
            <View style={styles.headerArea}>
              <Text variant="titleMedium" style={styles.productTitle}>
                {selectedProduct.name}
              </Text>
              {selectedProduct.code ? (
                <Text variant="bodySmall" style={styles.productCode}>
                  编码: {selectedProduct.code}
                </Text>
              ) : null}
              <Text variant="bodySmall" style={styles.bindingsSummary}>
                已绑定 {bindings.length} 道工序 (按下方顺序执行)
              </Text>
            </View>
          }
          renderItem={({ item, index }) => (
            <Card style={styles.bindingCard}>
              <Card.Content>
                <View style={styles.bindingRow}>
                  <View style={styles.orderBadge}>
                    <Text style={styles.orderBadgeText}>{index + 1}</Text>
                  </View>
                  <View style={styles.bindingInfo}>
                    <Text variant="titleSmall" style={styles.bindingName}>
                      {item.processName ?? item.workProcessId}
                    </Text>
                    <View style={styles.chipRow}>
                      {item.processCategory ? (
                        <Chip compact style={styles.chip} textStyle={styles.chipText}>
                          {item.processCategory}
                        </Chip>
                      ) : null}
                      <Chip compact style={styles.chip} textStyle={styles.chipText}>
                        单位: {item.unitOverride || item.defaultUnit || '-'}
                      </Chip>
                      <Chip compact style={styles.chip} textStyle={styles.chipText}>
                        工时:{' '}
                        {item.estimatedMinutesOverride !== undefined &&
                        item.estimatedMinutesOverride !== null
                          ? `${item.estimatedMinutesOverride} 分`
                          : item.defaultEstimatedMinutes !== undefined &&
                              item.defaultEstimatedMinutes !== null
                            ? `${item.defaultEstimatedMinutes} 分`
                            : '-'}
                      </Chip>
                    </View>
                    {/* Wave2 可配置报工粒度: 该工序是否需报工 (开=逐道报默认; 关=免报, 仅留配置不生成报工任务) */}
                    <View style={styles.reportingRow}>
                      <Text variant="bodySmall" style={styles.reportingLabel}>
                        {item.reportingRequired === false ? '免报 (不生成报工任务)' : '需报工'}
                      </Text>
                      <Switch
                        value={item.reportingRequired !== false}
                        disabled={actioning}
                        onValueChange={(val) => handleToggleReporting(item, val)}
                      />
                    </View>
                  </View>
                  <View style={styles.bindingActions}>
                    <IconButton
                      icon="arrow-up"
                      size={20}
                      disabled={index === 0 || actioning}
                      onPress={() => handleMoveUp(index)}
                      accessibilityLabel="上移"
                    />
                    <IconButton
                      icon="arrow-down"
                      size={20}
                      disabled={index === bindings.length - 1 || actioning}
                      onPress={() => handleMoveDown(index)}
                      accessibilityLabel="下移"
                    />
                    <IconButton
                      icon="delete-outline"
                      size={20}
                      disabled={actioning}
                      onPress={() => handleRemoveBinding(item)}
                      accessibilityLabel="移除"
                    />
                  </View>
                </View>
              </Card.Content>
            </Card>
          )}
          ListEmptyComponent={
            <View style={styles.emptyArea}>
              <Text variant="bodyLarge" style={styles.placeholderText}>
                该产品还没有绑定工序
              </Text>
              <Text variant="bodySmall" style={styles.placeholderHint}>
                点击下方 "添加工序" 选择已定义的工序绑定到此产品
              </Text>
            </View>
          }
          ListFooterComponent={
            <Button
              icon="plus"
              mode="contained-tonal"
              onPress={handleAddBindings}
              style={styles.addBtn}
              disabled={actioning}
            >
              添加工序
            </Button>
          }
        />
      )}

      {/* 选择工序 Modal */}
      <Modal
        visible={pickerVisible}
        animationType="slide"
        transparent
        onRequestClose={() => setPickerVisible(false)}
      >
        <Pressable style={styles.modalOverlay} onPress={() => setPickerVisible(false)}>
          <Pressable style={styles.modalContent} onPress={() => {}}>
            <Text variant="titleMedium" style={styles.modalTitle}>
              选择工序
            </Text>
            <Searchbar
              placeholder="搜索工序名称或类别"
              value={pickerSearch}
              onChangeText={setPickerSearch}
              style={styles.searchbar}
            />
            <FlatList
              data={availableForPicker}
              keyExtractor={(item) => item.id}
              ItemSeparatorComponent={() => <Divider />}
              renderItem={({ item }) => (
                <List.Item
                  title={item.processName}
                  description={`${item.processCategory ?? '-'} · 单位 ${item.unit} · ${
                    item.estimatedMinutes !== undefined && item.estimatedMinutes !== null
                      ? `${item.estimatedMinutes} 分`
                      : '工时未设'
                  }`}
                  onPress={() => handlePickWorkProcess(item)}
                  left={(props) => <List.Icon {...props} icon="cog-outline" />}
                />
              )}
              ListEmptyComponent={
                <View style={styles.modalEmpty}>
                  <Text variant="bodyMedium" style={styles.placeholderText}>
                    没有可添加的工序
                  </Text>
                  <Text variant="bodySmall" style={styles.placeholderHint}>
                    {boundIds.size > 0
                      ? '当前可选工序已全部绑定, 或先到 "工序管理" 新增工序'
                      : '先到 "工序管理" 创建工序定义'}
                  </Text>
                </View>
              }
            />
            <Button mode="text" onPress={() => setPickerVisible(false)} style={styles.modalCloseBtn}>
              关闭
            </Button>
          </Pressable>
        </Pressable>
      </Modal>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f5f5f5',
  },
  productSelectorRow: {
    padding: 12,
    backgroundColor: '#fff',
  },
  centerArea: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    padding: 32,
  },
  placeholderText: {
    color: '#666',
    textAlign: 'center',
  },
  placeholderHint: {
    marginTop: 6,
    color: '#999',
    textAlign: 'center',
  },
  list: {
    padding: 12,
    paddingBottom: 32,
  },
  emptyList: {
    padding: 12,
  },
  emptyArea: {
    padding: 32,
    alignItems: 'center',
  },
  headerArea: {
    marginBottom: 12,
  },
  productTitle: {
    fontWeight: '600',
  },
  productCode: {
    color: '#888',
    marginTop: 2,
  },
  bindingsSummary: {
    marginTop: 6,
    color: '#666',
  },
  bindingCard: {
    marginBottom: 8,
  },
  bindingRow: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  orderBadge: {
    width: 32,
    height: 32,
    borderRadius: 16,
    backgroundColor: '#1976d2',
    justifyContent: 'center',
    alignItems: 'center',
    marginRight: 12,
  },
  orderBadgeText: {
    color: '#fff',
    fontWeight: '700',
  },
  bindingInfo: {
    flex: 1,
  },
  bindingName: {
    fontWeight: '600',
  },
  chipRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 4,
    marginTop: 4,
  },
  chip: {
    height: 22,
    backgroundColor: '#f0f0f0',
  },
  chipText: {
    fontSize: 10,
    lineHeight: 12,
  },
  reportingRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginTop: 6,
  },
  reportingLabel: {
    color: '#666',
  },
  bindingActions: {
    flexDirection: 'row',
  },
  addBtn: {
    marginTop: 8,
  },
  modalOverlay: {
    flex: 1,
    backgroundColor: 'rgba(0, 0, 0, 0.5)',
    justifyContent: 'flex-end',
  },
  modalContent: {
    backgroundColor: '#fff',
    borderTopLeftRadius: 16,
    borderTopRightRadius: 16,
    padding: 16,
    maxHeight: '80%',
  },
  modalTitle: {
    marginBottom: 12,
    fontWeight: '600',
  },
  searchbar: {
    marginBottom: 12,
  },
  modalEmpty: {
    padding: 32,
    alignItems: 'center',
  },
  modalCloseBtn: {
    marginTop: 12,
  },
});
