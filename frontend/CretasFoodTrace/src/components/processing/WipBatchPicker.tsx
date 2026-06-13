/**
 * WipBatchPicker — 上道多笔半成品 (WIP) 领用批次选择器 (G7 部分领用 · F006 #5)
 *
 * 用途: 当上道工序产出了 >1 笔 AVAILABLE 半成品 (例如跨两天分两批产出 → 2 行 WIP),
 *       后端无法自动判定本道该领哪一笔 (limits.sourceWipNo = null, 但 wipAvailable > 0)。
 *       此时让操作工显式单选要领用的半成品批次, 选中后回传 intermediateBatchNo (= sourceWipNo)
 *       与该批次单位, 供报工单提交时扣减对应 WIP 余额。
 *
 * 调用方: YieldStepReportScreen (非首道 && limits.sourceWipNo == null && wipAvailable > 0)。
 *
 * 设计 (防呆, 低文化操作工友好):
 *  - 单选 (radio), 不是 MaterialBatchPicker 的多选 —— 一道只领一笔来源 WIP。
 *  - 默认展开 (非折叠), 因为这一步是必选的, 不让操作工漏看。
 *  - 每行: 工序批次号 · 工序名 · 可领 X单位; 选中行高亮。
 *  - 未选时父组件 disable 提交 + 显示"请选择要领用的半成品批次"。
 */
import React, { useCallback, useEffect, useState } from 'react';
import {
  View,
  Text,
  TouchableOpacity,
  ActivityIndicator,
  StyleSheet,
  ScrollView,
} from 'react-native';
import { yieldReportApi, WipRowDTO } from '../../services/api/yieldReportApi';

export interface WipSelection {
  /** 选中的工序批次号 = 报工 sourceWipNo */
  sourceWipNo: string;
  /** 该批次余额单位 (= 上道 outputUnit, 可能 ≠ 本道 unit), 供投入 :max 用 */
  unit: string | null;
  /** 该批次可领余额 (= availableQuantity), 供投入 :max 用 */
  availableQuantity: number | null;
}

interface WipBatchPickerProps {
  /** 当前批次 ID (传给 listWip) */
  batchId: number;
  /** 当前工厂 ID (可选, 不传则 yieldReportApi 自动解析) */
  factoryId?: string;
  /** 当前选中的 sourceWipNo (受控; null = 未选) */
  selectedSourceWipNo: string | null;
  /** 选中变更回调 (传 null 表示清空选择) */
  onChange: (sel: WipSelection | null) => void;
  /** 是否禁用 (提交进行中) */
  disabled?: boolean;
  /** 是否必须选择 WIP; false 时再次点击已选行会清空选择 */
  required?: boolean;
}

