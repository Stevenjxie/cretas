/**
 * 报损提交屏 (SP7 R-C)
 *
 * UX Flow 设计回应:
 *   F2: 原因改为 Picker (变质/破损/污染/盗损/其他), 选"其他"才展开 TextInput
 *   F3: 照片区红框强制, 提交按钮在 photos.length < 1 时 disabled
 *   F6: 失败不清表单, 重试
 *   F7: 数量输入 fontSize 24, keyboardType=numeric
 *
 * 照片上传: 复用 /api/mobile/{factoryId}/upload/yield-evidence 端点 (OSS 后端中转)
 * 提交: POST /api/mobile/{factoryId}/wastage-reports (create DRAFT + 自动 submit)
 */

import React, { useState, useCallback, useEffect } from 'react';
import {
  View,
  StyleSheet,
  ScrollView,
  TextInput as RNTextInput,
  KeyboardAvoidingView,
  Platform,
  Image,
  ActivityIndicator,
  TouchableOpacity,
} from 'react-native';
import {
  Text,
  TouchableRipple,
  RadioButton,
  Divider,
} from 'react-native-paper';
import { BatchSelectorModal, BatchSelection } from '../../../components/warehouse/BatchSelectorModal';
import { SafeAreaView } from 'react-native-safe-area-context';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useNavigation, useRoute, RouteProp } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import * as ImagePicker from 'expo-image-picker';
import * as ImageManipulator from 'expo-image-manipulator';
import { WHInventoryStackParamList } from '../../../types/navigation';
import { materialBatchApiClient } from '../../../services/api/materialBatchApiClient';
import { NeoButton } from '../../../components/ui/NeoButton';
import { NeoCard } from '../../../components/ui/NeoCard';
import { AppDialogHost, appAlert } from '../../../components/ui/AppDialog';
import {
  wastageReportApiClient,
  WastageTrackType,
  WastageReason,
  CreateWastageReportRequest,
} from '../../../services/api/stocktakeApiClient';
import { apiClient } from '../../../services/api/apiClient';
import { getCurrentFactoryId } from '../../../utils/factoryIdHelper';

type NavProp = NativeStackNavigationProp<WHInventoryStackParamList>;

const PRIMARY = '#1890FF';
const RED = '#FF4D4F';

// ── 原因选项 ─────────────────────────────────────
const REASON_OPTIONS: { value: WastageReason; label: string }[] = [
  { value: 'EXPIRED', label: '变质/过期' },
  { value: 'DAMAGED', label: '破损' },
  { value: 'CONTAMINATED', label: '污染' },
  { value: 'THEFT', label: '盗损' },
  { value: 'OTHER', label: '其他' },
];

interface PhotoItem {
  uri: string;
  uploading: boolean;
  serverUrl?: string;
  error?: boolean;
}

