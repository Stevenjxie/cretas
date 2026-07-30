package com.cretas.aims.entity.config;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;
import java.time.LocalDateTime;

@Entity
@Table(name = "factory_default_values")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class FactoryDefaultValue {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "factory_id", length = 50) private String factoryId;
    @Column(name = "module_code", length = 64, nullable = false) private String moduleCode;
    @Column(name = "field_code", length = 64, nullable = false) private String fieldCode;
    @Type(JsonBinaryType.class)
    @Column(name = "default_value", columnDefinition = "jsonb", nullable = false) private Object defaultValue;
    @Column(name = "condition", columnDefinition = "TEXT") private String condition;
    @Column(name = "description", columnDefinition = "TEXT") private String description;
    @Column(name = "created_at") private LocalDateTime createdAt;
    @Column(name = "updated_at") private LocalDateTime updatedAt;
    @PrePersist protected void onCreate() { createdAt = updatedAt = LocalDateTime.now(); }
    @PreUpdate protected void onUpdate() { updatedAt = LocalDateTime.now(); }
}