export const WipBatchPicker: React.FC<WipBatchPickerProps> = ({
  batchId,
  factoryId,
  selectedSourceWipNo,
  onChange,
  disabled = false,
  required = true,
}) => {
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [rows, setRows] = useState<WipRowDTO[]>([]);

  // 加载该批次 WIP, 过滤到 AVAILABLE && 余额>0 (服务端可能已过滤, 这里再保险一次)
  const loadWip = useCallback(async () => {
    setLoading(true);
    setLoadError(null);
    try {
      const res = await yieldReportApi.listWip(batchId, factoryId);
      if (res.success && Array.isArray(res.data)) {
        const available = res.data.filter(
          (w: WipRowDTO) =>
            w.status === 'AVAILABLE' && (w.availableQuantity ?? 0) > 0,
        );
        setRows(available);
      } else {
        setLoadError(res.message || '无法加载半成品批次');
      }
    } catch (err) {
      const msg = err instanceof Error ? err.message : '加载半成品批次失败';
      setLoadError(msg);
    } finally {
      setLoading(false);
    }
  }, [batchId, factoryId]);

  useEffect(() => {
    loadWip();
  }, [loadWip]);

  const selectRow = useCallback(
    (row: WipRowDTO) => {
      if (disabled) return;
      if (!required && row.intermediateBatchNo === selectedSourceWipNo) {
        onChange(null);
        return;
      }
      // required=true 时再次点击不取消; optional 模式下上方已处理清空。
      onChange({
        sourceWipNo: row.intermediateBatchNo,
        unit: row.unit,
        availableQuantity: row.availableQuantity,
      });
    },
    [disabled, onChange, required, selectedSourceWipNo],
  );

  return (
    <View style={styles.wrap}>
      <View style={styles.header}>
        <Text style={styles.headerTitle}>
          上道半成品有 {rows.length} 笔, {required ? '请选择本道领用哪一笔' : '可选择本道要领用的半成品'}
        </Text>
      </View>

      <View style={styles.body}>
        {loading ? (
          <View style={styles.center}>
            <ActivityIndicator size="small" color="#E8732E" />
            <Text style={styles.loadingText}>加载半成品批次...</Text>
          </View>
        ) : loadError != null ? (
          <View style={styles.center}>
            <Text style={styles.errorText}>{loadError}</Text>
            <TouchableOpacity onPress={loadWip} style={styles.retryBtn}>
              <Text style={styles.retryText}>重试</Text>
            </TouchableOpacity>
          </View>
        ) : rows.length === 0 ? (
          <Text style={styles.emptyText}>暂无可领半成品批次</Text>
        ) : (
          <ScrollView style={styles.listScroll} nestedScrollEnabled>
            {rows.map((row: WipRowDTO) => {
              const selected = row.intermediateBatchNo === selectedSourceWipNo;
              return (
                <TouchableOpacity
                  key={row.intermediateBatchNo}
                  style={[styles.row, selected && styles.rowSelected]}
                  onPress={() => selectRow(row)}
                  disabled={disabled}
                  accessibilityLabel={`选择半成品批次 ${row.intermediateBatchNo}`}
                  testID={`wip-row-${row.intermediateBatchNo}`}
                >
                  {/* 单选圆点 (radio) */}
                  <View style={[styles.radio, selected && styles.radioChecked]}>
                    {selected ? <View style={styles.radioDot} /> : null}
                  </View>

                  {/* 批次信息 */}
                  <View style={styles.wipInfo}>
                    <Text style={styles.wipNo}>{row.intermediateBatchNo}</Text>
                    <Text style={styles.wipProcess}>
                      {row.processName ?? `第 ${row.processOrder ?? '?'} 道`}
                    </Text>
                    <Text style={styles.wipAvail}>
                      可领 {row.availableQuantity ?? 0} {row.unit ?? ''}
                    </Text>
                  </View>
                </TouchableOpacity>
              );
            })}
          </ScrollView>
        )}

        {/* 未选提示 (防呆 Rule 1: 事前明确告知必须选) */}
        {!loading && loadError == null && rows.length > 0 && required && selectedSourceWipNo == null ? (
          <View style={styles.hint}>
            <Text style={styles.hintText}>请选择要领用的半成品批次</Text>
          </View>
        ) : null}
      </View>
    </View>
  );
};

const styles = StyleSheet.create({
  wrap: { marginTop: 8, marginBottom: 16, borderWidth: 1, borderColor: '#A0CFFF', borderRadius: 8, backgroundColor: '#F4F9FF' },
  header: { paddingHorizontal: 14, paddingVertical: 12 },
  headerTitle: { fontSize: 15, fontWeight: '700', color: '#409EFF' },
  body: { borderTopWidth: 1, borderTopColor: '#D9ECFF', paddingHorizontal: 12, paddingTop: 10, paddingBottom: 6 },
  center: { alignItems: 'center', paddingVertical: 16 },
  loadingText: { marginTop: 8, fontSize: 13, color: '#909399' },
  errorText: { fontSize: 13, color: '#F56C6C', textAlign: 'center' },
  retryBtn: { marginTop: 8, paddingHorizontal: 16, paddingVertical: 6, backgroundColor: '#FFEFD5', borderRadius: 6 },
  retryText: { fontSize: 13, color: '#E8732E', fontWeight: '600' },
  emptyText: { fontSize: 13, color: '#909399', textAlign: 'center', paddingVertical: 12 },
  listScroll: { maxHeight: 320 },
  row: {
    flexDirection: 'row', alignItems: 'center',
    paddingVertical: 12, paddingHorizontal: 6,
    borderBottomWidth: 1, borderBottomColor: '#E6F0FB',
  },
  rowSelected: { backgroundColor: '#ECF5FF', borderRadius: 6 },
  radio: {
    width: 22, height: 22, borderRadius: 11, borderWidth: 1.5, borderColor: '#C0C4CC',
    backgroundColor: '#FFFFFF', alignItems: 'center', justifyContent: 'center', marginRight: 12,
  },
  radioChecked: { borderColor: '#409EFF' },
  radioDot: { width: 12, height: 12, borderRadius: 6, backgroundColor: '#409EFF' },
  wipInfo: { flex: 1 },
  wipNo: { fontSize: 15, fontWeight: '600', color: '#303133' },
  wipProcess: { fontSize: 13, color: '#606266', marginTop: 2 },
  wipAvail: { fontSize: 13, color: '#409EFF', marginTop: 2, fontWeight: '500' },
  hint: {
    backgroundColor: '#FDF6EC', borderRadius: 6, paddingHorizontal: 10, paddingVertical: 8, marginTop: 8,
  },
  hintText: { fontSize: 13, color: '#E6A23C', fontWeight: '600' },
});

export default WipBatchPicker;
