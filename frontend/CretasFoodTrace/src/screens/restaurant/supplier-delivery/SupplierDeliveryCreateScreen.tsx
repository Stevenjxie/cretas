import React, { useEffect, useMemo, useState } from 'react';
import { Alert, Image, ScrollView, StyleSheet, TouchableOpacity, View } from 'react-native';
import * as ImagePicker from 'expo-image-picker';
import {
  ActivityIndicator,
  Appbar,
  Button,
  Card,
  Chip,
  HelperText,
  IconButton,
  Text,
  TextInput,
} from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useNavigation } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';

import { FAManagementStackParamList } from '../../../types/navigation';
import { restaurantApiClient } from '../../../services/api/restaurantApiClient';
import { supplierApiClient, Supplier } from '../../../services/api/supplierApiClient';
import { materialTypeApiClient, MaterialType } from '../../../services/api/materialTypeApiClient';
import { useAuthStore } from '../../../store/authStore';
import { handleError } from '../../../utils/errorHandler';
import { roleCanViewPrice } from '../../../config/rowActionsConfig';

type Nav = NativeStackNavigationProp<FAManagementStackParamList, 'SupplierDeliveryCreate'>;

interface DraftLine {
  key: string;
  ingredientName: string;
  rawMaterialTypeId: string;
  materialSearch: string;
  quantity: string;
  unit: string;
  unitPrice: string;
  qcResult: 'PASS' | 'FAIL' | 'PENDING';
  remark: string;
  showManualMaterialId: boolean;
}

const newLine = (): DraftLine => ({
  key: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
  ingredientName: '',
  rawMaterialTypeId: '',
  materialSearch: '',
  quantity: '',
  unit: 'kg',
  unitPrice: '',
  qcResult: 'PASS',
  remark: '',
  showManualMaterialId: false,
});

const QC_OPTIONS: Array<{ value: DraftLine['qcResult']; label: string }> = [
  { value: 'PASS', label: '合格' },
  { value: 'PENDING', label: '待复核' },
  { value: 'FAIL', label: '不合格' },
];

