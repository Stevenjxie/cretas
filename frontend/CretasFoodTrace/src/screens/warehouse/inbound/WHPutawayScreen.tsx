/**
 * 上架页面
 * 对应原型: warehouse/inbound-putaway.html
 */

import React, { useState, useEffect, useCallback } from "react";
import {
  View,
  ScrollView,
  StyleSheet,
  TouchableOpacity,
  Alert,
  ActivityIndicator,
} from "react-native";
import {
  Text,
  Surface,
  Button,
  TextInput,
  useTheme,
} from "react-native-paper";
import { SafeAreaView } from "react-native-safe-area-context";
import { MaterialCommunityIcons } from "@expo/vector-icons";
import { useNavigation, useRoute, RouteProp } from "@react-navigation/native";
import { NativeStackNavigationProp } from "@react-navigation/native-stack";
import { useTranslation } from 'react-i18next';
import { WHInboundStackParamList } from "../../../types/navigation";
import { materialBatchApiClient, MaterialBatch } from "../../../services/api/materialBatchApiClient";
import { handleError } from "../../../utils/errorHandler";

/**
 * 获取材料类型显示名称
 */
const getStorageTypeLabel = (storageType?: string): string => {
  switch (storageType?.toLowerCase()) {
    case 'frozen': return '冻品';
    case 'dry': return '干货';
    case 'fresh':
    default: return '鲜品';
  }
};

/**
 * 获取存储温度范围
 */
const getStorageTemp = (storageType?: string): string => {
  switch (storageType?.toLowerCase()) {
    case 'frozen': return '-18°C ~ -25°C';
    case 'fresh': return '-2°C ~ 4°C';
    case 'dry': return '常温';
    default: return '-2°C ~ 4°C';
  }
};

type NavigationProp = NativeStackNavigationProp<WHInboundStackParamList>;
type RouteType = RouteProp<WHInboundStackParamList, "WHPutaway">;

interface BatchInfo {
  batchNumber: string;
  material: string;
  materialType: string;
  supplier: string;
  quantity: number;
  inspectResult: string;
  storageTemp: string;
}

interface LocationOption {
  id: string;
  name: string;
  zone: string;
  capacity: number;
  used: number;
  temperature: string;
  recommended?: boolean;
}

