## React Native 设计规范

**技术栈**: Expo 53+ / React Native Paper (MD3) / StyleSheet.create()

### 颜色

| Token | 值 | 用途 |
|-------|----|------|
| primary | `#1890FF` | 主色 |
| secondary | `#5856D6` | 辅助色 |
| success | `#34C759` | 成功 |
| warning | `#FFCC00` | 警告 |
| error | `#FF3B30` | 错误 |
| background | `#F5F5F5` | 页面背景 |
| surface | `#FFFFFF` | 卡片背景 |
| textPrimary | `#1F2937` | 主文字 |
| textSecondary | `#6B7280` | 辅助文字 |
| border | `#E5E7EB` | 边框 |
| primaryContainer | `#E6F7FF` | 浅蓝容器 |

### 间距

```typescript
const spacing = { xs: 4, s: 8, m: 12, l: 16, xl: 24, xxl: 32, section: 40 };
```

### 圆角

```typescript
const borderRadius = { xs: 4, s: 8, m: 12, l: 16, xl: 24, round: 999 };
```

### 阴影

```typescript
const shadows = {
  small: Platform.select({
    ios: { shadowOffset: { width: 0, height: 2 }, shadowOpacity: 0.05, shadowRadius: 4 },
    android: { elevation: 2 },
  }),
  medium: Platform.select({
    ios: { shadowOffset: { width: 0, height: 4 }, shadowOpacity: 0.08, shadowRadius: 12 },
    android: { elevation: 4 },
  }),
};
```

### 按钮尺寸

| Size | Height | paddingH | fontSize |
|------|--------|----------|----------|
| small | 32 | 12 | 13 |
| medium | 44 | 20 | 15 |
| large | 56 | 32 | 17 |

### 组件模式

优先使用项目已有组件: `ScreenWrapper`, `NeoCard`, `NeoButton`, `StatusBadge`。

```tsx
import { ScreenWrapper } from '@/components/common/ScreenWrapper';
import { NeoCard } from '@/components/common/NeoCard';

export default function MyScreen() {
  return (
    <ScreenWrapper title="页面标题">
      <NeoCard variant="elevated" style={{ marginBottom: spacing.l }}>
        {/* 内容 */}
      </NeoCard>
    </ScreenWrapper>
  );
}
```

`ScreenWrapper` 包含 SafeAreaView + max-width 500 居中。

