# Sprint 5 H-1 — Attachment + Inline Link Counter spec (MVP delivered + Sprint 6 follow-up)

**Date**: 2026-05-19
**Author**: Sprint 5 Track-H agent
**Status**: MVP shipped this sprint; deep integration deferred to Sprint 6 (跟 Track C-2)

---

## §1 背景 — dispatch ask vs reality

Dispatch §H H-1 brief 描述:

> **Source**: Round 12 §B.6 X2 — Cretas BusinessLink 8 类 (sale/sample/request/produce/outsource/stock/project/free) vs HJ baseline 8 类 (file/image/contract/sample/request/produce/outsource/stock). **3 类 mismatch**.
>
> **Decision**: 扩 11 类 (Cretas 8 + HJ 3 = file/image/contract) OR 拆 AttachmentRecord 独立 entity
>
> **推荐**: 拆 AttachmentRecord 独立 entity (file/image/contract 是 attachment 性质, 不应跟 sale/sample 等业务关联混)
>
> **Backend**: `AttachmentRecord` entity (id / entityType / entityId / fileType / fileUrl / uploaderId) — 跟 Round 11 N20 C-ATT-1 集成 (已 ship)

**Reality (grep-verified pre-coding per HARD rule `feedback_brief_must_grep_existing_endpoint_paths.md`)**:

