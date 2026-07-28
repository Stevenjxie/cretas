import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { FlatList, RefreshControl, StyleSheet, Text, View } from 'react-native';
import { ActivityIndicator, Appbar, Divider, TextInput } from 'react-native-paper';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useNavigation } from '@react-navigation/native';

import { NeoButton, NeoCard, ScreenWrapper, StatusBadge } from '../../../components/ui';
import { appAlert, AppDialogHost } from '../../../components/ui/AppDialog';
import {
  transitLedgerApiClient,
  TransitLedgerItem,
  TransitOutputLine,
} from '../../../services/api/transitLedgerApiClient';
import { theme } from '../../../theme';

const COPY = {
  title: '\u4e2d\u8f6c\u5b9e\u6536\u786e\u8ba4',
  subtitle: '\u5f85\u4ed3\u5e93\u6838\u5bf9\u5b9e\u6536',
  refresh: '\u5237\u65b0',
  pending: '\u5f85\u786e\u8ba4',
  finishedGoods: '\u6210\u54c1\u5165\u5e93',
  rawMaterial: '\u539f\u6599\u5165\u5e93',
  source: '\u6765\u6e90\u5355',
  planned: '\u8ba1\u5212\u6570',
  reported: '\u62a5\u5de5\u4ea7\u51fa',
  tolerance: '\u5bb9\u5dee',
  receivedQty: '\u5b9e\u6536\u6570\u91cf',
  receivedPlaceholder: '\u8bf7\u586b\u5199\u4ed3\u5e93\u590d\u6838\u540e\u7684\u5b9e\u6536\u6570\u91cf',
  receivedHint: '\u63d0\u4ea4\u524d\u6838\u5bf9\u6765\u6e90\u5355\u3001\u62a5\u5de5\u4ea7\u51fa\u548c\u73b0\u573a\u5b9e\u7269\uff1b\u63d0\u4ea4\u540e\u7531\u540e\u7aef\u5199\u5165\u6210\u54c1\u5e93\u5b58\u3002',
  outputLines: '\u7ec8\u7aef\u4ea7\u51fa\u9010\u884c\u5b9e\u6536',
  outputLineHint: '\u6bcf\u884c\u5fc5\u987b\u6838\u5bf9 SKU\u3001\u6279\u6b21\u3001\u6570\u91cf\u548c\u5355\u4f4d\uff1b\u4e0d\u540c\u5355\u4f4d\u4e0d\u4f1a\u5408\u5e76\u6210\u603b\u6570\u3002',
  sku: 'SKU',
  batch: '\u6279\u6b21',
  note: '\u5907\u6ce8',
  notePlaceholder: '\u53ef\u586b\u5199\u79f0\u91cd\u3001\u590d\u6838\u6216\u73b0\u573a\u8bf4\u660e',
  confirm: '\u786e\u8ba4\u5b9e\u6536',
  confirming: '\u63d0\u4ea4\u4e2d',
  noData: '\u6682\u65e0\u5f85\u786e\u8ba4\u7684\u4e2d\u8f6c\u5b9e\u6536',
  noDataHint: '\u751f\u4ea7\u7ed3\u5355\u8fdb\u5165\u5f85\u4ed3\u5e93\u786e\u8ba4\u540e\u4f1a\u51fa\u73b0\u5728\u8fd9\u91cc',
  loadFailed: '\u5f85\u786e\u8ba4\u5217\u8868\u52a0\u8f7d\u5931\u8d25',
  retry: '\u91cd\u8bd5',
  invalidQtyTitle: '\u8bf7\u586b\u5199\u5b9e\u6536\u6570\u91cf',
  invalidQtyMessage: '\u5b9e\u6536\u6570\u91cf\u5fc5\u987b\u5927\u4e8e 0\uff0c\u4e0d\u5141\u8bb8\u7a7a\u503c\u6216\u8d1f\u6570\u3002',
  invalidQtyScaleMessage: '\u5b9e\u6536\u6570\u91cf\u6700\u591a\u652f\u6301 4 \u4f4d\u5c0f\u6570\uff0c\u7cfb\u7edf\u4e0d\u4f1a\u81ea\u52a8\u56db\u820d\u4e94\u5165\u3002',
  invalidLineMessage: '\u8bf7\u9010\u884c\u6838\u5bf9\u5b9e\u6536\u3002\u5f53\u524d\u6bcf\u884c\u5fc5\u987b\u4e0e\u5df2\u63d0\u4ea4\u62a5\u4ea7\u6570\u91cf\u4e00\u81f4\uff1b\u5982\u73b0\u573a\u4e0d\u4e00\u81f4\uff0c\u8bf7\u5148\u8ba9\u751f\u4ea7\u4fee\u6b63\u62a5\u5de5\u3002',
  successTitle: '\u786e\u8ba4\u6210\u529f',
  successMessage: '\u4ed3\u5e93\u5b9e\u6536\u5df2\u63d0\u4ea4\uff0c\u5e93\u5b58\u548c\u7ed3\u5355\u72b6\u6001\u5df2\u7531\u540e\u7aef\u5904\u7406\u3002',
  failedTitle: '\u786e\u8ba4\u5931\u8d25',
  overTolerance: '\u5dee\u5f02\u8d85\u51fa\u5bb9\u5dee\uff0c\u63d0\u4ea4\u540e\u9700\u8981\u660e\u786e\u8d23\u4efb\u4fa7\u624d\u80fd\u5165\u5e93\u3002',
  withinTolerance: '\u5dee\u5f02\u5728\u5bb9\u5dee\u5185',
  exactMatch: '\u6570\u91cf\u4e00\u81f4',
};

