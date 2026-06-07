---
name: aliyun-operations
description: 阿里云服务器运维操作。包括ECS实例管理、安全组配置、服务状态查看、日志排查。适用于服务器管理、端口开放、故障排查等任务。
allowed-tools:
  - Bash
  - Read
  - Write
  - Grep
  - Glob
---

# 阿里云运维操作 Skill

## 服务器架构

| 服务器 | IP | 配置 | 用途 | 状态 |
|--------|-----|------|------|------|
| **新服务器 (主)** | `47.100.235.168` | 8C/16GB/100GB | Java + Python + Web | 运行中 |
| **旧服务器** | `139.196.165.140` | 4C/8GB/40GB | Nginx 反代 + 静态文件 | 仅代理 |

### 新服务器 (47.100.235.168)

| 项目 | 值 |
|------|-----|
| SSH | `ssh root@47.100.235.168` |
| 宝塔面板 | `https://47.100.235.168:8888/658e3b15` |
| 宝塔用户 | `user` / `baotaiWQ3PUc` |
| 系统 | Alibaba Cloud Linux 3 |
| 到期 | 2027-02-10 |

### 旧服务器 (139.196.165.140)

| 项目 | 值 |
|------|-----|
| SSH | `ssh root@139.196.165.140` |
| 宝塔面板 | `https://139.196.165.140:17400` |
| 用途 | Nginx 反代到新服务器，托管 web-admin 静态文件 |

---

## 服务端口 (新服务器)

| 服务 | 端口 | 进程 | 路径 |
|------|------|------|------|
| Java 后端 | 10010 | `aims-0.0.1-SNAPSHOT.jar` | `/www/wwwroot/cretas/` |
| Python 服务 | 8083 | `uvicorn main:app` | `/www/wwwroot/cretas/code/backend/python/` |
| PostgreSQL | 5432 | `postgresql` | 本机 |
| Redis | 6379 | `redis-server` | 本机 |
| Web 前端 | 8088 | Nginx | `/www/wwwroot/web-admin/` |
| 宝塔面板 | 8888 | `bt` | - |

---

## 常用运维命令

### 服务状态

```bash
# 查看所有 Java/Python 进程
ssh root@47.100.235.168 "ps aux | grep -E 'java|uvicorn' | grep -v grep"

# 健康检查
curl -s http://47.100.235.168:10010/api/mobile/health   # Java
curl -s http://47.100.235.168:8083/health                # Python
curl -s -o /dev/null -w "%{http_code}" http://47.100.235.168:8088/  # Web

# 端口监听
ssh root@47.100.235.168 "ss -tlnp | grep -E '10010|8083|5432|6379'"

# 磁盘和内存
ssh root@47.100.235.168 "df -h && echo '---' && free -h"
```

### 日志查看

```bash
# Java 后端
ssh root@47.100.235.168 "tail -100 /www/wwwroot/cretas/cretas-backend.log"

# Python 服务
ssh root@47.100.235.168 "tail -100 /www/wwwroot/cretas/code/backend/python/python-services.log"

# Nginx 访问/错误
ssh root@47.100.235.168 "tail -50 /www/wwwlogs/47.100.235.168.log"
ssh root@47.100.235.168 "tail -50 /www/wwwlogs/47.100.235.168.error.log"
```

### 服务重启

```bash
# 重启全部 (Java + Python)
ssh root@47.100.235.168 "cd /www/wwwroot/cretas && bash restart.sh"

# 仅重启 Nginx
ssh root@47.100.235.168 "systemctl restart nginx"

# 重启 PostgreSQL
ssh root@47.100.235.168 "systemctl restart postgresql"
```

---

## 安全组管理

```bash
# 新服务器 AccessKey
export ALIBABA_CLOUD_ACCESS_KEY_ID=<REDACTED-AK-SEE-LOCAL-CREDENTIAL-FILE>
export ALIBABA_CLOUD_ACCESS_KEY_SECRET=<REDACTED-SECRET-SEE-LOCAL-CREDENTIAL-FILE>

# 查看安全组规则
aliyun ecs DescribeSecurityGroupAttribute --RegionId cn-shanghai --SecurityGroupId <sg-id>

# 开放端口
aliyun ecs AuthorizeSecurityGroup --RegionId cn-shanghai \
  --SecurityGroupId <sg-id> --IpProtocol tcp \
  --PortRange 8083/8083 --SourceCidrIp 0.0.0.0/0
```

已开放端口: `10010`, `8083`, `8088`, `8888`, `22`

---

## 数据库

### PostgreSQL (新服务器)

| 数据库 | 用户 | 用途 |
|--------|------|------|
| cretas_db | cretas_user | 主应用数据 |
| smartbi_db | smartbi_user | SmartBI 数据 |

```bash
# 连接
ssh root@47.100.235.168 "sudo -u postgres psql cretas_db"

# 查看表
ssh root@47.100.235.168 "sudo -u postgres psql cretas_db -c '\dt'"

# 备份
ssh root@47.100.235.168 "sudo -u postgres pg_dump cretas_db > /tmp/cretas_backup_$(date +%Y%m%d).sql"
```

---

## 故障排查

| 症状 | 排查步骤 |
|------|----------|
| Java 无法启动 | 1. 检查端口占用 `ss -tlnp \| grep 10010` → 2. 检查日志 `tail -50 cretas-backend.log` → 3. 检查环境变量 `env \| grep DB_` |
| Python 502 | 1. 检查进程 `ps aux \| grep uvicorn` → 2. 检查日志 `tail -50 python-services.log` → 3. 检查端口 `ss -tlnp \| grep 8083` |
| 前端白屏 | 1. 检查 Nginx `nginx -t` → 2. 检查 dist 文件 `ls /www/wwwroot/web-admin/index.html` → 3. 检查 Nginx 日志 |
| DB 连接失败 | 1. 检查 PG 状态 `systemctl status postgresql` → 2. 检查 pg_hba.conf → 3. 检查密码环境变量 |
| 磁盘满 | `du -sh /www/wwwroot/cretas/logs/* \| sort -rh \| head` → 清理旧日志和备份 |
