module.exports = {
  preset: 'react-native',
  testEnvironment: 'node',

  // 全局变量定义 - 修复 __DEV__ 未定义错误
  globals: {
    __DEV__: true,
  },

  setupFilesAfterEnv: [
    '<rootDir>/src/__tests__/setup.ts'
  ],
  testMatch: [
    '**/__tests__/**/*.test.{js,jsx,ts,tsx}',
    '**/?(*.)+(spec|test).{js,jsx,ts,tsx}'
  ],
  testPathIgnorePatterns: [
    '/node_modules/',
    '/src/test/' // 忽略现有的测试目录
    // 2026-08-16: 摘掉 '/__tests__/integration/screens/'。
    //
    // 它是 PR-2 (May 9 2026) 留下的债: 那时 transformIgnorePatterns 不够宽,
    // @react-navigation/native 的 ESM 进不了转译, 于是把整个目录排除掉先把
    // 44 个 suite 的 continue-on-error 摘了。注释写的是「3 个」, 实际是 4 个文件。
    //
    // ⚠️ 排除的后果不是「少跑几个测试」, 而是【断言红了看不见】—— 这 4 个文件
    // 三个月来只被 tsc 编译, 从没执行过。与 Java 侧 GateSelectorCoverageContractTest
    // 守的是同一个形状: 编译得到 ≠ 会被执行。
    //
    // 解法是把 transformIgnorePatterns 配对(见下), 不是继续排除。
    // ⛔ 不要为了让 CI 变绿再把这一行加回来 —— 那等于把闸关掉。
  ],
  collectCoverageFrom: [
    'src/**/*.{js,jsx,ts,tsx}',
    '!src/**/*.d.ts',
    '!src/test/**', // 排除现有测试目录
    '!src/__tests__/**', // 排除测试目录本身
    '!src/**/*.test.{js,jsx,ts,tsx}',
    '!src/**/index.{js,ts}',
    '!src/mocks/**'
  ],
  // PR #224 (May 9 2026) removed `continue-on-error: true` from rn-test job and
  // added the missing jest binary, but left the historical 70% coverage threshold
  // in place. Actual coverage today is ~4% (44 suites / 880 tests vs hundreds of
  // untested screens/components), so the threshold immediately blocked CI with
  // exit code 1 even though every test passes. The 70% gate has been masked by
  // continue-on-error since the file was created and was never enforceable.
  // PR #276 dropped the 70% gate to unblock CI on green tests.
  //
  // PR #276 follow-up (May 10 2026): restore a REALISTIC baseline so further
  // regressions are blocked AND the gate can be ratcheted up over time as new
  // tests land. Current actual: stmts 4.04 / branches 1.86 / lines 4.01 /
  // funcs 5.02. Baseline below sits ~0.5-1pp under each axis as a defensive
  // margin (single test deletion shouldn't tip CI red). Coverage data is
  // still collected and uploaded as the rn-coverage artifact (retention 14d).
  //
  // Ratchet plan (see docs/qa-audits/2026-05-10-rn-coverage-ratchet-plan.md):
  //   Quarter 1 (~3mo)  target: stmts 10  / branches 5   / lines 10  / funcs 12
  //   Quarter 2 (~6mo)  target: stmts 20  / branches 10  / lines 20  / funcs 25
  //   Long-term (12mo+) target: stmts 60  / branches 50  / lines 60  / funcs 65
  // Each new test PR can ratchet the baseline up by 1-2 percentage points
  // when it covers new ground. Do NOT raise targets aggressively (test churn);
  // do NOT set baseline ABOVE current actual (PR #224's mistake repeated).
  coverageThreshold: {
    global: {
      statements: 4,
      branches: 1.5,
      lines: 4,
      functions: 4.9 // PR #536 procure screens added; attachmentApi tests added, full screen coverage follow-up
    }
  },
  moduleFileExtensions: ['ts', 'tsx', 'js', 'jsx', 'json'],
  moduleNameMapper: {
    '^@/(.*)$': '<rootDir>/src/$1',
    '^@env$': '<rootDir>/src/__tests__/mocks/env.ts',
    '\\.(jpg|jpeg|png|gif|eot|otf|webp|svg|ttf|woff|woff2)$': 'jest-transform-stub',
    // 'react-native' 是正则，会匹配所有含该子串的包名 (如 @react-native-xxx)
    // 下面两行让这些包跳过宽泛匹配、正常解析到 jest.mock 工厂
    '@react-native-async-storage/async-storage': '@react-native-async-storage/async-storage',
    '@testing-library/react-native': '@testing-library/react-native',
    // 🔴 已知缺口, 2026-08-16 未解决 —— 留给下一轮, ⛔ 不要照下面的思路直接重试。
    //
    // 症状: integration/screens 解禁后, ProcessTaskListScreen:225 的
    //   `import { Appbar } from 'react-native-paper'` 拿到 undefined
    //   → TypeError: Cannot read properties of undefined (reading 'Header') ×12。
    //
    // 诊断: 症状长得像「setup.ts 的 paper mock 没写对」, 但在 mock 上改了两轮都无效
    //   (含一次真实的坑: jest.requireActual 返回冻结命名空间, `Appbar.Header = x`
    //    静默失败)。真因怀疑是下面那条【宽泛正则】—— 'react-native' 会子串匹配
    //   `react-native-paper`, 把整个包映射到 react-native 的 mock 上。
    //
    // ⚠️ 我试过在这里加豁免, 三次都更糟, 每次坏法不同:
    //   ① 裸串 'react-native-vector-icons' → 吃掉子路径导入, 整套 0 tests
    //   ② 锚定 '^react-native-paper$' 等 → 3 个 suite 加载期死, 63 → 16 tests
    //   ⇒ 说明这条宽泛正则和 __mocks__/react-native.js 的耦合比看上去深,
    //     改它要连着 __mocks__ 一起想, 不是加一行豁免能解决的。
    //
    // 现状: 保持原样(63 tests 收集到, 28 通过), ⛔ 不为了「看起来在推进」而留一个
    //   把套件搞崩的改动。
    // 宽泛匹配：react-native 本体 + 子路径 + react-native-xxx 三方包 → __mocks__/react-native.js
    'react-native': 'react-native'
  },
  // 默认值是 ['/node_modules/'] —— 即 node_modules 里一律不转译。RN 生态里大量包
  // 只发 ESM 源码(@react-navigation/*, expo-*, react-native 本体…), 不转译就会在
  // `import` 那一行 SyntaxError。下面这条是标准的「除了这些包之外都不转译」写法:
  // (?!…) 里列出的包会被 babel-jest 处理。
  //
  // ⚠️ 这一条和 testPathIgnorePatterns 里被摘掉的那一行是配对的 —— 少了它,
  //    integration/screens 下的用例会在 import @react-navigation 时当场炸。
  // ⚠️ 括号要自己数一遍: 外层 (?! 一个、白名单 ( 一个, 结尾 '/)' 里的 ')' 关的是 (?!。
  //    写成 '(?!(?:' + 'jest-)?…' 会让 '(?:' 被 '(jest-)' 的右括号提前关掉,
  //    多出来的那个 ')' 直接 SyntaxError —— 实测踩过一次(好在它是当场炸, 不是静默失效)。
  // ⚠️ Windows 上 jest 会把这里的 '/' 换成 '\\' 再编译成正则, 所以只能写 '/'。
  transformIgnorePatterns: [
    'node_modules/(?!('
      + '(jest-)?react-native'
      + '|@react-native(-community)?/.*'
      + '|@react-navigation/.*'
      + '|expo(nent)?'
      + '|@expo(nent)?/.*'
      + '|expo-.*'
      + '|react-native-.*'
      + '|@sentry/react-native'
      + '|native-base'
      + ')/)'
  ],
  // ⚠️ 顺序有意义: jest 取【第一条匹配上的】规则, 所以 node_modules 那条必须排最前。
  //
  // 放开 transformIgnorePatterns 之后, node_modules 里的三方 .ts 源码也会进 transform
  // 链(expo-modules-core 直接发 .ts)。落到 ts-jest 就会去【类型检查三方源码】——
  // expo-modules-core 自带 5 个 TS2532, 于是 3 个 suite 在【加载期】就崩,
  // 一条断言都没跑到。⚠️ 那 3 条红看起来像「存量欠账」, 其实是这条配置造出来的。
  //
  // 三方代码要的是【转译】不是【类型检查】⇒ 交给 babel-jest。
  // ⛔ 不要改成 diagnostics:false 去消这些错 —— 那会把我们自己代码的类型检查一起关掉,
  //    而那是真信号。
  transform: {
    'node_modules[\\\\/].+\\.(js|jsx|ts|tsx)$': 'babel-jest',
    '^.+\\.(js|jsx)$': 'babel-jest',
    '^.+\\.(ts|tsx)$': ['ts-jest', {
      tsconfig: {
        jsx: 'react-jsx',
      },
    }],
  }
};