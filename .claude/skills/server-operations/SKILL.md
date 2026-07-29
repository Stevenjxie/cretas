---
name: server-operations
description: cretas 服务器运维规范(47.100.235.168 主服务器 + 139.196.165.140 网关)。触发场景:SSH 上服务器操作;部署 Java 后端/Python 服务/Web-Admin/Showcase(内容分布禁止搞混,Showcase 只上 139);systemd 服务管理(cretas-backend/cretas-python/cretas-embedding)与启动依赖链;健康检查/看日志/查端口/磁盘内存;双环境(prod 10010/8083,test 已于 2026-07-13 下线);smartbi 数据库 schema 变更硬规则(migration runner,禁手动 psql DDL);环境变量 .env.prod;rsync/scp 传输规范;查服务器/数据库/阿里云凭证。凡涉及"服务器/部署/运维/systemd/日志/migration/凭证"必读本 skill。
---

# 服务器运维规范

**最后更新**: 2026-06-07

## 服务器架构

| 服务器 | IP | 配置 | 用途 | 状态 |
|--------|-----|------|------|------|
| **新服务器 (主)** | `47.100.235.168` | 8C/16GB/100GB | Java + Python + DB | 运行中 |
| **旧服务器 (网关)** | `139.196.165.140` | 4C/8GB/40GB | Nginx 网关 + Web-Admin + Showcase 静态站 | 运行中 |

### 内容分布 — 禁止搞混

| 内容 | 所在服务器 | 路径 | 域名 |
|------|-----------|------|------|
| Java 后端 | **47** (新) | `/www/wwwroot/cretas/` | `47.100.235.168:10010` |
| Python 服务 | **47** (新) | `/www/wwwroot/cretas/code/backend/python/` | `47.100.235.168:8083` |
| PostgreSQL / Redis | **47** (新) | systemd 管理 | localhost only |
| **Web-Admin 前端** | **139** (网关) | `/www/wwwroot/web-admin/` | `139.196.165.140:8086` |
| **Showcase 展示站** | **139** (网关) | `/www/wwwroot/showcase/cretaceousfuture/` | `www.cretaceousfuture.com` |
| Nginx 网关 | **139** (网关) | 宝塔 Nginx | API→47, Python→47 |
| **餐饮平台模拟器** | **139** (网关) | `/www/wwwroot/mock-platform/` | `139.196.165.140/mock/` |

**关键规则**: Showcase 相关文件（HTML、截图、CSS）只部署到 **139**，不要传到 47。

### 本地目录 → 服务器路径映射

| 本地目录 | 部署目标 | 服务器路径 |
|---------|---------|-----------|
| `platform/` | **139** (旧) | `/www/wwwroot/showcase/cretaceousfuture/` |
| `backend/java/cretas-api/` | **47** (新) | `/www/wwwroot/cretas/` |
| `backend/python/` | **47** (新) | `/www/wwwroot/cretas/code/backend/python/` |
| `web-admin/` | **139** (网关) | `/www/wwwroot/web-admin/` |
| `mock-platform/` | **139** (网关) | `/www/wwwroot/mock-platform/code/` |

**餐饮平台模拟器 (2026-07-29)**: 假 POS 开放平台，**只上 139，绝不上 47** —— 它扮演「外部世界」，
放到 47 就破坏了隔离前提。绑 `127.0.0.1:9200` 不对外，由 139 已有 nginx 从 80 反代 `/mock/`
（139 的阿里云安全组只放行 80/443/8086，自开端口从 47 打过去一律 TIMEOUT）。
部署：`./scripts/deploy/deploy-mock-platform.sh`；服务名 `cretas-mock-platform`。
健康判据用 `generator: running`，**不能只看 `status: ok`** —— 生成器没挂时那一档也是 ok。

**`platform/` 目录说明**: 包含 www.cretaceousfuture.com 网站的全部内容 — 主站页面 + showcase 演示子页 (factorybi-example, client-request-example, restaurantbi-example)。

---

## 双环境 (生产 + 测试)

