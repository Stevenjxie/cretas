import React, { useCallback, useEffect, useState } from 'react';
import { Alert, Image, ScrollView, StyleSheet, TouchableOpacity, View } from 'react-native';
import * as FileSystem from 'expo-file-system';
import * as ImagePicker from 'expo-image-picker';
import { Button, Card, Text, TextInput } from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useNavigation } from '@react-navigation/native';

import { attachmentApi } from '../../../services/api/attachmentApi';
import { materialTypeApiClient, MaterialType } from '../../../services/api/materialTypeApiClient';
import { purchaseRequisitionApiClient, PurchaseRequisition } from '../../../services/api/purchaseRequisitionApiClient';
import { restaurantApiClient } from '../../../services/api/restaurantApiClient';
import { speechRecognitionService } from '../../../services/voice/SpeechRecognitionService';
import { useAuthStore } from '../../../store/authStore';
import { handleError } from '../../../utils/errorHandler';

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
  const [materials, setMaterials] = useState<MaterialType[]>([]);
  const [lines, setLines] = useState<LineDraft[]>([newLine()]);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    let alive = true;
    (async () => {
      try {
        const [reqRes, matRes] = await Promise.all([
          purchaseRequisitionApiClient.list({ status: 'APPROVED', page: 1, size: 30 }),
          materialTypeApiClient.getActiveMaterialTypes(factoryId),
        ]);
        if (!alive) return;
        setRequisitions(reqRes.data);
        setMaterials(matRes?.data || []);
      } catch (error) {
        handleError(error, { title: '采购待办加载失败', showAlert: false });
      }
    })();
    return () => { alive = false; };
  }, [factoryId]);

  const applyRequisition = (req: PurchaseRequisition) => {
    setSelectedReqId(req.id);
    setExpectedDeliveryDate(req.expectedDate || '');
    setLines((req.requestedItems || []).map((item, index) => ({
      key: `req-${req.id}-${index}`,
      materialSearch: item.materialName || item.materialTypeId,
      ingredientName: item.materialName || item.materialTypeId,
      rawMaterialTypeId: item.materialTypeId,
      quantity: String(item.quantity),
      unit: item.unit || 'kg',
      unitPrice: '',
    })));
  };

  const updateLine = (key: string, patch: Partial<LineDraft>) => {
    setLines((curr) => curr.map((line) => (line.key === key ? { ...line, ...patch } : line)));
  };

  const filteredMaterials = (line: LineDraft) => {
    const query = line.materialSearch.trim().toLowerCase();
    return materials
      .filter((material) => !query || [material.name, material.code].some((v) => (v || '').toLowerCase().includes(query)))
      .slice(0, 5);
  };

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
    if (!supplierName.trim() && !supplierId.trim()) {
      Alert.alert('请填写供应商', '确认供应商后才能生成送货单草稿。');
      return;
    }
    if (validLines.length === 0) {
      Alert.alert('请填写明细', '至少一行食材数量大于 0。');
      return;
    }
    if (quotePhotos.some((p) => p.uploading)) {
      Alert.alert('照片上传中', '请等待报价照片上传完成后再提交。');
      return;
    }
    if (voiceUploading) {
      Alert.alert('语音上传中', '请等待录音上传完成后再提交。');
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
        <Text style={styles.sectionTitle}>已审批报货</Text>
        {requisitions.length === 0 ? (
          <Text style={styles.meta}>暂无已审批报货，可先手工录入明细。</Text>
        ) : (
          requisitions.map((req) => (
            <Card key={req.id} style={[styles.card, selectedReqId === req.id && styles.cardSelected]} onPress={() => applyRequisition(req)}>
              <Card.Content>
                <Text style={styles.cardTitle}>{req.requisitionNumber}</Text>
                <Text style={styles.meta}>档口：{req.requesterDeptId || '—'} · 到货：{req.expectedDate || '—'}</Text>
              </Card.Content>
            </Card>
          ))
        )}

        <Text style={styles.label}>供应商名称 *</Text>
        <TextInput mode="outlined" value={supplierName} onChangeText={setSupplierName} style={styles.input} />
        <Text style={styles.label}>供应商 ID（可选）</Text>
        <TextInput mode="outlined" value={supplierId} onChangeText={setSupplierId} style={styles.input} />
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
              <TextInput label="食材" mode="outlined" value={line.materialSearch} onChangeText={(v) => updateLine(line.key, { materialSearch: v, ingredientName: v })} style={styles.input} />
              {filteredMaterials(line).map((material) => (
                <TouchableOpacity key={material.id} onPress={() => updateLine(line.key, {
                  rawMaterialTypeId: material.id,
                  ingredientName: material.name,
                  materialSearch: material.name,
                  unit: line.unit || material.unit || 'kg',
                })}>
                  <Text style={styles.pickItem}>{material.name}</Text>
                </TouchableOpacity>
              ))}
              <View style={styles.row}>
                <TextInput label="数量" mode="outlined" keyboardType="decimal-pad" value={line.quantity} onChangeText={(v) => updateLine(line.key, { quantity: v })} style={[styles.input, styles.flex]} />
                <TextInput label="单位" mode="outlined" value={line.unit} onChangeText={(v) => updateLine(line.key, { unit: v })} style={[styles.input, styles.flex]} />
              </View>
              <TextInput label="单价" mode="outlined" keyboardType="decimal-pad" value={line.unitPrice} onChangeText={(v) => updateLine(line.key, { unitPrice: v })} style={styles.input} />
            </Card.Content>
          </Card>
        ))}

        <Button mode="contained" loading={submitting} disabled={submitting} onPress={submit} style={styles.submit}>
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
  sectionTitle: { fontSize: 16, fontWeight: '700', marginTop: 12, marginBottom: 8 },
  sectionRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginTop: 12 },
  label: { fontSize: 14, fontWeight: '500', marginTop: 10, marginBottom: 4 },
  input: { backgroundColor: '#fff', marginTop: 6 },
  card: { marginBottom: 8, borderRadius: 8 },
  cardSelected: { borderColor: '#2E7D32', borderWidth: 1 },
  cardTitle: { fontWeight: '700' },
  meta: { fontSize: 13, color: '#6B7280', marginTop: 4 },
  pickItem: { paddingVertical: 6, color: '#1B65A8' },
  row: { flexDirection: 'row', gap: 8 },
  flex: { flex: 1 },
  submit: { marginTop: 20, borderRadius: 8 },
  photoActions: { flexDirection: 'row', gap: 8 },
  photoGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginBottom: 8 },
  photoItem: { width: 108, alignItems: 'center' },
  photoThumb: { width: 96, height: 96, borderRadius: 8, backgroundColor: '#E5E7EB' },
  photoStatus: { fontSize: 12, color: '#6B7280', marginTop: 4 },
});

export default ProcurementDeliveryConfirmScreen;
