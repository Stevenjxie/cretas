---
name: code-review
description: 审查代码质量、性能、安全性和设计模式。检查项目特定的反模式（降级处理、错误处理、类型安全）。使用此 Skill 来审查 PR、优化代码、或检查 TypeScript 类型安全性。
allowed-tools:
  - Read
  - Grep
  - Glob
  - Bash
---

# 代码审查 Skill

## 审查检查清单

### 错误处理
- [ ] try-catch 使用具体错误类型（不是 `any`）
- [ ] 错误有用户提示（不只是 console.log）
- [ ] 没有空 catch 块
- [ ] Promise.allSettled 失败有提示

### 数据验证
- [ ] 没有 `as any`（或有充分理由并注释）
- [ ] 使用 `??` 而非 `||` 作为默认值
- [ ] 可选链不超过 2 层
- [ ] TypeScript strict 模式通过

### 降级处理
- [ ] 降级时有用户通知
- [ ] 没有 SecureStore 静默降级到 AsyncStorage
- [ ] 区分开发/生产环境

### 配置管理
- [ ] 没有硬编码超时/重试次数
- [ ] 没有硬编码 GPS/URL/密码
- [ ] 角色判断使用枚举

### TODO/安全
- [ ] 生产代码没有 TODO/FIXME
- [ ] Token 使用 SecureStore
- [ ] 不记录敏感信息到日志
- [ ] 没有硬编码凭证 (DB 密码等)

### Hermes 兼容性 (React Native)
- [ ] 没有 `toLocaleString` / `toLocaleDateString` / `toLocaleTimeString`
- [ ] 使用 `src/utils/formatters.ts` 中的安全函数替代

## 自动化检查命令

```bash
# TypeScript 检查 (RN 前端)
cd frontend/CretasFoodTrace && npx tsc --noEmit --skipLibCheck

# TypeScript 检查 (Vue Web Admin)
cd web-admin && npx vue-tsc --noEmit
```

使用 Grep 工具检查反模式:
- `as any` → Grep pattern `as any` in `frontend/CretasFoodTrace/src/`
- `TODO|FIXME` → Grep pattern `TODO|FIXME` in `frontend/CretasFoodTrace/src/`
- `AsyncStorage.*token` → Grep pattern `AsyncStorage.*token` in `frontend/CretasFoodTrace/src/`
- `toLocaleString` → Grep pattern `toLocaleString|toLocaleDateString|toLocaleTimeString` in `frontend/CretasFoodTrace/src/`
- 硬编码密码 → Grep pattern `password.*=.*["']` in `backend/java/cretas-api/src/`

## 项目特定规则

### 禁止使用的 API 客户端

| 禁止使用 | 应该使用 |
|----------|----------|
| `attendanceApiClient` | `timeclockApiClient` |
| `employeeApiClient` | `userApiClient` |
| `enhancedApiClient` | `apiClient` |

### TypeScript 配置

- `strict: true`
- `noImplicitAny: true`
- `strictNullChecks: true`

## 参考

- 规范文件: `.claude/rules/` 目录
- ESLint: `.eslintrc.js`
