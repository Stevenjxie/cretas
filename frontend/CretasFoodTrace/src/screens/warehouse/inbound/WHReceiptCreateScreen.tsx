/**
 * W-ABA-1 Day 5 — PDF 扫码后收货录入页 (2 字段闭环).
 *
 * 场景: 仓管员扫 PDF 二维码 → WHScanOperation 拿到 orderNumber → 调 by-number
 * 接口 → 跳本页 (purchaseOrderId / orderNumber). 本页 prefill 全部信息, 仓管员只
 * 录"收货数量 + 商品日期"两个字段, 抄码品行的数量改成实际称重.
 *
 * 客户原话 (六扇门 May 7 part 2):
 * - "做仓管的他年纪都比较大文化素质很低的"
 * - "对于那个仓管员来说他的任务很简单就是我核对数量核对那个商品的日期这两个"
 * - "其他的话就尽量少让那个仓管员去参与什么什么价格类的"
 * - L177-180: "拍照也可以留个单谱吧, 就是你留个附件类似一个拍照然后一个附件吗也可以的呀"
 *
 * 提交流程:
 *   1. POST /purchase/receives  (创建草稿入库单, 含全部 items)
 *   2. POST /purchase/receives/{id}/confirm  (确认 → 触发 material_batches 创建)
 *   3. 对每个抄码品行: POST /material/abaca-log (用 batchNumber 自动解析 batchId)
 *   4. Issue #794: 把预先拍的照片上传到 OSS, 注册 entityType=PURCHASE_RECEIPT, entityId=receive.id
 */

import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  View,
  ScrollView,
  StyleSheet,
  Alert,
  Image,
  KeyboardAvoidingView,
  Platform,
  TouchableOpacity,
} from 'react-native';
import {
  ActivityIndicator,
  Appbar,
  Button,
  Card,
  Chip,
  Text,
  TextInput,
} from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useNavigation, useRoute, RouteProp } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import * as ImagePicker from 'expo-image-picker';
import * as FileSystem from 'expo-file-system';

import { WHInboundStackParamList } from '../../../types/navigation';
import {
  purchaseApiClient,
  PurchaseOrder,
  PurchaseOrderItem,
  PurchaseReceiveRecord,
  PurchaseReceiveItem,
  CreateReceiveRecordRequest,
} from '../../../services/api/purchaseApiClient';
import {
  materialTypeApiClient,
  MaterialType,
} from '../../../services/api/materialTypeApiClient';
import { abacaApiClient } from '../../../services/api/abacaApiClient';
import { attachmentApi } from '../../../services/api/attachmentApi';
import { materialBatchApiClient } from '../../../services/api/materialBatchApiClient';
import { handleError } from '../../../utils/errorHandler';
import { useAuthStore } from '../../../store/authStore';

type Nav = NativeStackNavigationProp<WHInboundStackParamList, 'WHReceiptCreate'>;
type RouteProps = RouteProp<WHInboundStackParamList, 'WHReceiptCreate'>;

interface RowDraft {
  receivedQuantity: string;
  productionDate: string;       // YYYY-MM-DD
}

// Issue #794: 收货拍照 — 提交前拍, 提交后批量上传到 entity=PURCHASE_RECEIPT
interface PendingPhoto {
  uri: string;
  fileName: string;
  mimeType: string;
  size: number;
}

const todayStr = (): string => {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
};