> ⚠️ **2026-07-13 更新: 47 上的 test 环境已下线 (Steve 决定)。** `cretas-backend-test` (10011) 已 `systemctl stop + disable` (不再自启), test Python (8084, 本就 nohup 非 systemd) 也已停。**当前 47 只跑 prod (10010 Java / 8083 Python)**。Steve 后期会用**单独一台服务器**做 test 环境。
> - 下线原因: 47 内存紧张, prod Java 在 2.56G 堆下被并发压力 OOM 崩过 (2026-07-13); 停 test 腾出 ~1.5G 给 prod。
> - **Prod JVM 堆已 2.56G→3G** (`cretas-backend.service` ExecStart `-Xmx3g`, 备份 `.bak.20260713_*`)。`-XX:+HeapDumpOnOutOfMemoryError` 已开, 下次 OOM 自动 dump 到 `/www/wwwroot/cretas/logs/`。
> - **下方"双环境"「test」相关内容暂留作历史参考**, 但 47 上已不适用: `deploy-backend.sh --env test` / 部 prod 后的"防御性 ping test 10011" 会警告 test 不通 (**不阻塞部署**, 忽略即可)。schema drift 对比等 test 操作在新 test 服务器就位前不适用。

同一台服务器运行两套独立环境，共享 JAR 和 Python 代码，通过环境变量区分数据库。

| 服务 | 生产端口 | 测试端口 |
|------|----------|----------|
| Java 后端 | **10010** | **10011** |
| Python 服务 | **8083** | **8084** |
| PostgreSQL | 5432 (共享) | 5432 (共享) |

| 环境 | 主库 | SmartBI 库 | 启动脚本 |
|------|------|-----------|----------|
| 生产 | `cretas_prod_db` | `smartbi_prod_db` | `restart-prod.sh` |
| 测试 | `cretas_db` | `smartbi_db` | `restart-test.sh` |

### 启动命令

```bash
bash restart.sh           # 启动两套 (默认)
bash restart.sh prod      # 仅生产
bash restart.sh test      # 仅测试
```

### 日志文件

| 环境 | Java 日志 | Python 日志 |
|------|-----------|-------------|
| 生产 | `cretas-prod.log` | `python-prod.log` |
| 测试 | `cretas-test.log` | `python-test.log` |

---

## 新服务器目录结构 (47.100.235.168)

```
/www/wwwroot/
├── cretas/                          # Cretas 食品溯源 (主项目)
│   ├── aims-0.0.1-SNAPSHOT.jar      # Java 后端 JAR (两套共享)
│   ├── .env.prod                    # 生产环境变量 (DB密码/JWT/LLM Key)
│   ├── restart.sh                   # 入口: 调用 prod + test
│   ├── restart-prod.sh              # 生产环境启动 (systemd 版)
│   ├── restart-test.sh              # 测试环境启动 (10011+8084)
│   ├── cretas-prod.log              # 生产 Java 日志
│   ├── cretas-test.log              # 测试 Java 日志
│   ├── embedding-service/           # gRPC 向量嵌入服务
│   │   ├── embedding-service-1.0.0.jar
│   │   └── embedding-service.log
│   ├── models/                      # ONNX 模型文件
│   │   └── gte-base-zh-finetuned-onnx-fixed/
│   └── code/backend/python/         # Python 服务代码 (两套共享)
├── web-admin/                       # Web 前端 (Vue dist)
└── python-services/                 # Python 独立服务 (food_kb)
```

---

## 服务管理 (systemd)

**生产环境所有服务均由 systemd 管理，开机自启 + 崩溃自动重启。**
**测试环境 Java 后端 (10011) 自 2026-04-29 起也由 systemd 管理 (cretas-backend-test). Python (8084) 仍由 restart-test.sh nohup 管理 (Phase B-N: 加 cretas-python-test).**

| 服务 | 端口 | systemd 服务名 | 状态 |
|------|------|---------------|------|
| gRPC Embedding | 9090 | `cretas-embedding` | enabled |
| Java 后端 (prod) | 10010 | `cretas-backend` | enabled |
| Python 服务 (prod) | 8083 | `cretas-python` | enabled |
| **Java 后端 (test)** | **10011** | **`cretas-backend-test`** | **enabled (自 2026-04-29)** |
| Python 服务 (test) | 8084 | (nohup, 待 Phase B-N 加 systemd) | — |
| Redis | 6379 | `redis` | enabled |
| PostgreSQL | 5432 | `postgresql` | enabled |

