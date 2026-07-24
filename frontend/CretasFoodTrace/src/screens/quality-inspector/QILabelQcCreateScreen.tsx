import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert,
  Image,
  Modal,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import DateTimePicker, { DateTimePickerEvent } from '@react-native-community/datetimepicker';
import * as FileSystem from 'expo-file-system';
import * as ImagePicker from 'expo-image-picker';
import { Ionicons } from '@expo/vector-icons';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { useNavigation } from '@react-navigation/native';
import {
  ActivityIndicator,
  Button,
  ProgressBar,
  Searchbar,
  TextInput,
  TouchableRipple,
} from 'react-native-paper';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import {
  ProductType,
  productTypeApiClient,
} from '../../services/api/productTypeApiClient';
import { attachmentApi } from '../../services/api/attachmentApi';
import { labelQcApi } from '../../services/api/labelQcApi';
import { useAuthStore } from '../../store/authStore';
import { QI_COLORS, QualityInspectorStackParamList } from '../../types/qualityInspector';

type NavigationProp = NativeStackNavigationProp<QualityInspectorStackParamList>;
type ProductWithCode = ProductType & { code?: string };

const MAX_PHOTOS = 6;

const formatDate = (date: Date): string => {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
};

const getErrorMessage = (error: unknown): string => {
  const responseMessage = (
    error as { response?: { data?: { message?: string } } }
  )?.response?.data?.message;
  if (responseMessage) return responseMessage;
  if (error instanceof Error && error.message) return error.message;
  return '提交失败，请检查网络后重试';
};

