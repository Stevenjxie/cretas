import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Alert, Image, Linking, RefreshControl, ScrollView, StyleSheet, TouchableOpacity, View } from 'react-native';
import {
  ActivityIndicator,
  Appbar,
  Button,
  Card,
  Chip,
  Dialog,
  HelperText,
  IconButton,
  Portal,
  RadioButton,
  Text,
  TextInput,
} from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { RouteProp, useFocusEffect, useNavigation, useRoute } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';

import { FAManagementStackParamList, WHInboundStackParamList } from '../../../types/navigation';
import { restaurantApiClient } from '../../../services/api/restaurantApiClient';
import type {
  PriceAnomalyApprovalStatus,
  RejectSupplierDeliveryRequest,
  SupplierDeliveryLine,
  SupplierDeliveryNote,
} from '../../../types/restaurant';
import { materialTypeApiClient, MaterialType } from '../../../services/api/materialTypeApiClient';
import { useAuthStore } from '../../../store/authStore';
import { handleError } from '../../../utils/errorHandler';
import { roleCanViewPrice } from '../../../config/rowActionsConfig';

type DeliveryRoutes = FAManagementStackParamList & WHInboundStackParamList;
type Nav = NativeStackNavigationProp<DeliveryRoutes, 'SupplierDeliveryDetail'>;
type DetailRoute = RouteProp<DeliveryRoutes, 'SupplierDeliveryDetail'>;
type RejectReason = RejectSupplierDeliveryRequest['rejectReasonCode'];

interface EditableLine {
  key: string;
  id?: number;
  ingredientName: string;
  rawMaterialTypeId: string;
  materialSearch: string;
  quantity: string;
  unit: string;
  unitPrice: string;
  qcResult: string;
  remark: string;
  showManualMaterialId: boolean;
  priceAnomalyFlag?: boolean;
  priceAnomalyReasonCode: string;
  priceAnomalyExplanation: string;
  baselineUnitPrice?: number | null;
  priceVarianceRate?: number | null;
}

const ANOMALY_REASONS: Array<{ value: string; label: string }> = [
  { value: 'MARKET_PRICE_UP', label: '市场涨价' },
  { value: 'SPEC_UPGRADE', label: '规格升级' },
  { value: 'SUBSTITUTE_SHORTAGE', label: '临时缺货换货' },
  { value: 'SUPPLIER_EXPLAINED', label: '供应商补充说明' },
  { value: 'OTHER', label: '其他' },
];

const STATUS_LABEL: Record<string, string> = {
  DRAFT: '待验收',
  CONFIRMED: '已入库',
  REJECTED: '已拒绝',
};

const POSTING_LABEL: Record<string, string> = {
  UNPOSTED: '未过账',
  POSTING: '过账中',
  POSTED: '已生成库存',
  FAILED: '过账失败',
};

const APPROVAL_LABEL: Record<PriceAnomalyApprovalStatus, string> = {
  NONE: '未提交审批',
  PENDING: '等待老板审批',
  APPROVED: '老板已批准',
  REJECTED: '老板已驳回',
};

const BOSS_ROLES = new Set(['factory_super_admin', 'restaurant_manager', 'platform_admin']);

const REJECT_REASONS: Array<{ value: RejectReason; label: string; hint: string }> = [
  { value: 'WRONG_DOCUMENT', label: '不是本店送货单', hint: '单据拿错或供应商送错门店' },
  { value: 'SUPPLIER_NOT_FOUND', label: '供应商未匹配', hint: '先维护供应商主数据' },
  { value: 'IMAGE_BLUR', label: '单据看不清', hint: '请供应商重拍或重开单' },
  { value: 'LOW_LIGHT', label: '照片太暗', hint: '重新拍照后再录入' },
  { value: 'OTHER', label: '其它原因', hint: '需要写明拒绝原因' },
];

const QC_OPTIONS = ['PASS', 'PENDING', 'FAIL'];
const QC_LABEL: Record<string, string> = {
  PASS: '合格',
  PENDING: '待复核',
  FAIL: '不合格',
};

