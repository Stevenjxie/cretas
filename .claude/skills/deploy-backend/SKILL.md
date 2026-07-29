---
name: deploy-backend
description: 全栈部署工作流。当用户说"部署"、"deploy"、"发布"、"上线"时触发。支持 Java 后端、Python 服务、Web 前端的独立或组合部署。
allowed-tools:
  - Bash
  - Read
  - Glob
  - Grep
---

# 全栈部署 Skill

## Trigger

用户说 `/deploy-backend` 或 "部署后端"、"deploy"、"发布"、"上线"。

---

## ⭐ Phase -1: 统一发布入口优先（2026-07 吸收自 Codex 发布优化）

**正常 Java/Web 发布优先用 `release-cretas.sh` 统一入口，不要自行把单组件脚本拼接成全栈发布流程**：

```bash
# ① 合并前（在干净的已审查候选 worktree）：唯一一次构建 + Java 制品预上传（不安装/不重启/不切流）
./scripts/deploy/release-cretas.sh --phase build \
  --base-sha '<dispatch 登记的 Base SHA>' --tests '<Maven 测试选择器>' \
  --stage-backend YES-STAGE

# ② 合并后（强制 clean HEAD == origin/main）：自动检测 Java/Web 变更范围，
#    复用 manifest / tree cache，部署或安全 no-op，输出统一 JSON 回执
./scripts/deploy/release-cretas.sh --phase deploy \
  --base-sha '<Base SHA>' --tests '<tests>' --confirm-prod YES-PROD
```

要点：
- **最多构建一次**：Java 目标测试 + 最终 JAR = 同一条 `mvn clean package -Dtest=<tests>` 生命周期（`release-jar-manifest.sh build`）；Web = `release-web-manifest.sh build` 产不可变 `dist.tar.gz` + 可信 manifest。squash 后 commit 不同但 Git tree 相同**可复用制品**，禁止重复构建；任一校验失败只回退一次本地构建。
- ⚠️ **`build` 子命令会跳过 Maven 而复用缓存 JAR**，当且仅当三者全部成立：backend tree == `HEAD:backend/java/cretas-api`、目标测试选择器与本次请求完全一致、缓存 JAR 的 SHA256 与 manifest 记录相符。任一不符即回落真实编译。因此**回执里 `"build": "reused"` + `java_build=0` 是正常成功，不是构建未执行**，也不代表跳过了测试 —— 选择器一变必重编。需要无条件重编时用 `CRETAS_RELEASE_FORCE_JAVA_BUILD=1`。
- ⚠️ **但 deploy 阶段的 `validate` 不比对测试选择器**：它只断言 JAR 匹配当前 origin/main 的 backend tree。选择器由 build 阶段把关，事后审计看 manifest / 回执里的 `target_tests` 字段。单凭一个 `reused` 回执**不能**证明本次传入的选择器与制品一致。
- **并发互斥**：`release-cretas.sh` 持 `cretas-release`、`deploy-backend.sh` 持 `cretas-backend-deploy`、`deploy-web-admin.sh` 持 `cretas-web-admin-deploy`。遇到「另一个 deploy 进程持有锁」**不要反射性 `rm` 锁文件** —— `acquire_deploy_lock` 已内建 stale-PID 自动清理，还报错说明大概率真有并发进程在跑；先确认 PID 不存活再清理。
- **默认安全串行**；仅显式传 `--parallel-if-independent YES-INDEPENDENT-SERVICES` 且迁移/Entity/Security/跨端契约等风险检测全部未命中时才并行。`--order backend-first|web-first` 控制串行顺序。
- **发布证据复用**：入口自动调 `verify-release.sh`（真实 upstream / systemd / 直连健康 / Web 四方哈希）写入结构化回执；回执完整且成功时**不要手工重复同类检查**，只补任务特有断言。单独验证：`./scripts/deploy/verify-release.sh --target backend|web-admin|all --env prod`。
- **蓝绿槽位**：prod Java 的 `10010` 与 `10020` **交替成为 active**。部署/核对前必须读 `139.196.165.140:/www/server/panel/vhost/nginx/_upstream_cretas.conf`，禁止假设某槽永久停用。
- `deploy-backend.sh` / `deploy-web-admin.sh` / `release-jar-manifest.sh` 保留为**单组件发布与故障排查入口**（下方 Phase 1-3），适用：只动 Python、紧急单点修复、排查部署链路本身。

