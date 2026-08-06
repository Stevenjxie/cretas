import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Alert, Image, ScrollView, StyleSheet, View } from 'react-native';
import * as FileSystem from 'expo-file-system';
import * as ImagePicker from 'expo-image-picker';
import { ActivityIndicator, Button, Card, HelperText, Text, TextInput, TouchableRipple } from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useNavigation } from '@react-navigation/native';

import { attachmentApi } from '../../../services/api/attachmentApi';
import { materialTypeApiClient, MaterialType } from '../../../services/api/materialTypeApiClient';
import { purchaseRequisitionApiClient, PurchaseRequisition } from '../../../services/api/purchaseRequisitionApiClient';
import { restaurantApiClient } from '../../../services/api/restaurantApiClient';
import { supplierApiClient, Supplier } from '../../../services/api/supplierApiClient';
import { speechRecognitionService } from '../../../services/voice/SpeechRecognitionService';
import { useAuthStore } from '../../../store/authStore';
import { handleError } from '../../../utils/errorHandler';
import {
  filterSupplierOptions,
  getProcurementDeliverySubmitBlocker,
  resolveRequisitionMaterialName,
} from './procurementDeliveryForm';

interface LineDraft {
  key: string;
  materialSearch: string;
  ingredientName: string;
  rawMaterialTypeId: string;
  quantity: string;
  unit: string;
  unitPrice: string;
}

interface QuotePhoto {
  key: string;
  uri: string;
  fileName: string;
  mimeType: string;
  uploading?: boolean;
  fileUrl?: string;
}

