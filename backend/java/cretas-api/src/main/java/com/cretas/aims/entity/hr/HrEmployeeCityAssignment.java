package com.cretas.aims.entity.hr;

import com.cretas.aims.entity.BaseEntity;
import lombok.*;
import org.hibernate.annotations.Where;

import jakarta.persistence.*;
import java.time.LocalDate;

/**
 * 员工 → 城市 分配 (#863 follow-up - 城市差异化社保).
 *
 * <p>背景: User 实体被全系统重度引用 (100+ FK), 不希望加 cityCode 字段触发广泛 schema churn.
 * 本表作为 side-car 维护员工 → 工作城市的关联, 用于 SalaryItemServiceImpl 查询时
 * 根据 user 取 cityCode → 应用 {@link HrCityInsuranceOverride}.
 *
 * <p>设计要点:
 * <ul>
 *   <li>(factory, user) 一对一 — DB unique index. 员工不会同时在两个城市工作.</li>
 *   <li>无 city → 该员工用工厂默认 (无 override) 配置.</li>
 *   <li>支持 effectiveFrom 历史 (将来扩展: 员工调动). MVP 不严格按 yearMonth 取最新.</li>
 * </ul>
 *
 * <p>R4 防呆: (factory, userId) DB unique partial index 保证唯一 ACTIVE 行.
 *
 * @author Cretas Team — #863 城市差异化社保 follow-up
 * @since 2026-05-18
 */
@Entity
@Table(name = "hr_employee_city_assignments",
       indexes = {
           @Index(name = "idx_emp_city_factory_user",
                  columnList = "factory_id,user_id,status"),
           @Index(name = "idx_emp_city_factory_city",
                  columnList = "factory_id,city_code,status")
       })
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HrEmployeeCityAssignment extends BaseEntity {

    @Id
    @GeneratedValue(generator = "uuid2")
    @org.hibernate.annotations.GenericGenerator(name = "uuid2", strategy = "uuid2")
    @Column(length = 36)
    private String id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 城市代号 (与 HrCityInsuranceOverride.cityCode 对齐). */
    @Column(name = "city_code", nullable = false, length = 50)
    private String cityCode;

    /** 该 assignment 生效起始月 (含). */
    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    /** ACTIVE / ARCHIVED. 切换城市时旧 → ARCHIVED, 新 → ACTIVE. */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "ACTIVE";

    @Column(name = "remark", length = 500)
    private String remark;

    @Column(name = "created_by")
    private Long createdBy;
}