### 启动依赖链

```
redis(6379) ─┐
postgresql ──┤
              ├─ cretas-embedding(9090) ─→ cretas-backend(10010)
              └─ cretas-python(8083)
```

Java 后端依赖 Embedding 服务 (`After=cretas-embedding.service`)。

### systemd 配置文件

| 文件 | 位置 |
|------|------|
| `cretas-embedding.service` | `/etc/systemd/system/` |
| `cretas-backend.service` | `/etc/systemd/system/` — 使用 `EnvironmentFile=/www/wwwroot/cretas/.env.prod` |
| `cretas-python.service` | `/etc/systemd/system/` — 环境变量内联 |
| **`cretas-backend-test.service`** | `/etc/systemd/system/` — 使用 `EnvironmentFile=/www/wwwroot/cretas/.env.test` (chmod 600). 源文件 in repo: `scripts/systemd/cretas-backend-test.service` |
| **`.env.test`** | `/www/wwwroot/cretas/` — chmod 600. Template: `scripts/systemd/.env.test.template` |

### 常用管理命令

```bash
# 查看状态 (prod + test Java)
systemctl status cretas-backend cretas-backend-test cretas-python cretas-embedding

# 重启单个服务
systemctl restart cretas-backend            # prod Java
systemctl restart cretas-backend-test       # test Java (auto-restart on crash, RestartSec=15)

# 重启全部生产服务 (按依赖顺序)
bash /www/wwwroot/cretas/restart.sh prod

# 实时日志
journalctl -u cretas-backend -f             # prod
journalctl -u cretas-backend-test -f        # test

# 测试环境 (Java systemctl-managed, Python 仍 nohup)
bash /www/wwwroot/cretas/restart.sh test    # 调用 restart-test.sh
# 或仅重启 Java:
systemctl restart cretas-backend-test
```

### 测试环境 Java 自动重启验证 (2026-04-29)

`cretas-backend-test.service` 配置 `Restart=on-failure RestartSec=15 StartLimitBurst=3 / 120s`:
- Kill PID → systemd 15s 后 respawn → Spring Boot 启动 ~80s → 健康
- Total recovery: ~95s from crash to fully healthy
- 防御 deep-test 时 Java 静默挂掉阻塞测试 (Apr 28 真踩过)

### 环境变量管理

- 生产环境变量集中在 `/www/wwwroot/cretas/.env.prod` (权限 600)
- Java 服务通过 `EnvironmentFile` 引用
- Python 服务通过 `Environment=` 内联（含 LLM 模型分配）
- **修改模型配置后**需同时更新 systemd service 文件和 `.env.prod`，然后 `systemctl daemon-reload`

---

## 常用运维命令

```bash
# 健康检查
curl -s http://47.100.235.168:10010/api/mobile/health   # 生产 Java
curl -s http://47.100.235.168:10011/api/mobile/health   # 测试 Java
curl -s http://47.100.235.168:8083/health                # 生产 Python
curl -s http://47.100.235.168:8084/health                # 测试 Python

# systemd 状态
ssh root@47.100.235.168 "systemctl status cretas-backend cretas-python cretas-embedding --no-pager"

# 端口监听 (含 9090 Embedding)
ssh root@47.100.235.168 "ss -tlnp | grep -E '10010|10011|8083|8084|9090|6379'"

# 磁盘和内存
ssh root@47.100.235.168 "df -h && echo '---' && free -h"

# 查看日志
ssh root@47.100.235.168 "tail -100 /www/wwwroot/cretas/cretas-prod.log"
ssh root@47.100.235.168 "journalctl -u cretas-backend --since '5 min ago' --no-pager"
```

---

## 部署规范

**部署必须使用项目内的部署脚本，禁止手动 rsync/ssh 拼命令：**