type QuantityById = Record<string, string>;

function formatQty(value?: number | null, unit?: string | null): string {
  if (value == null || Number.isNaN(Number(value))) {
    return '-';
  }
  return `${Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 4 })}${unit ? ` ${unit}` : ''}`;
}

function hasMoreThanFourFractionDigits(value: string | undefined): boolean {
  const trimmed = value?.trim();
  if (!trimmed) return false;
  const normalized = trimmed.replace(',', '.');
  const fraction = normalized.split('.')[1] ?? '';
  return fraction.replace(/0+$/, '').length > 4;
}

function directionLabel(direction: string): string {
  if (direction === 'FINISHED_GOODS_RECEIPT') return COPY.finishedGoods;
  if (direction === 'RAW_MATERIAL_RECEIPT') return COPY.rawMaterial;
  return direction;
}

function parseQuantity(value: string | undefined): number | null {
  const trimmed = value?.trim();
  if (!trimmed) return null;
  const parsed = Number(trimmed);
  return Number.isFinite(parsed) ? parsed : null;
}

function varianceFor(item: TransitLedgerItem, received: number | null): number | null {
  if (received == null || item.reportedQuantity == null) return null;
  return Number(item.reportedQuantity) - received;
}

function isOverTolerance(item: TransitLedgerItem, variance: number | null): boolean {
  if (variance == null) return false;
  const tolerance = Number(item.toleranceQuantity ?? 0);
  return tolerance > 0 && Math.abs(variance) > tolerance;
}

function messageFromError(error: unknown): string {
  const responseData = (error as { response?: { data?: { message?: string; actionHint?: string } } })?.response?.data;
  if (responseData?.message && responseData?.actionHint) {
    return `${responseData.message}\n${responseData.actionHint}`;
  }
  if (responseData?.message) {
    return responseData.message;
  }
  return (error as { message?: string })?.message ?? COPY.failedTitle;
}

function outputLineKey(itemId: string, line: TransitOutputLine, index: number): string {
  return `${itemId}::${line.productTypeId}::${line.batchNumber}::${line.unit}::${index}`;
}

