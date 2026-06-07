---
name: debug-assistant
description: 调试辅助工具。快速诊断后端、前端和数据库问题。适用于 API 失败、日志分析、服务健康检查等场景。
allowed-tools:
  - Bash
  - Read
  - Grep
  - Glob
---

# 调试辅助 Skill

## 服务架构

| 服务 | 端口 | 服务器 | 日志位置 | 健康检查 |
|------|------|--------|----------|----------|
| Java 后端 | 10010 | 47.100.235.168 | `/www/wwwroot/cretas/cretas-backend.log` | `/api/mobile/health` |
| Python 服务 | 8083 | 47.100.235.168 | `/www/wwwroot/cretas/code/backend/python/python-services.log` | `/health` |
| Web 前端 | 8088 | 47.100.235.168 (Nginx) | `/www/wwwlogs/47.100.235.168.log` | HTTP 200 |
| PostgreSQL | 5432 | 47.100.235.168 (本机) | `journalctl -u postgresql` | `SELECT 1` |
| React Native | 3010 | 本地开发 | Metro bundler 控制台 | N/A |

> **注意**: 旧服务器 `139.196.165.140` 仅运行 Nginx 反代 + web-admin 静态文件，后端已迁移到 `47.100.235.168`。

---

## 快速诊断命令

### 服务状态检查

```bash
# 一键健康检查 (全部服务)
echo "=== Health Check ===" && \
echo -n "Java:   " && curl -s -o /dev/null -w "%{http_code}" http://47.100.235.168:10010/api/mobile/health && echo "" && \
echo -n "Python: " && curl -s -o /dev/null -w "%{http_code}" http://47.100.235.168:8083/health && echo "" && \
echo -n "Web:    " && curl -s -o /dev/null -w "%{http_code}" http://47.100.235.168:8088/ && echo ""

# 检查进程
ssh root@47.100.235.168 "ps aux | grep -E 'java|uvicorn' | grep -v grep"

# 检查端口监听
ssh root@47.100.235.168 "ss -tlnp | grep -E '10010|8083|5432|6379'"

# 磁盘和内存
ssh root@47.100.235.168 "df -h && echo '---' && free -h"
```

### 查看日志

```bash
# Java 后端日志
ssh root@47.100.235.168 "tail -100 /www/wwwroot/cretas/cretas-backend.log"

# 过滤 ERROR
ssh root@47.100.235.168 "tail -500 /www/wwwroot/cretas/cretas-backend.log | grep -A5 'ERROR'"

# Python 服务日志
ssh root@47.100.235.168 "tail -100 /www/wwwroot/cretas/code/backend/python/python-services.log"

# Nginx 访问/错误日志
ssh root@47.100.235.168 "tail -50 /www/wwwlogs/47.100.235.168.log"
ssh root@47.100.235.168 "tail -50 /www/wwwlogs/47.100.235.168.error.log"
```

### 数据库检查

```bash
# PostgreSQL 连接测试
ssh root@47.100.235.168 "sudo -u postgres psql cretas_db -c 'SELECT 1'"

# 查看表列表
ssh root@47.100.235.168 "sudo -u postgres psql cretas_db -c '\dt'"

# SmartBI 数据库
ssh root@47.100.235.168 "sudo -u postgres psql smartbi_db -c '\dt'"
```

---

## 常见错误速查

| 错误 | 检查命令 | 常见原因 |
|------|----------|----------|
| 500 Error | `grep ERROR cretas-backend.log` | Entity字段缺失、NPE、JSON错误 |
| 401 Unauthorized | 解码 JWT payload | Token过期/格式错误/权限不足 |
| 网络失败 | `curl -s http://47.100.235.168:10010/api/mobile/health` | 端口未开放/服务未启动 |
| Python 502 | `ssh ... "ps aux \| grep uvicorn"` | Python 进程挂了/依赖缺失 |
| 前端白屏 | 浏览器 F12 Console | API 地址错误/Nginx 配置 |
| RN 白屏 | `npx expo start --clear` | Metro 编译错误 |

---

## JWT Token 调试

```bash
# 解码 JWT payload
echo "YOUR_TOKEN" | cut -d'.' -f2 | base64 -d 2>/dev/null | jq

# Payload 结构: { role, factoryId, userId, username }
```

## React Native 调试

```bash
cd frontend/CretasFoodTrace
npx expo start --clear          # 清除缓存启动
npx expo doctor                 # 检查环境
```

## 故障排查流程

| 症状 | 排查步骤 |
|------|----------|
| Java 无法启动 | 1. `ss -tlnp \| grep 10010` → 2. `tail -50 cretas-backend.log` → 3. `env \| grep DB_` |
| Python 502 | 1. `ps aux \| grep uvicorn` → 2. `tail -50 python-services.log` → 3. `ss -tlnp \| grep 8083` |
| 前端白屏 | 1. `nginx -t` → 2. `ls /www/wwwroot/web-admin/index.html` → 3. Nginx 日志 |
| DB 连接失败 | 1. `systemctl status postgresql` → 2. 检查 pg_hba.conf → 3. 检查密码环境变量 |
| 磁盘满 | `du -sh /www/wwwroot/cretas/logs/* \| sort -rh \| head` → 清理旧日志 |

## 参考文档

- `references/common-errors.md` - 完整错误速查表
