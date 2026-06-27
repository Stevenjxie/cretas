# 前后端字段命名规范

**最后更新**: 2026-01-22

---

## 命名约定

| 层级 | 命名风格 | 示例 |
|------|----------|------|
| Java Entity | camelCase | `batchNumber` |
| 数据库列 | snake_case | `batch_number` |
| JSON API | camelCase | `"batchNumber"` |
| TypeScript | camelCase | `batchNumber` |

---

## 正确示例

```java
@Entity
@Table(name = "material_batches")  // snake_case 表名
public class MaterialBatch {
    @Column(name = "batch_number")  // snake_case 列名
    private String batchNumber;      // camelCase 字段
}
```

```typescript
// 前端 interface 统一 camelCase
interface MaterialBatch {
  batchNumber: string;
  materialTypeId: string;
}
```

---

## 禁止做法

```java
// ❌ JSON 使用 snake_case
@JsonProperty("batch_number")
private String batchNumber;

// ❌ 数据库列名用 camelCase
@Column(name = "batchNumber")
```

```typescript
// ❌ 前端混用 snake_case
interface Batch {
  batch_number: string;  // 错误
}
```
