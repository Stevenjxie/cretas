/**
 * 调度员创建生产计划页面
 *
 * 功能：
 * - 创建新的生产计划
 * - 支持多种计划来源类型
 * - 设置优先级和截止日期
 * - 支持混批标识
 *
 * 与 factory-admin/CreatePlanScreen 区别：
 * - 使用紫色主题 (#722ed1)
 * - 额外字段: sourceType, priority, workshopId, deadline, isMixedBatch
 *
 * @version 1.0.0
 * @since 2025-12-29
 */
import React, { useState, useEffect, useRef } from 'react';
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
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useTranslation } from 'react-i18next';
import { DISPATCHER_THEME, PlanSourceType, DispatcherStackParamList } from '../../../types/dispatcher';
import {
  createProductionPlanClientRequestId,
  productionPlanApiClient,
  PlanType,
} from '../../../services/api/productionPlanApiClient';
import { productTypeApiClient, ProductType } from '../../../services/api/productTypeApiClient';

type NavigationProp = NativeStackNavigationProp<DispatcherStackParamList, 'PlanCreate'>;

// 计划来源类型选项 (六扇门一期: 仅手动创建, 其他来源待画布配置系统支持后开放)
const SOURCE_TYPE_OPTIONS = [
  { value: 'manual' as PlanSourceType, label: '手动创建', icon: 'pencil', description: '调度员手动录入' },
] as const;

// 优先级选项
const PRIORITY_OPTIONS = [
  { value: 10, label: '最高', color: '#ff4d4f' },
  { value: 8, label: '高', color: '#fa8c16' },
  { value: 5, label: '中', color: '#1890ff' },
  { value: 3, label: '低', color: '#52c41a' },
];

