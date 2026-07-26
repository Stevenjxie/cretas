import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Alert, ScrollView, StyleSheet, View } from 'react-native';
import {
  ActivityIndicator,
  Appbar,
  Button,
  Card,
  IconButton,
  Menu,
  Text,
  TextInput,
} from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useNavigation } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';

import { FAManagementStackParamList } from '../../../types/navigation';
import { purchaseApiClient, CreatePurchaseOrderRequest } from '../../../services/api/purchaseApiClient';
import { supplierApiClient, Supplier } from '../../../services/api/supplierApiClient';
import {
  materialTypeApiClient,
  MaterialType,
} from '../../../services/api/materialTypeApiClient';
import {
  materialPackagingApiClient,
  MaterialPackagingHierarchy,
} from '../../../services/api/materialPackagingApiClient';
import {
  DynamicForm,
  DynamicFormRef,
  FormSchema,
  schemaService,
  purchaseOrderSchema,
} from '../../../formily';
import { useAuthStore } from '../../../store/authStore';
import { logger } from '../../../utils/logger';
import { formatNumberWithCommas } from '../../../utils/formatters';

const log = logger.createContextLogger('PurchaseOrderCreate');

type Nav = NativeStackNavigationProp<FAManagementStackParamList>;

interface DraftItem {
  key: string; // 仅前端 React key, 提交时丢弃
  materialTypeId: string;
  materialName: string;
  materialUnit: string; // 原料默认单位 (一级)
  quantity: string;
  unit: string; // 实际下单单位 (可能是 1/2/3 级)
  materialPackagingSpecId: string;
  unitPrice: string;
  remark?: string;
}

const blankItem = (): DraftItem => ({
  key: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
  materialTypeId: '',
  materialName: '',
  materialUnit: '',
  quantity: '',
  unit: '',
  materialPackagingSpecId: '',
  unitPrice: '',
  remark: '',
});

