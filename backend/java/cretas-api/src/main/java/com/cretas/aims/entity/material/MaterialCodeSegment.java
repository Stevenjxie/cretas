package com.cretas.aims.entity.material;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Where;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Optional three-level material taxonomy.
 *
 * <p>The generated database {@link #id} is the only node identity. Users maintain names and
 * parent relationships; there is no user-visible or length-based classification code.</p>
 */
@Entity
@Table(name = "material_code_segments", indexes = {
    @Index(name = "idx_mcs_factory_level", columnList = "factory_id, level"),
    @Index(name = "idx_mcs_parent", columnList = "factory_id, parent_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true)
@Where(clause = "deleted_at IS NULL")
public class MaterialCodeSegment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    /** 层级: 1=大类, 2=中类, 3=小类. */
    @Column(name = "level", nullable = false)
    private Short level;

    /** 展示名称, e.g. "原料" / "肉类" / "牛腱". */
    @Column(name = "segment_label", nullable = false, length = 100)
    private String segmentLabel;

    /** NFKC/lower-case/whitespace-free identity used for parent-scoped deduplication. */
    @Column(name = "normalized_label", nullable = false, length = 100)
    private String normalizedLabel;

    /** 上级节点 ID；一级分类为 null。 */
    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @PrePersist
    @PreUpdate
    void normalizeIdentity() {
        if (segmentLabel != null) {
            segmentLabel = segmentLabel.trim();
            normalizedLabel = Normalizer.normalize(segmentLabel, Normalizer.Form.NFKC)
                    .trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        }
    }
}