> ⚠️ test 环境已于 2026-07-13 下线（见 server-operations skill）。下文 `--env test` / 10011 相关内容暂留作历史参考；部 prod 后"防御 ping test"的警告可忽略。

**合入通道双轨**（部署前置，详见 `worktree-and-main-only-deploy.md` §2b）：docs/`.claude/`/配置类可走 `publish-main-fastlane.sh` 直推 main（免 PR 往返；分支需 `codex/*` 前缀；非 docs 批次须带 `--task-id <本批任务ID>`，否则 ACTIVE 门禁按「全局零未完成任务」判定而恒拒；ID 必须在 `docs/dispatch/` 里真实出现，拼错会被拒而非静默放行）；碰 backend/web-admin 代码走 PR（CI JPA 门禁）；AGENTS.md/迁移/Entity/Security/`scripts/deploy/*` 强制 PR。任何通道都必须推上 origin/main 才可部署。

---

## Phase 0: 解析部署目标

根据用户输入判断部署范围：

| 用户说的 | 部署目标 |
|----------|---------|
| "部署后端"、"deploy backend"、"上传JAR" | Java 后端 |
| "部署Python"、"deploy python"、"更新Python服务" | Python 服务 |
| "部署前端"、"deploy frontend"、"发布前端" | Web 前端 |
| "全部部署"、"deploy all"、"全量发布" | Java + Python + 前端 |
| "重启服务"、"restart" | 仅重启 (不构建) |
| 仅说 "部署" | 默认 Java 后端 |

如果判断不了，用 AskUserQuestion 确认：
- Java 后端 (构建+部署 JAR)
- Python 服务 (同步代码+重启)
- Web 前端 (构建+上传 dist)
- 全部

---

## 服务器信息

| 项目 | 值 |
|------|-----|
| **生产服务器** | `47.100.235.168` (8C/16GB) |
| **SSH** | `root@47.100.235.168` |
| **旧服务器** | `139.196.165.140` — 仅 Nginx 反代 + 静态文件 |

### 双环境 (生产 + 测试)

| 服务 | 生产端口 | 测试端口 | 路径 |
|------|----------|----------|------|
| Java 后端 | 10010 | 10011 | `/www/wwwroot/cretas/` |
| Python 服务 | 8083 | 8084 | `/www/wwwroot/cretas/code/backend/python/` |
| Web 前端 | 8088 | - | `/www/wwwroot/web-admin/` |

| 环境 | 主库 | SmartBI 库 |
|------|------|-----------|
| 生产 | `cretas_prod_db` | `smartbi_prod_db` |
| 测试 | `cretas_db` | `smartbi_db` |

两套环境**共享同一份 JAR**, 通过环境变量区分数据库. 但**进程独立** — 默认 `--env prod` 不会重启 test, **test 容易长期落后甚至宕机不被察觉** (Apr 7 教训).

**推荐工作流**:
```bash
./scripts/deploy/deploy-backend.sh --env test       # 先部 test
# smoke test 验证
./scripts/deploy/deploy-backend.sh --env prod       # 满意后部 prod
```

紧急 hotfix: `--env all` 一次部两套. v4.2 已加防御 ping (部 prod 后顺便 ping test, 反之亦然).

---

## Phase 1: Java 后端部署

### 方式 A: 一键脚本 (推荐, ~2 分钟)

```bash
./scripts/deploy/deploy-backend.sh                  # 部署到生产 (默认)
./scripts/deploy/deploy-backend.sh --env test       # 部署到测试
./scripts/deploy/deploy-backend.sh --env all        # 部署后重启两套
```

脚本 v5.0 自动完成: Maven 打包 → rsync 主 (scp 兜底) 上传 → 服务器备份 → Blue-Green 部署 → 重启 → 健康检查 (30次重试) + **防御 ping 另一环境**.

可选参数:
```bash
./scripts/deploy/deploy-backend.sh --jar v1.2             # 指定版本号
./scripts/deploy/deploy-backend.sh --git                  # Git 部署 (服务器端编译, 用于本地无 mvn 时)
./scripts/deploy/deploy-backend.sh --rollback             # 回滚到上一个备份
```

