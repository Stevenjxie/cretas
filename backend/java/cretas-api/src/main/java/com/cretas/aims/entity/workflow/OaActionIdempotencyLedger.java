package com.cretas.aims.entity.workflow;

import com.cretas.aims.entity.BaseEntity;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.Where;

import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "oa_action_idempotency_ledger",
        uniqueConstraints = @UniqueConstraint(name = "uk_oa_action_idempotency_scope",
                columnNames = {"factory_id", "instance_id", "idempotency_key"}))
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class OaActionIdempotencyLedger extends BaseEntity {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "factory_id", length = 50, nullable = false)
    private String factoryId;

    @Column(name = "instance_id", length = 36, nullable = false)
    private String instanceId;

    @Column(name = "idempotency_key", length = 128, nullable = false)
    private String idempotencyKey;

    @Column(name = "expected_node_id", length = 50, nullable = false)
    private String expectedNodeId;

    @Column(name = "action", length = 32, nullable = false)
    private String action;

    @Column(name = "operator_id", nullable = false)
    private Long operatorId;

    @Column(name = "operator_role", length = 50)
    private String operatorRole;

    @Column(name = "request_fingerprint", length = 64, nullable = false)
    private String requestFingerprint;

    @Column(name = "completion_state", length = 20, nullable = false)
    private String completionState;

    @Type(JsonBinaryType.class)
    @Column(name = "result_json", columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> resultJson = new HashMap<>();
}