- N20 C-ATT-1 (PR #658, 2026-05-15) 已 ship 完整的 `Attachment` entity:
  - `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/Attachment.java`
  - `backend/java/cretas-api/src/main/resources/db/flyway/V20260516_01__attachment.sql`
- Attachment 已是多态 (entity_type + entity_id, 18 个 EntityType 白名单)
- Attachment 已支持 8 endpoint via AttachmentController
- 已支持 5 大业务场景 (客户跟踪 / 采购订单 / 质检 / 生产证据 / 财务凭证)

**结论**: 不需要拆新的 `AttachmentRecord` entity — 现有 `Attachment` 已覆盖. **brief 写时未 grep main**.
正确 MVP scope: 把 `Attachment.FileCategory` 加 `CONTRACT` 值, 让 SalesOrderListDTO inline link counter
可以按 PHOTO + DOCUMENT + CONTRACT 三分类 query, 对齐 HJ `文件(N) 图片(N) 合同(N)` UX.

BusinessLink 8 类 (sale/sample/request/produce/outsource/stock/project/free) 跟 HJ "file/image/contract"
是 **不同语义** 的两个东西:
- BusinessLink = 业务单 跨域关联 (e.g. ReturnOrder 关联回 SalesOrder)
- file/image/contract = 文件附件三分类 (Attachment.FileCategory)

把这俩塞到同一 enum 是 brief 误判. 正解: BusinessLink 保持 8 类不动 + Attachment 加 CONTRACT 类.

---

## §2 本 sprint MVP delivered (Sprint 5 H-1)

### 2.1 Backend — Attachment.FileCategory 加 CONTRACT

| 文件 | 改动 |
|---|---|
| `entity/Attachment.java` | enum 加 `CONTRACT` 值 + Javadoc 解释跟 DOCUMENT/VOUCHER 区别 |
| `db/flyway/V20260519_06__attachment_contract_category.sql` | DROP+CREATE `chk_att_category` CHECK 加 'CONTRACT' |

**RBAC**: 无变化, 沿用 Attachment 现有 RBAC.

**测试**: 现有 AttachmentServiceImpl 测试不破坏 (CONTRACT 是新增值, 旧测试不引用).
register 时 caller 可 explicit 传 `fileCategory: CONTRACT`, 或 默认 inferCategory 从 MIME 推 DOCUMENT
(CONTRACT 不可从 MIME 推, 业务上需 explicit 标注).

### 2.2 Backend — 不做

- ❌ 不创建 `AttachmentRecord` 新 entity (已被 PR #658 Attachment 覆盖)
- ❌ 不改 BusinessLink 8 类 (BusinessLink + Attachment 是两个独立模型, 不混)
- ❌ 不写新 migration 加 `business_links.link_type` 加 'file/image/contract' (语义错误)

---

## §3 Sprint 6 follow-up (deferred to Track C-2 + Sprint 6 spec)

### 3.1 SalesOrderListDTO 加 linkCounts 字段

```java
public class SalesOrderListDTO {
    // ... existing fields ...

    /**
     * Inline link counter — Sprint 6 follow-up, 对齐 HJ 销售单 list 行内
     * `文件(N) 图片(N) 合同(N)` 3 chip row.
     */
    private LinkCounts linkCounts;

    @Data @AllArgsConstructor @NoArgsConstructor
    public static class LinkCounts {
        private int file;     // file_category IN ('DOCUMENT', 'OTHER')
        private int image;    // file_category IN ('PHOTO', 'VIDEO')
        private int contract; // file_category = 'CONTRACT'
    }
}
```

### 3.2 SQL helper

```sql
-- 单个 SalesOrder 的 link counts
SELECT
    SUM(CASE WHEN file_category IN ('DOCUMENT', 'OTHER') THEN 1 ELSE 0 END) AS file_count,
    SUM(CASE WHEN file_category IN ('PHOTO', 'VIDEO')     THEN 1 ELSE 0 END) AS image_count,
    SUM(CASE WHEN file_category = 'CONTRACT'              THEN 1 ELSE 0 END) AS contract_count
FROM attachments
WHERE factory_id = ? AND entity_type = 'SALES_ORDER' AND entity_id = ? AND deleted_at IS NULL
```

**注意 `SALES_ORDER` 还需要先加入 `EntityType` 枚举 + CHECK constraint** (Sprint 6 task pre-req).

### 3.3 Frontend — SalesOrderList.vue 加 chip row

```vue
<el-table-column label="附件" width="180">
  <template #default="{ row }">
    <el-tag size="small" type="info">文件 ({{ row.linkCounts?.file ?? 0 }})</el-tag>
    <el-tag size="small" type="primary">图片 ({{ row.linkCounts?.image ?? 0 }})</el-tag>
    <el-tag size="small" type="success">合同 ({{ row.linkCounts?.contract ?? 0 }})</el-tag>
  </template>
</el-table-column>
```

跟 Track C-2 (G12-1 inline link counter, P1 4d) 集成 — 那个 brief 描述 4 list (sales/PO/inventory/voucher)
全 ship. C-2 brief 应在 Sprint 6 dispatch 时引用本 spec §3.1-3.3.

### 3.4 Track C-2 跟本 spec 协同 checklist

- [ ] (C-2) SalesOrderListDTO + ProcurementOrderListDTO + InventoryListDTO + VoucherListDTO 加 `linkCounts` 字段
- [ ] (C-2) 后端 LEFT JOIN attachments + GROUP BY 性能 — 大量 SO list 可能需要 cache (Sprint 6 设计)
- [ ] (C-2) Frontend 4 list 加 chip row
- [ ] (本 spec) 确保 `SALES_ORDER` `PURCHASE_ORDER` `INVENTORY` `VOUCHER` 都在 `Attachment.EntityType` 白名单
  (现有: `PURCHASE_ORDER` ✅, `INVOICE` ✅, `PAYMENT_VOUCHER` ✅; **缺**: `SALES_ORDER`, `INVENTORY`)
- [ ] (本 spec) 加 migration 扩 `chk_att_entity_type` CHECK + Java EntityType enum

---

## §4 Open questions for Sprint 6

1. **Cache vs JOIN**: SalesOrderList 一次返 50 条, 每条 `SUM(...) GROUP BY` 3 类 — 是否性能可接受?
   benchmark 后决定 cache 或 inline JOIN.
2. **business_tag vs file_category**: 现有 Attachment 有 `business_tag` 自由文本 ('CONTRACT_SCAN' 等),
   要不要 deprecate business_tag 让 file_category=CONTRACT 是 single source of truth?
   Sprint 6 决策: 保留 business_tag 用于 sub-classify (合同类型, e.g. 'PURCHASE_CONTRACT' vs 'SALES_CONTRACT').
3. **Permission**: contract attachment 看权限 是否需 RBAC 单独 grant? 现有附件继承 entity 权限, 合同可能更敏感.
   Sprint 6 评估是否加 `attachment:contract:view` 权限码.

---

## §5 Audit references

| 来源 | 内容 |
|---|---|
| Dispatch 2026-05-19 §H H-1 | 原始 ask (拆 AttachmentRecord 或扩 11 类) |
| PR #658 (f296447c6) | Attachment entity ship (Track-C C-ATT-1) |
| PR #671 (2eb3da928) | AttachmentController + Print RBAC follow-up |
| Round 12 §B.6 X2 | HJ link_type 8 类 vs Cretas 8 类对比 |
| `feedback_brief_must_grep_existing_endpoint_paths.md` HARD | Pre-flight grep 抓到 brief 误判 |

---

**Sprint 5 H-1 spec v1.0 (2026-05-19)**
