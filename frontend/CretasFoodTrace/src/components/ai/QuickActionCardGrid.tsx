import React from 'react';
import { View, TouchableOpacity, Text, StyleSheet } from 'react-native';
import { MaterialCommunityIcons } from '@expo/vector-icons';

interface QuickActionCardGridProps {
  userRole: string;
  isRestaurantMode?: boolean;
  onSendIntent: (text: string) => void;
  onNavigate: (screen: string, params?: Record<string, unknown>) => void;
}

const RESTAURANT_CARDS = ({ onSendIntent }: QuickActionCardGridProps): CardConfig[] => [
  { icon: 'cash-register', label: '今日经营', description: '营收、订单与客单价', color: '#E85D04', action: () => onSendIntent('对比今天与昨天的营收、订单量和客单价，说明变化、数据依据和需要关注的问题') },
  { icon: 'store-search', label: '门店对比', description: '找出异常门店', color: '#2563EB', action: () => onSendIntent('比较最近7天各门店的营收、订单量和客单价，找出异常门店并说明依据') },
  { icon: 'food', label: '菜品表现', description: '销量与毛利分析', color: '#16A34A', action: () => onSendIntent('分析最近7天菜品销量和毛利表现，指出最值得关注的菜品并给出可执行建议') },
  { icon: 'chart-donut', label: '损耗分析', description: '定位食材损耗', color: '#DC2626', action: () => onSendIntent('分析本月食材损耗，按食材和门店定位主要问题，并给出今天可以执行的改进动作') },
  { icon: 'package-variant', label: '库存风险', description: '缺货与积压预警', color: '#7C3AED', action: () => onSendIntent('分析当前食材库存风险，列出可能缺货或积压的食材、影响和处理建议') },
  { icon: 'chef-hat', label: '经营建议', description: '让 AI 综合诊断', color: '#0891B2', action: () => onSendIntent('基于最近7天经营数据做一次综合诊断，按优先级给出三条有数据依据的行动建议') },
];

interface CardConfig {
  icon: keyof typeof MaterialCommunityIcons.glyphMap;
  label: string;
  description: string;
  color: string;
  action: () => void;
}

const ROLE_CARDS: Record<string, (props: QuickActionCardGridProps) => CardConfig[]> = {
  factory_super_admin: ({ onSendIntent, onNavigate }) => [
    { icon: 'clipboard-text', label: '生产报工', description: '记录产量和用料', color: '#4CAF50', action: () => onSendIntent('我要报工') },
    { icon: 'package-down', label: '原料入库', description: '扫码或手动入库', color: '#2196F3', action: () => onSendIntent('原料入库') },
    { icon: 'magnify', label: '查库存', description: '查看物料库存', color: '#FF9800', action: () => onSendIntent('查看库存') },
    { icon: 'chart-bar', label: 'BOM达成率', description: '投入产出对比', color: '#9C27B0', action: () => onNavigate('BomAchievement') },
    { icon: 'currency-cny', label: '成本分析', description: '查看生产成本', color: '#F44336', action: () => onSendIntent('查看成本') },
    { icon: 'trending-up', label: '毛利率', description: 'SKU盈利分析', color: '#009688', action: () => onSendIntent('查毛利率') },
  ],
  workshop_supervisor: ({ onSendIntent, onNavigate }) => [
    { icon: 'clipboard-text', label: '生产报工', description: '记录产量', color: '#4CAF50', action: () => onSendIntent('我要报工') },
    { icon: 'format-list-checks', label: '我的任务', description: '查看工序任务', color: '#2196F3', action: () => onNavigate('ProcessTaskList') },
    { icon: 'account-group', label: '人员状态', description: '查看在岗人员', color: '#FF9800', action: () => onSendIntent('查看人员') },
    { icon: 'wrench', label: '设备状态', description: '查看设备运行', color: '#9C27B0', action: () => onSendIntent('查看设备') },
  ],
  warehouse_manager: ({ onSendIntent, onNavigate }) => [
    { icon: 'package-down', label: '原料入库', description: '扫码或手动入库', color: '#2196F3', action: () => onNavigate('MaterialReceiptAI') },
    { icon: 'package-up', label: '出库发货', description: '发货出库', color: '#4CAF50', action: () => onSendIntent('出库') },
    { icon: 'magnify', label: '查库存', description: '查看物料库存', color: '#FF9800', action: () => onSendIntent('查看库存') },
    { icon: 'alert', label: '低库存', description: '库存预警查看', color: '#F44336', action: () => onSendIntent('低库存预警') },
  ],
};

const QuickActionCardGrid: React.FC<QuickActionCardGridProps> = (props) => {
  const { userRole, isRestaurantMode } = props;
  const configFn = isRestaurantMode ? RESTAURANT_CARDS : ROLE_CARDS[userRole];
  if (!configFn) return null;

  const cards = configFn(props);

  return (
    <View testID="quick-action-card-grid" style={styles.grid}>
      {cards.map((card, index) => (
        <TouchableOpacity
          key={index}
          testID={`quick-action-card-${index}`}
          style={styles.card}
          activeOpacity={0.7}
          onPress={card.action}
        >
          <View style={[styles.iconContainer, { backgroundColor: card.color + '15' }]}>
            <MaterialCommunityIcons name={card.icon} size={40} color={card.color} />
          </View>
          <Text testID={`quick-action-label-${index}`} style={styles.label} numberOfLines={1}>{card.label}</Text>
          <Text style={styles.description} numberOfLines={2}>{card.description}</Text>
        </TouchableOpacity>
      ))}
    </View>
  );
};

const styles = StyleSheet.create({
  grid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 12,
  },
  card: {
    flex: 1,
    minWidth: '45%',
    maxWidth: '50%',
    height: 160,
    backgroundColor: '#ffffff',
    borderRadius: 16,
    borderWidth: 1,
    borderColor: '#e8e8e8',
    paddingVertical: 20,
    paddingHorizontal: 16,
    alignItems: 'center',
    justifyContent: 'center',
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 1 },
    shadowOpacity: 0.06,
    shadowRadius: 4,
    elevation: 2,
  },
  iconContainer: {
    width: 64,
    height: 64,
    borderRadius: 16,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 12,
  },
  label: {
    fontSize: 16,
    fontWeight: '700',
    color: '#1a1a1a',
    marginBottom: 4,
  },
  description: {
    fontSize: 12,
    color: '#888888',
    textAlign: 'center',
  },
});

export { QuickActionCardGrid };
export default QuickActionCardGrid;
