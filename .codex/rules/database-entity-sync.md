# 数据库与Entity同步规范

**最后更新**: 2026-02-19

---

## 数据库类型

**生产环境和开发环境均使用 PostgreSQL**（已从 MySQL 迁移完成）。

---

## BaseEntity 必需字段

继承 `BaseEntity` 的实体，数据库表**必须**包含：

| 字段 | 类型 (PostgreSQL) | 说明 |
|------|-------------------|------|
| `created_at` | TIMESTAMP DEFAULT NOW() | 创建时间 |
| `updated_at` | TIMESTAMP DEFAULT NOW() | 更新时间 |
| `deleted_at` | TIMESTAMP NULL | 软删除 |

```sql
-- PostgreSQL
ALTER TABLE {table_name}
ADD COLUMN created_at TIMESTAMP DEFAULT NOW(),
ADD COLUMN updated_at TIMESTAMP DEFAULT NOW(),
ADD COLUMN deleted_at TIMESTAMP NULL;

-- 自动更新 updated_at (需要触发器)
CREATE OR REPLACE FUNCTION update_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trigger_updated_at
BEFORE UPDATE ON {table_name}
FOR EACH ROW EXECUTE FUNCTION update_updated_at();
```

---

## 新建 Entity 检查清单

- [ ] 继承 BaseEntity → 确保表有 audit 字段
- [ ] 检查 @Column 字段在数据库中存在
- [ ] 检查 @JoinColumn 外键约束
- [ ] GROUP BY 包含所有非聚合列 (PG 严格模式)

---

## MySQL → PostgreSQL 差异

| MySQL | PostgreSQL | 说明 |
|-------|-----------|------|
| `INSERT IGNORE` | `INSERT ... ON CONFLICT DO NOTHING` | 忽略重复 |
| `DATETIME` | `TIMESTAMP` | 时间类型 |
| `ON UPDATE CURRENT_TIMESTAMP` | 需触发器 | 自动更新 |
| `boolean 1/0` | `boolean true/false` | 布尔值 |
| `CONCAT(a, b)` | `a \|\| b` | 字符串拼接 |
| 宽松 GROUP BY | 严格 GROUP BY | 必须列出所有非聚合列 |

---

## 常见错误

### Unknown column 'xxx_.created_at'

Entity 继承 BaseEntity，但表缺少 created_at 列：
```sql
ALTER TABLE {table_name} ADD COLUMN created_at TIMESTAMP DEFAULT NOW();
```

### GROUP BY 错误 (PostgreSQL)

```
column "xxx" must appear in the GROUP BY clause or be used in an aggregate function
```
修复: 在 GROUP BY 中列出所有 SELECT 的非聚合列。

### could not determine data type of parameter $N (PostgreSQL)

JPQL **parameter-side** `IS NULL` 检查在 PG 上失败，因为 PG 严格类型推断不接受 untyped `?` 占位符。

**反 pattern**（PG 报错 `could not determine data type of parameter`）：

```java
// ❌ BAD: :status 当 null 传入, PG 推不出 ? 的类型
@Query("SELECT r FROM Foo r WHERE r.factoryId = :factoryId " +
       "AND (:status IS NULL OR r.status = :status)")
```

**正 pattern**（用 `CAST` 给 PG 类型 hint）：

```java
// ✅ GOOD: CAST(:status AS string) 让 Hibernate 显式声明类型
@Query("SELECT r FROM Foo r WHERE r.factoryId = :factoryId " +
       "AND (CAST(:status AS string) IS NULL OR r.status = :status)")
```

**何时适用**：
- JPQL 中**参数**出现在 `IS NULL` 左边（不是列）— 即 `(:param IS NULL OR ...)`
- 参数实际**会传 null**（call site 检查）
- 数据库是 PostgreSQL（H2/MySQL 不强制类型推断，所以 CI mock 漏报）

**列-side null 不需要 CAST**（列类型 PG 从 schema 已知）：

```java
// ✅ OK: 列在 IS NULL 左边, PG 知道 c.factoryId 的类型
"AND (c.factoryId IS NULL OR c.factoryId = :factoryId)"
```

**为什么用 `string`**：Hibernate 6 的 `CAST(... AS string)` 给所有 Java 类型（enum/LocalDate/Boolean/...）一个 universal toString 转换。CAST 本身只用作 null 检测的类型 hint，并不影响 `r.status = :status` 的实际比较（Hibernate 还是按 entity 列类型走）。

**Audit grep**（找潜在风险）：

```bash
# 找 parameter-side IS NULL pattern (param `:` 紧跟 IS NULL)
grep -rn ':[a-zA-Z][a-zA-Z0-9]* IS NULL' backend/java/cretas-api/src/main/java --include='*.java'
```

**修复历史**：
- PR #120 (`839ef9df57`) — `RawMaterialTypeRepository.findSimilarByNameAndCategory` 首次踩 + 修
- PR after #169 (May 9 2026) — `MaterialRequisitionRepository.findByFilters` + `WastageRecordRepository.findByFilters` 同样问题（CI H2 mock 漏报，PG test env deploy 后才暴）
- 已知 dead code 同模式: `ProductionPlanRepository.findByFactoryIdWithFilters` (0 callers, 不修)

---

## 推荐配置

```properties
# 开发环境
spring.jpa.hibernate.ddl-auto=update

# 生产环境 (禁止自动修改 schema)
spring.jpa.hibernate.ddl-auto=none
```
