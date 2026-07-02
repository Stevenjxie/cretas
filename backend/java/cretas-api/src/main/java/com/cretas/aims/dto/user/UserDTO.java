package com.cretas.aims.dto.user;

import com.cretas.aims.entity.enums.Department;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.entity.enums.HireType;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 用户数据传输对象
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "用户信息")
public class UserDTO {

    @Schema(description = "用户ID")
    private Long id;

    @Schema(description = "工厂ID")
    private String factoryId;

    @Schema(description = "用户名")
    private String username;

    @Schema(description = "邮箱")
    private String email;

    @Schema(description = "手机号")
    private String phone;

    @Schema(description = "全名")
    private String fullName;

    @Schema(description = "是否激活")
    private Boolean isActive;

    @Schema(description = "角色代码")
    private FactoryUserRole roleCode;

    @Schema(description = "角色显示名称")
    private String roleDisplayName;

    @Schema(description = "部门")
    private Department department;

    @Schema(description = "部门显示名称")
    private String departmentDisplayName;

    @Schema(description = "职位")
    private String position;

    @Schema(description = "月薪")
    private BigDecimal monthlySalary;

    @Schema(description = "预期工作分钟数")
    private Integer expectedWorkMinutes;

    @Schema(description = "CCR费率")
    private BigDecimal ccrRate;

    @Schema(description = "最后登录时间")
    private LocalDateTime lastLogin;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    // ==================== 调度员模块扩展字段 ====================

    @Schema(description = "工号 (001-999)")
    private String employeeCode;

    @Schema(description = "雇用类型")
    private HireType hireType;

    @Schema(description = "雇用类型显示名称")
    private String hireTypeDisplayName;

    @Schema(description = "合同到期日")
    private LocalDate contractEndDate;

    @Schema(description = "技能等级 JSON")
    private String skillLevels;

    @Schema(description = "小时工资")
    private BigDecimal hourlyRate;

    @Schema(description = "头像URL")
    private String avatarUrl;

    @Schema(description = "入职日期")
    private LocalDate hireDate;

    @Schema(description = "是否临时工")
    private Boolean isTemporaryWorker;

    @Schema(description = "工龄(月)")
    private Integer workMonths;

    @Schema(description = "合同剩余天数")
    private Integer contractRemainingDays;

    @Schema(description = "合同是否即将到期(30天内)")
    private Boolean isContractExpiringSoon;

    @Schema(description = "工厂类型: FACTORY, RESTAURANT, HEADQUARTERS, BRANCH, CENTRAL_KITCHEN")
    private String factoryType;

    @Schema(description = "业务领域: FACTORY / RESTAURANT")
    private String businessDomain;

    /**
     * Normalizing setter — ensures factoryType is always stored as uppercase.
     * Lombok @Data generates a setter but explicit setters take precedence.
     */
    public void setFactoryType(String factoryType) {
        this.factoryType = factoryType != null ? factoryType.toUpperCase() : "FACTORY";
    }

    // ==================== 前端字段别名 ====================

    /**
     * realName 别名（兼容前端）
     * 前端使用 realName，后端使用 fullName
     */
    @JsonProperty("realName")
    @Schema(description = "真实姓名(前端别名)")
    public String getRealName() {
        return fullName;
    }
}