export function WHPutawayScreen() {
  const { t } = useTranslation('warehouse');
  const theme = useTheme();
  const navigation = useNavigation<NavigationProp>();
  const route = useRoute<RouteType>();
  const { batchId } = route.params;

  // 状态管理
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [batchInfo, setBatchInfo] = useState<BatchInfo | null>(null);

  // 加载批次数据
  const loadBatchData = useCallback(async () => {
    if (!batchId) {
      setLoading(false);
      return;
    }

    try {
      const response = await materialBatchApiClient.getBatchById(batchId) as
        { data?: MaterialBatch } | MaterialBatch | undefined;

      const batch = (response as { data?: MaterialBatch })?.data ?? (response as MaterialBatch);

      if (batch) {
        const storageType = batch.storageType ?? 'fresh';
        setBatchInfo({
          batchNumber: batch.batchNumber ?? `MB-${batch.id}`,
          material: batch.materialName ?? t('messages.unknownMaterial'),
          materialType: getStorageTypeLabel(storageType),
          supplier: batch.supplierName ?? t('messages.unknownSupplier'),
          quantity: batch.remainingQuantity ?? batch.inboundQuantity ?? 0,
          inspectResult: batch.status === 'available' ? t('inbound.putaway.inspectPass') : t('inbound.putaway.inspectPending'),
          storageTemp: getStorageTemp(storageType),
        });
      }
    } catch (error) {
      handleError(error, { title: t('messages.loadBatchFailed') });
    } finally {
      setLoading(false);
    }
  }, [batchId]);

  useEffect(() => {
    loadBatchData();
  }, [loadBatchData]);

  // 推荐库位（TODO: 后续接入库位管理 API）
  const locationOptions: LocationOption[] = [
    {
      id: "1",
      name: "A区-冷藏库-01",
      zone: "A区",
      capacity: 500,
      used: 350,
      temperature: "0°C ~ 4°C",
      recommended: true,
    },
    {
      id: "2",
      name: "A区-冷藏库-02",
      zone: "A区",
      capacity: 500,
      used: 420,
      temperature: "0°C ~ 4°C",
    },
    {
      id: "3",
      name: "A区-冷藏库-03",
      zone: "A区",
      capacity: 500,
      used: 280,
      temperature: "-2°C ~ 2°C",
    },
  ];

  const [selectedLocation, setSelectedLocation] = useState<string | null>(
    locationOptions.find((l) => l.recommended)?.id ?? null
  );
  const [actualQuantity, setActualQuantity] = useState("");
  const [remarks, setRemarks] = useState("");

  // 当批次信息加载完成后，更新数量
  useEffect(() => {
    if (batchInfo) {
      setActualQuantity(batchInfo.quantity.toString());
    }
  }, [batchInfo]);

  const handleConfirm = async () => {
    if (!selectedLocation) {
      Alert.alert(t('inbound.putaway.alert'), t('inbound.putaway.selectLocationAlert'));
      return;
    }

    Alert.alert(t('inbound.putaway.confirmTitle'), t('inbound.putaway.confirmMessage'), [
      { text: t('inbound.putaway.cancel'), style: "cancel" },
      {
        text: t('inbound.detail.confirm'),
        onPress: async () => {
          setSubmitting(true);
          try {
            // 🔴 这里原本是一句 `// TODO: 调用上架 API` + 直接弹「上架成功」——
            //    **没有调用任何接口**。仓管选完库位、点确认、看到成功提示,
            //    而系统里没有任何记录。这比死按钮更糟: 它是**假成功**。
            //
            //    后端接口一直是现成的: `PUT /material-batches/{batchId}` 支持 partial body,
            //    且 service 明确写着「非库存字段 (storageLocation/notes/…) 仍放行」——
            //    纯操作员改库位不受那条「不许改数量/单价」的红线限制。
            const resp = await materialBatchApiClient.updateBatch(
              batchId,
              { storageLocation: selectedLocation },
            );
            if (!resp?.success) {
              throw new Error(resp?.message || t('messages.putawayFailed'));
            }
            Alert.alert(t('inbound.putaway.success'), t('inbound.putaway.successMessage'));
            navigation.goBack();
          } catch (error) {
            handleError(error, { title: t('messages.putawayFailed') });
          } finally {
            setSubmitting(false);
          }
        },
      },
    ]);
  };

  // 加载状态
  if (loading) {
    return (
      <SafeAreaView style={styles.container} edges={["top"]}>
        <View style={styles.header}>
          <TouchableOpacity style={styles.backButton} onPress={() => navigation.goBack()}>
            <MaterialCommunityIcons name="arrow-left" size={24} color="#fff" />
          </TouchableOpacity>
          <Text style={styles.headerTitle}>{t('inbound.putaway.title')}</Text>
          <View style={styles.headerRight} />
        </View>
        <View style={styles.loadingContainer}>
          <ActivityIndicator size="large" color="#4CAF50" />
          <Text style={styles.loadingText}>{t('inbound.putaway.loading')}</Text>
        </View>
      </SafeAreaView>
    );
  }

  // 空状态
  if (!batchInfo) {
    return (
      <SafeAreaView style={styles.container} edges={["top"]}>
        <View style={styles.header}>
          <TouchableOpacity style={styles.backButton} onPress={() => navigation.goBack()}>
            <MaterialCommunityIcons name="arrow-left" size={24} color="#fff" />
          </TouchableOpacity>
          <Text style={styles.headerTitle}>{t('inbound.putaway.title')}</Text>
          <View style={styles.headerRight} />
        </View>
        <View style={styles.loadingContainer}>
          <MaterialCommunityIcons name="package-variant" size={64} color="#ddd" />
          <Text style={styles.loadingText}>{t('inbound.putaway.notFound')}</Text>
        </View>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={styles.container} edges={["top"]}>
      {/* Header */}
      <View style={styles.header}>
        <TouchableOpacity
          style={styles.backButton}
          onPress={() => navigation.goBack()}
        >
          <MaterialCommunityIcons name="arrow-left" size={24} color="#fff" />
        </TouchableOpacity>
        <Text style={styles.headerTitle}>{t('inbound.putaway.title')}</Text>
        <View style={styles.headerRight} />
      </View>

      <ScrollView style={styles.content} showsVerticalScrollIndicator={false}>
        {/* 批次信息 */}
        <Surface style={styles.batchCard} elevation={1}>
          <View style={styles.batchHeader}>
            <Text style={styles.batchNumber}>{batchInfo.batchNumber}</Text>
            <View style={styles.statusBadge}>
              <Text style={styles.statusText}>{batchInfo.inspectResult}</Text>
            </View>
          </View>
          <View style={styles.batchInfo}>
            <View style={styles.infoRow}>
              <Text style={styles.infoLabel}>{t('inbound.putaway.material')}</Text>
              <Text style={styles.infoValue}>
                {batchInfo.material} ({batchInfo.materialType})
              </Text>
            </View>
            <View style={styles.infoRow}>
              <Text style={styles.infoLabel}>{t('inbound.putaway.supplier')}</Text>
              <Text style={styles.infoValue}>{batchInfo.supplier}</Text>
            </View>
            <View style={styles.infoRow}>
              <Text style={styles.infoLabel}>{t('inbound.putaway.quantity')}</Text>
              <Text style={[styles.infoValue, styles.quantityValue]}>
                {batchInfo.quantity} kg
              </Text>
            </View>
            <View style={styles.infoRow}>
              <Text style={styles.infoLabel}>{t('inbound.putaway.storageTemp')}</Text>
              <Text style={styles.infoValue}>{batchInfo.storageTemp}</Text>
            </View>
          </View>
        </Surface>

        {/* 选择库位 */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>{t('inbound.putaway.selectLocation')}</Text>

          {locationOptions.map((location) => (
            <TouchableOpacity
              key={location.id}
              style={[
                styles.locationCard,
                selectedLocation === location.id && styles.locationCardSelected,
              ]}
              onPress={() => setSelectedLocation(location.id)}
              activeOpacity={0.7}
            >
              <View style={styles.locationHeader}>
                <View style={styles.locationInfo}>
                  <Text style={styles.locationName}>{location.name}</Text>
                  {location.recommended && (
                    <View style={styles.recommendBadge}>
                      <Text style={styles.recommendText}>{t('inbound.putaway.recommend')}</Text>
                    </View>
                  )}
                </View>
                <MaterialCommunityIcons
                  name={
                    selectedLocation === location.id
                      ? "radiobox-marked"
                      : "radiobox-blank"
                  }
                  size={24}
                  color={selectedLocation === location.id ? "#4CAF50" : "#ccc"}
                />
              </View>
              <View style={styles.locationMeta}>
                <View style={styles.metaItem}>
                  <MaterialCommunityIcons
                    name="thermometer"
                    size={14}
                    color="#666"
                  />
                  <Text style={styles.metaText}>{location.temperature}</Text>
                </View>
                <View style={styles.metaItem}>
                  <MaterialCommunityIcons
                    name="package-variant"
                    size={14}
                    color="#666"
                  />
                  <Text style={styles.metaText}>
                    {location.used}/{location.capacity} kg
                  </Text>
                </View>
                <View style={styles.capacityBar}>
                  <View
                    style={[
                      styles.capacityFill,
                      {
                        width: `${location.capacity > 0 ? (location.used / location.capacity) * 100 : 0}%`,
                      },
                    ]}
                  />
                </View>
              </View>
            </TouchableOpacity>
          ))}
        </View>

        {/* 上架数量 */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>{t('inbound.putaway.putawayQuantity')}</Text>
          <View style={styles.quantityRow}>
            <TextInput
              mode="outlined"
              value={actualQuantity}
              onChangeText={setActualQuantity}
              keyboardType="numeric"
              style={styles.quantityInput}
              outlineColor="#ddd"
              activeOutlineColor="#4CAF50"
            />
            <Text style={styles.unitText}>kg</Text>
          </View>
          {Number(actualQuantity) !== batchInfo.quantity && (
            <Text style={styles.warningText}>
              {t('inbound.putaway.quantityMismatch')}
            </Text>
          )}
        </View>

        {/* 备注 */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>{t('inbound.putaway.remarks')}</Text>
          <TextInput
            mode="outlined"
            value={remarks}
            onChangeText={setRemarks}
            placeholder={t('inbound.putaway.placeholders.remarks')}
            multiline
            numberOfLines={3}
            style={styles.textArea}
            outlineColor="#ddd"
            activeOutlineColor="#4CAF50"
          />
        </View>

        <View style={{ height: 100 }} />
      </ScrollView>

      {/* 底部操作 */}
      <View style={styles.bottomActions}>
        <Button
          mode="outlined"
          onPress={() => navigation.goBack()}
          style={styles.cancelButton}
          labelStyle={styles.cancelButtonLabel}
          disabled={submitting}
        >
          {t('inbound.putaway.back')}
        </Button>
        <Button
          mode="contained"
          onPress={handleConfirm}
          style={styles.confirmButton}
          labelStyle={styles.confirmButtonLabel}
          icon="check"
          disabled={submitting}
          loading={submitting}
        >
          {submitting ? t('inbound.putaway.confirming') : t('inbound.putaway.confirm')}
        </Button>
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: "#f5f5f5",
  },
  loadingContainer: {
    flex: 1,
    justifyContent: "center",
    alignItems: "center",
  },
  loadingText: {
    marginTop: 12,
    fontSize: 14,
    color: "#666",
  },
  header: {
    backgroundColor: "#4CAF50",
    flexDirection: "row",
    alignItems: "center",
    paddingHorizontal: 16,
    paddingVertical: 12,
  },
  backButton: {
    padding: 4,
  },
  headerTitle: {
    flex: 1,
    fontSize: 18,
    fontWeight: "600",
    color: "#fff",
    textAlign: "center",
    marginRight: 28,
  },
  headerRight: {
    width: 28,
  },
  content: {
    flex: 1,
  },
  batchCard: {
    backgroundColor: "#fff",
    marginHorizontal: 16,
    marginTop: 16,
    borderRadius: 12,
    padding: 16,
  },
  batchHeader: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    marginBottom: 12,
  },
  batchNumber: {
    fontSize: 15,
    fontWeight: "600",
    color: "#333",
  },
  statusBadge: {
    backgroundColor: "#e8f5e9",
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 12,
  },
  statusText: {
    fontSize: 12,
    color: "#4CAF50",
    fontWeight: "500",
  },
  batchInfo: {
    borderTopWidth: 1,
    borderTopColor: "#f0f0f0",
    paddingTop: 12,
  },
  infoRow: {
    flexDirection: "row",
    marginBottom: 8,
  },
  infoLabel: {
    width: 70,
    fontSize: 13,
    color: "#999",
  },
  infoValue: {
    flex: 1,
    fontSize: 13,
    color: "#333",
  },
  quantityValue: {
    fontWeight: "600",
    color: "#4CAF50",
  },
  section: {
    backgroundColor: "#fff",
    marginHorizontal: 16,
    marginTop: 12,
    borderRadius: 12,
    padding: 16,
  },
  sectionTitle: {
    fontSize: 14,
    fontWeight: "600",
    color: "#999",
    marginBottom: 12,
  },
  locationCard: {
    borderWidth: 1,
    borderColor: "#e0e0e0",
    borderRadius: 8,
    padding: 12,
    marginBottom: 10,
  },
  locationCardSelected: {
    borderColor: "#4CAF50",
    backgroundColor: "#f1f8e9",
  },
  locationHeader: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    marginBottom: 8,
  },
  locationInfo: {
    flexDirection: "row",
    alignItems: "center",
    gap: 8,
  },
  locationName: {
    fontSize: 15,
    fontWeight: "600",
    color: "#333",
  },
  recommendBadge: {
    backgroundColor: "#fff3e0",
    paddingHorizontal: 8,
    paddingVertical: 2,
    borderRadius: 4,
  },
  recommendText: {
    fontSize: 10,
    color: "#ff9800",
    fontWeight: "600",
  },
  locationMeta: {
    flexDirection: "row",
    alignItems: "center",
    gap: 16,
  },
  metaItem: {
    flexDirection: "row",
    alignItems: "center",
    gap: 4,
  },
  metaText: {
    fontSize: 12,
    color: "#666",
  },
  capacityBar: {
    flex: 1,
    height: 4,
    backgroundColor: "#e0e0e0",
    borderRadius: 2,
  },
  capacityFill: {
    height: "100%",
    backgroundColor: "#4CAF50",
    borderRadius: 2,
  },
  quantityRow: {
    flexDirection: "row",
    alignItems: "center",
    gap: 12,
  },
  quantityInput: {
    flex: 1,
    backgroundColor: "#fff",
    fontSize: 20,
    textAlign: "center",
  },
  unitText: {
    fontSize: 16,
    color: "#666",
  },
  warningText: {
    marginTop: 8,
    fontSize: 12,
    color: "#ff9800",
  },
  textArea: {
    backgroundColor: "#fff",
    fontSize: 14,
    minHeight: 80,
  },
  bottomActions: {
    position: "absolute",
    bottom: 0,
    left: 0,
    right: 0,
    backgroundColor: "#fff",
    flexDirection: "row",
    padding: 16,
    paddingBottom: 34,
    gap: 12,
    borderTopWidth: 1,
    borderTopColor: "#f0f0f0",
  },
  cancelButton: {
    flex: 1,
    borderRadius: 8,
    borderColor: "#ddd",
  },
  cancelButtonLabel: {
    color: "#666",
  },
  confirmButton: {
    flex: 1,
    borderRadius: 8,
    backgroundColor: "#4CAF50",
  },
  confirmButtonLabel: {
    color: "#fff",
    fontWeight: "600",
  },
});

export default WHPutawayScreen;
