---
name: e2e-native
description: Cretas React Native 原生 E2E 测试。使用 Maestro 执行原生 UI 测试，涵盖 testID 扫描注入、Maestro YAML 生成、多角色测试流执行、测试报告。
allowed-tools:
  - Bash
  - Read
  - Edit
  - Write
  - Glob
  - Grep
---

# React Native 原生 E2E 测试

## 命令解析

解析用户输入的参数（`$ARGUMENTS`）：

| 输入 | 动作 |
|------|------|
| _(空)_ | 显示帮助：列出所有子命令和用法 |
| `scan` | 扫描全部 .tsx 文件的 testID 覆盖率 |
| `scan --fix` | 扫描 + 为缺失组件生成 testID 建议，Claude 审核后注入 |
| `inject {screen}` | 为指定屏幕注入 testID（如 `inject EnhancedLoginScreen`） |
| `inject --batch {module}` | 批量为模块下所有屏幕注入 testID（如 `inject --batch factory-admin`） |
| `gen {role}` | 为指定角色生成 Maestro 测试流（如 `gen factory_admin`） |
| `gen all` | 为所有 8 角色生成基础测试流 |
| `run {role}` | 执行指定角色的 Maestro 测试流（如 `run factory_admin`） |
| `run all` | 顺序执行所有角色的测试流 |
| `report` | 基于最近一次 run 结果生成 Markdown 测试报告 |

可选参数：
- `--apk <path>` — 指定本地 APK 路径（run 子命令可用）
- `--device <id>` — 指定设备 ID（run 子命令可用）

## 配置

| 项目 | 默认值 | 说明 |
|------|--------|------|
| APP_ID | `com.cretas.foodtrace` | 应用包名 |
| SCREENS_DIR | `frontend/CretasFoodTrace/src/screens` | 屏幕文件目录 |
| COMPONENTS_DIR | `frontend/CretasFoodTrace/src/components` | 组件文件目录 |
| MAESTRO_DIR | `tests/e2e-native/.maestro` | Maestro 流文件目录 |
| SCREENSHOT_DIR | `tests/e2e-native/screenshots` | 失败截图保存目录 |
| REPORT_DIR | `tests/e2e-native/reports` | 报告输出目录 |
| FLOW_TIMEOUT | `60000` | 单流超时 ms |
| TOTAL_TIMEOUT | `600000` | 总超时 ms |

## 测试账号

| 角色 | 用户名 | 密码 | factoryId | Navigator |
|------|--------|------|-----------|-----------|
| 工厂超级管理员 | `factory_admin1` | `123456` | F001 | FactoryAdminNavigator |
| 平台管理员 | `platform_admin` | `123456` | - | MainNavigator |
| 车间主管 | `workshop_sup1` | `123456` | F001 | WorkshopSupervisorNavigator |
| 仓储主管 | `warehouse_mgr1` | `123456` | F001 | WarehouseManagerNavigator |
| HR 管理员 | `hr_admin1` | `123456` | F001 | HRNavigator |
| 调度员 | `dispatcher1` | `123456` | F001 | DispatcherNavigator |
| 质检员 | `quality_insp1` | `123456` | F001 | QualityInspectorNavigator |

---

## 执行流程

### Phase 0: 环境检查

**所有子命令均先执行此检查**（scan/inject 仅检查项目结构，run 需检查全部）：

**scan/inject/gen 需要：**
1. 确认 `frontend/CretasFoodTrace/src/screens/` 目录存在
2. 确认项目结构可访问

**run 额外需要：**
1. 检测 Maestro CLI：`maestro --version`
   - 未安装 → 输出：`curl -Ls "https://get.maestro.mobile.dev" | bash`，终止
2. 检测设备/模拟器：`adb devices` 或 `xcrun simctl list booted`
   - 无设备 → 提示启动模拟器或连接真机，终止