### 环境变量 (deploy script 启动时自动 source ~/.bashrc)

| 变量 | 作用 |
|---|---|
| `SKIP_RSYNC=1` | escape hatch: 跳过 rsync 走 scp 兜底 (默认不设; 仅 SSH 链路不稳时临时用) |
| `SKIP_BUILD=1` | 跳过 Maven 打包 (用 target/ 已有 jar) |
| `R2_ACCOUNT_ID/ACCESS_KEY_ID/SECRET_ACCESS_KEY/PUBLIC_URL` | 启用 R2 备份通道 |

R2 凭证存在 `~/.r2-env` (NTFS ACL chmod 600), `~/.bashrc` source 它.

### 方式 B: 服务器自编译 (本地 mvn 不可用 / target 锁定时)

```bash
# 1. 把 patch 推到服务器
git diff backend/java/cretas-api/ | ssh root@47.100.235.168 \
  "cat > /tmp/p.patch && cd /www/wwwroot/cretas/code && git apply /tmp/p.patch"

# 2. 服务器编译 (注意 JAVA_HOME 必须是 21, 默认 javac 是 17!)
ssh root@47.100.235.168 "
  export JAVA_HOME=/usr/lib/jvm/java-21-alibaba-dragonwell-21.0.5.0.5-1.1.al8.x86_64
  export PATH=\$JAVA_HOME/bin:\$PATH
  cd /www/wwwroot/cretas/code/backend/java/cretas-api
  mvn clean package -Dmaven.test.skip=true -q
"

# 3. 替换 jar + 重启 + cleanup patch
ssh root@47.100.235.168 "
  cd /www/wwwroot/cretas
  cp aims-0.0.1-SNAPSHOT.jar aims-0.0.1-SNAPSHOT.jar.bak.\$(date +%Y%m%d_%H%M%S)
  cp code/backend/java/cretas-api/target/cretas-backend-system-1.0.0.jar aims-0.0.1-SNAPSHOT.jar
  systemctl restart cretas-backend
  cd code && git checkout -- backend/java/cretas-api/
"
```

### Java 部署注意事项

- **JAR 命名**: 本地构建 `cretas-backend-system-1.0.0.jar`, 服务器运行名 `aims-0.0.1-SNAPSHOT.jar`
- **JAVA_HOME**: 本地 Zulu 21 (`C:/Program Files/Zulu/zulu-21`), 服务器 Dragonwell 21 (默认 javac 是 17, 必须显式 export)
- **Test 编译会失败**: `ProcessWorkReportingServiceImplTest` 方法签名不匹配, 必须用 `-Dmaven.test.skip=true` (不能用 `-DskipTests`, 后者只跳执行不跳编译)
- **Profile**: 生产 `pg-prod`, 测试 `pg`
- **环境变量**: `DB_PASSWORD`, `SMARTBI_DB_PASSWORD`, `JWT_SECRET` 在服务器 `.env.prod` (systemd EnvironmentFile)
- **启动时间**: prod ~20s, test ~75s (768MB 堆 GC 频繁)
- **回滚**: `--rollback` 自动恢复最近 timestamp 备份

### ⚠️ 本地 Java 启动方式 (CRITICAL)

**用 `mvn spring-boot:run` 启动本地后端, 不要用 `java -jar`**!

`java -jar fat-jar` 会 mmap 锁定 jar 文件 → mvn package 的 repackage 阶段无法 rename → **deploy 必失败**.

```bash
cd backend/java/cretas-api && \
DB_PASSWORD=cretas_pass POSTGRES_SMARTBI_PASSWORD=smartbi_pass \
JAVA_HOME="C:/Program Files/Zulu/zulu-21" \
SPRING_PROFILES_ACTIVE=pg,dev SPRING_JPA_HIBERNATE_DDL_AUTO=none \
SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5432/cretas_db?sslmode=disable" \
SERVER_PORT=10010 \
./mvnw.cmd spring-boot:run -Dmaven.test.skip=true \
  -Dspring-boot.run.jvmArguments="-Xms512m -Xmx1280m"
```

代价: 内存 ~3.2GB (vs java -jar 1.3GB), 启动 ~75s (vs 15s). 但 JVM 加载完类后不持有 .class file handle, mvn clean package 可同时跑.

