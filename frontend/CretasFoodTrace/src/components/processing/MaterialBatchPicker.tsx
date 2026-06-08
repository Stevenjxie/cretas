/**
 * MaterialBatchPicker — 首道领料批次选择器 (A2b)
 *
 * 用途: 让操作员选择本次领料的原料批次 (1 or N) + 每批次用量, 生成 materialBatchRefs 数组.
 * 调用方: YieldStepReportScreen (currentStepIndex === 0, 首道).
 */
import React, { useCallback, useEffect, useState } from 'react';
import {
  View,
  Text,
  TextInput,
  TouchableOpacity,
  ActivityIndicator,
  StyleSheet,
  ScrollView,
} from 'react-native';
import { materialBatchApiClient, MaterialBatch } from '../../services/api/materialBatchApiClient';

export interface MaterialBatchRef {
  materialBatchId: string;
  quantity: number;
  unit?: string;
}

interface MaterialBatchPickerProps {
  /** 当前工序单位 (e.g. 'kg'), 作为 ref.unit 默认值 */
  unit: string;
  /** 当前工厂 ID (可选, 不传则 materialBatchApiClient 自动解析) */
  factoryId?: string;
  /** 选中的 refs (受控) */
  value: MaterialBatchRef[];
  /** 变更回调 */
  onChange: (refs: MaterialBatchRef[]) => void;
  /** 是否禁用 (提交进行中) */
  disabled?: boolean;
  /**
   * B1: When true the header shows a red asterisk (领料批次 *) to signal this field
   * is required. Caller (YieldStepReportScreen first-step) passes required={true}.
   */
  required?: boolean;
}

/** 组件内部行状态 */
interface RowState {
  batch: MaterialBatch;
  selected: boolean;
  qtyStr: string; // string 便于 TextInput
}