3. 检测 APK 安装状态：`adb shell pm list packages | grep com.cretas.foodtrace`
   - 未安装 → 提示安装方式：
     - `adb install <apk-path>`（若有 APK）
     - `eas build --profile preview --platform android`（云端构建）
     - `npx expo run:android`（本地开发构建）
   - 终止（禁止降级处理）
4. 检测 Maestro flows 目录存在：`tests/e2e-native/.maestro/flows/`
   - 不存在 → 提示先运行 `/e2e-native gen {role}`

---

### scan 子命令

**目的**: 扫描前端代码中 testID 覆盖率，识别缺失 testID 的可交互组件。

**步骤**：

1. 运行扫描脚本：
   ```bash
   python3 .claude/skills/e2e-native/scripts/scan-testids.py frontend/CretasFoodTrace/src/
   ```

2. 脚本输出 CSV 格式结果，Claude 将其格式化为表格展示：

   ```markdown
   ## testID 覆盖率报告

   | 文件 | 可交互元素 | 已有 testID | 覆盖率 |
   |------|-----------|------------|--------|
   | screens/auth/EnhancedLoginScreen.tsx | 8 | 0 | 0% |
   | screens/factory-admin/home/FAHomeScreen.tsx | 12 | 0 | 0% |
   | ... | ... | ... | ... |

   ### 汇总
   - 扫描文件: N
   - 可交互元素总数: N
   - 已有 testID: N
   - 覆盖率: N%

   ### 按组件类型
   | 组件类型 | 总数 | 已有 testID | 覆盖率 |
   |---------|------|------------|--------|
   | TouchableOpacity | N | 0 | 0% |
   | TextInput | N | 0 | 0% |
   | Pressable | N | 0 | 0% |
   | ScrollView | N | 0 | 0% |
   | NeoButton | N | 0 | 0% |
   | Paper IconButton/FAB | N | 0 | 0% |
   | FlatList/SectionList | N | 0 | 0% |
   ```

3. `--fix` 模式额外输出建议的 testID 名称（基于文件名+组件类型+上下文推断），Claude 逐条审核后通过 Edit 写入。

**扫描的组件类型**（6+ 种）：

| 组件 | 正则模式 | 说明 |
|------|---------|------|
| TouchableOpacity | `<TouchableOpacity` | RN 原生可点击 |
| TextInput | `<TextInput` | RN 原生输入框 |
| Pressable | `<Pressable` | RN 原生可按压 |
| ScrollView | `<ScrollView` | 可滚动容器 |
| NeoButton | `<NeoButton` | 自定义封装按钮（需验证 testID 透传） |
| Button/IconButton/FAB | `<Button\b\|<IconButton\|<FAB` | react-native-paper 组件 |
| FlatList/SectionList | `<FlatList\|<SectionList` | 列表容器 |

---

### inject 子命令

**目的**: 为指定屏幕的可交互组件添加 testID 属性。

**步骤**：

1. **定位目标文件**：
   - `inject EnhancedLoginScreen` → 用 Glob 查找 `**/EnhancedLoginScreen.tsx`
   - `inject --batch factory-admin` → 用 Glob 查找 `screens/factory-admin/**/*.tsx`

2. **读取目标文件**，识别缺失 testID 的可交互元素

3. **封装组件预检**（首次 inject 时执行一次）：
   - 检查 NeoButton 等自定义组件是否透传 testID：
     ```bash
     grep -n "testID" frontend/CretasFoodTrace/src/components/common/NeoButton.tsx
     ```
   - 若不透传，**先修改组件定义**添加 testID prop 透传：
     ```tsx
     // NeoButton.tsx — 确保 testID 透传到根元素
     const NeoButton: React.FC<NeoButtonProps & { testID?: string }> = ({
       testID, ...props
     }) => (
       <Button testID={testID} {...props} />
     );
     ```
   - 修改后通知用户此为公共组件变更

