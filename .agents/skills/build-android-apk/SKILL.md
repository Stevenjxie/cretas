---
name: build-android-apk
description: 自动化构建 React Native Android APK。包括依赖检查、Debug/Release 构建、设备安装。使用此 Skill 来构建 APK、测试安装、或排查构建问题。
allowed-tools:
  - Bash
  - Read
---

# 构建 Android APK

## 环境要求

| 项目 | 路径 |
|------|------|
| JAVA_HOME | `C:/Program Files/Java/jdk-17` |
| ANDROID_HOME | `C:\Users\Steve\AppData\Local\Android\Sdk` |
| 项目目录 | `frontend/CretasFoodTrace/android` |
| 输出 APK | `android/app/build/outputs/apk/release/app-release.apk` (~98MB) |

---

## 快速构建 (Release)

```bash
# 1. 添加 CMake 路径修复 (必须! Windows 250字符限制)
#    在 frontend/CretasFoodTrace/android/build.gradle 的末尾添加:
cat >> frontend/CretasFoodTrace/android/build.gradle << 'GRADLE'

// CMake path length workaround (Windows MAX_PATH)
subprojects { subproject ->
    def applyCmakeWorkaround = {
        def androidExt = subproject.extensions.findByName('android')
        if (androidExt != null) {
            try {
                if (androidExt.externalNativeBuild?.cmake?.path != null) {
                    androidExt.externalNativeBuild.cmake.buildStagingDirectory = file("C:/b/${subproject.name}")
                }
            } catch (Exception ignored) {}
        }
    }
    try { subproject.afterEvaluate { applyCmakeWorkaround() } }
    catch (Exception e) { applyCmakeWorkaround() }
}
GRADLE

# 2. 清理旧 CMake 缓存
rm -rf node_modules/react-native-reanimated/android/.cxx

# 3. 构建
ANDROID_HOME="C:\\Users\\Steve\\AppData\\Local\\Android\\Sdk" \
JAVA_HOME="C:/Program Files/Java/jdk-17" \
cmd //c "cd frontend\CretasFoodTrace\android && gradlew.bat assembleRelease"

# 4. 复制到项目根目录
cp frontend/CretasFoodTrace/android/app/build/outputs/apk/release/app-release.apk ./CretasFoodTrace.apk

# 5. 清理 CMake 临时目录 + 恢复 build.gradle
rm -rf C:/b
git checkout frontend/CretasFoodTrace/android/build.gradle
```

## Debug 构建

```bash
ANDROID_HOME="C:\\Users\\Steve\\AppData\\Local\\Android\\Sdk" \
JAVA_HOME="C:/Program Files/Java/jdk-17" \
cmd //c "cd frontend\CretasFoodTrace\android && gradlew.bat assembleDebug"
```

---

## 关键注意事项

### CMake 路径修复 (CRITICAL)

Windows 下 `react-native-reanimated` 的 CMake 对象路径超过 250 字符限制，必须用 `buildStagingDirectory` 重定向到短路径 (`C:/b/`)。

- **构建前**: 添加 workaround 到 `build.gradle`
- **构建后**: 用 `git checkout` 恢复 `build.gradle`（不要提交这个修改）
- **如果构建失败**: 先清理 `rm -rf node_modules/react-native-reanimated/android/.cxx`

### 其他注意事项

- Junction (`mklink /J`) 不可用 — Metro bundler 会解析回原始路径
- 构建耗时约 5-7 分钟
- 包名: `com.cretas.foodtrace`
- Hermes 引擎: 禁止使用 `toLocaleString`/`toLocaleDateString`/`toLocaleTimeString`，使用 `src/utils/formatters.ts`

---

## 参考文档

- `references/build-troubleshooting.md` - 构建问题排查
- `references/signing-guide.md` - 签名配置
