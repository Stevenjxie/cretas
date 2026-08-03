# 白垩纪食品溯源系统 - TimeClock 后端实现

## 📋 概述

本目录包含 **考勤打卡(TimeClock)** 功能的完整 Spring Boot 后端实现。

**功能特性**:
- ✅ 上班打卡 / 下班打卡
- ✅ 开始休息 / 结束休息
- ✅ 获取今日打卡记录 (`/today` 端点 - P0优先级)
- ✅ 获取打卡状态
- ✅ 获取打卡历史
- ✅ GPS位置记录
- ✅ 自动计算工作时长和休息时长

**技术栈**:
- Spring Boot 2.7.15
- Java 11
- MySQL 8.0
- Spring Data JPA
- Maven

---

## 📁 项目结构

```
backend-java/
├── src/main/java/com/cretas/aims/
│   ├── CretasBackendApplication.java    # Spring Boot 主类
│   ├── controller/
│   │   └── TimeClockController.java     # API 控制器
│   ├── service/
│   │   └── TimeClockService.java        # 业务逻辑
│   ├── repository/
│   │   └── TimeClockRepository.java     # 数据访问
│   └── entity/
│       └── TimeClockRecord.java         # 实体类
├── src/main/resources/
│   └── application.properties           # 应用配置
├── database/
│   └── create_timeclock_table.sql       # 数据库建表脚本
├── pom.xml                              # Maven 配置
├── build.sh                             # 仅供本地开发的编译脚本
├── run-local.sh                         # 本地运行脚本
├── test-timeclock-e2e.sh               # E2E 测试脚本
└── README.md                            # 本文档
```

---

## 🚀 快速开始

### 1. 环境准备

**必需软件**:
- ✅ Java 11 或更高版本
- ✅ Maven 3.6+
- ✅ MySQL 8.0+

**安装 Java 和 Maven (macOS)**:
```bash
# 使用 Homebrew 安装
brew install openjdk@11
brew install maven

# 验证安装
java -version
mvn -version
```

### 2. 数据库配置

**创建数据库**:
```sql
CREATE DATABASE cretas_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

**创建表**:
```bash
mysql -u root -p cretas_db < database/create_timeclock_table.sql
```

**配置数据库连接**:

编辑 `src/main/resources/application.properties`:
```properties
spring.datasource.url=jdbc:mysql://localhost:3306/cretas_db?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai
spring.datasource.username=root
spring.datasource.password=your_password_here
```

### 3. 编译项目

```bash
./build.sh
```

编译成功后，JAR 文件位于: `target/cretas-backend-system-1.0.0.jar`

### 4. 本地测试运行

```bash
./run-local.sh
```

服务启动后，访问: `http://localhost:10010`

### 5. 运行 E2E 测试

在另一个终端窗口运行:

```bash
./test-timeclock-e2e.sh
```

测试脚本会执行完整的打卡工作流程:
1. 查询今日打卡记录（初始状态）
2. 上班打卡
3. 查询今日打卡记录（已上班）
4. 获取打卡状态
5. 开始休息
6. 结束休息
7. 下班打卡
8. 查询今日打卡记录（已下班）
9. 获取打卡历史

---

## 🌐 API 端点

**Base URL**: `http://localhost:10010/api/mobile/{factoryId}/timeclock`

### 1. 上班打卡
```bash
POST /api/mobile/{factoryId}/timeclock/clock-in
参数:
  - userId (必需): 用户ID
  - location (可选): 打卡位置
  - device (可选): 设备信息
  - latitude (可选): GPS纬度
  - longitude (可选): GPS经度
```

### 2. 下班打卡
```bash
POST /api/mobile/{factoryId}/timeclock/clock-out
参数:
  - userId (必需): 用户ID
```

### 3. 开始休息
```bash
POST /api/mobile/{factoryId}/timeclock/break-start
参数:
  - userId (必需): 用户ID
```

### 4. 结束休息
```bash
POST /api/mobile/{factoryId}/timeclock/break-end
参数:
  - userId (必需): 用户ID
```

### 5. 获取打卡状态
```bash
GET /api/mobile/{factoryId}/timeclock/status
参数:
  - userId (必需): 用户ID
```

### 6. 获取今日打卡记录 ⭐ (P0 - 核心端点)
```bash
GET /api/mobile/{factoryId}/timeclock/today
参数:
  - userId (必需): 用户ID

响应:
  - 如果今日已打卡: 返回打卡记录
  - 如果今日未打卡: data 为 null
```

### 7. 获取打卡历史
```bash
GET /api/mobile/{factoryId}/timeclock/history
参数:
  - userId (必需): 用户ID
  - startDate (必需): 开始日期 (YYYY-MM-DD)
  - endDate (必需): 结束日期 (YYYY-MM-DD)
  - page (可选): 页码，默认1
  - size (可选): 每页大小，默认20
```

---

## 📝 测试示例

### 手动测试 (使用 curl)

**1. 上班打卡**:
```bash
curl -X POST "http://localhost:10010/api/mobile/F001/timeclock/clock-in?userId=1&location=上海市浦东新区&device=iPhone13&latitude=31.2304&longitude=121.4737"
```

**2. 获取今日打卡记录**:
```bash
curl "http://localhost:10010/api/mobile/F001/timeclock/today?userId=1"
```

**3. 下班打卡**:
```bash
curl -X POST "http://localhost:10010/api/mobile/F001/timeclock/clock-out?userId=1"
```

**4. 获取打卡历史**:
```bash
TODAY=$(date +%Y-%m-%d)
curl "http://localhost:10010/api/mobile/F001/timeclock/history?userId=1&startDate=$TODAY&endDate=$TODAY"
```

