package com.cretas.aims.entity.canvas;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

import java.util.UUID;

/**
 * Canvas-Phase C: 枚举字典 (Enum Dictionary).
 *
 * <p>统一存储防呆 Rule 3 (自由文本改约束选择) 的 enum dropdown 值。覆盖
 * CANCEL_REASON / RETURN_REASON / APPROVAL_OPINION / DEFECT_SEVERITY /
 * NONCONFORM_TYPE / WASTAGE_REASON / RECALL_LEVEL / URGENCY_LEVEL 等 8 大类。
 *
 * <p>{@code factoryId} 语义:
 * <ul>
 *   <li>{@code "*"} → global default (跨工厂; 见 {@code GLOBAL_FALLBACK_FACTORY_ID})</li>
 *   <li>其他 → per-factory override (优先级最高)</li>
 * </ul>
 *
 * <p>Resolver service {@code EnumDictionaryResolverService.getEnumValues(factoryId, category)}
 * 先查 per-factory, 不存在则回退到 global "*"。前端取得后即填 el-select dropdown.
 *
 * <p>唯一约束 (partial unique index, see V20260824_03__enum_dictionary.sql):
 * {@code (factory_id, category, code) WHERE deleted_at IS NULL}
 *
 * @since Canvas Phase C (2026-05-22)
 */
@Entity
@Table(name = "enum_dictionary", indexes = {
        @Index(name = "idx_enum_dictionary_factory_category",
               columnList = "factory_id,category"),
        @Index(name = "idx_enum_dictionary_category_code",
               columnList = "category,code")
})
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class EnumDictionary extends BaseEntity {

    /** Sentinel factoryId for global defaults (used when no per-factory row exists). */
    public static final String GLOBAL_FALLBACK_FACTORY_ID = "*";

    /** Default locale (zh-CN). Reserved for future i18n. */
    public static final String DEFAULT_LOCALE = "zh-CN";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /**
     * 工厂 ID; {@value #GLOBAL_FALLBACK_FACTORY_ID} = 全局默认 fallback.
     */
    @Column(name = "factory_id", length = 50, nullable = false)
    private String factoryId;

    /**
     * 枚举大类, e.g. "CANCEL_REASON", "RETURN_REASON". UPPER_SNAKE_CASE 命名.
     * Resolver 按 category 拉取所有 code/label 组成 dropdown.
     */
    @Column(name = "category", length = 50, nullable = false)
    private String category;

    /**
     * 枚举值内部 code (机器可读), e.g. "CUSTOMER_CANCEL", "QUALITY_ISSUE".
     * UPPER_SNAKE_CASE; partial unique (factory_id, category, code).
     */
    @Column(name = "code", length = 50, nullable = false)
    private String code;

    /**
     * 显示文本 (人类可读), e.g. "客户撤单", "质量问题". 前端 el-option label.
     */
    @Column(name = "label", length = 200, nullable = false)
    private String label;

    /**
     * UI 显示顺序 (小到大). 默认 0; 同 displayOrder 时按 code 字母序。
     */
    @Column(name = "display_order", nullable = false)
    @Builder.Default
    private Integer displayOrder = 0;

    /**
     * 是否启用; false → 不出现在 dropdown.
     */
    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    /**
     * 父级 code (用于嵌套层级). NULL = 顶级. e.g. APPROVAL_OPINION 树状审批意见可嵌套.
     * 同 (factory_id, category) 内 parent_code 必须指向同 category 的另一 code (软约束, 不强制 FK).
     */
    @Column(name = "parent_code", length = 50)
    private String parentCode;

    /**
     * 描述 (UI 显示, e.g. tooltip).
     */
    @Column(name = "description", length = 500)
    private String description;

    /**
     * Locale (i18n 预留). 默认 "zh-CN". 同 (factory_id, category, code) 可有多 locale.
     */
    @Column(name = "locale", length = 10, nullable = false)
    @Builder.Default
    private String locale = DEFAULT_LOCALE;

    /**
     * AUD-4 P1 JPA 乐观锁 version. 由 Flyway 默认 0 NOT NULL。
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
