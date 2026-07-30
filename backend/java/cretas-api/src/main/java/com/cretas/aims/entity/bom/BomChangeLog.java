package com.cretas.aims.entity.bom;

import com.cretas.aims.entity.BaseEntity;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.Where;

/**
 * BOM 变更痕迹记录 (P1-9, v1 §2.2.6 痕迹追踪).
 *
 * <p>客户需求: BOM 配方不是一成不变, 随季节/原料供应/成本调整会修改. 客户需要
 * 审计 BOM 变更历史 (什么时候把带鱼段 10 kg 改成了 12 kg / 谁改的 / 为什么).
 *
 * <p>设计: 每次 BomRecipe 或 BomRecipeItem 的 CREATE/UPDATE/DELETE 写一条 log, 用 JSONB
 * 存 old/new 全量 snapshot 支持任意字段对比.
 *
 * <p>Service 层可以用 @EventListener 订阅 BomRecipe 变更事件,
 * 或者用 Hibernate envers, 或者 AOP around BomService 写入方法.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "bom_change_logs",
        indexes = {
                @Index(name = "idx_bcl_recipe", columnList = "bom_recipe_id"),
                @Index(name = "idx_bcl_recipe_item", columnList = "bom_recipe_item_id"),
                @Index(name = "idx_bcl_factory_time", columnList = "factory_id,changed_at")
        }
)
@Where(clause = "deleted_at IS NULL")
public class BomChangeLog extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) id = UUID.randomUUID().toString();
    }

    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    /** BomRecipe ID。 */
    @Column(name = "bom_recipe_id", length = 191)
    private String bomRecipeId;

    /** BomRecipeItem ID (nullable — 为 null 表示配方头级变更). */
    @Column(name = "bom_recipe_item_id")
    private Long bomRecipeItemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 20)
    private ChangeType changeType;

    /** 变更前快照 (JSONB, full object); CREATE 时 null. */
    @Type(JsonBinaryType.class)
    @Column(name = "old_value", columnDefinition = "jsonb")
    private Map<String, Object> oldValue;

    /** 变更后快照 (JSONB, full object); DELETE 时 null. */
    @Type(JsonBinaryType.class)
    @Column(name = "new_value", columnDefinition = "jsonb")
    private Map<String, Object> newValue;

    /** 变更人 (userId). */
    @Column(name = "changed_by")
    private Long changedBy;

    /** 变更人姓名 (冗余). */
    @Column(name = "changed_by_name", length = 100)
    private String changedByName;

    /** 变更原因 (业务级说明, e.g. "季节调整带鱼段用量"). */
    @Column(name = "change_reason", columnDefinition = "TEXT")
    private String changeReason;

    public enum ChangeType {
        CREATE,
        UPDATE,
        DELETE
    }
}
