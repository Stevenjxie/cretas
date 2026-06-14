module.exports = ({ config }) => {
  // 从环境变量读取环境配置，默认为 test
  const env = process.env.EXPO_PUBLIC_ENV || 'test';
  const existingExtra = config.extra || {};
  const existingEas = existingExtra.eas || {};

  return {
    ...config,
    name: '白垩纪AI Agent',
    slug: 'CretasFoodTrace',
    version: '1.0.1',
    orientation: 'portrait',
    icon: './assets/icon.png',
    userInterfaceStyle: 'light',
    newArchEnabled: true,
    splash: {
      image: './assets/splash-icon.png',
      resizeMode: 'contain',
      backgroundColor: '#ffffff',
    },
    ios: {
      supportsTablet: true,
      bundleIdentifier: 'com.cretaceousfuture',
      infoPlist: {
        NSCameraUsageDescription: '需要使用相机拍摄收货签收照片作为凭证',
        NSPhotoLibraryUsageDescription: '需要访问相册选择收货凭证照片',
      },
    },
    android: {
      adaptiveIcon: {
        foregroundImage: './assets/adaptive-icon.png',
        backgroundColor: '#ffffff',
      },
      edgeToEdgeEnabled: true,
      package: 'com.cretas.foodtrace',
      permissions: ['android.permission.CAMERA'],
    },
    web: {
      favicon: './assets/favicon.png',
      bundler: 'metro',
    },
    plugins: [
      'expo-secure-store',
      'expo-localization',
      [
        'expo-image-picker',
        {
          cameraPermission: '需要使用相机拍摄收货签收照片作为凭证',
        },
      ],
    ],
    extra: {
      ...existingExtra,
      // 将环境变量传递给应用
      env: env,
      eas: {
        ...existingEas,
        projectId:
          process.env.EXPO_PUBLIC_EAS_PROJECT_ID ||
          existingEas.projectId ||
          'com.cretas.foodtrace',
      },
    },
  };
};
