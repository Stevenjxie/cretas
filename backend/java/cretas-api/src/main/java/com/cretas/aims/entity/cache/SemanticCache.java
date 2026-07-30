package com.cretas.aims.entity.cache;

import com.cretas.aims.entity.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.hibernate.annotations.Where;

/**
 * 语义缓存实体
 * 存储意图识别结果以加速后续相似查询
 *
 * 工作原理：
 * 1. 首次请求：完整执行意图识别 → 缓存结果
 * 2. 后续请求：
 *    - 精确匹配 (hash): O(1) 查找
 *    - 语义匹配 (embedding): 余弦相似度 > threshold
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-05
 */
@Entity
@Table(name = "semantic_cache", indexes = {
    @Index(name = "idx_factory_hash", columnList = "factory_id, input_hash"),
    @Index(name = "idx_factory_expires", columnList = "factory_id, expires_at"),
    @Index(name = "idx_expires", columnList = "expires_at"),
    @Index(name = "idx_intent_code", columnList = "factory_id, intent_code")
})
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Where(clause = "deleted_at IS NULL")
public class SemanticCache extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 工厂ID
     */
    @Column(name = "factory_id", nullable = false, length = 36)
    private String factoryId;

    /**
     * 规范化后的输入文本
     * 去除多余空格、标点，转小写等
     */
    @Column(name = "normalized_input", nullable = false, length = 500)
    private String normalizedInput;

    /**
     * 原始输入文本
     */
    @Column(name = "original_input", nullable = false, columnDefinition = "TEXT")
    private String originalInput;

    /**
     * 输入文本哈希 (SHA-256)
     * 用于精确匹配的快速查找
     */
    @Column(name = "input_hash", nullable = false, length = 64)
    private String inputHash;

    /**
     * Embedding 向量
     * 768 维 float32 (GTE-base-zh 模型)，约 3KB
     * 存储为二进制 BLOB
     */
    @Column(name = "embedding_vector", columnDefinition = "bytea")
    private byte[] embeddingVector;

    /**
     * 匹配的意图代码
     */
    @Column(name = "intent_code", length = 100)
    private String intentCode;

    /**
     * 意图识别完整结果 (JSON)
     * 包含 IntentMatchResult 的序列化数据
     */
    @Column(name = "intent_result", columnDefinition = "JSON")
    private String intentResult;

    /**
     * 意图执行结果 (JSON)
     * 包含 IntentExecuteResponse 的序列化数据
     */
    @Column(name = "execution_result", columnDefinition = "JSON")
    private String executionResult;

    /**
     * 匹配置信度 (0-1)
     */
    @Column(name = "confidence", precision = 4, scale = 3)
    private BigDecimal confidence;

    /**
     * 缓存命中次数
     */
    @Column(name = "hit_count")
    @lombok.Builder.Default
    private Integer hitCount = 0;

    /**
     * 最后命中时间
     */
    @Column(name = "last_hit_at")
    private LocalDateTime lastHitAt;

    /**
     * 过期时间
     */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /**
     * 记录一次命中
     */
    public void recordHit() {
        this.hitCount = (this.hitCount == null ? 0 : this.hitCount) + 1;
        this.lastHitAt = LocalDateTime.now();
    }

    /**
     * 检查是否已过期
     */
    public boolean isExpired() {
        return this.expiresAt != null && LocalDateTime.now().isAfter(this.expiresAt);
    }

    /**
     * 检查是否有完整的执行结果
     */
    public boolean hasExecutionResult() {
        return this.executionResult != null && !this.executionResult.isEmpty();
    }
}
