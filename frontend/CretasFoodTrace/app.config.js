const SELF_HOSTED_OTA_PLUGIN = './plugins/withSelfHostedOta';

module.exports = ({ config }) => {
  const environment = process.env.EXPO_PUBLIC_ENV || 'production';
  const isTestBuild = environment === 'development' || environment === 'test';
  const version = config.version || '1.0.3';
  const explicitRuntime = process.env.APP_OTA_RUNTIME?.trim();
  const runtimeVersion = explicitRuntime || (isTestBuild ? `${version}-test` : version);
  const existingPlugins = config.plugins || [];
  const plugins = existingPlugins.includes(SELF_HOSTED_OTA_PLUGIN)
    ? existingPlugins
    : [...existingPlugins, SELF_HOSTED_OTA_PLUGIN];

  return {
    ...config,
    name: '白垩纪AI Agent',
    slug: 'CretasFoodTrace',
    version,
    runtimeVersion,
    orientation: 'portrait',
    icon: './assets/icon.png',
    userInterfaceStyle: 'light',
    newArchEnabled: true,
    updates: {
      ...config.updates,
      enabled: true,
      url: 'https://ota.cretaceousfuture.com/api/ota/manifest',
      checkAutomatically: 'NEVER',
      fallbackToCacheTimeout: 0,
      codeSigningCertificate: './ota_cert.pem',
      codeSigningMetadata: {
        keyid: 'main',
        alg: 'rsa-v1_5-sha256',
      },
      requestHeaders: {
        ...config.updates?.requestHeaders,
        'expo-channel-name': isTestBuild ? 'staging' : 'production',
      },
    },
    android: {
      ...config.android,
      package: 'com.cretas.foodtrace',
      versionCode: 14,
    },
    extra: {
      ...config.extra,
      env: environment,
      otaRuntimeVersion: runtimeVersion,
    },
    plugins,
  };
};
