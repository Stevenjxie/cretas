package com.cretas.aims.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
/**
 * 白名单数据传输对象
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WhitelistDTO {
    private Integer id;
    private String factoryId;
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phoneNumber;
    @Size(max = 50, message = "姓名长度不能超过50个字符")
    private String name;
    @Size(max = 50, message = "部门长度不能超过50个字符")
    private String department;
    @Size(max = 50, message = "职位长度不能超过50个字符")
    private String position;
    private String status;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime expiresAt;
    private LocalDateTime lastUsedAt;
    private Integer usageCount;
    @Min(value = 1, message = "最大使用次数必须大于0")
    private Integer maxUsageCount;
    private String role;
    private Boolean accountCreated;
    private Boolean accountActive;
    private Long registeredUserId;
    private String registeredUsername;
    private List<String> permissions;
    private String notes;
    private Long addedBy;
    private String addedByName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ===================================================================
    // 前端字段别名（兼容前端）
    // ===================================================================

    /**
     * realName 别名（兼容前端）
     * 前端使用 realName，后端使用 name
     */
    @JsonProperty("realName")
    public String getRealName() {
        return name;
    }

    /**
     * usedCount 别名（兼容前端）
     * 前端使用 usedCount，后端使用 usageCount
     */
    @JsonProperty("usedCount")
    public Integer getUsedCount() {
        return usageCount;
    }

    /**
     * createdBy 别名（兼容前端）
     * 前端使用 createdBy，后端使用 addedBy
     */
    @JsonProperty("createdBy")
    public Long getCreatedBy() {
        return addedBy;
    }

    // 计算字段
    private Boolean isValid;
    private Boolean isExpiringSoon;
    private Integer remainingUsage;
    private Integer daysUntilExpiry;
    /**
     * 批量添加请求
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BatchAddRequest {
        @NotNull(message = "手机号列表不能为空")
        @Size(min = 1, max = 100, message = "批量添加数量限制为1-100个")
        private List<WhitelistEntry> entries;
        @Future(message = "过期时间必须是将来的时间")
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime expiresAt;
        @Min(value = 1, message = "最大使用次数必须大于0")
        private Integer maxUsageCount;
        private String department;
        @Size(max = 50, message = "角色代码长度不能超过50个字符")
        private String role;
        private String notes;
    }

    /**
     * 单个白名单条目
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WhitelistEntry {
        @NotBlank(message = "手机号不能为空")
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
        private String phoneNumber;

        @Size(max = 50, message = "姓名长度不能超过50个字符")
        private String name;

        @Size(max = 50, message = "职位长度不能超过50个字符")
        private String position;
    }

    /**
     * 单个白名单添加请求（前端表单直连用）
     * 前端 web-admin/src/views/hr/whitelist/index.vue 使用该形状 POST /whitelist
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SingleAddRequest {
        @NotBlank(message = "手机号不能为空")
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
        private String phoneNumber;

        @Size(max = 50, message = "姓名长度不能超过50个字符")
        private String name;

        @Size(max = 50, message = "角色代码长度不能超过50个字符")
        private String role;

        /**
         * 前端使用 departmentId（部门主键），后端 Entity 里是 department 名称字符串，
         * 此处仅作为 BatchAddRequest.department 透传值。
         */
        private String departmentId;

        /**
         * 前端使用 expirationDate = "YYYY-MM-DD"
         */
        private String expirationDate;

        private String notes;

        @Min(value = 1, message = "最大使用次数必须大于0")
        private Integer maxUsageCount;
    }

    /**
     * 更新请求
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateRequest {
        @Size(max = 50, message = "姓名长度不能超过50个字符")
        private String name;

        @Size(max = 50, message = "部门长度不能超过50个字符")
        private String department;

        @Size(max = 50, message = "职位长度不能超过50个字符")
        private String position;

        @Size(max = 50, message = "角色代码长度不能超过50个字符")
        private String role;

        private String status;

        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime expiresAt;

        private String notes;

        private List<String> permissions;
    }

    /**
     * 查询条件
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QueryRequest {
        private String keyword;
        private String status;
        private String department;
        private String role;
        private Boolean showExpired;
        private Boolean showExpiringSoon;

        @Min(0)
        @Builder.Default
        private Integer page = 0;

        @Min(1)
        @Max(100)
        @Builder.Default
        private Integer size = 20;

        @Builder.Default
        private String sortBy = "createdAt";

        @Builder.Default
        private String sortDirection = "DESC";
    }

    /**
     * 统计信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WhitelistStats {
        private Long totalCount;
        private Long activeCount;
        private Long disabledCount;
        private Long expiredCount;
        private Long limitReachedCount;
        private Long todayAddedCount;
        private Long expiringSoonCount;
        private Long activeUsersCount;
        private Map<String, Long> countByDepartment;
        private Map<String, Long> countByRole;
        private List<WhitelistDTO> mostActiveUsers;
        private List<WhitelistDTO> recentlyUsedUsers;
        private List<WhitelistDTO> expiringSoonUsers;
        private Double averageUsage;
        private Long totalUsageCount;
        private LocalDateTime lastUpdated;
    }

    /**
     * 批量操作结果
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BatchResult {
        private Integer successCount;
        private Integer failedCount;
        private List<String> successPhones;
        private List<FailedEntry> failedEntries;

        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class FailedEntry {
            private String phoneNumber;
            private String reason;
        }
    }

    /**
     * 验证响应
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValidationResponse {
        private Boolean isValid;
        private String phone;
        private String name;
        private String role;
        private List<String> permissions;
        private String invalidReason;
        private LocalDateTime expiresAt;
        private Integer remainingUsage;
    }
}
