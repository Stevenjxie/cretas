package com.cretas.aims.entity;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import lombok.Data;
import org.hibernate.annotations.Type;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 文员逐道录入幂等记录表。SP-B1 Task 3.
 * 按 (factory_id, plan_id, idempotency_key) 唯一约束防重复录入。
 */
@Data
@Entity
@Table(name = "process_entry_idempotency")
public class ProcessEntryIdempotency {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    /** 生产计划 ID (可空 — 无计划录入时为 null) */
    @Column(name = "plan_id", length = 191)
    private String planId;

    @Column(name = "idempotency_key", nullable = false, length = 255)
    private String idempotencyKey;

    /** 序列化的 ProcessChainEntryResult JSON (JSONB) */
    @Type(JsonBinaryType.class)
    @Column(name = "result_json", columnDefinition = "jsonb")
    private Map<String, Object> resultJson;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
