/**
 * UpdateOverlay — 启动时 OTA 自动更新遮罩
 *
 * 显示时机: App 启动检测到可用更新，下载期间全屏展示。
 * 防呆设计: 大字体 + 居中 spinner，无按钮（自动）。
 * 受众: 低文化素质的仓管员 / 操作员 — 字号 24+，文案简洁。
 */
import React from 'react';
import {
  View,
  Text,
  ActivityIndicator,
  StyleSheet,
  Image,
  StatusBar,
} from 'react-native';

const UpdateOverlay: React.FC = () => (
  <View style={styles.container}>
    <StatusBar translucent backgroundColor="transparent" barStyle="light-content" />

    {/* App 图标 */}
    <Image
      source={require('../../../assets/icon.png')}
      style={styles.logo}
      resizeMode="contain"
    />

    {/* 主提示文字 — 24px+，低文化素质友好 */}
    <Text style={styles.titleText}>正在更新到最新版</Text>
    <Text style={styles.subtitleText}>请稍候，更新完成后自动继续…</Text>

    {/* 居中 spinner */}
    <ActivityIndicator
      size="large"
      color="#FFFFFF"
      style={styles.spinner}
    />
  </View>
);

export default UpdateOverlay;

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#2d5016',       // 与 splash 背景色一致，视觉无缝衔接
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 32,
  },
  logo: {
    width: 100,
    height: 100,
    marginBottom: 32,
    borderRadius: 20,
  },
  titleText: {
    fontSize: 26,                      // 大字号：低文化素质防呆
    fontWeight: '700',
    color: '#FFFFFF',
    textAlign: 'center',
    marginBottom: 12,
  },
  subtitleText: {
    fontSize: 16,
    color: 'rgba(255,255,255,0.8)',
    textAlign: 'center',
    marginBottom: 40,
  },
  spinner: {
    marginTop: 8,
  },
});
