import React, { useState } from 'react';
import {
  Image,
  Linking,
  StatusBar,
  StyleSheet,
  View,
} from 'react-native';
import {
  ActivityIndicator,
  Button,
  Provider as PaperProvider,
  Text,
} from 'react-native-paper';
import {
  SafeAreaProvider,
  SafeAreaView,
} from 'react-native-safe-area-context';

import i18n from '../../i18n';
import type { AppVersionCheckResult } from '../../services/appVersionCheck';
import { theme } from '../../theme';

type MandatoryUpdateGateProps = {
  result: Extract<AppVersionCheckResult, { status: 'update_required' }> | null;
  checking: boolean;
  onRetry: () => void;
};

const MandatoryUpdateGate: React.FC<MandatoryUpdateGateProps> = ({
  result,
  checking,
  onRetry,
}) => {
  const [openingDownload, setOpeningDownload] = useState(false);
  const [downloadError, setDownloadError] = useState<string | null>(null);

  const openDownload = async (): Promise<void> => {
    if (!result?.downloadUrl) {
      setDownloadError(i18n.t('common:ota.required_missing_url'));
      return;
    }

    setOpeningDownload(true);
    setDownloadError(null);
    try {
      await Linking.openURL(result.downloadUrl);
    } catch {
      setDownloadError(i18n.t('common:ota.required_open_failed'));
    } finally {
      setOpeningDownload(false);
    }
  };

  return (
    <SafeAreaProvider>
      <PaperProvider theme={theme}>
        <SafeAreaView style={styles.safeArea} edges={['top', 'bottom']}>
          <StatusBar backgroundColor="#F5F5F5" barStyle="dark-content" />
          <View style={styles.container}>
            <Image
              source={require('../../../assets/icon.png')}
              style={styles.logo}
              resizeMode="contain"
              accessibilityIgnoresInvertColors
            />

            {checking || !result ? (
              <>
                <Text variant="headlineSmall" style={styles.title}>
                  {i18n.t('common:ota.required_checking_title')}
                </Text>
                <Text variant="bodyLarge" style={styles.description}>
                  {i18n.t('common:ota.required_checking_message')}
                </Text>
                <ActivityIndicator
                  size="large"
                  color={theme.colors.primary}
                  style={styles.spinner}
                  accessibilityLabel={i18n.t('common:ota.checking')}
                />
              </>
            ) : (
              <>
                <View style={styles.badge}>
                  <Text variant="labelLarge" style={styles.badgeText}>
                    {i18n.t('common:ota.required_badge')}
                  </Text>
                </View>
                <Text variant="headlineMedium" style={styles.title}>
                  {i18n.t('common:ota.required_title')}
                </Text>
                <Text variant="bodyLarge" style={styles.description}>
                  {i18n.t('common:ota.required_message')}
                </Text>

                <View style={styles.versionCard}>
                  <View style={styles.versionRow}>
                    <Text variant="bodyMedium" style={styles.versionLabel}>
                      {i18n.t('common:ota.current_version')}
                    </Text>
                    <Text variant="titleMedium" style={styles.versionValue}>
                      {result.currentVersion}
                    </Text>
                  </View>
                  <View style={styles.divider} />
                  <View style={styles.versionRow}>
                    <Text variant="bodyMedium" style={styles.versionLabel}>
                      {i18n.t('common:ota.minimum_version')}
                    </Text>
                    <Text variant="titleMedium" style={styles.versionValue}>
                      {result.minimumVersion}
                    </Text>
                  </View>
                </View>

                {result.releaseNotes ? (
                  <Text variant="bodyMedium" style={styles.releaseNotes}>
                    {result.releaseNotes}
                  </Text>
                ) : null}

                {downloadError ? (
                  <Text
                    variant="bodyMedium"
                    style={styles.errorText}
                    accessibilityLiveRegion="polite"
                  >
                    {downloadError}
                  </Text>
                ) : null}

                <Button
                  mode="contained"
                  onPress={() => void openDownload()}
                  loading={openingDownload}
                  disabled={openingDownload}
                  contentStyle={styles.primaryButtonContent}
                  style={styles.primaryButton}
                  labelStyle={styles.primaryButtonLabel}
                  accessibilityLabel={i18n.t('common:ota.update_now')}
                >
                  {i18n.t('common:ota.update_now')}
                </Button>
                <Button
                  mode="text"
                  onPress={onRetry}
                  disabled={openingDownload}
                  contentStyle={styles.retryButtonContent}
                  style={styles.retryButton}
                >
                  {i18n.t('common:ota.required_retry')}
                </Button>
                <Text variant="bodySmall" style={styles.helpText}>
                  {i18n.t('common:ota.required_help')}
                </Text>
              </>
            )}
          </View>
        </SafeAreaView>
      </PaperProvider>
    </SafeAreaProvider>
  );
};

export default MandatoryUpdateGate;

const styles = StyleSheet.create({
  safeArea: {
    flex: 1,
    backgroundColor: '#F5F5F5',
  },
  container: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    paddingHorizontal: 24,
    paddingVertical: 32,
  },
  logo: {
    width: 88,
    height: 88,
    borderRadius: 18,
    marginBottom: 24,
  },
  badge: {
    minHeight: 32,
    justifyContent: 'center',
    paddingHorizontal: 12,
    borderRadius: 999,
    backgroundColor: '#FFF1F0',
    marginBottom: 16,
  },
  badgeText: {
    color: '#CF1322',
  },
  title: {
    color: '#1F2937',
    fontWeight: '700',
    textAlign: 'center',
    marginBottom: 12,
  },
  description: {
    maxWidth: 420,
    color: '#6B7280',
    textAlign: 'center',
    lineHeight: 24,
    marginBottom: 24,
  },
  spinner: {
    marginTop: 8,
  },
  versionCard: {
    width: '100%',
    maxWidth: 420,
    borderRadius: 16,
    borderWidth: 1,
    borderColor: '#E5E7EB',
    backgroundColor: '#FFFFFF',
    marginBottom: 16,
  },
  versionRow: {
    minHeight: 56,
    paddingHorizontal: 16,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  versionLabel: {
    color: '#6B7280',
  },
  versionValue: {
    color: '#1F2937',
    fontWeight: '700',
  },
  divider: {
    height: 1,
    backgroundColor: '#E5E7EB',
  },
  releaseNotes: {
    width: '100%',
    maxWidth: 420,
    color: '#4B5563',
    lineHeight: 21,
    marginBottom: 16,
  },
  errorText: {
    width: '100%',
    maxWidth: 420,
    color: '#CF1322',
    lineHeight: 21,
    marginBottom: 12,
  },
  primaryButton: {
    width: '100%',
    maxWidth: 420,
    borderRadius: 12,
  },
  primaryButtonContent: {
    minHeight: 52,
  },
  primaryButtonLabel: {
    fontSize: 17,
    fontWeight: '700',
  },
  retryButton: {
    width: '100%',
    maxWidth: 420,
    marginTop: 8,
  },
  retryButtonContent: {
    minHeight: 48,
  },
  helpText: {
    maxWidth: 420,
    color: '#6B7280',
    textAlign: 'center',
    lineHeight: 18,
    marginTop: 4,
  },
});
