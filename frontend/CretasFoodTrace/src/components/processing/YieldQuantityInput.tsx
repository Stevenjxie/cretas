import React, { useCallback, useMemo, useState } from 'react';
import { View, Text, TextInput, TouchableOpacity, StyleSheet } from 'react-native';

interface YieldQuantityInputProps {
  /** 标签, 如 "投入量" / "产出量" */
  label: string;
  /** 受控 string 值 (父持有, 允许中间空/小数态) */
  value: string;
  onChangeText: (v: string) => void;
  /** 单位后缀, 如 "kg" / "盒" */
  unit?: string | null;
  /** 步进步长, 默认 1 */
  step?: number;
  /** 软上限 (不硬 clamp, 超过仅黄字提示). 如 plannedQuantity*1.3 */
  max?: number | null;
  /** 软上限超限提示文案 (Rule 1 预先显示边界), 如 "计划 998 kg, 可投上限约 1297 (含 30% 超收)" */
  maxHint?: string | null;
  /** 预填标注 (命门), 如 "← 上道产出 998 kg, 请确认实际投了多少". 橙色高亮显示. */
  prefillNote?: string | null;
  /** 是否禁用 (提交中) */
  disabled?: boolean;
  /**
   * 单元4 STEP 6: 允许"按托称重"计算器模式 (六扇门毛重-皮重算净重).
   * true → 数量字段下方显示"按托称重"全宽按钮; 打开后录多笔毛重 + 单托皮重, 自动算净重写回 value.
   */
  calculatorMode?: boolean;
  /**
   * B4: When true the tray-weighing calculator panel is pre-expanded on mount.
   * Used by YieldStepReportScreen for first-step (投入) input where tray-weighing
   * is the norm. Defaults to false (opt-in toggle for all other steps).
   *
   * Note: which products/steps default-expand can later be refined to a
   * per-product-type flag on ProductType when needed.
   */
  defaultTrayWeighing?: boolean;
  testID?: string;
}

const clampToZero = (n: number): number => (n < 0 ? 0 : n);

/** 净重 = Σ毛重 − 托数×皮重 (托数自动取毛重笔数). 负数归零. */
const computeNet = (grossRows: string[], tare: string): number => {
  const grossSum = grossRows.reduce((acc, g) => {
    const n = parseFloat(g);
    return acc + (Number.isNaN(n) ? 0 : n);
  }, 0);
  const t = parseFloat(tare);
  const palletCount = grossRows.filter((g) => {
    const n = parseFloat(g);
    return !Number.isNaN(n) && n > 0;
  }).length;
  const net = grossSum - palletCount * (Number.isNaN(t) ? 0 : t);
  return clampToZero(Number(net.toFixed(2)));
};

/** 净重计算明细文案: "毛 294.5+245.5 − 皮 2×24.5 = 净 491.0". 无有效毛重 → 空串. */
const buildBreakdown = (grossRows: string[], tare: string, net: number): string => {
  const valid = grossRows.filter((g) => {
    const n = parseFloat(g);
    return !Number.isNaN(n) && n > 0;
  });
  if (valid.length === 0) return '';
  const t = parseFloat(tare);
  const tareNum = Number.isNaN(t) ? 0 : t;
  return `毛 ${valid.join('+')} − 皮 ${valid.length}×${tareNum} = 净 ${net}`;
};