export function SupplierDeliveryDetailScreen() {
  const navigation = useNavigation<Nav>();
  const route = useRoute<DetailRoute>();
  const { user } = useAuthStore();
  const factoryId = user?.factoryId;
  const roleCode = user?.userType === 'platform' ? user.platformUser?.role : user?.factoryUser?.role;
  const canViewAmounts = roleCanViewPrice(roleCode);
  const { noteId } = route.params;

  const [note, setNote] = useState<SupplierDeliveryNote | null>(null);
  const [materials, setMaterials] = useState<MaterialType[]>([]);
  const [draftLines, setDraftLines] = useState<EditableLine[]>([]);
  const [editing, setEditing] = useState(false);
  const [loading, setLoading] = useState(false);
  const [savingLines, setSavingLines] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [rejecting, setRejecting] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [rejectDialogVisible, setRejectDialogVisible] = useState(false);
  const [rejectReason, setRejectReason] = useState<RejectReason>('WRONG_DOCUMENT');
  const [rejectNote, setRejectNote] = useState('');
  const [submittingApproval, setSubmittingApproval] = useState(false);
  const [stickyError, setStickyError] = useState<string | null>(null);
  const [stickyHint, setStickyHint] = useState<string | null>(null);

  const isDraft = note?.status === 'DRAFT';
  const canBossApprove = BOSS_ROLES.has(roleCode || '');
  const priceAnomalyLines = useMemo(
    () => (note?.lines || []).filter((line) => line.priceAnomalyFlag),
    [note?.lines],
  );
  const hasPriceAnomalies = priceAnomalyLines.length > 0;
  const unexplainedAnomalies = useMemo(
    () => priceAnomalyLines.filter((line) => !String(line.priceAnomalyExplanation || '').trim()),
    [priceAnomalyLines],
  );
  const approvalStatus = note?.priceAnomalyApprovalStatus || 'NONE';
  const canSubmitApproval = isDraft
    && hasPriceAnomalies
    && unexplainedAnomalies.length === 0
    && (approvalStatus === 'NONE' || approvalStatus === 'REJECTED');

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await restaurantApiClient.getSupplierDeliveryNote(noteId);
      setNote(data);
      setDraftLines(toEditableLines(data.lines || []));
    } catch (error) {
      handleError(error, { title: '送货单加载失败' });
    } finally {
      setLoading(false);
    }
  }, [noteId]);

  useEffect(() => {
    let alive = true;
    (async () => {
      try {
        const materialList = await materialTypeApiClient.getActiveMaterialTypes(factoryId);
        if (alive) setMaterials(materialList?.data || []);
      } catch (error) {
        handleError(error, { title: '食材主数据加载失败', showAlert: false });
      }
    })();
    return () => {
      alive = false;
    };
  }, [factoryId]);

  useFocusEffect(useCallback(() => { void load(); }, [load]));

  const totalAmount = useMemo(() => {
    const source = editing ? draftLines : toEditableLines(note?.lines || []);
    return source.reduce((sum, line) => {
      const qty = Number(line.quantity);
      const price = Number(line.unitPrice);
      return Number.isFinite(qty) && Number.isFinite(price) ? sum + qty * price : sum;
    }, 0);
  }, [draftLines, editing, note?.lines]);

  const updateLine = (key: string, patch: Partial<EditableLine>) => {
    setDraftLines((curr) => curr.map((line) => (line.key === key ? { ...line, ...patch } : line)));
  };

  const addLine = () => {
    setDraftLines((curr) => [
      ...curr,
      {
        key: `new-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
        ingredientName: '',
        rawMaterialTypeId: '',
        materialSearch: '',
        quantity: '',
        unit: 'kg',
        unitPrice: '',
        qcResult: 'PASS',
        remark: '',
        showManualMaterialId: false,
        priceAnomalyFlag: false,
        priceAnomalyReasonCode: '',
        priceAnomalyExplanation: '',
      },
    ]);
  };

  const removeLine = (key: string) => {
    setDraftLines((curr) => (curr.length === 1 ? curr : curr.filter((line) => line.key !== key)));
  };

  const filteredMaterials = (line: EditableLine): MaterialType[] => {
    const query = line.materialSearch.trim().toLowerCase();
    return materials
      .filter((material) => {
        if (!query) return true;
        return [material.name, material.code, material.category]
          .some((value) => (value || '').toLowerCase().includes(query));
      })
      .slice(0, 6);
  };

  const selectMaterial = (line: EditableLine, material: MaterialType) => {
    updateLine(line.key, {
      ingredientName: material.name,
      rawMaterialTypeId: material.id,
      materialSearch: `${material.name}${material.code ? ` (${material.code})` : ''}`,
      unit: line.unit || material.unit || 'kg',
      unitPrice: canViewAmounts ? line.unitPrice || (material.unitPrice == null ? '' : String(material.unitPrice)) : '',
      showManualMaterialId: false,
    });
  };

  const beginEdit = () => {
    if (!note) return;
    setDraftLines(toEditableLines(note.lines || []));
    setEditing(true);
  };

  const validateLines = (): string | null => {
    if (draftLines.length === 0) return '至少保留一行食材明细。';
    for (const [index, line] of draftLines.entries()) {
      const lineName = `第 ${index + 1} 行`;
      const qty = Number(line.quantity);
      const price = line.unitPrice.trim() ? Number(line.unitPrice) : undefined;
      if (!line.ingredientName.trim()) return `${lineName} 请选择或填写食材名称。`;
      if (!line.rawMaterialTypeId.trim()) return `${lineName} 需要匹配食材主数据。`;
      if (!Number.isFinite(qty) || qty <= 0) return `${lineName} 的验收数量必须大于 0。`;
      if (!line.unit.trim()) return `${lineName} 需要填写单位。`;
      if (price !== undefined && (!Number.isFinite(price) || price < 0)) return `${lineName} 的单价不能为负数。`;
      if (line.qcResult === 'FAIL' && !line.remark.trim()) return `${lineName} 不合格时请写明问题。`;
      if (line.priceAnomalyFlag) {
        if (!line.priceAnomalyReasonCode.trim()) return `${lineName} 价格异常需选择涨价原因。`;
        if (!line.priceAnomalyExplanation.trim()) return `${lineName} 价格异常需填写现场解释。`;
      }
    }
    return null;
  };

  const saveLines = async () => {
    const error = validateLines();
    if (error) {
      Alert.alert('明细还不能保存', error);
      return;
    }
    setSavingLines(true);
    try {
      const updated = await restaurantApiClient.updateSupplierDeliveryLines(noteId, draftLines.map(toLinePayload));
      setNote(updated);
      setDraftLines(toEditableLines(updated.lines || []));
      setEditing(false);
      Alert.alert('已保存', '送货明细已更新，可以继续确认入库。');
    } catch (error) {
      handleError(error, { title: '保存明细失败' });
    } finally {
      setSavingLines(false);
    }
  };

  const confirm = () => {
    if (!note) return;
    if (editing) {
      Alert.alert('请先保存明细', '当前明细有未保存修改，保存后再确认入库。');
      return;
    }
    const lines = (note.lines || []).map(formatLine).join('\n');
    Alert.alert(
      '确认验收入库',
      `送货单：${note.noteNumber || note.id}\n供应商：${note.supplierName || note.supplierId || '未绑定'}\n日期：${note.deliveryDate}\n将生成 ${(note.lines || []).length} 个库存批次：\n${lines}\n仓库：${note.warehouseId || '默认餐饮仓库'}`,
      [
        { text: '再核对', style: 'cancel' },
        { text: '确认入库', onPress: () => { void doConfirm(); } },
      ],
    );
  };

  const extractApiHint = (error: unknown): { message: string; hint?: string } => {
    const payload = (error as { response?: { data?: Record<string, unknown> } })?.response?.data;
    const message = String(payload?.message || payload?.errorMessage || getErrorText(error));
    const hint = String(payload?.actionHint || payload?.hint || '').trim() || undefined;
    return { message, hint };
  };

  const getErrorText = (error: unknown): string => {
    if (error instanceof Error) return error.message;
    return '操作失败，请稍后重试。';
  };

  const doConfirm = async () => {
    setStickyError(null);
    setStickyHint(null);
    setConfirming(true);
    try {
      const updated = await restaurantApiClient.confirmSupplierDelivery(noteId);
      setNote(updated);
      setDraftLines(toEditableLines(updated.lines || []));
      Alert.alert('验收入库成功', '已生成真实库存批次，后续领料/损耗可扣这批库存。');
    } catch (error) {
      const { message, hint } = extractApiHint(error);
      setStickyError(message);
      setStickyHint(hint || (hasPriceAnomalies && approvalStatus !== 'APPROVED' ? '请先提交老板审批，待批准后再确认入库。' : undefined) || null);
      handleError(error, { title: '验收入库失败', customMessage: hint ? `${message}\n${hint}` : message });
      void load();
    } finally {
      setConfirming(false);
    }
  };

  const submitApproval = () => {
    if (!canSubmitApproval) {
      Alert.alert('还不能提交审批', unexplainedAnomalies.length > 0
        ? '请先在行项中填写涨价解释，再提交老板审批。'
        : '当前状态不需要重复提交。');
      return;
    }
    Alert.alert(
      '提交老板审批',
      `送货单 ${note?.noteNumber || noteId} 有 ${priceAnomalyLines.length} 行进价异常，提交后需老板/店长批准才能入库。`,
      [
        { text: '取消', style: 'cancel' },
        { text: '提交审批', onPress: () => { void doSubmitApproval(); } },
      ],
    );
  };

  const doSubmitApproval = async () => {
    setSubmittingApproval(true);
    setStickyError(null);
    setStickyHint(null);
    try {
      const updated = await restaurantApiClient.submitPriceAnomalyApproval(noteId);
      setNote(updated);
      setDraftLines(toEditableLines(updated.lines || []));
      Alert.alert('已提交审批', '已通知老板/店长，请等待批准后再确认入库。');
    } catch (error) {
      const { message, hint } = extractApiHint(error);
      setStickyError(message);
      setStickyHint(hint || null);
      handleError(error, { title: '提交审批失败', customMessage: hint ? `${message}\n${hint}` : message });
    } finally {
      setSubmittingApproval(false);
    }
  };

  const submitReject = async () => {
    if (rejectReason === 'OTHER' && !rejectNote.trim()) {
      Alert.alert('请写明原因', '选择其它原因时，需要写一句拒绝说明。');
      return;
    }
    setRejecting(true);
    try {
      const reasonLabel = REJECT_REASONS.find((reason) => reason.value === rejectReason)?.label;
      const updated = await restaurantApiClient.rejectSupplierDelivery(noteId, {
        rejectReasonCode: rejectReason,
        rejectReasonNote: rejectNote.trim() || reasonLabel,
      });
      setNote(updated);
      setEditing(false);
      setRejectDialogVisible(false);
      Alert.alert('已拒绝', '该送货单已标记为拒绝，不会生成库存批次。');
    } catch (error) {
      handleError(error, { title: '拒绝送货单失败' });
    } finally {
      setRejecting(false);
    }
  };

  const deleteDraft = () => {
    if (!note) return;
    Alert.alert(
      '删除草稿',
      `将删除送货单 ${note.noteNumber || note.id}，不会影响库存。这个操作只允许待验收草稿执行。`,
      [
        { text: '取消', style: 'cancel' },
        {
          text: '删除',
          style: 'destructive',
          onPress: () => { void doDeleteDraft(); },
        },
      ],
    );
  };

  const doDeleteDraft = async () => {
    setDeleting(true);
    try {
      await restaurantApiClient.deleteSupplierDelivery(noteId);
      Alert.alert('已删除', '送货单草稿已删除。', [
        { text: '返回列表', onPress: () => navigation.goBack() },
      ]);
    } catch (error) {
      handleError(error, { title: '删除草稿失败' });
    } finally {
      setDeleting(false);
    }
  };

  const renderLineView = (line: SupplierDeliveryLine, index: number) => (
    <Card
      key={line.id || `${line.rawMaterialTypeId}-${index}`}
      style={[styles.card, line.priceAnomalyFlag ? styles.anomalyCard : null]}
    >
      <Card.Content>
        <View style={styles.titleRow}>
          <Text style={styles.lineName}>{line.ingredientName}</Text>
          {line.priceAnomalyFlag ? <Chip compact textStyle={styles.chipText}>价格异常</Chip> : null}
        </View>
        <Text style={styles.meta}>食材：{line.rawMaterialTypeId || '未匹配'}</Text>
        <Text style={styles.meta}>数量：{line.quantity ?? '-'} {line.unit || ''}</Text>
        <Text style={styles.meta}>单价：{formatAmount(line.unitPrice, canViewAmounts, '未填')}</Text>
        {line.priceAnomalyFlag ? (
          <>
            <Text style={styles.meta}>历史基线：{formatAmount(line.baselineUnitPrice, canViewAmounts, '—')}</Text>
            <Text style={styles.anomaly}>涨幅：{formatVariance(line.priceVarianceRate)}</Text>
            <Text style={styles.meta}>涨价解释：{line.priceAnomalyExplanation || '未填写'}</Text>
          </>
        ) : null}
        <Text style={styles.meta}>行金额：{formatAmount(line.lineAmount ?? calcLineAmount(line), canViewAmounts, '未计算')}</Text>
        <Text style={styles.meta}>质检：{QC_LABEL[line.qcResult || ''] || line.qcResult || '-'}</Text>
        {line.remark ? <Text style={styles.meta}>备注：{line.remark}</Text> : null}
        {line.materialBatchId ? <Text style={styles.batch}>库存批次：{line.materialBatchId}</Text> : null}
      </Card.Content>
    </Card>
  );

  const renderLineEditor = (line: EditableLine, index: number) => (
    <Card key={line.key} style={styles.card}>
      <Card.Content>
        <View style={styles.lineHeader}>
          <Text style={styles.lineName}>第 {index + 1} 行</Text>
          <IconButton icon="close" size={20} disabled={draftLines.length === 1} onPress={() => removeLine(line.key)} />
        </View>

        <TextInput
          label="食材"
          mode="outlined"
          value={line.materialSearch}
          onChangeText={(value) => updateLine(line.key, {
            materialSearch: value,
            ingredientName: value,
            rawMaterialTypeId: '',
          })}
          style={styles.field}
        />
        <View style={styles.optionList}>
          {filteredMaterials(line).map((material) => (
            <TouchableOpacity key={material.id} activeOpacity={0.84} onPress={() => selectMaterial(line, material)}>
              <View style={[styles.optionCard, line.rawMaterialTypeId === material.id && styles.optionCardSelected]}>
                <Text style={styles.optionTitle}>{material.name}</Text>
                <Text style={styles.optionMeta}>
                  {[material.code, material.category, material.unit].filter(Boolean).join(' · ') || '食材主数据'}
                </Text>
              </View>
            </TouchableOpacity>
          ))}
          {filteredMaterials(line).length === 0 ? (
            <View style={styles.emptyOption}>
              <Text style={styles.emptyOptionText}>没有匹配食材。建议先维护食材主数据；紧急验收可手动填写 ID。</Text>
              <Button compact mode="outlined" onPress={() => updateLine(line.key, { showManualMaterialId: true })}>
                手动填写食材 ID
              </Button>
            </View>
          ) : null}
        </View>
        {line.showManualMaterialId ? (
          <TextInput
            label="食材主数据 ID（兜底）"
            mode="outlined"
            value={line.rawMaterialTypeId}
            onChangeText={(value) => updateLine(line.key, { rawMaterialTypeId: value })}
            style={styles.field}
          />
        ) : null}

        <View style={styles.row}>
          <TextInput
            label="数量"
            mode="outlined"
            keyboardType="decimal-pad"
            value={line.quantity}
            onChangeText={(value) => updateLine(line.key, { quantity: value })}
            style={[styles.field, styles.flex]}
          />
          <TextInput
            label="单位"
            mode="outlined"
            value={line.unit}
            onChangeText={(value) => updateLine(line.key, { unit: value })}
            style={[styles.field, styles.flex]}
          />
        </View>
        {canViewAmounts ? (
          <TextInput
            label="单价（可空）"
            mode="outlined"
            keyboardType="decimal-pad"
            value={line.unitPrice}
            onChangeText={(value) => updateLine(line.key, { unitPrice: value })}
            style={styles.field}
          />
        ) : (
          <Text style={styles.maskedAmount}>单价：无权限</Text>
        )}
        <View style={styles.chipRow}>
          {QC_OPTIONS.map((qc) => (
            <Chip key={qc} selected={line.qcResult === qc} onPress={() => updateLine(line.key, { qcResult: qc })}>
              {QC_LABEL[qc]}
            </Chip>
          ))}
        </View>
        <TextInput
          label={line.qcResult === 'FAIL' ? '问题说明（必填）' : '备注'}
          mode="outlined"
          value={line.remark}
          onChangeText={(value) => updateLine(line.key, { remark: value })}
          style={styles.field}
        />
        {line.priceAnomalyFlag ? (
          <View style={styles.anomalyEditor}>
            <Text style={styles.anomalyEditorTitle}>进价异常处理（必填）</Text>
            {line.baselineUnitPrice != null ? (
              <Text style={styles.meta}>
                历史基线 {formatAmount(line.baselineUnitPrice, canViewAmounts, '—')}
                {line.priceVarianceRate != null ? ` · 涨幅 ${formatVariance(line.priceVarianceRate)}` : ''}
              </Text>
            ) : null}
            <Text style={styles.labelCompact}>涨价原因</Text>
            <View style={styles.chipRow}>
              {ANOMALY_REASONS.map((reason) => (
                <Chip
                  key={reason.value}
                  compact
                  selected={line.priceAnomalyReasonCode === reason.value}
                  onPress={() => updateLine(line.key, { priceAnomalyReasonCode: reason.value })}
                >
                  {reason.label}
                </Chip>
              ))}
            </View>
            <TextInput
              label="现场解释（必填）"
              mode="outlined"
              value={line.priceAnomalyExplanation}
              onChangeText={(value) => updateLine(line.key, { priceAnomalyExplanation: value })}
              multiline
              style={styles.field}
              placeholder="例如：供应商说明菜场今日涨价，仓管已现场询价确认。"
            />
          </View>
        ) : null}
        <HelperText type="info" visible>
          保存后再确认入库，避免按旧数量生成库存批次。
        </HelperText>
      </Card.Content>
    </Card>
  );

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      <Appbar.Header>
        <Appbar.BackAction onPress={() => navigation.goBack()} />
        <Appbar.Content title="送货单详情" subtitle={note ? STATUS_LABEL[note.status] || note.status : undefined} />
        {isDraft && !editing ? <Appbar.Action icon="pencil" onPress={beginEdit} /> : null}
      </Appbar.Header>

      <ScrollView refreshControl={<RefreshControl refreshing={loading} onRefresh={load} />} contentContainerStyle={styles.content}>
        {stickyError ? (
          <Card style={styles.stickyErrorCard}>
            <Card.Content>
              <Text style={styles.stickyErrorTitle}>还不能确认入库</Text>
              <Text style={styles.error}>{stickyError}</Text>
              {stickyHint ? <Text style={styles.stickyHint}>{stickyHint}</Text> : null}
              {canSubmitApproval ? (
                <Button mode="contained" style={styles.stickyAction} loading={submittingApproval} onPress={submitApproval}>
                  提交老板审批
                </Button>
              ) : null}
              {approvalStatus === 'PENDING' && canBossApprove ? (
                <Button mode="outlined" style={styles.stickyAction} onPress={() => navigation.navigate('PriceAnomalyApproval')}>
                  去审批待办
                </Button>
              ) : null}
            </Card.Content>
          </Card>
        ) : null}

        {!note ? (
          <View style={styles.center}>
            <ActivityIndicator />
            <Text style={styles.loadingText}>正在加载送货单...</Text>
          </View>
        ) : (
          <>
            <Card style={styles.card}>
              <Card.Content>
                <View style={styles.titleRow}>
                  <Text style={styles.title}>{note.noteNumber || note.id}</Text>
                  <Chip compact>{POSTING_LABEL[note.postingStatus || 'UNPOSTED'] || note.postingStatus || '未过账'}</Chip>
                </View>
                <Text style={styles.meta}>供应商：{note.supplierName || note.supplierId || '未绑定'}</Text>
                <Text style={styles.meta}>送货日期：{note.deliveryDate}</Text>
                <Text style={styles.meta}>单据状态：{STATUS_LABEL[note.status] || note.status}</Text>
                <Text style={styles.meta}>过账状态：{POSTING_LABEL[note.postingStatus || 'UNPOSTED'] || note.postingStatus || '未过账'}</Text>
                <Text style={styles.meta}>食材行数：{(note.lines || []).length}</Text>
                <Text style={styles.meta}>预估金额：{formatAmount(note.totalAmount ?? totalAmount, canViewAmounts, '未计算')}</Text>
                {note.receiveRecordId ? <Text style={styles.batch}>入库单：{note.receiveRecordId}</Text> : null}
                {note.postedAt ? <Text style={styles.batch}>过账时间：{note.postedAt}</Text> : null}
                {note.rejectReasonCode ? <Text style={styles.error}>拒绝原因：{note.rejectReasonNote || note.rejectReasonCode}</Text> : null}
                {note.postingError ? <Text style={styles.error}>过账失败：{note.postingError}</Text> : null}
                {hasPriceAnomalies ? (
                  <View style={styles.approvalBox}>
                    <Text style={styles.approvalTitle}>
                      价格异常审批：{APPROVAL_LABEL[approvalStatus]}
                    </Text>
                    {note.priceAnomalyApprovalComment ? (
                      <Text style={styles.meta}>审批意见：{note.priceAnomalyApprovalComment}</Text>
                    ) : null}
                    {approvalStatus === 'PENDING' ? (
                      <Text style={styles.stickyHint}>已提交老板审批，批准前不能入库。</Text>
                    ) : null}
                    {approvalStatus === 'REJECTED' ? (
                      <Text style={styles.error}>老板已驳回，请重新核对价格或联系采购。</Text>
                    ) : null}
                  </View>
                ) : null}
                {note.photoOssUrl ? (
                  <TouchableOpacity
                    activeOpacity={0.84}
                    onPress={() => { void Linking.openURL(note.photoOssUrl as string); }}
                    style={styles.photoEvidence}
                  >
                    <Image source={{ uri: note.photoOssUrl }} style={styles.photoThumb} />
                    <View style={styles.photoText}>
                      <Text style={styles.photoTitle}>现场送货单照片</Text>
                      <Text style={styles.photoHint}>点击打开原图，作为验收证据</Text>
                    </View>
                  </TouchableOpacity>
                ) : null}
              </Card.Content>
            </Card>

            {editing ? (
              <>
                <View style={styles.sectionRow}>
                  <Text style={styles.sectionTitle}>复核食材明细</Text>
                  <Button compact mode="outlined" icon="plus" onPress={addLine}>
                    加一行
                  </Button>
                </View>
                {draftLines.map(renderLineEditor)}
              </>
            ) : (
              <>
                <Text style={styles.sectionTitle}>食材明细</Text>
                {(note.lines || []).map(renderLineView)}
              </>
            )}

            {isDraft ? (
              <View style={styles.actions}>
                {editing ? (
                  <>
                    <Button mode="outlined" onPress={() => setEditing(false)} disabled={savingLines}>
                      取消编辑
                    </Button>
                    <Button mode="contained" icon="content-save" loading={savingLines} disabled={savingLines} onPress={saveLines}>
                      保存明细
                    </Button>
                  </>
                ) : (
                  <>
                    <Button mode="outlined" icon="pencil" onPress={beginEdit}>
                      编辑明细
                    </Button>
                    {canSubmitApproval ? (
                      <Button
                        mode="contained-tonal"
                        icon="account-check"
                        loading={submittingApproval}
                        disabled={submittingApproval}
                        onPress={submitApproval}
                      >
                        提交老板审批
                      </Button>
                    ) : null}
                    <Button mode="contained" icon="check" loading={confirming} disabled={confirming} onPress={confirm}>
                      确认验收入库
                    </Button>
                    {canBossApprove ? (
                      <Button mode="outlined" icon="cash-multiple" onPress={() => navigation.navigate('PriceAnomalyApproval')}>
                        价格异常待审批
                      </Button>
                    ) : null}
                    <Button mode="outlined" icon="close-circle" textColor="#B91C1C" onPress={() => setRejectDialogVisible(true)} disabled={rejecting}>
                      拒绝送货单
                    </Button>
                    <Button mode="text" icon="delete" textColor="#B91C1C" loading={deleting} disabled={deleting} onPress={deleteDraft}>
                      删除草稿
                    </Button>
                  </>
                )}
              </View>
            ) : (
              <Text style={styles.done}>该送货单已处理，不能再编辑或删除。</Text>
            )}
          </>
        )}
      </ScrollView>

      <Portal>
        <Dialog visible={rejectDialogVisible} onDismiss={() => setRejectDialogVisible(false)}>
          <Dialog.Title>拒绝送货单</Dialog.Title>
          <Dialog.Content>
            <Text style={styles.dialogHint}>
              送货单：{note?.noteNumber || note?.id || '-'}，供应商：{note?.supplierName || note?.supplierId || '未绑定'}
            </Text>
            <RadioButton.Group value={rejectReason} onValueChange={(value) => setRejectReason(value as RejectReason)}>
              {REJECT_REASONS.map((reason) => (
                <RadioButton.Item key={reason.value} label={`${reason.label} - ${reason.hint}`} value={reason.value} />
              ))}
            </RadioButton.Group>
            <TextInput
              label={rejectReason === 'OTHER' ? '其它原因（必填）' : '补充说明'}
              mode="outlined"
              value={rejectNote}
              onChangeText={setRejectNote}
              multiline
              style={styles.field}
            />
          </Dialog.Content>
          <Dialog.Actions>
            <Button onPress={() => setRejectDialogVisible(false)} disabled={rejecting}>取消</Button>
            <Button loading={rejecting} disabled={rejecting} onPress={submitReject}>确认拒绝</Button>
          </Dialog.Actions>
        </Dialog>
      </Portal>
    </SafeAreaView>
  );
}

function toEditableLines(lines: SupplierDeliveryLine[]): EditableLine[] {
  return lines.map((line, index) => ({
    key: `${line.id || 'line'}-${index}`,
    id: line.id,
    ingredientName: line.ingredientName || '',
    rawMaterialTypeId: line.rawMaterialTypeId || '',
    materialSearch: line.ingredientName || '',
    quantity: line.quantity == null ? '' : String(line.quantity),
    unit: line.unit || 'kg',
    unitPrice: line.unitPrice == null ? '' : String(line.unitPrice),
    qcResult: line.qcResult || 'PASS',
    remark: line.remark || '',
    showManualMaterialId: false,
    priceAnomalyFlag: line.priceAnomalyFlag,
    priceAnomalyReasonCode: line.priceAnomalyReasonCode || '',
    priceAnomalyExplanation: line.priceAnomalyExplanation || '',
    baselineUnitPrice: line.baselineUnitPrice,
    priceVarianceRate: line.priceVarianceRate,
  }));
}

function toLinePayload(line: EditableLine): SupplierDeliveryLine {
  return {
    id: line.id,
    ingredientName: line.ingredientName.trim(),
    rawMaterialTypeId: line.rawMaterialTypeId.trim(),
    quantity: Number(line.quantity),
    unit: line.unit.trim(),
    unitPrice: line.unitPrice.trim() ? Number(line.unitPrice) : undefined,
    qcResult: line.qcResult,
    remark: line.remark.trim() || undefined,
    priceAnomalyFlag: line.priceAnomalyFlag,
    priceAnomalyReasonCode: line.priceAnomalyReasonCode.trim() || undefined,
    priceAnomalyExplanation: line.priceAnomalyExplanation.trim() || undefined,
    baselineUnitPrice: line.baselineUnitPrice,
    priceVarianceRate: line.priceVarianceRate,
  };
}

function formatLine(line: SupplierDeliveryLine) {
  return `${line.ingredientName}：${line.quantity ?? '-'} ${line.unit || ''}`;
}

function formatAmount(value: number | null | undefined, canViewAmounts: boolean, emptyLabel: string): string {
  if (!canViewAmounts) return '无权限';
  if (value == null || !Number.isFinite(value)) return emptyLabel;
  return `¥${Number(value).toFixed(2)}`;
}

function calcLineAmount(line: SupplierDeliveryLine): number | null {
  if (line.quantity == null || line.unitPrice == null) return null;
  const amount = Number(line.quantity) * Number(line.unitPrice);
  return Number.isFinite(amount) ? amount : null;
}

function formatVariance(rate?: number | null): string {
  if (rate == null || !Number.isFinite(rate)) return '—';
  return `${rate > 0 ? '+' : ''}${(rate * 100).toFixed(1)}%`;
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#F5F5F5' },
  content: { padding: 12, paddingBottom: 40 },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center', paddingTop: 80 },
  loadingText: { marginTop: 12, color: '#6B7280' },
  card: { borderRadius: 8, marginBottom: 12, backgroundColor: '#FFFFFF' },
  titleRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', gap: 8 },
  title: { flex: 1, fontSize: 17, fontWeight: '800', color: '#111827' },
  sectionTitle: { fontSize: 16, fontWeight: '700', color: '#1F2937', marginBottom: 8 },
  sectionRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 },
  lineHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  lineName: { fontSize: 15, fontWeight: '700', color: '#1F2937', marginBottom: 6 },
  meta: { fontSize: 13, color: '#4B5563', marginTop: 4, lineHeight: 19 },
  batch: { fontSize: 13, color: '#047857', marginTop: 6 },
  error: { fontSize: 13, color: '#B91C1C', marginTop: 8, lineHeight: 19 },
  done: { textAlign: 'center', color: '#047857', marginTop: 8 },
  photoEvidence: {
    flexDirection: 'row',
    gap: 10,
    alignItems: 'center',
    borderWidth: 1,
    borderColor: '#D1FAE5',
    backgroundColor: '#F0FDF4',
    borderRadius: 8,
    padding: 8,
    marginTop: 10,
  },
  photoThumb: { width: 64, height: 64, borderRadius: 6, backgroundColor: '#E5E7EB' },
  photoText: { flex: 1 },
  photoTitle: { color: '#047857', fontWeight: '700', fontSize: 13 },
  photoHint: { color: '#4B5563', fontSize: 12, marginTop: 4 },
  field: { backgroundColor: '#FFFFFF', marginTop: 10 },
  row: { flexDirection: 'row', gap: 8 },
  flex: { flex: 1 },
  optionList: { marginTop: 8 },
  optionCard: {
    borderWidth: 1,
    borderColor: '#E5E7EB',
    borderRadius: 8,
    paddingVertical: 9,
    paddingHorizontal: 10,
    marginBottom: 8,
    backgroundColor: '#FFFFFF',
  },
  optionCardSelected: { borderColor: '#1890FF', backgroundColor: '#E6F7FF' },
  optionTitle: { fontSize: 14, fontWeight: '700', color: '#1F2937' },
  optionMeta: { fontSize: 12, color: '#6B7280', marginTop: 3 },
  emptyOption: {
    borderWidth: 1,
    borderColor: '#F59E0B',
    borderRadius: 8,
    padding: 10,
    backgroundColor: '#FFFBEB',
    marginBottom: 8,
  },
  emptyOptionText: { fontSize: 12, color: '#92400E', lineHeight: 18, marginBottom: 6 },
  chipRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginTop: 8 },
  maskedAmount: { marginTop: 10, color: '#6B7280', fontSize: 13 },
  actions: { gap: 10, marginTop: 4, marginBottom: 20 },
  dialogHint: { color: '#4B5563', lineHeight: 20, marginBottom: 8 },
  anomalyCard: { borderColor: '#FECACA', borderWidth: 1, backgroundColor: '#FEF2F2' },
  anomaly: { fontSize: 13, color: '#B91C1C', marginTop: 4, fontWeight: '700' },
  chipText: { fontSize: 11 },
  approvalBox: {
    marginTop: 10,
    padding: 10,
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#FDE68A',
    backgroundColor: '#FFFBEB',
  },
  approvalTitle: { fontSize: 14, fontWeight: '700', color: '#92400E' },
  stickyErrorCard: {
    marginBottom: 12,
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#FECACA',
    backgroundColor: '#FEF2F2',
  },
  stickyErrorTitle: { fontSize: 15, fontWeight: '800', color: '#991B1B', marginBottom: 6 },
  stickyHint: { fontSize: 13, color: '#92400E', marginTop: 6, lineHeight: 19 },
  stickyAction: { marginTop: 10 },
  anomalyEditor: {
    marginTop: 10,
    padding: 10,
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#FECACA',
    backgroundColor: '#FEF2F2',
  },
  anomalyEditorTitle: { fontSize: 14, fontWeight: '700', color: '#991B1B', marginBottom: 6 },
  labelCompact: { fontSize: 13, fontWeight: '600', color: '#374151', marginTop: 4, marginBottom: 4 },
});

export default SupplierDeliveryDetailScreen;
