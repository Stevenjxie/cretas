/**
 * BatchSelectorModal — 通用批次选择弹窗 (SP7 报损防呆)
 *
 * 用途: 替代手输批次号文本框, 以身份信息(物料名/批次号/可用量)展示让操作员点选.
 * 设计 (fool-proof-design 规范 Rule 2: 上下文必带身份信息):
 *   - 每行显示: 物料名 + 批次号 + 剩余量
 *   - 点选即回填, 无需手动输入
 *   - 支持按名称模糊搜索 (操作员可叫出同事帮查)
 *
 * 数据源: materialBatchApiClient.getBatchesByStatus('AVAILABLE')
 *         (复用领料选批次的同一个 API, 不新造 fetch)
 */

import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  FlatList,
  Modal,
  StyleSheet,
  TextInput,
  TouchableOpacity,
  View,
} from 'react-native';
import { Text } from 'react-native-paper';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import {
  MaterialBatch,
  materialBatchApiClient,
} from '../../services/api/materialBatchApiClient';

export interface BatchSelection {
  materialBatchId: string;
  batchNumber: string;
  materialName: string;
  remainingQuantity: number;
  /** 批次的 unit (来自 materialBatch, 报损数量单位参考) */
  unit?: string;
}

interface BatchSelectorModalProps {
  visible: boolean;
  onSelect: (selection: BatchSelection) => void;
  onDismiss: () => void;
  factoryId?: string;
}

const PRIMARY = '#1890FF';