export const YieldQuantityInput: React.FC<YieldQuantityInputProps> = ({
  label,
  value,
  onChangeText,
  unit,
  step = 1,
  max,
  maxHint,
  prefillNote,
  disabled = false,
  calculatorMode = false,
  defaultTrayWeighing = false,
  testID,
}) => {
  const numeric = parseFloat(value);
  const overMax = useMemo(
    () => max != null && !Number.isNaN(numeric) && numeric > max,
    [max, numeric],
  );

  // STEP 6 / B4: 按托称重计算器状态. defaultTrayWeighing pre-expands for first-step.
  const [calcOpen, setCalcOpen] = useState(defaultTrayWeighing);
  const [grossRows, setGrossRows] = useState<string[]>(['']);
  const [tare, setTare] = useState('');

  const calcNet = useMemo(() => computeNet(grossRows, tare), [grossRows, tare]);
  const breakdown = useMemo(
    () => buildBreakdown(grossRows, tare, calcNet),
    [grossRows, tare, calcNet],
  );

  // 计算器明细变化 → 自动把净重写回受控 value (仅打开计算器时)
  const applyNet = useCallback(
    (rows: string[], tareVal: string) => {
      const net = computeNet(rows, tareVal);
      onChangeText(Number.isInteger(net) ? String(net) : String(Number(net.toFixed(2))));
    },
    [onChangeText],
  );

  const handleGrossChange = useCallback(
    (idx: number, raw: string) => {
      const cleaned = raw.replace(/[^0-9.]/g, '');
      const parts = cleaned.split('.');
      const normalized = parts.length > 2 ? `${parts[0]}.${parts.slice(1).join('')}` : cleaned;
      setGrossRows((prev) => {
        const next = [...prev];
        next[idx] = normalized;
        applyNet(next, tare);
        return next;
      });
    },
    [applyNet, tare],
  );

  const handleTareChange = useCallback(
    (raw: string) => {
      const cleaned = raw.replace(/[^0-9.]/g, '');
      const parts = cleaned.split('.');
      const normalized = parts.length > 2 ? `${parts[0]}.${parts.slice(1).join('')}` : cleaned;
      setTare(normalized);
      applyNet(grossRows, normalized);
    },
    [applyNet, grossRows],
  );

  const addGrossRow = useCallback(() => {
    setGrossRows((prev) => [...prev, '']);
  }, []);

  const removeGrossRow = useCallback(
    (idx: number) => {
      setGrossRows((prev) => {
        const next = prev.filter((_, i) => i !== idx);
        const safe = next.length > 0 ? next : [''];
        applyNet(safe, tare);
        return safe;
      });
    },
    [applyNet, tare],
  );

  const toggleCalc = useCallback(() => {
    setCalcOpen((open) => {
      const next = !open;
      // 打开时立即把当前明细净重写回 (空则为 0)
      if (next) applyNet(grossRows, tare);
      return next;
    });
  }, [applyNet, grossRows, tare]);

  const handleStep = useCallback(
    (delta: number) => {
      const base = Number.isNaN(numeric) ? 0 : numeric;
      const next = clampToZero(base + delta);
      // 保留小数: 整数显整数, 否则最多 2 位
      onChangeText(Number.isInteger(next) ? String(next) : String(Number(next.toFixed(2))));
    },
    [numeric, onChangeText],
  );

  // 只允许数字 + 单个小数点
  const handleChange = useCallback(
    (raw: string) => {
      const cleaned = raw.replace(/[^0-9.]/g, '');
      const parts = cleaned.split('.');
      const normalized = parts.length > 2 ? `${parts[0]}.${parts.slice(1).join('')}` : cleaned;
      onChangeText(normalized);
    },
    [onChangeText],
  );

  return (
    <View style={styles.wrap} testID={testID}>
      <View style={styles.labelRow}>
        <Text style={styles.label}>{label}</Text>
      </View>

      {/* B4: 按托称重计算器面板 — shown above qty row when open */}
      {/* STEP 6 / B4: relabeled 毛重→秤上数字, 皮重→每个托盘重; updated help text */}
      {calculatorMode && calcOpen ? (
        <View style={styles.calcPanel} testID={testID ? `${testID}-calc-panel` : undefined}>
          <Text style={styles.calcHelp}>净重 = 每个秤上数字之和 − 托盘数 × 每个托盘重</Text>
          {grossRows.map((g, idx) => (
            <View style={styles.calcGrossRow} key={`gross-${idx}`}>
              <Text style={styles.calcGrossLabel}>秤上数字{idx + 1}</Text>
              <TextInput
                style={styles.calcGrossInput}
                keyboardType="decimal-pad"
                value={g}
                onChangeText={(raw) => handleGrossChange(idx, raw)}
                editable={!disabled}
                placeholder="0"
                placeholderTextColor="#C0C4CC"
                testID={testID ? `${testID}-gross-${idx}` : undefined}
              />
              {unit ? <Text style={styles.calcUnit}>{unit}</Text> : null}
              {grossRows.length > 1 ? (
                <TouchableOpacity
                  style={styles.calcRemoveBtn}
                  onPress={() => removeGrossRow(idx)}
                  disabled={disabled}
                  accessibilityLabel="删除这笔毛重"
                >
                  <Text style={styles.calcRemoveText}>✕</Text>
                </TouchableOpacity>
              ) : null}
            </View>
          ))}
          <TouchableOpacity
            style={styles.calcAddBtn}
            onPress={addGrossRow}
            disabled={disabled}
            testID={testID ? `${testID}-add-gross` : undefined}
          >
            <Text style={styles.calcAddText}>＋ 加托 (再称一托)</Text>
          </TouchableOpacity>

          <View style={styles.calcTareRow}>
            {/* B4: relabeled 单托皮重 → 每个托盘重 (kg) */}
            <Text style={styles.calcTareLabel}>每个托盘重 (kg)</Text>
            <TextInput
              style={styles.calcGrossInput}
              keyboardType="decimal-pad"
              value={tare}
              onChangeText={handleTareChange}
              editable={!disabled}
              placeholder="0"
              placeholderTextColor="#C0C4CC"
              testID={testID ? `${testID}-tare` : undefined}
            />
            {unit ? <Text style={styles.calcUnit}>{unit}</Text> : null}
          </View>

          {breakdown ? (
            <Text style={styles.calcBreakdown} testID={testID ? `${testID}-breakdown` : undefined}>
              {breakdown} {unit ?? ''}
            </Text>
          ) : null}
        </View>
      ) : null}

      <View style={styles.row}>
        <TouchableOpacity
          style={[styles.stepBtn, disabled && styles.stepBtnDisabled]}
          onPress={() => handleStep(-step)}
          disabled={disabled}
          testID={testID ? `${testID}-minus` : undefined}
          accessibilityLabel="减少"
        >
          <Text style={styles.stepText}>−</Text>
        </TouchableOpacity>

        <View style={styles.valueBox}>
          <TextInput
            style={styles.valueInput}
            keyboardType="decimal-pad"
            value={value}
            onChangeText={handleChange}
            editable={!disabled}
            placeholder="0"
            placeholderTextColor="#C0C4CC"
            testID={testID ? `${testID}-input` : undefined}
          />
          {unit ? <Text style={styles.unit}>{unit}</Text> : null}
        </View>

        <TouchableOpacity
          style={[styles.stepBtn, disabled && styles.stepBtnDisabled]}
          onPress={() => handleStep(step)}
          disabled={disabled}
          testID={testID ? `${testID}-plus` : undefined}
          accessibilityLabel="增加"
        >
          <Text style={styles.stepText}>＋</Text>
        </TouchableOpacity>
      </View>

      {/* B4: full-width outlined "按托称重" toggle below the quantity row (replaces the small pill in the label row) */}
      {calculatorMode ? (
        <TouchableOpacity
          style={[styles.calcToggleFull, calcOpen && styles.calcToggleFullOn]}
          onPress={toggleCalc}
          disabled={disabled}
          testID={testID ? `${testID}-calc-toggle` : undefined}
          accessibilityLabel="按托称重计算器"
        >
          <Text style={[styles.calcToggleFullText, calcOpen && styles.calcToggleFullTextOn]}>
            {calcOpen ? '✓ 按托称重（点击收起）' : '按托称重（点击展开）'}
          </Text>
        </TouchableOpacity>
      ) : null}

      {prefillNote ? <Text style={styles.prefillNote}>{prefillNote}</Text> : null}
      {overMax && maxHint ? <Text style={styles.maxHint}>{maxHint}</Text> : null}
    </View>
  );
};