export default function WHReceiptCreateScreen() {
  const navigation = useNavigation<Nav>();
  const route = useRoute<RouteProps>();
  const { purchaseOrderId, orderNumber } = route.params;
  const { user } = useAuthStore();
  const factoryId = user?.factoryId;

  const [order, setOrder] = useState<PurchaseOrder | null>(null);
  const [materialsById, setMaterialsById] = useState<Record<string, MaterialType>>({});
  const [rows, setRows] = useState<Record<string, RowDraft>>({});
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  // Issue #794: 提交前 stash 照片, 提交成功后批量上传
  const [photos, setPhotos] = useState<PendingPhoto[]>([]);
  // SP4: 可选录入厂号/产地 (入库时记录, 提交后写入批次)
  const [factoryNumber, setFactoryNumber] = useState('');
  const [originPlace, setOriginPlace] = useState('');

  // ============================================================
  // 加载 PO + 关联 MaterialType (含 abaca flag)
  // ============================================================
  useEffect(() => {
    (async () => {
      try {
        const orderRes = await purchaseApiClient.getOrder(purchaseOrderId, factoryId);
        const o = orderRes?.data;
        if (!o || !o.items || o.items.length === 0) {
          Alert.alert('订单异常', '此采购单无明细, 无法收货', [
            { text: '返回', onPress: () => navigation.goBack() },
          ]);
          return;
        }
        setOrder(o);

        // prefill 每行: 收货数量默认 = 订单数量 - 已收数量, 商品日期默认今天
        const initRows: Record<string, RowDraft> = {};
        for (const it of o.items) {
          const remaining = Math.max(0, (it.quantity || 0) - (it.receivedQuantity || 0));
          initRows[it.id] = {
            receivedQuantity: remaining > 0 ? String(remaining) : '',
            productionDate: todayStr(),
          };
        }
        setRows(initRows);

        // 并行拉每个原料的 MaterialType (拿 abaca 标记)
        const uniqueTypeIds = Array.from(new Set(o.items.map((i) => i.materialTypeId)));
        const typesArr = await Promise.all(
          uniqueTypeIds.map((id) =>
            materialTypeApiClient
              .getMaterialTypeById(id, factoryId)
              .catch(() => null),
          ),
        );
        const map: Record<string, MaterialType> = {};
        typesArr.forEach((t, idx) => {
          const typeId = uniqueTypeIds[idx];
          if (t && typeId) map[typeId] = t;
        });
        setMaterialsById(map);
      } catch (err) {
        handleError(err, { title: '加载采购订单失败' });
      } finally {
        setLoading(false);
      }
    })();
  }, [purchaseOrderId, factoryId, navigation]);

  // ============================================================
  // 工具
  // ============================================================
  const isAbacaItem = useCallback(
    (item: PurchaseOrderItem): boolean =>
      !!materialsById[item.materialTypeId]?.isAbacaPackaging,
    [materialsById],
  );

  const unitFor = useCallback(
    (item: PurchaseOrderItem): string => {
      const m = materialsById[item.materialTypeId];
      if (m?.isAbacaPackaging) return m.abacaDefaultUnit || 'kg';
      return item.unit || m?.unit || '';
    },
    [materialsById],
  );

  const updateRow = (itemId: string, patch: Partial<RowDraft>) => {
    setRows((prev) => ({
      ...prev,
      [itemId]: { ...(prev[itemId] || { receivedQuantity: '', productionDate: todayStr() }), ...patch },
    }));
  };

  // ============================================================
  // Issue #794: 拍照附件
  // ============================================================
  const guessExt = (uri: string, mime?: string): string => {
    if (mime?.startsWith('image/')) return mime.replace('image/', '');
    const dot = uri.lastIndexOf('.');
    return dot > 0 ? uri.substring(dot + 1) : 'jpg';
  };

  const addPhotoFromAsset = useCallback(async (asset: ImagePicker.ImagePickerAsset) => {
    let size = asset.fileSize ?? 0;
    if (!size) {
      const info = await FileSystem.getInfoAsync(asset.uri, { size: true });
      size = info.exists && 'size' in info ? (info.size as number) : 0;
    }
    const fileName = asset.fileName
      ?? `receipt_${Date.now()}.${guessExt(asset.uri, asset.mimeType)}`;
    const mimeType = asset.mimeType ?? 'image/jpeg';
    setPhotos((prev) => [...prev, { uri: asset.uri, fileName, mimeType, size }]);
  }, []);

  const takePhoto = useCallback(async () => {
    const perm = await ImagePicker.requestCameraPermissionsAsync();
    if (!perm.granted) {
      Alert.alert('需要相机权限', '请在设置中开启相机权限以拍照存档');
      return;
    }
    const result = await ImagePicker.launchCameraAsync({
      mediaTypes: ImagePicker.MediaTypeOptions.Images,
      quality: 0.7,
    });
    if (!result.canceled && result.assets[0]) {
      await addPhotoFromAsset(result.assets[0]);
    }
  }, [addPhotoFromAsset]);

  const pickPhotoFromGallery = useCallback(async () => {
    const perm = await ImagePicker.requestMediaLibraryPermissionsAsync();
    if (!perm.granted) {
      Alert.alert('需要相册权限');
      return;
    }
    const result = await ImagePicker.launchImageLibraryAsync({
      mediaTypes: ImagePicker.MediaTypeOptions.Images,
      quality: 0.7,
      allowsMultipleSelection: true,
    });
    if (!result.canceled) {
      for (const a of result.assets) {
        await addPhotoFromAsset(a);
      }
    }
  }, [addPhotoFromAsset]);

  const removePhoto = (idx: number) => {
    setPhotos((prev) => prev.filter((_, i) => i !== idx));
  };

  // ============================================================
  // 提交
  // ============================================================
  const handleSubmit = useCallback(async () => {
    if (!order || !order.items) return;

    // 校验: 至少 1 行有 quantity > 0
    const validItems = order.items.filter((it) => {
      const q = Number(rows[it.id]?.receivedQuantity);
      return Number.isFinite(q) && q > 0;
    });
    if (validItems.length === 0) {
      Alert.alert('提示', '请至少填写一行收货数量');
      return;
    }

    // 校验: 所有有数量的行必须有商品日期
    for (const it of validItems) {
      const d = rows[it.id]?.productionDate;
      if (!d || !/^\d{4}-\d{2}-\d{2}$/.test(d)) {
        Alert.alert('提示', `行 "${it.materialTypeName || it.materialTypeId}" 商品日期格式不对, 应为 YYYY-MM-DD`);
        return;
      }
    }

    setSubmitting(true);
    try {
      // 1. createReceive (草稿)
      const receivePayload: CreateReceiveRecordRequest = {
        purchaseOrderId: order.id,
        supplierId: order.supplierId,
        receiveDate: todayStr(),
        remark: orderNumber ? `扫码入库: ${orderNumber}` : '扫码入库',
        items: validItems.map((it) => {
          const row = rows[it.id];
          if (!row) throw new Error(`收货行缺失: ${it.materialTypeName || it.materialTypeId}`);
          return {
            materialTypeId: it.materialTypeId,
            materialName: it.materialTypeName,
            receivedQuantity: Number(row.receivedQuantity),
            unit: unitFor(it),
            unitPrice: it.unitPrice,
          };
        }),
      };
      const createRes = await purchaseApiClient.createReceive(receivePayload, factoryId);
      const receive: PurchaseReceiveRecord = createRes.data;
      if (!receive?.id) throw new Error('createReceive 返回缺 id');

      // 2. confirmReceive (触发 material_batches 创建)
      const confirmRes = await purchaseApiClient.confirmReceive(receive.id, factoryId);
      const confirmed: PurchaseReceiveRecord = confirmRes.data;

      // SP4: 写入厂号/产地 (非必填, 有值时 patch 每个批次)
      if (factoryNumber.trim() || originPlace.trim()) {
        const batchMetaUpdate = {
          ...(factoryNumber.trim() ? { factoryNumber: factoryNumber.trim() } : {}),
          ...(originPlace.trim() ? { originPlace: originPlace.trim() } : {}),
        };
        for (const item of (confirmed.items || [])) {
          if (item.batchNumber) {
            try {
              // 先按 batchNumber 查出批次 UUID, 再 patch
              const listRes = await materialBatchApiClient.getMaterialBatches({ batchNumber: item.batchNumber, factoryId });
              const batchId = listRes?.data?.content?.[0]?.id;
              if (batchId) {
                await materialBatchApiClient.updateBatch(batchId, batchMetaUpdate, factoryId);
              }
            } catch {
              // 非阻塞: 厂号/产地写入失败不影响入库主流程
            }
          }
        }
      }

      // 3. 对每个抄码品行 → POST /material/abaca-log (用 batchNumber 自动 lookup batchId)
      const abacaItems = validItems.filter((it) => isAbacaItem(it));
      const confirmedItemsByMaterial = new Map<string, PurchaseReceiveItem>();
      (confirmed.items || []).forEach((ri) => confirmedItemsByMaterial.set(ri.materialTypeId, ri));

      let abacaLogged = 0;
      const abacaErrors: string[] = [];
      for (const it of abacaItems) {
        const confirmedItem = confirmedItemsByMaterial.get(it.materialTypeId);
        if (!confirmedItem?.batchNumber) {
          abacaErrors.push(`${it.materialTypeName || it.materialTypeId}: 确认后未拿到 batchNumber`);
          continue;
        }
        try {
          const row = rows[it.id];
          if (!row) throw new Error(`收货行缺失: ${it.materialTypeName || it.materialTypeId}`);
          await abacaApiClient.create(
            {
              batchNumber: confirmedItem.batchNumber,
              rawMaterialTypeId: it.materialTypeId,
              actualWeight: Number(row.receivedQuantity),
              unit: unitFor(it),
              weighingMethod: 'MANUAL',
              purchaseOrderItemId: it.id,
              notes: `扫码入库自动落账; 商品日期 ${row.productionDate}`,
            },
            factoryId,
          );
          abacaLogged += 1;
        } catch (err: unknown) {
          const msg = err instanceof Error ? err.message : '抄码日志失败';
          abacaErrors.push(`${it.materialTypeName || it.materialTypeId}: ${msg}`);
        }
      }

      // 4. Issue #794: 上传所有 stash 的照片到 PURCHASE_RECEIPT/{receive.id}
      let photosUploaded = 0;
      const photoErrors: string[] = [];
      for (const p of photos) {
        try {
          await attachmentApi.uploadAndRegister(
            { uri: p.uri, name: p.fileName, type: p.mimeType, size: p.size },
            'PURCHASE_RECEIPT',
            receive.id,
            { businessTag: 'RECEIVE_PHOTO', fileCategory: 'PHOTO' },
            factoryId,
          );
          photosUploaded += 1;
        } catch (err: unknown) {
          const msg = err instanceof Error ? err.message : '照片上传失败';
          photoErrors.push(`${p.fileName}: ${msg}`);
        }
      }

      const summary = [
        `入库单 ${confirmed.receiveNumber || receive.id} 创建并确认成功`,
        `共 ${validItems.length} 行`,
        abacaItems.length > 0
          ? `抄码品自动落称重日志: ${abacaLogged}/${abacaItems.length}`
          : null,
        photos.length > 0
          ? `照片上传: ${photosUploaded}/${photos.length}`
          : null,
      ]
        .filter(Boolean)
        .join('\n');
      const errLines: string[] = [];
      if (abacaErrors.length > 0) {
        errLines.push(`抄码日志失败 (可手工补录):\n- ${abacaErrors.join('\n- ')}`);
      }
      if (photoErrors.length > 0) {
        errLines.push(`照片上传失败:\n- ${photoErrors.join('\n- ')}`);
      }
      const finalMsg = errLines.length > 0 ? `${summary}\n\n${errLines.join('\n\n')}` : summary;

      Alert.alert('入库成功', finalMsg, [
        { text: '确定', onPress: () => navigation.goBack() },
      ]);
    } catch (err) {
      handleError(err, { title: '入库提交失败' });
    } finally {
      setSubmitting(false);
    }
  }, [order, rows, orderNumber, factoryId, navigation, unitFor, isAbacaItem, photos, factoryNumber, originPlace]);

  // ============================================================
  // Render
  // ============================================================
  const totalReceivedRows = useMemo(
    () =>
      Object.values(rows).filter((r) => {
        const q = Number(r.receivedQuantity);
        return Number.isFinite(q) && q > 0;
      }).length,
    [rows],
  );

  if (loading) {
    return (
      <SafeAreaView style={styles.container} edges={['top']}>
        <Appbar.Header>
          <Appbar.BackAction onPress={() => navigation.goBack()} />
          <Appbar.Content title="扫码入库录入" />
        </Appbar.Header>
        <View style={styles.center}>
          <ActivityIndicator size="large" />
          <Text style={styles.loadingText}>加载采购单中...</Text>
        </View>
      </SafeAreaView>
    );
  }

  if (!order) {
    return (
      <SafeAreaView style={styles.container} edges={['top']}>
        <Appbar.Header>
          <Appbar.BackAction onPress={() => navigation.goBack()} />
          <Appbar.Content title="扫码入库录入" />
        </Appbar.Header>
        <View style={styles.center}>
          <Text>采购单加载失败</Text>
        </View>
      </SafeAreaView>
    );
  }

  return (
    <KeyboardAvoidingView
      style={styles.flex}
      behavior={Platform.OS === 'ios' ? 'padding' : undefined}
    >
      <SafeAreaView style={styles.container} edges={['top']}>
        <Appbar.Header>
          <Appbar.BackAction onPress={() => navigation.goBack()} />
          <Appbar.Content
            title="扫码入库录入"
            subtitle={order.orderNumber ? `订单 ${order.orderNumber}` : ''}
          />
        </Appbar.Header>

        <ScrollView style={styles.scroll} contentContainerStyle={styles.scrollContent}>
          {/* 订单信息卡片 (全部只读, prefill) */}
          <Card style={styles.card}>
            <Card.Content>
              <Text style={styles.sectionTitle}>订单信息 (已 prefill)</Text>
              <View style={styles.kv}>
                <Text style={styles.kvLabel}>订单号</Text>
                <Text style={styles.kvValue}>{order.orderNumber}</Text>
              </View>
              <View style={styles.kv}>
                <Text style={styles.kvLabel}>供应商</Text>
                <Text style={styles.kvValue}>{order.supplierName || order.supplierId}</Text>
              </View>
              <View style={styles.kv}>
                <Text style={styles.kvLabel}>下单日期</Text>
                <Text style={styles.kvValue}>{order.orderDate}</Text>
              </View>
              {order.expectedDeliveryDate && (
                <View style={styles.kv}>
                  <Text style={styles.kvLabel}>期望交货</Text>
                  <Text style={styles.kvValue}>{order.expectedDeliveryDate}</Text>
                </View>
              )}
              <View style={styles.kv}>
                <Text style={styles.kvLabel}>明细行数</Text>
                <Text style={styles.kvValue}>{order.items?.length || 0} 行</Text>
              </View>
            </Card.Content>
          </Card>

          {/* 收货明细 — 只要 2 字段必填 */}
          <Text style={styles.outerHeader}>收货明细 (仅录数量 + 日期)</Text>
          <Text style={styles.deBlameHint}>按实际到货填写即可，差异由采购处理</Text>
          {(order.items || []).map((it, idx) => {
            const abaca = isAbacaItem(it);
            const m = materialsById[it.materialTypeId];
            const row = rows[it.id] || { receivedQuantity: '', productionDate: todayStr() };
            return (
              <Card key={it.id} style={styles.itemCard}>
                <Card.Content>
                  <View style={styles.itemHeaderRow}>
                    <Text style={styles.itemIndex}>第 {idx + 1} 行</Text>
                    {abaca && (
                      <Chip
                        compact
                        style={styles.abacaChip}
                        textStyle={styles.abacaChipText}
                      >
                        🥩 抄码品
                      </Chip>
                    )}
                  </View>

                  <View style={styles.kv}>
                    <Text style={styles.kvLabel}>原料</Text>
                    <Text style={styles.kvValue}>
                      {it.materialTypeName || m?.name || it.materialTypeId}
                    </Text>
                  </View>
                  <View style={styles.kv}>
                    <Text style={styles.kvLabel}>订单数量</Text>
                    <Text style={styles.kvValue}>
                      {it.quantity} {it.unit}
                      {it.receivedQuantity > 0 ? `  (已收 ${it.receivedQuantity})` : ''}
                    </Text>
                  </View>

                  {abaca && (
                    <View style={styles.abacaBanner}>
                      <Text style={styles.abacaBannerText}>
                        本品按实际称重 {m?.abacaUnitPerBox ? `(${m.abacaUnitPerBox})` : ''} —
                        提交后自动写入称重日志 (boxIndex 自动分配, 可在抄码品详情页继续 append)
                      </Text>
                    </View>
                  )}

                  <View style={styles.fieldsRow}>
                    <TextInput
                      label={abaca ? `估算重量 (${unitFor(it)}) *` : `数量 (${unitFor(it)}) *`}
                      value={row.receivedQuantity}
                      onChangeText={(t) =>
                        updateRow(it.id, { receivedQuantity: t.replace(/[^0-9.]/g, '') })
                      }
                      mode="outlined"
                      keyboardType="numeric"
                      style={[styles.field, styles.flex1]}
                      contentStyle={{ fontSize: 24 }}
                      placeholder={abaca ? '入库以实际称重为准' : ''}
                    />
                    <TextInput
                      label="商品日期 *"
                      value={row.productionDate}
                      onChangeText={(t) => updateRow(it.id, { productionDate: t })}
                      mode="outlined"
                      placeholder="YYYY-MM-DD"
                      style={[styles.field, styles.flex1]}
                    />
                  </View>
                </Card.Content>
              </Card>
            );
          })}

          {/* SP4: 可选录入厂号/产地 — 填写后提交时写入每个批次 */}
          <Card style={styles.card}>
            <Card.Content>
              <Text style={styles.sectionTitle}>厂号 / 产地（可选）</Text>
              <Text style={styles.helperText}>
                如需追溯来源，可在此录入。留空则跳过。
              </Text>
              <TextInput
                label="厂号"
                value={factoryNumber}
                onChangeText={setFactoryNumber}
                mode="outlined"
                style={styles.field}
                placeholder="如: S-2024-008"
                disabled={submitting}
              />
              <TextInput
                label="产地"
                value={originPlace}
                onChangeText={setOriginPlace}
                mode="outlined"
                style={[styles.field, { marginTop: 8 }]}
                placeholder="如: 山东章丘"
                disabled={submitting}
              />
            </Card.Content>
          </Card>

          {/* Issue #794: 收货拍照附件 — 提交后随入库单一起上传 */}
          <Card style={styles.card}>
            <Card.Content>
              <Text style={styles.sectionTitle}>
                收货照片 ({photos.length})
              </Text>
              <Text style={styles.helperText}>
                拍照留单, 提交后随入库单一起保存。可点缩略图删除。
              </Text>

              {photos.length > 0 && (
                <View style={styles.photoGrid}>
                  {photos.map((p, idx) => (
                    <View key={`${p.uri}-${idx}`} style={styles.photoThumbWrap}>
                      <Image source={{ uri: p.uri }} style={styles.photoThumb} />
                      <TouchableOpacity
                        style={styles.photoDeleteBtn}
                        onPress={() => removePhoto(idx)}
                        accessibilityLabel="删除此照片"
                      >
                        <Text style={styles.photoDeleteIcon}>×</Text>
                      </TouchableOpacity>
                    </View>
                  ))}
                </View>
              )}

              <View style={styles.photoButtonRow}>
                <Button
                  mode="outlined"
                  icon="camera"
                  onPress={takePhoto}
                  disabled={submitting}
                  style={styles.photoButton}
                  compact
                >
                  拍照
                </Button>
                <Button
                  mode="outlined"
                  icon="image-multiple"
                  onPress={pickPhotoFromGallery}
                  disabled={submitting}
                  style={styles.photoButton}
                  compact
                >
                  从相册
                </Button>
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
            disabled={submitting || totalReceivedRows === 0}
            icon="check"
          >
            提交入库 ({totalReceivedRows} 行)
          </Button>
        </View>
      </SafeAreaView>
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  container: { flex: 1, backgroundColor: '#f5f7fa' },
  scroll: { flex: 1 },
  scrollContent: { padding: 12 },
  center: { flex: 1, justifyContent: 'center', alignItems: 'center', gap: 12 },
  loadingText: { fontSize: 14, color: '#666' },

  card: { marginBottom: 12, borderRadius: 8 },
  sectionTitle: { fontSize: 15, fontWeight: '600', marginBottom: 8, color: '#2d3748' },
  outerHeader: { fontSize: 15, fontWeight: '600', marginBottom: 8, marginTop: 4, color: '#2d3748' },

  itemCard: { marginBottom: 12, borderRadius: 8 },
  itemHeaderRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: 6,
  },
  itemIndex: { fontSize: 13, color: '#666', fontWeight: '500' },
  abacaChip: { backgroundColor: '#fef3c7' },
  abacaChipText: { color: '#92400e', fontSize: 12 },

  kv: { flexDirection: 'row', paddingVertical: 4 },
  kvLabel: { width: 80, fontSize: 13, color: '#718096' },
  kvValue: { flex: 1, fontSize: 13, color: '#2d3748' },

  abacaBanner: {
    backgroundColor: '#fef3c7',
    borderLeftWidth: 3,
    borderLeftColor: '#f59e0b',
    paddingVertical: 6,
    paddingHorizontal: 10,
    marginTop: 8,
    marginBottom: 8,
    borderRadius: 4,
  },
  abacaBannerText: { fontSize: 12, color: '#92400e', lineHeight: 18 },

  fieldsRow: { flexDirection: 'row', gap: 8, marginTop: 6 },
  field: { backgroundColor: 'transparent' },
  flex1: { flex: 1 },

  helperText: { fontSize: 12, color: '#718096', lineHeight: 18, marginBottom: 8 },
  deBlameHint: { fontSize: 12, color: '#718096', lineHeight: 18, marginBottom: 10, fontStyle: 'italic' },

  // Issue #794: 拍照附件样式
  photoGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
    marginBottom: 8,
  },
  photoThumbWrap: {
    position: 'relative',
    width: 72,
    height: 72,
  },
  photoThumb: {
    width: 72,
    height: 72,
    borderRadius: 6,
    backgroundColor: '#f4f4f5',
  },
  photoDeleteBtn: {
    position: 'absolute',
    top: -6,
    right: -6,
    width: 22,
    height: 22,
    borderRadius: 11,
    backgroundColor: 'rgba(0,0,0,0.65)',
    alignItems: 'center',
    justifyContent: 'center',
  },
  photoDeleteIcon: {
    color: '#fff',
    fontSize: 16,
    lineHeight: 18,
    fontWeight: '600',
  },
  photoButtonRow: {
    flexDirection: 'row',
    gap: 8,
  },
  photoButton: {
    flex: 1,
  },

  bottomSpacer: { height: 24 },

  footer: {
    padding: 12,
    backgroundColor: '#fff',
    borderTopWidth: 1,
    borderTopColor: '#e0e0e0',
  },
});
