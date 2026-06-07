# 常见错误速查表

## 后端错误 (Spring Boot + PostgreSQL)

| 错误 | 原因 | 解决命令 |
|------|------|----------|
| `Unknown column 'xxx' in 'field list'` | Entity 字段与数据库不匹配 | `ALTER TABLE xxx ADD COLUMN created_at TIMESTAMP DEFAULT NOW()` |
| `NullPointerException` | 空指针访问 | `ssh root@47.100.235.168 "grep -A30 'NullPointerException' /www/wwwroot/cretas/cretas-backend.log"` |
| `Infinite recursion` (JSON) | 循环引用 | 添加 `@JsonIgnore` 或 `@JsonBackReference` |
| `401 Unauthorized` | Token 过期/格式错误 | `echo "TOKEN" \| cut -d'.' -f2 \| base64 -d \| jq '.exp'` |
| `403 Forbidden` | 用户角色/工厂权限不匹配 | 检查 JWT 中的 role 和 factoryId |
| `404 Not Found` | 路径错误或资源不存在 | 确认 `/api/mobile/` 前缀和资源 ID |
| `BYTEA → long` 警告 | Hibernate 类型映射差异 | 非阻断，可忽略 (PG 迁移遗留) |

## 前端错误 (React Native)

| 错误 | 原因 | 解决命令 |
|------|------|----------|
| `Network request failed` | 无法连接 API | `curl -s http://47.100.235.168:10010/api/mobile/health` |
| `timeout exceeded` | 请求超时 | 检查网络/增加超时时间 |
| `Property 'xxx' does not exist` | 类型定义缺失 | 更新 interface 添加属性 |
| `Type 'xxx' is not assignable` | 类型不匹配 | 检查 API 响应类型，避免 `as any` |
| 白屏/崩溃 | Metro 编译错误 | `npx expo start --clear` |
| 依赖冲突 | node_modules 损坏 | `rm -rf node_modules && npm install` |
| Native 模块错误 | prebuild 需要更新 | `npx expo prebuild --clean` |
| `toLocaleString` 崩溃 | Hermes 不支持 locale | 使用 `src/utils/formatters.ts` 中的安全函数 |

## 数据库错误 (PostgreSQL)

| 错误 | 原因 | 解决命令 |
|------|------|----------|
| `Connection refused` | PostgreSQL 未运行 | `ssh root@47.100.235.168 "systemctl start postgresql"` |
| `FATAL: password authentication failed` | 密码错误 | 检查 `DB_PASSWORD` / `SMARTBI_DB_PASSWORD` 环境变量 |
| `Too many connections` | 连接数超限 | `SELECT * FROM pg_stat_activity;` 然后 `pg_terminate_backend(pid)` |
| `GROUP BY` 错误 | PG 严格模式 | 必须 GROUP BY 所有非聚合列 |

## 部署错误

| 错误 | 原因 | 解决命令 |
|------|------|----------|
| 端口被占用 | 旧进程未退出 | `ssh root@47.100.235.168 "ss -tlnp \| grep 10010"` |
| 内存不足 | JVM 内存过大 | `java -Xmx512m -jar app.jar` |
| JAR 大小异常 (81M) | .jar.new 覆盖了正常 JAR | `rm -f aims-0.0.1-SNAPSHOT.jar.new` 后重新部署 |
| 健康检查超时 | Spring Boot 冷启动慢 | 等待 60s 后重试 |

## Android 模拟器注意

- 使用 `10.0.2.2` 替代 `localhost` 访问本机 API
- TCP 代理: `adb reverse tcp:10010 tcp:10010`

## 快速诊断

```bash
# 检查后端日志
ssh root@47.100.235.168 "tail -100 /www/wwwroot/cretas/cretas-backend.log"

# 检查 Python 日志
ssh root@47.100.235.168 "tail -100 /www/wwwroot/cretas/code/backend/python/python-services.log"

# 验证 API 健康
curl -s http://47.100.235.168:10010/api/mobile/health
curl -s http://47.100.235.168:8083/health

# 检查数据库
ssh root@47.100.235.168 "sudo -u postgres psql cretas_db -c 'SELECT 1'"

# 前端环境
npx expo doctor
```
