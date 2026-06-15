/**
 * 开工缺料预警明细屏 — N1b Lane B
 *
 * 路由: OperatorReportStack → ShortageAlertDetail
 * 参数: { batchId, batchNumber, productTypeName, productTypeId, plannedQuantity, plannedUnit }
 *
 * UX 设计原则 (fool-proof):
 *   - Rule 2: 标题带产品名+批次号，操作员知道是哪批次的缺料
 *   - Rule 5: "不阻断开工"——可见预警但仍可继续操作
 *   - 触摸目标 ≥44pt (TouchableRipple)
 *   - 数字 ≥18px (仓管员年纪偏大)
 */

import React, { useCallback, useState } from 'react';
import { FlatList, StyleSheet, View } from 'react-native';
import {
  ActivityIndicator,
  Appbar,
  Banner,
  Card,
  Divider,
  Icon,
  IconButton,
  Text,
} from 'react-native-paper';
import { useFocusEffect, useRoute, useNavigation } from '@react-navigation/native';
import type { RouteProp } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { ScreenWrapper } from '../../components/ui';
import { AppDialogHost } from '../../components/ui/AppDialog';
import {
  shortageAlertApiClient,
  type ShortageItem,
} from '../../services/api/shortageAlertApiClient';
import { useAuthStore } from '../../store/authStore';
import { theme } from '../../theme';

// ── 导航参数（与 OperatorNavigator 内联 Stack 匹配） ──
export type ShortageAlertDetailParams = {
  batchId: number;
  batchNumber?: string;
  productTypeName?: string;
  productTypeId: string;
  plannedQuantity?: number;
  plannedUnit?: string;
};

type StackParamList = {
  ShortageAlertDetail: ShortageAlertDetailParams;
};

type RoutePropT = RouteProp<StackParamList, 'ShortageAlertDetail'>;
type NavPropT = NativeStackNavigationProp<StackParamList, 'ShortageAlertDetail'>;

// ── 单行缺料条目 ──
function ShortageRow({ item }: { item: ShortageItem }) {
  const shortLabel = item.shortfallQuantity.toFixed(2);
  const reqLabel = item.requiredQuantity.toFixed(2);
  const avlLabel = item.availableQuantity.toFixed(2);

  return (
    <View style={styles.row}>
      <View style={styles.rowLeft}>
        <Icon source="alert-circle-outline" size={20} color={theme.colors.error} />
        <Text style={styles.materialName} numberOfLines={2}>
          {item.materialName}
        </Text>
      </View>
      <View style={styles.rowRight}>
        <View style={styles.qtyRow}>
          <Text style={styles.qtyLabel}>需求</Text>
          <Text style={styles.qtyValue}>
            {reqLabel} {item.unit}
          </Text>
        </View>
        <View style={styles.qtyRow}>
          <Text style={styles.qtyLabel}>库存</Text>
          <Text style={[styles.qtyValue, item.availableQuantity <= 0 && styles.zeroQty]}>
            {avlLabel} {item.unit}
          </Text>
        </View>
        <View style={styles.qtyRow}>
          <Text style={styles.qtyLabel}>缺口</Text>
          <Text style={[styles.qtyValue, styles.shortfallValue]}>
            -{shortLabel} {item.unit}
          </Text>
        </View>
      </View>
    </View>
  );
}

