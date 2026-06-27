# TypeScript 类型安全规范

**最后更新**: 2026-02-22

### 统计现状 (更新于 2026-02-22)

当前项目存在 **273 处** `as any` 类型断言，需逐步清理。

---

## 禁止 `as any`

```typescript
// ❌ BAD
const data = response as any;
catch (error: any) { ... }

// ✅ GOOD - 类型守卫
function isApiResponse<T>(obj: unknown): obj is ApiResponse<T> {
  return typeof obj === 'object' && obj !== null && 'success' in obj;
}
```

**例外**（必须注释）:
```typescript
// @ts-expect-error - 第三方库类型不完整
const theme = useTheme() as any;
```

## 组件 Props

```typescript
// ❌ BAD
const MyComponent = (props: any) => { ... };

// ✅ GOOD
interface Props { title: string; onPress: () => void; }
const MyComponent: React.FC<Props> = ({ title, onPress }) => { ... };
```

## 导航参数

```typescript
// ❌ BAD
const route = useRoute<any>();

// ✅ GOOD
const route = useRoute<RouteProp<StackParamList, 'Detail'>>();
```
