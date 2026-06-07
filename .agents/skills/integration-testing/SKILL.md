---
name: integration-testing
description: 运行后端和前端集成测试，检查 API 和 UI 交互。包含 E2E 测试执行、结果分析、失败诊断。使用此 Skill 来验证系统功能完整性，运行端到端测试，或调试集成问题。
allowed-tools:
  - Bash
  - Read
  - Grep
  - Glob
---

# 集成测试 Skill

## 测试套件总览

| 套件 | 位置 | 技术 | 覆盖范围 |
|------|------|------|----------|
| API Shell 测试 | `tests/api/` | Bash + curl | 20+ 个 REST API 测试脚本 |
| SmartBI E2E | `tests/e2e-smartbi/` | Playwright | Excel 上传、图表、AI 问答、财务分析 |
| Intent Routing | `tests/intent-routing-e2e-150.py` | Python | 208 条意图识别测试用例 (34 类别) |
| E2E Round4 | `tests/e2e-round4/` | Playwright | 134 页面 × 10 角色全覆盖 |

---

## 1. API Shell 测试

### 检查/启动后端

```bash
# 检查后端是否运行
curl -s -o /dev/null -w "%{http_code}" http://47.100.235.168:10010/api/mobile/health

# 本地启动后端 (如需要)
cd backend/java/cretas-api
JAVA_HOME="C:/Program Files/Java/jdk-17" ./mvnw.cmd clean package -Dmaven.test.skip=true -q
cd ../../..
DB_PASSWORD=cretas_pass POSTGRES_SMARTBI_PASSWORD=smartbi_pass \
  java -jar backend/java/cretas-api/target/cretas-backend-system-1.0.0.jar \
  --spring.profiles.active=pg,dev \
  --spring.jpa.database-platform=org.hibernate.dialect.PostgreSQL10Dialect \
  --spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQL10Dialect
```

### 运行测试

```bash
cd tests/api

# 认证测试
bash test_authentication.sh 2>&1 | tee /tmp/auth_test.log

# 材料批次测试
bash test_phase2_1_material_batches.sh 2>&1 | tee /tmp/material_test.log

# 设备管理测试
bash test_phase2_2_equipment.sh 2>&1 | tee /tmp/equipment_test.log
```

### 分析结果

```bash
# 检查失败
grep -i "FAIL\|ERROR\|failed" /tmp/*.log | head -30

# 统计通过/失败
grep -c "PASS\|SUCCESS" /tmp/*.log 2>/dev/null || echo "0 passed"
grep -c "FAIL\|ERROR" /tmp/*.log 2>/dev/null || echo "0 failed"
```

---

## 2. SmartBI E2E 测试 (Playwright)

```bash
cd tests/e2e-smartbi

# 安装依赖
npm install && npx playwright install chromium

# 运行测试
npm test                        # 无头模式
npm run test:headed             # 有头模式
npm run test:debug              # 调试模式

# Ralph Loop 持续测试
npm run ralph-loop              # 无限循环
LOOPS=50 npm run ralph-loop     # 运行 50 轮

# 查看报告
npm run report
```

**环境变量**:
- `BASE_URL` — 默认 `http://localhost:5173` (本地 Vite dev), 生产用 `http://47.100.235.168:8088`
- 测试数据: `Test.xlsx` (11 sheets), 264 行财务数据, enrichment 约需 30-40s

---

## 3. Intent Routing 测试

```bash
# 需要先登录获取 token
cd tests
python intent-routing-e2e-150.py

# 测试结果: 206/208 (99%), 34 categories
# API: POST /api/mobile/{factoryId}/ai-intents/recognize
```

---

## 4. 远程服务器测试

```bash
# 测试生产服务器
export API_BASE_URL="http://47.100.235.168:10010"
bash tests/api/test_authentication.sh
```

---

## 常见问题排查

| 症状 | 可能原因 | 解决方案 |
|------|----------|----------|
| 后端启动超时 | PostgreSQL 未运行 | `systemctl start postgresql` |
| 端口被占用 | 旧进程未退出 | `ss -tlnp \| grep 10010` 找到 PID 后 kill |
| API 返回 401 | JWT token 过期 | 重新登录获取新 token |
| 连接超时 | 网络/防火墙问题 | 检查安全组和 `ss -tlnp` |
| 数据库错误 | PG 连接失败 | `systemctl status postgresql` → 检查 pg_hba.conf |
| Playwright 超时 | Vite 启动在其他端口 | 检查 5173/5174/5175 哪个端口有 Vite |
| enrichment 超时 | 264行 sheet 需 30-40s | 增加 Playwright timeout |

## 测试账号

**密码统一**: `123456`

| 账号 | 角色 | factoryId |
|------|------|-----------|
| factory_admin1 | 工厂管理员 | F001 |
| workshop_sup1 | 车间主任 | F001 |
| warehouse_mgr1 | 仓储主管 | F001 |
| hr_admin1 | HR 管理员 | F001 |
| dispatcher1 | 调度员 | F001 |
| quality_insp1 | 质检员 | F001 |
| platform_admin | 平台管理员 | - |

## 参考

- 后端代码: `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/`
- API Shell 测试: `tests/api/`
- SmartBI E2E: `tests/e2e-smartbi/`
- Intent 测试: `tests/intent-routing-e2e-150.py`
- E2E Round4: `tests/e2e-round4/`