| 部署目标 | 脚本 | 说明 |
|----------|------|------|
| **正常 Java/Web 发布（首选）** | `./scripts/deploy/release-cretas.sh --phase build\|deploy --base-sha <SHA> --tests '<tests>' --confirm-prod YES-PROD` | 统一入口：自动检测变更范围、构建一次 + manifest/tree-cache 复用、默认安全串行（并行需 `YES-INDEPENDENT-SERVICES` 口令）、JSON 回执；deploy 阶段强制 clean HEAD == origin/main |
| 发布证据（只读） | `./scripts/deploy/verify-release.sh --target backend\|web-admin\|all --env prod` | 汇总真实 upstream / systemd / 直连健康 / Web 四方哈希；回执成功不重复手工检查 |
| Java 单组件/排查 | `./scripts/deploy/deploy-backend.sh [--env prod\|test\|all]` | Maven 打包 → rsync 主 (scp 兜底) → 备份 → Blue-Green 部署 → 健康检查 + 防御 ping 另一环境 |
| Python 服务 | `./scripts/deploy/deploy-smartbi-python.sh [--env prod\|test\|all]` | rsync 增量同步 → 安装依赖 → 重启 → 健康检查 |
| Web 单组件/排查 | `./scripts/deploy/deploy-web-admin.sh --env prod` | npm build → tar+scp → 139 网关原子切换 |
| 全栈部署 | 使用 `/deploy-backend` skill | 根据指令自动选择部署范围（其 Phase -1 即统一入口） |

`--env` 默认 `prod`，只更新生产环境。**蓝绿槽位**：prod Java `10010`/`10020` 交替 active，部署/核对前先读 `139:/www/server/panel/vhost/nginx/_upstream_cretas.conf`，禁止假设某槽永久停用。

### 双环境部署最佳实践 (Apr 7 2026)

两套环境**共享同一份 jar 但进程独立**, 默认 `--env prod` 不重启 test → **test 环境长期不被部署 → 容易宕机不被察觉** (今晚发现 test 已挂掉一段时间无人知).

**推荐工作流**:
```bash
./scripts/deploy/deploy-backend.sh --env test       # 先部 test
# smoke test 验证业务
./scripts/deploy/deploy-backend.sh --env prod       # 满意后部 prod (防御检查会顺手 ping test)
```

紧急 hotfix: `./scripts/deploy/deploy-backend.sh --env all` 一次部两套.

deploy-backend.sh v4.2 已加**防御性 health check**: 部 prod 完顺便 ping test 10011 (反之亦然), 挂了警告并提示恢复命令, 不阻塞 deploy. 所以即使忘记 `--env all`, 下次 deploy 会立即提醒.

详见 `.claude/skills/deploy-backend/SKILL.md` 和 memory 里的 `feedback_deploy_pipeline.md`.

---

## ⛔ Smartbi 数据库 schema 变更 (HARD RULE)

**触发**: task #30 (2026-05-06) — 8 个 data fabric C 系列 migrations 当初部署漏跑 prod, T6.2 canary 4h 才发现 9 errors。
**Spec**: `docs/superpowers/specs/2026-05-07-smartbi-migration-runner-spec.md`

**所有** smartbi 数据库 (smartbi_db / smartbi_prod_db) schema 变更**必须**:

1. 写 `backend/python/smartbi/database/migrations/V<YYYYMMDD>_<NN>__<description>.sql`
2. 部署通过 `./scripts/deploy/deploy-smartbi-python.sh --env <env>` 自动 apply
   - Step 3.5 调用 `apply-smartbi-migrations.sh --env $env` runner
   - Runner 失败 → deploy ABORT,Python NOT restarted (旧 schema + 旧 code 继续跑)

**禁止** 手动 `ssh + psql -f` 直接跑 schema DDL,**除非**紧急 hotfix。完后**必须立即**:
1. 把 SQL 落 V*.sql 文件 commit 进 repo
2. 手动 INSERT 进 `smartbi_migrations` tracker (per spec §3.8 escape hatch):
   ```sql
   INSERT INTO smartbi_migrations (filename, version, checksum, applied_by)
   VALUES ('V20260507_99__hotfix.sql', 'V20260507_99',
           '<sha256 of file>', 'manual-emergency')
   ON CONFLICT (filename) DO NOTHING;
   ```
