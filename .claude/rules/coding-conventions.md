---
paths:
  - "frontend/**"
  - "web-admin/**"
  - "backend/**"
---

# 编码规范

**最后更新**: 2026-07-28（合并自旧 api-response-handling / typescript-type-safety / field-naming-convention / jwt-token-handling 四条规则）

## API 响应

统一格式 `{ success: true, data, message }`；失败 `{ success: false, data: null, message, code }`；分页 data 内 `{ content, totalElements, totalPages }`。前端 `!response.success` 即抛 ApiError。错误处理用 `isAxiosError`：401 走 token 刷新、403 提示权限不足、其余展示 `error.response.data.message` 原文（error toast sticky，见 fool-proof-design「4 位一体」）。禁止 `catch (error: any)`、静默失败、返回假数据。

## TypeScript

禁 `as any`；确需绕过用 `@ts-expect-error` + 原因注释。组件 Props / 导航参数用明确类型（如 `RouteProp<StackParamList, 'Detail'>`）。

## 字段命名

Java Entity 字段 camelCase ↔ 数据库列 snake_case（`@Column(name = "...")` 显式标注）↔ JSON API / TypeScript camelCase。禁止 `@JsonProperty("snake_case")`、禁止数据库列名 camelCase、禁止前端 interface 用 snake_case。

## JWT Token

| Token | 有效期 | 存储 |
|-------|--------|------|
| accessToken | 24小时 | SecureStore |
| refreshToken | 7天 | SecureStore |
| tempToken | 5分钟 | 内存 |

Payload：`{ role, factoryId, userId, username }`。RN 端必须 `expo-secure-store`，禁止 AsyncStorage 存 token；401 拦截器自动刷新（`_retry` 标志防循环）；登出清除全部 token；JWT secret 只从环境变量读，生产走 HTTPS。
