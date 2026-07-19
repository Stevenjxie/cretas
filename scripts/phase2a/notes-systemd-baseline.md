# Phase 2A T1 — systemd / Python JWT_SECRET 同步 baseline

**Date:** 2026-04-29 12:34 UTC
**Status:** ✅ DONE — 两个 Python 环境都能读 JWT_SECRET，无需新增配置

---

## 现状审计（T1 执行前）

### Prod Python (端口 8083, systemd-managed)

文件：`/etc/systemd/system/cretas-python.service`

```
Environment=JWT_SECRET=<redacted historical inline value>
```

inline 已经包含 JWT_SECRET（值与 `.env.prod` 中的同名变量相同）。

### Test Python (端口 8084, nohup-managed via restart-test.sh)

启动命令（节选）：

```
JWT_SECRET=cretas-jwt-secret-key-2026-test nohup ... python ... uvicorn main:app --port 8084
```

inline 已经包含 JWT_SECRET（注意 test 环境用 `-test` 后缀，与 prod 不同）。

### Java 服务 (对比基线)

```
cretas-backend.service:        EnvironmentFile=/www/wwwroot/cretas/.env.prod
cretas-backend-test.service:   EnvironmentFile=/www/wwwroot/cretas/.env.test
```

---

## T1 实施

### Prod (cretas-python.service)

执行：

```bash
ssh root@47.100.235.168 "cp /etc/systemd/system/cretas-python.service /etc/systemd/system/cretas-python.service.bak.20260429_123338"
ssh root@47.100.235.168 "sed -i '/^\[Service\]\$/a EnvironmentFile=/www/wwwroot/cretas/.env.prod' /etc/systemd/system/cretas-python.service"
ssh root@47.100.235.168 "systemctl daemon-reload && systemctl restart cretas-python"
```

变更：在 `[Service]` 段后追加 `EnvironmentFile=/www/wwwroot/cretas/.env.prod`。

**历史状态（已废止）**：inline `Environment=JWT_SECRET=...` 曾被保留。systemd 中 inline `Environment=` 会覆盖 `EnvironmentFile=`，因此凭据轮换必须删除同名 inline secret，只保留 `0600` 环境文件。

未来若要让 Python 自动跟随 `.env.prod` 中的 secret 轮换，需要从 service 文件中删除 inline `Environment=JWT_SECRET=...` 行（但要先确认 `.env.prod` 包含所有 inline 中的其他变量，避免破坏服务启动）。

### Test (restart-test.sh nohup 模式)

**未做改动**。理由：`restart-test.sh` 已经在 nohup 命令中 inline 了 `JWT_SECRET=cretas-jwt-secret-key-2026-test`，目标"Python 能读 JWT_SECRET"已满足。

待 Phase B-N 把 test Python 改为 systemd 管理时，再加 `EnvironmentFile=/www/wwwroot/cretas/.env.test`。

---

## 验证

### Prod

```bash
ssh root@47.100.235.168 "systemctl show cretas-python --property=Environment | grep -c JWT_SECRET"
→ 1
ssh root@47.100.235.168 "curl -s http://localhost:8083/health"
→ {"status":"healthy",...}
```

### Test

```bash
ssh root@47.100.235.168 "cat /proc/$(lsof -ti :8084)/environ | tr '\0' '\n' | grep JWT_SECRET"
→ JWT_SECRET=cretas-jwt-secret-key-2026-test
```

---

## 回滚

```bash
ssh root@47.100.235.168 "cp /etc/systemd/system/cretas-python.service.bak.20260429_123338 /etc/systemd/system/cretas-python.service"
ssh root@47.100.235.168 "systemctl daemon-reload && systemctl restart cretas-python"
```

---

## 后续（不在 T1 范围）

- Phase B-N: test Python 8084 改 systemd 管理 → 那时再加 `EnvironmentFile=/www/wwwroot/cretas/.env.test`
- Phase 2B+: 评估是否删除 service 文件中的 inline `Environment=JWT_SECRET=...` 行，让 secret 完全跟随 `.env.prod`
