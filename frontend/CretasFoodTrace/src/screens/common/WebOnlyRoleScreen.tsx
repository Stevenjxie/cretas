/**
 * 非仓管角色的引导屏。
 *
 * 2026-08-02 Steve 拍板: **以 Web 端为主, RN App 只做仓管**。
 * 本屏是第一步 —— 不删任何代码, 只把非仓管角色从各自的 Navigator 改道到这里,
 * 一处改动、完全可回退。等 Web 端补齐确认后, 再分批删除那些 Navigator 和屏。
 *
 * 事实依据 (prod 实测): 全库 device_registrations 只有 2 条, 都是 F001 测试号
 * factory_admin1, 停在 2025-12-31 —— RN App 在生产上基本没有真实用户,
 * 所以改道的实际影响面很小。
 *
 * 防呆 Rule 5 (Dead-end 改导航): 不能只说"你不能用", 必须告诉用户去哪。
 * 这里给出 Web 地址 + 一键复制 + 退出登录换号。
 */

import React, { useState } from 'react';
import { StyleSheet, ScrollView, Platform } from 'react-native';
import { Text, Button, Card } from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import * as Clipboard from 'expo-clipboard';
import { useAuthStore } from '../../store/authStore';
import { ROLE_METADATA } from '../../types/auth';

const WEB_ADMIN_URL = 'https://admin.cretaceousfuture.com';

export default function WebOnlyRoleScreen() {
  const { user, logout } = useAuthStore();
  const [copied, setCopied] = useState(false);

  const roleCode =
    (user as { role?: string; factoryUser?: { role?: string } } | null)?.role ??
    (user as { factoryUser?: { role?: string } } | null)?.factoryUser?.role ??
    '';
  const roleName = ROLE_METADATA[roleCode]?.displayName || roleCode || '当前角色';

  const handleCopy = async () => {
    try {
      await Clipboard.setStringAsync(WEB_ADMIN_URL);
      setCopied(true);
      setTimeout(() => setCopied(false), 2500);
    } catch {
      // 复制失败不阻断 —— 地址本身就显示在屏幕上, 用户可以手抄。
      setCopied(false);
    }
  };

  return (
    <SafeAreaView style={styles.container} edges={['top', 'bottom']}>
      <ScrollView contentContainerStyle={styles.content}>
        <MaterialCommunityIcons name="monitor-dashboard" size={72} color="#4CAF50" />

        <Text style={styles.title}>请使用电脑端</Text>
        <Text style={styles.subtitle}>
          手机 App 目前只提供<Text style={styles.strong}>仓储管理</Text>功能。
          {'\n'}
          <Text style={styles.strong}>{roleName}</Text>的功能请在电脑上使用管理后台。
        </Text>

        <Card style={styles.card} mode="outlined">
          <Card.Content>
            <Text style={styles.cardLabel}>管理后台地址</Text>
            <Text selectable style={styles.url}>
              {WEB_ADMIN_URL}
            </Text>
            {Platform.OS !== 'web' && (
              <Button
                mode="contained"
                icon={copied ? 'check' : 'content-copy'}
                onPress={handleCopy}
                style={styles.copyBtn}
              >
                {copied ? '已复制' : '复制地址'}
              </Button>
            )}
          </Card.Content>
        </Card>

        <Text style={styles.hint}>
          如果你同时负责仓储工作，请用仓管账号登录。
        </Text>

        <Button mode="outlined" icon="logout" onPress={logout} style={styles.logoutBtn}>
          退出登录 / 换个账号
        </Button>
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f5f7fa' },
  content: { flexGrow: 1, alignItems: 'center', justifyContent: 'center', padding: 24, gap: 16 },
  title: { fontSize: 24, fontWeight: 'bold', color: '#1f2937' },
  subtitle: { fontSize: 15, color: '#4b5563', textAlign: 'center', lineHeight: 24 },
  strong: { fontWeight: 'bold', color: '#1f2937' },
  card: { width: '100%', backgroundColor: '#fff' },
  cardLabel: { fontSize: 13, color: '#6b7280', marginBottom: 6 },
  url: { fontSize: 16, fontWeight: '600', color: '#1565c0' },
  copyBtn: { marginTop: 14, minHeight: 44 },
  hint: { fontSize: 13, color: '#6b7280', textAlign: 'center' },
  logoutBtn: { marginTop: 8, minHeight: 44, width: '100%' },
});
