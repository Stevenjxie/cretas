package com.cretas.aims.mapper;

import com.cretas.aims.dto.user.CreateUserRequest;
import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.entity.User;
import com.cretas.aims.util.BusinessDomainUtils;
import org.springframework.stereotype.Component;
/**
 * 用户实体映射器
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Component
public class UserMapper {
    /**
     * Entity 转 DTO
     */
    public UserDTO toDTO(User user) {
        return toDTO(user, null);
    }

    /**
     * Entity 转 DTO，附带工厂类型
     */
    public UserDTO toDTO(User user, String factoryType) {
        if (user == null) {
            return null;
        }
        return UserDTO.builder()
                .id(user.getId())
                .factoryId(user.getFactoryId())
                .username(user.getUsername())
                .email(user.getEmail())
                .phone(user.getPhone())
                .fullName(user.getFullName())
                .isActive(user.getIsActive())
                .roleCode(user.getRoleEnum())
                .roleDisplayName(user.getPosition()) // 使用position作为显示名称
                .department(null) // department现在是String，不是枚举
                .departmentDisplayName(user.getDepartment()) // 直接使用department字符串
                .position(user.getPosition())
                .monthlySalary(user.getMonthlySalary())
                .expectedWorkMinutes(user.getExpectedWorkMinutes())
                .ccrRate(user.getCcrRate())
                .lastLogin(user.getLastLogin())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                // 调度员模块扩展字段
                .employeeCode(user.getEmployeeCode())
                .hireType(user.getHireType())
                .hireTypeDisplayName(user.getHireType() != null ? user.getHireType().getDisplayName() : null)
                .contractEndDate(user.getContractEndDate())
                .skillLevels(user.getSkillLevels())
                .hourlyRate(user.getHourlyRate())
                .avatarUrl(user.getAvatarUrl())
                .hireDate(user.getHireDate())
                .isTemporaryWorker(user.isTemporaryWorker())
                .workMonths(user.getWorkMonths())
                .contractRemainingDays(user.getContractRemainingDays())
                .isContractExpiringSoon(user.isContractExpiringSoon())
                .factoryType(factoryType != null ? factoryType : "FACTORY")
                .businessDomain(BusinessDomainUtils.resolveDomain(factoryType))
                .build();
    }

    /**
     * CreateRequest 转 Entity
     */
    public User toEntity(CreateUserRequest request, String factoryId) {
        if (request == null) {
            return null;
        }
        User user = new User();
        user.setFactoryId(factoryId);
        user.setUsername(request.getUsername());
        user.setEmail(request.getEmail());
        user.setPhone(request.getPhone());
        user.setFullName(request.getFullName());
        // department现在是String类型，如果request.getDepartment()返回枚举，需要转换
        if (request.getDepartment() != null) {
            user.setDepartment(request.getDepartment().name());
        }
        // 设置 roleCode 和 position
        if (request.getRoleCode() != null) {
            user.setRoleCode(request.getRoleCode().name());
            user.setPosition(request.getRoleCode().name());
        } else if (request.getPosition() != null) {
            user.setRoleCode(request.getPosition());
            user.setPosition(request.getPosition());
        }
        user.setMonthlySalary(request.getMonthlySalary());
        user.setExpectedWorkMinutes(request.getExpectedWorkMinutes());
        user.setCcrRate(request.getCcrRate());
        if (request.getEmployeeCode() != null && !request.getEmployeeCode().isBlank()) {
            user.setEmployeeCode(request.getEmployeeCode());
        }
        user.setIsActive(false); // 默认未激活
        return user;
    }

    /**
     * 更新实体
     */
    public void updateEntity(User user, CreateUserRequest request) {
        if (request.getEmail() != null && !request.getEmail().isBlank()) {
            user.setEmail(request.getEmail());
        }
        if (request.getPhone() != null) {
            user.setPhone(request.getPhone());
        }
        if (request.getFullName() != null) {
            user.setFullName(request.getFullName());
        }
        if (request.getEmployeeCode() != null && !request.getEmployeeCode().isBlank()) {
            user.setEmployeeCode(request.getEmployeeCode());
        }
        // Apr 20 Bug BR-13 fix: roleCode 之前在此 mapper 被 ignore, 导致 /users/{id} PUT
        // 送 roleCode 无效 (用户 "角色变更保存, 更新失败"). 现补 update.
        if (request.getRoleCode() != null) {
            user.setRoleCode(request.getRoleCode().name());
        }
        if (request.getMonthlySalary() != null) {
            user.setMonthlySalary(request.getMonthlySalary());
        }
        if (request.getExpectedWorkMinutes() != null) {
            user.setExpectedWorkMinutes(request.getExpectedWorkMinutes());
        }
        if (request.getCcrRate() != null) {
            user.setCcrRate(request.getCcrRate());
        }
        // W-09 fix (Round 12, qa-prompt v2.4 Rule 17.2 sweep): `position` was
        // silently dropped on PUT /users/{id}. UI edit dialog doesn't expose
        // position today, so user-facing impact is low — but API consumers,
        // AI tools, and other code paths (e.g. UserServiceImpl.updateUserRole
        // line 250 uses setPosition) make this a real silent-drop gap.
        // isActive is intentionally handled in UserServiceImpl.updateUser
        // (Apr 18 Bug #57) and stays there; department is Entity-relation and
        // needs separate update path (not a simple string set).
        if (request.getPosition() != null) {
            user.setPosition(request.getPosition());
        }
    }
}
