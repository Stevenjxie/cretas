import React from 'react';
import { StyleSheet, View } from 'react-native';
import { Button, Card, Text } from 'react-native-paper';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useAuthStore } from '../store/authStore';

export default function RestrictedRoleNavigator() {
  const insets = useSafeAreaInsets();
  const logout = useAuthStore((state) => state.logout);

  return (
    <View style={[styles.container, { paddingTop: insets.top + 24, paddingBottom: insets.bottom + 24 }]}>
      <Card style={styles.card}>
        <Card.Content>
          <Text variant="headlineSmall" style={styles.title}>暂无可用的移动工作台</Text>
          <Text style={styles.body}>
            当前账号角色尚未配置 RN 工作路径。请联系管理员核对角色；系统不会自动开放通用业务入口。
          </Text>
          <Button mode="contained" onPress={logout} style={styles.button}>退出登录</Button>
        </Card.Content>
      </Card>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, justifyContent: 'center', backgroundColor: '#F5F5F5', paddingHorizontal: 20 },
  card: { width: '100%' },
  title: { marginBottom: 12 },
  body: { color: '#667085', lineHeight: 22 },
  button: { marginTop: 24, minHeight: 48, justifyContent: 'center' },
});
