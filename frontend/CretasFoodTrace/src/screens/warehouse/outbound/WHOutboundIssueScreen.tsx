/**
 * 扫码出库确认页 (领料/出库) — 对称于入库的 WHReceiptCreate (2 字段闭环).
 *
 * 场景: 仓管员在 WHScanOperation (出库模式) 扫物料批次一物一码标签 → 拿到
 * batchId/batchNumber/materialName/remainingQuantity → 跳本页. 本页 prefill
 * 物料名 + 批次号 + 剩余量 (全部只读), 仓管员只填"出库数量"一个字段, 数量上限
 * 锁死为剩余量, 不需要仓管员自己记批次号/核对库存, 防止拿错料/超领.
 *
 * 客户原话 (六扇门仓管场景, 见 .claude/rules/fool-proof-design.md):
 * "做仓管的他年纪都比较大文化素质很低, 你不能太依赖他们, 最好的方法就是你告诉他
 * 这个东西你要收多少就行了"
 *
 * 出库路径选择 (本次改造范围内的决策, 见 PR description):
 * 出库场景实际分三种 — 领料出库(仓库→车间生产)/销售出库(仓库→客户,走
 * 销售订单→发货单→仓库确认, 对象是成品 FinishedGoodsBatch)/库位调拨(仓库内部,
 * 不消耗库存). 扫描的一物一码标签是**原料批次**标签 (LabelServiceImpl.scanLabel
 * 只支持 batchType=MATERIAL), 与销售出库用的成品批次是不同的数据体系, 也不是单纯
 * 库位调拨 (调拨不减库存). 因此本页对接后端已有的"使用批次材料"接口
 * (POST /material-batches/{batchId}/use, MaterialBatchServiceImpl.useBatchMaterial),
 * 语义就是"领料/出库确认": 扣减 remainingQuantity, 记录操作人, 触发低库存预警,
 * 且已有完整边界守卫 (数量>0/不超剩余量/过期报废批次拦截) — 是最贴近仓管员真实
 * 出库动作、且无需新建审批工作流的最小闭环。不产 productionPlanId (留空即通用出库,
 * 不强绑某个生产计划), 后续如需与生产计划/FactoryMaterialRequisition 走审批链路可
 * 在此基础上扩展。
 */

import React, { useCallback, useState } from 'react';
import {
  View,
  ScrollView,
  StyleSheet,
  Alert,
  KeyboardAvoidingView,
  Platform,
} from 'react-native';
import {
  Appbar,
  Button,
  Card,
  Text,
  TextInput,
} from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useNavigation, useRoute, RouteProp } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';

import { WHOutboundStackParamList } from '../../../types/navigation';
import { materialBatchApiClient } from '../../../services/api/materialBatchApiClient';
import { handleError } from '../../../utils/errorHandler';

type Nav = NativeStackNavigationProp<WHOutboundStackParamList, 'WHOutboundIssue'>;
type RouteProps = RouteProp<WHOutboundStackParamList, 'WHOutboundIssue'>;

