import React, { useState } from 'react';
import { Alert, ScrollView, StyleSheet, View } from 'react-native';
import { Button, Divider, Text, TextInput } from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useNavigation } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { FAManagementStackParamList } from '../../../types/navigation';
import { restaurantApiClient } from '../../../services/api/restaurantApiClient';
import { handleError } from '../../../utils/errorHandler';

type Nav = NativeStackNavigationProp<FAManagementStackParamList, 'SupplierDeliveryCreate'>;

interface DraftLine {
  ingredientName: string;
  rawMaterialTypeId: string;
  quantity: string;
  unit: string;
  unitPrice: string;
  qcResult: string;
  remark: string;
}

const newLine = (): DraftLine => ({
  ingredientName: '',
  rawMaterialTypeId: '',
  quantity: '',
  unit: 'kg',
  unitPrice: '',
  qcResult: 'PASS',
  remark: '',
});

export function SupplierDeliveryCreateScreen() {
  const navigation = useNavigation<Nav>();
  const [supplierId, setSupplierId] = useState('');
  const [supplierName, setSupplierName] = useState('');
  const [deliveryDate, setDeliveryDate] = useState(new Date().toISOString().slice(0, 10));
  const [noteNumber, setNoteNumber] = useState('');
  const [lines, setLines] = useState<DraftLine[]>([newLine()]);
  const [submitting, setSubmitting] = useState(false);

  const updateLine = (index: number, patch: Partial<DraftLine>) => {
    setLines(curr => curr.map((line, i) => i === index ? { ...line, ...patch } : line));
  };

  const submit = async () => {
    if (!supplierId.trim()) {
      Alert.alert('缺少供应商', '请先填写供应商 ID，避免送货单无法入库过账。');
      return;
    }
    for (const line of lines) {
      const qty = Number(line.quantity);
      const price = line.unitPrice.trim() ? Number(line.unitPrice) : undefined;
      if (!line.ingredientName.trim() || !line.rawMaterialTypeId.trim() || !Number.isFinite(qty) || qty <= 0 || !line.unit.trim()) {
        Alert.alert('食材行不完整', '每行必须填写食材名称、食材主数据 ID、数量和单位。');
        return;
      }
      if (price !== undefined && (!Number.isFinite(price) || price < 0)) {
        Alert.alert('单价不正确', `请检查「${line.ingredientName || '未命名食材'}」的单价。`);
        return;
      }
    }

    setSubmitting(true);
    try {
      const note = await restaurantApiClient.createSupplierDelivery({
        supplierId: supplierId.trim(),
        supplierName: supplierName.trim() || undefined,
        deliveryDate,
        noteNumber: noteNumber.trim() || undefined,
        lines: lines.map(line => ({
          ingredientName: line.ingredientName.trim(),
          rawMaterialTypeId: line.rawMaterialTypeId.trim(),
          quantity: Number(line.quantity),
          unit: line.unit.trim(),
          unitPrice: line.unitPrice.trim() ? Number(line.unitPrice) : undefined,
          qcResult: line.qcResult.trim() || undefined,
          remark: line.remark.trim() || undefined,
        })),
      });
      navigation.replace('SupplierDeliveryDetail', { noteId: note.id });
    } catch (error) {
      handleError(error, { title: '送货单创建失败' });
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <SafeAreaView style={styles.container}>
      <View style={styles.header}>
        <Button icon="arrow-left" textColor="#fff" onPress={() => navigation.goBack()}>返回</Button>
        <Text style={styles.headerTitle}>新建送货单</Text>
        <View style={{ width: 72 }} />
      </View>
      <ScrollView contentContainerStyle={styles.content}>
        <TextInput label="供应商 ID *" mode="outlined" value={supplierId} onChangeText={setSupplierId} style={styles.input} />
        <TextInput label="供应商名称" mode="outlined" value={supplierName} onChangeText={setSupplierName} style={styles.input} />
        <TextInput label="送货日期 YYYY-MM-DD *" mode="outlined" value={deliveryDate} onChangeText={setDeliveryDate} style={styles.input} />
        <TextInput label="送货单号" mode="outlined" value={noteNumber} onChangeText={setNoteNumber} style={styles.input} />

        <View style={styles.sectionHeader}>
          <Text style={styles.sectionTitle}>食材明细</Text>
          <Button mode="outlined" onPress={() => setLines(curr => [...curr, newLine()])}>加一行</Button>
        </View>

        {lines.map((line, index) => (
          <View key={index} style={styles.lineBox}>
            <Text style={styles.lineTitle}>第 {index + 1} 行</Text>
            <TextInput label="食材名称 *" mode="outlined" value={line.ingredientName} onChangeText={v => updateLine(index, { ingredientName: v })} style={styles.input} />
            <TextInput label="食材主数据 ID *" mode="outlined" value={line.rawMaterialTypeId} onChangeText={v => updateLine(index, { rawMaterialTypeId: v })} style={styles.input} />
            <View style={styles.row}>
              <TextInput label="数量 *" mode="outlined" value={line.quantity} keyboardType="decimal-pad" onChangeText={v => updateLine(index, { quantity: v })} style={[styles.input, styles.flex]} />
              <TextInput label="单位 *" mode="outlined" value={line.unit} onChangeText={v => updateLine(index, { unit: v })} style={[styles.input, styles.flex]} />
            </View>
            <TextInput label="单价" mode="outlined" value={line.unitPrice} keyboardType="decimal-pad" onChangeText={v => updateLine(index, { unitPrice: v })} style={styles.input} />
            <TextInput label="质检结果" mode="outlined" value={line.qcResult} onChangeText={v => updateLine(index, { qcResult: v })} style={styles.input} />
            <TextInput label="备注" mode="outlined" value={line.remark} onChangeText={v => updateLine(index, { remark: v })} style={styles.input} />
            {lines.length > 1 ? <Button onPress={() => setLines(curr => curr.filter((_, i) => i !== index))}>删除本行</Button> : null}
          </View>
        ))}

        <Divider style={{ marginVertical: 12 }} />
        <Button mode="contained" buttonColor="#2563EB" loading={submitting} disabled={submitting} onPress={submit}>
          保存草稿
        </Button>
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f5f5f5' },
  header: { backgroundColor: '#2563EB', flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingVertical: 8 },
  headerTitle: { fontSize: 18, color: '#fff', fontWeight: '700' },
  content: { padding: 16, paddingBottom: 40 },
  input: { backgroundColor: '#fff', marginBottom: 10 },
  sectionHeader: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginTop: 8, marginBottom: 8 },
  sectionTitle: { fontSize: 16, fontWeight: '700', color: '#1F2937' },
  lineBox: { backgroundColor: '#fff', borderRadius: 10, padding: 12, marginBottom: 12 },
  lineTitle: { fontSize: 14, fontWeight: '700', color: '#374151', marginBottom: 8 },
  row: { flexDirection: 'row', gap: 10 },
  flex: { flex: 1 },
});

export default SupplierDeliveryCreateScreen;
