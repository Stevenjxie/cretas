# testID 命名规范

## 格式

```
{screen}-{semantic}-{element}
```

- **全小写**，连字符分隔
- **screen**: 屏幕缩写前缀
- **semantic**: 语义描述（功能/用途）
- **element**: 元素类型后缀

## 屏幕前缀缩写

| 前缀 | 屏幕/模块 | 示例 |
|------|----------|------|
| `login` | 登录（Landing + LoginForm） | `login-submit-btn` |
| `fa-home` | 工厂管理员首页 | `fa-home-smartbi-card` |
| `ds-home` | 调度员首页 | `ds-home-plan-card` |
| `ds-plan` | 调度员计划 | `ds-plan-create-btn` |
| `wh-home` | 仓储首页 | `wh-home-inbound-card` |
| `wh-inbound` | 入库 | `wh-inbound-filter-input` |
| `qi-home` | 质检员首页 | `qi-home-scan-btn` |
| `qi-scan` | 质检扫码 | `qi-scan-camera-view` |
| `hr-home` | HR 首页 | `hr-home-attendance-card` |
| `ws-home` | 车间主管首页 | `ws-home-task-list` |
| `pa-home` | 平台管理员首页 | `pa-home-factory-list` |
| `smartbi` | SmartBI 分析 | `smartbi-home-root` |
| `smartbi-exec` | 经营驾驶舱 | `smartbi-exec-kpi-card` |
| `profile` | 个人中心 | `profile-logout-btn` |

## 元素类型后缀

| 后缀 | 元素类型 | 使用场景 |
|------|---------|---------|
| `-btn` | TouchableOpacity / Pressable / NeoButton | 可点击按钮 |
| `-input` | TextInput | 输入框 |
| `-scroll` | ScrollView | 可滚动容器 |
| `-list` | FlatList / SectionList | 列表容器 |
| `-card` | TouchableOpacity (卡片) | 可点击卡片 |
| `-tab` | Tab 按钮 | 标签页切换 |
| `-modal` | Modal 容器 | 弹窗根元素 |
| `-icon-btn` | IconButton / FAB | 图标按钮 |
| `-root` | View (屏幕根容器) | 屏幕级可见性断言 |
| `-toggle` | Switch / Checkbox | 开关/选择 |
| `-picker` | Picker / DatePicker | 选择器 |
| `-badge` | Badge / Chip | 徽章/标签 |

## 核心屏幕 testID 清单（PoC 优先级）

### 1. EnhancedLoginScreen（所有角色必经）

```tsx
// Landing 视图
<TouchableOpacity testID="landing-login-btn">      // 进入登录表单
<TouchableOpacity testID="landing-register-btn">    // 进入注册

// LoginForm 视图
<TextInput testID="login-username-input">            // 用户名
<TextInput testID="login-password-input">            // 密码
<TouchableOpacity testID="login-toggle-password">    // 密码可见切换
<TouchableOpacity testID="login-remember-checkbox">  // 记住我
<TouchableOpacity testID="login-submit-btn">         // 登录提交
<TouchableOpacity testID="login-register-link">      // 去注册
<TouchableOpacity testID="login-forgot-link">        // 忘记密码
```

### 2. FAHomeScreen（工厂管理员首页）

```tsx
<View testID="fa-home-root">                         // 屏幕根容器
<TouchableOpacity testID="fa-home-smartbi-card">     // SmartBI 入口
<TouchableOpacity testID="fa-home-production-card">  // 生产管理
<TouchableOpacity testID="fa-home-inventory-card">   // 库存管理
<TouchableOpacity testID="fa-home-quality-card">     // 质量管理
<ScrollView testID="fa-home-scroll">                 // 主滚动区
```

### 3. SmartBIHomeScreen

```tsx
<View testID="smartbi-home-root">
<TouchableOpacity testID="smartbi-exec-dashboard-card">
<TouchableOpacity testID="smartbi-sales-card">
<TouchableOpacity testID="smartbi-finance-card">
```

### 4. DSHomeScreen（调度员首页）

```tsx
<View testID="ds-home-root">
<TouchableOpacity testID="ds-home-plan-card">
<TouchableOpacity testID="ds-home-task-card">
```

### 5. WHHomeScreen（仓储首页）

```tsx
<View testID="wh-home-root">
<TouchableOpacity testID="wh-home-inbound-card">
<TouchableOpacity testID="wh-home-outbound-card">
```

## 规则

1. **不要使用文本作为 testID** — i18n 双语环境下文本不稳定
2. **不要使用行号** — 代码重构后行号会变
3. **每个可交互元素必须有 testID** — 按钮、输入框、卡片、列表
4. **屏幕根容器必须有 testID** — 用于 `assertVisible` 断言页面加载
5. **封装组件需透传 testID** — NeoButton 等组件需确认 testID 到达原生 View
