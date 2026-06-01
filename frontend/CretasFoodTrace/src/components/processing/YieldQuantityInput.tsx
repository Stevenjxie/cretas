import React, { useCallback, useMemo } from 'react';
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
  testID?: string;
}

const clampToZero = (n: number): number => (n < 0 ? 0 : n);

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
  testID,
}) => {
  const numeric = parseFloat(value);
  const overMax = useMemo(
    () => max != null && !Number.isNaN(numeric) && numeric > max,
    [max, numeric],
  );

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
      <Text style={styles.label}>{label}</Text>
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

      {prefillNote ? <Text style={styles.prefillNote}>{prefillNote}</Text> : null}
      {overMax && maxHint ? <Text style={styles.maxHint}>{maxHint}</Text> : null}
    </View>
  );
};

const styles = StyleSheet.create({
  wrap: { marginBottom: 16 },
  label: { fontSize: 16, fontWeight: '600', color: '#303133', marginBottom: 10 },
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