export function BatchSelectorModal({
  visible,
  onSelect,
  onDismiss,
  factoryId,
}: BatchSelectorModalProps): React.JSX.Element {
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [batches, setBatches] = useState<MaterialBatch[]>([]);
  const [searchText, setSearchText] = useState('');
  const loadedRef = useRef(false);

  const loadBatches = useCallback(async () => {
    setLoading(true);
    setLoadError(null);
    try {
      const res = await materialBatchApiClient.getBatchesByStatus('AVAILABLE', factoryId);
      if (res.success && Array.isArray(res.data)) {
        setBatches(res.data);
      } else {
        setLoadError(res.message || '无法加载批次列表');
      }
    } catch (err) {
      setLoadError(err instanceof Error ? err.message : '加载批次失败');
    } finally {
      setLoading(false);
    }
  }, [factoryId]);

  // 每次 Modal 打开时重新加载 (确保数据新鲜)
  useEffect(() => {
    if (visible) {
      setSearchText('');
      loadedRef.current = false;
      loadBatches();
    }
  }, [visible, loadBatches]);

  // 搜索过滤: 物料名 或 批次号 模糊匹配
  const filteredBatches = batches.filter((b) => {
    if (!searchText.trim()) return true;
    const q = searchText.trim().toLowerCase();
    return (
      (b.materialName ?? '').toLowerCase().includes(q) ||
      b.batchNumber.toLowerCase().includes(q)
    );
  });

  const handleSelect = useCallback(
    (batch: MaterialBatch) => {
      onSelect({
        materialBatchId: batch.id,
        batchNumber: batch.batchNumber,
        materialName: batch.materialName ?? batch.batchNumber,
        remainingQuantity: batch.remainingQuantity,
      });
    },
    [onSelect],
  );

  return (
    <Modal
      visible={visible}
      transparent
      animationType="slide"
      onRequestClose={onDismiss}
    >
      <View style={styles.overlay}>
        <View style={styles.sheet}>
          {/* 标题栏 */}
          <View style={styles.titleBar}>
            <Text style={styles.title}>选择批次</Text>
            <TouchableOpacity
              onPress={onDismiss}
              style={styles.closeBtn}
              hitSlop={{ top: 8, right: 8, bottom: 8, left: 8 }}
              accessibilityLabel="关闭批次选择"
            >
              <MaterialCommunityIcons name="close" size={22} color="#555" />
            </TouchableOpacity>
          </View>

          {/* 搜索框 */}
          <View style={styles.searchBox}>
            <MaterialCommunityIcons name="magnify" size={18} color="#aaa" style={styles.searchIcon} />
            <TextInput
              style={styles.searchInput}
              value={searchText}
              onChangeText={setSearchText}
              placeholder="搜索物料名或批次号..."
              placeholderTextColor="#bbb"
              returnKeyType="search"
              autoCorrect={false}
              autoCapitalize="none"
            />
            {searchText.length > 0 && (
              <TouchableOpacity onPress={() => setSearchText('')} hitSlop={{ top: 6, right: 6, bottom: 6, left: 6 }}>
                <MaterialCommunityIcons name="close-circle" size={16} color="#bbb" />
              </TouchableOpacity>
            )}
          </View>

          {/* 列表主体 */}
          {loading ? (
            <View style={styles.center}>
              <ActivityIndicator size="large" color={PRIMARY} />
              <Text style={styles.centerText}>加载中...</Text>
            </View>
          ) : loadError != null ? (
            <View style={styles.center}>
              <Text style={styles.errorText}>{loadError}</Text>
              <TouchableOpacity onPress={loadBatches} style={styles.retryBtn}>
                <Text style={styles.retryText}>重试</Text>
              </TouchableOpacity>
            </View>
          ) : filteredBatches.length === 0 ? (
            <View style={styles.center}>
              <Text style={styles.centerText}>
                {searchText.trim() ? '没有匹配的批次' : '暂无可用批次'}
              </Text>
            </View>
          ) : (
            <FlatList
              data={filteredBatches}
              keyExtractor={(item) => item.id}
              contentContainerStyle={styles.listContent}
              keyboardShouldPersistTaps="handled"
              renderItem={({ item }) => (
                <TouchableOpacity
                  style={styles.batchRow}
                  onPress={() => handleSelect(item)}
                  activeOpacity={0.7}
                  accessibilityLabel={`选择批次 ${item.batchNumber} ${item.materialName ?? ''} 剩余 ${item.remainingQuantity}`}
                >
                  {/* 左侧身份区 — fool-proof Rule 2: 物料名+批次号+剩余量 */}
                  <View style={styles.batchMain}>
                    <Text style={styles.batchMaterialName} numberOfLines={1}>
                      {item.materialName ?? '—'}
                    </Text>
                    <Text style={styles.batchNumber}>{item.batchNumber}</Text>
                  </View>
                  {/* 右侧剩余量 */}
                  <View style={styles.batchRight}>
                    <Text style={styles.remainQty}>{item.remainingQuantity}</Text>
                    <Text style={styles.remainUnit}>可用</Text>
                  </View>
                  {/* 右箭头 */}
                  <MaterialCommunityIcons name="chevron-right" size={20} color="#ccc" />
                </TouchableOpacity>
              )}
              ItemSeparatorComponent={() => <View style={styles.separator} />}
            />
          )}
        </View>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  overlay: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.45)',
    justifyContent: 'flex-end',
  },
  sheet: {
    backgroundColor: '#fff',
    borderTopLeftRadius: 16,
    borderTopRightRadius: 16,
    maxHeight: '75%',
    paddingBottom: 32,
  },
  titleBar: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 20,
    paddingTop: 18,
    paddingBottom: 12,
    borderBottomWidth: 1,
    borderBottomColor: '#f0f0f0',
  },
  title: { fontSize: 17, fontWeight: '700', color: '#111' },
  closeBtn: { padding: 4 },
  searchBox: {
    flexDirection: 'row',
    alignItems: 'center',
    marginHorizontal: 16,
    marginVertical: 10,
    paddingHorizontal: 12,
    paddingVertical: 8,
    backgroundColor: '#f5f5f5',
    borderRadius: 10,
    borderWidth: 1,
    borderColor: '#e8e8e8',
  },
  searchIcon: { marginRight: 6 },
  searchInput: {
    flex: 1,
    fontSize: 15,
    color: '#111',
    paddingVertical: 0,
  },
  center: {
    alignItems: 'center',
    justifyContent: 'center',
    paddingVertical: 48,
    gap: 12,
  },
  centerText: { fontSize: 14, color: '#999' },
  errorText: { fontSize: 14, color: '#FF4D4F', textAlign: 'center', paddingHorizontal: 24 },
  retryBtn: {
    paddingHorizontal: 20,
    paddingVertical: 8,
    backgroundColor: '#E6F0FF',
    borderRadius: 8,
  },
  retryText: { fontSize: 14, color: PRIMARY, fontWeight: '600' },
  listContent: { paddingHorizontal: 16, paddingTop: 4 },
  batchRow: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 14,
    paddingHorizontal: 4,
    minHeight: 56, // 触摸目标 ≥ 44pt
  },
  batchMain: { flex: 1, marginRight: 8 },
  batchMaterialName: { fontSize: 15, fontWeight: '600', color: '#111' },
  batchNumber: { fontSize: 13, color: '#888', marginTop: 2 },
  batchRight: { alignItems: 'flex-end', marginRight: 4 },
  remainQty: { fontSize: 16, fontWeight: '700', color: PRIMARY },
  remainUnit: { fontSize: 11, color: '#999', marginTop: 1 },
  separator: { height: 1, backgroundColor: '#f5f5f5', marginLeft: 4 },
});

export default BatchSelectorModal;
