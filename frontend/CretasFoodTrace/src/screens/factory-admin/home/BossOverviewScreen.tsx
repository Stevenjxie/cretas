import React from 'react';
import { StyleSheet, View } from 'react-native';
import { Appbar, Button, Card, Text } from 'react-native-paper';
import { useNavigation } from '@react-navigation/native';

type BossRoute = 'BossProductionPlans' | 'BossSalesOrders' | 'BossPurchaseOrders' | 'BossFinishedGoods';

export default function BossOverviewScreen() {
  const navigation = useNavigation();
  const open = (route: BossRoute) => navigation.navigate(route as never);

  return (
    <View style={styles.container}>
      <Appbar.Header>
        <Appbar.Content title="经营总览" subtitle="查看进度与异常" />
      </Appbar.Header>
      <Card style={styles.notice}>
        <Card.Content>
          <Text style={styles.noticeTitle}>查看与决策，不替岗位录入</Text>
          <Text style={styles.noticeText}>
            业务事实由销售、采购、生产和仓储岗位填写；需要您处理的事项会进入“审批”待办。
          </Text>
        </Card.Content>
      </Card>
      <View style={styles.grid}>
        <Button mode="contained-tonal" icon="factory" onPress={() => open('BossProductionPlans')} style={styles.button}>生产进度</Button>
        <Button mode="contained-tonal" icon="cart-outline" onPress={() => open('BossSalesOrders')} style={styles.button}>销售订单</Button>
        <Button mode="contained-tonal" icon="package-down" onPress={() => open('BossPurchaseOrders')} style={styles.button}>采购订单</Button>
        <Button mode="contained-tonal" icon="package-variant-closed" onPress={() => open('BossFinishedGoods')} style={styles.button}>成品库存</Button>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#F5F7FA' },
  notice: { margin: 16, backgroundColor: '#E8F0FE' },
  noticeTitle: { color: '#174EA6', fontWeight: '700', marginBottom: 4 },
  noticeText: { color: '#315A8A', lineHeight: 20 },
  grid: { paddingHorizontal: 16, gap: 12 },
  button: { minHeight: 52, justifyContent: 'center' },
});
