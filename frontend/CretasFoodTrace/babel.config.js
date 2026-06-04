module.exports = function (api) {
  // Cache keyed on ENVFILE so the correct .env file is picked per build profile.
  api.cache.using(() => process.env.ENVFILE);
  return {
    presets: ['babel-preset-expo'],
    plugins: [
      ['module:react-native-dotenv', {
        moduleName: '@env',
        // EAS/CI sets ENVFILE per profile (.env.production / .env.test); local dev falls back to .env
        path: process.env.ENVFILE || '.env',
      }],
      'react-native-reanimated/plugin',
    ],
  };
};
