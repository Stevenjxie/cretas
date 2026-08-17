/**
 * 手写的 `react-native-paper` 测试替身。
 *
 * ## 为什么需要它 (2026-08-17, 实测而不是推断)
 *
 * `jest.config.js` 的 moduleNameMapper 里有一条【宽泛正则】`'react-native': 'react-native'`。
 * 正则会子串匹配, 所以 `react-native-paper` / `react-native-safe-area-context` /
 * `react-native-vector-icons/...` 【全部】被映射到同一个解析结果上。
 *
 * 后果不是「都变成 react-native 的 mock」—— 实测比那更阴:
 * setup.ts 里对这些包各写了一条 `jest.mock(...)`, 它们注册到【同一个 key】上,
 * 于是【最后注册的那条赢】。探针读数:
 *
 *     paperKeys      = SafeAreaProvider|SafeAreaView|initialWindowMetrics|useSafeAreaFrame|useSafeAreaInsets
 *     paperAppbar    = undefined
 *     paperIsSafeArea= true      <-- 两个 import 拿到的是【同一个对象】
 *     paperIsRN      = false     <-- 而且并不是 react-native 的 mock
 *
 * 这就是 `<Appbar.Header>` 报 "Cannot read properties of undefined (reading 'Header')"
 * 的全部原因 —— 屏幕没有回归, 是测试环境把 paper 换成了 safe-area-context。
 *
 * ## 修法
 *
 * 在 moduleNameMapper 里给 `^react-native-paper$` 一条【锚定】的映射指向本文件,
 * 排在宽泛正则【之前】。这样 paper 有了自己独立的解析身份, 不再和别人抢同一个槽。
 *
 * ⛔ 不要把它映射到【真的】react-native-paper —— 实测过, 3 个 suite 在加载期就死
 *    (paper 内部要的 RN 私有模块在这套 mock 下不存在)。要的是替身, 不是真身。
 *
 * ## 写替身的判据
 *
 * 渲染结构要让 `@testing-library/react-native` 的 getByText / getByPlaceholderText /
 * fireEvent.press 能正常工作 —— 也就是说 children 必须【真的渲染出去】,
 * 不能只 createElement 一个空壳 (那正是 FlatList 替身之前的坏法)。
 */
import React from 'react';
import {
  View,
  Text as RNText,
  TouchableOpacity,
  TextInput as RNTextInput,
  ActivityIndicator as RNActivityIndicator,
} from 'react-native';

type AnyProps = Record<string, any>;

/** 容器类: 原样渲染 children, 把 testID 等透传下去 */
const container = (name: string) => {
  const C = ({ children, ...props }: AnyProps) => (
    <View {...props}>{children}</View>
  );
  C.displayName = name;
  return C;
};

/** 文本类: 渲染成 RN Text, 这样 getByText 找得到 */
const textual = (name: string) => {
  const C = ({ children, ...props }: AnyProps) => (
    <RNText {...props}>{children}</RNText>
  );
  C.displayName = name;
  return C;
};

/** 可点击类: 保留 onPress, 这样 fireEvent.press 打得中 */
const pressable = (name: string) => {
  const C = ({ children, onPress, ...props }: AnyProps) => (
    <TouchableOpacity onPress={onPress} {...props}>
      {typeof children === 'string' || typeof children === 'number' ? (
        <RNText>{children}</RNText>
      ) : (
        children
      )}
    </TouchableOpacity>
  );
  C.displayName = name;
  return C;
};

export const Text = textual('Text');
export const Title = textual('Title');
export const Paragraph = textual('Paragraph');
export const Caption = textual('Caption');
export const Subheading = textual('Subheading');
export const Headline = textual('Headline');
export const HelperText = textual('HelperText');

export const Button = pressable('Button');
export const TouchableRipple = pressable('TouchableRipple');
export const Chip = pressable('Chip');
export const FAB = Object.assign(pressable('FAB'), { Group: container('FAB.Group') });

export const Surface = container('Surface');
export const Divider = container('Divider');
export const Banner = container('Banner');
export const Badge = textual('Badge');
export const Modal = container('Modal');
export const ProgressBar = container('ProgressBar');
export const Switch = container('Switch');
export const Checkbox = Object.assign(container('Checkbox'), {
  Item: container('Checkbox.Item'),
  Android: container('Checkbox.Android'),
});
export const RadioButton = Object.assign(container('RadioButton'), {
  Item: container('RadioButton.Item'),
  Group: container('RadioButton.Group'),
  Android: container('RadioButton.Android'),
});
export const ActivityIndicator = ({ children, ...props }: AnyProps) => (
  <RNActivityIndicator {...props}>{children}</RNActivityIndicator>
);

/** Appbar —— 本轮 12 条红的直接触发点 */
export const Appbar = Object.assign(container('Appbar'), {
  Header: container('Appbar.Header'),
  // Content 的 title 是【文字】, 必须渲染出来, 否则 getByText('工序任务') 找不到
  Content: ({ title, subtitle, ...props }: AnyProps) => (
    <View {...props}>
      {title != null ? <RNText>{title}</RNText> : null}
      {subtitle != null ? <RNText>{subtitle}</RNText> : null}
    </View>
  ),
  Action: pressable('Appbar.Action'),
  BackAction: pressable('Appbar.BackAction'),
});

export const IconButton = pressable('IconButton');
export const Icon = container('Icon');