---

## 🚢 部署到生产服务器

生产发布统一从仓库根目录执行 `scripts/deploy/release-cretas.sh`。CI 负责构建并签名候选制品，发布入口优先取回可信制品或复用内容树缓存，再由受控的底层组件完成生产安装、蓝绿切流和结构化验收回执。

合并前在干净候选 worktree 中构建可信制品：

```bash
./scripts/deploy/release-cretas.sh \
  --phase build \
  --base-sha '<dispatch Base SHA>' \
  --tests '<本次目标测试>' \
  --stage-backend YES-STAGE
```

合并后**先预热**（把 CI 制品的跨境运输挪出发布窗口；幂等，可丢后台）。不预热则探测落空、
回退本地 Maven fallback（构建段实测 204s vs 25s），且 200s+ 构建期间 `origin/main` 常被并发推进，
导致构建全部成功却被 exact-main 复检整体作废：

```bash
./scripts/deploy/prewarm-main-artifact.sh \
  --tests '<本次目标测试>' \
  --wait 420
```

看到 `PREWARM=done` / `already-warm` 后，仅从 clean exact `origin/main` 执行生产发布：

```bash
./scripts/deploy/release-cretas.sh \
  --phase deploy \
  --base-sha '<dispatch Base SHA>' \
  --tests '<本次目标测试>' \
  --confirm-prod YES-PROD
```

发布成败的唯一可靠判据：`DEPLOY_EXIT=0` **且**日志里 `RELEASE_FINAL_STATUS` 恰好出现 1 次
（该行不出现本身就是失败信号）。被 exact-main 闸拒绝时是静默的 —— 构建 `BUILD SUCCESS`、
测试全绿、然后戛然而止，回执里 `deploy_mode: none`；`git fetch && git checkout --detach origin/main`
拉最新重跑即可。

不要从服务器拉取任意分支现场编译，也不要直接杀固定端口进程。Java 生产运行在 PostgreSQL + Flyway 上，并在 `10010`/`10020` 两个 systemd 槽位间蓝绿切换；真实 active 槽位必须从 Nginx upstream 读取。

---

## 🔍 故障排查

### 问题1: 编译失败

**检查**:
```bash
# 检查 Java 版本
java -version  # 应该是 11+

# 检查 Maven 版本
mvn -version   # 应该是 3.6+

# 清理重试
mvn clean
./build.sh
```

### 问题2: 数据库连接失败

**检查**:
```bash
# 测试数据库连接
mysql -u root -p -h localhost -P 3306 cretas_db

# 检查表是否存在
mysql -u root -p cretas_db -e "SHOW TABLES LIKE 'time_clock_record';"
```

### 问题3: 端口被占用

**检查并释放端口**:
```bash
# macOS/Linux
lsof -i :10010
kill -9 <PID>

# 或者修改端口
# 编辑 src/main/resources/application.properties
# server.port=另一个端口
```

### 问题4: API 返回 500 错误

**查看日志**:
```bash
# 本地运行时，终端会直接显示日志

# 服务器部署后（先按真实 active 槽位选择服务）
systemctl status cretas-backend cretas-backend-green --no-pager
journalctl -u cretas-backend -u cretas-backend-green -n 100 --no-pager
```

---

## 📚 前端集成

前端 React Native 代码已更新为使用新的 `/today` 端点:

**文件**: `frontend/CretasFoodTrace/src/screens/attendance/TimeClockScreen.tsx`

**修改内容**:
```typescript
// ✅ 使用 /timeclock/today 端点获取今日打卡记录
const todayResponse = await timeclockApiClient.getTodayRecord(userId, factoryId);

if (todayResponse.data) {
  // 后端返回今日打卡记录
  setTodayRecords([todayResponse.data]);
  setLastClockIn(todayResponse.data);
} else {
  // 今日未打卡
  setTodayRecords([]);
  setLastClockIn(null);
}
```

**前端 API 客户端**: `frontend/CretasFoodTrace/src/services/api/timeclockApiClient.ts`

---

## ✅ 完成状态

- [x] TimeClockRecord 实体类
- [x] TimeClockRepository 数据访问层
- [x] TimeClockService 业务逻辑层
- [x] TimeClockController API 控制器
- [x] `/today` 端点实现 (P0 优先级)
- [x] 数据库建表脚本
- [x] Maven 配置文件
- [x] Spring Boot 主类
- [x] 编译脚本
- [x] 部署脚本
- [x] E2E 测试脚本
- [x] 前端代码更新（移除降级处理）

---

## 🎯 下一步

1. **本地测试** ✅
   ```bash
   ./build.sh
   ./run-local.sh
   ./test-timeclock-e2e.sh
   ```

2. **部署到服务器** 📤
   ```bash
   # 从仓库根目录按上方统一发布流程执行
   ./scripts/deploy/release-cretas.sh --help
   ```

3. **前后端联调** 🔄
   - 启动 React Native 前端
   - 测试完整的打卡流程
   - 验证数据正确保存和显示

4. **生产发布** 🚀
   - 确保所有测试通过
   - 备份数据库
   - 部署到生产环境
   - 监控服务状态

---

## 📞 支持

如有问题，请查看:
1. 本 README 的故障排查部分
2. 后端日志: `cretas-backend.log`
3. 数据库连接配置: `application.properties`

---

**开发完成时间**: 2025-11-15
**版本**: 1.0.0
**状态**: ✅ 完成 - 可以投入使用
# Deploy Test 2026-01-21 06:03:00
# Test Wed Jan 21 06:04:55 EST 2026
# Test Wed Jan 21 06:07:38 EST 2026
# Test Wed Jan 21 06:13:30 EST 2026
