/**
 * Manual mock for react-native in Jest node environment.
 * The moduleNameMapper maps all 'react-native*' imports here.
 */
const React = require('react');

const createMockComponent = (name) => {
  const component = ({ children, ...props }) => {
    return React.createElement(name, props, children);
  };
  component.displayName = name;
  return component;
};

/**
 * 列表类替身 (FlatList / SectionList / Animated.FlatList)。
 *
 * ⚠️ 2026-08-17: 原来这三个也是 createMockComponent, 也就是只渲染一个空壳 ——
 * `data` / `renderItem` / `ListEmptyComponent` 全被丢掉。后果不是「样式不对」,
 * 是【列表里一行都没有】, 于是所有 `getByText('BATCH-2026-001')` 这类断言全红,
 * 而红的原因看起来像「屏幕没渲染出批次」(屏幕是好的)。
 *
 * 判据: 替身要保真到【断言依赖的那部分行为】为止 —— 列表的那部分行为就是
 * 「把 data 逐条交给 renderItem, 空的时候渲染 ListEmptyComponent」。
 */
const createMockList = (name) => {
  const component = ({
    data,
    renderItem,
    keyExtractor,
    ListEmptyComponent,
    ListHeaderComponent,
    ListFooterComponent,
    ItemSeparatorComponent,
    sections,
    children,
    // refreshControl 是个 element, 塞进宿主组件会被当成 children 渲染, 这里丢掉
    refreshControl,
    ...props
  } = {}) => {
    const renderMaybeElement = (C) => {
      if (!C) return null;
      return React.isValidElement(C) ? C : React.createElement(C);
    };

    // SectionList 的 sections 摊平成 items; FlatList 直接用 data
    const items = Array.isArray(sections)
      ? sections.reduce(
          (acc, section, si) =>
            acc.concat(
              (section && section.data ? section.data : []).map((item) => ({ item, section, si }))
            ),
          []
        )
      : Array.isArray(data)
        ? data.map((item) => ({ item }))
        : [];

    const body =
      items.length === 0
        ? renderMaybeElement(ListEmptyComponent)
        : items.map((entry, index) => {
            const key =
              (keyExtractor && keyExtractor(entry.item, index)) ??
              (entry.item && (entry.item.id ?? entry.item.key)) ??
              index;
            const rendered = renderItem
              ? renderItem({ item: entry.item, index, section: entry.section, separators: {} })
              : null;
            return React.createElement(
              React.Fragment,
              { key: String(key) },
              rendered
            );
          });

    return React.createElement(
      name,
      props,
      renderMaybeElement(ListHeaderComponent),
      body,
      renderMaybeElement(ListFooterComponent),
      children
    );
  };
  component.displayName = name;
  return component;
};