export function ProcurementDeliveryConfirmScreen() {
  const navigation = useNavigation<any>();
  const { user } = useAuthStore();
  const factoryId = user?.factoryId;

  const [requisitions, setRequisitions] = useState<PurchaseRequisition[]>([]);
  const [selectedReqId, setSelectedReqId] = useState('');
  const [supplierId, setSupplierId] = useState('');
  const [supplierName, setSupplierName] = useState('');
  const [deliveryDate, setDeliveryDate] = useState(new Date().toISOString().slice(0, 10));
  const [expectedDeliveryDate, setExpectedDeliveryDate] = useState('');
  const [supplierContactNote, setSupplierContactNote] = useState('');
  const [voiceTranscriptText, setVoiceTranscriptText] = useState('');
  const [voiceAudioUrl, setVoiceAudioUrl] = useState('');
  const [voiceUploading, setVoiceUploading] = useState(false);
  const [voiceRecording, setVoiceRecording] = useState(false);
  const [quotePhotos, setQuotePhotos] = useState<QuotePhoto[]>([]);
  const [suppliers, setSuppliers] = useState<Supplier[]>([]);
  const [materials, setMaterials] = useState<MaterialType[]>([]);
  const [lines, setLines] = useState<LineDraft[]>([newLine()]);
  const [loadingReferences, setLoadingReferences] = useState(true);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    let alive = true;
    (async () => {
      try {
        const [reqRes, matRes, supplierList] = await Promise.all([
          purchaseRequisitionApiClient.list({ status: 'APPROVED', page: 1, size: 30 }),
          materialTypeApiClient.getActiveMaterialTypes(factoryId),
          supplierApiClient.getActiveSuppliers(factoryId),
        ]);
        if (!alive) return;
        setRequisitions(reqRes.data);
        setMaterials(matRes?.data || []);
        setSuppliers(supplierList || []);
      } catch (error) {
        handleError(error, { title: '采购送货资料加载失败', showAlert: false });
      } finally {
        if (alive) setLoadingReferences(false);
      }
    })();
    return () => { alive = false; };
  }, [factoryId]);

  const applyRequisition = (req: PurchaseRequisition) => {
    setSelectedReqId(req.id);
    setExpectedDeliveryDate(req.expectedDate || '');
    setLines((req.requestedItems || []).map((item, index) => {
      const materialName = resolveRequisitionMaterialName(item.materialName, item.materialTypeId, materials);
      return {
        key: `req-${req.id}-${index}`,
        materialSearch: materialName,
        ingredientName: materialName,
        rawMaterialTypeId: item.materialTypeId,
        quantity: String(item.quantity),
        unit: item.unit || 'kg',
        unitPrice: '',
      };
    }));
  };

  const filteredSuppliers = useMemo(
    () => filterSupplierOptions(suppliers, supplierName),
    [supplierName, suppliers],
  );

  const selectedSupplier = useMemo(
    () => suppliers.find((supplier) => supplier.id === supplierId),
    [supplierId, suppliers],
  );

  const updateLine = (key: string, patch: Partial<LineDraft>) => {
    setLines((curr) => curr.map((line) => (line.key === key ? { ...line, ...patch } : line)));
  };

  const filteredMaterials = (line: LineDraft) => {
    const query = line.materialSearch.trim().toLowerCase();
    return materials
      .filter((material) => !query || [material.name, material.code].some((v) => (v || '').toLowerCase().includes(query)))
      .slice(0, 5);
  };

  const submitBlocker = useMemo(() => getProcurementDeliverySubmitBlocker({
    supplierName,
    deliveryDate,
    lines,
    quoteUploading: quotePhotos.some((photo) => photo.uploading),
    voiceUploading,
  }), [deliveryDate, lines, quotePhotos, supplierName, voiceUploading]);

  const guessExt = (uri: string, mime?: string): string => {
    if (mime?.startsWith('image/')) return mime.replace('image/', '');
    const dot = uri.lastIndexOf('.');
    return dot > 0 ? uri.substring(dot + 1) : 'jpg';
  };

  const addQuotePhoto = useCallback(async (asset: ImagePicker.ImagePickerAsset) => {
    let size = asset.fileSize ?? 0;
    if (!size) {
      const info = await FileSystem.getInfoAsync(asset.uri, { size: true });
      size = info.exists && 'size' in info ? (info.size as number) : 0;
    }
    const fileName = asset.fileName ?? `supplier_quote_${Date.now()}.${guessExt(asset.uri, asset.mimeType)}`;
    const mimeType = asset.mimeType ?? 'image/jpeg';
    const key = `photo-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`;
    setQuotePhotos((prev) => [...prev, { key, uri: asset.uri, fileName, mimeType, uploading: true }]);
    try {
      const fileUrl = await attachmentApi.uploadToOss({ uri: asset.uri, name: fileName, type: mimeType }, factoryId);
      setQuotePhotos((prev) => prev.map((p) => (p.key === key ? { ...p, uploading: false, fileUrl } : p)));
    } catch (error) {
      setQuotePhotos((prev) => prev.filter((p) => p.key !== key));
      handleError(error, { title: '报价照片上传失败' });
    }
  }, [factoryId]);

  const pickQuotePhoto = async (source: 'camera' | 'library') => {
    const permission = source === 'camera'
      ? await ImagePicker.requestCameraPermissionsAsync()
      : await ImagePicker.requestMediaLibraryPermissionsAsync();
    if (!permission.granted) {
      Alert.alert('需要照片权限', '请允许访问相机或相册后再上传供应商报价照片。');
      return;
    }
    const result = source === 'camera'
      ? await ImagePicker.launchCameraAsync({ mediaTypes: ['images'], quality: 0.82 })
      : await ImagePicker.launchImageLibraryAsync({ mediaTypes: ['images'], quality: 0.82, allowsMultipleSelection: true });
    if (result.canceled) return;
    for (const asset of result.assets) {
      await addQuotePhoto(asset);
    }
  };

  const removeQuotePhoto = (key: string) => {
    setQuotePhotos((prev) => prev.filter((p) => p.key !== key));
  };

  const uploadVoiceRecording = useCallback(async (audioUri: string) => {
    setVoiceUploading(true);
    try {
      const fileUrl = await attachmentApi.uploadToOss({
        uri: audioUri,
        name: `supplier_voice_${Date.now()}.wav`,
        type: 'audio/wav',
      }, factoryId);
      setVoiceAudioUrl(fileUrl);
    } catch (error) {
      handleError(error, { title: '语音录音上传失败', showAlert: false });
    } finally {
      setVoiceUploading(false);
    }
  }, [factoryId]);

  const toggleVoiceRecording = async () => {
    if (voiceRecording) {
      try {
        const result = await speechRecognitionService.stopListening();
        const text = result?.text?.trim();
        if (text && !text.includes('[语音识别需要配置讯飞密钥]')) {
          setVoiceTranscriptText((prev) => (prev.trim() ? `${prev.trim()}\n${text}` : text));
        } else if (!voiceTranscriptText.trim()) {
          Alert.alert('未识别到语音', '可重试录音，或直接粘贴供应商联系记录。');
        }
        if (result?.audioUri) {
          await uploadVoiceRecording(result.audioUri);
        }
      } catch (error) {
        handleError(error, { title: '语音识别失败' });
      } finally {
        setVoiceRecording(false);
      }
      return;
    }

    try {
      const hasPermission = await speechRecognitionService.requestPermissions();
      if (!hasPermission) {
        Alert.alert('需要麦克风权限', '请在系统设置中开启麦克风后再录音。');
        return;
      }
      setVoiceRecording(true);
      await speechRecognitionService.startListening();
    } catch (error) {
      setVoiceRecording(false);
      handleError(error, { title: '无法开始录音' });
    }
  };

  const submit = async () => {
    const validLines = lines.filter((line) => line.rawMaterialTypeId && Number(line.quantity) > 0);
    if (submitBlocker) {
      Alert.alert('送货信息还不完整', submitBlocker);
      return;
    }

    setSubmitting(true);
    try {
      const supplierQuotePhotoUrls = quotePhotos.map((p) => p.fileUrl).filter((url): url is string => Boolean(url));
      const note = await restaurantApiClient.createProcurementDelivery({
        sourceRequisitionId: selectedReqId || undefined,
        supplierId: supplierId.trim() || undefined,
        supplierName: supplierName.trim() || undefined,
        deliveryDate,
        expectedDeliveryDate: expectedDeliveryDate || undefined,
        supplierContactNote: supplierContactNote.trim() || undefined,
        voiceTranscriptText: voiceTranscriptText.trim() || undefined,
        voiceAudioUrl: voiceAudioUrl.trim() || undefined,
        supplierQuotePhotoUrls: supplierQuotePhotoUrls.length > 0 ? supplierQuotePhotoUrls : undefined,
        lines: validLines.map((line) => ({
          ingredientName: line.ingredientName,
          rawMaterialTypeId: line.rawMaterialTypeId,
          quantity: Number(line.quantity),
          unit: line.unit,
          unitPrice: line.unitPrice.trim() ? Number(line.unitPrice) : undefined,
        })),
      });
      Alert.alert('送货单草稿已生成', `单号：${note.noteNumber || note.id}`, [
        {
          text: '去验收',
          onPress: () => navigation.navigate('SupplierDeliveryDetail', { noteId: note.id }),
        },
      ]);
    } catch (error) {
      handleError(error, { title: '生成送货单失败' });
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <SafeAreaView style={styles.container}>
      <View style={styles.header}>
        <Button icon="arrow-left" textColor="#fff" onPress={() => navigation.goBack()}>返回</Button>
        <Text style={styles.headerTitle}>采购确认送货</Text>
        <View style={{ width: 60 }} />
      </View>

      <ScrollView contentContainerStyle={styles.content}>
        {loadingReferences ? (
          <View style={styles.loadingRow}>
            <ActivityIndicator size="small" />
            <Text style={styles.meta}>正在加载报货、供应商和食材...</Text>
          </View>
        ) : null}

        <Text style={styles.sectionTitle}>已审批报货</Text>
        {requisitions.length === 0 ? (
          <Text style={styles.meta}>暂无已审批报货，可先手工录入明细。</Text>
        ) : (
          requisitions.map((req) => (
            <Card key={req.id} style={[styles.card, selectedReqId === req.id && styles.cardSelected]} onPress={() => applyRequisition(req)}>
              <Card.Content>
                <Text style={styles.cardTitle}>{req.requisitionNumber}</Text>
                <Text style={styles.meta}>
                  食材：{req.requestedItems?.length || 0} 项 · 到货：{req.expectedDate || '未填写'}
                </Text>
                {req.reason ? <Text style={styles.meta}>用途：{req.reason}</Text> : null}
              </Card.Content>
            </Card>
          ))
        )}

        <Text style={styles.label}>供应商名称 *</Text>
        <TextInput
          mode="outlined"
          value={supplierName}
          onChangeText={(value) => {
            setSupplierName(value);
            setSupplierId('');
          }}
          placeholder="输入供应商名称、编号或联系人"
          style={styles.input}
        />
        {filteredSuppliers.map((supplier) => (
          <TouchableRipple
            key={supplier.id}
            onPress={() => {
              setSupplierId(supplier.id);
              setSupplierName(supplier.name);
            }}
            style={[styles.optionCard, supplierId === supplier.id && styles.optionCardSelected]}
            borderless={false}
          >
            <View>
              <Text style={styles.optionTitle}>{supplier.name}</Text>
              <Text style={styles.optionMeta}>
                {[supplier.supplierCode || supplier.code, supplier.contactPerson, supplier.phone].filter(Boolean).join(' · ') || '活跃供应商'}
              </Text>
            </View>
          </TouchableRipple>
        ))}
        <HelperText type="info" visible={Boolean(supplierName.trim())}>
          {selectedSupplier
            ? `已匹配供应商：${selectedSupplier.name}`
            : '未匹配供应商主数据，将按当前名称保存；请确认名称无误。'}
        </HelperText>
        <Text style={styles.label}>送货日期</Text>
        <TextInput mode="outlined" value={deliveryDate} onChangeText={setDeliveryDate} style={styles.input} />
        <Text style={styles.label}>供应商联系记录</Text>
        <TextInput mode="outlined" value={supplierContactNote} onChangeText={setSupplierContactNote} multiline style={styles.input} />

        <View style={styles.sectionRow}>
          <Text style={styles.sectionTitle}>供应商报价照片</Text>
          <View style={styles.photoActions}>
            <Button compact mode="outlined" icon="camera" onPress={() => { void pickQuotePhoto('camera'); }}>拍照</Button>
            <Button compact mode="outlined" icon="image" onPress={() => { void pickQuotePhoto('library'); }}>相册</Button>
          </View>
        </View>
        {quotePhotos.length === 0 ? (
          <Text style={styles.meta}>可上传微信报价截图或纸质报价单，照片会直传 OSS 并关联送货单。</Text>
        ) : (
          <View style={styles.photoGrid}>
            {quotePhotos.map((photo) => (
              <View key={photo.key} style={styles.photoItem}>
                <Image source={{ uri: photo.uri }} style={styles.photoThumb} />
                {photo.uploading ? <Text style={styles.photoStatus}>上传中…</Text> : null}
                {!photo.uploading ? (
                  <Button compact mode="text" textColor="#B91C1C" onPress={() => removeQuotePhoto(photo.key)}>删除</Button>
                ) : null}
              </View>
            ))}
          </View>
        )}

        <View style={styles.sectionRow}>
          <Text style={styles.sectionTitle}>语音联系记录</Text>
          <Button
            compact
            mode={voiceRecording ? 'contained' : 'outlined'}
            icon={voiceRecording ? 'stop' : 'microphone'}
            onPress={() => { void toggleVoiceRecording(); }}
          >
            {voiceRecording ? '结束录音' : '开始录音'}
          </Button>
        </View>
        <TextInput
          mode="outlined"
          value={voiceTranscriptText}
          onChangeText={setVoiceTranscriptText}
          multiline
          placeholder="录音转写会填入此处，也可手动粘贴或编辑"
          style={styles.input}
        />
        {voiceUploading ? <Text style={styles.meta}>语音录音上传中…</Text> : null}
        {!voiceUploading && voiceAudioUrl ? (
          <Text style={styles.meta}>原音频已保存，将随送货单一并归档。</Text>
        ) : null}

        <View style={styles.sectionRow}>
          <Text style={styles.sectionTitle}>送货明细</Text>
          <Button compact mode="outlined" icon="plus" onPress={() => setLines((curr) => [...curr, newLine()])}>加一行</Button>
        </View>
        {lines.map((line, index) => (
          <Card key={line.key} style={styles.card}>
            <Card.Content>
              <Text style={styles.cardTitle}>第 {index + 1} 行</Text>
              <TextInput
                label="食材"
                mode="outlined"
                value={line.materialSearch}
                onChangeText={(value) => updateLine(line.key, {
                  materialSearch: value,
                  ingredientName: value,
                  rawMaterialTypeId: '',
                })}
                placeholder="输入食材名称或编码后选择"
                style={styles.input}
              />
              {filteredMaterials(line).map((material) => (
                <TouchableRipple
                  key={material.id}
                  onPress={() => updateLine(line.key, {
                    rawMaterialTypeId: material.id,
                    ingredientName: material.name,
                    materialSearch: material.name,
                    unit: line.unit || material.unit || 'kg',
                  })}
                  style={[styles.optionCard, line.rawMaterialTypeId === material.id && styles.optionCardSelected]}
                  borderless={false}
                >
                  <View>
                    <Text style={styles.optionTitle}>{material.name}</Text>
                    <Text style={styles.optionMeta}>
                      {[material.code, material.category, material.unit].filter(Boolean).join(' · ') || '食材主数据'}
                    </Text>
                  </View>
                </TouchableRipple>
              ))}
              {line.materialSearch.trim() && filteredMaterials(line).length === 0 ? (
                <Text style={styles.emptyHint}>没有匹配食材，请联系管理员先维护食材主数据。</Text>
              ) : null}
              {line.rawMaterialTypeId ? (
                <HelperText type="info" visible>
                  已选择：{line.ingredientName || '未命名食材'}
                </HelperText>
              ) : null}
              <View style={styles.row}>
                <TextInput label="数量" mode="outlined" keyboardType="decimal-pad" value={line.quantity} onChangeText={(v) => updateLine(line.key, { quantity: v })} style={[styles.input, styles.flex]} />
                <TextInput label="单位" mode="outlined" value={line.unit} onChangeText={(v) => updateLine(line.key, { unit: v })} style={[styles.input, styles.flex]} />
              </View>
              <TextInput label="单价" mode="outlined" keyboardType="decimal-pad" value={line.unitPrice} onChangeText={(v) => updateLine(line.key, { unitPrice: v })} style={styles.input} />
            </Card.Content>
          </Card>
        ))}

        <View style={[styles.submitStatus, submitBlocker ? styles.submitStatusBlocked : styles.submitStatusReady]}>
          <Text style={submitBlocker ? styles.submitHintBlocked : styles.submitHintReady}>
            {submitBlocker || '信息完整，可以生成送货单草稿。'}
          </Text>
        </View>
        <Button
          mode="contained"
          loading={submitting}
          disabled={submitting || Boolean(submitBlocker)}
          onPress={submit}
          style={styles.submit}
          contentStyle={styles.primaryActionContent}
        >
          生成送货单草稿
        </Button>
      </ScrollView>
    </SafeAreaView>
  );
}