---

## Phase 2: Python 服务部署

```bash
# 方式 A: 使用脚本
./scripts/deploy/deploy-smartbi-python.sh                # 部署到生产 (默认)
./scripts/deploy/deploy-smartbi-python.sh --env test     # 部署到测试
./scripts/deploy/deploy-smartbi-python.sh --env all      # 部署后重启两套
```

### Python 部署验证

```bash
curl -s http://47.100.235.168:8083/health    # 生产
curl -s http://47.100.235.168:8084/health    # 测试
```

### Python 注意事项

- **两套 Python 共享代码目录**: rsync 一次，通过环境变量区分数据库
- **虚拟环境**: 服务器使用 `/www/wwwroot/cretas/code/backend/python/venv38/`
- **依赖更新**: 脚本自动 `pip install -r requirements.txt`
- **.env 不要覆盖**: 服务器有自己的 `.env` (含 LLM API Key)

---

## Phase 3: Web 前端部署

```bash
# 用专门的部署脚本 (内部: npm build → tar 打包 → scp → 远端解压 atomic-swap)
./scripts/deploy/deploy-web-admin.sh --env test    # 测试 (139:8097, 默认)
./scripts/deploy/deploy-web-admin.sh --env prod    # 生产 (139:8086 / admin.cretaceousfuture.com, 需输 YES-PROD 确认)
```

### 前端注意事项

- ⚠️ **web-admin 在 139 (网关), 不是 47**! prod 路径 `/www/wwwroot/web-admin/` (139:8086 + admin.cretaceousfuture.com:443), test 路径 `/www/wwwroot/web-admin-test/` (139:8097).
- **传输用 tar+scp 不用 rsync**: dist 是大量 hash chunk 小文件, 打包成单 tar 一次传 + 远端解压 atomic-swap (整体替换避免新旧 chunk 混合的半成品). 不是因为 rsync 不可用 (rsync 现已恢复), 而是原子替换更安全.
- ❌ **不要手动 scp dist 目录**: 会丢目录权限 (assets 变 700 → nginx 403) 且容易传错服务器. 必须用脚本.
- **环境变量**: `.env.production` 中 `VITE_SMARTBI_URL=/smartbi-api` (代理路径，不是直连)
- **无需重启**: 部署后 nginx 自动生效

---

## Phase 4: 仅重启 (不构建)

```bash
# 重启全部 (生产+测试, Java+Python)
ssh root@47.100.235.168 "cd /www/wwwroot/cretas && bash restart.sh all"

# 仅重启生产
ssh root@47.100.235.168 "cd /www/wwwroot/cretas && bash restart.sh prod"

# 仅重启测试
ssh root@47.100.235.168 "cd /www/wwwroot/cretas && bash restart.sh test"

# 仅检查状态
ssh root@47.100.235.168 "ps aux | grep -E 'java|uvicorn' | grep -v grep"
```

---

## Phase 5: 部署后验证

**每次部署后必须执行的健康检查：**

```bash
# 生产环境
curl -s http://47.100.235.168:10010/api/mobile/health   # Java 生产
curl -s http://47.100.235.168:8083/health                # Python 生产

# 测试环境
curl -s http://47.100.235.168:10011/api/mobile/health   # Java 测试
curl -s http://47.100.235.168:8084/health                # Python 测试

# Web 前端
curl -s -o /dev/null -w "%{http_code}" http://47.100.235.168:8088/

# 全部一起检查
echo "=== Health Check ===" && \
echo -n "Java Prod:   " && curl -s -o /dev/null -w "%{http_code}" http://47.100.235.168:10010/api/mobile/health && echo "" && \
echo -n "Java Test:   " && curl -s -o /dev/null -w "%{http_code}" http://47.100.235.168:10011/api/mobile/health && echo "" && \
echo -n "Python Prod: " && curl -s -o /dev/null -w "%{http_code}" http://47.100.235.168:8083/health && echo "" && \
echo -n "Python Test: " && curl -s -o /dev/null -w "%{http_code}" http://47.100.235.168:8084/health && echo "" && \
echo -n "Web:         " && curl -s -o /dev/null -w "%{http_code}" http://47.100.235.168:8088/
```

