package com.cretas.aims.entity;

import lombok.*;

import jakarta.persistence.*;
import org.hibernate.annotations.Where;

/**
 * AI审计日志实体 - 记录所有AI分析请求用于合规和分析
 *
 * 审计目标：
 * - ISO 27001合规性（3年保留期）
 * - 成本分析和优化
 * - 用户行为追踪
 * - 问题溯源和排查
 *
 * 记录内容：
 * - 所有AI请求（包括follow-up、定时任务、历史报告）
 * - 请求上下文（工厂、用户、批次、问题）
 * - Session追踪（关联Python服务）
 * - 是否消耗配额
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-11-04
 */
@Entity
@Table(name = "ai_audit_logs",
       indexes = {
           @Index(name = "idx_factory_created", columnList = "factory_id,created_at"),
           @Index(name = "idx_user_created", columnList = "user_id,created_at"),
           @Index(name = "idx_batch_id", columnList = "batch_id"),
           @Index(name = "idx_session_id", columnList = "session_id"),
           @Index(name = "idx_question_type", columnList = "question_type")
       })
@Data
@EqualsAndHashCode(callSuper = false)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Where(clause = "deleted_at IS NULL")
public class AIAuditLog extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 工厂ID
     */
    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    /**
     * 用户ID（自动任务时为NULL）
     */
    @Column(name = "user_id")
    private Long userId;

    /**
     * 批次ID（聚合报告时可为空）
     */
    @Column(name = "batch_id", length = 50)
    private String batchId;

    /**
     * 问题类型
     * - default: 默认批次分析
     * - followup: 追问
     * - weekly: 周报告（定时任务）
     * - monthly: 月报告（定时任务）
     * - historical: 历史综合报告
     */
    @Column(name = "question_type", nullable = false, length = 20)
    private String questionType;

    /**
     * 用户问题内容（follow-up时记录）
     */
    @Column(name = "question", columnDefinition = "TEXT")
    private String question;

    /**
     * Python Session ID（用于多轮对话追踪）
     */
    @Column(name = "session_id", length = 100)
    private String sessionId;

    /**
     * 是否消耗配额
     * - followup: true（1次）
     * - historical: true（5次）
     * - default/weekly/monthly: false（不消耗）
     */
    @Builder.Default
    @Column(name = "consumed_quota", nullable = false)
    private Boolean consumedQuota = false;

    /**
     * 消耗的配额数量
     * - followup: 1
     * - historical: 5
     * - 其他: 0
     */
    @Builder.Default
    @Column(name = "quota_cost", nullable = false)
    private Integer quotaCost = 0;

    /**
     * 请求是否成功
     */
    @Column(name = "is_success", nullable = false)
    private Boolean isSuccess;

    /**
     * 错误信息（失败时记录）
     */
    @Column(name = "error_message", length = 500)
    private String errorMessage;

    /**
     * AI响应时间（毫秒）
     */
    @Column(name = "response_time_ms")
    private Long responseTimeMs;

    /**
     * 是否命中缓存
     */
    @Builder.Default
    @Column(name = "cache_hit", nullable = false)
    private Boolean cacheHit = false;

    /**
     * 用户IP地址
     */
    @Column(name = "ip_address", length = 50)
    private String ipAddress;

    /**
     * 用户设备信息
     */
    @Column(name = "user_agent", length = 500)
    private String userAgent;
}
