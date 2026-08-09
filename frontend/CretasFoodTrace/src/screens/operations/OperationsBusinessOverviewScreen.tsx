import React from 'react';
import { StyleSheet, View } from 'react-native';
import { Appbar, Button, Card, Text } from 'react-native-paper';
import { useNavigation } from '@react-navigation/native';

type BusinessRoute =
  | 'OperationsProductionPlans'
  | 'OperationsSalesOrders'
  | 'OperationsPurchaseOrders'
  | 'OperationsFinishedGoods';

export default function OperationsBusinessOverviewScreen() {
  const navigation = useNavigation();
  const open = (route: BusinessRoute) => navigation.navigate(route as never);

  return (
    <View style={styles.container}>
      <Appbar.Header>
        <Appbar.Content title="业务查看" subtitle="跨部门进度，只读" />
      </Appbar.Header>
      <Card style={styles.notice}>
        <Card.Content>
          <Text style={styles.noticeTitle}>这里不修改业务事实</Text>
          <Text style={styles.noticeText}>
            运营可查看销售、采购、生产和成品库存进度；需要处理时交给对应岗位。
          </Text>
        </Card.Content>
      </Card>
      <View style={styles.grid}>
        <Button mode="contained-tonal" icon="factory" onPress={() => open('OperationsProductionPlans')} style={styles.button}>
          生产进度
        </Button>
        <Button mode="contained-tonal" icon="cart-outline" onPress={() => open('OperationsSalesOrders')} style={styles.button}>
          销售订单
        </Button>
        <Button mode="contained-tonal" icon="package-down" onPress={() => open('OperationsPurchaseOrders')} style={styles.button}>
          采购订单
        </Button>
        <Button mode="contained-tonal" icon="package-variant-closed" onPress={() => open('OperationsFinishedGoods')} style={styles.button}>
          成品库存
        </Button>
      </View>
      <Text style={styles.handoff}>发现异常后，请联系页面对应的生产、销售、采购或仓储负责人处理。</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#F5F7F5' },
  notice: { margin: 16, backgroundColor: '#E8F5E9' },
  noticeTitle: { color: '#1B5E20', fontWeight: '700', marginBottom: 4 },
  noticeText: { color: '#2E5D35', lineHeight: 20 },
  grid: { paddingHorizontal: 16, gap: 12 },
  button: { minHeight: 52, justifyContent: 'center' },
  handoff: { color: '#667085', lineHeight: 20, margin: 20, textAlign: 'center' },
});
