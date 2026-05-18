package com.cretas.aims.entity.learning;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;

/**
 * 学习的表达模式实体
 *
 * 存储完整的用户表达（整句），用于精确匹配和语义匹配。
 * 与关键词学习不同，保留完整语义上下文。
 *
 * 用途：
 * - Layer 1 精确表达匹配 (hash 查表, O(1))
 * - Layer 4 语义向量匹配 (embedding cosine similarity)
 *
 * @author Cretas Team
 * @version 1.1.0
 * @since 2026-01-05
 */
@Entity
@Table(name = "ai_learned_expressions", indexes = {
    @Index(name = "idx_ale_factory_intent", columnList = "factory_id, intent_code"),
    @Index(name = "idx_ale_expression_hash", columnList = "expression_hash"),
    @Index(name = "idx_ale_source_type", columnList = "source_type"),
    @Index(name = "idx_ale_is_active", columnList = "is_active")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LearnedExpression {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    /**
     * 工厂ID (null = 全局)
     */
    @Column(name = "factory_id", length = 50)
    private String factoryId;

    /**
     * 意图代码
     */
    @Column(name = "intent_code", nullable = false, length = 100)
    private String intentCode;

    /**
     * 完整表达 (整句)
     */
    @Column(name = "expression", columnDefinition = "TEXT", nullable = false)
    private String expression;

    /**
     * 表达的 SHA256 hash (用于精确匹配)
     */
    @Column(name = "expression_hash", length = 64, nullable = false)
    private String expressionHash;

    /**
     * 来源类型
     */
    @Column(name = "source_type", length = 20, nullable = false)
    @Enumerated(EnumType.STRING)
    private SourceType sourceType;

    /**
     * 置信度 (来源的置信度)
     */
    @Column(name = "confidence", precision = 5, scale = 4)
    @Builder.Default
    private BigDecimal confidence = BigDecimal.ZERO;

    /**
     * 命中次数
     */
    @Column(name = "hit_count")
    @Builder.Default
    private Integer hitCount = 0;

    /**
     * 最后命中时间
     */
    @Column(name = "last_hit_at")
    private LocalDateTime lastHitAt;

    /**
     * 是否已人工确认
     */
    @Column(name = "is_verified")
    @Builder.Default
    private Boolean isVerified = false;

    /**
     * 是否启用
     */
    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    /**
     * 重提议计数 (flywheel v2)
     *
     * 每次学习流 dedup 命中同一表达时递增。
     * 达到阈值 ({@link com.cretas.aims.service.impl.ExpressionLearningServiceImpl#PROMOTE_THRESHOLD})
     * 且通过 shouldLearnExpression 守卫时自动激活 (staged→active)。
     * 新行写入时默认 1。
     */
    @Column(name = "proposal_count")
    @Builder.Default
    private Integer proposalCount = 1;

    /**
     * 最后重提议时间 (flywheel v2)
     */
    @Column(name = "last_proposed_at")
    private LocalDateTime lastProposedAt;

    /**
     * 创建时间
     */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // ========== Embedding 相关字段 (用于 Layer 4 语义匹配) ==========

    /**
     * 768维向量 (768*4=3072 bytes)
     * 使用 GTE-base-zh 模型生成
     */
    // columnDefinition was "MEDIUMBLOB" (MySQL-only) — PG-equivalent is BYTEA.
    // Project is PG-only per CLAUDE.md ("已从 MySQL 迁移完成"); prod table is
    // already BYTEA (created via db/migration-pg-converted/
    // V2026_01_05_30__expression_learning_tables.sql). The MySQL-typed
    // columnDefinition blocked Hibernate ddl-auto=update from CREATEing the
    // table on a fresh PG database (CI), so runtime queries died with
    // 'relation "ai_learned_expressions" does not exist'. ddl-auto=none envs
    // (prod) are unaffected — Hibernate never CREATEs / ALTERs there.
    @Column(name = "embedding_vector", columnDefinition = "BYTEA")
    private byte[] embeddingVector;

    /**
     * 生成 embedding 的模型名称
     */
    @Column(name = "embedding_model", length = 50)
    @Builder.Default
    private String embeddingModel = "gte-base-zh";

    /**
     * embedding 生成时间
     */
    @Column(name = "embedding_created_at")
    private LocalDateTime embeddingCreatedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = java.util.UUID.randomUUID().toString();
        }
        if (expressionHash == null && expression != null) {
            expressionHash = computeHash(expression);
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * 记录一次命中
     */
    public void recordHit() {
        this.hitCount = (this.hitCount == null ? 0 : this.hitCount) + 1;
        this.lastHitAt = LocalDateTime.now();
    }

    /**
     * 计算表达的 SHA256 hash
     */
    public static String computeHash(String expression) {
        if (expression == null || expression.isEmpty()) {
            return "";
        }
        try {
            String normalized = expression.toLowerCase().trim();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            return String.valueOf(expression.hashCode());
        }
    }

    // ========== Embedding 工具方法 ==========

    /**
     * 将 byte[] 转换为 float[] (768维向量)
     *
     * @return 768维浮点数组，如果无 embedding 则返回 null
     */
    public float[] getEmbeddingAsFloatArray() {
        if (embeddingVector == null || embeddingVector.length == 0) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(embeddingVector);
        buffer.order(ByteOrder.LITTLE_ENDIAN); // Python numpy 默认小端
        int dimensions = embeddingVector.length / 4; // 每个 float 4 bytes
        float[] result = new float[dimensions];
        for (int i = 0; i < dimensions; i++) {
            result[i] = buffer.getFloat();
        }
        return result;
    }

    /**
     * 从 float[] 设置 embedding (768维向量)
     *
     * @param embedding 768维浮点数组
     */
    public void setEmbeddingFromFloatArray(float[] embedding) {
        if (embedding == null || embedding.length == 0) {
            this.embeddingVector = null;
            return;
        }
        ByteBuffer buffer = ByteBuffer.allocate(embedding.length * 4);
        buffer.order(ByteOrder.LITTLE_ENDIAN); // 与 Python numpy 保持一致
        for (float v : embedding) {
            buffer.putFloat(v);
        }
        this.embeddingVector = buffer.array();
    }

    /**
     * 检查是否有有效的 embedding
     */
    public boolean hasEmbedding() {
        return embeddingVector != null && embeddingVector.length > 0;
    }

    /**
     * 来源类型枚举
     */
    public enum SourceType {
        /** LLM Fallback 返回 */
        LLM_FALLBACK,
        /** 用户反馈确认 */
        USER_FEEDBACK,
        /** 手动添加 */
        MANUAL,
        /** 语义匹配高置信度 */
        SEMANTIC_HIGH,
        /** 从关键词匹配学习 */
        KEYWORD_MATCH,
        /** LLM Reranking 确认 (中置信度区间) */
        LLM_RERANKING,
        /** 动态工具选择路径自愈学习 */
        DYNAMIC_SELECTION
    }

    /**
     * 构建器辅助方法
     */
    public static LearnedExpression createFromLlm(String factoryId, String intentCode,
                                                   String expression, double confidence) {
        return LearnedExpression.builder()
            .factoryId(factoryId)
            .intentCode(intentCode)
            .expression(expression)
            .expressionHash(computeHash(expression))
            .sourceType(SourceType.LLM_FALLBACK)
            .confidence(BigDecimal.valueOf(confidence))
            .hitCount(0)
            .isVerified(false)
            .isActive(true)
            .build();
    }
}