export function PlanCreateScreen() {
  const navigation = useNavigation<NavigationProp>();
  const { t } = useTranslation('dispatcher');

  const [loading, setLoading] = useState(false);
  const [loadingProducts, setLoadingProducts] = useState(true);
  const [products, setProducts] = useState<ProductType[]>([]);

  // 基础字段
  const [selectedProductId, setSelectedProductId] = useState('');
  const [plannedQuantity, setPlannedQuantity] = useState('');
  const [plannedDate, setPlannedDate] = useState<string>(() => {
    const tomorrow = new Date();
    tomorrow.setDate(tomorrow.getDate() + 1);
    const iso = tomorrow.toISOString();
    return iso.split('T')[0] ?? iso.substring(0, 10);
  });
  const [planType, setPlanType] = useState<PlanType>('FROM_INVENTORY');
  const [autoCustomerName, setAutoCustomerName] = useState('');
  const [notes, setNotes] = useState('');
  const [showProductPicker, setShowProductPicker] = useState(false);

  // 调度员专属字段
  const [sourceType, setSourceType] = useState<PlanSourceType>('manual');
  const [priority, setPriority] = useState<number>(5);
  const [deadline, setDeadline] = useState<string>(() => {
    const threeDays = new Date();
    threeDays.setDate(threeDays.getDate() + 3);
    const iso = threeDays.toISOString();
    return iso.split('T')[0] ?? iso.substring(0, 10);
  });
  const [isMixedBatch, setIsMixedBatch] = useState(false);
  const [showSourceTypePicker, setShowSourceTypePicker] = useState(false);
  const createIntentRef = useRef<{ fingerprint: string; clientRequestId: string } | null>(null);

  // 加载产品类型 + 客户列表
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

  // 选产品时从产品的 relatedCustomer 字段自动带出客户
  const lookupCustomerForProduct = (productId: string) => {
    const product = products.find(p => p.id === productId);
    const customer = (product as any)?.relatedCustomer;
    setAutoCustomerName(customer || '');
  };

  const selectedProduct = products.find((p) => p.id === selectedProductId);
  const selectedSourceOption = SOURCE_TYPE_OPTIONS.find((s) => s.value === sourceType);

  const validateForm = (): boolean => {
    if (!selectedProductId) {
      Alert.alert(t('common.info'), t('planCreate.validation.selectProduct'));
      return false;
    }
    if (!autoCustomerName) {
      Alert.alert('提示', '该产品未绑定客户，请在Web管理端产品设置中关联客户');
      return false;
    }
    if (!plannedQuantity || parseFloat(plannedQuantity) <= 0) {
      Alert.alert(t('common.info'), t('planCreate.validation.enterQuantity'));
      return false;
    }
    if (!plannedDate) {
      Alert.alert(t('common.info'), t('planCreate.validation.selectPlannedDate'));
      return false;
    }
    if (!deadline) {
      Alert.alert(t('common.info'), t('planCreate.validation.selectDeadline'));
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
        sourceCustomerName: autoCustomerName || undefined,
        notes: notes || undefined,
        // 调度员扩展字段 - 后端需要支持这些字段
        // sourceType,
        // priority,
        // deadline,
        // isMixedBatch,
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
          ? `${t('planCreate.submit.successMessage')}\n${planNumber}`
          : t('planCreate.submit.successMessage');
        Alert.alert(t('planCreate.submit.success'), successMessage, [
          { text: t('common.confirm'), onPress: () => navigation.goBack() },
        ]);
      } else {
        Alert.alert(t('planCreate.submit.failed'), response.message || t('planCreate.submit.failedMessage'));
      }
    } catch (err) {
      console.error('创建生产计划失败:', err);
      Alert.alert(t('planCreate.submit.error'), t('planCreate.submit.errorMessage'));
    } finally {
      setLoading(false);
    }
  };

  const formatDate = (dateStr: string): string => {
    return new Date(dateStr).toLocaleDateString('zh-CN', {
      year: 'numeric',
      month: 'long',
      day: 'numeric',
    });
  };

  // 计算 CR 值 (交期-计划日期) / 预估工期
  const calculateCrValue = (): number | null => {
    if (!plannedDate || !deadline) return null;
    const planDate = new Date(plannedDate);
    const deadlineDate = new Date(deadline);
    const diffDays = Math.ceil((deadlineDate.getTime() - planDate.getTime()) / (1000 * 60 * 60 * 24));
    // 假设预估工期为1天
    const estimatedDays = 1;
    return parseFloat((diffDays / estimatedDays).toFixed(1));
  };

  const crValue = calculateCrValue();
  const getCrLevel = (cr: number | null): { label: string; color: string } => {
    if (cr === null) return { label: '', color: '#999' };
    if (cr < 1) return { label: '紧急', color: '#ff4d4f' };
    if (cr < 2) return { label: '较紧', color: '#fa8c16' };
    return { label: '充裕', color: '#52c41a' };
  };

  return (
    <SafeAreaView style={styles.container}>
      {/* Header */}
      <View style={styles.header}>
        <TouchableOpacity onPress={() => navigation.goBack()} style={styles.backBtn}>
          <Icon source="arrow-left" size={24} color="#333" />
        </TouchableOpacity>
        <Text style={styles.headerTitle}>{t('planCreate.title')}</Text>
        <View style={{ width: 40 }} />
      </View>

      <KeyboardAvoidingView
        style={{ flex: 1 }}
        behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
      >
        <ScrollView style={styles.scrollView} showsVerticalScrollIndicator={false}>
          {/* 计划来源类型 */}
          <View style={styles.section}>
            <Text style={styles.sectionTitle}>计划来源 *</Text>
            <TouchableOpacity
              style={styles.selectField}
              onPress={() => setShowSourceTypePicker(!showSourceTypePicker)}
            >
              {selectedSourceOption ? (
                <View style={styles.selectValueRow}>
                  <MaterialCommunityIcons
                    name={selectedSourceOption.icon}
                    size={20}
                    color={DISPATCHER_THEME.primary}
                  />
                  <Text style={styles.selectValue}>{selectedSourceOption.label}</Text>
                </View>
              ) : (
                <Text style={styles.selectPlaceholder}>选择计划来源</Text>
              )}
              <Icon source="chevron-down" size={20} color="#999" />
            </TouchableOpacity>

            {showSourceTypePicker && (
              <View style={styles.pickerDropdown}>
                <ScrollView style={{ maxHeight: 250 }}>
                  {SOURCE_TYPE_OPTIONS.map((option) => (
                    <TouchableOpacity
                      key={option.value}
                      style={[
                        styles.pickerItem,
                        sourceType === option.value && styles.pickerItemActive,
                      ]}
                      onPress={() => {
                        setSourceType(option.value);
                        setShowSourceTypePicker(false);
                      }}
                    >
                      <MaterialCommunityIcons
                        name={option.icon}
                        size={20}
                        color={sourceType === option.value ? DISPATCHER_THEME.primary : '#666'}
                      />
                      <View style={styles.pickerItemContent}>
                        <Text
                          style={[
                            styles.pickerItemText,
                            sourceType === option.value && styles.pickerItemTextActive,
                          ]}
                        >
                          {option.label}
                        </Text>
                        <Text style={styles.pickerItemDesc}>{option.description}</Text>
                      </View>
                    </TouchableOpacity>
                  ))}
                </ScrollView>
              </View>
            )}
          </View>

          {/* 原料计划类型: 六扇门一期固定为库存生产, 隐藏整个选择器 */}

          {/* 产品选择 */}
          <View style={styles.section}>
            <Text style={styles.sectionTitle}>产品类型 *</Text>
            <TouchableOpacity
              style={styles.selectField}
              onPress={() => setShowProductPicker(!showProductPicker)}
            >
              {loadingProducts ? (
                <ActivityIndicator size="small" color={DISPATCHER_THEME.primary} />
              ) : selectedProduct ? (
                <Text style={styles.selectValue}>{selectedProduct.name}</Text>
              ) : (
                <Text style={styles.selectPlaceholder}>选择产品类型</Text>
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
                        lookupCustomerForProduct(product.id);
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
                      <Text style={styles.pickerItemUnit}>
                        ({product.unit || 'kg'}){(product as any).relatedCustomer ? ` · ${(product as any).relatedCustomer}` : ''}
                      </Text>
                    </TouchableOpacity>
                  ))}
                  {products.length === 0 && (
                    <Text style={styles.pickerEmpty}>暂无产品类型</Text>
                  )}
                </ScrollView>
              </View>
            )}
          </View>

          {/* 客户 (从产品的 relatedCustomer 自动带出) */}
          <View style={styles.section}>
            <Text style={styles.sectionTitle}>客户 *</Text>
            {autoCustomerName ? (
              <View style={[styles.textInput, { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' }]}>
                <Text style={{ fontSize: 16, color: '#333', fontWeight: '500' }}>{autoCustomerName}</Text>
                <Text style={{ fontSize: 12, color: '#52c41a' }}>已关联</Text>
              </View>
            ) : selectedProductId ? (
              <View style={[styles.textInput, { backgroundColor: '#fff2e8' }]}>
                <Text style={{ fontSize: 14, color: '#fa8c16' }}>该产品未绑定客户，请在Web管理端设置</Text>
              </View>
            ) : (
              <View style={styles.textInput}>
                <Text style={{ fontSize: 14, color: '#999' }}>请先选择产品</Text>
              </View>
            )}
          </View>

          {/* 计划数量 */}
          <View style={styles.section}>
            <Text style={styles.sectionTitle}>计划数量 *</Text>
            <View style={styles.inputRow}>
              <TextInput
                style={styles.textInputInRow}
                value={plannedQuantity}
                onChangeText={setPlannedQuantity}
                placeholder="请输入数量"
                keyboardType="decimal-pad"
                placeholderTextColor="#999"
              />
              <Text style={styles.inputUnit}>{selectedProduct?.unit ?? 'kg'}</Text>
            </View>
          </View>

          {/* 优先级 */}
          <View style={styles.section}>
            <Text style={styles.sectionTitle}>优先级</Text>
            <View style={styles.priorityGrid}>
              {PRIORITY_OPTIONS.map((option) => (
                <TouchableOpacity
                  key={option.value}
                  style={[
                    styles.priorityItem,
                    priority === option.value && { borderColor: option.color, backgroundColor: option.color + '10' },
                  ]}
                  onPress={() => setPriority(option.value)}
                >
                  <View
                    style={[
                      styles.priorityDot,
                      { backgroundColor: option.color },
                    ]}
                  />
                  <Text
                    style={[
                      styles.priorityLabel,
                      priority === option.value && { color: option.color, fontWeight: '600' },
                    ]}
                  >
                    {option.label}
                  </Text>
                </TouchableOpacity>
              ))}
            </View>
          </View>

          {/* 计划日期 */}
          <View style={styles.section}>
            <Text style={styles.sectionTitle}>计划开始日期 *</Text>
            <View style={styles.dateDisplay}>
              <Icon source="calendar" size={20} color={DISPATCHER_THEME.primary} />
              <Text style={styles.dateText}>{formatDate(plannedDate)}</Text>
            </View>
            <View style={styles.quickDates}>
              {[0, 1, 2, 3].map((offset) => {
                const date = new Date();
                date.setDate(date.getDate() + offset);
                const iso = date.toISOString();
                const dateStr = iso.split('T')[0] ?? iso.substring(0, 10);
                const label = offset === 0 ? '今天' : offset === 1 ? '明天' : `${offset}天后`;
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

          {/* 交货期限 */}
          <View style={styles.section}>
            <Text style={styles.sectionTitle}>交货期限 *</Text>
            <View style={styles.dateDisplay}>
              <Icon source="clock-outline" size={20} color={DISPATCHER_THEME.primary} />
              <Text style={styles.dateText}>{formatDate(deadline)}</Text>
              {crValue !== null && (
                <View style={styles.crContainer}>
                  <Text style={styles.crLabel}>CR值: </Text>
                  <Text style={[styles.crValue, { color: getCrLevel(crValue).color }]}>
                    {crValue}
                  </Text>
                  <View style={[styles.crBadge, { backgroundColor: getCrLevel(crValue).color + '20' }]}>
                    <Text style={[styles.crBadgeText, { color: getCrLevel(crValue).color }]}>
                      {getCrLevel(crValue).label}
                    </Text>
                  </View>
                </View>
              )}
            </View>
            <View style={styles.quickDates}>
              {[1, 3, 5, 7].map((offset) => {
                const date = new Date();
                date.setDate(date.getDate() + offset);
                const iso = date.toISOString();
                const dateStr = iso.split('T')[0] ?? iso.substring(0, 10);
                const label = `${offset}天后`;
                return (
                  <TouchableOpacity
                    key={offset}
                    style={[
                      styles.quickDateBtn,
                      deadline === dateStr && styles.quickDateBtnActive,
                    ]}
                    onPress={() => setDeadline(dateStr)}
                  >
                    <Text
                      style={[
                        styles.quickDateText,
                        deadline === dateStr && styles.quickDateTextActive,
                      ]}
                    >
                      {label}
                    </Text>
                  </TouchableOpacity>
                );
              })}
            </View>
          </View>

          {/* 混批开关 */}
          <View style={styles.section}>
            <View style={styles.switchRow}>
              <View>
                <Text style={styles.switchLabel}>允许混批生产</Text>
                <Text style={styles.switchDesc}>启用后可与相似订单合并生产</Text>
              </View>
              <TouchableOpacity
                style={[styles.switchBtn, isMixedBatch && styles.switchBtnActive]}
                onPress={() => setIsMixedBatch(!isMixedBatch)}
              >
                <View style={[styles.switchThumb, isMixedBatch && styles.switchThumbActive]} />
              </TouchableOpacity>
            </View>
          </View>

          {/* 备注 */}
          <View style={styles.section}>
            <Text style={styles.sectionTitle}>备注 (可选)</Text>
            <TextInput
              style={[styles.textInput, styles.textArea]}
              value={notes}
              onChangeText={setNotes}
              placeholder="请输入备注信息"
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
                <Text style={styles.submitBtnText}>创建中...</Text>
              </View>
            ) : (
              <View style={styles.buttonContent}>
                <Icon source="check" size={20} color="#fff" />
                <Text style={styles.submitBtnText}>创建计划</Text>
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
    backgroundColor: DISPATCHER_THEME.primary,
    borderColor: DISPATCHER_THEME.primary,
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
  selectValueRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
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
    gap: 10,
  },
  pickerItemActive: {
    backgroundColor: DISPATCHER_THEME.primary + '10',
  },
  pickerItemContent: {
    flex: 1,
  },
  pickerItemText: {
    fontSize: 15,
    color: '#333',
  },
  pickerItemTextActive: {
    color: DISPATCHER_THEME.primary,
    fontWeight: '500',
  },
  pickerItemDesc: {
    fontSize: 12,
    color: '#999',
    marginTop: 2,
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
  textInputInRow: {
    flex: 1,
    padding: 14,
    fontSize: 15,
    color: '#333',
  },
  textInput: {
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
  priorityGrid: {
    flexDirection: 'row',
    gap: 8,
  },
  priorityItem: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#fff',
    borderRadius: 8,
    padding: 10,
    borderWidth: 1.5,
    borderColor: '#e0e0e0',
    gap: 6,
  },
  priorityDot: {
    width: 8,
    height: 8,
    borderRadius: 4,
  },
  priorityLabel: {
    fontSize: 13,
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
  crContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    marginLeft: 'auto',
    gap: 4,
  },
  crLabel: {
    fontSize: 12,
    color: '#666',
  },
  crValue: {
    fontSize: 12,
    fontWeight: '600',
  },
  crBadge: {
    paddingHorizontal: 6,
    paddingVertical: 2,
    borderRadius: 4,
  },
  crBadgeText: {
    fontSize: 10,
    fontWeight: '500',
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
    backgroundColor: DISPATCHER_THEME.primary,
  },
  quickDateText: {
    fontSize: 13,
    color: '#666',
  },
  quickDateTextActive: {
    color: '#fff',
    fontWeight: '500',
  },
  switchRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    backgroundColor: '#fff',
    borderRadius: 10,
    padding: 14,
    borderWidth: 1,
    borderColor: '#e0e0e0',
  },
  switchLabel: {
    fontSize: 15,
    color: '#333',
    fontWeight: '500',
  },
  switchDesc: {
    fontSize: 12,
    color: '#999',
    marginTop: 2,
  },
  switchBtn: {
    width: 50,
    height: 28,
    borderRadius: 14,
    backgroundColor: '#e0e0e0',
    justifyContent: 'center',
    padding: 2,
  },
  switchBtnActive: {
    backgroundColor: DISPATCHER_THEME.primary,
  },
  switchThumb: {
    width: 24,
    height: 24,
    borderRadius: 12,
    backgroundColor: '#fff',
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.2,
    shadowRadius: 2,
    elevation: 2,
  },
  switchThumbActive: {
    alignSelf: 'flex-end',
  },
  submitBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    marginHorizontal: 16,
    marginTop: 32,
    paddingVertical: 14,
    backgroundColor: DISPATCHER_THEME.primary,
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

export default PlanCreateScreen;