3. 否则下次 deploy 跑 runner 会 try re-apply → DDL 已存在报错。

**escape hatch**: 紧急绕过 runner (e.g. runner 自身有 bug):
```bash
SKIP_MIGRATIONS=1 ./scripts/deploy/deploy-smartbi-python.sh --env prod
```
WARN level 日志 + 跳过 Step 3.5。完后立即修 runner 重新部署。

如果 schema 在 prod / test 之间出现 drift:
1. `comm -23 <(test schema dump) <(prod schema dump)` 找差异
2. 单一 transaction (`BEGIN; ALL; COMMIT;`) apply 缺失 migrations 到落后的 env
3. Backfill tracker 表 (per `scripts/migrations/backfill-applied.sh`)

**Tracker schema 关键点** (per spec §3.2): PRIMARY KEY = `filename` (not version),因为 历史上 `V20260427_01` 有两个不同文件。filename PK 防 future dupe 静默跳过。

---

## 注意事项

1. **不要直接删除** `/www/wwwroot` 下的目录，先确认内容
2. **备份 jar 包**会自动生成 `.bak.*` 文件，保留最近 3 份，定期清理旧的
3. **日志文件**在 `logs/` 目录，会持续增长，需定期清理
4. **数据库**: 已迁移到 PostgreSQL，不再使用 MySQL
5. **旧服务器 (139)**: 后端已停用，仅保留 Nginx 反代 + **Showcase 静态站** (www.cretaceousfuture.com)
6. **Showcase 只部署到 139**: 不要向 47 传 showcase 文件，47 是纯后端服务器
7. **文件传输: rsync 为主, scp 兜底** (deploy script v5.0, Steve 2026-05-28; 全 SSH-based 谁快谁赢). `rsync` (主, 更长久更快) + `rsync+compress` + `scp` (兜底, 任何环境都能跑, 实测 10.85 MB/s). **R2/OSS/GitHub 默认禁用** (代码保留, `ENABLE_R2=1` 紧急 opt-in). ⚠️ **`SKIP_RSYNC=1` 已于 2026-06-07 从 `~/.bashrc` 移除** —— 旧"rsync 被 RST 永久禁用"结论已过期, 残留的 flag 一直在 forcing scp 兜底; 现 deploy 默认走 rsync 主通道.
8. **两套环境共享 JAR + Python 代码**: 部署一次代码后按需重启对应环境. **进程独立**, 默认部 prod 不动 test, 见上方"双环境部署最佳实践".
9. **修改 systemd 服务文件后**: 必须 `systemctl daemon-reload` 再 `systemctl restart <service>`
10. **生产环境变量**: 集中在 `.env.prod`，修改后需重启对应服务才生效
11. **本地启动 Java 后端**: 用 `mvn spring-boot:run` 不要用 `java -jar` (后者 mmap 锁 fat jar 会阻断 deploy 的 mvn package). 见 `feedback_deploy_pipeline.md`.
12. **R2/OSS 凭证位置 (legacy, 默认禁用)**: R2 在 `~/.r2-env` (NTFS ACL 仅 Steve+SYSTEM); OSS 在 `~/.ossutilconfig` (账号 B, **`cretas-media` bucket 属账号 B 不是 A**). 这些是 Steve 在国外期间为绕过跨境 RST 用的中转通道; 现已回国, rsync 主 + scp 兜底, R2/OSS 默认禁用 (`ENABLE_R2=1` 紧急 opt-in). `SKIP_RSYNC=1` 已移除 (见注意事项 7). deploy script 启动时仍自动 source ~/.bashrc (取 R2 凭证供紧急 opt-in).
13. **Backup 文件清理**: deploy script 自动保留最近 3 份 `*.bak.YYYYMMDD_HHMMSS`. 历史命名 (`.bak4/5/6/.broken/.bak.pre_fix`) 不会被自动清理, 需手动 rm.

---

## 凭证文件位置

凭证见本目录 `db-credentials.md` / `aliyun-credentials.md`(本地文件, gitignored, 不入库)。含数据库密码、服务器登录、阿里云 AccessKey 等真值; 需要凭证时直接读这两个文件, 不要把真值写进任何会被 commit 的文件。
