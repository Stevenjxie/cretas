package com.cretas.aims.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import org.hibernate.annotations.Where;

/**
 * 用户反馈实体类
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-11-20
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"user", "factory"})
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "user_feedbacks",
       indexes = {
           @Index(name = "idx_feedback_factory", columnList = "factory_id"),
           @Index(name = "idx_feedback_user", columnList = "user_id"),
           @Index(name = "idx_feedback_status", columnList = "status"),
           @Index(name = "idx_feedback_created_at", columnList = "created_at")
       }
)
@Where(clause = "deleted_at IS NULL")
public class UserFeedback extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;

    /**
     * 工厂ID
     */
    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    /**
     * 用户ID
     */
    @Column(name = "user_id")
    private Long userId;

    /**
     * 反馈类型
     */
    @Column(name = "type", nullable = false, length = 20)
    private String type;  // "bug", "feature", "other"

    /**
     * 反馈标题
     */
    @Column(name = "title", nullable = false, length = 200)
    private String title;

    /**
     * 反馈内容
     */
    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    /**
     * 联系方式
     */
    @Column(name = "contact", length = 100)
    private String contact;

    /**
     * 截图URL（JSON数组）
     */
    @Column(name = "screenshots", columnDefinition = "TEXT")
    private String screenshots;

    /**
     * 反馈状态
     */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "pending";  // "pending", "processing", "resolved"

    /**
     * 处理人ID
     */
    @Column(name = "handler_id")
    private Integer handlerId;

    /**
     * 处理备注
     */
    @Column(name = "handler_notes", columnDefinition = "TEXT")
    private String handlerNotes;

    /**
     * 解决时间
     */
    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    // 关联关系
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", referencedColumnName = "id", insertable = false, updatable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "factory_id", referencedColumnName = "id", insertable = false, updatable = false)
    private Factory factory;

    // ===================================================================
    // 前端字段别名（兼容前端）
    // ===================================================================

    /**
     * feedbackId 别名（兼容前端）
     * 前端使用 feedbackId，后端使用 id
     */
    @JsonProperty("feedbackId")
    public String getFeedbackId() {
        return id != null ? String.valueOf(id) : null;
    }
}
