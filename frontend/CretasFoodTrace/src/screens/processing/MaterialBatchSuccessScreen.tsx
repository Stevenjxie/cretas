/**
 * MaterialBatchSuccessScreen - AI 入库成功确认页
 *
 * 在 AI 辅助入库完成后展示入库结果摘要。
 * 模式参照 ScanReportSuccessScreen。
 */

import React from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useNavigation, useRoute, RouteProp } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { ProcessingStackParamList } from '../../types/navigation';

type ScreenRouteProp = RouteProp<ProcessingStackParamList, 'MaterialBatchSuccess'>;
type ScreenNavProp = NativeStackNavigationProp<ProcessingStackParamList, 'MaterialBatchSuccess'>;

const MaterialBatchSuccessScreen: React.FC = () => {
  const navigation = useNavigation<ScreenNavProp>();
  const route = useRoute<ScreenRouteProp>();
  const { batchNumber, materialName, quantity, supplierName } = route.params;

  const handleContinueReceipt = () => {
    // 原本 replace('MaterialReceiptAI') —— 等于入库成功后又把人送回那个提交必 409 的屏。
    // 工厂侧没有可用的入库入口了, 回批次列表是同树内唯一有意义的去处。
    navigation.replace('MaterialBatchManagement');
  };

  const handleViewBatches = () => {
    navigation.replace('MaterialBatchManagement');
  };

  return (
    <SafeAreaView style={styles.container} edges={['top']} testID="material-batch-success-screen">
      <View style={styles.content}>
        {/* Success icon */}
        <View style={styles.iconContainer}>
          <MaterialCommunityIcons name="check-circle" size={80} color="#10B981" />
        </View>

        <Text style={styles.title}>入库成功</Text>
        <Text style={styles.subtitle}>原材料已成功入库</Text>

        {/* Summary card */}
        <View style={styles.summaryCard}>
          <Text style={styles.cardTitle}>入库摘要</Text>

          <View style={styles.row}>
            <Text style={styles.label}>批次号</Text>
            <Text style={styles.value}>{batchNumber}</Text>
          </View>

          <View style={styles.divider} />

          <View style={styles.row}>
            <Text style={styles.label}>物料名称</Text>
            <Text style={styles.valueHighlight}>{materialName || '-'}</Text>
          </View>

          <View style={styles.divider} />

          <View style={styles.row}>
            <Text style={styles.label}>数量</Text>
            <Text style={[styles.value, { color: '#10B981' }]}>{quantity}</Text>
          </View>

          <View style={styles.divider} />

          <View style={styles.row}>
            <Text style={styles.label}>供应商</Text>
            <Text style={styles.value}>{supplierName || '-'}</Text>
          </View>
        </View>

        {/* Action buttons */}
        <View style={styles.actions}>
          <TouchableOpacity style={styles.primaryButton} onPress={handleContinueReceipt}>
            <MaterialCommunityIcons name="package-down" size={20} color="#fff" />
            <Text style={styles.primaryButtonText}>继续入库</Text>
          </TouchableOpacity>

          <TouchableOpacity style={styles.secondaryButton} onPress={handleViewBatches}>
            <MaterialCommunityIcons name="format-list-bulleted" size={20} color="#4F46E5" />
            <Text style={styles.secondaryButtonText}>查看详情</Text>
          </TouchableOpacity>
        </View>
      </View>
    </SafeAreaView>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f5f7fa',
  },
  content: {
    flex: 1,
    paddingHorizontal: 24,
    paddingTop: 48,
    alignItems: 'center',
  },
  iconContainer: {
    marginBottom: 16,
  },
  title: {
    fontSize: 24,
    fontWeight: '700',
    color: '#1a1a1a',
    marginBottom: 8,
  },
  subtitle: {
    fontSize: 15,
    color: '#666',
    marginBottom: 32,
  },
  summaryCard: {
    width: '100%',
    backgroundColor: '#fff',
    borderRadius: 12,
    padding: 20,
    marginBottom: 32,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.06,
    shadowRadius: 8,
    elevation: 3,
  },
  cardTitle: {
    fontSize: 16,
    fontWeight: '600',
    color: '#333',
    marginBottom: 16,
  },
  row: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: 10,
  },
  divider: {
    height: 1,
    backgroundColor: '#f0f0f0',
  },
  label: {
    fontSize: 14,
    color: '#666',
  },
  value: {
    fontSize: 14,
    fontWeight: '500',
    color: '#1a1a1a',
  },
  valueHighlight: {
    fontSize: 16,
    fontWeight: '700',
    color: '#4F46E5',
  },
  actions: {
    width: '100%',
    gap: 12,
  },
  primaryButton: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#4F46E5',
    paddingVertical: 16,
    borderRadius: 12,
    gap: 8,
  },
  primaryButtonText: {
    color: '#fff',
    fontSize: 17,
    fontWeight: '600',
  },
  secondaryButton: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#fff',
    paddingVertical: 16,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: '#4F46E5',
    gap: 8,
  },
  secondaryButtonText: {
    color: '#4F46E5',
    fontSize: 17,
    fontWeight: '600',
  },
});

export default MaterialBatchSuccessScreen;
