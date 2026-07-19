# 凭证配置

**最后更新**: 2026-04-25

---

## 必需环境变量

### Java 后端 (生产环境 pg-prod)

| 变量 | 用途 | 示例 |
|------|------|------|
| `DB_PASSWORD` | 主数据库 (cretas_db) 密码 | 见 `.claude/skills/server-operations/db-credentials.md` (gitignored) |
| `SMARTBI_DB_PASSWORD` | SmartBI 数据库 (smartbi_db) 密码 | 见 `.claude/skills/server-operations/db-credentials.md` (gitignored) |
| `JWT_SECRET` | JWT 签名密钥 | 随机长字符串 |
| `IFLYTEK_APPID` | 讯飞语音 AppID | 见 `.claude/skills/server-operations/db-credentials.md` (gitignored) |
| `IFLYTEK_API_KEY` | 讯飞语音 API Key | - |
| `IFLYTEK_API_SECRET` | 讯飞语音 API Secret | - |
| `ALIBABA_ACCESSKEY_ID` | 阿里云 OSS AccessKey | - |
| `ALIBABA_SECRET_KEY` | 阿里云 OSS Secret | - |

### Java 后端 (本地开发 pg)

| 变量 | 值 |
|------|-----|
| `DB_PASSWORD` | 见 `.claude/skills/server-operations/db-credentials.md` (gitignored) |
| `POSTGRES_SMARTBI_PASSWORD` | 见 `.claude/skills/server-operations/db-credentials.md` (gitignored) |

**注意**: SmartBI 统一密码同时用于 smartbi_db (test) 和 smartbi_prod_db (prod)。真值见 `.claude/skills/server-operations/db-credentials.md` (gitignored, 本地+服务器可见)。
来源: 服务器 `/www/wwwroot/cretas/.env.prod`。旧值 `smartbi_pass` 已废弃 (2026-04-25 修正)。

### Python 服务

| 变量 | 用途 | 位置 |
|------|------|------|
| `DASHSCOPE_API_KEY` | 通义千问 LLM API Key | `backend/python/.env` |
| PostgreSQL 连接 | 在 `.env` 中配置 | `backend/python/.env` |

---

## Spring Boot 配置

```properties
# 通过环境变量注入，禁止硬编码
spring.datasource.password=${DB_PASSWORD}
smartbi.postgres.password=${SMARTBI_DB_PASSWORD}
cretas.jwt.secret=${JWT_SECRET}
```

---

## 安全规范

- **禁止** 在代码/配置文件中硬编码密码
- **禁止** 将 `.env` 文件提交到 Git
- 服务器环境变量通过 `restart.sh` 或 `/etc/environment` 设置
- 本地开发通过命令行参数或 shell export 传入
