import React, { useState } from 'react';
import {
  View,
  ScrollView,
  StyleSheet,
  Alert,
  ActivityIndicator,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { Text, Card, Button } from 'react-native-paper';
import { isAxiosError } from 'axios';
import { materialTypeApiClient } from '../../services/api/materialTypeApiClient';
import { supplierApiClient } from '../../services/api/supplierApiClient';
import { processingApiClient } from '../../services/api/processingApiClient';
import { materialBatchApiClient, MaterialBatch } from '../../services/api/materialBatchApiClient';

/**
 * Type guard helpers for API responses
 */
interface WithId {
  id?: string | number;
}

interface WrappedResponse<T> {
  data?: T;
}

/**
 * Extracts ID from API response (handles both wrapped and unwrapped formats)
 * Edge cases handled:
 * - null/undefined response -> ''
 * - empty object {} -> ''
 * - data: null -> ''
 * - data: { id: null } -> ''
 * - id at root level -> extracted
 * - non-object response -> ''
 */
function extractId(response: unknown): string {
  if (response === null || response === undefined) {
    return '';
  }
  if (typeof response !== 'object') {
    return '';
  }
  const res = response as WrappedResponse<WithId> & WithId;
  const id = res.data?.id ?? res.id;
  // Return empty string for null, undefined, or empty values
  if (id === null || id === undefined || id === '') {
    return '';
  }
  return String(id);
}

/**
 * Type-safe error message extraction
 * Edge cases handled:
 * - Axios error with response -> data.message
 * - Axios error without response -> error.message
 * - Standard Error object -> error.message
 * - String error -> string directly (no double-quoting)
 * - null/undefined -> 'Unknown error'
 * - Object without message -> JSON stringified
 */
function getErrorMessage(error: unknown): string {
  // Handle null/undefined
  if (error === null || error === undefined) {
    return 'Unknown error';
  }
  // Handle Axios errors
  if (isAxiosError(error)) {
    const axiosMessage = error.response?.data?.message;
    if (typeof axiosMessage === 'string') {
      return axiosMessage;
    }
    return error.message;
  }
  // Handle standard Error objects
  if (error instanceof Error) {
    return error.message;
  }
  // Handle string errors directly (no JSON.stringify to avoid double-quoting)
  if (typeof error === 'string') {
    return error;
  }
  // Handle objects with message property
  if (typeof error === 'object' && 'message' in error) {
    const msg = (error as { message: unknown }).message;
    if (typeof msg === 'string') {
      return msg;
    }
  }
  // Fallback to JSON for other types
  return JSON.stringify(error);
}

/**
 * Type guard for MaterialBatch response
 * Edge cases handled:
 * - Valid MaterialBatch response -> extracted
 * - Response with data: null -> null
 * - Response without data property -> checks root level
 * - Response with partial batch data -> returns it (caller handles validation)
 * - Non-object response -> null
 * - null/undefined response -> null
 */
function extractBatchData(response: unknown): MaterialBatch | null {
  // Handle null/undefined/non-object
  if (response === null || response === undefined) {
    return null;
  }
  if (typeof response !== 'object') {
    return null;
  }

  const res = response as WrappedResponse<MaterialBatch> & MaterialBatch;

  // Check wrapped format: { data: MaterialBatch }
  if (res.data && typeof res.data === 'object') {
    return res.data as MaterialBatch;
  }

  // Check unwrapped format: MaterialBatch at root (identified by remainingQuantity field)
  if (res.remainingQuantity !== undefined) {
    return res as MaterialBatch;
  }

  return null;
}

/**
 * 批次操作测试页面
 * 测试完整的业务流程：
 * 1. 创建原材料类型
 * 2. 创建供应商
 * 3. 原材料入库
 * 4. 预留批次
 * 5. 消耗批次
 * 6. 释放预留
 */

export const BatchOperationsTestScreen = () => {
  const [loading, setLoading] = useState(false);
  const [logs, setLogs] = useState<string[]>([]);

  // 测试数据
  const [materialTypeId, setMaterialTypeId] = useState<string>('');
  const [supplierId, setSupplierId] = useState<string>('');
  const [batchId, setBatchId] = useState<string>('');
  const [productionPlanId] = useState<number>(1); // 固定测试用

  const addLog = (message: string) => {
    const timestamp = new Date().toLocaleTimeString();
    setLogs(prev => [...prev, `[${timestamp}] ${message}`]);
  };

  const clearLogs = () => {
    setLogs([]);
    setMaterialTypeId('');
    setSupplierId('');
    setBatchId('');
  };

  // Step 1: 创建原材料类型
  const testCreateMaterialType = async () => {
    setLoading(true);
    addLog('🚀 开始测试：创建原材料类型');

    addLog('⛔ 已停用：原料建档必须在管理页选择完整 L1-L2-L3，测试页不得绕过');
    setLoading(false);
  };

  // Step 2: 创建供应商
  const testCreateSupplier = async () => {
    setLoading(true);
    addLog('🚀 开始测试：创建供应商');

    try {
      const response = await supplierApiClient.createSupplier({
        supplierCode: `SUP_${Date.now()}`,
        name: '测试供应商',
        contactPerson: '张三',
        phone: '13800138000', // 使用phone而不是contactPhone
        address: '测试地址'
      });

      const id = extractId(response);
      setSupplierId(id);
      addLog(`✅ 成功：创建供应商 ID=${id}`);
    } catch (error) {
      addLog(`❌ 失败：${getErrorMessage(error)}`);
    } finally {
      setLoading(false);
    }
  };

  // Step 3: 原材料入库
  const testMaterialReceipt = async () => {
    if (!materialTypeId || !supplierId) {
      Alert.alert('提示', '请先创建原材料类型和供应商');
      return;
    }

    setLoading(true);
    addLog('🚀 开始测试：原材料入库（500公斤）');

    try {
      const response = await processingApiClient.recordMaterialReceipt({
        materialTypeId: materialTypeId,
        quantity: 500,
        unit: 'kg',
        supplierId: supplierId,
        receivedDate: new Date().toISOString().split('T')[0]
      });

      const id = extractId(response);
      setBatchId(id);
      addLog(`✅ 成功：原材料入库 Batch ID=${id}, 数量=500kg`);
    } catch (error) {
      addLog(`❌ 失败：${getErrorMessage(error)}`);
    } finally {
      setLoading(false);
    }
  };

  // Step 4: 预留批次
  const testReserveBatch = async () => {
    if (!batchId) {
      Alert.alert('提示', '请先完成原材料入库');
      return;
    }

    setLoading(true);
    addLog('🚀 开始测试：预留批次（300公斤）');

    try {
      await materialBatchApiClient.reserveBatch(
        batchId,
        300,
        productionPlanId
      );
      addLog(`✅ 成功：预留300kg, 剩余应为200kg`);

      // 查询确认
      const batchResponse = await materialBatchApiClient.getBatchById(batchId);
      const batchData = extractBatchData(batchResponse);
      addLog(`📊 查询结果：剩余=${batchData?.remainingQuantity ?? 'N/A'}kg, 预留=${batchData?.reservedQuantity ?? 'N/A'}kg`);
    } catch (error) {
      addLog(`❌ 失败：${getErrorMessage(error)}`);
    } finally {
      setLoading(false);
    }
  };

  // Step 5: 消耗批次
  const testConsumeBatch = async () => {
    if (!batchId) {
      Alert.alert('提示', '请先完成原材料入库和预留');
      return;
    }

    setLoading(true);
    addLog('🚀 开始测试：消耗批次（150公斤）');

    try {
      await materialBatchApiClient.consumeBatch(
        batchId,
        150,
        productionPlanId
      );
      addLog(`✅ 成功：消耗150kg`);

      // 查询确认
      const batchResponse = await materialBatchApiClient.getBatchById(batchId);
      const batchData = extractBatchData(batchResponse);
      addLog(`📊 查询结果：预留=${batchData?.reservedQuantity ?? 'N/A'}kg, 已用=${batchData?.usedQuantity ?? 'N/A'}kg`);
    } catch (error) {
      addLog(`❌ 失败：${getErrorMessage(error)}`);
    } finally {
      setLoading(false);
    }
  };

  // Step 6: 释放预留
  const testReleaseBatch = async () => {
    if (!batchId) {
      Alert.alert('提示', '请先完成原材料入库和预留');
      return;
    }

    setLoading(true);
    addLog('🚀 开始测试：释放预留（50公斤）');

    try {
      await materialBatchApiClient.releaseBatch(
        batchId,
        50,
        productionPlanId
      );
      addLog(`✅ 成功：释放50kg`);

      // 查询确认
      const batchResponse = await materialBatchApiClient.getBatchById(batchId);
      const batchData = extractBatchData(batchResponse);
      addLog(`📊 查询结果：剩余=${batchData?.remainingQuantity ?? 'N/A'}kg, 预留=${batchData?.reservedQuantity ?? 'N/A'}kg`);
    } catch (error) {
      addLog(`❌ 失败：${getErrorMessage(error)}`);
    } finally {
      setLoading(false);
    }
  };

  // 完整流程测试
  const runFullTest = async () => {
    clearLogs();
    addLog('========================================');
    addLog('🎯 开始完整流程测试');
    addLog('========================================');

    try {
      // Step 1
      await testCreateMaterialType();
      await new Promise(resolve => setTimeout(resolve, 1000));

      // Step 2
      await testCreateSupplier();
      await new Promise(resolve => setTimeout(resolve, 1000));

      // Step 3
      await testMaterialReceipt();
      await new Promise(resolve => setTimeout(resolve, 1000));

      // Step 4
      await testReserveBatch();
      await new Promise(resolve => setTimeout(resolve, 1000));

      // Step 5
      await testConsumeBatch();
      await new Promise(resolve => setTimeout(resolve, 1000));

      // Step 6
      await testReleaseBatch();

      addLog('========================================');
      addLog('✅ 完整流程测试完成！');
      addLog('========================================');
    } catch (error) {
      addLog(`❌ 流程测试失败：${getErrorMessage(error)}`);
    }
  };

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
    <ScrollView style={{ flex: 1 }}>
      <Card>
        <Card.Title title="批次操作接口测试" />
        <Card.Content>
          {loading && <ActivityIndicator size="large" color="#0066CC" style={styles.loader} />}

          <View style={styles.section}>
            <Text style={styles.sectionTitle}>单步测试</Text>

            <Button
              mode="contained"
              onPress={testCreateMaterialType}
              disabled={loading}
              style={styles.buttonContainer}
            >
              1. 创建原材料类型
            </Button>

            <Button
              mode="contained"
              onPress={testCreateSupplier}
              disabled={loading}
              style={styles.buttonContainer}
            >
              2. 创建供应商
            </Button>

            <Button
              mode="contained"
              onPress={testMaterialReceipt}
              disabled={loading || !materialTypeId || !supplierId}
              style={styles.buttonContainer}
            >
              3. 原材料入库 (500kg)
            </Button>

            <Button
              mode="contained"
              onPress={testReserveBatch}
              disabled={loading || !batchId}
              style={[styles.buttonContainer, styles.primaryButton]}
            >
              4. 预留批次 (300kg)
            </Button>

            <Button
              mode="contained"
              onPress={testConsumeBatch}
              disabled={loading || !batchId}
              style={[styles.buttonContainer, styles.primaryButton]}
            >
              5. 消耗批次 (150kg)
            </Button>

            <Button
              mode="contained"
              onPress={testReleaseBatch}
              disabled={loading || !batchId}
              style={[styles.buttonContainer, styles.primaryButton]}
            >
              6. 释放预留 (50kg)
            </Button>
          </View>

          <View style={styles.section}>
            <Text style={styles.sectionTitle}>完整流程测试</Text>

            <Button
              mode="contained"
              onPress={runFullTest}
              disabled={loading}
              style={[styles.buttonContainer, styles.fullTestButton]}
            >
              🎯 运行完整测试
            </Button>

            <Button
              mode="outlined"
              onPress={clearLogs}
              disabled={loading}
              style={[styles.buttonContainer, styles.clearButton]}
            >
              清空日志
            </Button>
          </View>

          <View style={styles.section}>
            <Text style={styles.sectionTitle}>当前测试数据</Text>
            <Text style={styles.dataText}>原材料类型ID: {materialTypeId || '未创建'}</Text>
            <Text style={styles.dataText}>供应商ID: {supplierId || '未创建'}</Text>
            <Text style={styles.dataText}>批次ID: {batchId || '未创建'}</Text>
            <Text style={styles.dataText}>生产计划ID: {productionPlanId}</Text>
          </View>
        </Card.Content>
      </Card>

      <Card style={styles.logCard}>
        <Card.Title title="测试日志" />
        <Card.Content>
          <ScrollView style={styles.logContainer}>
            {logs.map((log, index) => (
              <Text key={index} style={styles.logText}>
                {log}
              </Text>
            ))}
            {logs.length === 0 && (
              <Text style={styles.emptyText}>暂无日志</Text>
            )}
          </ScrollView>
        </Card.Content>
      </Card>
    </ScrollView>
    </SafeAreaView>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#F5F5F5',
  },
  logCard: {
    marginTop: 16,
  },
  loader: {
    marginVertical: 20,
  },
  section: {
    marginVertical: 10,
  },
  sectionTitle: {
    fontSize: 16,
    fontWeight: 'bold',
    marginBottom: 10,
    color: '#333',
  },
  buttonContainer: {
    marginVertical: 5,
  },
  primaryButton: {
    backgroundColor: '#0066CC',
  },
  fullTestButton: {
    backgroundColor: '#28A745',
  },
  clearButton: {
    borderColor: '#DC3545',
  },
  dataText: {
    fontSize: 14,
    color: '#666',
    marginVertical: 2,
  },
  logContainer: {
    maxHeight: 400,
    backgroundColor: '#F8F9FA',
    padding: 10,
    borderRadius: 5,
  },
  logText: {
    fontSize: 12,
    color: '#333',
    marginVertical: 2,
    fontFamily: 'monospace',
  },
  emptyText: {
    fontSize: 14,
    color: '#999',
    textAlign: 'center',
    marginVertical: 20,
  },
});