export default function WHOutboundIssueScreen() {
  const navigation = useNavigation<Nav>();
  const route = useRoute<RouteProps>();
  const { batchId, batchNumber, materialName, remainingQuantity, quantityUnit } = route.params;

  const [quantity, setQuantity] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const unit = quantityUnit || 'kg';
  // 防呆 Rule 6: 剩余量为 0 (或缺失) 时 honest-fail, 不允许进入录入
  const depleted = !(remainingQuantity > 0);

  const qtyNum = Number(quantity);
  const qtyValid = quantity.trim() !== '' && Number.isFinite(qtyNum) && qtyNum > 0;
  // 防呆 Rule 1: 出库数量硬上限 = 剩余量, 超出直接不可提交 (非提交后才报错)
  const overLimit = qtyValid && qtyNum > remainingQuantity;
  const canSubmit = !depleted && qtyValid && !overLimit && !submitting;

  const handleQuantityChange = useCallback((text: string) => {
    setQuantity(text.replace(/[^0-9.]/g, ''));
  }, []);

  const handleSubmit = useCallback(async () => {
    if (!canSubmit) return;

    Alert.alert(
      '确认出库',
      `${materialName || '该物料'} 批次 ${batchNumber}\n出库数量: ${qtyNum} ${unit}\n出库后剩余: ${(remainingQuantity - qtyNum).toFixed(2)} ${unit}`,
      [
        { text: '取消', style: 'cancel' },
        {
          text: '确定出库',
          onPress: async () => {
            setSubmitting(true);
            try {
              const res = await materialBatchApiClient.useBatch(batchId, qtyNum);
              if (res && res.success === false) {
                throw new Error(res.message || '出库失败');
              }
              Alert.alert('出库成功', `${batchNumber} 已出库 ${qtyNum} ${unit}`, [
                { text: '确定', onPress: () => navigation.goBack() },
              ]);
            } catch (err) {
              handleError(err, { title: '出库提交失败' });
            } finally {
              setSubmitting(false);
            }
          },
        },
      ],
    );
  }, [canSubmit, materialName, batchNumber, qtyNum, unit, remainingQuantity, batchId, navigation]);

  return (
    <KeyboardAvoidingView
      style={styles.flex}
      behavior={Platform.OS === 'ios' ? 'padding' : undefined}
    >
      <SafeAreaView style={styles.container} edges={['top']}>
        <Appbar.Header>
          <Appbar.BackAction onPress={() => navigation.goBack()} />
          <Appbar.Content title="扫码出库确认" subtitle={`批次 ${batchNumber}`} />
        </Appbar.Header>

        <ScrollView style={styles.scroll} contentContainerStyle={styles.scrollContent}>
          {/* 物料信息卡片 — 全部只读, 扫码 prefill (防呆 Rule 2: 上下文必带身份信息) */}
          <Card style={styles.card}>
            <Card.Content>
              <Text style={styles.sectionTitle}>物料信息 (扫码识别)</Text>
              <View style={styles.kv}>
                <Text style={styles.kvLabel}>物料</Text>
                <Text style={styles.kvValue}>{materialName || '未知物料'}</Text>
              </View>
              <View style={styles.kv}>
                <Text style={styles.kvLabel}>批次号</Text>
                <Text style={styles.kvValue}>{batchNumber}</Text>
              </View>
              <View style={styles.kv}>
                <Text style={styles.kvLabel}>剩余库存</Text>
                <Text style={[styles.kvValue, styles.remainingValue]}>
                  {remainingQuantity > 0 ? `${remainingQuantity} ${unit}` : '0 (已出清)'}
                </Text>
              </View>
            </Card.Content>
          </Card>

          {depleted ? (
            // 防呆 Rule 6 honest-fail: 剩余量为 0, 明确告知且不可继续, 不静默失败
            <Card style={[styles.card, styles.depletedCard]}>
              <Card.Content>
                <Text style={styles.depletedTitle}>该批次库存已出清，无法出库</Text>
                <Text style={styles.depletedHint}>
                  请确认扫描的是正确的批次标签，或联系仓库主管核实库存。
                </Text>
              </Card.Content>
            </Card>
          ) : (
            <Card style={styles.card}>
              <Card.Content>
                <Text style={styles.sectionTitle}>出库数量</Text>
                <TextInput
                  label={`出库数量 (${unit}) *`}
                  value={quantity}
                  onChangeText={handleQuantityChange}
                  mode="outlined"
                  keyboardType="numeric"
                  style={styles.field}
                  contentStyle={{ fontSize: 24 }}
                  placeholder={`最多可出 ${remainingQuantity} ${unit}`}
                  error={overLimit}
                  disabled={submitting}
                />
                <Text style={[styles.hint, overLimit && styles.hintError]}>
                  {overLimit
                    ? `超出剩余库存 (最多 ${remainingQuantity} ${unit})`
                    : `可出库范围: 1 - ${remainingQuantity} ${unit}`}
                </Text>
              </Card.Content>
            </Card>
          )}

          <View style={styles.bottomSpacer} />
        </ScrollView>

        <View style={styles.footer}>
          <Button
            mode="contained"
            onPress={handleSubmit}
            loading={submitting}
            disabled={!canSubmit}
            icon="check"
          >
            {submitting ? '提交中...' : '确认出库'}
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

  card: { marginBottom: 12, borderRadius: 8 },
  sectionTitle: { fontSize: 15, fontWeight: '600', marginBottom: 8, color: '#2d3748' },

  kv: { flexDirection: 'row', paddingVertical: 4 },
  kvLabel: { width: 80, fontSize: 13, color: '#718096' },
  kvValue: { flex: 1, fontSize: 13, color: '#2d3748' },
  remainingValue: { fontWeight: '700', color: '#2196F3' },

  depletedCard: { backgroundColor: '#fff3e0', borderLeftWidth: 3, borderLeftColor: '#f57c00' },
  depletedTitle: { fontSize: 14, fontWeight: '600', color: '#e65100', marginBottom: 4 },
  depletedHint: { fontSize: 12, color: '#8d6e63', lineHeight: 18 },

  field: { backgroundColor: 'transparent' },
  hint: { fontSize: 12, color: '#718096', marginTop: 6 },
  hintError: { color: '#d32f2f', fontWeight: '600' },

  bottomSpacer: { height: 24 },

  footer: {
    padding: 12,
    backgroundColor: '#fff',
    borderTopWidth: 1,
    borderTopColor: '#e0e0e0',
  },
});
