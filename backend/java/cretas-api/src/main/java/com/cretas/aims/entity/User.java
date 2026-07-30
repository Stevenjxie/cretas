package com.cretas.aims.entity;

import com.cretas.aims.entity.enums.Department;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.entity.enums.HireType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.Where;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
/**
 * 用户实体类
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"factory", "sessions", "workSessions", "materialConsumptions", "batchWorkSessions"})
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@Entity
@Table(name = "users",
       uniqueConstraints = {
           @UniqueConstraint(columnNames = {"username"})  // 全局唯一：username不能重复
       },
       indexes = {
           @Index(name = "idx_factory_username", columnList = "factory_id, username"),
           @Index(name = "idx_active_users", columnList = "is_active, factory_id"),
           @Index(name = "idx_username", columnList = "username")  // 加速username查询
       }
)
@Where(clause = "deleted_at IS NULL")
public class User extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;
    @Column(name = "factory_id", nullable = false)
    private String factoryId;
    @Column(name = "username", nullable = false)
    private String username;
    @JsonIgnore
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;
    @Column(name = "phone")
    private String phone;

    @Column(name = "email", length = 100)
    private String email;

    @Column(name = "full_name")
    private String fullName;
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;
    @Column(name = "department")
    private String department;
    @Column(name = "position")
    private String position;
    @Column(name = "role_code")
    private String roleCode;

    @Column(name = "level")
    private Integer level;  // 权限级别 (0最高, 99最低)

    @Column(name = "platform_type", length = 20)
    private String platformType = "web,mobile";  // 支持的平台: web, mobile, web,mobile

    @Column(name = "reports_to")
    private Long reportsTo;  // 汇报对象用户ID

    @Column(name = "secondary_reports_to")
    private Long secondaryReportsTo;  // 质检员双重汇报 (质量经理ID)

    @Column(name = "last_login")
    private LocalDateTime lastLogin;
    // 薪资和成本相关字段
    @Column(name = "monthly_salary", precision = 10, scale = 2)
    private BigDecimal monthlySalary;
    @Column(name = "expected_work_minutes")
    private Integer expectedWorkMinutes;
    @Column(name = "ccr_rate", precision = 8, scale = 4)
    private BigDecimal ccrRate;

    // 员工扩展字段 (调度员模块)
    @Column(name = "employee_code", length = 10, unique = true)
    private String employeeCode;  // 工号 001-999

    @Column(name = "hire_type", length = 20)
    @Enumerated(EnumType.STRING)
    private HireType hireType = HireType.FULL_TIME;  // 雇用类型

    @Column(name = "contract_end_date")
    private LocalDate contractEndDate;  // 合同到期日（临时工）

    @Column(name = "skill_levels", columnDefinition = "JSON")
    private String skillLevels;  // 技能等级 JSON: {"切片": 3, "质检": 2}

    @Column(name = "hourly_rate", precision = 10, scale = 2)
    private BigDecimal hourlyRate;  // 小时工资

    @Column(name = "avatar_url", length = 255)
    private String avatarUrl;  // 头像URL

    @Column(name = "hire_date")
    private LocalDate hireDate;  // 入职日期

    // 关联关系
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "factory_id", referencedColumnName = "id", insertable = false, updatable = false)
    private Factory factory;
    @JsonIgnore
    @BatchSize(size = 20)
    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Session> sessions = new ArrayList<>();

    @JsonIgnore
    @BatchSize(size = 20)
    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<EmployeeWorkSession> workSessions = new ArrayList<>();

    @JsonIgnore
    @BatchSize(size = 20)
    @OneToMany(mappedBy = "recorder", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<MaterialConsumption> materialConsumptions = new ArrayList<>();

    @JsonIgnore
    @BatchSize(size = 20)
    @OneToMany(mappedBy = "employee", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<BatchWorkSession> batchWorkSessions = new ArrayList<>();

    @JsonIgnore
    @BatchSize(size = 20)
    @OneToMany(mappedBy = "createdByUser", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<RawMaterialType> createdMaterialTypes = new ArrayList<>();

    @JsonIgnore
    @BatchSize(size = 20)
    @OneToMany(mappedBy = "createdByUser", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<ProductType> createdProductTypes = new ArrayList<>();

    @JsonIgnore
    @BatchSize(size = 20)
    @OneToMany(mappedBy = "createdByUser", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Supplier> createdSuppliers = new ArrayList<>();

    @JsonIgnore
    @BatchSize(size = 20)
    @OneToMany(mappedBy = "createdByUser", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Customer> createdCustomers = new ArrayList<>();

    @JsonIgnore
    @BatchSize(size = 20)
    @OneToMany(mappedBy = "createdByUser", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<ProductionPlan> createdProductionPlans = new ArrayList<>();

    @JsonIgnore
    @BatchSize(size = 20)
    @OneToMany(mappedBy = "createdByUser", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<MaterialBatch> createdMaterialBatches = new ArrayList<>();

    @JsonIgnore
    @BatchSize(size = 20)
    @OneToMany(mappedBy = "adjustedBy", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<MaterialBatchAdjustment> batchAdjustments = new ArrayList<>();
    // 便捷方法
    /**
     * 获取密码（兼容方法）
     */
    @JsonIgnore
    public String getPassword() {
        return this.passwordHash;
    }
    /**
     * 设置密码（兼容方法）
     */
    public void setPassword(String password) {
        this.passwordHash = password;
    }

    /**
     * 获取角色字符串
     */
    public String getRole() {
        return this.roleCode != null ? this.roleCode : this.position;
    }

    /**
     * 获取姓名（兼容方法）
     */
    public String getName() {
        return this.fullName;
    }

    /**
     * 设置姓名（兼容方法）
     */
    public void setName(String name) {
        this.fullName = name;
    }

    /**
     * 获取头像
     */
    public String getAvatar() {
        return this.avatarUrl;
    }

    /**
     * 检查是否为临时性质员工
     */
    public boolean isTemporaryWorker() {
        return this.hireType != null && this.hireType.isTemporary();
    }

    /**
     * 获取工龄（月数）
     */
    public Integer getWorkMonths() {
        if (this.hireDate == null) {
            return null;
        }
        LocalDate now = LocalDate.now();
        return (int) java.time.temporal.ChronoUnit.MONTHS.between(this.hireDate, now);
    }

    /**
     * 检查合同是否即将到期（30天内）
     */
    public boolean isContractExpiringSoon() {
        if (this.contractEndDate == null) {
            return false;
        }
        LocalDate warningDate = LocalDate.now().plusDays(30);
        return this.contractEndDate.isBefore(warningDate) || this.contractEndDate.isEqual(warningDate);
    }

    /**
     * 获取合同剩余天数
     */
    public Integer getContractRemainingDays() {
        if (this.contractEndDate == null) {
            return null;
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), this.contractEndDate);
        return (int) Math.max(0, days);
    }

    /**
     * 获取权限字符串（基于角色）
     */
    public String getPermissions() {
        FactoryUserRole role = getRoleEnum();
        if (role == null) {
            return "";
        }
        return role.getPermissionPrefix() + ":*";
    }

    /**
     * 获取角色枚举
     */
    public FactoryUserRole getRoleEnum() {
        return FactoryUserRole.fromRoleCode(this.roleCode);
    }

    /**
     * 检查是否为管理层
     */
    public boolean isManager() {
        FactoryUserRole role = getRoleEnum();
        return role != null && role.isManager();
    }

    /**
     * 检查是否为一线员工
     */
    public boolean isWorker() {
        FactoryUserRole role = getRoleEnum();
        return role != null && role.isWorker();
    }

    /**
     * 检查是否可以管理目标用户
     */
    public boolean canManage(User target) {
        if (target == null) return false;
        FactoryUserRole myRole = getRoleEnum();
        FactoryUserRole targetRole = target.getRoleEnum();
        if (myRole == null || targetRole == null) return false;
        return myRole.canManage(targetRole);
    }

    /**
     * 获取权限级别 (从角色自动获取)
     */
    public int getEffectiveLevel() {
        if (this.level != null) {
            return this.level;
        }
        FactoryUserRole role = getRoleEnum();
        return role != null ? role.getLevel() : 99;
    }

    /**
     * 检查是否支持Web平台
     */
    public boolean supportsWeb() {
        return this.platformType == null || this.platformType.contains("web");
    }

    /**
     * 检查是否支持Mobile平台
     */
    public boolean supportsMobile() {
        return this.platformType == null || this.platformType.contains("mobile");
    }
}
