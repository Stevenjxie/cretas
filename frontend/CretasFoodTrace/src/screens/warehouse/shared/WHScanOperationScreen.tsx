/**
 * 扫码作业页面 — 真实摄像头 + QR/Code128 扫描.
 *
 * 入库 (inbound): 扫 PDF 二维码 → orderNumber → 调 by-number 接口拿订单 →
 *                跳 WHReceiptCreate (2 字段收货页).
 *                六扇门 May 7 transcript: "扫一下上面的拳运码 然后开始入库"
 *
 * 出库 (outbound): 扫批次条码 — Phase 2 接入实际出库流程.
 *
 * 历史: 原实现是 setTimeout + 假批次号 mock (TODO 注释明示). 本次 W-ABA-1
 * Day 4 改造为真实 expo-camera + onBarcodeScanned, 支持 QR + Code128 双格式
 * (PDF 上 PR #413 两种都打印).
 */

import React, { useCallback, useRef, useState } from "react";
import { View, StyleSheet, TouchableOpacity, Alert, Vibration } from "react-native";
import { Text, Surface, Button } from "react-native-paper";
import { SafeAreaView } from "react-native-safe-area-context";
import { MaterialCommunityIcons } from "@expo/vector-icons";
import { useNavigation, useRoute } from "@react-navigation/native";
import { NativeStackNavigationProp } from "@react-navigation/native-stack";
import { CameraView, useCameraPermissions, type BarcodeScanningResult } from "expo-camera";
import { WHInboundStackParamList } from "../../../types/navigation";
import { purchaseApiClient } from "../../../services/api/purchaseApiClient";
import { handleError } from "../../../utils/errorHandler";

type NavigationProp = NativeStackNavigationProp<WHInboundStackParamList>;

/**
 * 兼容旧/新参数:
 * - brief & navigation.ts 用 `type`
 * - 旧 outbound stack 也用 `type`
 * - 旧本文件接收的是 `mode` (内部别名)
 */
interface ScanOperationParams {
  type?: "inbound" | "outbound";
  mode?: "inbound" | "outbound";
  orderId?: string;
}

interface PurchaseOrderLookup {
  id?: string;
  orderNumber?: string;
}

type AlertPrompt = (
  title: string,
  message?: string,
  buttons?: Array<{ text: string; style?: "default" | "cancel" | "destructive"; onPress?: (text: string) => void }>,
  type?: "plain-text" | "secure-text" | "login-password",
  defaultValue?: string,
  keyboardType?: string,
) => void;
type AlertWithPrompt = typeof Alert & { prompt?: AlertPrompt };

const SCAN_COOLDOWN_MS = 2000; // 同一扫码冷却, 防 onBarcodeScanned 高频重复触发