export const MaterialBatchPicker: React.FC<MaterialBatchPickerProps> = ({
  unit,
  factoryId,
  value,
  onChange,
  disabled = false,
  required = false,
}) => {
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [rows, setRows] = useState<RowState[]>([]);
  const [expanded, setExpanded] = useState(false);

  // Issue #3 fix: emit onChange in a useEffect keyed on rows, NOT inside setRows updater.
  // Calling parent setState (onChange → setMaterialBatchRefs) from inside setRows updater
  // triggers "Cannot update a component while rendering" warning.
  const onChangeRef = React.useRef(onChange);
  onChangeRef.current = onChange;
  useEffect(() => {
    const refs: MaterialBatchRef[] = [];
    for (const row of rows) {
      if (!row.selected) continue;
      const qty = parseFloat(row.qtyStr);
      if (!Number.isNaN(qty) && qty > 0) {
        refs.push({ materialBatchId: row.batch.id, quantity: qty, unit });
      }
    }
    onChangeRef.current(refs);
    // unit is intentionally included: if the picker unit changes, re-emit with new unit
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rows, unit]);

  // 从 value prop 初始化/同步 rows (仅在 batches 加载完成后)
  const syncRowsFromValue = useCallback(
    (batches: MaterialBatch[]) => {
      const refMap = new Map(value.map((r: MaterialBatchRef) => [r.materialBatchId, r]));
      setRows(
        batches.map((b) => {
          const existing = refMap.get(b.id);
          return {
            batch: b,
            selected: existing != null,
            qtyStr: existing != null ? String(existing.quantity) : '',
          };
        }),
      );
    },
    [value],
  );

  // 加载 AVAILABLE 批次
  const loadBatches = useCallback(async () => {
    setLoading(true);
    setLoadError(null);
    try {
      const res = await materialBatchApiClient.getBatchesByStatus('AVAILABLE', factoryId);
      if (res.success && Array.isArray(res.data)) {
        syncRowsFromValue(res.data);
      } else {
        setLoadError(res.message || '无法加载原料批次');
      }
    } catch (err) {
      const msg = err instanceof Error ? err.message : '加载批次失败';
      setLoadError(msg);
    } finally {
      setLoading(false);
    }
  }, [factoryId, syncRowsFromValue]);

  // 展开时加载
  useEffect(() => {
    if (expanded && rows.length === 0 && !loading) {
      loadBatches();
    }
  }, [expanded, rows.length, loading, loadBatches]);

  const toggleRow = useCallback(
    (idx: number) => {
      setRows((prev: RowState[]) =>
        prev.map((r: RowState, i: number) =>
          i === idx ? { ...r, selected: !r.selected, qtyStr: !r.selected ? '' : r.qtyStr } : r,
        ),
      );
    },
    [],
  );

  const setQty = useCallback(
    (idx: number, raw: string) => {
      // 只允许数字 + 小数点
      const cleaned = raw.replace(/[^0-9.]/g, '');
      const parts = cleaned.split('.');
      const normalized =
        parts.length > 2 ? `${parts[0]}.${parts.slice(1).join('')}` : cleaned;
      setRows((prev: RowState[]) =>
        prev.map((r: RowState, i: number) =>
          i === idx ? { ...r, qtyStr: normalized, selected: true } : r,
        ),
      );
    },
    [],
  );

  const selectedCount = value.length;

  return (
    <View style={styles.wrap}>
      {/* 折叠 header */}
      <TouchableOpacity
        style={styles.header}
        onPress={() => setExpanded((e: boolean) => !e)}
        disabled={disabled}
        accessibilityLabel="展开/折叠领料批次选择"
      >
        <Text style={styles.headerTitle}>
          {/* B1: asterisk when required and nothing selected yet; badge when batches chosen */}
          {'领料批次'}
          {selectedCount > 0 ? (
            <Text style={styles.badge}> · 已选 {selectedCount} 批</Text>
          ) : required ? (
            <Text style={styles.requiredMark}> *</Text>
          ) : (
            <Text style={styles.optional}> (可选)</Text>
          )}
        </Text>
        <Text style={styles.chevron}>{expanded ? '▲' : '▼'}</Text>
      </TouchableOpacity>

      {expanded ? (
        <View style={styles.body}>
          {loading ? (
            <View style={styles.center}>
              <ActivityIndicator size="small" color="#E8732E" />
              <Text style={styles.loadingText}>加载批次...</Text>
            </View>
          ) : loadError != null ? (
            <View style={styles.center}>
              <Text style={styles.errorText}>{loadError}</Text>
              <TouchableOpacity onPress={loadBatches} style={styles.retryBtn}>
                <Text style={styles.retryText}>重试</Text>
              </TouchableOpacity>
            </View>
          ) : rows.length === 0 ? (
            <Text style={styles.emptyText}>暂无可用原料批次</Text>
          ) : (
            // B2: removed scrollEnabled={false} — the 320dp clip was preventing scroll to lower batches
            <ScrollView style={styles.listScroll} nestedScrollEnabled>
              {rows.map((row: RowState, idx: number) => (
                <View
                  key={row.batch.id}
                  style={[styles.row, row.selected && styles.rowSelected]}
                >
                  {/* 复选框 */}
                  <TouchableOpacity
                    style={[styles.checkbox, row.selected && styles.checkboxChecked]}
                    onPress={() => !disabled && toggleRow(idx)}
                    accessibilityLabel={`选择批次 ${row.batch.batchNumber}`}
                  >
                    {row.selected ? <Text style={styles.checkmark}>✓</Text> : null}
                  </TouchableOpacity>

                  {/* 批次信息 */}
                  <View style={styles.batchInfo}>
                    <Text style={styles.batchNumber}>{row.batch.batchNumber}</Text>
                    {row.batch.materialName ? (
                      <Text style={styles.materialName}>{row.batch.materialName}</Text>
                    ) : null}
                    <Text style={styles.remaining}>
                      剩余 {row.batch.remainingQuantity} {unit}
                    </Text>
                  </View>

                  {/* 用量输入 (选中时显示) */}
                  {row.selected ? (
                    <View style={styles.qtyBox}>
                      <TextInput
                        style={[styles.qtyInput, disabled && styles.qtyInputDisabled]}
                        keyboardType="decimal-pad"
                        value={row.qtyStr}
                        onChangeText={(v: string) => setQty(idx, v)}
                        editable={!disabled}
                        placeholder="用量"
                        placeholderTextColor="#C0C4CC"
                        accessibilityLabel={`批次 ${row.batch.batchNumber} 用量`}
                      />
                      <Text style={styles.qtyUnit}>{unit}</Text>
                    </View>
                  ) : null}
                </View>
              ))}
            </ScrollView>
          )}

          {/* 选中提示 */}
          {selectedCount > 0 ? (
            <View style={styles.selectedHint}>
              <Text style={styles.selectedHintText}>
                已选 {selectedCount} 批次 · 合计领料{' '}
                {value.reduce((s: number, r: MaterialBatchRef) => s + r.quantity, 0).toFixed(2)} {unit}
              </Text>
            </View>
          ) : null}
        </View>
      ) : null}
    </View>
  );
};