export default function PurchaseOrderCreateScreen() {
  const navigation = useNavigation<Nav>();
  const { user } = useAuthStore();
  const factoryId = user?.factoryId;

  const [suppliers, setSuppliers] = useState<Supplier[]>([]);
  const [materials, setMaterials] = useState<MaterialType[]>([]);
  const [packagingByMaterial, setPackagingByMaterial] = useState<Record<string, MaterialPackagingHierarchy | null>>({});

  // 头部表单 (DynamicForm) — supplier/expectedDate/remark; Canvas 可加自定义字段
  const headerFormRef = useRef<DynamicFormRef>(null);
  const [headerSchema, setHeaderSchema] = useState<FormSchema>(purchaseOrderSchema);
  const [headerSchemaReady, setHeaderSchemaReady] = useState(false);
  const [headerValues, setHeaderValues] = useState<Record<string, any>>({});

  const [items, setItems] = useState<DraftItem[]>([blankItem()]);
  const [openMenuFor, setOpenMenuFor] = useState<{ kind: 'material' | 'unit'; key: string } | null>(null);

  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    (async () => {
      try {
        const [supplierList, materialList, mergedSchemaResult] = await Promise.all([
          supplierApiClient.getActiveSuppliers(factoryId),
          materialTypeApiClient.getActiveMaterialTypes(factoryId),
          schemaService.getMergedSchema('PURCHASE_ORDER', purchaseOrderSchema, factoryId),
        ]);
        setSuppliers(supplierList || []);
        setMaterials(materialList?.data || []);

        // 注入 supplier enum 到 schema
        const supplierOptions = (supplierList || []).map((s) => ({
          label: `${s.name} (${s.supplierCode || s.code || ''})`.trim(),
          value: s.id,
        }));
        const properties = { ...mergedSchemaResult.schema.properties };
        if (properties.supplierId) {
          properties.supplierId = { ...properties.supplierId, type: properties.supplierId.type || 'string', enum: supplierOptions };
        }
        setHeaderSchema({ ...mergedSchemaResult.schema, properties });
        setHeaderSchemaReady(true);

        log.info('采购订单创建页 schema + 数据加载', {
          suppliers: supplierList?.length,
          materials: materialList?.data?.length,
          canvasCustom: mergedSchemaResult.isCustomized,
        });
      } catch (err) {
        log.error('加载供应商/原料/schema 失败', err as Error);
        Alert.alert('错误', '加载页面数据失败');
      } finally {
        setLoading(false);
      }
    })();
  }, [factoryId]);

  const totalAmount = useMemo(() => {
    return items.reduce((sum, it) => {
      const qty = Number(it.quantity);
      const price = Number(it.unitPrice);
      if (!isFinite(qty) || !isFinite(price)) return sum;
      return sum + qty * price;
    }, 0);
  }, [items]);

  // 选定原料后, 获取该原料的包装层级 (用于单位下拉)
  const ensurePackagingLoaded = async (materialId: string): Promise<MaterialPackagingHierarchy | null> => {
    if (packagingByMaterial[materialId] !== undefined) return packagingByMaterial[materialId] || null;
    try {
      const data = await materialPackagingApiClient.getByMaterial(materialId);
      setPackagingByMaterial((prev) => ({ ...prev, [materialId]: data }));
      return data;
    } catch {
      setPackagingByMaterial((prev) => ({ ...prev, [materialId]: null }));
      return null;
    }
  };

  const updateItem = (key: string, patch: Partial<DraftItem>) => {
    setItems((prev) => prev.map((it) => (it.key === key ? { ...it, ...patch } : it)));
  };

  const removeItem = (key: string) => {
    setItems((prev) => (prev.length === 1 ? prev : prev.filter((it) => it.key !== key)));
  };

  const addItem = () => {
    setItems((prev) => [...prev, blankItem()]);
  };

  const handleSubmit = async () => {
    // 头部值从 DynamicForm 取
    const header = headerFormRef.current?.getValues() || headerValues;
    const supplierId = header.supplierId;
    if (!supplierId) {
      Alert.alert('提示', '请选择供应商');
      return;
    }
    const cleanedItems = items.filter((it) => it.materialTypeId && Number(it.quantity) > 0 && Number(it.unitPrice) >= 0 && it.unit);
    if (cleanedItems.length === 0) {
      Alert.alert('提示', '至少填写一行有效明细');
      return;
    }

    const payload: CreatePurchaseOrderRequest = {
      supplierId,
      expectedDeliveryDate: header.expectedDeliveryDate || undefined,
      remark: header.remark || undefined,
      items: cleanedItems.map((it) => ({
        materialTypeId: it.materialTypeId,
        materialPackagingSpecId: it.materialPackagingSpecId || undefined,
        quantity: Number(it.quantity),
        unitPrice: Number(it.unitPrice),
        unit: it.unit,
      })),
    };

    try {
      setSubmitting(true);
      await purchaseApiClient.createOrder(payload, factoryId);
      log.info('采购订单创建成功', { supplierId, items: cleanedItems.length });
      Alert.alert('成功', '采购订单已创建为草稿', [
        { text: '确定', onPress: () => navigation.goBack() },
      ]);
    } catch (err) {
      log.error('创建采购订单失败', err as Error);
      const msg = err instanceof Error ? err.message : '创建失败';
      Alert.alert('错误', msg);
    } finally {
      setSubmitting(false);
    }
  };

  // W-ABA-1 抄码品工具函数 — 查行对应的 MaterialType + 判断是否抄码品
  const getSelectedMaterial = (item: DraftItem): MaterialType | undefined =>
    materials.find((m) => m.id === item.materialTypeId);

  const isAbacaItem = (item: DraftItem): boolean =>
    !!getSelectedMaterial(item)?.isAbacaPackaging;

  // 单位选项: 该行原料的 1/2/3 级单位 + 该原料默认 unit (兜底)
  // 抄码品锁定为 abacaDefaultUnit (默认 kg), 不允许选箱/盒等包装级单位
  // — 因为入库以实际称重为准, 箱数无意义.
  const getUnitOptionsFor = (item: DraftItem): string[] => {
    const m = getSelectedMaterial(item);
    if (m?.isAbacaPackaging) {
      return [m.abacaDefaultUnit || 'kg'];
    }
    const set = new Set<string>();
    if (item.materialUnit) set.add(item.materialUnit);
    const pkg = packagingByMaterial[item.materialTypeId];
    if (pkg) {
      for (const spec of pkg.packagingSpecs || []) {
        if (spec.active !== false && spec.packageUnit) set.add(spec.packageUnit);
      }
      if (pkg.level1Unit) set.add(pkg.level1Unit);
      if (pkg.level2Unit) set.add(pkg.level2Unit);
      if (pkg.level3Unit) set.add(pkg.level3Unit);
    }
    if (item.unit && !set.has(item.unit)) set.add(item.unit);
    return Array.from(set);
  };

  if (loading) {
    return (
      <SafeAreaView style={styles.container} edges={['top']}>
        <Appbar.Header>
          <Appbar.BackAction onPress={() => navigation.goBack()} />
          <Appbar.Content title="新建采购订单" />
        </Appbar.Header>
        <View style={styles.center}>
          <ActivityIndicator size="large" />
          <Text style={styles.loadingText}>加载中...</Text>
        </View>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      <Appbar.Header>
        <Appbar.BackAction onPress={() => navigation.goBack()} />
        <Appbar.Content title="新建采购订单" />
        <Appbar.Action icon="check" disabled={submitting} onPress={handleSubmit} testID="purchase-order-create-submit-appbar" />
      </Appbar.Header>

      <ScrollView style={styles.scroll} contentContainerStyle={styles.scrollContent}>
        {/* 基本信息 — Schema 驱动, Canvas 可改 */}
        <Card style={styles.card}>
          <Card.Content>
            <Text style={styles.sectionTitle}>订单信息</Text>
            {headerSchemaReady ? (
              <DynamicForm
                ref={headerFormRef}
                schema={headerSchema}
                initialValues={headerValues}
                showSubmitButton={false}
                scrollable={false}
                onValuesChange={(vals) => setHeaderValues(vals)}
              />
            ) : (
              <View style={styles.center}>
                <ActivityIndicator />
                <Text style={styles.loadingText}>加载表单 schema 中...</Text>
              </View>
            )}
          </Card.Content>
        </Card>

        {/* 明细列表 */}
        <View style={styles.itemsHeaderRow}>
          <Text style={styles.sectionTitle}>采购明细 ({items.length})</Text>
          <Button mode="outlined" icon="plus" onPress={addItem} compact>
            添加一行
          </Button>
        </View>

        {items.map((item, idx) => {
          const unitOptions = getUnitOptionsFor(item);
          const subtotal = (Number(item.quantity) || 0) * (Number(item.unitPrice) || 0);
          return (
            <Card key={item.key} style={styles.itemCard}>
              <Card.Content>
                <View style={styles.itemHeaderRow}>
                  <Text style={styles.itemIndex}>第 {idx + 1} 行</Text>
                  <IconButton
                    icon="close"
                    size={20}
                    disabled={items.length === 1}
                    onPress={() => removeItem(item.key)}
                  />
                </View>

                {/* 原料 */}
                <Menu
                  visible={openMenuFor?.kind === 'material' && openMenuFor.key === item.key}
                  onDismiss={() => setOpenMenuFor(null)}
                  anchor={
                    <TextInput
                      label="原料 *"
                      value={item.materialName}
                      mode="outlined"
                      style={styles.field}
                      editable={false}
                      right={<TextInput.Icon icon="menu-down" onPress={() => setOpenMenuFor({ kind: 'material', key: item.key })} />}
                      onPressIn={() => setOpenMenuFor({ kind: 'material', key: item.key })}
                    />
                  }
                >
                  {materials.length === 0 ? (
                    <Menu.Item title="无可选原料" disabled />
                  ) : (
                    materials.map((m) => (
                      <Menu.Item
                        key={m.id}
                        title={`${m.name} (${m.code})${m.isAbacaPackaging ? ' 🥩抄码' : ''}`}
                        onPress={() => {
                          // 抄码品锁单位为 abacaDefaultUnit (默认 kg), 防止用户选成箱级
                          const forcedUnit = m.isAbacaPackaging
                            ? (m.abacaDefaultUnit || 'kg')
                            : (item.unit || m.unit);
                          updateItem(item.key, {
                            materialTypeId: m.id,
                            materialName: m.name,
                            materialUnit: m.unit,
                            unit: forcedUnit,
                            materialPackagingSpecId: '',
                          });
                          setOpenMenuFor(null);
                          void ensurePackagingLoaded(m.id).then((pkg) => {
                            if (m.isAbacaPackaging) return;
                            const active = (pkg?.packagingSpecs || []).filter((spec) => spec.active !== false);
                            const selected = active.find((spec) => spec.defaultSpec) || (active.length === 1 ? active[0] : undefined);
                            if (selected) {
                              updateItem(item.key, {
                                unit: selected.packageUnit,
                                materialPackagingSpecId: selected.id,
                              });
                            }
                          });
                        }}
                      />
                    ))
                  )}
                </Menu>

                {/* W-ABA-1 抄码品 banner — 提示用户入库按实际称重, 箱数无意义 */}
                {isAbacaItem(item) && (
                  <View style={styles.abacaBanner}>
                    <Text style={styles.abacaBannerText}>
                      🥩 本品为抄码品 — 入库时按实际称重{' '}
                      {getSelectedMaterial(item)?.abacaUnitPerBox
                        ? `(${getSelectedMaterial(item)?.abacaUnitPerBox})`
                        : '(每箱重量不一)'}
                    </Text>
                  </View>
                )}

                {/* 数量 + 单位 (宽行) */}
                <View style={styles.row}>
                  <TextInput
                    label={isAbacaItem(item) ? '估算重量 *' : '数量 *'}
                    value={item.quantity}
                    onChangeText={(t) => updateItem(item.key, { quantity: t })}
                    mode="outlined"
                    keyboardType="numeric"
                    style={[styles.field, styles.flex2]}
                    placeholder={isAbacaItem(item) ? '入库以实际称重为准' : undefined}
                  />
                  <Menu
                    visible={openMenuFor?.kind === 'unit' && openMenuFor.key === item.key && !isAbacaItem(item)}
                    onDismiss={() => setOpenMenuFor(null)}
                    anchor={
                      <TextInput
                        label="单位 *"
                        value={item.unit}
                        mode="outlined"
                        editable={false}
                        style={[styles.field, styles.flex1]}
                        right={
                          isAbacaItem(item)
                            ? <TextInput.Icon icon="lock" />
                            : <TextInput.Icon icon="menu-down" onPress={() => setOpenMenuFor({ kind: 'unit', key: item.key })} />
                        }
                        onPressIn={isAbacaItem(item)
                          ? undefined
                          : () => setOpenMenuFor({ kind: 'unit', key: item.key })}
                      />
                    }
                  >
                    {unitOptions.length === 0 ? (
                      <Menu.Item title="先选原料" disabled />
                    ) : (
                      unitOptions.map((u) => (
                        <Menu.Item
                          key={u}
                          title={(() => {
                            const spec = (packagingByMaterial[item.materialTypeId]?.packagingSpecs || [])
                              .find((candidate) => candidate.packageUnit === u);
                            return spec
                              ? `${spec.name} · 1${spec.packageUnit}=${spec.conversionFactor}${spec.baseUnit}`
                              : `${u}（基本单位）`;
                          })()}
                          onPress={() => {
                            const spec = (packagingByMaterial[item.materialTypeId]?.packagingSpecs || [])
                              .find((candidate) => candidate.packageUnit === u);
                            updateItem(item.key, {
                              unit: u,
                              materialPackagingSpecId: spec?.id || '',
                            });
                            setOpenMenuFor(null);
                          }}
                        />
                      ))
                    )}
                  </Menu>
                </View>
                {item.unit && Number(item.quantity) > 0 && (
                  <Text style={styles.packagingPreview}>
                    {(() => {
                      const spec = (packagingByMaterial[item.materialTypeId]?.packagingSpecs || [])
                        .find((candidate) => candidate.id === item.materialPackagingSpecId);
                      const baseQuantity = spec
                        ? Number(item.quantity) * Number(spec.conversionFactor)
                        : Number(item.quantity);
                      const baseUnit = spec?.baseUnit || item.materialUnit || item.unit;
                      return `折合库存：${baseQuantity} ${baseUnit}`;
                    })()}
                  </Text>
                )}

                {/* 单价 + 小计 */}
                <View style={styles.row}>
                  <TextInput
                    label="单价 *"
                    value={item.unitPrice}
                    onChangeText={(t) => updateItem(item.key, { unitPrice: t })}
                    mode="outlined"
                    keyboardType="numeric"
                    style={[styles.field, styles.flex1]}
                    placeholder="0.00"
                  />
                  <TextInput
                    label="小计"
                    value={subtotal > 0 ? `¥${formatNumberWithCommas(subtotal)}` : ''}
                    mode="outlined"
                    style={[styles.field, styles.flex1]}
                    editable={false}
                  />
                </View>
              </Card.Content>
            </Card>
          );
        })}

        {/* 合计 */}
        <Card style={styles.totalCard}>
          <Card.Content>
            <View style={styles.totalRow}>
              <Text style={styles.totalLabel}>合计金额</Text>
              <Text style={styles.totalValue}>¥{formatNumberWithCommas(totalAmount)}</Text>
            </View>
          </Card.Content>
        </Card>

        <View style={styles.bottomSpacer} />
      </ScrollView>

      <View style={styles.footer}>
        <Button
          mode="contained"
          onPress={handleSubmit}
          loading={submitting}
          disabled={submitting}
          icon="check"
          testID="purchase-order-create-submit"
        >
          创建为草稿
        </Button>
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f5f5f5' },
  scroll: { flex: 1 },
  scrollContent: { padding: 12, paddingBottom: 100 },
  center: { flex: 1, justifyContent: 'center', alignItems: 'center' },
  loadingText: { marginTop: 12, color: '#666' },

  card: { marginBottom: 12, borderRadius: 8 },
  itemCard: { marginBottom: 10, borderRadius: 8 },
  totalCard: { marginTop: 8, borderRadius: 8, backgroundColor: '#fff8e1' },

  sectionTitle: { fontSize: 16, fontWeight: '600', color: '#333', marginBottom: 8 },
  field: { marginBottom: 10, backgroundColor: 'white' },

  row: { flexDirection: 'row', gap: 8 },
  flex1: { flex: 1 },
  flex2: { flex: 2 },

  itemsHeaderRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginTop: 4,
    marginBottom: 8,
  },
  itemHeaderRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 4,
  },
  itemIndex: { fontSize: 13, color: '#666', fontWeight: '500' },

  totalRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  totalLabel: { fontSize: 15, color: '#666' },
  totalValue: { fontSize: 22, fontWeight: '700', color: '#e6a23c' },

  bottomSpacer: { height: 24 },

  footer: {
    padding: 12,
    backgroundColor: '#fff',
    borderTopWidth: 1,
    borderTopColor: '#e0e0e0',
  },

  // W-ABA-1 抄码品提示条
  abacaBanner: {
    backgroundColor: '#fef3c7',  // 浅黄色背景, 区分常规
    borderLeftWidth: 3,
    borderLeftColor: '#f59e0b',
    paddingVertical: 6,
    paddingHorizontal: 10,
    marginBottom: 8,
    borderRadius: 4,
  },
  abacaBannerText: {
    fontSize: 12,
    color: '#92400e',
    lineHeight: 18,
  },
  packagingPreview: {
    fontSize: 13,
    color: '#2563eb',
    marginTop: -4,
    marginBottom: 10,
  },
});
