/**
 * 创建生产计划页面
 * 支持创建新的生产计划
 */
import React, { useState, useEffect, useCallback, useRef } from 'react';
import {
  View,
  Text,
  StyleSheet,
  ScrollView,
  TouchableOpacity,
  TextInput,
  Alert,
  ActivityIndicator,
  KeyboardAvoidingView,
  Platform,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useNavigation } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { Icon } from 'react-native-paper';
import { useTranslation } from 'react-i18next';
import { FAAIStackParamList } from '../../../types/navigation';
import {
  createProductionPlanClientRequestId,
  productionPlanApiClient,
  PlanType,
} from '../../../services/api/productionPlanApiClient';
import { productTypeApiClient, ProductType } from '../../../services/api/productTypeApiClient';

type NavigationProp = NativeStackNavigationProp<FAAIStackParamList, 'CreatePlan'>;

export function CreatePlanScreen() {
  const navigation = useNavigation<NavigationProp>();
  const { t, i18n } = useTranslation('management');

  const [loading, setLoading] = useState(false);
  const [loadingProducts, setLoadingProducts] = useState(true);
  const [products, setProducts] = useState<ProductType[]>([]);

  // 表单字段
  const [selectedProductId, setSelectedProductId] = useState('');
  const [plannedQuantity, setPlannedQuantity] = useState('');
  const [plannedDate, setPlannedDate] = useState<string>(() => {
    const tomorrow = new Date();
    tomorrow.setDate(tomorrow.getDate() + 1);
    const iso = tomorrow.toISOString();
    return iso.split('T')[0] ?? iso.substring(0, 10);
  });
  const [planType, setPlanType] = useState<PlanType>('FROM_INVENTORY');
  const [customerOrderNumber, setCustomerOrderNumber] = useState('');
  const [notes, setNotes] = useState('');
  const [showProductPicker, setShowProductPicker] = useState(false);
  const createIntentRef = useRef<{ fingerprint: string; clientRequestId: string } | null>(null);

  // 加载产品类型
  useEffect(() => {
    loadProducts();
  }, []);

  const loadProducts = async () => {
    try {
      const response = await productTypeApiClient.getProductTypes({ page: 1, limit: 100 });
      if (response.data) {
        setProducts(response.data);
      }
    } catch (err) {
      console.error('加载产品类型失败:', err);
    } finally {
      setLoadingProducts(false);
    }
  };

  const selectedProduct = products.find((p) => p.id === selectedProductId);

  const validateForm = (): boolean => {
    if (!selectedProductId) {
      Alert.alert(t('common.tip'), t('createPlan.selectProductType'));
      return false;
    }
    if (!plannedQuantity || parseFloat(plannedQuantity) <= 0) {
      Alert.alert(t('common.tip'), t('createPlan.enterValidQuantity'));
      return false;
    }
    if (!plannedDate) {
      Alert.alert(t('common.tip'), t('createPlan.selectPlanDate'));
      return false;
    }
    return true;
  };

  const handleSubmit = async () => {
    if (!validateForm()) return;

    try {
      setLoading(true);

      const payload = {
        productTypeId: selectedProductId,
        plannedQuantity: parseFloat(plannedQuantity),
        plannedDate,
        planType,
        customerOrderNumber: customerOrderNumber || undefined,
        notes: notes || undefined,
      };
      const fingerprint = JSON.stringify(payload);
      if (createIntentRef.current?.fingerprint !== fingerprint) {
        createIntentRef.current = {
          fingerprint,
          clientRequestId: createProductionPlanClientRequestId(),
        };
      }
      const response = await productionPlanApiClient.createProductionPlan({
        ...payload,
        clientRequestId: createIntentRef.current.clientRequestId,
      });

      if (response.success) {
        createIntentRef.current = null;
        const planNumber = response.data?.planNumber;
        const successMessage = planNumber
          ? `${t('createPlan.createSuccess')}\n${planNumber}`
          : t('createPlan.createSuccess');
        Alert.alert(t('common.success'), successMessage, [
          { text: t('common.confirm'), onPress: () => navigation.goBack() },
        ]);
      } else {
        Alert.alert(t('common.failure'), response.message || t('createPlan.createFailed'));
      }
    } catch (err) {
      console.error('创建生产计划失败:', err);
      Alert.alert(t('common.error'), t('createPlan.networkError'));
    } finally {
      setLoading(false);
    }
  };

  const formatDate = (dateStr: string): string => {
    return new Date(dateStr).toLocaleDateString(i18n.language, {
      year: 'numeric',
      month: 'long',
      day: 'numeric',
    });
  };

  return (
    <SafeAreaView style={styles.container}>
      {/* Header */}
      <View style={styles.header}>
        <TouchableOpacity onPress={() => navigation.goBack()} style={styles.backBtn}>
          <Icon source="arrow-left" size={24} color="#333" />
        </TouchableOpacity>
        <Text style={styles.headerTitle}>{t('createPlan.title')}</Text>
        <View style={{ width: 40 }} />
      </View>

      <KeyboardAvoidingView
        style={{ flex: 1 }}
        behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
      >
        <ScrollView style={styles.scrollView} showsVerticalScrollIndicator={false}>
          {/* 计划类型选择 */}
          <View style={styles.section}>
            <Text style={styles.sectionTitle}>{t('createPlan.planType')}</Text>
            <View style={styles.planTypeGrid}>
              <TouchableOpacity
                style={[
                  styles.planTypeItem,
                  planType === 'FROM_INVENTORY' && styles.planTypeItemActive,
                ]}
                onPress={() => setPlanType('FROM_INVENTORY')}
              >
                <Icon
                  source="package-variant"
                  size={24}
                  color={planType === 'FROM_INVENTORY' ? '#fff' : '#667eea'}
                />
                <Text
                  style={[
                    styles.planTypeLabel,
                    planType === 'FROM_INVENTORY' && styles.planTypeLabelActive,
                  ]}
                >
                  {t('createPlan.inventoryProduction')}
                </Text>
                <Text
                  style={[
                    styles.planTypeDesc,
                    planType === 'FROM_INVENTORY' && styles.planTypeDescActive,
                  ]}
                >
                  {t('createPlan.useExistingMaterials')}
                </Text>
              </TouchableOpacity>
              <TouchableOpacity
                style={[
                  styles.planTypeItem,
                  planType === 'FUTURE' && styles.planTypeItemActive,
                ]}
                onPress={() => setPlanType('FUTURE')}
              >
                <Icon
                  source="calendar-clock"
                  size={24}
                  color={planType === 'FUTURE' ? '#fff' : '#667eea'}
                />
                <Text
                  style={[
                    styles.planTypeLabel,
                    planType === 'FUTURE' && styles.planTypeLabelActive,
                  ]}
                >
                  {t('createPlan.futurePlan')}
                </Text>
                <Text
                  style={[
                    styles.planTypeDesc,
                    planType === 'FUTURE' && styles.planTypeDescActive,
                  ]}
                >
                  {t('createPlan.pendingMaterials')}
                </Text>
              </TouchableOpacity>
            </View>
          </View>

          {/* 产品选择 */}
          <View style={styles.section}>
            <Text style={styles.sectionTitle}>{t('createPlan.productTypeRequired')}</Text>
            <TouchableOpacity
              style={styles.selectField}
              onPress={() => setShowProductPicker(!showProductPicker)}
            >
              {loadingProducts ? (
                <ActivityIndicator size="small" color="#667eea" />
              ) : selectedProduct ? (
                <Text style={styles.selectValue}>{selectedProduct.name}</Text>
              ) : (
                <Text style={styles.selectPlaceholder}>{t('createPlan.selectProductTypePlaceholder')}</Text>
              )}
              <Icon source="chevron-down" size={20} color="#999" />
            </TouchableOpacity>

            {showProductPicker && (
              <View style={styles.pickerDropdown}>
                <ScrollView style={{ maxHeight: 200 }}>
                  {products.map((product) => (
                    <TouchableOpacity
                      key={product.id}
                      style={[
                        styles.pickerItem,
                        selectedProductId === product.id && styles.pickerItemActive,
                      ]}
                      onPress={() => {
                        setSelectedProductId(product.id);
                        setShowProductPicker(false);
                      }}
                    >
                      <Text
                        style={[
                          styles.pickerItemText,
                          selectedProductId === product.id && styles.pickerItemTextActive,
                        ]}
                      >
                        {product.name}
                      </Text>
                      {product.unit && (
                        <Text style={styles.pickerItemUnit}>({product.unit})</Text>
                      )}
                    </TouchableOpacity>
                  ))}
                  {products.length === 0 && (
                    <Text style={styles.pickerEmpty}>{t('createPlan.noProductTypes')}</Text>
                  )}
                </ScrollView>
              </View>
            )}
          </View>

          {/* 计划数量 */}
          <View style={styles.section}>
            <Text style={styles.sectionTitle}>{t('createPlan.plannedQuantityRequired')}</Text>
            <View style={styles.inputRow}>
              <TextInput
                style={styles.textInput}
                value={plannedQuantity}
                onChangeText={setPlannedQuantity}
                placeholder={t('createPlan.enterQuantity')}
                keyboardType="decimal-pad"
                placeholderTextColor="#999"
              />
              <Text style={styles.inputUnit}>{selectedProduct?.unit ?? 'kg'}</Text>
            </View>
          </View>

          {/* 计划日期 */}
          <View style={styles.section}>
            <Text style={styles.sectionTitle}>{t('createPlan.plannedDateRequired')}</Text>
            <View style={styles.dateDisplay}>
              <Icon source="calendar" size={20} color="#667eea" />
              <Text style={styles.dateText}>{formatDate(plannedDate)}</Text>
            </View>
            <View style={styles.quickDates}>
              {[0, 1, 2, 3].map((offset) => {
                const date = new Date();
                date.setDate(date.getDate() + offset);
                const iso = date.toISOString();
                const dateStr = iso.split('T')[0] ?? iso.substring(0, 10);
                const label = offset === 0 ? t('createPlan.today') : offset === 1 ? t('createPlan.tomorrow') : t('createPlan.daysLater', { days: offset });
                return (
                  <TouchableOpacity
                    key={offset}
                    style={[
                      styles.quickDateBtn,
                      plannedDate === dateStr && styles.quickDateBtnActive,
                    ]}
                    onPress={() => setPlannedDate(dateStr)}
                  >
                    <Text
                      style={[
                        styles.quickDateText,
                        plannedDate === dateStr && styles.quickDateTextActive,
                      ]}
                    >
                      {label}
                    </Text>
                  </TouchableOpacity>
                );
              })}
            </View>
          </View>

          {/* 订单号 */}
          <View style={styles.section}>
            <Text style={styles.sectionTitle}>{t('createPlan.customerOrderOptional')}</Text>
            <TextInput
              style={styles.textInput}
              value={customerOrderNumber}
              onChangeText={setCustomerOrderNumber}
              placeholder={t('createPlan.enterCustomerOrder')}
              placeholderTextColor="#999"
            />
          </View>

          {/* 备注 */}
          <View style={styles.section}>
            <Text style={styles.sectionTitle}>{t('createPlan.notesOptional')}</Text>
            <TextInput
              style={[styles.textInput, styles.textArea]}
              value={notes}
              onChangeText={setNotes}
              placeholder={t('createPlan.enterNotes')}
              placeholderTextColor="#999"
              multiline
              numberOfLines={3}
              textAlignVertical="top"
            />
          </View>

          {/* 提交按钮 */}
          <TouchableOpacity
            style={[styles.submitBtn, loading && styles.submitBtnDisabled]}
            onPress={handleSubmit}
            disabled={loading}
          >
            {loading ? (
              <View style={styles.buttonContent}>
                <ActivityIndicator size="small" color="#fff" />
                <Text style={styles.submitBtnText}>{t('createPlan.creating')}</Text>
              </View>
            ) : (
              <View style={styles.buttonContent}>
                <Icon source="check" size={20} color="#fff" />
                <Text style={styles.submitBtnText}>{t('createPlan.createPlanBtn')}</Text>
              </View>
            )}
          </TouchableOpacity>

          <View style={{ height: 32 }} />
        </ScrollView>
      </KeyboardAvoidingView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f5f7fa',
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    paddingVertical: 12,
    backgroundColor: '#fff',
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#e0e0e0',
  },
  backBtn: {
    width: 40,
    height: 40,
    justifyContent: 'center',
    alignItems: 'center',
  },
  headerTitle: {
    fontSize: 18,
    fontWeight: '600',
    color: '#1a202c',
  },
  scrollView: {
    flex: 1,
  },
  section: {
    marginHorizontal: 16,
    marginTop: 20,
  },
  sectionTitle: {
    fontSize: 14,
    fontWeight: '600',
    color: '#333',
    marginBottom: 10,
  },
  planTypeGrid: {
    flexDirection: 'row',
    gap: 12,
  },
  planTypeItem: {
    flex: 1,
    backgroundColor: '#fff',
    borderRadius: 12,
    padding: 16,
    alignItems: 'center',
    borderWidth: 2,
    borderColor: '#e0e0e0',
  },
  planTypeItemActive: {
    backgroundColor: '#667eea',
    borderColor: '#667eea',
  },
  planTypeLabel: {
    marginTop: 8,
    fontSize: 14,
    fontWeight: '600',
    color: '#333',
  },
  planTypeLabelActive: {
    color: '#fff',
  },
  planTypeDesc: {
    marginTop: 4,
    fontSize: 12,
    color: '#999',
  },
  planTypeDescActive: {
    color: 'rgba(255,255,255,0.8)',
  },
  selectField: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    backgroundColor: '#fff',
    borderRadius: 10,
    padding: 14,
    borderWidth: 1,
    borderColor: '#e0e0e0',
  },
  selectValue: {
    fontSize: 15,
    color: '#333',
  },
  selectPlaceholder: {
    fontSize: 15,
    color: '#999',
  },
  pickerDropdown: {
    marginTop: 8,
    backgroundColor: '#fff',
    borderRadius: 10,
    borderWidth: 1,
    borderColor: '#e0e0e0',
    overflow: 'hidden',
  },
  pickerItem: {
    flexDirection: 'row',
    alignItems: 'center',
    padding: 14,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#f0f0f0',
  },
  pickerItemActive: {
    backgroundColor: '#f0f3ff',
  },
  pickerItemText: {
    fontSize: 15,
    color: '#333',
  },
  pickerItemTextActive: {
    color: '#667eea',
    fontWeight: '500',
  },
  pickerItemUnit: {
    marginLeft: 8,
    fontSize: 13,
    color: '#999',
  },
  pickerEmpty: {
    padding: 16,
    fontSize: 14,
    color: '#999',
    textAlign: 'center',
  },
  inputRow: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#fff',
    borderRadius: 10,
    borderWidth: 1,
    borderColor: '#e0e0e0',
  },
  textInput: {
    flex: 1,
    backgroundColor: '#fff',
    borderRadius: 10,
    padding: 14,
    fontSize: 15,
    color: '#333',
    borderWidth: 1,
    borderColor: '#e0e0e0',
  },
  textArea: {
    minHeight: 80,
  },
  inputUnit: {
    paddingHorizontal: 16,
    fontSize: 14,
    color: '#666',
  },
  dateDisplay: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#fff',
    borderRadius: 10,
    padding: 14,
    borderWidth: 1,
    borderColor: '#e0e0e0',
    gap: 10,
  },
  dateText: {
    fontSize: 15,
    color: '#333',
  },
  quickDates: {
    flexDirection: 'row',
    marginTop: 10,
    gap: 8,
  },
  quickDateBtn: {
    flex: 1,
    paddingVertical: 8,
    backgroundColor: '#f0f0f0',
    borderRadius: 8,
    alignItems: 'center',
  },
  quickDateBtnActive: {
    backgroundColor: '#667eea',
  },
  quickDateText: {
    fontSize: 13,
    color: '#666',
  },
  quickDateTextActive: {
    color: '#fff',
    fontWeight: '500',
  },
  submitBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    marginHorizontal: 16,
    marginTop: 32,
    paddingVertical: 14,
    backgroundColor: '#667eea',
    borderRadius: 12,
  },
  submitBtnDisabled: {
    opacity: 0.7,
  },
  buttonContent: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  submitBtnText: {
    fontSize: 16,
    fontWeight: '600',
    color: '#fff',
    marginLeft: 8,
  },
});

export default CreatePlanScreen;
