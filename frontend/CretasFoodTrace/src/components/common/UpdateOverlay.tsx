import React, { useEffect, useState } from 'react';
import {
  ActivityIndicator,
  Image,
  Modal,
  StatusBar,
  StyleSheet,
  Text,
  View,
} from 'react-native';

import i18n from '../../i18n';
import { subscribeOtaUpdating } from '../../services/otaUpdateService';

/**
 * Full-screen feedback shown only after the user accepts an OTA update.
 * Background checks and pre-downloads never block the application shell.
 */
const UpdateOverlay: React.FC = () => {
  const [visible, setVisible] = useState(false);

  useEffect(() => subscribeOtaUpdating(setVisible), []);

  return (
    <Modal
      visible={visible}
      animationType="fade"
      presentationStyle="fullScreen"
      onRequestClose={() => undefined}
    >
      <View style={styles.container}>
        <StatusBar
          translucent
          backgroundColor="transparent"
          barStyle="light-content"
        />
        <Image
          source={require('../../../assets/icon.png')}
          style={styles.logo}
          resizeMode="contain"
        />
        <Text style={styles.titleText}>
          {i18n.t('common:ota.updating_title')}
        </Text>
        <Text style={styles.subtitleText}>
          {i18n.t('common:ota.updating_message')}
        </Text>
        <ActivityIndicator
          size="large"
          color="#FFFFFF"
          style={styles.spinner}
        />
      </View>
    </Modal>
  );
};

export default UpdateOverlay;

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#2d5016',
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
    fontSize: 26,
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