module.exports = {
  // Core components
  View: createMockComponent('View'),
  Text: createMockComponent('Text'),
  TouchableOpacity: createMockComponent('TouchableOpacity'),
  TouchableHighlight: createMockComponent('TouchableHighlight'),
  TouchableWithoutFeedback: createMockComponent('TouchableWithoutFeedback'),
  Pressable: createMockComponent('Pressable'),
  ScrollView: createMockComponent('ScrollView'),
  FlatList: createMockList('FlatList'),
  SectionList: createMockList('SectionList'),
  TextInput: createMockComponent('TextInput'),
  Image: createMockComponent('Image'),
  ImageBackground: createMockComponent('ImageBackground'),
  // Modal 必须认 `visible`。真的 RN Modal 在 visible=false 时【什么都不渲染】,
  // 而之前的替身无条件渲染 children ⇒ 关着的弹窗里的文字也能被 getByText 找到。
  // 实测后果: SearchableDropdown 的 label 既出现在选择器上、又出现在(关着的)
  // 弹窗标题里, `getByText(/生产类目\/工序/)` 报 "Found multiple elements" ——
  // 一个纯粹由替身造出来的假冲突。
  Modal: (() => {
    const C = ({ children, visible, ...props }) =>
      visible === false ? null : React.createElement('Modal', props, children);
    C.displayName = 'Modal';
    return C;
  })(),
  ActivityIndicator: createMockComponent('ActivityIndicator'),
  Switch: createMockComponent('Switch'),
  KeyboardAvoidingView: createMockComponent('KeyboardAvoidingView'),
  SafeAreaView: createMockComponent('SafeAreaView'),
  StatusBar: createMockComponent('StatusBar'),
  RefreshControl: createMockComponent('RefreshControl'),

  // APIs
  StyleSheet: {
    create: (styles) => styles,
    flatten: (style) => (Array.isArray(style) ? Object.assign({}, ...style) : style || {}),
    hairlineWidth: 0.5,
    absoluteFill: { position: 'absolute', left: 0, right: 0, top: 0, bottom: 0 },
    absoluteFillObject: { position: 'absolute', left: 0, right: 0, top: 0, bottom: 0 },
  },
  Platform: {
    OS: 'ios',
    select: (obj) => obj.ios || obj.default,
    Version: 14,
  },
  Dimensions: {
    get: () => ({ width: 375, height: 812, scale: 2, fontScale: 1 }),
    addEventListener: jest.fn(),
    removeEventListener: jest.fn(),
  },
  Alert: {
    alert: jest.fn(),
  },
  Linking: {
    openURL: jest.fn(() => Promise.resolve()),
    canOpenURL: jest.fn(() => Promise.resolve(true)),
    addEventListener: jest.fn(),
    removeEventListener: jest.fn(),
  },
  Animated: {
    View: createMockComponent('Animated.View'),
    Text: createMockComponent('Animated.Text'),
    Image: createMockComponent('Animated.Image'),
    ScrollView: createMockComponent('Animated.ScrollView'),
    FlatList: createMockList('Animated.FlatList'),
    Value: jest.fn().mockImplementation(() => ({
      setValue: jest.fn(),
      interpolate: jest.fn(() => ({ __getValue: () => 0 })),
      addListener: jest.fn(),
      removeListener: jest.fn(),
      removeAllListeners: jest.fn(),
      stopAnimation: jest.fn(),
      __getValue: () => 0,
    })),
    timing: jest.fn(() => ({ start: jest.fn((cb) => cb && cb({ finished: true })), stop: jest.fn() })),
    spring: jest.fn(() => ({ start: jest.fn((cb) => cb && cb({ finished: true })), stop: jest.fn() })),
    decay: jest.fn(() => ({ start: jest.fn((cb) => cb && cb({ finished: true })), stop: jest.fn() })),
    parallel: jest.fn(() => ({ start: jest.fn((cb) => cb && cb({ finished: true })), stop: jest.fn() })),
    sequence: jest.fn(() => ({ start: jest.fn((cb) => cb && cb({ finished: true })), stop: jest.fn() })),
    loop: jest.fn(() => ({ start: jest.fn(), stop: jest.fn() })),
    event: jest.fn(() => jest.fn()),
    createAnimatedComponent: (component) => component,
  },
  Keyboard: {
    dismiss: jest.fn(),
    addListener: jest.fn(() => ({ remove: jest.fn() })),
    removeListener: jest.fn(),
  },
  AppState: {
    currentState: 'active',
    addEventListener: jest.fn(() => ({ remove: jest.fn() })),
    removeEventListener: jest.fn(),
  },
  PixelRatio: {
    get: () => 2,
    getFontScale: () => 1,
    getPixelSizeForLayoutSize: (size) => size * 2,
    roundToNearestPixel: (size) => size,
  },
  Easing: {
    linear: jest.fn(),
    ease: jest.fn(),
    bezier: jest.fn(() => jest.fn()),
    in: jest.fn(),
    out: jest.fn(),
    inOut: jest.fn(),
  },
  NativeModules: {},
  NativeEventEmitter: jest.fn().mockImplementation(() => ({
    addListener: jest.fn(),
    removeAllListeners: jest.fn(),
  })),
  useColorScheme: jest.fn(() => 'light'),
  useWindowDimensions: jest.fn(() => ({ width: 375, height: 812, scale: 2, fontScale: 1 })),
  Appearance: {
    getColorScheme: () => 'light',
    addChangeListener: jest.fn(() => ({ remove: jest.fn() })),
  },
  I18nManager: {
    isRTL: false,
  },
  LayoutAnimation: {
    configureNext: jest.fn(),
    create: jest.fn(),
    Types: { spring: 'spring', linear: 'linear', easeInEaseOut: 'easeInEaseOut' },
    Properties: { opacity: 'opacity', scaleXY: 'scaleXY' },
  },
  InteractionManager: {
    runAfterInteractions: jest.fn((cb) => { if (typeof cb === 'function') cb(); return { then: jest.fn(), cancel: jest.fn() }; }),
  },
};