4. **按命名规范添加 testID**：
   - 读取 `references/testid-naming-convention.md` 获取命名规则
   - 格式: `{screen}-{element}-{semantic}`
   - 使用 Edit 工具逐个添加，仅添加 `testID` 属性，不修改其他代码

5. **输出注入报告**：
   ```markdown
   ## testID 注入报告: EnhancedLoginScreen.tsx

   | 元素 | 组件类型 | testID | 行号 |
   |------|---------|--------|------|
   | 登录按钮(Landing) | TouchableOpacity | landing-login-btn | 78 |
   | 用户名输入 | TextInput | login-username-input | 145 |
   | 密码输入 | TextInput | login-password-input | 162 |
   | 登录提交 | TouchableOpacity | login-submit-btn | 195 |
   | ... | ... | ... | ... |

   已注入: N 个 testID
   ```

**注意事项**：
- 仅添加 `testID` 属性，不修改样式、逻辑或其他属性
- 所有选择器使用 `id:` 前缀，**禁止使用文本匹配**（i18n 双语环境文本不稳定）
- NeoButton 等封装组件需先确认 testID 透传后再在调用处添加

---

### gen 子命令

**目的**: 为指定角色生成 Maestro YAML 测试流。

**步骤**：

1. **读取参考文档**：
   - `references/maestro-flow-templates.md` — YAML 模板
   - `references/role-flow-matrix.md` — 角色对应屏幕

2. **创建目录结构**（若不存在）：
   ```
   tests/e2e-native/.maestro/
   ├── config.yaml
   ├── flows/
   │   ├── common/
   │   │   ├── _login.yaml
   │   │   ├── _logout.yaml
   │   │   └── _wait_for_app.yaml
   │   └── {role}/
   │       ├── smoke.yaml
   │       └── {feature}.yaml
   └── screenshots/
   ```

3. **生成 common 子流**（仅首次）：
   - `_login.yaml` — 登录子流（**关键**: 登录是同组件内状态切换，非页面导航）
   - `_logout.yaml` — 退出子流
   - `_wait_for_app.yaml` — 等待应用启动

4. **生成角色测试流**：
   - 读取该角色对应的 Navigator 和屏幕列表
   - 生成 `smoke.yaml`（登录 → 首页 → 验证核心卡片可见）
   - 生成功能测试流（如 `smartbi-dashboard.yaml`、`plan-create.yaml`）

5. **YAML 编写规则**（严格遵循）：
   - 所有元素定位使用 `id:` 选择器（基于 testID）
   - **禁止使用 text 选择器**（i18n 双语使文本不稳定）
   - **禁止使用坐标定位**
   - 使用 `assertVisible` + `timeout` 等待元素出现
   - 使用 `env:` 块参数化角色凭证
   - 使用 `runFlow:` 复用 common 子流

6. **输出生成报告**：
   ```markdown
   ## Maestro Flow 生成报告: factory_admin

   | Flow 文件 | 测试范围 | 步骤数 | 依赖 testID |
   |-----------|---------|--------|------------|
   | common/_login.yaml | 登录 | 12 | 5 个 |
   | factory-admin/smoke.yaml | 首页验证 | 8 | 6 个 |
   | factory-admin/smartbi-dashboard.yaml | SmartBI | 15 | 8 个 |

   生成文件: N 个
   依赖 testID 总数: N 个（请确认已通过 inject 注入）
   ```

---

### run 子命令

**目的**: 执行 Maestro 测试流并收集结果。

**步骤**：

1. 执行 Phase 0 完整环境检查

2. 运行 Maestro 测试：
   ```bash
   bash .claude/skills/e2e-native/scripts/run-maestro.sh {role} [--apk path] [--device id]
   ```

3. 脚本内部流程：
   - 检测设备连接
   - 安装 APK（若指定 `--apk`）
   - 执行 `maestro test tests/e2e-native/.maestro/flows/{role}/`
   - 收集截图到 `tests/e2e-native/screenshots/`
   - 输出 JSON 结果到 `tests/e2e-native/reports/`