export const Avatar = Object.assign(container('Avatar'), {
  Icon: container('Avatar.Icon'),
  Text: textual('Avatar.Text'),
  Image: container('Avatar.Image'),
});

export const Card = Object.assign(container('Card'), {
  Title: ({ title, subtitle, left, right, ...props }: AnyProps) => (
    <View {...props}>
      {title != null ? <RNText>{title}</RNText> : null}
      {subtitle != null ? <RNText>{subtitle}</RNText> : null}
    </View>
  ),
  Content: container('Card.Content'),
  Actions: container('Card.Actions'),
  Cover: container('Card.Cover'),
});

export const Dialog = Object.assign(container('Dialog'), {
  Title: textual('Dialog.Title'),
  Content: container('Dialog.Content'),
  Actions: container('Dialog.Actions'),
  ScrollArea: container('Dialog.ScrollArea'),
  Icon: container('Dialog.Icon'),
});

export const List = Object.assign(container('List'), {
  Item: ({ title, description, left, right, onPress, ...props }: AnyProps) => (
    <TouchableOpacity onPress={onPress} {...props}>
      {title != null ? <RNText>{title}</RNText> : null}
      {description != null ? <RNText>{description}</RNText> : null}
    </TouchableOpacity>
  ),
  Section: container('List.Section'),
  Subheader: textual('List.Subheader'),
  Accordion: ({ title, children, ...props }: AnyProps) => (
    <View {...props}>
      {title != null ? <RNText>{title}</RNText> : null}
      {children}
    </View>
  ),
  Icon: container('List.Icon'),
});

export const Menu = Object.assign(container('Menu'), {
  Item: ({ title, onPress, ...props }: AnyProps) => (
    <TouchableOpacity onPress={onPress} {...props}>
      {title != null ? <RNText>{title}</RNText> : null}
    </TouchableOpacity>
  ),
});

export const DataTable = Object.assign(container('DataTable'), {
  Header: container('DataTable.Header'),
  Title: textual('DataTable.Title'),
  Row: container('DataTable.Row'),
  Cell: container('DataTable.Cell'),
  Pagination: container('DataTable.Pagination'),
});

export const Snackbar = ({ visible, children, ...props }: AnyProps) =>
  visible ? <View {...props}><RNText>{children}</RNText></View> : null;

/** Portal 直接透传 children —— 测试环境没有真正的 portal 宿主 */
export const Portal = Object.assign(
  ({ children }: AnyProps) => <>{children}</>,
  { Host: ({ children }: AnyProps) => <>{children}</> }
);

/** Searchbar —— placeholder 必须落到真 TextInput 上, getByPlaceholderText 才找得到 */
export const Searchbar = ({ value, onChangeText, placeholder, ...props }: AnyProps) => (
  <RNTextInput
    value={value}
    onChangeText={onChangeText}
    placeholder={placeholder}
    {...props}
  />
);

export const TextInput = Object.assign(
  ({ label, value, onChangeText, placeholder, ...props }: AnyProps) => (
    <RNTextInput
      value={value}
      onChangeText={onChangeText}
      placeholder={placeholder ?? label}
      {...props}
    />
  ),
  { Icon: container('TextInput.Icon'), Affix: container('TextInput.Affix') }
);

/**
 * SegmentedButtons —— 每个分段渲染成可点的 Text。
 * ⚠️ onValueChange 必须真的接上: RN-SCR-02 的断言是「点『已完成』之后
 *    getTasks 带 status: 'COMPLETED' 被调用」, 接不上那条断言就恒绿。
 */
export const SegmentedButtons = ({ value, onValueChange, buttons = [], ...props }: AnyProps) => (
  <View {...props}>
    {buttons.map((b: AnyProps) => (
      <TouchableOpacity
        key={b.value}
        testID={b.testID}
        accessibilityState={{ selected: value === b.value }}
        disabled={b.disabled}
        onPress={() => onValueChange && onValueChange(b.value)}
      >
        <RNText>{b.label}</RNText>
      </TouchableOpacity>
    ))}
  </View>
);

const colors = {
  primary: '#1890ff',
  onPrimary: '#ffffff',
  primaryContainer: '#e6f4ff',
  secondary: '#6B7280',
  background: '#f5f5f5',
  surface: '#ffffff',
  surfaceVariant: '#f0f0f0',
  surfaceDisabled: '#f0f0f0',
  error: '#ef4444',
  errorContainer: '#fee2e2',
  onSurface: '#1F2937',
  onSurfaceVariant: '#6B7280',
  onSurfaceDisabled: '#9CA3AF',
  outline: '#d1d5db',
  outlineVariant: '#e5e7eb',
  elevation: { level0: 'transparent', level1: '#fff', level2: '#fff', level3: '#fff', level4: '#fff', level5: '#fff' },
};

export const MD3LightTheme = { dark: false, roundness: 4, version: 3, colors, fonts: {}, animation: { scale: 1 } };
export const MD3DarkTheme = { ...MD3LightTheme, dark: true };
export const DefaultTheme = MD3LightTheme;
export const DarkTheme = MD3DarkTheme;

export const useTheme = () => MD3LightTheme;
export const withTheme = (C: any) => C;
export const Provider = ({ children }: AnyProps) => <>{children}</>;
export const PaperProvider = Provider;
export const configureFonts = () => ({});
export const adaptNavigationTheme = () => ({ LightTheme: MD3LightTheme, DarkTheme: MD3DarkTheme });
