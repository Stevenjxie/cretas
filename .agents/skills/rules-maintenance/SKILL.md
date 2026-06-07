---
name: rules-maintenance
description: 检查和更新 Claude Rules 内容。包括格式检查、统计数据同步、过时内容清理。使用 /rules-cleanup 或 /rules-update 调用。
allowed-tools:
  - Bash
  - Read
  - Glob
  - Grep
  - Edit
---

# Rules 维护 Skill

维护 `.claude/rules/` 目录中的规则文件。

## Rules 目录

```
.claude/rules/
```

## 检查步骤

### 1. 代码库统计

使用 Grep 工具收集以下统计数据：

| 指标 | Grep 模式 | 搜索范围 |
|------|----------|----------|
| `as any` 使用数 | `as any` | `frontend/CretasFoodTrace/src/` (*.ts, *.tsx) |
| Controller 数量 | `@RestController\|@Controller` | `backend/java/cretas-api/src/` (*.java) |
| API 端点数 | `@.*Mapping` | `backend/java/cretas-api/src/.../controller/` (*.java) |
| 硬编码密码 | `password.*=.*["']` | `backend/java/cretas-api/` |
| Hermes 不兼容 | `toLocaleString\|toLocaleDateString` | `frontend/CretasFoodTrace/src/` |

### 2. Rules 格式检查

对 `.claude/rules/` 中每个 `.md` 文件检查:

- [ ] 包含 `**最后更新**` 日期标记
- [ ] 内容与当前代码库一致
- [ ] 引用的文件路径仍然有效
- [ ] 统计数据未过时

### 3. 同步检查项

| Rule 文件 | 需要检查的统计数据 |
|-----------|-------------------|
| `api-response-handling.md` | Controller 数量、API 端点数 |
| `typescript-type-safety.md` | `as any` 使用数 |
| `server-operations.md` | 服务器 IP、端口、目录结构 |
| `aliyun-credentials.md` | 服务器到期时间、实例信息 |
| `CREDENTIAL-MANAGEMENT.md` | 环境变量列表 |

### 4. MEMORY.md 检查

同时检查 auto memory 文件是否需要更新:
- 路径: `.claude/projects/C--Users-Steve-my-prototype-logistics/memory/MEMORY.md`
- 确保不超过 200 行限制
- 过时信息应移入主题文件或删除

## 更新操作

### 更新统计数据

在对应 rule 文件中更新 "统计现状" 或 "后端规模统计" 章节：

```markdown
### 统计现状 (更新于 YYYY-MM-DD)

当前项目存在 **XX 处** `as any` 类型断言
```

### 更新版本信息

每次修改 rule 后，更新文件头部：

```markdown
**最后更新**: YYYY-MM-DD
```

## 检查清单

- [ ] 统计数据是否与代码库一致
- [ ] 文件引用路径是否有效
- [ ] 服务器信息是否为最新 (47.100.235.168)
- [ ] 数据库引用是否为 PostgreSQL (非 MySQL)
- [ ] 格式是否完整 (概述/Rule章节/相关文件)