export function SupplierDeliveryCreateScreen() {
  const navigation = useNavigation<Nav>();
  const { user } = useAuthStore();
  const factoryId = user?.factoryId;
  const roleCode = user?.userType === 'platform' ? user.platformUser?.role : user?.factoryUser?.role;
  const canViewAmounts = roleCanViewPrice(roleCode);

  const [suppliers, setSuppliers] = useState<Supplier[]>([]);
  const [materials, setMaterials] = useState<MaterialType[]>([]);
  const [loadingRefs, setLoadingRefs] = useState(true);

  const [supplierId, setSupplierId] = useState('');
  const [supplierName, setSupplierName] = useState('');
  const [supplierSearch, setSupplierSearch] = useState('');
  const [showManualSupplierId, setShowManualSupplierId] = useState(false);

  const [deliveryDate, setDeliveryDate] = useState(new Date().toISOString().slice(0, 10));
  const [noteNumber, setNoteNumber] = useState('');
  const [lines, setLines] = useState<DraftLine[]>([newLine()]);
  const [submitting, setSubmitting] = useState(false);
  const [ocrSubmitting, setOcrSubmitting] = useState(false);
  const [ocrPhotoUri, setOcrPhotoUri] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    (async () => {
      try {
        const [supplierList, materialList] = await Promise.all([
          supplierApiClient.getActiveSuppliers(factoryId),
          materialTypeApiClient.getActiveMaterialTypes(factoryId),
        ]);
        if (!alive) return;
        setSuppliers(supplierList || []);
        setMaterials(materialList?.data || []);
      } catch (error) {
        handleError(error, { title: '加载供应商和食材失败' });
      } finally {
        if (alive) setLoadingRefs(false);
      }
    })();
    return () => {
      alive = false;
    };
  }, [factoryId]);

  const filteredSuppliers = useMemo(() => {
    const query = supplierSearch.trim().toLowerCase();
    return suppliers
      .filter((supplier) => {
        if (!query) return true;
        return [
          supplier.name,
          supplier.supplierCode,
          supplier.code,
          supplier.contactPerson,
          supplier.phone,
        ].some((value) => (value || '').toLowerCase().includes(query));
      })
      .slice(0, 6);
  }, [supplierSearch, suppliers]);

  const totalAmount = useMemo(() => {
    return lines.reduce((sum, line) => {
      const qty = Number(line.quantity);
      const price = Number(line.unitPrice);
      return Number.isFinite(qty) && Number.isFinite(price) ? sum + qty * price : sum;
    }, 0);
  }, [lines]);

  const updateLine = (key: string, patch: Partial<DraftLine>) => {
    setLines((curr) => curr.map((line) => (line.key === key ? { ...line, ...patch } : line)));
  };

  const addLine = () => {
    setLines((curr) => [...curr, newLine()]);
  };

  const removeLine = (key: string) => {
    setLines((curr) => (curr.length === 1 ? curr : curr.filter((line) => line.key !== key)));
  };

  const filteredMaterials = (line: DraftLine): MaterialType[] => {
    const query = line.materialSearch.trim().toLowerCase();
    return materials
      .filter((material) => {
        if (!query) return true;
        return [material.name, material.code, material.category]
          .some((value) => (value || '').toLowerCase().includes(query));
      })
      .slice(0, 6);
  };

  const selectSupplier = (supplier: Supplier) => {
    setSupplierId(supplier.id);
    setSupplierName(supplier.name);
    setSupplierSearch(`${supplier.name}${supplier.supplierCode ? ` (${supplier.supplierCode})` : ''}`);
    setShowManualSupplierId(false);
  };

  const selectMaterial = (line: DraftLine, material: MaterialType) => {
    updateLine(line.key, {
      ingredientName: material.name,
      rawMaterialTypeId: material.id,
      materialSearch: `${material.name}${material.code ? ` (${material.code})` : ''}`,
      unit: line.unit || material.unit || 'kg',
      unitPrice: canViewAmounts ? line.unitPrice || (material.unitPrice == null ? '' : String(material.unitPrice)) : '',
      showManualMaterialId: false,
    });
  };

  const runOcr = async (source: 'camera' | 'library') => {
    const permission = source === 'camera'
      ? await ImagePicker.requestCameraPermissionsAsync()
      : await ImagePicker.requestMediaLibraryPermissionsAsync();
    if (!permission.granted) {
      Alert.alert('需要照片权限', '请允许访问相机或相册，才能识别送货单。下一步：也可以继续改用手工录入。');
      return;
    }

    const result = source === 'camera'
      ? await ImagePicker.launchCameraAsync({ mediaTypes: ['images'], quality: 0.82 })
      : await ImagePicker.launchImageLibraryAsync({ mediaTypes: ['images'], quality: 0.82 });
    if (result.canceled || !result.assets[0]?.uri) return;

    const asset = result.assets[0];
    setOcrPhotoUri(asset.uri);
    setOcrSubmitting(true);
    try {
      const note = await restaurantApiClient.parseSupplierDeliveryOcr({
        fileUri: asset.uri,
        fileName: asset.fileName || `supplier_delivery_${Date.now()}.jpg`,
        mimeType: asset.mimeType || 'image/jpeg',
        deliveryDate,
        supplierId: supplierId.trim() || undefined,
        factoryId,
      });
      navigation.replace('SupplierDeliveryDetail', { noteId: note.id });
    } catch (error) {
      Alert.alert(
        'OCR 识别失败',
        `${extractErrorMessage(error)}\n\n下一步：改用手工录入，或重拍一张更清楚的送货单。`,
        [{ text: '改用手工录入' }, { text: '知道了', style: 'cancel' }],
      );
    } finally {
      setOcrSubmitting(false);
    }
  };

  const validate = (): string | null => {
    if (!supplierId.trim()) {
      return '请选择供应商。找不到供应商时，再展开手动填写供应商 ID。';
    }
    if (!/^\d{4}-\d{2}-\d{2}$/.test(deliveryDate)) {
      return '送货日期请填写为 YYYY-MM-DD，例如 2026-06-05。';
    }
    for (const [index, line] of lines.entries()) {
      const lineName = `第 ${index + 1} 行`;
      const qty = Number(line.quantity);
      const price = line.unitPrice.trim() ? Number(line.unitPrice) : undefined;
      if (!line.ingredientName.trim()) return `${lineName} 请选择或填写食材名称。`;
      if (!line.rawMaterialTypeId.trim()) return `${lineName} 需要匹配食材主数据，找不到时请展开手动 ID。`;
      if (!Number.isFinite(qty) || qty <= 0) return `${lineName} 的送货数量必须大于 0。`;
      if (!line.unit.trim()) return `${lineName} 需要填写单位。`;
      if (price !== undefined && (!Number.isFinite(price) || price < 0)) return `${lineName} 的单价不能为负数。`;
      if (line.qcResult === 'FAIL' && !line.remark.trim()) return `${lineName} 不合格时请写明问题，方便后续拒收或复核。`;
    }
    return null;
  };

  const submit = async () => {
    const error = validate();
    if (error) {
      Alert.alert('送货单还不能保存', error);
      return;
    }

    setSubmitting(true);
    try {
      const note = await restaurantApiClient.createSupplierDelivery({
        supplierId: supplierId.trim(),
        supplierName: supplierName.trim() || undefined,
        deliveryDate,
        noteNumber: noteNumber.trim() || undefined,
        lines: lines.map((line) => ({
          ingredientName: line.ingredientName.trim(),
          rawMaterialTypeId: line.rawMaterialTypeId.trim(),
          quantity: Number(line.quantity),
          unit: line.unit.trim(),
          unitPrice: line.unitPrice.trim() ? Number(line.unitPrice) : undefined,
          qcResult: line.qcResult,
          remark: line.remark.trim() || undefined,
        })),
      });
      navigation.replace('SupplierDeliveryDetail', { noteId: note.id });
    } catch (error) {
      handleError(error, { title: '送货单保存失败' });
    } finally {
      setSubmitting(false);
    }
  };

  if (loadingRefs) {
    return (
      <SafeAreaView style={styles.container} edges={['top']}>
        <Appbar.Header>
          <Appbar.BackAction onPress={() => navigation.goBack()} />
          <Appbar.Content title="新建送货单" />
        </Appbar.Header>
        <View style={styles.center}>
          <ActivityIndicator size="large" />
          <Text style={styles.loadingText}>正在加载供应商和食材...</Text>
        </View>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      <Appbar.Header>
        <Appbar.BackAction onPress={() => navigation.goBack()} />
        <Appbar.Content title="新建送货单" subtitle="仓管现场验收录入" />
        <Appbar.Action icon="check" disabled={submitting} onPress={submit} />
      </Appbar.Header>

      <ScrollView style={styles.scroll} contentContainerStyle={styles.content}>
        <Card style={styles.card}>
          <Card.Content>
            <Text style={styles.sectionTitle}>送货信息</Text>
            <View style={styles.ocrPanel}>
              <View style={styles.ocrText}>
                <Text style={styles.ocrTitle}>送货单照片 OCR</Text>
                <Text style={styles.hint}>仓管先拍照或从相册选择送货单，系统识别后生成可校对草稿。</Text>
              </View>
              {ocrPhotoUri ? <Image source={{ uri: ocrPhotoUri }} style={styles.ocrThumb} /> : null}
            </View>
            <View style={styles.row}>
              <Button
                compact
                mode="outlined"
                icon="camera"
                loading={ocrSubmitting}
                disabled={ocrSubmitting}
                onPress={() => { void runOcr('camera'); }}
                style={styles.flex}
              >
                拍照 OCR
              </Button>
              <Button
                compact
                mode="outlined"
                icon="image"
                loading={ocrSubmitting}
                disabled={ocrSubmitting}
                onPress={() => { void runOcr('library'); }}
                style={styles.flex}
              >
                相册 OCR
              </Button>
            </View>
            <TextInput
              label="供应商"
              mode="outlined"
              value={supplierSearch}
              onChangeText={(value) => {
                setSupplierSearch(value);
                setSupplierId('');
                setSupplierName(value);
              }}
              placeholder="输入供应商名称、编号或联系人"
              style={styles.field}
            />
            <View style={styles.optionList}>
              {filteredSuppliers.map((supplier) => (
                <TouchableOpacity key={supplier.id} activeOpacity={0.84} onPress={() => selectSupplier(supplier)}>
                  <View style={[styles.optionCard, supplierId === supplier.id && styles.optionCardSelected]}>
                    <Text style={styles.optionTitle}>{supplier.name}</Text>
                    <Text style={styles.optionMeta}>
                      {[supplier.supplierCode || supplier.code, supplier.contactPerson, supplier.phone].filter(Boolean).join(' · ') || '活跃供应商'}
                    </Text>
                  </View>
                </TouchableOpacity>
              ))}
              {filteredSuppliers.length === 0 ? (
                <View style={styles.emptyOption}>
                  <Text style={styles.emptyOptionText}>没有找到供应商。先确认主数据，必要时手动填写 ID。</Text>
                  <Button compact mode="outlined" onPress={() => setShowManualSupplierId(true)}>
                    手动填写供应商 ID
                  </Button>
                </View>
              ) : null}
            </View>
            {showManualSupplierId ? (
              <TextInput
                label="供应商 ID（兜底）"
                mode="outlined"
                value={supplierId}
                onChangeText={setSupplierId}
                style={styles.field}
              />
            ) : null}
            <View style={styles.row}>
              <TextInput
                label="送货日期"
                mode="outlined"
                value={deliveryDate}
                onChangeText={setDeliveryDate}
                placeholder="YYYY-MM-DD"
                style={[styles.field, styles.flex]}
              />
              <TextInput
                label="送货单号"
                mode="outlined"
                value={noteNumber}
                onChangeText={setNoteNumber}
                style={[styles.field, styles.flex]}
              />
            </View>
          </Card.Content>
        </Card>

        <View style={styles.itemsHeader}>
          <View>
            <Text style={styles.sectionTitle}>食材明细</Text>
            <Text style={styles.hint}>每行先匹配食材主数据，确认入库后会生成真实库存批次。</Text>
          </View>
          <Button compact mode="outlined" icon="plus" onPress={addLine}>
            加一行
          </Button>
        </View>

        {lines.map((line, index) => (
          <Card key={line.key} style={styles.card}>
            <Card.Content>
              <View style={styles.lineHeader}>
                <Text style={styles.lineTitle}>第 {index + 1} 行</Text>
                <IconButton icon="close" size={20} disabled={lines.length === 1} onPress={() => removeLine(line.key)} />
              </View>

              <TextInput
                label="食材"
                mode="outlined"
                value={line.materialSearch}
                onChangeText={(value) => updateLine(line.key, {
                  materialSearch: value,
                  ingredientName: value,
                  rawMaterialTypeId: '',
                })}
                placeholder="输入食材名称或编码"
                style={styles.field}
              />
              <View style={styles.optionList}>
                {filteredMaterials(line).map((material) => (
                  <TouchableOpacity key={material.id} activeOpacity={0.84} onPress={() => selectMaterial(line, material)}>
                    <View style={[styles.optionCard, line.rawMaterialTypeId === material.id && styles.optionCardSelected]}>
                      <Text style={styles.optionTitle}>{material.name}</Text>
                      <Text style={styles.optionMeta}>
                        {[material.code, material.category, material.unit].filter(Boolean).join(' · ') || '食材主数据'}
                      </Text>
                    </View>
                  </TouchableOpacity>
                ))}
                {filteredMaterials(line).length === 0 ? (
                  <View style={styles.emptyOption}>
                    <Text style={styles.emptyOptionText}>没有匹配食材。建议先维护食材主数据；紧急验收可手动填写 ID。</Text>
                    <Button compact mode="outlined" onPress={() => updateLine(line.key, { showManualMaterialId: true })}>
                      手动填写食材 ID
                    </Button>
                  </View>
                ) : null}
              </View>
              {line.showManualMaterialId ? (
                <TextInput
                  label="食材主数据 ID（兜底）"
                  mode="outlined"
                  value={line.rawMaterialTypeId}
                  onChangeText={(value) => updateLine(line.key, { rawMaterialTypeId: value })}
                  style={styles.field}
                />
              ) : null}

              <View style={styles.row}>
                <TextInput
                  label="数量"
                  mode="outlined"
                  keyboardType="decimal-pad"
                  value={line.quantity}
                  onChangeText={(value) => updateLine(line.key, { quantity: value })}
                  style={[styles.field, styles.flex]}
                />
                <TextInput
                  label="单位"
                  mode="outlined"
                  value={line.unit}
                  onChangeText={(value) => updateLine(line.key, { unit: value })}
                  style={[styles.field, styles.flex]}
                />
              </View>
              {canViewAmounts ? (
                <TextInput
                  label="单价（可空）"
                  mode="outlined"
                  keyboardType="decimal-pad"
                  value={line.unitPrice}
                  onChangeText={(value) => updateLine(line.key, { unitPrice: value })}
                  style={styles.field}
                />
              ) : (
                <Text style={styles.maskedAmount}>单价：无权限</Text>
              )}
              <View style={styles.chipRow}>
                {QC_OPTIONS.map((option) => (
                  <Chip
                    key={option.value}
                    selected={line.qcResult === option.value}
                    onPress={() => updateLine(line.key, { qcResult: option.value })}
                    style={styles.qcChip}
                  >
                    {option.label}
                  </Chip>
                ))}
              </View>
              <TextInput
                label={line.qcResult === 'FAIL' ? '问题说明（必填）' : '备注'}
                mode="outlined"
                value={line.remark}
                onChangeText={(value) => updateLine(line.key, { remark: value })}
                style={styles.field}
              />
              <HelperText type="info" visible={Boolean(line.rawMaterialTypeId)}>
                已匹配食材主数据，确认入库后会按这一项生成批次。
              </HelperText>
            </Card.Content>
          </Card>
        ))}

        <Card style={styles.totalCard}>
          <Card.Content>
            <View style={styles.totalRow}>
              <Text style={styles.totalLabel}>预估金额</Text>
              <Text style={styles.totalValue}>{canViewAmounts ? `¥${totalAmount.toFixed(2)}` : '无权限'}</Text>
            </View>
            <Text style={styles.hint}>金额只用于复核；确认入库以食材、数量、单位为准。</Text>
          </Card.Content>
        </Card>
      </ScrollView>

      <View style={styles.footer}>
        <Button mode="contained" icon="content-save" loading={submitting} disabled={submitting} onPress={submit}>
          保存为待验收草稿
        </Button>
      </View>
    </SafeAreaView>
  );
}

