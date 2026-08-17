const { getDefaultConfig } = require('expo/metro-config');

const config = getDefaultConfig(__dirname);

// worker 上限 (2026-08-17) —— Metro 的转译池用的是 jest-worker, 默认按 CPU 核数开。
//
// 实测(开发机 32 逻辑核): 一次 `expo start` 拉起 17 个 `jest-worker/processChild.js`。
// 这个仓常态是多个 worktree 同时 `expo start`(那天实测同时挂着 3010/5199/5211/
// 5233/5299/5411 六个实例), 于是 50+ 个 worker 进程一起压 commit —— 而它们
// 每个只有几十 MB 驻留, 吃的全是提交内存, 在任务管理器里几乎看不出来。
//
// ⚠️ 与 jest.config.js 的 maxWorkers 是同一件事的两处, 改一处等于没改:
//    jest 那处管跑测试, 这处管起 dev server。两处都要有。
// 单实例想跑快: METRO_MAX_WORKERS=16 npx expo start
config.maxWorkers = Number(process.env.METRO_MAX_WORKERS) || 4;

// Add transformer to handle import.meta
config.transformer.getTransformOptions = async () => ({
  transform: {
    experimentalImportSupport: false,
    inlineRequires: false,
  },
});

// Add resolver configuration for web
config.resolver.alias = {
  'react-native': 'react-native-web',
};

// Prefer CJS over ESM to avoid import.meta issues in non-module script context
// (zustand/esm/middleware.mjs uses import.meta.env which fails in Metro web bundles)
config.resolver.resolverMainFields = ['react-native', 'browser', 'main'];

// Force 'require' condition over 'import' in package.json exports to get CJS builds
config.resolver.unstable_conditionNames = ['react-native', 'require', 'default'];

module.exports = config;