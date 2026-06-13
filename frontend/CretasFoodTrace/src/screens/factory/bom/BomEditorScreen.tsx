/**
 * M-BOM-1 工厂端 BOM 配方编辑 (Track D1).
 *
 * 主子表编辑器:
 *   - 主表: 产品 select / 出成率 / 单份成品克数 / 单位
 *   - 子表: 多个 item (materialName 显示 + materialTypeId 隐藏 + standardQuantity + yieldRate + unit + unitPrice)
 *   - 出成率自动折算 preview (per item): actualQuantity = standardQuantity / (yieldRate/100)
 *   - 总成本聚合 preview
 *   - 保存 → POST /bom/recipes (status=DRAFT)
 *   - 激活 → POST /bom/recipes/{id}/activate
 *
 * 物料选择 (Day 6 Bug-2 Fix): 集成 MaterialSelectModal 弹窗 (取代旧 Alert.prompt 占位).
 *   - "添加原料" / "更换原料" 按钮 → 打开 modal → 选择后回填 materialTypeId/Name/defaultUnit
 *   - excludeIds: 排除当前 BOM 已用过的原料 (避免重复)
 */
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { View, ScrollView, StyleSheet, Alert } from 'react-native';
import {
  Text, TextInput, Button, Surface, IconButton, Menu, Divider, useTheme,
} from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useNavigation, useRoute, RouteProp } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';

import { bomApiClient } from '../../../services/api/bomApiClient';
import {
  calculateActualQuantity,
  formatUnitDisplay,
  convertUnit,
} from '../../../types/bom';
import type {
  BomRecipe, BomRecipeItemDTO, BomUnit, BomMaterialCategory,
} from '../../../types/bom';
import type { ManagementStackParamList } from '../../../types/navigation';
import { handleError } from '../../../utils/errorHandler';
import {
  MaterialSelectModal,
  MaterialSelectResult,
} from '../../../components/MaterialSelectModal';

type Nav = NativeStackNavigationProp<ManagementStackParamList, 'BomConfigEdit'>;
type R = RouteProp<ManagementStackParamList, 'BomConfigEdit'>;

const UNIT_OPTIONS: BomUnit[] = ['g', 'kg', 'mg', 'ml', 'L', '个', '袋', '箱', '瓶', '盒'];
const CATEGORY_OPTIONS: { value: BomMaterialCategory; label: string }[] = [
  { value: 'RAW', label: '原料' },
  { value: 'AUXILIARY', label: '辅料' },
  { value: 'PACKAGING', label: '包材' },
];

interface ItemRow {
  /** Local UI id (server id assigned after persist). */
  uiId: string;
  materialTypeId: string;
  materialName: string;
  standardQuantity: string;  // string for TextInput, parse on save
  yieldRate: string;
  unit: BomUnit;
  unitPrice: string;
  materialCategory: BomMaterialCategory;
  sortOrder: number;
}

let uiIdSeq = 1;
const newRowId = () => `r${Date.now()}-${uiIdSeq++}`;

