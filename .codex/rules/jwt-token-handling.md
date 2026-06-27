# JWT Token 处理规范

**最后更新**: 2026-01-22

---

## Token 类型

| Token | 有效期 | 存储 |
|-------|--------|------|
| accessToken | 24小时 | SecureStore |
| refreshToken | 7天 | SecureStore |
| tempToken | 5分钟 | 内存 |

## Payload 结构

```json
{ "role": "factory_super_admin", "factoryId": "F001", "userId": 22, "username": "admin" }
```

---

## 前端存储

```typescript
// ✅ 必须使用 SecureStore
import * as SecureStore from 'expo-secure-store';
await SecureStore.setItemAsync('access_token', token);

// ❌ 禁止 AsyncStorage
await AsyncStorage.setItem('access_token', token);
```

---

## 401 自动刷新

```typescript
axios.interceptors.response.use(null, async (error) => {
  if (error.response?.status === 401 && !error.config._retry) {
    error.config._retry = true;
    const newToken = await refreshToken();
    error.config.headers.Authorization = `Bearer ${newToken}`;
    return axios(error.config);
  }
  return Promise.reject(error);
});
```

---

## 安全要求

- HTTPS (生产环境)
- JWT Secret 从环境变量读取
- 登出时清除所有 Token
