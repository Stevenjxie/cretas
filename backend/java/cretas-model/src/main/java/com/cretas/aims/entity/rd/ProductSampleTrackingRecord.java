package com.cretas.aims.entity.rd;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;
import org.hibernate.annotations.Where;

/**
 * 研发样品追踪记录 (P1-8, v1 §2.1.3 "追踪记录表").
 *
 * <p>客户会议提及: 研发样品下方需有"追踪记录表", 支持多条记录记录样品开发进度
 * (日期 / 内容 / 附件 / 记录人).
 *
 * <p>之前用 {@code ProductSample.progressNotes TEXT} 存 JSON array 实现, 本 entity
 * 把追踪记录独立为一张表, 便于按时间排序/审计查询/导出/权限控制. 老的 progressNotes
 * 字段保留作为向后兼容 fallback, 新数据应写入此 table.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = "sample")
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "product_sample_tracking_records",
        indexes = {
                @Index(name = "idx_pstr_sample", columnList = "sample_id"),
                @Index(name = "idx_pstr_sample_date", columnList = "sample_id,recorded_at")
        }
)
@Where(clause = "deleted_at IS NULL")
public class ProductSampleTrackingRecord extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) id = UUID.randomUUID().toString();
    }

    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    @Column(name = "sample_id", nullable = false, length = 191)
    private String sampleId;

    /** 记录发生日期 (不一定等于 createdAt, 客户手动填). */
    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;

    /** 记录内容 (长文本, e.g. "第一次打样, 盐度偏高, 需调整配方"). */
    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    /** 附件 URL (图片/PDF, 单个; 多个请用 JSONB). */
    @Column(name = "attachment_url", length = 500)
    private String attachmentUrl;

    /** 记录人 (userId). */
    @Column(name = "recorded_by")
    private Long recordedBy;

    /** 记录人姓名 (冗余, 方便列表展示). */
    @Column(name = "recorded_by_name", length = 100)
    private String recordedByName;
}
