/**
 * 添加白名单
 *
 * @version 1.0.0
 * @since 2025-12-29
 */

import React, { useState } from 'react';
import {
  View, ScrollView, StyleSheet, TouchableOpacity, Alert, KeyboardAvoidingView, Platform,
} from 'react-native';
import { Text, TextInput, Button, ActivityIndicator, HelperText, Menu } from 'react-native-paper';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useNavigation } from '@react-navigation/native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useTranslation } from 'react-i18next';

import { whitelistApiClient } from '../../../services/api/whitelistApiClient';
import { HR_THEME, ROLE_OPTIONS } from '../../../types/hrNavigation';

export default function WhitelistAddScreen() {
  const navigation = useNavigation();
  const { t } = useTranslation('hr');
  const [loading, setLoading] = useState(false);
  const [phoneNumber, setPhoneNumber] = useState('');
  const [realName, setRealName] = useState('');
  const [presetRole, setPresetRole] = useState('');
  const [presetRoleName, setPresetRoleName] = useState('');
  const [notes, setNotes] = useState('');
  const [menuVisible, setMenuVisible] = useState(false);
  const [phoneError, setPhoneError] = useState('');

  const validatePhone = (phone: string): boolean => {
    const phoneRegex = /^1[3-9]\d{9}$/;
    if (!phone) {
      setPhoneError(t('whitelist.add.phoneRequired'));
      return false;
    }
    if (!phoneRegex.test(phone)) {
      setPhoneError(t('whitelist.add.phoneInvalid'));
      return false;
    }
    setPhoneError('');
    return true;
  };

  const handleSubmit = async () => {
    if (!validatePhone(phoneNumber)) return;

    if (!presetRole) {
      Alert.alert(t('whitelist.add.tipTitle'), t('whitelist.add.roleRequired'));
      return;
    }
    if (!realName.trim()) {
      Alert.alert(t('whitelist.add.tipTitle'), t('whitelist.add.nameRequired'));
      return;
    }

    setLoading(true);
    try {
      const res = await whitelistApiClient.addWhitelist({
        phoneNumber,
        presetRole,
        presetRoleName,
        realName: realName.trim(),
        notes,
      });

      if (res.success) {
        Alert.alert(t('messages.success'), t('whitelist.add.success'), [
          { text: t('common.confirm'), onPress: () => navigation.goBack() }
        ]);
      } else {
        Alert.alert(t('messages.error'), res.message || t('whitelist.add.failed'));
      }
    } catch (error) {
      console.error('添加白名单失败:', error);
      Alert.alert(t('whitelist.add.errorTitle'), t('whitelist.add.failed'));
    } finally {
      setLoading(false);
    }
  };

  const selectRole = (role: { value: string; label: string }) => {
    setPresetRole(role.value);
    setPresetRoleName(role.label);
    setMenuVisible(false);
  };

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      <View style={styles.header}>
        <TouchableOpacity onPress={() => navigation.goBack()} style={styles.backBtn}>
          <MaterialCommunityIcons name="arrow-left" size={24} color={HR_THEME.textPrimary} />
        </TouchableOpacity>
        <Text style={styles.headerTitle}>{t('whitelist.add.title')}</Text>
        <View style={{ width: 32 }} />
      </View>

      <KeyboardAvoidingView
        behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
        style={styles.content}
      >
        <ScrollView showsVerticalScrollIndicator={false}>
          <View style={styles.form}>
            <View style={styles.inputGroup}>
              <Text style={styles.label}>{t('whitelist.add.phone')} *</Text>
              <TextInput
                mode="outlined"
                value={phoneNumber}
                onChangeText={(text) => {
                  setPhoneNumber(text);
                  if (phoneError) validatePhone(text);
                }}
                placeholder={t('whitelist.add.phonePlaceholder')}
                keyboardType="phone-pad"
                maxLength={11}
                style={styles.input}
                outlineColor={HR_THEME.border}
                activeOutlineColor={HR_THEME.primary}
                error={!!phoneError}
              />
              {phoneError ? (
                <HelperText type="error" visible={!!phoneError}>
                  {phoneError}
                </HelperText>
              ) : null}
            </View>

            <View style={styles.inputGroup}>
              <Text style={styles.label}>{t('whitelist.add.name')} *</Text>
              <TextInput
                mode="outlined"
                value={realName}
                onChangeText={setRealName}
                placeholder={t('whitelist.add.namePlaceholder')}
                style={styles.input}
                outlineColor={HR_THEME.border}
                activeOutlineColor={HR_THEME.primary}
              />
            </View>

            <View style={styles.inputGroup}>
              <Text style={styles.label}>{t('whitelist.add.role')} *</Text>
              <Menu
                visible={menuVisible}
                onDismiss={() => setMenuVisible(false)}
                anchor={
                  <TouchableOpacity
                    style={styles.selectButton}
                    onPress={() => setMenuVisible(true)}
                  >
                    <Text style={presetRoleName ? styles.selectText : styles.selectPlaceholder}>
                      {presetRoleName || t('whitelist.add.rolePlaceholder')}
                    </Text>
                    <MaterialCommunityIcons name="chevron-down" size={24} color={HR_THEME.textSecondary} />
                  </TouchableOpacity>
                }
              >
                {ROLE_OPTIONS.map((role) => (
                  <Menu.Item
                    key={role.value}
                    onPress={() => selectRole(role)}
                    title={role.label}
                    leadingIcon={presetRole === role.value ? 'check' : undefined}
                  />
                ))}
              </Menu>
            </View>

            <View style={styles.inputGroup}>
              <Text style={styles.label}>{t('whitelist.add.remark')}</Text>
              <TextInput
                mode="outlined"
                value={notes}
                onChangeText={setNotes}
                placeholder={t('whitelist.add.remarkPlaceholder')}
                multiline
                numberOfLines={3}
                style={[styles.input, styles.textArea]}
                outlineColor={HR_THEME.border}
                activeOutlineColor={HR_THEME.primary}
              />
            </View>

            <View style={styles.tips}>
              <MaterialCommunityIcons name="information-outline" size={18} color={HR_THEME.info} />
              <Text style={styles.tipsText}>
                {t('whitelist.add.tip')}
              </Text>
            </View>
          </View>
        </ScrollView>

        <View style={styles.footer}>
          <Button
            mode="outlined"
            onPress={() => navigation.goBack()}
            style={styles.cancelButton}
            textColor={HR_THEME.textSecondary}
          >
            {t('whitelist.add.cancel')}
          </Button>
          <Button
            mode="contained"
            onPress={handleSubmit}
            loading={loading}
            disabled={loading}
            style={styles.submitButton}
            buttonColor={HR_THEME.primary}
          >
            {t('whitelist.add.add')}
          </Button>
        </View>
      </KeyboardAvoidingView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: HR_THEME.background },
  header: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    paddingHorizontal: 16, paddingVertical: 12,
    backgroundColor: HR_THEME.cardBackground, borderBottomWidth: 1, borderBottomColor: HR_THEME.border,
  },
  backBtn: { padding: 4 },
  headerTitle: { fontSize: 18, fontWeight: '600', color: HR_THEME.textPrimary },
  content: { flex: 1 },
  form: { padding: 16 },
  inputGroup: { marginBottom: 20 },
  label: { fontSize: 14, fontWeight: '500', color: HR_THEME.textPrimary, marginBottom: 8 },
  input: { backgroundColor: HR_THEME.cardBackground },
  textArea: { minHeight: 100 },
  selectButton: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    backgroundColor: HR_THEME.cardBackground, borderWidth: 1, borderColor: HR_THEME.border,
    borderRadius: 4, paddingHorizontal: 14, paddingVertical: 16,
  },
  selectText: { fontSize: 16, color: HR_THEME.textPrimary },
  selectPlaceholder: { fontSize: 16, color: HR_THEME.textMuted },
  tips: {
    flexDirection: 'row', alignItems: 'flex-start',
    backgroundColor: HR_THEME.info + '10', padding: 12, borderRadius: 8, marginTop: 8,
  },
  tipsText: { flex: 1, fontSize: 13, color: HR_THEME.textSecondary, marginLeft: 8, lineHeight: 18 },
  footer: {
    flexDirection: 'row', padding: 16,
    backgroundColor: HR_THEME.cardBackground, borderTopWidth: 1, borderTopColor: HR_THEME.border,
  },
  cancelButton: { flex: 1, marginRight: 12, borderColor: HR_THEME.border },
  submitButton: { flex: 1 },
});