export default function ShortageAlertDetailScreen() {
  const navigation = useNavigation<NavPropT>();
  const route = useRoute<RoutePropT>();
  const { getFactoryId } = useAuthStore();

  const {
    batchId,
    batchNumber,
    productTypeName,
    productTypeId,
    plannedQuantity,
    plannedUnit,
  } = route.params;

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [shortageItems, setShortageItems] = useState<ShortageItem[]>([]);
  const [shortfallLeafCount, setShortfallLeafCount] = useState(0);

  const loadShortage = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const quantity = plannedQuantity ?? 1;
      const { result, shortageItems: items } = await shortageAlertApiClient.fetchBomShortage(
        productTypeId,
        quantity,
        getFactoryId() ?? undefined,
      );
      setShortfallLeafCount(result.shortfallLeafCount);
      setShortageItems(items);
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载缺料数据失败，请稍后重试');
    } finally {
      setLoading(false);
    }
  }, [productTypeId, plannedQuantity, getFactoryId]);

  useFocusEffect(
    useCallback(() => {
      loadShortage();
    }, [loadShortage]),
  );

  const batchLabel = batchNumber ? `批次 ${batchNumber}` : `批次 #${batchId}`;
  const productLabel = productTypeName ?? '未命名产品';

  return (
    <ScreenWrapper testID="shortage-alert-detail" edges={['top']} backgroundColor={theme.colors.background}>
      <AppDialogHost />
      <Appbar.Header elevated style={{ backgroundColor: theme.colors.surface }}>
        <Appbar.BackAction onPress={() => navigation.goBack()} />
        <Appbar.Content
          title="缺料明细"
          subtitle={`${productLabel} · ${batchLabel}`}
          titleStyle={{ fontWeight: '600' }}
        />
        <Appbar.Action icon="refresh" onPress={loadShortage} />
      </Appbar.Header>

      {/* 防呆 Rule 5: 不阻断开工横幅 */}
      <Banner
        visible
        icon={({ size }) => <Icon source="information-outline" size={size} color={theme.colors.primary} />}
        style={styles.banner}
      >
        <Text style={styles.bannerText}>
          以下原料库存不足，供参考。操作员仍可继续开工，请联系仓管备料。
        </Text>
      </Banner>

      {loading ? (
        <View style={styles.center}>
          <ActivityIndicator size="large" />
          <Text style={styles.hint}>正在查询缺料情况...</Text>
        </View>
      ) : error ? (
        <View style={styles.center}>
          <IconButton icon="alert-circle-outline" size={52} iconColor={theme.colors.error} />
          <Text style={styles.hint}>{error}</Text>
        </View>
      ) : shortageItems.length === 0 ? (
        <View style={styles.center}>
          <IconButton icon="check-circle-outline" size={52} iconColor={theme.colors.primary} />
          <Text style={styles.hint}>
            {shortfallLeafCount === 0
              ? '库存充足，无缺料！'
              : '暂无可展示的缺料明细。'}
          </Text>
        </View>
      ) : (
        <>
          {/* 摘要卡片 */}
          <Card style={styles.summaryCard} mode="contained">
            <Card.Content style={styles.summaryContent}>
              <View style={styles.summaryRow}>
                <Icon source="package-variant-closed-remove" size={24} color={theme.colors.error} />
                <Text style={styles.summaryText}>
                  共 <Text style={styles.summaryCount}>{shortfallLeafCount}</Text> 种原料缺料
                  {plannedQuantity != null
                    ? `（计划 ${plannedQuantity}${plannedUnit ?? ''}）`
                    : ''}
                </Text>
              </View>
            </Card.Content>
          </Card>

          <FlatList
            data={shortageItems}
            keyExtractor={(item, idx) => `${item.materialName}-${idx}`}
            contentContainerStyle={styles.list}
            ItemSeparatorComponent={() => <Divider style={styles.divider} />}
            renderItem={({ item }) => <ShortageRow item={item} />}
          />
        </>
      )}
    </ScreenWrapper>
  );
}

const styles = StyleSheet.create({
  banner: {
    backgroundColor: '#EEF4FF',
    borderBottomWidth: 1,
    borderBottomColor: '#C7D9F5',
  },
  bannerText: {
    fontSize: 14,
    lineHeight: 20,
    color: theme.colors.onSurface,
  },
  center: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    padding: 24,
  },
  hint: {
    marginTop: 12,
    textAlign: 'center',
    fontSize: 16,
    lineHeight: 24,
    color: theme.colors.onSurfaceVariant,
  },
  summaryCard: {
    margin: 16,
    backgroundColor: '#FFF3F3',
  },
  summaryContent: {
    paddingVertical: 12,
  },
  summaryRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  summaryText: {
    fontSize: 16,
    color: theme.colors.onSurface,
  },
  summaryCount: {
    fontWeight: '700',
    fontSize: 18,
    color: theme.colors.error,
  },
  list: {
    paddingHorizontal: 16,
    paddingBottom: 32,
  },
  divider: {
    marginVertical: 4,
  },
  row: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    paddingVertical: 14,
    gap: 12,
    minHeight: 44,
  },
  rowLeft: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    gap: 6,
    flex: 1,
  },
  materialName: {
    fontSize: 16,
    fontWeight: '600',
    color: theme.colors.onSurface,
    flexShrink: 1,
  },
  rowRight: {
    alignItems: 'flex-end',
    gap: 2,
    minWidth: 140,
  },
  qtyRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
  },
  qtyLabel: {
    fontSize: 13,
    color: theme.colors.onSurfaceVariant,
    minWidth: 28,
  },
  qtyValue: {
    fontSize: 14,
    color: theme.colors.onSurface,
    fontVariant: ['tabular-nums'],
  },
  zeroQty: {
    color: theme.colors.error,
  },
  shortfallValue: {
    fontSize: 16,
    fontWeight: '700',
    color: theme.colors.error,
    fontVariant: ['tabular-nums'],
  },
});
