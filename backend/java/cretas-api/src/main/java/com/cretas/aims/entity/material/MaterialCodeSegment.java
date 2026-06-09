package com.cretas.aims.entity.material;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Where;

/**
 * SP8: 物料16位分段编码字典.
 *
 * <p>三层层级结构:
 * <ul>
 *   <li>level=1 → 类型段 (3位,  e.g. "001"=原料)</li>
 *   <li>level=2 → 部位/品类段 (累积6位, e.g. "001001"=牛腱)</li>
 *   <li>level=3 → 品名段 (累积10位, e.g. "0010010001"=牛腱A级)</li>
 * </ul>
 *
 * <p>{@code segmentCode} 采用累积编码便于 {@code LIKE '001%'} 前缀搜索和16位最终拼装.
 * 16位最终编码 = segmentCode(10位L3) + String.format("%06d", seq).
 *
 * @since SP8 V20260911_02
 */
@Entity
@Table(name = "material_code_segments", indexes = {
    @Index(name = "idx_mcs_factory_level", columnList = "factory_id, level"),
    @Index(name = "idx_mcs_parent", columnList = "factory_id, parent_code")
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

    /**
     * 层级: 1=类型(3位), 2=部位/品类(6位), 3=品名(10位).
     * DB: CHECK (level IN (1, 2, 3))
     */
    @Column(name = "level", nullable = false)
    private Short level;

    /**
     * 累积段编码:
     * L1=3位("001"), L2=6位("001001"), L3=10位("0010010001").
     * 工厂内唯一 (UNIQUE (factory_id, segment_code)).
     */
    @Column(name = "segment_code", nullable = false, length = 10)
    private String segmentCode;

    /** 展示标签, e.g. "原料" / "牛腱" / "牛腱(A级)". */
    @Column(name = "segment_label", nullable = false, length = 100)
    private String segmentLabel;

    /**
     * 上级累积编码: L2 → 指向 L1 segmentCode; L3 → 指向 L2 segmentCode; L1 为 null.
     */
    @Column(name = "parent_code", length = 10)
    private String parentCode;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;
}
