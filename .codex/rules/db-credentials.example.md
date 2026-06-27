# 数据库 / 服务密码 (模板 — 占位)

> 这是 tracked 模板。复制为 `db-credentials.md`(被 `.gitignore` 忽略)并填真值。
> ⛔ 真值绝不写进本 `.example.md`。真值来源: 服务器 `/www/wwwroot/cretas/.env.prod`。

## Java 后端 (生产 — 47 服务器)

| 变量 | 值 | PG 用户 / 库 |
|------|-----|------|
| `DB_PASSWORD` | `<填真值>` | cretas_user → cretas_prod_db |
| `SMARTBI_DB_PASSWORD` | `<填真值>` | smartbi_user → smartbi_prod_db |
| `IFLYTEK_APPID` | `<填真值>` | — |

**Python (cretas-python.service) 内联同步同值** (同一 PG 用户):
- `FOOD_KB_POSTGRES_PASSWORD` = `DB_PASSWORD` (cretas_user)
- `POSTGRES_PASSWORD` = `SMARTBI_DB_PASSWORD` (smartbi_user)

⚠️ 改 prod DB 密码必须**同时**改这 4 处 (.env.prod 2 + systemd unit 2) + `daemon-reload` + 重启 cretas-backend & cretas-python。Java 启动 ~80s, 健康检查轮询 `"status":"UP"` 别用固定 sleep。

## 取线上其他真值

```bash
ssh root@47.100.235.168 "cat /www/wwwroot/cretas/.env.prod"   # JWT_SECRET / DASHSCOPE_API_KEY / OSS key
```
读出的值不要写回仓库任何文件。
