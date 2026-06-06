import React, { useEffect, useMemo, useState } from 'react';
import { Alert, ScrollView, StyleSheet, TouchableOpacity, View } from 'react-native';
import { Button, Card, Chip, Text, TextInput } from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useNavigation } from '@react-navigation/native';

import { materialTypeApiClient, MaterialType } from '../../../services/api/materialTypeApiClient';
import {
  purchaseRequisitionApiClient,
  PurchaseRequisition,
  RequisitionItem,
} from '../../../services/api/purchaseRequisitionApiClient';
import { useAuthStore } from '../../../store/authStore';
import { handleError } from '../../../utils/errorHandler';

const STALL_OPTIONS = [
  { value: 'SEAFOOD', label: '海鲜档' },
  { value: 'COLD_DISH', label: '凉菜档' },
  { value: 'HOT_DISH', label: '热菜档' },
  { value: 'FRONT_HOUSE', label: '前厅档' },
  { value: 'OTHER', label: '其他档口' },
];

interface LineDraft {
  key: string;
  materialSearch: string;
  materialTypeId: string;
  materialName: string;
  quantity: string;
  unit: string;
  remark: string;
}

export function ChefRequisitionCreateScreen() {
  const navigation = useNavigation();
  const { user } = useAuthStore();
  const factoryId = user?.factoryId;

  const [materials, setMaterials] = useState<MaterialType[]>([]);
  const [stallCode, setStallCode] = useState('HOT_DISH');
  const [expectedDate, setExpectedDate] = useState(new Date().toISOString().slice(0, 10));
  const [reason, setReason] = useState('');
  const [remark, setRemark] = useState('');
  const [lines, setLines] = useState<LineDraft[]>([newLine()]);
  const [recentRequisitions, setRecentRequisitions] = useState<PurchaseRequisition[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [showPreview, setShowPreview] = useState(false);

  useEffect(() => {
    let alive = true;
    (async () => {
      try {
        const [matRes, reqRes] = await Promise.all([
          materialTypeApiClient.getActiveMaterialTypes(factoryId),
          purchaseRequisitionApiClient.list({
            requesterId: user?.id,
            page: 1,
            size: 12,
            factoryId,
          }),
        ]);
        if (!alive) return;
        setMaterials(matRes?.data || []);
        setRecentRequisitions(reqRes.data || []);
      } catch (error) {
        handleError(error, { title: '食材主数据加载失败', showAlert: false });
      }
    })();
    return () => { alive = false; };
  }, [factoryId, user?.id]);

  const recentForStall = useMemo(
    () => recentRequisitions
      .filter((req) => (req.requestedItems || []).length > 0)
      .filter((req) => !stallCode || req.requesterDeptId === stallCode)
      .slice(0, 5),
    [recentRequisitions, stallCode],
  );

  const stallLabel = STALL_OPTIONS.find((s) => s.value === stallCode)?.label || stallCode;

  const previewItems = useMemo(
    () => lines
      .filter((line) => line.materialTypeId && Number(line.quantity) > 0)
      .map((line) => ({
        name: line.materialName,
        quantity: line.quantity,
        unit: line.unit,
      })),
    [lines],
  );

  const updateLine = (key: string, patch: Partial<LineDraft>) => {
    setLines((curr) => curr.map((line) => (line.key === key ? { ...line, ...patch } : line)));
  };

  const filteredMaterials = (line: LineDraft) => {
    const query = line.materialSearch.trim().toLowerCase();
    return materials
      .filter((material) => {
        if (!query) return true;
        return [material.name, material.code, material.category]
          .some((value) => (value || '').toLowerCase().includes(query));
      })
      .slice(0, 6);
  };

  const selectMaterial = (line: LineDraft, material: MaterialType) => {
    updateLine(line.key, {
      materialTypeId: material.id,
      materialName: material.name,
      materialSearch: material.name,
      unit: line.unit || material.unit || 'kg',
    });
  };

  const applyRecentRequisition = (req: PurchaseRequisition) => {
    const nextLines = (req.requestedItems || []).map((item, index) => ({
      key: `recent-${req.id}-${index}`,
      materialSearch: item.materialName || item.materialTypeId,
      materialTypeId: item.materialTypeId,
      materialName: item.materialName || item.materialTypeId,
      quantity: String(item.quantity),
      unit: item.unit || 'kg',
      remark: item.remark || '',
    }));
    if (nextLines.length === 0) {
      Alert.alert('无法套用', '该报货单没有可复用的明细行。');
      return;
    }
    if (req.requesterDeptId) setStallCode(req.requesterDeptId);
    if (req.expectedDate) setExpectedDate(req.expectedDate);
    if (req.reason) setReason(req.reason);
    setLines(nextLines);
    Alert.alert('已套用最近报货', `${req.requisitionNumber} · ${nextLines.length} 行明细已填入，请核对数量后提交。`);
  };

  const validate = (): string | null => {
    if (!stallCode) return '请选择档口。';
    if (!expectedDate) return '请填写希望到货日。';
    const validLines = lines.filter((line) => line.materialTypeId && Number(line.quantity) > 0);
    if (validLines.length === 0) return '请至少添加一行报货明细。';
    for (const [index, line] of validLines.entries()) {
      const qty = Number(line.quantity);
      if (!Number.isFinite(qty) || qty <= 0) return `第 ${index + 1} 行数量必须大于 0。`;
      if (!line.unit.trim()) return `第 ${index + 1} 行需要填写单位。`;
    }
    return null;
  };

  const buildPayload = (): RequisitionItem[] => lines
    .filter((line) => line.materialTypeId && Number(line.quantity) > 0)
    .map((line) => ({
      materialTypeId: line.materialTypeId,
      materialName: line.materialName,
      quantity: Number(line.quantity),
      unit: line.unit.trim(),
      remark: line.remark.trim() || undefined,
    }));

  const submit = async (asDraft: boolean) => {
    const error = validate();
    if (error) {
      Alert.alert('还不能提交', error);
      return;
    }
    setSubmitting(true);
    try {
      const created = await purchaseRequisitionApiClient.create({
        requesterDeptId: stallCode,
        requestedItems: buildPayload(),
        expectedDate,
        reason: reason.trim() || undefined,
        remark: remark.trim() || `档口：${stallLabel}`,
      });
      if (!asDraft && created?.id) {
        await purchaseRequisitionApiClient.submit(created.id);
      }
      Alert.alert(
        asDraft ? '报货草稿已保存' : '报货已提交',
        `${created.requisitionNumber || created.id}\n档口：${stallLabel}\n希望到货：${expectedDate}`,
        [{ text: '返回', onPress: () => navigation.goBack() }],
      );
    } catch (err) {
      handleError(err, { title: '报货提交失败' });
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <SafeAreaView style={styles.container}>
      <View style={styles.header}>
        <Button icon="arrow-left" textColor="#fff" onPress={() => navigation.goBack()}>返回</Button>
        <Text style={styles.headerTitle}>档口报货</Text>
        <View style={{ width: 60 }} />
      </View>

      <ScrollView contentContainerStyle={styles.content}>
        <Text style={styles.label}>档口 *</Text>
        <View style={styles.chipRow}>
          {STALL_OPTIONS.map((stall) => (
            <Chip key={stall.value} selected={stallCode === stall.value} onPress={() => setStallCode(stall.value)}>
              {stall.label}
            </Chip>
          ))}
        </View>

        <Text style={styles.label}>希望到货日 *</Text>
        <TextInput mode="outlined" value={expectedDate} onChangeText={setExpectedDate} placeholder="YYYY-MM-DD" style={styles.input} />

        <Text style={styles.label}>报货原因</Text>
        <TextInput mode="outlined" value={reason} onChangeText={setReason} placeholder="明日备货 / 厨房急用" style={styles.input} />

        {recentForStall.length > 0 ? (
          <>
            <Text style={styles.sectionTitle}>最近报货</Text>
            <Text style={styles.meta}>点选可套用明细，提交前请核对数量与到货日。</Text>
            {recentForStall.map((req) => (
              <Card key={req.id} style={styles.recentCard} onPress={() => applyRecentRequisition(req)}>
                <Card.Content>
                  <View style={styles.recentTitleRow}>
                    <Text style={styles.recentTitle}>{req.requisitionNumber}</Text>
                    <Chip compact>{(req.requestedItems || []).length} 行</Chip>
                  </View>
                  <Text style={styles.meta}>
                    {STALL_OPTIONS.find((s) => s.value === req.requesterDeptId)?.label || req.requesterDeptId || '—'}
                    {' · '}
                    到货 {req.expectedDate || '—'}
                  </Text>
                  <Text style={styles.recentPreview} numberOfLines={2}>
                    {(req.requestedItems || []).map((item) => item.materialName || item.materialTypeId).join('、')}
                  </Text>
                </Card.Content>
              </Card>
            ))}
          </>
        ) : null}

        <View style={styles.sectionRow}>
          <Text style={styles.sectionTitle}>报货明细</Text>
          <Button compact mode="outlined" icon="plus" onPress={() => setLines((curr) => [...curr, newLine()])}>加一行</Button>
        </View>

        {lines.map((line, index) => (
          <Card key={line.key} style={styles.card}>
            <Card.Content>
              <Text style={styles.lineTitle}>第 {index + 1} 行</Text>
              <TextInput
                label="搜索食材"
                mode="outlined"
                value={line.materialSearch}
                onChangeText={(value) => updateLine(line.key, { materialSearch: value, materialTypeId: '', materialName: value })}
                style={styles.input}
              />
              <View style={styles.optionList}>
                {filteredMaterials(line).map((material) => (
                  <TouchableOpacity key={material.id} activeOpacity={0.84} onPress={() => selectMaterial(line, material)}>
                    <View style={[styles.optionCard, line.materialTypeId === material.id && styles.optionSelected]}>
                      <Text style={styles.optionTitle}>{material.name}</Text>
                      <Text style={styles.optionMeta}>{[material.code, material.unit].filter(Boolean).join(' · ')}</Text>
                    </View>
                  </TouchableOpacity>
                ))}
              </View>
              <View style={styles.row}>
                <TextInput
                  label="数量"
                  mode="outlined"
                  keyboardType="decimal-pad"
                  value={line.quantity}
                  onChangeText={(value) => updateLine(line.key, { quantity: value })}
                  style={[styles.input, styles.flex]}
                />
                <TextInput
                  label="单位"
                  mode="outlined"
                  value={line.unit}
                  onChangeText={(value) => updateLine(line.key, { unit: value })}
                  style={[styles.input, styles.flex]}
                />
              </View>
              <TextInput
                label="行备注"
                mode="outlined"
                value={line.remark}
                onChangeText={(value) => updateLine(line.key, { remark: value })}
                style={styles.input}
              />
            </Card.Content>
          </Card>
        ))}

        <Button mode="text" onPress={() => setShowPreview((v) => !v)}>
          {showPreview ? '收起提交预览' : '查看提交预览'}
        </Button>
        {showPreview ? (
          <Card style={styles.previewCard}>
            <Card.Content>
              <Text style={styles.previewTitle}>提交前确认</Text>
              <Text style={styles.meta}>档口：{stallLabel}</Text>
              <Text style={styles.meta}>希望到货：{expectedDate}</Text>
              {previewItems.map((item, idx) => (
                <Text key={`${item.name}-${idx}`} style={styles.meta}>
                  {idx + 1}. {item.name} — {item.quantity} {item.unit}
                </Text>
              ))}
            </Card.Content>
          </Card>
        ) : null}

        <View style={styles.btnRow}>
          <Button mode="outlined" disabled={submitting} onPress={() => submit(true)} style={styles.btn}>保存草稿</Button>
          <Button mode="contained" loading={submitting} disabled={submitting} buttonColor="#1B65A8" onPress={() => submit(false)} style={styles.btn}>
            提交报货
          </Button>
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

function newLine(): LineDraft {
  return {
    key: `line-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
    materialSearch: '',
    materialTypeId: '',
    materialName: '',
    quantity: '',
    unit: 'kg',
    remark: '',
  };
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f5f5f5' },
  header: { backgroundColor: '#1B65A8', flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: 4, paddingVertical: 8 },
  headerTitle: { fontSize: 18, fontWeight: 'bold', color: '#fff' },
  content: { padding: 16, paddingBottom: 40 },
  label: { fontSize: 14, fontWeight: '500', color: '#333', marginBottom: 4, marginTop: 12 },
  input: { backgroundColor: '#fff', marginTop: 8 },
  chipRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginTop: 4 },
  sectionRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginTop: 16, marginBottom: 8 },
  sectionTitle: { fontSize: 16, fontWeight: '700', color: '#1F2937', marginTop: 16 },
  recentCard: { marginTop: 8, marginBottom: 4, borderRadius: 8, borderColor: '#BFDBFE', borderWidth: 1 },
  recentTitleRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', gap: 8 },
  recentTitle: { flex: 1, fontWeight: '700', color: '#1D4ED8' },
  recentPreview: { fontSize: 12, color: '#6B7280', marginTop: 6 },
  card: { marginBottom: 10, borderRadius: 8 },
  lineTitle: { fontWeight: '700', marginBottom: 4 },
  optionList: { marginTop: 8 },
  optionCard: { borderWidth: 1, borderColor: '#E5E7EB', borderRadius: 8, padding: 10, marginBottom: 8, backgroundColor: '#fff' },
  optionSelected: { borderColor: '#1890FF', backgroundColor: '#E6F7FF' },
  optionTitle: { fontWeight: '700', color: '#1F2937' },
  optionMeta: { fontSize: 12, color: '#6B7280', marginTop: 2 },
  row: { flexDirection: 'row', gap: 8 },
  flex: { flex: 1 },
  previewCard: { marginTop: 8, backgroundColor: '#F0F9FF' },
  previewTitle: { fontWeight: '800', marginBottom: 6, color: '#1D4ED8' },
  meta: { fontSize: 13, color: '#4B5563', marginTop: 4 },
  btnRow: { flexDirection: 'row', marginTop: 24, gap: 12 },
  btn: { flex: 1, borderRadius: 8 },
});

export default ChefRequisitionCreateScreen;