向用户报告部署结果，包括：
1. 部署的组件
2. 健康检查状态
3. 如有失败，提供日志查看命令

---

## 故障排查

| 症状 | 排查命令 | 常见原因 |
|------|----------|---------|
| Java 启动后立即退出 | `ssh ... "tail -50 /www/wwwroot/cretas/cretas-backend.log"` | DB 密码错误、端口占用 |
| Python 500 错误 | `ssh ... "tail -50 /www/wwwroot/cretas/code/backend/python/python-services.log"` | 依赖缺失、.env 配置 |
| 前端白屏 | 浏览器 F12 Console | API 地址错误、CORS |
| 健康检查超时 | 等待 60s 后重试 | Spring Boot 冷启动慢 |
| JAR 上传 MD5 不匹配 | 重新运行 deploy-backend.sh | 网络不稳定 |

### 回滚

```bash
# Java 回滚到最近备份
ssh root@47.100.235.168 << 'EOF'
cd /www/wwwroot/cretas
LATEST_BAK=$(ls -t aims-0.0.1-SNAPSHOT.jar.bak.* 2>/dev/null | head -1)
if [ -n "$LATEST_BAK" ]; then
    cp "$LATEST_BAK" aims-0.0.1-SNAPSHOT.jar
    bash restart.sh
    echo "已回滚到: $LATEST_BAK"
fi
EOF
```

---

## 上传策略详情 (deploy-backend.sh v5.0)

### 上传通道 (Steve 2026-05-28 切 rsync 主; 2026-06-07 回国后确认 rsync 稳定)

| 通道 | 实测速度 | 启用条件 | 当前状态 |
|---|---|---|---|
| **rsync** | 通常跑满带宽 | 默认 (rsync 二进制可用) | ✅ 主力 |
| rsync + compress | 视内容 | 默认 fallback | ✅ 次选 |
| scp 直传 | 10.85 MB/s | 任何环境都能跑 | ✅ 兜底 |
| OSS 加速 | 6 MB/s | `ENABLE_R2` 系紧急 opt-in | ⏸ 默认禁用 (国外遗留) |
| Cloudflare R2 | 1.5 MB/s | `ENABLE_R2=1` + `R2_*` | ⏸ 默认禁用 (国外遗留) |
| ~~GitHub 镜像~~ | 永远失败 (private repo) | - | ❌ 自动跳过 |

> 旧"rsync 60KB/s 被 RST 永久禁用"结论是 Steve 在**国外**时跨境 SSH 的问题, 现已回国, rsync 恢复为主通道.

### 关键事实

**`cretas-media` bucket 属于阿里云账号 B** (AK redacted, see local credential file), 不是账号 A. 账号 A key 报 `AccessDenied`.

**Private repo 让 GitHub 镜像全失效**: ghproxy.cc / ghfast.top / cf.ghproxy.cc 等都是公共代理, curl 无 token, GitHub 对 release asset 返回 9 字节 `Not Found` 文本 (HTTP 200, 不是 404), 三个镜像 MD5 完全一致 = `9d1ead73e678fa2f51a70a933b0bf017`. v4.2 用 `gh api repos/$REPO --jq .private` 检测自动跳过.

### 阶段流程

**阶段 1 (传输)**: rsync 主 → rsync+compress → scp 兜底 (全 SSH-based, 谁可用谁上). R2/OSS/GitHub 默认禁用 (`ENABLE_R2=1` 紧急 opt-in).

**阶段 2**: 服务器 MD5 验证 + 备份 + Blue-Green 替换 + systemctl restart + 健康检查

**阶段 3**: 防御性 ping 另一环境 (DEPLOY_ENV=prod 时 ping test, 反之亦然)

### 健康检查 + 防御 ping

部署完显示:
```
🔍 [4/4] 验证部署...
   [生产] 检查 10010...
   ✓ 服务正常 (HTTP 200, 等待 16s)
   ✓ [防御检查] test 10011 同步运行
```

如果另一环境挂了:
```
   ⚠️  [防御检查] test 10011 异常 (HTTP 000)
      恢复: ssh root@47.100.235.168 'cd /www/wwwroot/cretas && bash restart.sh test'
      或下次用: ./scripts/deploy/deploy-backend.sh --env all
```

详见 memory `feedback_deploy_pipeline.md`.
