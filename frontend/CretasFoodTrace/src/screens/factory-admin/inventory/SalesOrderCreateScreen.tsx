import React, { useEffect, useMemo, useState } from 'react';
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
import { salesApiClient, CreateSalesOrderRequest, SalesOrder } from '../../../services/api/salesApiClient';
import { customerApiClient, Customer } from '../../../services/api/customerApiClient';
import { productTypeApiClient, ProductPackagingSpec, ProductType } from '../../../services/api/productTypeApiClient';
import { useAuthStore } from '../../../store/authStore';
import { formatNumberWithCommas } from '../../../utils/formatters';

type Nav = NativeStackNavigationProp<FAManagementStackParamList>;

interface DraftItem {
  key: string;
  productTypeId: string;
  productName: string;
  quantity: string;
  unitPrice: string;
  unit: string;
  packagingSpecId: string;
  packagingSpecs: ProductPackagingSpec[];
}

const todayIso = () => new Date().toISOString().slice(0, 10);

const tomorrowIso = () => {
  const next = new Date();
  next.setDate(next.getDate() + 1);
  return next.toISOString().slice(0, 10);
};

const blankItem = (): DraftItem => ({
  key: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
  productTypeId: '',
  productName: '',
  quantity: '',
  unitPrice: '',
  unit: '',
  packagingSpecId: '',
  packagingSpecs: [],
});

