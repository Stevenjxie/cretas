package com.cretas.aims.entity.config;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;
import java.time.LocalDateTime;
import java.util.Map;

@Entity
@Table(name = "factory_formulas",
    uniqueConstraints = @UniqueConstraint(name = "idx_ff_factory_module_formula", columnNames = {"factory_id", "module_code", "formula_code"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class FactoryFormula {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "factory_id", length = 50) private String factoryId;
    @Column(name = "module_code", length = 64, nullable = false) private String moduleCode;
    @Column(name = "formula_code", length = 64, nullable = false) private String formulaCode;
    @Column(name = "expression", columnDefinition = "TEXT", nullable = false) private String expression;
    @Type(JsonBinaryType.class)
    @Column(name = "variables", columnDefinition = "jsonb") private Map<String, String> variables;
    @Column(name = "result_type", length = 20) @Builder.Default private String resultType = "DECIMAL";
    @Column(name = "precision_val") @Builder.Default private Integer precisionVal = 2;
    @Column(name = "description", columnDefinition = "TEXT") private String description;
    @Column(name = "created_at") private LocalDateTime createdAt;
    @Column(name = "updated_at") private LocalDateTime updatedAt;
    @PrePersist protected void onCreate() { createdAt = updatedAt = LocalDateTime.now(); }
    @PreUpdate protected void onUpdate() { updatedAt = LocalDateTime.now(); }
}
