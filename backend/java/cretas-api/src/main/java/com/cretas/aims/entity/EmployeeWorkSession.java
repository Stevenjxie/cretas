package com.cretas.aims.entity;

import com.cretas.aims.security.PriceSensitive;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;
import org.hibernate.annotations.BatchSize;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.Where;
/**
 * 员工工作会话实体类
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"factory", "user", "workType", "batchWorkSessions"})
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "employee_work_sessions",
       indexes = {
           @Index(name = "idx_worksession_factory", columnList = "factory_id"),
           @Index(name = "idx_worksession_user", columnList = "user_id"),
           @Index(name = "idx_worksession_start", columnList = "start_time"),
           @Index(name = "idx_worksession_status", columnList = "status")
       }
)
@Where(clause = "deleted_at IS NULL")
public class EmployeeWorkSession extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;
    @Column(name = "factory_id", nullable = false)
    private String factoryId;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(name = "work_type_id", nullable = false)
    private Integer workTypeId;
    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;
    @Column(name = "end_time")
    private LocalDateTime endTime;
    @Column(name = "break_minutes")
    private Integer breakMinutes = 0;
    @Column(name = "actual_work_minutes")
    private Integer actualWorkMinutes;
    @Column(name = "status", nullable = false, length = 20)
    private String status = "active"; // active, completed
    @PriceSensitive
    @Column(name = "hourly_rate", precision = 10, scale = 2)
    private BigDecimal hourlyRate;  // stripped for non-procurement:price:view roles (PR #455 BUG-2 sweep)
    @PriceSensitive
    @Column(name = "labor_cost", precision = 10, scale = 2)
    private BigDecimal laborCost;  // stripped for non-procurement:price:view roles (PR #455 BUG-2 sweep)
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
    // 关联关系 (使用 @JsonIgnore 避免循环引用)
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "factory_id", referencedColumnName = "id", insertable = false, updatable = false)
    private Factory factory;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", referencedColumnName = "id", insertable = false, updatable = false)
    private User user;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_type_id", referencedColumnName = "id", insertable = false, updatable = false)
    private WorkType workType;

    @JsonIgnore
    @BatchSize(size = 20)
    @OneToMany(mappedBy = "workSession", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<BatchWorkSession> batchWorkSessions = new ArrayList<>();
}