function newLine(): LineDraft {
  return {
    key: `line-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
    materialSearch: '',
    ingredientName: '',
    rawMaterialTypeId: '',
    quantity: '',
    unit: 'kg',
    unitPrice: '',
  };
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f5f5f5' },
  header: { backgroundColor: '#2E7D32', flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: 4, paddingVertical: 8 },
  headerTitle: { fontSize: 18, fontWeight: 'bold', color: '#fff' },
  content: { padding: 16, paddingBottom: 40 },
  loadingRow: { minHeight: 44, flexDirection: 'row', alignItems: 'center', gap: 8, marginBottom: 4 },
  sectionTitle: { fontSize: 16, fontWeight: '700', marginTop: 12, marginBottom: 8 },
  sectionRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginTop: 12 },
  label: { fontSize: 14, fontWeight: '500', marginTop: 10, marginBottom: 4 },
  input: { backgroundColor: '#fff', marginTop: 6 },
  card: { marginBottom: 8, borderRadius: 8 },
  cardSelected: { borderColor: '#2E7D32', borderWidth: 1 },
  cardTitle: { fontWeight: '700' },
  meta: { fontSize: 13, color: '#6B7280', marginTop: 4 },
  optionCard: {
    minHeight: 52,
    justifyContent: 'center',
    marginTop: 6,
    paddingHorizontal: 12,
    paddingVertical: 8,
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#D1D5DB',
    backgroundColor: '#FFFFFF',
    overflow: 'hidden',
  },
  optionCardSelected: { borderColor: '#2E7D32', borderWidth: 2, backgroundColor: '#F0FDF4' },
  optionTitle: { color: '#111827', fontWeight: '700' },
  optionMeta: { color: '#6B7280', fontSize: 12, marginTop: 2 },
  emptyHint: { color: '#9A3412', fontSize: 13, lineHeight: 19, marginTop: 8 },
  row: { flexDirection: 'row', gap: 8 },
  flex: { flex: 1 },
  submit: { marginTop: 20, borderRadius: 8 },
  primaryActionContent: { minHeight: 48 },
  submitStatus: { marginTop: 18, padding: 12, borderRadius: 8, borderWidth: 1 },
  submitStatusBlocked: { backgroundColor: '#FFF7ED', borderColor: '#FED7AA' },
  submitStatusReady: { backgroundColor: '#F0FDF4', borderColor: '#BBF7D0' },
  submitHintBlocked: { color: '#9A3412', fontSize: 13, lineHeight: 19 },
  submitHintReady: { color: '#166534', fontSize: 13, lineHeight: 19, fontWeight: '600' },
  photoActions: { flexDirection: 'row', gap: 8 },
  photoGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginBottom: 8 },
  photoItem: { width: 108, alignItems: 'center' },
  photoThumb: { width: 96, height: 96, borderRadius: 8, backgroundColor: '#E5E7EB' },
  photoStatus: { fontSize: 12, color: '#6B7280', marginTop: 4 },
});

export default ProcurementDeliveryConfirmScreen;