export function WastageReportScreen(): React.JSX.Element {
  const navigation = useNavigation<NavProp>();
  // Route params: batchId 可由 WHExpireHandle 或 WHInventoryCheck 预传, 用于防呆预填批次
  const route = useRoute<RouteProp<WHInventoryStackParamList, 'WastageReport'>>();
  const prefillBatchId = route.params?.batchId;

  // ── 表单状态 ──────────────────────────────────
  const [trackType, setTrackType] = useState<WastageTrackType>('WAREHOUSE');
  const [materialBatchId, setMaterialBatchId] = useState('');
  // 已选批次的展示信息 (物料名 + 剩余量), 仅用于 UI 回显, 提交时只用 materialBatchId
  const [selectedBatch, setSelectedBatch] = useState<BatchSelection | null>(null);
  const [batchSelectorVisible, setBatchSelectorVisible] = useState(false);
  const [wastageQty, setWastageQty] = useState('');
  const [wastageReason, setWastageReason] = useState<WastageReason>('DAMAGED');
  const [reasonDetail, setReasonDetail] = useState('');
  const [notes, setNotes] = useState('');
  const [photos, setPhotos] = useState<PhotoItem[]>([]);
  const [submitting, setSubmitting] = useState(false);

  const handleBatchSelect = useCallback((sel: BatchSelection) => {
    setSelectedBatch(sel);
    setMaterialBatchId(sel.materialBatchId);
    setBatchSelectorVisible(false);
  }, []);

  // ── 防呆预填: 来自路由的 batchId 自动加载批次信息 ────────
  useEffect(() => {
    if (!prefillBatchId) return;
    const fid = getCurrentFactoryId();
    materialBatchApiClient.getBatchById(prefillBatchId, fid ?? undefined).then((res) => {
      if (res.success && res.data) {
        const b = res.data;
        setMaterialBatchId(b.id);
        setSelectedBatch({
          materialBatchId: b.id,
          batchNumber: b.batchNumber,
          materialName: b.materialName ?? b.batchNumber,
          remainingQuantity: b.remainingQuantity,
          unit: undefined,
        });
      }
    }).catch(() => {
      // 预填失败不阻断操作, 用户可手动选择批次
    });
  }, [prefillBatchId]);

  // ── 审批路由说明 ──────────────────────────────
  const approvalNote =
    trackType === 'WAREHOUSE'
      ? '仓库报损 → 财务审批'
      : '工厂报损 → 厂长审批';

  // ── 照片上传 ──────────────────────────────────
  const uploadPhoto = useCallback(async (localUri: string) => {
    const fid = getCurrentFactoryId();
    setPhotos((prev) => [...prev, { uri: localUri, uploading: true }]);
    try {
      const formData = new FormData();
      formData.append('file', {
        uri: localUri,
        name: `wastage_${Date.now()}.jpg`,
        type: 'image/jpeg',
      } as unknown as Blob);
      const res = await apiClient.post<{ success: boolean; data: { url: string }; message?: string }>(
        `/api/mobile/${fid}/upload/yield-evidence`,
        formData,
        { headers: { 'Content-Type': 'multipart/form-data' } },
      );
      if (!res.success || !res.data?.url) throw new Error(res.message ?? '上传失败');
      setPhotos((prev) =>
        prev.map((p) => (p.uri === localUri ? { ...p, uploading: false, serverUrl: res.data.url } : p)),
      );
    } catch (err) {
      setPhotos((prev) =>
        prev.map((p) => (p.uri === localUri ? { ...p, uploading: false, error: true } : p)),
      );
      const e = err as { response?: { data?: { message?: string } } };
      appAlert(
        '照片上传失败，请重试',
        e.response?.data?.message ?? '网络不稳定，请检查网络后重新拍照',
      );
    }
  }, []);

  const takePhoto = useCallback(async () => {
    const perm = await ImagePicker.requestCameraPermissionsAsync();
    if (!perm.granted) {
      appAlert('需要相机权限', '请在系统设置中开启相机权限后再拍照');
      return;
    }
    const result = await ImagePicker.launchCameraAsync({
      mediaTypes: ['images'],
      quality: 0.8,
      allowsEditing: false,
    });
    if (!result.canceled && result.assets?.[0]) {
      const manipulated = await ImageManipulator.manipulateAsync(
        result.assets[0].uri,
        [{ resize: { width: 1024 } }],
        { compress: 0.7, format: ImageManipulator.SaveFormat.JPEG },
      );
      await uploadPhoto(manipulated.uri);
    }
  }, [uploadPhoto]);

  const pickPhoto = useCallback(async () => {
    const perm = await ImagePicker.requestMediaLibraryPermissionsAsync();
    if (!perm.granted) {
      appAlert('需要相册权限', '请在系统设置中开启相册权限后再选图');
      return;
    }
    const result = await ImagePicker.launchImageLibraryAsync({
      mediaTypes: ['images'],
      quality: 0.8,
      allowsEditing: false,
      allowsMultipleSelection: true,
    });
    if (!result.canceled && result.assets && result.assets.length > 0) {
      await Promise.all(
        result.assets.map(async (asset) => {
          const manipulated = await ImageManipulator.manipulateAsync(
            asset.uri,
            [{ resize: { width: 1024 } }],
            { compress: 0.7, format: ImageManipulator.SaveFormat.JPEG },
          );
          return uploadPhoto(manipulated.uri);
        }),
      );
    }
  }, [uploadPhoto]);

  const removePhoto = useCallback((uri: string) => {
    setPhotos((prev) => prev.filter((p) => p.uri !== uri));
  }, []);

  // ── 校验 ──────────────────────────────────────
  const uploadedPhotos = photos.filter((p) => p.serverUrl && !p.error);
  const uploadingPhotos = photos.filter((p) => p.uploading);
  const canSubmit =
    materialBatchId.trim().length > 0 &&
    wastageQty.trim().length > 0 &&
    parseFloat(wastageQty) > 0 &&
    uploadedPhotos.length >= 1 &&
    uploadingPhotos.length === 0 &&
    (wastageReason !== 'OTHER' || reasonDetail.trim().length > 0) &&
    !submitting;

  // ── 提交 ──────────────────────────────────────
  const handleSubmit = useCallback(async () => {
    if (!canSubmit) {
      if (uploadedPhotos.length < 1) {
        appAlert('请先上传照片', '报损单必须至少上传一张照片作为凭证，请拍照后再提交');
        return;
      }
      if (!materialBatchId.trim()) {
        appAlert('请填写批次', '请输入被报损物料的批次编号');
        return;
      }
      if (!wastageQty.trim() || parseFloat(wastageQty) <= 0) {
        appAlert('请填写有效数量', '报损数量必须大于 0');
        return;
      }
      return;
    }

    const photoUrlsArr = uploadedPhotos.map((p) => p.serverUrl as string);
    const req: CreateWastageReportRequest = {
      trackType,
      materialBatchId: materialBatchId.trim(),
      wastageQty: parseFloat(wastageQty),
      wastageReason,
      reasonDetail: wastageReason === 'OTHER' ? reasonDetail.trim() : undefined,
      photoUrls: JSON.stringify(photoUrlsArr),
      notes: notes.trim() || undefined,
    };

    setSubmitting(true);
    try {
      const createRes = await wastageReportApiClient.create(req);
      if (!createRes.success || !createRes.data) throw new Error(createRes.message ?? '创建失败');
      // 自动提交审批
      await wastageReportApiClient.submit(createRes.data.id);
      appAlert(
        '报损单已提交',
        `已进入${approvalNote}队列，请等待审批结果。报损单号：${createRes.data.reportNo}`,
        [{ text: '确定', onPress: () => navigation.goBack() }],
      );
    } catch (err) {
      const e = err as { response?: { data?: { message?: string } } };
      // F6: 失败不清表单
      appAlert('提交失败，表单数据已保留', e.response?.data?.message ?? '请检查网络后重试');
    } finally {
      setSubmitting(false);
    }
  }, [canSubmit, uploadedPhotos, materialBatchId, wastageQty, wastageReason, reasonDetail, trackType, notes, approvalNote, navigation]);

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      <AppDialogHost />
      {/* Header */}
      <View style={styles.header}>
        <TouchableRipple onPress={() => navigation.goBack()} style={styles.backBtn} borderless>
          <MaterialCommunityIcons name="arrow-left" size={24} color="#fff" />
        </TouchableRipple>
        <Text style={styles.headerTitle}>报损单</Text>
        <View style={styles.headerRight} />
      </View>

      <KeyboardAvoidingView behavior={Platform.OS === 'ios' ? 'padding' : undefined} style={{ flex: 1 }}>
        <ScrollView style={styles.content} contentContainerStyle={{ paddingBottom: 120 }}>

          {/* 报损类型 */}
          <NeoCard style={styles.card}>
            <Text style={styles.sectionTitle}>报损类型</Text>
            <View style={styles.radioRow}>
              <TouchableRipple onPress={() => setTrackType('WAREHOUSE')} style={styles.radioItem}>
                <View style={styles.radioInner}>
                  <RadioButton
                    value="WAREHOUSE"
                    status={trackType === 'WAREHOUSE' ? 'checked' : 'unchecked'}
                    onPress={() => setTrackType('WAREHOUSE')}
                    color={PRIMARY}
                  />
                  <View>
                    <Text style={styles.radioLabel}>仓库报损</Text>
                    <Text style={styles.radioDesc}>→ 财务审批</Text>
                  </View>
                </View>
              </TouchableRipple>
              <TouchableRipple onPress={() => setTrackType('FACTORY')} style={styles.radioItem}>
                <View style={styles.radioInner}>
                  <RadioButton
                    value="FACTORY"
                    status={trackType === 'FACTORY' ? 'checked' : 'unchecked'}
                    onPress={() => setTrackType('FACTORY')}
                    color={PRIMARY}
                  />
                  <View>
                    <Text style={styles.radioLabel}>工厂报损</Text>
                    <Text style={styles.radioDesc}>→ 厂长审批</Text>
                  </View>
                </View>
              </TouchableRipple>
            </View>
            <View style={styles.approvalNote}>
              <MaterialCommunityIcons name="information-outline" size={14} color="#666" />
              <Text style={styles.approvalNoteText}>{approvalNote}</Text>
            </View>
          </NeoCard>

          {/* 被报损物料 — 批次选择器 (fool-proof Rule 2: 展示身份信息防盲填) */}
          <NeoCard style={styles.card}>
            <Text style={styles.sectionTitle}>被报损物料</Text>
            <Text style={styles.fieldLabel}>
              报损批次 <Text style={{ color: RED }}>*</Text>
            </Text>
            {/* 主要路径: 点击选择器 */}
            <TouchableOpacity
              style={[
                styles.batchSelector,
                !selectedBatch && styles.batchSelectorEmpty,
                !!selectedBatch && styles.batchSelectorFilled,
              ]}
              onPress={() => setBatchSelectorVisible(true)}
              disabled={submitting}
              accessibilityLabel="选择报损批次"
            >
              {selectedBatch ? (
                <View style={styles.batchSelectorContent}>
                  <View style={{ flex: 1 }}>
                    <Text style={styles.batchSelectedName} numberOfLines={1}>
                      {selectedBatch.materialName}
                    </Text>
                    <Text style={styles.batchSelectedNumber}>
                      {selectedBatch.batchNumber}
                    </Text>
                    <Text style={styles.batchSelectedRemain}>
                      可用: {selectedBatch.remainingQuantity}
                    </Text>
                  </View>
                  <Text style={styles.batchSelectorChange}>更换</Text>
                </View>
              ) : (
                <View style={styles.batchSelectorContent}>
                  <Text style={styles.batchSelectorPlaceholder}>
                    点击选择批次（物料名/批次号/可用量）
                  </Text>
                  <Text style={styles.batchSelectorArrow}>›</Text>
                </View>
              )}
            </TouchableOpacity>
            {/* 降级入口: 手输批次 ID（非主路径，仅用于批次不在列表时兜底） */}
            {!selectedBatch && (
              <View style={styles.manualFallback}>
                <Text style={styles.manualFallbackLabel}>批次号不在列表？手动输入：</Text>
                <RNTextInput
                  style={styles.textInputSmall}
                  value={materialBatchId}
                  onChangeText={(v) => {
                    setMaterialBatchId(v);
                    // 手输时清除已选 selection (显示摘要会与手输 ID 不一致)
                    if (selectedBatch) setSelectedBatch(null);
                  }}
                  placeholder="批次 ID"
                  placeholderTextColor="#bbb"
                  autoCapitalize="none"
                />
              </View>
            )}
          </NeoCard>

          {/* 批次选择 Modal */}
          <BatchSelectorModal
            visible={batchSelectorVisible}
            onSelect={handleBatchSelect}
            onDismiss={() => setBatchSelectorVisible(false)}
          />

          {/* 报损数量 */}
          <NeoCard style={styles.card}>
            <Text style={styles.sectionTitle}>报损数量</Text>
            <RNTextInput
              style={[styles.bigInput]}
              value={wastageQty}
              onChangeText={setWastageQty}
              keyboardType="numeric"
              placeholder="0"
              placeholderTextColor="#bbb"
              textAlign="center"
            />
          </NeoCard>

          {/* 报损原因 */}
          <NeoCard style={styles.card}>
            <Text style={styles.sectionTitle}>报损原因 <Text style={{ color: RED }}>*</Text></Text>
            {REASON_OPTIONS.map((opt) => (
              <TouchableRipple
                key={opt.value}
                onPress={() => setWastageReason(opt.value)}
                style={styles.reasonOption}
              >
                <View style={styles.radioInner}>
                  <RadioButton
                    value={opt.value}
                    status={wastageReason === opt.value ? 'checked' : 'unchecked'}
                    onPress={() => setWastageReason(opt.value)}
                    color={PRIMARY}
                  />
                  <Text style={styles.radioLabel}>{opt.label}</Text>
                </View>
              </TouchableRipple>
            ))}
            {wastageReason === 'OTHER' && (
              <RNTextInput
                style={[styles.textInput, { marginTop: 8 }]}
                value={reasonDetail}
                onChangeText={setReasonDetail}
                placeholder="请说明具体原因（必填）"
                placeholderTextColor="#bbb"
                multiline
                numberOfLines={3}
              />
            )}
          </NeoCard>

          {/* 照片证据（必传） */}
          <NeoCard style={[styles.card, uploadedPhotos.length < 1 && styles.photoCardRequired]}>
            <View style={styles.photoHeader}>
              <MaterialCommunityIcons
                name="camera"
                size={20}
                color={uploadedPhotos.length < 1 ? RED : '#555'}
              />
              <Text style={[styles.sectionTitle, { marginLeft: 6 }, uploadedPhotos.length < 1 && { color: RED }]}>
                照片证据（必须上传至少 1 张）
              </Text>
            </View>
            {uploadedPhotos.length < 1 && (
              <Text style={styles.photoRequiredHint}>
                请拍照记录报损物料，无照片无法提交审批
              </Text>
            )}
            <View style={styles.photoGrid}>
              {photos.map((photo) => (
                <View key={photo.uri} style={styles.photoThumb}>
                  <Image source={{ uri: photo.uri }} style={styles.thumbImg} />
                  {photo.uploading && (
                    <View style={styles.uploadingOverlay}>
                      <ActivityIndicator color="#fff" size="small" />
                    </View>
                  )}
                  {photo.error && (
                    <View style={[styles.uploadingOverlay, { backgroundColor: 'rgba(255,77,79,0.6)' }]}>
                      <MaterialCommunityIcons name="alert-circle" size={20} color="#fff" />
                    </View>
                  )}
                  {photo.serverUrl && !photo.error && (
                    <View style={styles.uploadedBadge}>
                      <MaterialCommunityIcons name="check" size={12} color="#fff" />
                    </View>
                  )}
                  <TouchableOpacity
                    style={styles.removeBtn}
                    onPress={() => removePhoto(photo.uri)}
                    hitSlop={{ top: 8, right: 8, bottom: 8, left: 8 }}
                  >
                    <MaterialCommunityIcons name="close-circle" size={20} color="#fff" />
                  </TouchableOpacity>
                </View>
              ))}
              {/* 拍照按钮 */}
              <TouchableRipple onPress={takePhoto} style={styles.addPhotoBtn} borderless>
                <View style={styles.addPhotoBtnInner}>
                  <MaterialCommunityIcons name="camera-plus" size={28} color="#888" />
                  <Text style={styles.addPhotoText}>拍照</Text>
                </View>
              </TouchableRipple>
              {/* 选相册按钮 */}
              <TouchableRipple onPress={pickPhoto} style={styles.addPhotoBtn} borderless>
                <View style={styles.addPhotoBtnInner}>
                  <MaterialCommunityIcons name="image-plus" size={28} color="#888" />
                  <Text style={styles.addPhotoText}>选图</Text>
                </View>
              </TouchableRipple>
            </View>
          </NeoCard>

          {/* 备注 */}
          <NeoCard style={styles.card}>
            <Text style={styles.sectionTitle}>备注（选填）</Text>
            <RNTextInput
              style={[styles.textInput, { minHeight: 60 }]}
              value={notes}
              onChangeText={setNotes}
              placeholder="其他补充说明..."
              placeholderTextColor="#bbb"
              multiline
              numberOfLines={3}
            />
          </NeoCard>

          <Divider style={{ marginHorizontal: 16 }} />
        </ScrollView>
      </KeyboardAvoidingView>

      {/* 底部提交 */}
      <View style={styles.bottomBar}>
        {uploadingPhotos.length > 0 && (
          <Text style={styles.uploadingHint}>
            {uploadingPhotos.length} 张照片上传中，请稍候...
          </Text>
        )}
        <NeoButton
          variant="primary"
          onPress={handleSubmit}
          disabled={!canSubmit}
          loading={submitting}
          style={styles.submitBtn}
          size="large"
        >
          {submitting ? '提交中...' : '提交报损单'}
        </NeoButton>
        {!canSubmit && uploadedPhotos.length < 1 && (
          <Text style={styles.disabledHint}>请先上传照片后方可提交</Text>
        )}
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#F4F6F9' },
  header: {
    backgroundColor: '#FA8C16',
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingVertical: 12,
  },
  backBtn: { padding: 8, borderRadius: 20 },
  headerTitle: { flex: 1, fontSize: 18, fontWeight: '700', color: '#fff', textAlign: 'center' },
  headerRight: { width: 40 },
  content: { flex: 1 },
  card: { margin: 16, marginBottom: 0, marginTop: 12, padding: 16 },
  sectionTitle: { fontSize: 16, fontWeight: '700', color: '#222', marginBottom: 12 },
  fieldLabel: { fontSize: 14, color: '#555', marginBottom: 8, fontWeight: '500' },
  textInput: {
    borderWidth: 1,
    borderColor: '#d9d9d9',
    borderRadius: 8,
    paddingHorizontal: 12,
    paddingVertical: 10,
    fontSize: 16,
    color: '#111',
    backgroundColor: '#fafafa',
  },
  bigInput: {
    fontSize: 40,
    fontWeight: '800',
    color: '#111',
    borderBottomWidth: 2,
    borderBottomColor: PRIMARY,
    paddingVertical: 8,
    textAlign: 'center',
    minHeight: 60,
    keyboardType: 'numeric',
  } as object,
  radioRow: { flexDirection: 'row', gap: 8 },
  radioItem: { flex: 1, paddingVertical: 10, paddingHorizontal: 8, borderRadius: 8 },
  radioInner: { flexDirection: 'row', alignItems: 'center', gap: 4 },
  radioLabel: { fontSize: 15, fontWeight: '600', color: '#222' },
  radioDesc: { fontSize: 12, color: '#888', marginTop: 2 },
  reasonOption: { paddingVertical: 8, paddingHorizontal: 4, borderRadius: 6, marginBottom: 2 },
  approvalNote: {
    flexDirection: 'row',
    alignItems: 'center',
    marginTop: 10,
    gap: 6,
    backgroundColor: '#f0f8ff',
    padding: 8,
    borderRadius: 6,
  },
  approvalNoteText: { fontSize: 13, color: '#555' },
  photoCardRequired: {
    borderWidth: 2,
    borderColor: RED,
  },
  photoHeader: { flexDirection: 'row', alignItems: 'center', marginBottom: 12 },
  photoRequiredHint: { fontSize: 13, color: RED, marginBottom: 12, fontWeight: '500' },
  photoGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  photoThumb: { width: 80, height: 80, borderRadius: 8, overflow: 'hidden', position: 'relative' },
  thumbImg: { width: 80, height: 80 },
  uploadingOverlay: {
    ...StyleSheet.absoluteFillObject,
    backgroundColor: 'rgba(0,0,0,0.4)',
    justifyContent: 'center',
    alignItems: 'center',
  },
  uploadedBadge: {
    position: 'absolute',
    top: 4,
    right: 4,
    width: 20,
    height: 20,
    borderRadius: 10,
    backgroundColor: '#52C41A',
    justifyContent: 'center',
    alignItems: 'center',
  },
  removeBtn: {
    position: 'absolute',
    top: 2,
    left: 2,
    backgroundColor: 'rgba(0,0,0,0.5)',
    borderRadius: 10,
  },
  addPhotoBtn: {
    width: 80,
    height: 80,
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#d9d9d9',
    borderStyle: 'dashed',
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: '#fafafa',
  },
  addPhotoBtnInner: { alignItems: 'center' },
  addPhotoText: { fontSize: 11, color: '#888', marginTop: 4 },
  bottomBar: {
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
    backgroundColor: '#fff',
    padding: 16,
    paddingBottom: 28,
    borderTopWidth: 1,
    borderTopColor: '#e8e8e8',
    gap: 6,
  },
  submitBtn: { width: '100%' },
  uploadingHint: { fontSize: 13, color: '#FA8C16', textAlign: 'center', fontWeight: '500' },
  disabledHint: { fontSize: 12, color: '#aaa', textAlign: 'center' },

  // ── 批次选择器样式 ─────────────────────────────
  batchSelector: {
    borderWidth: 1,
    borderRadius: 8,
    paddingHorizontal: 14,
    paddingVertical: 12,
    minHeight: 52,
    justifyContent: 'center',
  },
  batchSelectorEmpty: {
    borderColor: '#d9d9d9',
    backgroundColor: '#fafafa',
    borderStyle: 'dashed',
  },
  batchSelectorFilled: {
    borderColor: PRIMARY,
    backgroundColor: '#E6F0FF',
  },
  batchSelectorContent: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  batchSelectorPlaceholder: {
    flex: 1,
    fontSize: 15,
    color: '#bbb',
  },
  batchSelectorArrow: {
    fontSize: 22,
    color: '#aaa',
    marginLeft: 4,
  },
  batchSelectedName: {
    fontSize: 15,
    fontWeight: '700',
    color: '#111',
  },
  batchSelectedNumber: {
    fontSize: 13,
    color: '#555',
    marginTop: 2,
  },
  batchSelectedRemain: {
    fontSize: 12,
    color: PRIMARY,
    marginTop: 2,
    fontWeight: '500',
  },
  batchSelectorChange: {
    fontSize: 14,
    color: PRIMARY,
    fontWeight: '600',
    marginLeft: 8,
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderWidth: 1,
    borderColor: PRIMARY,
    borderRadius: 8,
  },
  manualFallback: {
    marginTop: 10,
    paddingTop: 10,
    borderTopWidth: 1,
    borderTopColor: '#f0f0f0',
    gap: 6,
  },
  manualFallbackLabel: {
    fontSize: 12,
    color: '#999',
  },
  textInputSmall: {
    borderWidth: 1,
    borderColor: '#d9d9d9',
    borderRadius: 8,
    paddingHorizontal: 12,
    paddingVertical: 8,
    fontSize: 14,
    color: '#111',
    backgroundColor: '#fafafa',
  },
});

export default WastageReportScreen;