export function BomEditorScreen() {
  const theme = useTheme();
  const navigation = useNavigation<Nav>();
  const route = useRoute<R>();
  const recipeId = route.params?.recipeId;

  // Recipe-level state.
  const [recipe, setRecipe] = useState<BomRecipe | null>(null);
  const [productTypeId, setProductTypeId] = useState('');
  const [productName, setProductName] = useState('');
  const [overallYieldRate, setOverallYieldRate] = useState('100');
  const [outputQuantityPerUnit, setOutputQuantityPerUnit] = useState('200');
  const [outputUnit, setOutputUnit] = useState<BomUnit>('g');
  const [notes, setNotes] = useState('');
  const [items, setItems] = useState<ItemRow[]>([]);

  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [unitMenuFor, setUnitMenuFor] = useState<string | null>(null);
  const [outputUnitMenuOpen, setOutputUnitMenuOpen] = useState(false);

  // Day 6 Bug-2: 物料字典选择器状态
  const [materialModalForUiId, setMaterialModalForUiId] = useState<string | null>(null);

  const isEdit = !!recipeId;
  const isDraft = !recipe || recipe.status === 'DRAFT';

  const loadRecipe = useCallback(async () => {
    if (!recipeId) return;
    setLoading(true);
    try {
      const r = await bomApiClient.getRecipe(recipeId);
      setRecipe(r);
      setProductTypeId(r.productTypeId);
      setProductName(r.productName);
      setOverallYieldRate(String(r.overallYieldRate));
      setOutputQuantityPerUnit(String(r.outputQuantityPerUnit));
      setOutputUnit(r.outputUnit as BomUnit);
      setNotes(r.notes ?? '');
      setItems((r.items ?? []).map(it => ({
        uiId: newRowId(),
        materialTypeId: it.materialTypeId,
        materialName: it.materialName ?? '',
        standardQuantity: String(it.standardQuantity),
        yieldRate: String(it.yieldRate),
        unit: it.unit,
        unitPrice: it.unitPrice != null ? String(it.unitPrice) : '',
        materialCategory: it.materialCategory,
        sortOrder: it.sortOrder,
      })));
    } catch (err) {
      handleError(err, { title: '加载配方失败' });
    } finally {
      setLoading(false);
    }
  }, [recipeId]);

  useEffect(() => { loadRecipe(); }, [loadRecipe]);

  // Compute totals (preview before save).
  const totalMaterialCost = useMemo(() => {
    let total = 0;
    let anyMissing = false;
    for (const r of items) {
      const sq = parseFloat(r.standardQuantity);
      const yr = parseFloat(r.yieldRate) || 100;
      const up = parseFloat(r.unitPrice);
      if (!isNaN(sq) && !isNaN(up)) {
        total += calculateActualQuantity(sq, yr) * up;
      } else if (r.unitPrice === '') {
        anyMissing = true;
      }
    }
    return { total, anyMissing };
  }, [items]);

  const addItem = () => {
    setItems(prev => [...prev, {
      uiId: newRowId(),
      materialTypeId: '',
      materialName: '',
      standardQuantity: '',
      yieldRate: '100',
      unit: 'g',
      unitPrice: '',
      materialCategory: 'RAW',
      sortOrder: prev.length,
    }]);
  };

  const removeItem = (uiId: string) => {
    setItems(prev => prev.filter(r => r.uiId !== uiId));
  };

  const updateItem = <K extends keyof ItemRow>(uiId: string, field: K, value: ItemRow[K]) => {
    setItems(prev => prev.map(r => r.uiId === uiId ? { ...r, [field]: value } : r));
  };

  // Day 6 Bug-2: 弹出 MaterialSelectModal 让用户从 raw_material_types 字典选择.
  const openMaterialSelect = (uiId: string) => {
    setMaterialModalForUiId(uiId);
  };

  const handleMaterialSelected = (result: MaterialSelectResult) => {
    const uiId = materialModalForUiId;
    if (!uiId) return;
    setItems(prev => prev.map(r => r.uiId === uiId ? {
      ...r,
      materialTypeId: result.materialTypeId,
      materialName: result.materialName,
      // 默认单位从字典带出 (BOM 端 g 偏好, 但保留 raw_material_types.unit 作为初始值)
      unit: (r.unit && r.unit !== 'g') ? r.unit : (result.defaultUnit as BomUnit) || 'g',
    } : r));
    setMaterialModalForUiId(null);
  };

  /** Excluded IDs for modal (avoid double-add of same material). */
  const excludedMaterialIds = useMemo(
    () => items
      .filter(r => r.materialTypeId && r.uiId !== materialModalForUiId)
      .map(r => r.materialTypeId),
    [items, materialModalForUiId],
  );

  const buildPayloadItems = (): BomRecipeItemDTO[] => {
    return items.map(r => ({
      materialTypeId: r.materialTypeId,
      standardQuantity: parseFloat(r.standardQuantity),
      yieldRate: parseFloat(r.yieldRate) || 100,
      unit: r.unit,
      unitPrice: r.unitPrice ? parseFloat(r.unitPrice) : undefined,
      materialCategory: r.materialCategory,
      sortOrder: r.sortOrder,
    }));
  };

  const validate = (): string | null => {
    if (!productTypeId.trim()) return '请填写产品类型ID';
    const oqpu = parseFloat(outputQuantityPerUnit);
    if (isNaN(oqpu) || oqpu <= 0) return '单份成品量必须 > 0';
    const oyr = parseFloat(overallYieldRate);
    if (isNaN(oyr) || oyr <= 0 || oyr > 100) return '整产品出成率必须在 0-100';
    if (items.length === 0) return '至少需要 1 个配方项';
    for (let i = 0; i < items.length; i++) {
      const r = items[i];
      if (!r) return `第 ${i + 1} 项配方数据缺失`;
      if (!r.materialTypeId) return `第 ${i + 1} 项: 请从字典选择原料`;
      const sq = parseFloat(r.standardQuantity);
      if (isNaN(sq) || sq <= 0) return `第 ${i + 1} 项: 标准用量必须 > 0`;
      const yr = parseFloat(r.yieldRate);
      if (isNaN(yr) || yr <= 0 || yr > 100) return `第 ${i + 1} 项: 出成率必须在 0-100`;
      if (!r.unit) return `第 ${i + 1} 项: 请选择单位`;
    }
    return null;
  };

  const save = async () => {
    const err = validate();
    if (err) {
      Alert.alert('请检查输入', err);
      return;
    }
    setSaving(true);
    try {
      if (isEdit) {
        await bomApiClient.updateRecipe(recipeId!, {
          productName: productName || undefined,
          overallYieldRate: parseFloat(overallYieldRate),
          outputQuantityPerUnit: parseFloat(outputQuantityPerUnit),
          outputUnit,
          items: buildPayloadItems(),
          notes: notes || undefined,
        });
        Alert.alert('保存成功', 'BOM 配方已更新');
      } else {
        const created = await bomApiClient.createRecipe({
          productTypeId,
          productName: productName || undefined,
          overallYieldRate: parseFloat(overallYieldRate),
          outputQuantityPerUnit: parseFloat(outputQuantityPerUnit),
          outputUnit,
          items: buildPayloadItems(),
          notes: notes || undefined,
        });
        Alert.alert('创建成功', `BOM 配方 ${created.recipeCode} 已创建为草稿`);
      }
      navigation.goBack();
    } catch (e) {
      handleError(e, { title: '保存失败' });
    } finally {
      setSaving(false);
    }
  };

  const activate = async () => {
    if (!recipeId) return;
    Alert.alert('确认激活', '激活后将无法修改, 且同产品其他生效版本会自动失效. 确定?', [
      { text: '取消', style: 'cancel' },
      {
        text: '激活',
        onPress: async () => {
          setSaving(true);
          try {
            await bomApiClient.activateRecipe(recipeId);
            Alert.alert('已激活', 'BOM 配方已生效');
            navigation.goBack();
          } catch (e) {
            handleError(e, { title: '激活失败' });
          } finally {
            setSaving(false);
          }
        },
      },
    ]);
  };

  return (
    <SafeAreaView style={styles.container}>
      <ScrollView contentContainerStyle={{ paddingBottom: 100 }}>
        <View style={styles.header}>
          <Text style={styles.headerTitle}>
            {isEdit ? '编辑 BOM 配方' : '新建 BOM 配方'}
          </Text>
          {recipe && (
            <Text style={styles.headerSub}>
              {recipe.recipeCode} · v{recipe.version} · {recipe.status}
            </Text>
          )}
        </View>

        <Surface style={styles.section} elevation={1}>
          <Text style={styles.sectionTitle}>产品信息</Text>
          <TextInput
            label="产品类型ID *"
            value={productTypeId}
            onChangeText={setProductTypeId}
            disabled={isEdit}
            mode="outlined"
            style={styles.input}
          />
          <TextInput
            label="产品名称"
            value={productName}
            onChangeText={setProductName}
            mode="outlined"
            style={styles.input}
          />
          <View style={styles.row}>
            <TextInput
              label="单份成品量 *"
              value={outputQuantityPerUnit}
              onChangeText={setOutputQuantityPerUnit}
              keyboardType="numeric"
              mode="outlined"
              style={[styles.input, { flex: 2 }]}
            />
            <Menu
              visible={outputUnitMenuOpen}
              onDismiss={() => setOutputUnitMenuOpen(false)}
              anchor={
                <Button
                  mode="outlined"
                  onPress={() => setOutputUnitMenuOpen(true)}
                  style={[styles.input, { flex: 1, marginLeft: 8 }]}
                  disabled={!isDraft}
                >
                  {outputUnit}
                </Button>
              }
            >
              {UNIT_OPTIONS.map(u => (
                <Menu.Item
                  key={u}
                  onPress={() => { setOutputUnit(u); setOutputUnitMenuOpen(false); }}
                  title={u}
                />
              ))}
            </Menu>
          </View>
          <TextInput
            label="整产品出成率 (0-100)"
            value={overallYieldRate}
            onChangeText={setOverallYieldRate}
            keyboardType="numeric"
            mode="outlined"
            style={styles.input}
            disabled={!isDraft}
          />
          <TextInput
            label="备注"
            value={notes}
            onChangeText={setNotes}
            mode="outlined"
            multiline
            style={styles.input}
            disabled={!isDraft}
          />
        </Surface>

        <Surface style={styles.section} elevation={1}>
          <View style={styles.sectionHeaderRow}>
            <Text style={styles.sectionTitle}>配方项 ({items.length})</Text>
            {isDraft && (
              <Button mode="contained" icon="plus" onPress={addItem} compact>
                添加原料
              </Button>
            )}
          </View>

          {items.length === 0 && (
            <Text style={styles.emptyHint}>点击"添加原料"开始配置</Text>
          )}

          {items.map((row, idx) => {
            const sq = parseFloat(row.standardQuantity);
            const yr = parseFloat(row.yieldRate) || 100;
            const actualQty = !isNaN(sq) ? calculateActualQuantity(sq, yr) : null;
            return (
              <View key={row.uiId} style={styles.itemBlock}>
                <View style={styles.itemHeader}>
                  <Text style={styles.itemIdx}>#{idx + 1}</Text>
                  <Text style={styles.itemName} numberOfLines={1}>
                    {row.materialName || <Text style={{ color: '#999' }}>(未选原料)</Text>}
                  </Text>
                  {isDraft && (
                    <IconButton icon="delete" size={20} onPress={() => removeItem(row.uiId)} />
                  )}
                </View>
                {isDraft && (
                  <Button
                    mode="outlined"
                    onPress={() => openMaterialSelect(row.uiId)}
                    style={{ marginBottom: 8 }}
                    icon="magnify"
                  >
                    {row.materialTypeId ? '更换原料' : '选择原料 (字典)'}
                  </Button>
                )}
                <View style={styles.row}>
                  <TextInput
                    label="标准用量"
                    value={row.standardQuantity}
                    onChangeText={v => updateItem(row.uiId, 'standardQuantity', v)}
                    keyboardType="numeric"
                    mode="outlined"
                    style={[styles.input, { flex: 2 }]}
                    disabled={!isDraft}
                  />
                  <Menu
                    visible={unitMenuFor === row.uiId}
                    onDismiss={() => setUnitMenuFor(null)}
                    anchor={
                      <Button
                        mode="outlined"
                        onPress={() => setUnitMenuFor(row.uiId)}
                        style={[styles.input, { flex: 1, marginLeft: 8 }]}
                        disabled={!isDraft}
                      >
                        {row.unit}
                      </Button>
                    }
                  >
                    {UNIT_OPTIONS.map(u => (
                      <Menu.Item
                        key={u}
                        onPress={() => { updateItem(row.uiId, 'unit', u); setUnitMenuFor(null); }}
                        title={u}
                      />
                    ))}
                  </Menu>
                </View>
                <View style={styles.row}>
                  <TextInput
                    label="出成率 %"
                    value={row.yieldRate}
                    onChangeText={v => updateItem(row.uiId, 'yieldRate', v)}
                    keyboardType="numeric"
                    mode="outlined"
                    style={[styles.input, { flex: 1 }]}
                    disabled={!isDraft}
                  />
                  <TextInput
                    label="单价 (¥)"
                    value={row.unitPrice}
                    onChangeText={v => updateItem(row.uiId, 'unitPrice', v)}
                    keyboardType="numeric"
                    mode="outlined"
                    style={[styles.input, { flex: 1, marginLeft: 8 }]}
                    disabled={!isDraft}
                  />
                </View>
                {actualQty != null && (
                  <>
                    <Text style={styles.previewText}>
                      实际用量 (含损耗): {formatUnitDisplay(parseFloat(actualQty.toFixed(4)), row.unit)}
                    </Text>
                    {/* Bug-3: g↔kg 自动换算 (客户 May10 line 263): 仓库 / 调拨单按 kg 显示 */}
                    {(row.unit === 'g' || row.unit === 'kg') && (
                      <Text style={styles.previewHint}>
                        仓库出库会按 {row.unit === 'g' ? 'kg' : 'g'} 自动换算
                      </Text>
                    )}
                  </>
                )}
                <Divider style={{ marginVertical: 8 }} />
              </View>
            );
          })}
        </Surface>

        {totalMaterialCost.total > 0 && (
          <Surface style={[styles.section, { backgroundColor: '#FFF8E1' }]} elevation={1}>
            <Text style={styles.totalText}>
              预估原料成本: ¥{totalMaterialCost.total.toFixed(2)}
              {totalMaterialCost.anyMissing && (
                <Text style={{ color: '#999', fontSize: 12 }}> (部分单价未填)</Text>
              )}
            </Text>
          </Surface>
        )}
      </ScrollView>

      <View style={styles.footer}>
        {isDraft && (
          <Button
            mode="contained"
            onPress={save}
            loading={saving}
            disabled={saving || loading}
            style={[styles.footerBtn, { flex: 1 }]}
          >
            保存草稿
          </Button>
        )}
        {isDraft && isEdit && (
          <Button
            mode="contained-tonal"
            onPress={activate}
            disabled={saving || loading}
            style={[styles.footerBtn, { flex: 1, marginLeft: 8 }]}
          >
            激活
          </Button>
        )}
      </View>

      {/* Day 6 Bug-2: 物料字典选择器 (取代 Alert.prompt 占位) */}
      <MaterialSelectModal
        visible={materialModalForUiId !== null}
        onDismiss={() => setMaterialModalForUiId(null)}
        onSelect={handleMaterialSelected}
        excludeIds={excludedMaterialIds}
        title="选择原料 (从字典)"
      />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f5f5f5' },
  header: { backgroundColor: '#FF6B35', paddingHorizontal: 16, paddingTop: 12, paddingBottom: 16 },
  headerTitle: { fontSize: 20, fontWeight: 'bold', color: '#fff' },
  headerSub: { fontSize: 13, color: 'rgba(255,255,255,0.85)', marginTop: 4 },
  section: { backgroundColor: '#fff', margin: 12, padding: 16, borderRadius: 12 },
  sectionHeaderRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: 12 },
  sectionTitle: { fontSize: 16, fontWeight: '600', marginBottom: 12, color: '#333' },
  input: { marginBottom: 12, backgroundColor: '#fff' },
  row: { flexDirection: 'row', alignItems: 'center' },
  itemBlock: { marginBottom: 8 },
  itemHeader: { flexDirection: 'row', alignItems: 'center', marginBottom: 8 },
  itemIdx: { fontSize: 12, color: '#999', marginRight: 8, fontWeight: '600' },
  itemName: { fontSize: 14, fontWeight: '500', flex: 1, color: '#333' },
  previewText: { fontSize: 12, color: '#1976D2', marginTop: 4, paddingHorizontal: 4 },
  previewHint: { fontSize: 11, color: '#999', marginTop: 2, paddingHorizontal: 4, fontStyle: 'italic' },
  totalText: { fontSize: 16, fontWeight: '600', color: '#FF6B35', textAlign: 'center' },
  emptyHint: { fontSize: 13, color: '#999', textAlign: 'center', paddingVertical: 16 },
  footer: { flexDirection: 'row', padding: 12, backgroundColor: '#fff', borderTopWidth: 1, borderTopColor: '#eee' },
  footerBtn: {},
});

export default BomEditorScreen;
