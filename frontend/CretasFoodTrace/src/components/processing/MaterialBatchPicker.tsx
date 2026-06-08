/**
 * MaterialBatchPicker — 首道领料批次选择器 (A2b)
 *
 * 用途: 让操作员选择本次领料的原料批次 (1 or N) + 每批次用量, 生成 materialBatchRefs 数组.
 * 调用方: YieldStepReportScreen (currentStepIndex === 0, 首道).
 *
 * ── 单一数据源规则 (Q1) ─────────────────────────────────────────────────────
 * 单批次已选时: 无独立的 per-batch 用量输入。该批次的 quantity = 屏幕的 inputQty
 * (由 singleBatchQty prop 传入)。屏幕的 YieldQuantityInput "投入量" IS 用量。
 * 多批次已选时: 每批显示独立用量输入；投入量 = Σ(各批次用量)，在屏幕层只读展示。
 * 效果：两字段永远不会产生分歧。
 * ────────────────────────────────────────────────────────────────────────────
 */
import React, { useCallback, useEffect, useRef, useState } from 'react';
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
  /**
   * Q1 单一数据源: 单批次已选时, 该批次的 quantity 由此 prop 驱动 (= 屏幕投入量).
   * 仅在单批次模式生效; 多批次模式下忽略 (各批次用独立输入).
   * 屏幕在 onChange 返回的 refs 里 quantity 若是 single-batch 会自动同步最新 singleBatchQty.
   */
  singleBatchQty?: string;
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
  qtyStr: string; // 仅多批次模式使用; 单批次模式下忽略 (由 singleBatchQty 驱动)
}