export default function WHTransitLedgerScreen() {
  const navigation = useNavigation();
  const [items, setItems] = useState<TransitLedgerItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [receivedById, setReceivedById] = useState<QuantityById>({});
  const [noteById, setNoteById] = useState<QuantityById>({});
  const [confirmingId, setConfirmingId] = useState<string | null>(null);

  const load = useCallback(async () => {
    setError(null);
    try {
      const response = await transitLedgerApiClient.listPending();
      if (response.success) {
        const pending = response.data ?? [];
        setItems(pending.filter((item) => item.direction === 'FINISHED_GOODS_RECEIPT'));
        setReceivedById((previous) => {
          const next = { ...previous };
          pending.forEach((item) => {
            (item.outputLines ?? []).forEach((line, index) => {
              const key = outputLineKey(item.id, line, index);
              if (next[key] == null && line.reportedQuantity != null) {
                next[key] = String(line.reportedQuantity);
              }
            });
          });
          return next;
        });
      } else {
        setError(response.message ?? COPY.loadFailed);
      }
    } catch (err: unknown) {
      setError(messageFromError(err));
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const pendingCount = useMemo(() => items.length, [items]);

  const onRefresh = useCallback(() => {
    setRefreshing(true);
    void load();
  }, [load]);

  const submitConfirm = useCallback(async (item: TransitLedgerItem) => {
    const outputLines = item.outputLines ?? [];
    const outputQuantityInputs = outputLines.map((line, index) =>
      receivedById[outputLineKey(item.id, line, index)]);
    if (outputQuantityInputs.some(hasMoreThanFourFractionDigits)
      || (outputLines.length === 0 && hasMoreThanFourFractionDigits(receivedById[item.id]))) {
      appAlert(COPY.invalidQtyTitle, COPY.invalidQtyScaleMessage);
      return;
    }
    const receivedQuantity = parseQuantity(receivedById[item.id]);
    const confirmedOutputLines = outputLines.map((line, index) => ({
      productTypeId: line.productTypeId,
      batchNumber: line.batchNumber,
      receivedQuantity: parseQuantity(receivedById[outputLineKey(item.id, line, index)]),
      quantityUnit: line.unit,
      reportedQuantity: Number(line.reportedQuantity),
    }));
    const invalidOutputLine = confirmedOutputLines.some((line) =>
      line.receivedQuantity == null
      || line.receivedQuantity <= 0
      || line.receivedQuantity !== line.reportedQuantity
    );
    if (outputLines.length > 0 && invalidOutputLine) {
      appAlert(COPY.invalidQtyTitle, COPY.invalidLineMessage);
      return;
    }
    if (outputLines.length === 0 && (receivedQuantity == null || receivedQuantity <= 0)) {
      appAlert(COPY.invalidQtyTitle, COPY.invalidQtyMessage);
      return;
    }

    setConfirmingId(item.id);
    try {
      const response = await transitLedgerApiClient.confirm(item.id, {
        receivedQuantity: outputLines.length === 0 ? receivedQuantity ?? undefined : undefined,
        outputLines: confirmedOutputLines.map(({ reportedQuantity: _reportedQuantity, ...line }) => ({
          ...line,
          receivedQuantity: line.receivedQuantity as number,
        })),
        note: noteById[item.id]?.trim() || undefined,
      });
      if (response.success) {
        appAlert(COPY.successTitle, response.data?.message ?? COPY.successMessage);
        setReceivedById((prev) => {
          const next = { ...prev };
          delete next[item.id];
          return next;
        });
        setNoteById((prev) => {
          const next = { ...prev };
          delete next[item.id];
          return next;
        });
        await load();
      } else {
        appAlert(COPY.failedTitle, response.message ?? COPY.failedTitle);
      }
    } catch (err: unknown) {
      appAlert(COPY.failedTitle, messageFromError(err));
    } finally {
      setConfirmingId(null);
    }
  }, [load, noteById, receivedById]);

  const renderItem = useCallback(({ item }: { item: TransitLedgerItem }) => {
    const receivedQuantity = parseQuantity(receivedById[item.id]);
    const variance = varianceFor(item, receivedQuantity);
    const overTolerance = isOverTolerance(item, variance);
    const name = item.productName || item.batchNumber || item.sourceNumber || item.id;
    const confirming = confirmingId === item.id;
    const outputLines = item.outputLines ?? [];
    const lineValuesValid = outputLines.length > 0 && outputLines.every((line, index) => {
      const value = parseQuantity(receivedById[outputLineKey(item.id, line, index)]);
      return value != null && value > 0 && value === Number(line.reportedQuantity);
    });
    const canConfirm = (outputLines.length > 0
      ? lineValuesValid
      : receivedQuantity != null && receivedQuantity > 0) && !confirming;

    return (
      <NeoCard style={styles.card} padding="l" variant="elevated" testID={`transit-card-${item.id}`}>
        <View style={styles.cardHeader}>
          <View style={styles.titleBlock}>
            <Text style={styles.itemTitle} numberOfLines={2}>{name}</Text>
            <Text style={styles.itemMeta}>{COPY.source}: {item.sourceNumber || item.id}</Text>
          </View>
          <StatusBadge status={directionLabel(item.direction)} variant="info" />
        </View>

        <View style={styles.metrics}>
          <Metric label={COPY.reported} value={formatQty(item.reportedQuantity, item.unit)} />
          <Metric label={COPY.tolerance} value={formatQty(item.toleranceQuantity, item.unit)} />
          <Metric label={COPY.planned} value={formatQty(item.plannedQuantity, item.unit)} />
        </View>

        <View style={styles.locationRow}>
          <MaterialCommunityIcons name="map-marker-path" size={18} color={theme.colors.textSecondary} />
          <Text style={styles.locationText}>
            {item.fromLocation || '-'} -&gt; {item.toWarehouseName || '-'}
          </Text>
        </View>

        {outputLines.length > 0 ? (
          <View style={styles.outputSection}>
            <Text style={styles.outputSectionTitle}>{COPY.outputLines}</Text>
            <Text style={styles.inputHint}>{COPY.outputLineHint}</Text>
            {outputLines.map((line, index) => {
              const key = outputLineKey(item.id, line, index);
              return (
                <View key={key} style={styles.outputLine}>
                  <Text style={styles.outputIdentity}>
                    {COPY.sku}: {line.productTypeId}  ·  {COPY.batch}: {line.batchNumber}
                  </Text>
                  <TextInput
                    mode="outlined"
                    label={`${COPY.receivedQty} / ${formatQty(line.reportedQuantity, line.unit)}`}
                    keyboardType="decimal-pad"
                    value={receivedById[key] ?? ''}
                    onChangeText={(text) => setReceivedById((prev) => ({ ...prev, [key]: text }))}
                    style={styles.lineInput}
                    right={<TextInput.Affix text={line.unit || ''} />}
                    testID={`transit-received-${item.id}-${index}`}
                  />
                </View>
              );
            })}
          </View>
        ) : (
          <>
            <TextInput
              mode="outlined"
              label={COPY.receivedQty}
              placeholder={COPY.receivedPlaceholder}
              keyboardType="decimal-pad"
              value={receivedById[item.id] ?? ''}
              onChangeText={(text) => setReceivedById((prev) => ({ ...prev, [item.id]: text }))}
              style={styles.input}
              right={<TextInput.Affix text={item.unit || ''} />}
              testID={`transit-received-${item.id}`}
            />
            {!receivedById[item.id] && (
              <Text style={styles.inputHint}>{COPY.receivedHint}</Text>
            )}
          </>
        )}

        <TextInput
          mode="outlined"
          label={COPY.note}
          placeholder={COPY.notePlaceholder}
          value={noteById[item.id] ?? ''}
          onChangeText={(text) => setNoteById((prev) => ({ ...prev, [item.id]: text }))}
          style={styles.input}
          multiline
          testID={`transit-note-${item.id}`}
        />

        {variance != null && (
          <View style={[styles.varianceBox, overTolerance ? styles.varianceError : styles.varianceOk]}>
            <Text style={[styles.varianceText, overTolerance ? styles.errorText : styles.okText]}>
              {overTolerance
                ? COPY.overTolerance
                : variance === 0
                  ? COPY.exactMatch
                  : `${COPY.withinTolerance}: ${formatQty(Math.abs(variance), item.unit)}`}
            </Text>
          </View>
        )}

        <NeoButton
          icon="check"
          size="large"
          onPress={() => void submitConfirm(item)}
          loading={confirming}
          disabled={!canConfirm}
          style={styles.button}
          testID={`transit-confirm-${item.id}`}
        >
          {confirming ? COPY.confirming : COPY.confirm}
        </NeoButton>
      </NeoCard>
    );
  }, [confirmingId, noteById, receivedById, submitConfirm]);

  return (
    <ScreenWrapper edges={['top', 'bottom']} backgroundColor={theme.colors.background}>
      <Appbar.Header elevated={false} style={styles.header}>
        <Appbar.BackAction onPress={() => navigation.goBack()} />
        <Appbar.Content title={COPY.title} subtitle={`${pendingCount} ${COPY.pending}`} />
        <Appbar.Action icon="refresh" onPress={onRefresh} accessibilityLabel={COPY.refresh} />
      </Appbar.Header>

      <Divider />

      {loading ? (
        <View style={styles.center}>
          <ActivityIndicator size="large" />
        </View>
      ) : error ? (
        <View style={styles.center}>
          <MaterialCommunityIcons name="alert-circle-outline" size={38} color={theme.colors.error} />
          <Text style={styles.errorTextCenter}>{error}</Text>
          <NeoButton icon="refresh" onPress={onRefresh} style={styles.retryButton}>{COPY.retry}</NeoButton>
        </View>
      ) : (
        <FlatList
          testID="transit-ledger-list"
          data={items}
          keyExtractor={(item) => item.id}
          renderItem={renderItem}
          contentContainerStyle={items.length === 0 ? styles.empty : styles.list}
          refreshControl={<RefreshControl refreshing={refreshing} onRefresh={onRefresh} />}
          ListHeaderComponent={items.length > 0 ? <Text style={styles.subtitle}>{COPY.subtitle}</Text> : null}
          ListEmptyComponent={
            <View style={styles.center}>
              <MaterialCommunityIcons name="check-circle-outline" size={42} color={theme.colors.success} />
              <Text style={styles.emptyTitle} testID="transit-empty-title">{COPY.noData}</Text>
              <Text style={styles.emptyHint}>{COPY.noDataHint}</Text>
            </View>
          }
        />
      )}
      <AppDialogHost />
    </ScreenWrapper>
  );
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <View style={styles.metric}>
      <Text style={styles.metricLabel}>{label}</Text>
      <Text style={styles.metricValue} numberOfLines={1}>{value}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  header: {
    backgroundColor: theme.colors.surface,
  },
  list: {
    padding: theme.custom.spacing.l,
    paddingBottom: theme.custom.spacing.xxl,
  },
  subtitle: {
    color: theme.colors.textSecondary,
    fontSize: 13,
    marginBottom: theme.custom.spacing.m,
  },
  empty: {
    flexGrow: 1,
    padding: theme.custom.spacing.xl,
  },
  center: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    padding: theme.custom.spacing.xl,
  },
  card: {
    marginBottom: theme.custom.spacing.l,
    borderRadius: theme.custom.borderRadius.m,
  },
  cardHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    gap: theme.custom.spacing.m,
  },
  titleBlock: {
    flex: 1,
    minWidth: 0,
  },
  itemTitle: {
    color: theme.colors.text,
    fontSize: 17,
    fontWeight: '700',
  },
  itemMeta: {
    color: theme.colors.textSecondary,
    fontSize: 12,
    marginTop: theme.custom.spacing.xs,
  },
  metrics: {
    flexDirection: 'row',
    gap: theme.custom.spacing.s,
    marginTop: theme.custom.spacing.l,
  },
  metric: {
    flex: 1,
    borderRadius: theme.custom.borderRadius.s,
    backgroundColor: theme.colors.surfaceVariant,
    padding: theme.custom.spacing.m,
    minHeight: 62,
  },
  metricLabel: {
    color: theme.colors.textSecondary,
    fontSize: 12,
  },
  metricValue: {
    color: theme.colors.text,
    fontSize: 14,
    fontWeight: '700',
    marginTop: theme.custom.spacing.xs,
  },
  locationRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: theme.custom.spacing.s,
    marginTop: theme.custom.spacing.l,
  },
  locationText: {
    flex: 1,
    color: theme.colors.textSecondary,
    fontSize: 13,
  },
  input: {
    marginTop: theme.custom.spacing.m,
  },
  inputHint: {
    color: theme.colors.textSecondary,
    fontSize: 12,
    lineHeight: 18,
    marginTop: theme.custom.spacing.xs,
  },
  outputSection: {
    marginTop: theme.custom.spacing.l,
  },
  outputSectionTitle: {
    color: theme.colors.text,
    fontSize: 15,
    fontWeight: '700',
  },
  outputLine: {
    marginTop: theme.custom.spacing.m,
    padding: theme.custom.spacing.m,
    borderRadius: theme.custom.borderRadius.s,
    backgroundColor: theme.colors.surfaceVariant,
  },
  outputIdentity: {
    color: theme.colors.text,
    fontSize: 13,
    fontWeight: '600',
  },
  lineInput: {
    marginTop: theme.custom.spacing.s,
    minHeight: 56,
  },
  varianceBox: {
    borderRadius: theme.custom.borderRadius.s,
    padding: theme.custom.spacing.m,
    marginTop: theme.custom.spacing.m,
    borderWidth: 1,
  },
  varianceOk: {
    backgroundColor: '#E6F9E9',
    borderColor: '#B7E5C1',
  },
  varianceError: {
    backgroundColor: theme.colors.errorContainer,
    borderColor: '#FFCCC7',
  },
  varianceText: {
    fontSize: 13,
    lineHeight: 18,
  },
  okText: {
    color: '#167A38',
  },
  errorText: {
    color: theme.colors.onErrorContainer,
  },
  errorTextCenter: {
    color: theme.colors.error,
    textAlign: 'center',
    marginTop: theme.custom.spacing.m,
    marginBottom: theme.custom.spacing.l,
    lineHeight: 20,
  },
  button: {
    marginTop: theme.custom.spacing.l,
  },
  retryButton: {
    minWidth: 120,
  },
  emptyTitle: {
    color: theme.colors.text,
    fontSize: 17,
    fontWeight: '700',
    textAlign: 'center',
    marginTop: theme.custom.spacing.m,
  },
  emptyHint: {
    color: theme.colors.textSecondary,
    fontSize: 13,
    lineHeight: 20,
    textAlign: 'center',
    marginTop: theme.custom.spacing.s,
  },
});
