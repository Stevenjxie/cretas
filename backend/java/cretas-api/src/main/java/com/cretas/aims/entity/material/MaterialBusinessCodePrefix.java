package com.cretas.aims.entity.material;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Where;

/**
 * Backend-controlled mapping from a stable classification segment to a human-readable code prefix.
 * The prefix is deliberately independent from the mutable classification label.
 */
@Entity
@Table(name = "material_business_code_prefixes",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_mbc_prefix_factory_segment",
                        columnNames = {"factory_id", "classification_segment_code"}),
                @UniqueConstraint(name = "uk_mbc_prefix_factory_code",
                        columnNames = {"factory_id", "code_prefix"})
        },
        indexes = {
                @Index(name = "idx_mbc_prefix_factory_active", columnList = "factory_id, is_active")
        })
@Where(clause = "deleted_at IS NULL")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class MaterialBusinessCodePrefix extends BaseEntity {

    public static final int DEFAULT_SEQUENCE_LENGTH = 6;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    /** Numeric L1/L2/L3 cumulative segment code from material_code_segments. */
    @Pattern(regexp = "^(?:[0-9]{3}|[0-9]{6}|[0-9]{10})$",
            message = "分类段编码必须为3、6或10位数字")
    @Column(name = "classification_segment_code", nullable = false, length = 10)
    private String classificationSegmentCode;

    /** Fixed ASCII prefix, for example RMSEA or PKBOX. No separator is allowed. */
    @Pattern(regexp = "^[A-Z0-9]{2,8}$", message = "业务编码前缀只能包含2至8位大写字母和数字")
    @Column(name = "code_prefix", nullable = false, length = 8)
    private String codePrefix;

    @Min(value = DEFAULT_SEQUENCE_LENGTH, message = "业务编码序列固定为6位")
    @Max(value = DEFAULT_SEQUENCE_LENGTH, message = "业务编码序列固定为6位")
    @Column(name = "sequence_length", nullable = false)
    @Builder.Default
    private Integer sequenceLength = DEFAULT_SEQUENCE_LENGTH;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;
}
