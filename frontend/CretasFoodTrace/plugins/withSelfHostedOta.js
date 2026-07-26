const fs = require('fs');
const path = require('path');
const {
  AndroidConfig,
  withAndroidManifest,
  withInfoPlist,
  withStringsXml,
} = require('@expo/config-plugins');

const DEFAULT_OTA_URL = 'https://ota.cretaceousfuture.com/api/ota/manifest';

function readCertificate(projectRoot, config) {
  const certificatePath = config.updates?.codeSigningCertificate;
  if (!certificatePath) {
    throw new Error('expo.updates.codeSigningCertificate is required');
  }

  const absolutePath = path.join(projectRoot, certificatePath);
  if (!fs.existsSync(absolutePath)) {
    throw new Error(`OTA code-signing certificate not found: ${absolutePath}`);
  }

  return fs.readFileSync(absolutePath, 'utf8').trim();
}

function resolveRuntimeVersion(config) {
  if (typeof config.runtimeVersion === 'string' && config.runtimeVersion.trim()) {
    return config.runtimeVersion.trim();
  }
  if (typeof config.version === 'string' && config.version.trim()) {
    return config.version.trim();
  }
  throw new Error('A string expo.runtimeVersion or expo.version is required');
}

function withSelfHostedOta(config) {
  const runtimeVersion = resolveRuntimeVersion(config);
  const otaUrl = config.updates?.url || DEFAULT_OTA_URL;
  const signingMetadata = config.updates?.codeSigningMetadata || {
    keyid: 'main',
    alg: 'rsa-v1_5-sha256',
  };
  const requestHeaders = config.updates?.requestHeaders || {
    'expo-channel-name': 'production',
  };

  config = withStringsXml(config, (cfg) => {
    cfg.modResults = AndroidConfig.Strings.setStringItem(
      [
        AndroidConfig.Resources.buildResourceItem({
          name: 'expo_runtime_version',
          value: runtimeVersion,
          translatable: false,
        }),
      ],
      cfg.modResults,
    );
    return cfg;
  });

  config = withAndroidManifest(config, (cfg) => {
    const application = AndroidConfig.Manifest.getMainApplicationOrThrow(cfg.modResults);
    const certificate = readCertificate(cfg.modRequest.projectRoot, cfg);
    const setMetadata = (name, value) => {
      AndroidConfig.Manifest.addMetaDataItemToMainApplication(
        application,
        name,
        value,
      );
    };

    setMetadata('expo.modules.updates.ENABLED', 'true');
    setMetadata(
      'expo.modules.updates.EXPO_RUNTIME_VERSION',
      '@string/expo_runtime_version',
    );
    setMetadata('expo.modules.updates.EXPO_UPDATE_URL', otaUrl);
    setMetadata('expo.modules.updates.EXPO_UPDATES_CHECK_ON_LAUNCH', 'NEVER');
    setMetadata('expo.modules.updates.EXPO_UPDATES_LAUNCH_WAIT_MS', '0');
    setMetadata('expo.modules.updates.CODE_SIGNING_CERTIFICATE', certificate);
    setMetadata(
      'expo.modules.updates.CODE_SIGNING_METADATA',
      JSON.stringify(signingMetadata),
    );
    setMetadata(
      'expo.modules.updates.UPDATES_CONFIGURATION_REQUEST_HEADERS_KEY',
      JSON.stringify(requestHeaders),
    );
    return cfg;
  });

  return withInfoPlist(config, (cfg) => {
    const certificate = readCertificate(cfg.modRequest.projectRoot, cfg);
    cfg.modResults.EXUpdatesEnabled = true;
    cfg.modResults.EXUpdatesURL = otaUrl;
    cfg.modResults.EXUpdatesRuntimeVersion = runtimeVersion;
    cfg.modResults.EXUpdatesCheckOnLaunch = 'NEVER';
    cfg.modResults.EXUpdatesLaunchWaitMs = 0;
    cfg.modResults.EXUpdatesCodeSigningCertificate = certificate;
    cfg.modResults.EXUpdatesCodeSigningMetadata = signingMetadata;
    cfg.modResults.EXUpdatesRequestHeaders = requestHeaders;
    return cfg;
  });
}

module.exports = withSelfHostedOta;
