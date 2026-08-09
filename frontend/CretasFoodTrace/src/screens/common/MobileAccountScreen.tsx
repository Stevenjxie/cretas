import React from 'react';
import { StyleSheet, View } from 'react-native';
import { Appbar, Button, Card, Text } from 'react-native-paper';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useAuthStore } from '../../store/authStore';
import { getUserRole, ROLE_METADATA } from '../../types/auth';

export default function MobileAccountScreen() {
  const insets = useSafeAreaInsets();
  const user = useAuthStore((state) => state.user);
  const logout = useAuthStore((state) => state.logout);
  const role = getUserRole(user);

  return (
    <View style={[styles.container, { paddingBottom: insets.bottom }]}>
      <Appbar.Header>
        <Appbar.Content title="我的" />
      </Appbar.Header>
      <Card style={styles.card}>
        <Card.Content>
          <Text variant="titleLarge">{user?.fullName || user?.username || '当前账号'}</Text>
          <Text style={styles.role}>{ROLE_METADATA[role]?.displayName || role || '角色未配置'}</Text>
          <Text style={styles.hint}>移动端只展示与当前岗位匹配的工作入口。</Text>
          <Button mode="outlined" onPress={logout} style={styles.logout}>退出登录</Button>
        </Card.Content>
      </Card>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#F5F5F5' },
  card: { margin: 16 },
  role: { color: '#475467', marginTop: 6 },
  hint: { color: '#667085', lineHeight: 20, marginTop: 18 },
  logout: { marginTop: 24, minHeight: 48, justifyContent: 'center' },
});