4. Claude 解析脚本输出，展示执行结果：
   ```markdown
   ## Maestro 测试执行: factory_admin

   | Flow | 状态 | 耗时 | 失败步骤 |
   |------|------|------|---------|
   | smoke.yaml | PASS ✅ | 12s | — |
   | smartbi-dashboard.yaml | FAIL ❌ | 45s | assertVisible: smartbi-home-root |

   通过: N/M
   截图: tests/e2e-native/screenshots/
   ```

---

### report 子命令

**目的**: 基于最近的测试结果生成结构化报告。

**步骤**：

1. 运行报告脚本：
   ```bash
   python3 .claude/skills/e2e-native/scripts/gen-report.py tests/e2e-native/reports/
   ```

2. 输出格式：
   ```markdown
   ## E2E Native 测试报告
   **日期**: YYYY-MM-DD HH:MM
   **设备**: {device_name}

   ### 总览
   | 指标 | 值 |
   |------|-----|
   | 总流程数 | N |
   | 通过 | N |
   | 失败 | N |
   | 跳过 | N |
   | 总耗时 | Ns |

   ### 按角色
   | 角色 | 流程 | 通过 | 失败 | 耗时 |
   |------|------|------|------|------|
   | factory_admin | 3 | 2 | 1 | 45s |

   ### 失败详情
   | Flow | 失败步骤 | 错误信息 | 截图 |
   |------|---------|---------|------|
   | smartbi-dashboard.yaml | Step 8 | Element not found: id=smartbi-home-root | screenshots/xxx.png |

   ### testID 覆盖率趋势
   | 日期 | 覆盖率 | 新增 testID |
   |------|--------|------------|
   | 2026-02-21 | 0% | 0 |
   ```

---

## 错误处理

| 场景 | 处理 |
|------|------|
| **Maestro CLI 未安装** | 输出安装命令，终止 |
| **无设备/模拟器** | 提示启动模拟器或连接真机，终止 |
| **APK 未安装** | 提示 3 种安装方式（adb install / EAS build / expo run），终止 |
| **testID 未注入** | 提示运行 `/e2e-native scan` 查看覆盖率，然后 `/e2e-native inject {screen}` |
| **Maestro flows 不存在** | 提示运行 `/e2e-native gen {role}` |
| **登录失败** | 截图 + 检查测试账号有效性 + 提示确认后端服务状态 |
| **元素未找到** | 截图 + 提示检查 testID 是否已注入（`maestro hierarchy` 可查看 View 树） |
| **Flow 超时 (>60s)** | 截图 + 标记 TIMEOUT，跳过到下一 Flow |
| **Maestro 崩溃** | 保存日志，提示重启模拟器后重试 |
| **封装组件不透传 testID** | 提示先修改组件定义（NeoButton 等），再重新 inject |

---

## 与其他 Skill 的协作

```
/build-android-apk          →  构建 APK（Windows 环境）
eas build --profile preview  →  构建 APK（macOS/CI，推荐）
                                  ↓
/e2e-native scan             →  扫描 testID 覆盖率
/e2e-native inject {screen}  →  注入缺失的 testID
                                  ↓
/e2e-native gen {role}       →  生成 Maestro YAML 测试流
                                  ↓
/e2e-native run {role}       →  执行原生 E2E 测试
                                  ↓
/e2e-native report           →  生成测试报告
```

## 关键约束

1. **所有选择器必须使用 `id:`**（基于 testID），禁止文本匹配（i18n 双语环境）
2. **登录是同组件内状态切换**（LandingView → LoginFormView），不是页面导航
3. **封装组件需先修改定义**（NeoButton 等），才能在调用处使用 testID
4. **8 个角色有独立的 Navigator**，每个角色的首页和功能路径完全不同
5. **macOS 环境推荐 EAS Build 或 `npx expo run:android`** 构建 APK
