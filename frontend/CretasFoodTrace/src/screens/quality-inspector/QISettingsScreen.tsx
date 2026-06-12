/**
 * 设置页面
 * Quality Inspector - Settings Screen
 */

import React, { useState } from 'react';
import {
  View,
  Text,
  StyleSheet,
  ScrollView,
  TouchableOpacity,
  Switch,
  Alert,
} from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];
import { useTranslation } from 'react-i18next';

import { QI_COLORS } from '../../types/qualityInspector';

interface SettingItem {
  id: string;
  icon: IoniconsName;
  label: string;
  type: 'toggle' | 'action' | 'value';
  value?: boolean | string;
  onToggle?: (value: boolean) => void;
  onPress?: () => void;
}

export default function QISettingsScreen() {
  const insets = useSafeAreaInsets();
  const { t } = useTranslation('quality');

  const [settings, setSettings] = useState({
    voiceAssistant: true,
    autoNextBatch: false,
    soundEffect: true,
    vibration: true,
    pushNotification: true,
    autoSync: true,
    darkMode: false,
    // biometricLock removed — feature not yet implemented (Phase 4)
  });

  const handleToggle = (key: keyof typeof settings) => {
    setSettings((prev) => ({
      ...prev,
      [key]: !prev[key],
    }));
  };

  const handleClearCache = () => {
    Alert.alert(t('settings.clearCache'), t('settings.confirmClearCache'), [
      { text: t('settings.cancel'), style: 'cancel' },
      {
        text: t('settings.confirm'),
        onPress: () => {
          Alert.alert(t('settings.success'), t('settings.cacheCleared'));
        },
      },
    ]);
  };

  const settingsGroups: { title: string; items: SettingItem[] }[] = [
    {
      title: t('settings.qualitySettings'),
      items: [
        {
          id: 'voiceAssistant',
          icon: 'mic-outline',
          label: t('settings.voiceAssistant'),
          type: 'toggle',
          value: settings.voiceAssistant,
          onToggle: () => handleToggle('voiceAssistant'),
        },
        {
          id: 'autoNextBatch',
          icon: 'arrow-forward-circle-outline',
          label: t('settings.autoNextBatch'),
          type: 'toggle',
          value: settings.autoNextBatch,
          onToggle: () => handleToggle('autoNextBatch'),
        },
        {
          id: 'defaultSampleSize',
          icon: 'layers-outline',
          label: t('settings.defaultSampleSize'),
          type: 'value',
          value: t('settings.sampleSizeValue'),
          onPress: () => Alert.alert(t('settings.setSampleSize'), t('settings.inDevelopment')),
        },
      ],
    },
    {
      title: t('settings.notificationSettings'),
      items: [
        {
          id: 'pushNotification',
          icon: 'notifications-outline',
          label: t('settings.pushNotification'),
          type: 'toggle',
          value: settings.pushNotification,
          onToggle: () => handleToggle('pushNotification'),
        },
        {
          id: 'soundEffect',
          icon: 'volume-medium-outline',
          label: t('settings.soundEffect'),
          type: 'toggle',
          value: settings.soundEffect,
          onToggle: () => handleToggle('soundEffect'),
        },
        {
          id: 'vibration',
          icon: 'phone-portrait-outline',
          label: t('settings.vibration'),
          type: 'toggle',
          value: settings.vibration,
          onToggle: () => handleToggle('vibration'),
        },
      ],
    },
    {
      title: t('settings.dataSync'),
      items: [
        {
          id: 'autoSync',
          icon: 'sync-outline',
          label: t('settings.autoSync'),
          type: 'toggle',
          value: settings.autoSync,
          onToggle: () => handleToggle('autoSync'),
        },
        {
          id: 'syncNow',
          icon: 'cloud-upload-outline',
          label: t('settings.syncNow'),
          type: 'action',
          onPress: () => Alert.alert(t('settings.sync'), t('settings.syncing')),
        },
        {
          id: 'lastSync',
          icon: 'time-outline',
          label: t('settings.lastSync'),
          type: 'value',
          value: t('settings.lastSyncTime'),
        },
      ],
    },
    {
      title: t('settings.securityPrivacy'),
      items: [
        // Rule 1/5: 生物识别锁定功能尚未实现 (Phase 4 计划)。
        // 隐藏该选项，防止用户勾选后发现没有实际效果。
        // TODO Phase 4: 实现生物识别后重新开启此选项。
        {
          id: 'changePassword',
          icon: 'key-outline',
          label: t('settings.changePassword'),
          type: 'action',
          onPress: () => Alert.alert(t('settings.changePassword'), t('settings.inDevelopment')),
        },
      ],
    },
    {
      title: t('settings.other'),
      items: [
        {
          id: 'darkMode',
          icon: 'moon-outline',
          label: t('settings.darkMode'),
          type: 'toggle',
          value: settings.darkMode,
          onToggle: () => handleToggle('darkMode'),
        },
        {
          id: 'clearCache',
          icon: 'trash-outline',
          label: t('settings.clearCache'),
          type: 'action',
          onPress: handleClearCache,
        },
        {
          id: 'version',
          icon: 'information-circle-outline',
          label: t('settings.version'),
          type: 'value',
          value: t('settings.versionNumber'),
        },
      ],
    },
  ];

  const renderSettingItem = (item: SettingItem) => (
    <TouchableOpacity
      key={item.id}
      style={styles.settingItem}
      onPress={item.type === 'action' || item.type === 'value' ? item.onPress : undefined}
      activeOpacity={item.type === 'toggle' ? 1 : 0.7}
      disabled={item.type === 'toggle'}
    >
      <View style={styles.settingLeft}>
        <Ionicons name={item.icon} size={22} color={QI_COLORS.textSecondary} />
        <Text style={styles.settingLabel}>{item.label}</Text>
      </View>
      <View style={styles.settingRight}>
        {item.type === 'toggle' && (
          <Switch
            value={item.value as boolean}
            onValueChange={item.onToggle}
            trackColor={{ false: QI_COLORS.border, true: '#81C784' }}
            thumbColor={item.value ? QI_COLORS.primary : '#f4f3f4'}
            ios_backgroundColor={QI_COLORS.border}
          />
        )}
        {item.type === 'value' && (
          <>
            <Text style={styles.settingValue}>{item.value}</Text>
            {item.onPress && (
              <Ionicons name="chevron-forward" size={20} color={QI_COLORS.disabled} />
            )}
          </>
        )}
        {item.type === 'action' && (
          <Ionicons name="chevron-forward" size={20} color={QI_COLORS.disabled} />
        )}
      </View>
    </TouchableOpacity>
  );

  return (
    <ScrollView
      style={styles.container}
      contentContainerStyle={[styles.content, { paddingBottom: insets.bottom + 20 }]}
    >
      {settingsGroups.map((group) => (
        <View key={group.title} style={styles.settingGroup}>
          <Text style={styles.groupTitle}>{group.title}</Text>
          <View style={styles.groupCard}>
            {group.items.map(renderSettingItem)}
          </View>
        </View>
      ))}

      {/* 重置设置 */}
      <TouchableOpacity
        style={styles.resetBtn}
        onPress={() =>
          Alert.alert(t('settings.resetSettings'), t('settings.confirmReset'), [
            { text: t('settings.cancel'), style: 'cancel' },
            {
              text: t('settings.confirm'),
              style: 'destructive',
              onPress: () => {
                setSettings({
                  voiceAssistant: true,
                  autoNextBatch: false,
                  soundEffect: true,
                  vibration: true,
                  pushNotification: true,
                  autoSync: true,
                  darkMode: false,
                });
                Alert.alert(t('settings.success'), t('settings.settingsReset'));
              },
            },
          ])
        }
      >
        <Text style={styles.resetText}>{t('settings.restoreDefaults')}</Text>
      </TouchableOpacity>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: QI_COLORS.background,
  },
  content: {
    padding: 16,
  },

  // 设置组
  settingGroup: {
    marginBottom: 24,
  },
  groupTitle: {
    fontSize: 13,
    color: QI_COLORS.textSecondary,
    marginBottom: 8,
    marginLeft: 4,
    textTransform: 'uppercase',
  },
  groupCard: {
    backgroundColor: QI_COLORS.card,
    borderRadius: 12,
    overflow: 'hidden',
  },

  // 设置项
  settingItem: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    paddingVertical: 14,
    borderBottomWidth: 1,
    borderBottomColor: QI_COLORS.border,
  },
  settingLeft: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
  },
  settingLabel: {
    fontSize: 15,
    color: QI_COLORS.text,
  },
  settingRight: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  settingValue: {
    fontSize: 14,
    color: QI_COLORS.textSecondary,
  },

  // 重置按钮
  resetBtn: {
    alignItems: 'center',
    paddingVertical: 14,
    marginTop: 8,
  },
  resetText: {
    fontSize: 14,
    color: QI_COLORS.danger,
  },
});
