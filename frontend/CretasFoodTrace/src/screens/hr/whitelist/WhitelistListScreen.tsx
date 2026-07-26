/**
 * 白名单列表
 *
 * @version 1.0.0
 * @since 2025-12-29
 */

import React, { useState, useCallback, useMemo } from 'react';
import {
  View, FlatList, StyleSheet, RefreshControl, TouchableOpacity, Alert,
} from 'react-native';
import { Text, Card, Searchbar, Chip, FAB, ActivityIndicator, IconButton } from 'react-native-paper';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useNavigation, useFocusEffect, CommonActions } from '@react-navigation/native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useTranslation } from 'react-i18next';

import { whitelistApiClient } from '../../../services/api/whitelistApiClient';
import {
  HR_THEME,
  ROLE_OPTIONS,
  WHITELIST_STATUS_CONFIG,
  type WhitelistEntry,
  type WhitelistStatus,
} from '../../../types/hrNavigation';

export default function WhitelistListScreen() {
  const navigation = useNavigation();
  const { t } = useTranslation('hr');
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [whitelist, setWhitelist] = useState<WhitelistEntry[]>([]);

  const loadData = useCallback(async () => {
    try {
      const res = await whitelistApiClient.getWhitelist({ page: 1, size: 100 });
      // API 直接返回 PageResponse
      if (res?.content) {
        // Map WhitelistDTO to WhitelistEntry
        const mappedEntries: WhitelistEntry[] = res.content.map((w) => {
          // Map backend status to frontend WhitelistStatus
          let status: WhitelistStatus = 'pending';
          if (w.status === 'DISABLED') status = 'disabled';
          else if (w.status === 'EXPIRED') status = 'expired';
          else if (w.accountCreated && w.accountActive) status = 'active';
          return {
            id: String(w.id),
            phoneNumber: w.phoneNumber,
            name: w.name || w.realName,
            maskedPhone: w.phoneNumber.replace(/(\d{3})\d{4}(\d{4})/, '$1****$2'),
            presetRole: w.role,
            presetRoleName: ROLE_OPTIONS.find(role => role.value === w.role)?.label || w.role,
            status,
            accountCreated: w.accountCreated,
            accountActive: w.accountActive,
            addedBy: undefined,
            addedByName: w.createdBy,
            addedAt: w.createdAt,
            activatedAt: undefined,
          };
        });
        setWhitelist(mappedEntries);
      }
    } catch (error) {
      console.error('加载白名单失败:', error);
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  useFocusEffect(useCallback(() => { loadData(); }, [loadData]));

  const onRefresh = useCallback(() => { setRefreshing(true); loadData(); }, [loadData]);

  const handleDelete = (item: WhitelistEntry) => {
    Alert.alert(t('whitelist.list.deleteConfirm'), t('whitelist.list.deleteMessage', { phone: item.maskedPhone || item.phoneNumber }), [
      { text: t('common.cancel'), style: 'cancel' },
      { text: t('common.delete'), style: 'destructive', onPress: async () => {
        try {
          await whitelistApiClient.deleteWhitelist(Number(item.id));
          loadData();
        } catch (e) { Alert.alert(t('messages.error'), t('whitelist.list.deleteFailed')); }
      }},
    ]);
  };

  const filteredData = useMemo(() => whitelist.filter(item =>
    item.phoneNumber?.includes(searchQuery) || item.presetRoleName?.includes(searchQuery)
  ), [whitelist, searchQuery]);

  const renderItem = useCallback(({ item }: { item: WhitelistEntry }) => {
    const config = WHITELIST_STATUS_CONFIG[item.status] || WHITELIST_STATUS_CONFIG.pending;
    return (
      <Card style={styles.card}>
        <Card.Content style={styles.cardContent}>
          <View style={styles.info}>
            <View style={styles.row}>
              <Text style={styles.phone}>{item.maskedPhone || item.phoneNumber}</Text>
              <Chip mode="flat" textStyle={{ fontSize: 10, color: config.color }}
                style={[styles.chip, { backgroundColor: config.bgColor }]}>{config.label}</Chip>
            </View>
            {item.name ? <Text style={styles.name}>{item.name}</Text> : null}
            <Text style={styles.role}>{item.presetRoleName || t('whitelist.list.noRole')}</Text>
            <Text style={styles.date}>{t('whitelist.list.addedAt')}: {item.addedAt?.split('T')[0]}</Text>
          </View>
          {!item.accountCreated ? (
            <IconButton icon="delete" size={20} iconColor={HR_THEME.danger} onPress={() => handleDelete(item)} />
          ) : null}
        </Card.Content>
      </Card>
    );
  }, [t, handleDelete]);

  if (loading) return <View style={styles.loading}><ActivityIndicator size="large" color={HR_THEME.primary} /></View>;

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      <View style={styles.header}><Text style={styles.headerTitle}>{t('whitelist.list.title')}</Text></View>
      <View style={styles.search}>
        <Searchbar placeholder={t('whitelist.list.searchPlaceholder')} value={searchQuery} onChangeText={setSearchQuery} style={styles.searchbar} />
      </View>
      <FlatList data={filteredData} keyExtractor={item => item.id} renderItem={renderItem}
        contentContainerStyle={styles.list} refreshControl={<RefreshControl refreshing={refreshing} onRefresh={onRefresh} />}
        ListEmptyComponent={<View style={styles.empty}><MaterialCommunityIcons name="shield-check-outline" size={64} color={HR_THEME.textMuted} /><Text style={styles.emptyText}>{t('whitelist.list.empty')}</Text></View>} />
      <FAB icon="plus" style={styles.fab} onPress={() => navigation.dispatch(CommonActions.navigate('WhitelistAdd'))} color="#fff" />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: HR_THEME.background },
  loading: { flex: 1, justifyContent: 'center', alignItems: 'center', backgroundColor: HR_THEME.background },
  header: { padding: 16, backgroundColor: HR_THEME.primary },
  headerTitle: { fontSize: 22, fontWeight: 'bold', color: '#fff' },
  search: { padding: 16, backgroundColor: HR_THEME.cardBackground, borderBottomWidth: 1, borderBottomColor: HR_THEME.border },
  searchbar: { backgroundColor: HR_THEME.background, elevation: 0 },
  list: { padding: 16 },
  card: { borderRadius: 12, marginBottom: 12, backgroundColor: HR_THEME.cardBackground },
  cardContent: { flexDirection: 'row', alignItems: 'center' },
  info: { flex: 1 },
  row: { flexDirection: 'row', alignItems: 'center' },
  phone: { fontSize: 16, fontWeight: '500', color: HR_THEME.textPrimary, marginRight: 8 },
  chip: { height: 22 },
  name: { fontSize: 14, color: HR_THEME.textPrimary, marginTop: 4 },
  role: { fontSize: 13, color: HR_THEME.textSecondary, marginTop: 4 },
  date: { fontSize: 12, color: HR_THEME.textMuted, marginTop: 2 },
  empty: { alignItems: 'center', paddingVertical: 60 },
  emptyText: { marginTop: 16, fontSize: 14, color: HR_THEME.textMuted },
  fab: { position: 'absolute', right: 16, bottom: 24, backgroundColor: HR_THEME.primary },
});