export const MaterialBatchPicker: React.FC<MaterialBatchPickerProps> = ({
  unit,
  factoryId,
  value,
  onChange,
  singleBatchQty = '',
  disabled = false,
  required = false,
}) => {
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [rows, setRows] = useState<RowState[]>([]);
  const [expanded, setExpanded] = useState(false);
  // Q2: picker 已通过「确定选择」按钮确认收起 → 显示 summary + 确认反馈
  const [confirmed, setConfirmed] = useState(false);
  const [confirmFeedback, setConfirmFeedback] = useState(false);
  const confirmTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const selectedRows = rows.filter((r) => r.selected);
  const isMultiBatch = selectedRows.length > 1;

  // ── onChange 防抖机制 ────────────────────────────────────────────────────
  // Issue #3 fix: emit onChange in a useEffect keyed on rows, NOT inside setRows updater.
  // Calling parent setState (onChange → setMaterialBatchRefs) from inside setRows updater
  // triggers "Cannot update a component while rendering" warning.
  const onChangeRef = useRef(onChange);
  onChangeRef.current = onChange;

  // Q1: 每当 rows / singleBatchQty / unit 变化时重新 emit refs
  // 单批次: quantity = singleBatchQty (父的 投入量 即用量, 单一真相)
  // 多批次: quantity = 各自独立输入的 qtyStr
  useEffect(() => {
    const refs: MaterialBatchRef[] = [];
    const selected = rows.filter((r) => r.selected);
    const isSingle = selected.length === 1;

    for (const row of selected) {
      if (isSingle) {
        // 单批次: qty 由屏幕 投入量 驱动
        const qty = parseFloat(singleBatchQty);
        if (!Number.isNaN(qty) && qty > 0) {
          refs.push({ materialBatchId: row.batch.id, quantity: qty, unit });
        }
      } else {
        // 多批次: 使用各自 qtyStr
        const qty = parseFloat(row.qtyStr);
        if (!Number.isNaN(qty) && qty > 0) {
          refs.push({ materialBatchId: row.batch.id, quantity: qty, unit });
        }
      }
    }
    onChangeRef.current(refs);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rows, singleBatchQty, unit]);

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

  // Q2: 清理定时器
  useEffect(() => {
    return () => {
      if (confirmTimerRef.current) clearTimeout(confirmTimerRef.current);
    };
  }, []);

  const toggleRow = useCallback(
    (idx: number) => {
      // 切换选中时清除 confirmed 状态 (用户在重新选)
      setConfirmed(false);
      setConfirmFeedback(false);
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
      // 只允许数字 + 小数点 (仅多批次模式使用)
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

  // Q2: 「确定选择」按钮点击: 校验 → 折叠 + 显示确认反馈
  const handleConfirm = useCallback(() => {
    const selected = rows.filter((r) => r.selected);
    if (selected.length === 0) {
      // 不满足最少选 1 批
      return;
    }
    const isSingle = selected.length === 1;
    if (isSingle) {
      const qty = parseFloat(singleBatchQty);
      if (Number.isNaN(qty) || qty <= 0) {
        // 单批次 qty 来自屏幕 投入量, 为空时提示先填投入量
        return;
      }
    } else {
      // 多批次: 每批 qtyStr 都要有效
      const anyInvalid = selected.some((r) => {
        const q = parseFloat(r.qtyStr);
        return Number.isNaN(q) || q <= 0;
      });
      if (anyInvalid) return;
    }

    setConfirmed(true);
    setExpanded(false);
    // 短暂反馈 (1.5s 后消失)
    setConfirmFeedback(true);
    if (confirmTimerRef.current) clearTimeout(confirmTimerRef.current);
    confirmTimerRef.current = setTimeout(() => {
      setConfirmFeedback(false);
    }, 1500);
  }, [rows, singleBatchQty]);

  // Q2 验证按钮是否可以点击
  const canConfirm = useCallback((): boolean => {
    const selected = rows.filter((r) => r.selected);
    if (selected.length === 0) return false;
    const isSingle = selected.length === 1;
    if (isSingle) {
      const qty = parseFloat(singleBatchQty);
      return !Number.isNaN(qty) && qty > 0;
    }
    return selected.every((r) => {
      const q = parseFloat(r.qtyStr);
      return !Number.isNaN(q) && q > 0;
    });
  }, [rows, singleBatchQty]);

  const selectedCount = value.length;

  // Q3: 折叠摘要文案 (已确认后显示)
  const collapsedSummary = (() => {
    if (selectedCount === 0) return null;
    const isSingle = selectedCount === 1;
    const firstRef = value[0];
    if (isSingle && firstRef) {
      const row = rows.find((r) => r.batch.id === firstRef.materialBatchId);
      const name = row?.batch.materialName ?? row?.batch.batchNumber ?? '—';
      const qty = firstRef.quantity;
      return `已选 ${name} · 用量 ${qty} ${unit}`;
    }
    const total = value.reduce((s, r) => s + r.quantity, 0);
    return `已选 ${selectedCount} 批次 · 合计 ${total.toFixed(2)} ${unit}`;
  })();

  return (
    <View style={styles.wrap}>
      {/* 折叠 header */}
      <TouchableOpacity
        style={styles.header}
        onPress={() => {
          setExpanded((e: boolean) => !e);
          // 重新展开时清除 confirmed 状态, 让用户重新确认
          if (!expanded) setConfirmed(false);
        }}
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

      {/* Q2 + Q3: 已确认折叠后显示摘要行 + 「修改」按钮 */}
      {!expanded && confirmed && collapsedSummary ? (
        <View style={styles.summaryRow}>
          <Text style={styles.summaryText} numberOfLines={1}>{collapsedSummary}</Text>
          <TouchableOpacity
            onPress={() => {
              setExpanded(true);
              setConfirmed(false);
            }}
            disabled={disabled}
            style={styles.modifyBtn}
            accessibilityLabel="修改领料批次"
          >
            <Text style={styles.modifyText}>修改</Text>
          </TouchableOpacity>
        </View>
      ) : null}

      {/* Q2: 已确认反馈 (短暂) */}
      {confirmFeedback ? (
        <View style={styles.feedbackRow}>
          <Text style={styles.feedbackText}>已确认 ✓</Text>
        </View>
      ) : null}

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

                  {/*
                   * Q1 单一数据源:
                   * 单批次已选 → 不显示 per-batch 用量输入，qty 由屏幕「投入量」驱动
                   * 多批次已选 → 显示各自用量输入
                   */}
                  {row.selected && isMultiBatch ? (
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
                  ) : row.selected && !isMultiBatch ? (
                    // 单批次: 显示提示, 投入量由屏幕控制
                    <View style={styles.singleQtyHint}>
                      <Text style={styles.singleQtyHintText}>用量 = 投入量</Text>
                    </View>
                  ) : null}
                </View>
              ))}
            </ScrollView>
          )}

          {/* 多批次合计 */}
          {selectedCount > 0 && isMultiBatch ? (
            <View style={styles.selectedHint}>
              <Text style={styles.selectedHintText}>
                已选 {selectedCount} 批次 · 合计领料{' '}
                {value.reduce((s: number, r: MaterialBatchRef) => s + r.quantity, 0).toFixed(2)} {unit}
              </Text>
            </View>
          ) : null}

          {/* Q1: 单批次时提示投入量即用量 */}
          {selectedCount === 1 && !isMultiBatch ? (
            <View style={styles.singleBatchNote}>
              <Text style={styles.singleBatchNoteText}>
                单批次: 投入量即该批次的领用量，请在下方「投入量」填写
              </Text>
            </View>
          ) : null}

          {/* Q2: 「确定选择」按钮 */}
          {selectedCount > 0 ? (
            <TouchableOpacity
              style={[styles.confirmBtn, !canConfirm() && styles.confirmBtnDisabled]}
              onPress={handleConfirm}
              disabled={disabled || !canConfirm()}
              accessibilityLabel="确定选择领料批次"
            >
              <Text style={[styles.confirmBtnText, !canConfirm() && styles.confirmBtnTextDisabled]}>
                {isMultiBatch
                  ? (canConfirm() ? '确定选择' : '请为每批次填写用量')
                  : (canConfirm() ? '确定选择' : '请先在下方填写投入量')}
              </Text>
            </TouchableOpacity>
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

  // Q3: 折叠后摘要行
  summaryRow: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    paddingHorizontal: 14, paddingVertical: 8,
    borderTopWidth: 1, borderTopColor: '#EBEEF5',
    backgroundColor: '#FFF7F0',
  },
  summaryText: { flex: 1, fontSize: 13, color: '#E8732E', fontWeight: '600' },
  modifyBtn: {
    paddingHorizontal: 12, paddingVertical: 4,
    backgroundColor: '#FFFFFF', borderWidth: 1, borderColor: '#E8732E', borderRadius: 12,
    marginLeft: 8,
  },
  modifyText: { fontSize: 13, color: '#E8732E', fontWeight: '600' },

  // Q2: 已确认反馈 (短暂)
  feedbackRow: {
    paddingHorizontal: 14, paddingVertical: 6,
    borderTopWidth: 1, borderTopColor: '#EBEEF5',
    backgroundColor: '#F0F9EB',
  },
  feedbackText: { fontSize: 13, color: '#67C23A', fontWeight: '600', textAlign: 'center' },

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

  // Q1: 单批次时 per-row 提示
  singleQtyHint: {
    paddingHorizontal: 8, paddingVertical: 4,
    backgroundColor: '#F0F9EB', borderRadius: 6,
  },
  singleQtyHintText: { fontSize: 12, color: '#67C23A', fontWeight: '500' },

  selectedHint: {
    backgroundColor: '#F0F9EB', borderRadius: 6, paddingHorizontal: 10, paddingVertical: 6, marginTop: 8,
  },
  selectedHintText: { fontSize: 13, color: '#67C23A', fontWeight: '500' },

  // Q1: 单批次提示
  singleBatchNote: {
    backgroundColor: '#ECF5FF', borderRadius: 6, paddingHorizontal: 10, paddingVertical: 6, marginTop: 8,
  },
  singleBatchNoteText: { fontSize: 12, color: '#409EFF' },

  // Q2: 确定选择按钮
  confirmBtn: {
    marginTop: 12, height: 44, borderRadius: 8,
    backgroundColor: '#E8732E',
    alignItems: 'center', justifyContent: 'center',
  },
  confirmBtnDisabled: { backgroundColor: '#F5F7FA', borderWidth: 1, borderColor: '#DCDFE6' },
  confirmBtnText: { fontSize: 15, fontWeight: '700', color: '#FFFFFF' },
  confirmBtnTextDisabled: { color: '#C0C4CC' },
});

export default MaterialBatchPicker;