export default function QILabelQcCreateScreen() {
  const navigation = useNavigation<NavigationProp>();
  const insets = useSafeAreaInsets();
  const factoryId = useAuthStore((state) => state.user?.factoryId);

  const [products, setProducts] = useState<ProductType[]>([]);
  const [productsLoading, setProductsLoading] = useState(true);
  const [productsError, setProductsError] = useState<string | null>(null);
  const [productModalVisible, setProductModalVisible] = useState(false);
  const [productKeyword, setProductKeyword] = useState('');
  const [selectedProduct, setSelectedProduct] = useState<ProductType | null>(null);
  const [batchNumber, setBatchNumber] = useState('');
  const [productionDate, setProductionDate] = useState(new Date());
  const [datePickerVisible, setDatePickerVisible] = useState(false);
  const [photos, setPhotos] = useState<ImagePicker.ImagePickerAsset[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [progress, setProgress] = useState(0);
  const [progressText, setProgressText] = useState('');
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [draftTaskId, setDraftTaskId] = useState<string | null>(null);

  const idempotencyKey = useRef(
    `label-qc-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`,
  );
  const uploadedAttachmentIds = useRef<Record<number, string>>({});

  const loadProducts = async () => {
    if (!factoryId) {
      setProductsError('登录信息缺少工厂，请重新登录');
      setProductsLoading(false);
      return;
    }
    try {
      setProductsLoading(true);
      setProductsError(null);
      const result = await productTypeApiClient.getActiveProductTypes(factoryId);
      setProducts(result.filter((item) => item.isActive !== false));
    } catch (error) {
      setProductsError(getErrorMessage(error));
    } finally {
      setProductsLoading(false);
    }
  };

  useEffect(() => {
    void loadProducts();
  }, [factoryId]);

  const filteredProducts = useMemo(() => {
    const keyword = productKeyword.trim().toLowerCase();
    if (!keyword) return products;
    return products.filter((item) => {
      const code = ((item as ProductWithCode).code ?? item.productCode ?? '').toLowerCase();
      return item.name.toLowerCase().includes(keyword) || code.includes(keyword);
    });
  }, [productKeyword, products]);

  const selectedCode = selectedProduct
    ? ((selectedProduct as ProductWithCode).code ?? selectedProduct.productCode ?? '')
    : '';
  const formLocked = Boolean(draftTaskId);

  const takePhoto = async () => {
    if (photos.length >= MAX_PHOTOS) {
      Alert.alert('照片已满', `每个拍检任务最多 ${MAX_PHOTOS} 张照片`);
      return;
    }
    const permission = await ImagePicker.requestCameraPermissionsAsync();
    if (!permission.granted) {
      Alert.alert('需要相机权限', '请在系统设置中允许相机权限后重试');
      return;
    }
    const result = await ImagePicker.launchCameraAsync({
      mediaTypes: ImagePicker.MediaTypeOptions.Images,
      quality: 0.85,
      exif: false,
    });
    const captured = result.canceled ? undefined : result.assets[0];
    if (captured) {
      setPhotos((current) => [...current, captured]);
      setSubmitError(null);
    }
  };

  const removePhoto = (index: number) => {
    if (formLocked) {
      Alert.alert('任务已创建', '上传失败后请直接重试，不能再更改已绑定的照片');
      return;
    }
    setPhotos((current) => current.filter((_, itemIndex) => itemIndex !== index));
  };

  const validate = (): string | null => {
    if (!selectedProduct) return '请选择 SKU';
    const batch = batchNumber.trim();
    if (!batch) return '请输入批次号';
    if (batch.length > 100) return '批次号不能超过 100 个字符';
    if (productionDate.getTime() > Date.now()) return '生产日期不能晚于今天';
    if (photos.length === 0) return '请至少拍摄 1 张照片';
    return null;
  };

  const fileSize = async (asset: ImagePicker.ImagePickerAsset): Promise<number> => {
    if (asset.fileSize && asset.fileSize > 0) return asset.fileSize;
    const info = await FileSystem.getInfoAsync(asset.uri);
    const size = (info as { size?: number }).size;
    return size && size > 0 ? size : 1;
  };

  const submit = async () => {
    const validationError = validate();
    if (validationError) {
      setSubmitError(validationError);
      return;
    }
    if (!factoryId || !selectedProduct) return;

    setSubmitting(true);
    setSubmitError(null);
    try {
      let taskId = draftTaskId;
      if (!taskId) {
        setProgressText('正在创建拍检任务…');
        const created = await labelQcApi.createTask(
          {
            productTypeId: selectedProduct.id,
            batchNumber: batchNumber.trim(),
            productionDate: formatDate(productionDate),
            idempotencyKey: idempotencyKey.current,
          },
          factoryId,
        );
        taskId = created.task.id;
        setDraftTaskId(taskId);
      }

      for (let index = 0; index < photos.length; index += 1) {
        const asset = photos[index];
        if (!asset) continue;
        setProgress((index + 0.2) / photos.length);
        setProgressText(`正在上传第 ${index + 1}/${photos.length} 张照片…`);

        let attachmentId = uploadedAttachmentIds.current[index];
        if (!attachmentId) {
          const mimeType = asset.mimeType || 'image/jpeg';
          const extension = mimeType.includes('png') ? 'png' : 'jpg';
          const attachment = await attachmentApi.uploadAndRegister(
            {
              uri: asset.uri,
              name: `label-qc-${taskId}-${index + 1}.${extension}`,
              type: mimeType,
              size: await fileSize(asset),
            },
            'QUALITY_CHECK',
            taskId,
            {
              businessTag: 'LABEL_QC_SOURCE',
              description: `包装标签拍检照片 ${index + 1}`,
              fileCategory: 'PHOTO',
            },
            factoryId,
          );
          attachmentId = attachment.id;
          uploadedAttachmentIds.current[index] = attachmentId;
        }

        await labelQcApi.addPhoto(
          taskId,
          {
            attachmentId,
            orderIndex: index,
            imageWidth: asset.width,
            imageHeight: asset.height,
          },
          factoryId,
        );
        setProgress((index + 1) / photos.length);
      }

      setProgressText('正在提交 AI 初筛…');
      const submitted = await labelQcApi.submitTask(taskId, factoryId);
      navigation.replace('QILabelQcSubmitted', {
        taskId,
        skuCode: submitted.task.skuCode,
        skuName: submitted.task.skuName,
        batchNumber: submitted.task.batchNumber,
        productionDate: submitted.task.productionDate,
      });
    } catch (error) {
      setSubmitError(getErrorMessage(error));
      setProgressText('上传中断，已完成内容会保留');
    } finally {
      setSubmitting(false);
    }
  };

  const onDateChange = (event: DateTimePickerEvent, selected?: Date) => {
    setDatePickerVisible(false);
    if (event.type === 'set' && selected) {
      setProductionDate(selected);
    }
  };

  return (
    <View style={styles.screen}>
      <ScrollView
        contentContainerStyle={[styles.content, { paddingBottom: insets.bottom + 108 }]}
        keyboardShouldPersistTaps="handled"
      >
        <View style={styles.notice}>
          <Ionicons name="shield-checkmark-outline" size={22} color={QI_COLORS.secondary} />
          <View style={styles.noticeText}>
            <Text style={styles.noticeTitle}>AI 只做疑似初筛</Text>
            <Text style={styles.noticeBody}>所有照片都会进入人工审核，不会自动判定合格。</Text>
          </View>
        </View>

        <Text style={styles.sectionTitle}>1. 核对批次信息</Text>
        <Text style={styles.fieldLabel}>SKU *</Text>
        <TouchableRipple
          style={[styles.selector, formLocked && styles.disabledControl]}
          onPress={() => !formLocked && setProductModalVisible(true)}
          disabled={formLocked}
          borderless={false}
          accessibilityRole="button"
          accessibilityLabel="选择 SKU"
        >
          <View style={styles.selectorContent}>
            <View style={styles.selectorText}>
              <Text style={selectedProduct ? styles.selectorValue : styles.selectorPlaceholder}>
                {selectedProduct ? `${selectedCode} · ${selectedProduct.name}` : '请选择产品 SKU'}
              </Text>
            </View>
            <Ionicons name="chevron-forward" size={20} color={QI_COLORS.textSecondary} />
          </View>
        </TouchableRipple>

        <TextInput
          mode="outlined"
          label="批次号 *"
          value={batchNumber}
          onChangeText={(value) => {
            setBatchNumber(value);
            setSubmitError(null);
          }}
          disabled={formLocked}
          maxLength={100}
          autoCapitalize="characters"
          style={styles.input}
          outlineColor={QI_COLORS.border}
          activeOutlineColor={QI_COLORS.primary}
        />

        <Text style={styles.fieldLabel}>生产日期 *</Text>
        <TouchableRipple
          style={[styles.selector, formLocked && styles.disabledControl]}
          onPress={() => !formLocked && setDatePickerVisible(true)}
          disabled={formLocked}
          borderless={false}
          accessibilityRole="button"
          accessibilityLabel="选择生产日期"
        >
          <View style={styles.selectorContent}>
            <Text style={styles.selectorValue}>{formatDate(productionDate)}</Text>
            <Ionicons name="calendar-outline" size={20} color={QI_COLORS.primary} />
          </View>
        </TouchableRipple>

        {datePickerVisible && (
          <DateTimePicker
            value={productionDate}
            mode="date"
            maximumDate={new Date()}
            onChange={onDateChange}
          />
        )}

        <View style={styles.sectionHeader}>
          <Text style={styles.sectionTitle}>2. 拍摄包装照片</Text>
          <Text style={styles.photoCounter}>{photos.length}/{MAX_PHOTOS}</Text>
        </View>

        <View style={styles.guideCard}>
          {[
            '每盒尽量露出标签所在边缘',
            '减少堆叠，单张建议拍 8–15 盒',
            '避开顶灯反光，画面保持清晰',
          ].map((text, index) => (
            <View key={text} style={styles.guideRow}>
              <View style={styles.guideNumber}>
                <Text style={styles.guideNumberText}>{index + 1}</Text>
              </View>
              <Text style={styles.guideText}>{text}</Text>
            </View>
          ))}
        </View>

        <View style={styles.photoGrid}>
          {photos.map((photo, index) => (
            <View key={`${photo.uri}-${index}`} style={styles.photoCard}>
              <Image source={{ uri: photo.uri }} style={styles.photoImage} />
              <View style={styles.photoIndex}>
                <Text style={styles.photoIndexText}>{index + 1}</Text>
              </View>
              <TouchableRipple
                style={styles.removePhoto}
                onPress={() => removePhoto(index)}
                borderless
                accessibilityRole="button"
                accessibilityLabel={`删除第 ${index + 1} 张照片`}
              >
                <Ionicons name="close" size={20} color="#fff" />
              </TouchableRipple>
            </View>
          ))}
          {photos.length < MAX_PHOTOS && !formLocked && (
            <TouchableRipple
              style={styles.addPhoto}
              onPress={takePhoto}
              borderless={false}
              accessibilityRole="button"
              accessibilityLabel="拍摄照片"
            >
              <View style={styles.addPhotoContent}>
                <Ionicons name="camera" size={30} color={QI_COLORS.primary} />
                <Text style={styles.addPhotoText}>拍照</Text>
              </View>
            </TouchableRipple>
          )}
        </View>

        {formLocked && (
          <View style={styles.lockedHint}>
            <Ionicons name="lock-closed-outline" size={18} color="#8A5A00" />
            <Text style={styles.lockedHintText}>
              草稿已创建，批次和照片已锁定。上传失败时直接点击下方重试。
            </Text>
          </View>
        )}

        {submitting && (
          <View style={styles.progressCard}>
            <ProgressBar progress={progress} color={QI_COLORS.primary} />
            <View style={styles.progressTextRow}>
              <ActivityIndicator size={18} color={QI_COLORS.primary} />
              <Text style={styles.progressText}>{progressText}</Text>
            </View>
          </View>
        )}

        {submitError && (
          <View style={styles.errorCard}>
            <Ionicons name="alert-circle-outline" size={20} color={QI_COLORS.danger} />
            <View style={styles.errorTextContainer}>
              <Text style={styles.errorTitle}>尚未提交成功</Text>
              <Text style={styles.errorText}>{submitError}</Text>
              <Text style={styles.errorAction}>请修正信息或检查网络后再次提交。</Text>
            </View>
          </View>
        )}
      </ScrollView>

      <View style={[styles.bottomAction, { paddingBottom: insets.bottom + 12 }]}>
        <Button
          mode="contained"
          onPress={submit}
          loading={submitting}
          disabled={submitting}
          buttonColor={QI_COLORS.primary}
          contentStyle={styles.primaryButtonContent}
          labelStyle={styles.primaryButtonLabel}
          accessibilityLabel={draftTaskId ? '重试提交拍检' : '提交拍检'}
        >
          {draftTaskId && submitError ? '重试提交' : '提交拍检'}
        </Button>
      </View>

      <Modal
        visible={productModalVisible}
        animationType="slide"
        presentationStyle="pageSheet"
        onRequestClose={() => setProductModalVisible(false)}
      >
        <View style={[styles.modalScreen, { paddingTop: insets.top + 8 }]}>
          <View style={styles.modalHeader}>
            <Text style={styles.modalTitle}>选择 SKU</Text>
            <TouchableRipple
              style={styles.modalClose}
              onPress={() => setProductModalVisible(false)}
              borderless
              accessibilityRole="button"
              accessibilityLabel="关闭 SKU 选择"
            >
              <Ionicons name="close" size={24} color={QI_COLORS.text} />
            </TouchableRipple>
          </View>
          <Searchbar
            placeholder="搜索 SKU 编码或名称"
            value={productKeyword}
            onChangeText={setProductKeyword}
            style={styles.searchbar}
          />
          {productsLoading ? (
            <View style={styles.modalState}>
              <ActivityIndicator color={QI_COLORS.primary} />
              <Text style={styles.modalStateText}>正在加载 SKU…</Text>
            </View>
          ) : productsError ? (
            <View style={styles.modalState}>
              <Ionicons name="cloud-offline-outline" size={42} color={QI_COLORS.disabled} />
              <Text style={styles.modalStateText}>{productsError}</Text>
              <Button mode="outlined" onPress={loadProducts}>重新加载</Button>
            </View>
          ) : filteredProducts.length === 0 ? (
            <View style={styles.modalState}>
              <Ionicons name="search-outline" size={42} color={QI_COLORS.disabled} />
              <Text style={styles.modalStateText}>没有匹配的 SKU，请更换关键词</Text>
            </View>
          ) : (
            <ScrollView keyboardShouldPersistTaps="handled">
              {filteredProducts.map((product) => {
                const code = (product as ProductWithCode).code ?? product.productCode ?? '';
                return (
                  <TouchableRipple
                    key={product.id}
                    style={styles.productRow}
                    onPress={() => {
                      setSelectedProduct(product);
                      setProductModalVisible(false);
                      setProductKeyword('');
                      setSubmitError(null);
                    }}
                    borderless={false}
                  >
                    <View style={styles.productRowContent}>
                      <View style={styles.productCodeBadge}>
                        <Text style={styles.productCodeText}>{code || 'SKU'}</Text>
                      </View>
                      <View style={styles.productText}>
                        <Text style={styles.productName}>{product.name}</Text>
                        <Text style={styles.productMeta}>
                          {[product.unit, product.packageSpec].filter(Boolean).join(' · ') || '未配置规格'}
                        </Text>
                      </View>
                      {selectedProduct?.id === product.id && (
                        <Ionicons name="checkmark-circle" size={22} color={QI_COLORS.primary} />
                      )}
                    </View>
                  </TouchableRipple>
                );
              })}
            </ScrollView>
          )}
        </View>
      </Modal>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: QI_COLORS.background },
  content: { padding: 16 },
  notice: {
    flexDirection: 'row',
    backgroundColor: '#EAF3FF',
    borderRadius: 12,
    padding: 14,
    marginBottom: 20,
  },
  noticeText: { flex: 1, marginLeft: 10 },
  noticeTitle: { fontSize: 15, fontWeight: '700', color: '#174A7E' },
  noticeBody: { fontSize: 13, color: '#365D80', marginTop: 3, lineHeight: 19 },
  sectionHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginTop: 24,
  },
  sectionTitle: {
    fontSize: 18,
    fontWeight: '700',
    color: QI_COLORS.text,
    marginBottom: 12,
  },
  fieldLabel: {
    fontSize: 14,
    fontWeight: '600',
    color: QI_COLORS.text,
    marginBottom: 8,
  },
  selector: {
    minHeight: 52,
    borderRadius: 10,
    borderWidth: 1,
    borderColor: QI_COLORS.border,
    backgroundColor: QI_COLORS.card,
    marginBottom: 16,
    overflow: 'hidden',
  },
  selectorContent: {
    minHeight: 52,
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 14,
  },
  selectorText: { flex: 1 },
  selectorValue: { fontSize: 15, color: QI_COLORS.text },
  selectorPlaceholder: { fontSize: 15, color: QI_COLORS.disabled },
  disabledControl: { opacity: 0.65 },
  input: { backgroundColor: QI_COLORS.card, marginBottom: 16 },
  photoCounter: { fontSize: 14, fontWeight: '700', color: QI_COLORS.primary, marginBottom: 12 },
  guideCard: {
    backgroundColor: '#FFF8E8',
    borderRadius: 12,
    padding: 14,
    marginBottom: 16,
  },
  guideRow: { flexDirection: 'row', alignItems: 'center', marginVertical: 5 },
  guideNumber: {
    width: 24,
    height: 24,
    borderRadius: 12,
    backgroundColor: '#FFD884',
    alignItems: 'center',
    justifyContent: 'center',
    marginRight: 10,
  },
  guideNumberText: { fontSize: 12, fontWeight: '800', color: '#7A4B00' },
  guideText: { flex: 1, fontSize: 14, color: '#654A1D', lineHeight: 20 },
  photoGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: 10 },
  photoCard: {
    width: '30.5%',
    aspectRatio: 0.8,
    borderRadius: 10,
    overflow: 'hidden',
    backgroundColor: '#E9EDF2',
  },
  photoImage: { width: '100%', height: '100%' },
  photoIndex: {
    position: 'absolute',
    left: 6,
    bottom: 6,
    width: 24,
    height: 24,
    borderRadius: 12,
    backgroundColor: 'rgba(0,0,0,0.65)',
    alignItems: 'center',
    justifyContent: 'center',
  },
  photoIndexText: { color: '#fff', fontSize: 12, fontWeight: '700' },
  removePhoto: {
    position: 'absolute',
    top: 4,
    right: 4,
    width: 44,
    height: 44,
    borderRadius: 22,
    backgroundColor: 'rgba(0,0,0,0.62)',
    alignItems: 'center',
    justifyContent: 'center',
  },
  addPhoto: {
    width: '30.5%',
    aspectRatio: 0.8,
    borderWidth: 1.5,
    borderStyle: 'dashed',
    borderColor: QI_COLORS.primary,
    borderRadius: 10,
    backgroundColor: '#EFFAF5',
    overflow: 'hidden',
  },
  addPhotoContent: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  addPhotoText: { fontSize: 14, fontWeight: '600', color: QI_COLORS.primary, marginTop: 6 },
  lockedHint: {
    flexDirection: 'row',
    backgroundColor: '#FFF4D6',
    borderRadius: 10,
    padding: 12,
    marginTop: 16,
  },
  lockedHintText: { flex: 1, marginLeft: 8, fontSize: 13, lineHeight: 19, color: '#6B4A0A' },
  progressCard: { backgroundColor: QI_COLORS.card, borderRadius: 12, padding: 14, marginTop: 16 },
  progressTextRow: { flexDirection: 'row', alignItems: 'center', marginTop: 10 },
  progressText: { marginLeft: 8, fontSize: 13, color: QI_COLORS.textSecondary },
  errorCard: {
    flexDirection: 'row',
    backgroundColor: '#FFF0F0',
    borderRadius: 12,
    padding: 14,
    marginTop: 16,
  },
  errorTextContainer: { flex: 1, marginLeft: 10 },
  errorTitle: { fontSize: 14, fontWeight: '700', color: '#A52727' },
  errorText: { fontSize: 13, color: '#7D3030', marginTop: 3, lineHeight: 19 },
  errorAction: { fontSize: 12, color: '#9B5454', marginTop: 4 },
  bottomAction: {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: 0,
    paddingHorizontal: 16,
    paddingTop: 12,
    backgroundColor: QI_COLORS.card,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: QI_COLORS.border,
  },
  primaryButtonContent: { minHeight: 50 },
  primaryButtonLabel: { fontSize: 16, fontWeight: '700' },
  modalScreen: { flex: 1, backgroundColor: QI_COLORS.background },
  modalHeader: {
    minHeight: 56,
    flexDirection: 'row',
    alignItems: 'center',
    paddingLeft: 18,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: QI_COLORS.border,
  },
  modalTitle: { flex: 1, fontSize: 19, fontWeight: '700', color: QI_COLORS.text },
  modalClose: {
    width: 48,
    height: 48,
    borderRadius: 24,
    alignItems: 'center',
    justifyContent: 'center',
    marginRight: 4,
  },
  searchbar: { margin: 16, backgroundColor: QI_COLORS.card },
  modalState: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: 30, gap: 14 },
  modalStateText: { fontSize: 14, color: QI_COLORS.textSecondary, textAlign: 'center' },
  productRow: {
    minHeight: 72,
    backgroundColor: QI_COLORS.card,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: QI_COLORS.border,
  },
  productRowContent: { minHeight: 72, flexDirection: 'row', alignItems: 'center', paddingHorizontal: 16 },
  productCodeBadge: { backgroundColor: '#E8F5EF', borderRadius: 8, paddingHorizontal: 8, paddingVertical: 5 },
  productCodeText: { maxWidth: 88, fontSize: 12, fontWeight: '700', color: QI_COLORS.primary },
  productText: { flex: 1, marginHorizontal: 12 },
  productName: { fontSize: 15, fontWeight: '600', color: QI_COLORS.text },
  productMeta: { fontSize: 12, color: QI_COLORS.textSecondary, marginTop: 4 },
});