const styles = StyleSheet.create({
  wrap: { marginTop: 8, marginBottom: 16, borderWidth: 1, borderColor: '#DCDFE6', borderRadius: 8 },
  header: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    paddingHorizontal: 14, paddingVertical: 12,
  },
  headerTitle: { fontSize: 15, fontWeight: '600', color: '#303133' },
  badge: { color: '#E8732E', fontWeight: '700' },
  optional: { color: '#909399', fontWeight: '400' },
  requiredMark: { color: '#F56C6C', fontWeight: '700' },
  chevron: { fontSize: 12, color: '#909399' },
  body: { borderTopWidth: 1, borderTopColor: '#EBEEF5', paddingHorizontal: 12, paddingTop: 10, paddingBottom: 6 },
  center: { alignItems: 'center', paddingVertical: 16 },
  loadingText: { marginTop: 8, fontSize: 13, color: '#909399' },
  errorText: { fontSize: 13, color: '#F56C6C', textAlign: 'center' },
  retryBtn: { marginTop: 8, paddingHorizontal: 16, paddingVertical: 6, backgroundColor: '#FFEFD5', borderRadius: 6 },
  retryText: { fontSize: 13, color: '#E8732E', fontWeight: '600' },
  emptyText: { fontSize: 13, color: '#909399', textAlign: 'center', paddingVertical: 12 },
  listScroll: { maxHeight: 320 },
  row: {
    flexDirection: 'row', alignItems: 'center',
    paddingVertical: 10, paddingHorizontal: 4,
    borderBottomWidth: 1, borderBottomColor: '#F2F6FC',
  },
  rowSelected: { backgroundColor: '#FFF7F0' },
  checkbox: {
    width: 22, height: 22, borderRadius: 4, borderWidth: 1.5, borderColor: '#DCDFE6',
    backgroundColor: '#FFFFFF', alignItems: 'center', justifyContent: 'center', marginRight: 10,
  },
  checkboxChecked: { borderColor: '#E8732E', backgroundColor: '#E8732E' },
  checkmark: { color: '#FFFFFF', fontSize: 13, fontWeight: '700' },
  batchInfo: { flex: 1, marginRight: 8 },
  batchNumber: { fontSize: 14, fontWeight: '600', color: '#303133' },
  materialName: { fontSize: 12, color: '#606266', marginTop: 1 },
  remaining: { fontSize: 12, color: '#909399', marginTop: 2 },
  qtyBox: { flexDirection: 'row', alignItems: 'center' },
  qtyInput: {
    width: 72, height: 38, borderWidth: 1, borderColor: '#DCDFE6', borderRadius: 6,
    paddingHorizontal: 8, fontSize: 16, fontWeight: '600', color: '#1A1A1A',
    backgroundColor: '#FFFFFF', textAlign: 'center',
  },
  qtyInputDisabled: { opacity: 0.5 },
  qtyUnit: { fontSize: 13, color: '#909399', marginLeft: 4 },
  selectedHint: {
    backgroundColor: '#F0F9EB', borderRadius: 6, paddingHorizontal: 10, paddingVertical: 6, marginTop: 8,
  },
  selectedHintText: { fontSize: 13, color: '#67C23A', fontWeight: '500' },
});

export default MaterialBatchPicker;
