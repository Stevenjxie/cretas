package com.cretas.aims.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;

import jakarta.persistence.*;

/**
 * 批次证据照片实体
 * 记录生产批次各阶段的拍照证据（原料、加工中、成品、包装、质检）
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-02-09
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "batch_evidence_photos",
       indexes = {
           @Index(name = "idx_photo_batch", columnList = "batch_id"),
           @Index(name = "idx_photo_factory", columnList = "factory_id"),
           @Index(name = "idx_photo_stage", columnList = "stage")
       }
)
@SQLDelete(sql = "UPDATE batch_evidence_photos SET deleted_at = NOW() WHERE id = ?")
@Where(clause = "deleted_at IS NULL")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchEvidencePhoto extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    /**
     * 关联批次ID
     */
    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    /**
     * 工厂ID
     */
    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    /**
     * 生产阶段: RAW_MATERIAL, IN_PROCESS, FINISHED, PACKAGING, QUALITY_CHECK
     */
    @Column(name = "stage", nullable = false, length = 50)
    private String stage;

    /**
     * 照片URL
     */
    @Column(name = "photo_url", nullable = false, length = 500)
    private String photoUrl;

    /**
     * 缩略图URL
     */
    @Column(name = "thumbnail_url", length = 500)
    private String thumbnailUrl;

    /**
     * 文件大小（字节）
     */
    @Column(name = "file_size")
    private Long fileSize;

    /**
     * 上传人ID
     */
    @Column(name = "uploaded_by", nullable = false)
    private Long uploadedBy;

    /**
     * 备注
     */
    @Column(name = "notes", length = 500)
    private String notes;
}