export function WHScanOperationScreen() {
  const navigation = useNavigation<NavigationProp>();
  const route = useRoute();
  const params = route.params as ScanOperationParams | undefined;
  const mode: "inbound" | "outbound" = params?.type ?? params?.mode ?? "inbound";

  const [permission, requestPermission] = useCameraPermissions();
  const [cameraActive, setCameraActive] = useState(false);
  const [resolving, setResolving] = useState(false);
  const [scannedCount, setScannedCount] = useState(0);
  const [lastScan, setLastScan] = useState<string | null>(null);
  const lastScanAtRef = useRef<number>(0);

  const modeConfig = {
    inbound: { title: "扫码入库 (PDF QR)", color: "#4CAF50", action: "入库" },
    outbound: { title: "扫码出库", color: "#2196F3", action: "出库" },
  };
  const config = modeConfig[mode];

  // 处理扫码结果 — 冷却防抖 + 路由分发
  const resolveScan = useCallback(async (raw: string) => {
    const now = Date.now();
    if (now - lastScanAtRef.current < SCAN_COOLDOWN_MS) return;
    lastScanAtRef.current = now;

    const code = raw?.trim();
    if (!code) return;

    Vibration.vibrate(100);
    setLastScan(code);
    setScannedCount((prev) => prev + 1);

    if (mode === "outbound") {
      // Phase 2: 出库流程接入. 当前仅展示扫描结果.
      Alert.alert("扫码成功", `批次号: ${code}\n(出库流程 Phase 2 接入)`);
      return;
    }

    // 入库: code = orderNumber, 反查 PO + 跳收货页
    setResolving(true);
    setCameraActive(false);
    try {
      const res = await purchaseApiClient.getOrderByNumber(code);
      const order = res?.data as PurchaseOrderLookup | undefined;
      if (!order || !order.id) {
        Alert.alert("订单未找到", `订单号 ${code} 在当前工厂查不到. 请确认 PDF 是本工厂打印的供货单.`);
        return;
      }
      navigation.navigate("WHReceiptCreate", {
        purchaseOrderId: order.id,
        orderNumber: order.orderNumber ?? code,
      });
    } catch (err) {
      handleError(err, { title: "扫码识别失败" });
    } finally {
      setResolving(false);
    }
  }, [mode, navigation]);

  const handleBarcodeScanned = useCallback((result: BarcodeScanningResult) => {
    if (resolving) return;
    resolveScan(result.data);
  }, [resolveScan, resolving]);

  const handleManualInput = useCallback(() => {
    const promptFn = (Alert as AlertWithPrompt).prompt;
    if (typeof promptFn === "function") {
      promptFn(
        mode === "inbound" ? "手动输入采购订单号" : "手动输入批次号",
        mode === "inbound" ? "示例: PO-20260514-001" : "示例: MB-20260514-001",
        [
          { text: "取消", style: "cancel" },
          { text: "确定", onPress: (text: string) => text && resolveScan(text) },
        ],
        "plain-text",
        "",
        "default",
      );
    } else {
      Alert.alert("提示", "当前平台不支持手动输入弹窗, 请使用扫码功能");
    }
  }, [mode, resolveScan]);

  const toggleCamera = useCallback(async () => {
    if (cameraActive) {
      setCameraActive(false);
      return;
    }
    if (!permission?.granted) {
      const result = await requestPermission();
      if (!result.granted) {
        Alert.alert("权限不足", "需要相机权限才能扫描二维码. 请到系统设置中授权.");
        return;
      }
    }
    setCameraActive(true);
  }, [cameraActive, permission, requestPermission]);

  return (
    <SafeAreaView style={styles.container} edges={["top"]}>
      {/* Header */}
      <View style={[styles.header, { backgroundColor: config.color }]}>
        <TouchableOpacity style={styles.backButton} onPress={() => navigation.goBack()}>
          <MaterialCommunityIcons name="arrow-left" size={24} color="#fff" />
        </TouchableOpacity>
        <Text style={styles.headerTitle}>{config.title}</Text>
        <View style={styles.headerRight} />
      </View>

      <View style={styles.content}>
        {/* 扫码区域 */}
        <View style={styles.scanArea}>
          <View style={[styles.scanFrame, { borderColor: config.color }]}>
            {cameraActive && !resolving ? (
              <CameraView
                style={StyleSheet.absoluteFill}
                facing="back"
                barcodeScannerSettings={{ barcodeTypes: ["qr", "code128"] }}
                onBarcodeScanned={handleBarcodeScanned}
              />
            ) : resolving ? (
              <>
                <MaterialCommunityIcons name="loading" size={64} color={config.color} />
                <Text style={styles.scanHint}>识别中...</Text>
              </>
            ) : (
              <>
                <MaterialCommunityIcons name="qrcode-scan" size={80} color={config.color} />
                <Text style={styles.scanHint}>
                  {mode === "inbound" ? "扫 PDF 二维码 / 一维条码" : "扫批次条码"}
                </Text>
              </>
            )}
          </View>
        </View>

        {/* 统计 */}
        <Surface style={styles.statsCard} elevation={1}>
          <View style={styles.statsRow}>
            <View style={styles.statsItem}>
              <Text style={[styles.statsValue, { color: config.color }]}>{scannedCount}</Text>
              <Text style={styles.statsLabel}>已扫描</Text>
            </View>
            <View style={styles.statsDivider} />
            <View style={styles.statsItem}>
              <Text style={styles.lastScanText} numberOfLines={1}>
                {lastScan ?? "暂无扫描记录"}
              </Text>
              <Text style={styles.statsLabel}>最近扫描</Text>
            </View>
          </View>
        </Surface>

        {/* 操作按钮 */}
        <View style={styles.actions}>
          <Button
            mode="contained"
            onPress={toggleCamera}
            style={[styles.scanButton, { backgroundColor: config.color }]}
            labelStyle={styles.scanButtonLabel}
            icon={cameraActive ? "stop" : "qrcode-scan"}
            disabled={resolving}
          >
            {cameraActive ? "停止扫码" : resolving ? "识别中..." : "开始扫码"}
          </Button>

          <Button
            mode="outlined"
            onPress={handleManualInput}
            style={styles.manualButton}
            labelStyle={styles.manualButtonLabel}
            icon="keyboard"
            disabled={resolving || cameraActive}
          >
            手动输入
          </Button>
        </View>

        {/* 提示 */}
        <View style={styles.tips}>
          <Text style={styles.tipTitle}>扫码说明</Text>
          <View style={styles.tipItem}>
            <MaterialCommunityIcons name="check-circle" size={16} color="#4CAF50" />
            <Text style={styles.tipText}>
              {mode === "inbound" ? "对准 PDF 供货单上的二维码 / Code128 条码" : "对准批次标签条码"}
            </Text>
          </View>
          <View style={styles.tipItem}>
            <MaterialCommunityIcons name="check-circle" size={16} color="#4CAF50" />
            <Text style={styles.tipText}>识别成功后自动跳入库录入页 (仅 2 字段)</Text>
          </View>
          <View style={styles.tipItem}>
            <MaterialCommunityIcons name="check-circle" size={16} color="#4CAF50" />
            <Text style={styles.tipText}>如无法扫码, 可点击"手动输入"</Text>
          </View>
        </View>
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: "#f5f5f5" },
  header: {
    flexDirection: "row",
    alignItems: "center",
    paddingHorizontal: 16,
    paddingVertical: 12,
  },
  backButton: { padding: 4 },
  headerTitle: {
    flex: 1,
    fontSize: 18,
    fontWeight: "600",
    color: "#fff",
    textAlign: "center",
    marginRight: 28,
  },
  headerRight: { width: 28 },
  content: { flex: 1, padding: 16 },
  scanArea: { alignItems: "center", marginVertical: 24 },
  scanFrame: {
    width: 240,
    height: 240,
    borderWidth: 3,
    borderRadius: 16,
    alignItems: "center",
    justifyContent: "center",
    backgroundColor: "#fff",
    overflow: "hidden",
  },
  scanHint: { marginTop: 16, fontSize: 14, color: "#666" },
  statsCard: {
    backgroundColor: "#fff",
    borderRadius: 12,
    padding: 16,
    marginBottom: 24,
  },
  statsRow: { flexDirection: "row", alignItems: "center" },
  statsItem: { flex: 1, alignItems: "center" },
  statsDivider: { width: 1, height: 40, backgroundColor: "#e0e0e0" },
  statsValue: { fontSize: 32, fontWeight: "bold" },
  statsLabel: { fontSize: 12, color: "#999", marginTop: 4 },
  lastScanText: { fontSize: 14, color: "#333", fontFamily: "monospace" },
  actions: { gap: 12, marginBottom: 24 },
  scanButton: { borderRadius: 8, paddingVertical: 8 },
  scanButtonLabel: { fontSize: 16, fontWeight: "600", color: "#fff" },
  manualButton: { borderRadius: 8, borderColor: "#ddd" },
  manualButtonLabel: { color: "#666" },
  tips: { backgroundColor: "#fff", borderRadius: 12, padding: 16 },
  tipTitle: { fontSize: 14, fontWeight: "600", color: "#333", marginBottom: 12 },
  tipItem: { flexDirection: "row", alignItems: "center", gap: 8, marginBottom: 8 },
  tipText: { fontSize: 13, color: "#666", flex: 1 },
});

export default WHScanOperationScreen;
