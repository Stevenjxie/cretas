# Maestro Flow 模板

## 目录结构

```
tests/e2e-native/.maestro/
├── config.yaml
├── flows/
│   ├── common/
│   │   ├── _login.yaml           # 登录子流（所有角色复用）
│   │   ├── _logout.yaml          # 退出子流
│   │   └── _wait_for_app.yaml    # 等待应用启动
│   ├── factory-admin/
│   │   ├── smoke.yaml            # 冒烟测试
│   │   └── smartbi-dashboard.yaml
│   ├── dispatcher/
│   │   ├── smoke.yaml
│   │   └── plan-create.yaml
│   ├── quality-inspector/
│   │   └── smoke.yaml
│   ├── warehouse/
│   │   └── smoke.yaml
│   ├── hr/
│   │   └── smoke.yaml
│   ├── workshop/
│   │   └── smoke.yaml
│   └── platform-admin/
│       └── smoke.yaml
└── screenshots/
```

## config.yaml

```yaml
# tests/e2e-native/.maestro/config.yaml
flows:
  - flows/factory-admin/**
  - flows/dispatcher/**
  - flows/quality-inspector/**
  - flows/warehouse/**
  - flows/hr/**
  - flows/workshop/**
  - flows/platform-admin/**
  # common/ 不列入（仅被 runFlow 引用）
```

## 通用子流

### _login.yaml（关键模板 — 同组件状态切换）

```yaml
# flows/common/_login.yaml
# 登录子流：Landing 视图 → LoginForm 视图 → 提交
# 注意：登录是同一组件内的状态切换，不是页面导航
---
# Step 1: 等待 Landing 视图加载
- assertVisible:
    id: "landing-login-btn"
  timeout: 10000

# Step 2: 点击进入登录表单（同组件内切换视图）
- tapOn:
    id: "landing-login-btn"

# Step 3: 等待 LoginForm 视图出现
- assertVisible:
    id: "login-username-input"
  timeout: 5000

# Step 4: 填写用户名
- tapOn:
    id: "login-username-input"
- clearText
- inputText: ${USERNAME}

# Step 5: 填写密码
- tapOn:
    id: "login-password-input"
- clearText
- inputText: ${PASSWORD}

# Step 6: 提交登录
- tapOn:
    id: "login-submit-btn"

# Step 7: 等待首页加载（根据角色不同，首页 testID 不同）
- assertVisible:
    id: ${HOME_ROOT_ID}
  timeout: 15000
```

### _logout.yaml

```yaml
# flows/common/_logout.yaml
---
# 导航到个人中心
- tapOn:
    id: "tab-profile"
  timeout: 5000

# 点击退出
- tapOn:
    id: "profile-logout-btn"
  timeout: 5000

# 确认退出
- tapOn:
    id: "logout-confirm-btn"
  timeout: 3000

# 验证回到登录页
- assertVisible:
    id: "landing-login-btn"
  timeout: 10000
```

### _wait_for_app.yaml

```yaml
# flows/common/_wait_for_app.yaml
---
- waitForAnimationToEnd
- assertVisible:
    id: "landing-login-btn"
  timeout: 15000
```

## 角色冒烟测试模板

### factory-admin/smoke.yaml

```yaml
# flows/factory-admin/smoke.yaml
appId: com.cretas.foodtrace
env:
  USERNAME: factory_admin1
  PASSWORD: "123456"
  HOME_ROOT_ID: fa-home-root
---
- launchApp:
    clearState: true

- runFlow: ../common/_wait_for_app.yaml

- runFlow:
    file: ../common/_login.yaml
    env:
      USERNAME: ${USERNAME}
      PASSWORD: ${PASSWORD}
      HOME_ROOT_ID: ${HOME_ROOT_ID}

# 验证首页核心卡片
- assertVisible:
    id: "fa-home-smartbi-card"
  timeout: 5000

- assertVisible:
    id: "fa-home-production-card"

- assertVisible:
    id: "fa-home-inventory-card"
```

### dispatcher/smoke.yaml

```yaml
# flows/dispatcher/smoke.yaml
appId: com.cretas.foodtrace
env:
  USERNAME: dispatcher1
  PASSWORD: "123456"
  HOME_ROOT_ID: ds-home-root
---
- launchApp:
    clearState: true

- runFlow: ../common/_wait_for_app.yaml

- runFlow:
    file: ../common/_login.yaml
    env:
      USERNAME: ${USERNAME}
      PASSWORD: ${PASSWORD}
      HOME_ROOT_ID: ${HOME_ROOT_ID}

# 验证调度员首页
- assertVisible:
    id: "ds-home-plan-card"
  timeout: 5000
```

## 功能测试模板

### factory-admin/smartbi-dashboard.yaml

```yaml
# flows/factory-admin/smartbi-dashboard.yaml
appId: com.cretas.foodtrace
env:
  USERNAME: factory_admin1
  PASSWORD: "123456"
  HOME_ROOT_ID: fa-home-root
---
- launchApp:
    clearState: true

- runFlow: ../common/_wait_for_app.yaml

- runFlow:
    file: ../common/_login.yaml
    env:
      USERNAME: ${USERNAME}
      PASSWORD: ${PASSWORD}
      HOME_ROOT_ID: ${HOME_ROOT_ID}

# 进入 SmartBI
- tapOn:
    id: "fa-home-smartbi-card"

- assertVisible:
    id: "smartbi-home-root"
  timeout: 10000

# 进入经营驾驶舱
- tapOn:
    id: "smartbi-exec-dashboard-card"

- assertVisible:
    id: "smartbi-exec-root"
  timeout: 15000
```

### dispatcher/plan-create.yaml

```yaml
# flows/dispatcher/plan-create.yaml
appId: com.cretas.foodtrace
env:
  USERNAME: dispatcher1
  PASSWORD: "123456"
  HOME_ROOT_ID: ds-home-root
---
- launchApp:
    clearState: true

- runFlow: ../common/_wait_for_app.yaml

- runFlow:
    file: ../common/_login.yaml
    env:
      USERNAME: ${USERNAME}
      PASSWORD: ${PASSWORD}
      HOME_ROOT_ID: ${HOME_ROOT_ID}

# 进入计划列表
- tapOn:
    id: "ds-home-plan-card"

- assertVisible:
    id: "ds-plan-list-root"
  timeout: 10000

# 点击创建
- tapOn:
    id: "ds-plan-create-btn"

- assertVisible:
    id: "ds-plan-form-root"
  timeout: 5000
```

## 编写规则

1. **所有选择器使用 `id:`** — 基于 testID，禁止 text 匹配
2. **禁止坐标定位** — 布局变化即失效
3. **每个断言设置 timeout** — 网络请求和动画需要等待
4. **使用 `runFlow` 复用子流** — 避免重复编写登录逻辑
5. **使用 `env:` 参数化** — 角色凭证通过环境变量注入
6. **子流文件以 `_` 开头** — 不会被 config.yaml 直接执行
7. **每个 flow 以 `launchApp: clearState: true` 开头** — 确保干净状态
8. **登录断言基于元素可见性** — 不依赖 URL 或页面标题