export default function SalesOrderCreateScreen() {
  const navigation = useNavigation<Nav>();
  const { user } = useAuthStore();
  const factoryId = user?.factoryId;

  const [customers, setCustomers] = useState<Customer[]>([]);
  const [products, setProducts] = useState<ProductType[]>([]);
  const [customerId, setCustomerId] = useState('');
  const [customerName, setCustomerName] = useState('');
  const [requiredDeliveryDate, setRequiredDeliveryDate] = useState(tomorrowIso());
  const [remark, setRemark] = useState('');
  const [items, setItems] = useState<DraftItem[]>([blankItem()]);
  const [openMenuFor, setOpenMenuFor] = useState<{ kind: 'customer' | 'product' | 'packaging'; key?: string } | null>(null);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [createdOrder, setCreatedOrder] = useState<SalesOrder | null>(null);

  useEffect(() => {
    (async () => {
      try {
        const [customerList, productList] = await Promise.all([
          customerApiClient.getActiveCustomers(factoryId),
          productTypeApiClient.getActiveProductTypes(factoryId),
        ]);
        setCustomers(customerList || []);
        setProducts(productList || []);
      } catch (error) {
        const message = error instanceof Error ? error.message : '页面数据加载失败';
        Alert.alert('错误', message);
      } finally {
        setLoading(false);
      }
    })();
  }, [factoryId]);

  const totalAmount = useMemo(() => {
    return items.reduce((sum, item) => {
      const quantity = Number(item.quantity);
      const price = Number(item.unitPrice);
      if (!Number.isFinite(quantity) || !Number.isFinite(price)) return sum;
      return sum + quantity * price;
    }, 0);
  }, [items]);

  const updateItem = (key: string, patch: Partial<DraftItem>) => {
    setItems((prev) => prev.map((item) => (item.key === key ? { ...item, ...patch } : item)));
  };

  const addItem = () => {
    setItems((prev) => [...prev, blankItem()]);
  };

  const removeItem = (key: string) => {
    setItems((prev) => (prev.length === 1 ? prev : prev.filter((item) => item.key !== key)));
  };

  const packagingOptions = (item: DraftItem) =>
    item.packagingSpecs.filter((spec) => spec.active !== false && spec.packageUnit === item.unit);

  const handleUnitChange = (item: DraftItem, unit: string) => {
    const options = item.packagingSpecs.filter((spec) => spec.active !== false && spec.packageUnit === unit);
    updateItem(item.key, {
      unit,
      packagingSpecId: options.length === 1 ? options[0]!.id : '',
    });
  };

  const selectProduct = async (item: DraftItem, product: ProductType) => {
    updateItem(item.key, {
      productTypeId: product.id,
      productName: product.name,
      unit: product.unit || 'kg',
      unitPrice: product.unitPrice != null ? String(product.unitPrice) : item.unitPrice,
      packagingSpecId: '',
      packagingSpecs: [],
    });
    setOpenMenuFor(null);
    try {
      const specs = await productTypeApiClient.getPackagingSpecs(product.id, factoryId);
      updateItem(item.key, { packagingSpecs: specs });
    } catch (error) {
      const message = error instanceof Error ? error.message : '包装规格加载失败';
      Alert.alert('错误', message);
    }
  };

  const validate = (): DraftItem[] | null => {
    if (!customerId) {
      Alert.alert('提示', '请选择客户');
      return null;
    }
    if (requiredDeliveryDate && !/^\d{4}-\d{2}-\d{2}$/.test(requiredDeliveryDate)) {
      Alert.alert('提示', '要求交付日期格式应为 YYYY-MM-DD');
      return null;
    }

    const cleanedItems = items.filter((item) => item.productTypeId || item.quantity || item.unitPrice);
    if (cleanedItems.length === 0) {
      Alert.alert('提示', '至少填写一行产品明细');
      return null;
    }

    const invalidLine = cleanedItems.find((item) => {
      const quantity = Number(item.quantity);
      const unitPrice = Number(item.unitPrice);
      return !item.productTypeId || !item.unit || !Number.isFinite(quantity) || quantity <= 0
        || !Number.isFinite(unitPrice) || unitPrice < 0;
    });

    if (invalidLine) {
      Alert.alert('提示', '产品、数量、单位、单价必须填写完整，数量需大于 0，单价不能小于 0');
      return null;
    }

    const missingPackaging = cleanedItems.find(
      (item) => packagingOptions(item).length > 1 && !item.packagingSpecId
    );
    if (missingPackaging) {
      Alert.alert('提示', `${missingPackaging.productName || '产品'}有多个装箱规格，请选择本次使用的箱规`);
      return null;
    }

    return cleanedItems;
  };

  const handleSubmit = async () => {
    if (createdOrder) {
      navigation.goBack();
      return;
    }

    const cleanedItems = validate();
    if (!cleanedItems) return;

    const payload: CreateSalesOrderRequest = {
      customerId,
      orderDate: todayIso(),
      requiredDeliveryDate: requiredDeliveryDate || undefined,
      remark: remark || undefined,
      items: cleanedItems.map((item) => ({
        productTypeId: item.productTypeId,
        productName: item.productName,
        quantity: Number(item.quantity),
        unitPrice: Number(item.unitPrice),
        unit: item.unit,
        taxRate: 0,
        packagingSpecId: item.packagingSpecId || undefined,
      })),
    };

    try {
      setSubmitting(true);
      const response = await salesApiClient.createOrder(payload, factoryId);
      setCreatedOrder(response.data);
    } catch (error) {
      const message = error instanceof Error ? error.message : '创建销售订单失败';
      Alert.alert('错误', message);
    } finally {
      setSubmitting(false);
    }
  };

  if (loading) {
    return (
      <SafeAreaView style={styles.container} edges={['top']} testID="sales-order-create-screen">
        <Appbar.Header>
          <Appbar.BackAction onPress={() => navigation.goBack()} />
          <Appbar.Content title="新建销售订单" />
        </Appbar.Header>
        <View style={styles.center}>
          <ActivityIndicator size="large" />
          <Text style={styles.loadingText}>加载中...</Text>
        </View>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={styles.container} edges={['top']} testID="sales-order-create-screen">
      <Appbar.Header>
        <Appbar.BackAction onPress={() => navigation.goBack()} />
        <Appbar.Content title="新建销售订单" />
        <Appbar.Action icon="check" testID="sales-create-submit-icon" disabled={submitting || !!createdOrder} onPress={handleSubmit} />
      </Appbar.Header>

      <ScrollView style={styles.scroll} contentContainerStyle={styles.scrollContent}>
        {createdOrder && (
          <Card style={styles.successCard} testID="sales-create-success-card">
            <Card.Content>
              <Text style={styles.successTitle}>订单已创建</Text>
              <Text style={styles.successText}>{createdOrder.orderNumber || createdOrder.id}</Text>
              <Text style={styles.successHint}>状态为草稿，确认后进入后续财务/生产流程。</Text>
            </Card.Content>
          </Card>
        )}

        <Card style={styles.card}>
          <Card.Content>
            <Text style={styles.sectionTitle}>订单信息</Text>
            <Menu
              visible={openMenuFor?.kind === 'customer'}
              onDismiss={() => setOpenMenuFor(null)}
              anchor={
                <View style={styles.selectorBlock}>
                  <Text style={styles.fieldLabel}>客户</Text>
                  <Button
                  testID="sales-create-customer"
                  mode="outlined"
                  icon="chevron-down"
                  contentStyle={styles.selectorContent}
                  labelStyle={styles.selectorLabel}
                  onPress={() => setOpenMenuFor({ kind: 'customer' })}
                >
                  {customerName || '请选择客户'}
                </Button>
                </View>
              }
            >
              {customers.map((customer) => (
                <Menu.Item
                  key={customer.id}
                  testID={`sales-create-customer-option-${customer.id}`}
                  title={`${customer.name}${customer.customerCode ? ` (${customer.customerCode})` : ''}`}
                  onPress={() => {
                    setCustomerId(customer.id);
                    setCustomerName(customer.name);
                    setOpenMenuFor(null);
                  }}
                />
              ))}
              {customers.length === 0 && <Menu.Item title="暂无可用客户" disabled />}
            </Menu>

            <View style={styles.fieldBlock}>
              <Text style={styles.fieldLabel}>要求交付日期</Text>
              <TextInput
                testID="sales-create-required-date"
                value={requiredDeliveryDate}
                onChangeText={setRequiredDeliveryDate}
                mode="outlined"
                placeholder="YYYY-MM-DD"
                style={styles.input}
              />
            </View>

            <View style={styles.fieldBlock}>
              <Text style={styles.fieldLabel}>备注</Text>
              <TextInput
                testID="sales-create-remark"
                value={remark}
                onChangeText={setRemark}
                mode="outlined"
                multiline
                numberOfLines={2}
                style={styles.input}
              />
            </View>
          </Card.Content>
        </Card>

        <Card style={styles.card}>
          <Card.Content>
            <View style={styles.sectionHeader}>
              <Text style={styles.sectionTitle}>产品明细</Text>
              <Button compact mode="outlined" onPress={addItem} testID="sales-create-add-item">加一行</Button>
            </View>

            {items.map((item, index) => (
              <View key={item.key} style={styles.itemBlock} testID={`sales-create-item-${index}`}>
                <View style={styles.itemHeader}>
                  <Text style={styles.itemTitle}>第 {index + 1} 行</Text>
                  {items.length > 1 && (
                    <IconButton
                      icon="delete-outline"
                      size={20}
                      onPress={() => removeItem(item.key)}
                      testID={`sales-create-remove-item-${index}`}
                    />
                  )}
                </View>

                <Menu
                  visible={openMenuFor?.kind === 'product' && openMenuFor.key === item.key}
                  onDismiss={() => setOpenMenuFor(null)}
                  anchor={
                    <View style={styles.selectorBlock}>
                      <Text style={styles.fieldLabel}>产品</Text>
                      <Button
                      testID={`sales-create-product-${index}`}
                      mode="outlined"
                      icon="chevron-down"
                      contentStyle={styles.selectorContent}
                      labelStyle={styles.selectorLabel}
                      onPress={() => setOpenMenuFor({ kind: 'product', key: item.key })}
                    >
                      {item.productName || '请选择产品'}
                    </Button>
                    </View>
                  }
                >
                  {products.map((product) => (
                    <Menu.Item
                      key={product.id}
                      testID={`sales-create-product-option-${product.id}`}
                      title={`${product.name}${product.productCode ? ` (${product.productCode})` : ''}`}
                      onPress={() => { void selectProduct(item, product); }}
                    />
                  ))}
                  {products.length === 0 && <Menu.Item title="暂无可用产品" disabled />}
                </Menu>

                <View style={styles.threeColumns}>
                  <View style={styles.thirdField}>
                    <Text style={styles.fieldLabel}>数量</Text>
                    <TextInput
                      testID={`sales-create-quantity-${index}`}
                      value={item.quantity}
                      onChangeText={(value) => updateItem(item.key, { quantity: value })}
                      mode="outlined"
                      keyboardType="decimal-pad"
                      style={styles.input}
                    />
                  </View>
                  <View style={styles.thirdField}>
                    <Text style={styles.fieldLabel}>单位</Text>
                    <TextInput
                      testID={`sales-create-unit-${index}`}
                      value={item.unit}
                      onChangeText={(value) => handleUnitChange(item, value)}
                      mode="outlined"
                      style={styles.input}
                    />
                  </View>
                  <View style={styles.thirdField}>
                    <Text style={styles.fieldLabel}>单价</Text>
                    <TextInput
                      testID={`sales-create-unit-price-${index}`}
                      value={item.unitPrice}
                      onChangeText={(value) => updateItem(item.key, { unitPrice: value })}
                      mode="outlined"
                      keyboardType="decimal-pad"
                      style={styles.input}
                    />
                  </View>
                </View>
                {packagingOptions(item).length > 0 && (
                  <Menu
                    visible={openMenuFor?.kind === 'packaging' && openMenuFor.key === item.key}
                    onDismiss={() => setOpenMenuFor(null)}
                    anchor={
                      <View style={styles.selectorBlock}>
                        <Text style={styles.fieldLabel}>包装规格</Text>
                        <Button
                          mode="outlined"
                          icon="chevron-down"
                          contentStyle={styles.selectorContent}
                          labelStyle={styles.selectorLabel}
                          onPress={() => setOpenMenuFor({ kind: 'packaging', key: item.key })}
                        >
                          {packagingOptions(item).find((spec) => spec.id === item.packagingSpecId)
                            ? (() => {
                                const spec = packagingOptions(item).find((entry) => entry.id === item.packagingSpecId)!;
                                return `1${spec.packageUnit}=${spec.conversionFactor}${spec.baseUnit}`;
                              })()
                            : packagingOptions(item).length > 1 ? '请选择本次箱规' : '默认箱规'}
                        </Button>
                      </View>
                    }
                  >
                    {packagingOptions(item).map((spec) => (
                      <Menu.Item
                        key={spec.id}
                        title={`1${spec.packageUnit}=${spec.conversionFactor}${spec.baseUnit}`}
                        onPress={() => {
                          updateItem(item.key, { packagingSpecId: spec.id });
                          setOpenMenuFor(null);
                        }}
                      />
                    ))}
                  </Menu>
                )}
              </View>
            ))}
          </Card.Content>
        </Card>

        <Card style={styles.summaryCard}>
          <Card.Content>
            <View style={styles.summaryRow}>
              <Text style={styles.summaryLabel}>订单合计</Text>
              <Text style={styles.summaryAmount}>¥{formatNumberWithCommas(totalAmount)}</Text>
            </View>
            <Text style={styles.guardText}>低毛利、超账期、授信异常由财务审批规则复核；销售端先保证客户、数量、价格清楚。</Text>
          </Card.Content>
        </Card>
      </ScrollView>

      <View style={styles.footer}>
        <Button
          mode="contained"
          icon="check"
          loading={submitting}
          disabled={submitting}
          onPress={handleSubmit}
          testID="sales-create-submit"
          style={styles.submitButton}
        >
          {createdOrder ? '返回订单列表' : '创建订单'}
        </Button>
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f5f7fa' },
  scroll: { flex: 1 },
  scrollContent: { padding: 12, paddingBottom: 96 },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  loadingText: { marginTop: 10, color: '#666' },
  card: { marginBottom: 12, borderRadius: 8 },
  successCard: { marginBottom: 12, borderRadius: 8, backgroundColor: '#edf8f1' },
  successTitle: { color: '#16794c', fontSize: 17, fontWeight: '800', marginBottom: 6 },
  successText: { color: '#1f2933', fontSize: 15, fontWeight: '700' },
  successHint: { color: '#4d6558', marginTop: 6, lineHeight: 20 },
  summaryCard: { marginBottom: 12, borderRadius: 8, backgroundColor: '#fffaf4' },
  sectionHeader: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: 8 },
  sectionTitle: { fontSize: 16, fontWeight: '700', color: '#1f2933', marginBottom: 8 },
  input: { marginBottom: 10, backgroundColor: '#fff' },
  fieldBlock: { marginBottom: 2 },
  selectorBlock: { marginBottom: 10 },
  fieldLabel: { marginBottom: 6, color: '#59636e', fontSize: 13 },
  selectorContent: { minHeight: 48, justifyContent: 'flex-start' },
  selectorLabel: { flex: 1, textAlign: 'left' },
  itemBlock: { paddingTop: 6, marginTop: 8, borderTopWidth: 1, borderTopColor: '#edf0f2' },
  itemHeader: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  itemTitle: { fontWeight: '600', color: '#3d4852' },
  threeColumns: { flexDirection: 'row', gap: 8 },
  thirdField: { flex: 1 },
  summaryRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  summaryLabel: { color: '#59636e', fontSize: 14 },
  summaryAmount: { color: '#d35400', fontSize: 20, fontWeight: '800' },
  guardText: { marginTop: 8, color: '#6b5b3e', lineHeight: 20 },
  footer: {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: 0,
    padding: 12,
    backgroundColor: '#ffffff',
    borderTopWidth: 1,
    borderTopColor: '#e6e8eb',
  },
  submitButton: { borderRadius: 6 },
});