function extractErrorMessage(error: unknown): string {
  if (error && typeof error === 'object' && 'response' in error) {
    const response = (error as { response?: { data?: { message?: unknown } } }).response;
    if (typeof response?.data?.message === 'string' && response.data.message.trim()) {
      return response.data.message;
    }
  }
  if (error instanceof Error && error.message.trim()) return error.message;
  return '送货单照片没有识别成功';
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#F5F5F5' },
  scroll: { flex: 1 },
  content: { padding: 12, paddingBottom: 100 },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  loadingText: { marginTop: 12, color: '#6B7280' },
  card: { borderRadius: 8, marginBottom: 12, backgroundColor: '#FFFFFF' },
  ocrPanel: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 10,
    marginTop: 10,
    marginBottom: 4,
  },
  ocrText: { flex: 1 },
  ocrTitle: { fontSize: 14, fontWeight: '700', color: '#1F2937' },
  ocrThumb: { width: 56, height: 56, borderRadius: 6, backgroundColor: '#E5E7EB' },
  sectionTitle: { fontSize: 16, fontWeight: '700', color: '#1F2937' },
  hint: { fontSize: 12, color: '#6B7280', marginTop: 4, lineHeight: 18 },
  field: { backgroundColor: '#FFFFFF', marginTop: 10 },
  row: { flexDirection: 'row', gap: 8 },
  flex: { flex: 1 },
  optionList: { marginTop: 8 },
  optionCard: {
    borderWidth: 1,
    borderColor: '#E5E7EB',
    borderRadius: 8,
    paddingVertical: 9,
    paddingHorizontal: 10,
    marginBottom: 8,
    backgroundColor: '#FFFFFF',
  },
  optionCardSelected: { borderColor: '#1890FF', backgroundColor: '#E6F7FF' },
  optionTitle: { fontSize: 14, fontWeight: '700', color: '#1F2937' },
  optionMeta: { fontSize: 12, color: '#6B7280', marginTop: 3 },
  emptyOption: {
    borderWidth: 1,
    borderColor: '#F59E0B',
    borderRadius: 8,
    padding: 10,
    backgroundColor: '#FFFBEB',
    marginBottom: 8,
  },
  emptyOptionText: { fontSize: 12, color: '#92400E', lineHeight: 18, marginBottom: 6 },
  itemsHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 8,
  },
  lineHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  lineTitle: { fontSize: 14, fontWeight: '700', color: '#374151' },
  chipRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginTop: 8 },
  qcChip: { marginBottom: 4 },
  totalCard: { borderRadius: 8, marginBottom: 12, backgroundColor: '#F0FDF4' },
  totalRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  totalLabel: { fontSize: 14, color: '#166534' },
  totalValue: { fontSize: 22, color: '#166534', fontWeight: '800' },
  maskedAmount: { marginTop: 10, color: '#6B7280', fontSize: 13 },
  footer: {
    padding: 12,
    backgroundColor: '#FFFFFF',
    borderTopWidth: 1,
    borderTopColor: '#E5E7EB',
  },
});

export default SupplierDeliveryCreateScreen;