const styles = StyleSheet.create({
  wrap: { marginBottom: 16 },
  labelRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: 10 },
  label: { fontSize: 16, fontWeight: '600', color: '#303133' },
  // B4: full-width outlined toggle below the quantity row
  calcToggleFull: {
    marginTop: 10, height: 44, borderRadius: 8, borderWidth: 1.5,
    borderColor: '#409EFF', backgroundColor: '#FFFFFF',
    alignItems: 'center', justifyContent: 'center',
  },
  calcToggleFullOn: { backgroundColor: '#409EFF' },
  calcToggleFullText: { fontSize: 15, color: '#409EFF', fontWeight: '600' },
  calcToggleFullTextOn: { color: '#FFFFFF' },
  // STEP 6: legacy small pill styles kept for reference but no longer rendered
  calcToggle: {
    paddingHorizontal: 14, paddingVertical: 8, borderRadius: 16, borderWidth: 1,
    borderColor: '#409EFF', backgroundColor: '#FFFFFF',
  },
  calcToggleOn: { backgroundColor: '#409EFF' },
  calcToggleText: { fontSize: 14, color: '#409EFF', fontWeight: '600' },
  calcToggleTextOn: { color: '#FFFFFF' },
  calcPanel: {
    backgroundColor: '#ECF5FF', borderRadius: 8, padding: 12, marginBottom: 12,
  },
  calcHelp: { fontSize: 13, color: '#606266', marginBottom: 10 },
  calcGrossRow: { flexDirection: 'row', alignItems: 'center', marginBottom: 8 },
  calcGrossLabel: { fontSize: 15, color: '#303133', width: 64 },
  calcTareLabel: { fontSize: 15, color: '#303133', fontWeight: '600', width: 80 },
  calcGrossInput: {
    flex: 1, fontSize: 20, fontWeight: '600', color: '#1A1A1A', textAlign: 'center',
    borderWidth: 1, borderColor: '#DCDFE6', borderRadius: 8, backgroundColor: '#FFFFFF',
    height: 48, paddingHorizontal: 10,
  },
  calcUnit: { fontSize: 15, color: '#909399', marginLeft: 8, width: 40 },
  calcRemoveBtn: {
    width: 36, height: 36, borderRadius: 18, backgroundColor: '#FEF0F0',
    alignItems: 'center', justifyContent: 'center', marginLeft: 6,
  },
  calcRemoveText: { fontSize: 16, color: '#F56C6C', fontWeight: '700' },
  calcAddBtn: {
    height: 44, borderRadius: 8, borderWidth: 1, borderColor: '#409EFF', borderStyle: 'dashed',
    alignItems: 'center', justifyContent: 'center', marginTop: 2, marginBottom: 10,
  },
  calcAddText: { fontSize: 15, color: '#409EFF', fontWeight: '600' },
  calcTareRow: { flexDirection: 'row', alignItems: 'center', marginBottom: 10 },
  calcBreakdown: { fontSize: 15, color: '#303133', fontWeight: '700', textAlign: 'center', marginTop: 2 },
  row: { flexDirection: 'row', alignItems: 'center' },
  stepBtn: {
    width: 48, height: 48, borderRadius: 8, borderWidth: 1, borderColor: '#DCDFE6',
    backgroundColor: '#F5F7FA', alignItems: 'center', justifyContent: 'center',
  },
  stepBtnDisabled: { opacity: 0.4 },
  stepText: { fontSize: 26, color: '#303133', fontWeight: '600', lineHeight: 30 },
  valueBox: {
    flex: 1, flexDirection: 'row', alignItems: 'center', justifyContent: 'center',
    marginHorizontal: 10, borderWidth: 1, borderColor: '#DCDFE6', borderRadius: 8,
    backgroundColor: '#FFFFFF', height: 56, paddingHorizontal: 12,
  },
  valueInput: { flex: 1, fontSize: 28, fontWeight: '700', color: '#1A1A1A', textAlign: 'center', padding: 0 },
  unit: { fontSize: 16, color: '#909399', marginLeft: 6 },
  prefillNote: { fontSize: 14, color: '#E8732E', marginTop: 8, fontWeight: '500' },
  maxHint: { fontSize: 13, color: '#E6A23C', marginTop: 6 },
});

export default YieldQuantityInput;
