package com.cretas.aims.entity;

import lombok.*;
import org.hibernate.annotations.Where;

import jakarta.persistence.*;
import java.time.DayOfWeek;
import java.util.HashMap;
import java.util.Map;

/**
 * AI配额规则实体 - 配置化的配额管理
 *
 * 支持按工厂、按角色配置不同的AI配额规则。
 * 替代硬编码的配额值，实现动态配置。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-31
 */
@Entity
@Table(name = "ai_quota_rules",
       uniqueConstraints = {
           @UniqueConstraint(name = "uk_factory_quota_rule", columnNames = {"factory_id"})
       },
       indexes = {
           @Index(name = "idx_factory_id", columnList = "factory_id"),
           @Index(name = "idx_enabled", columnList = "enabled")
       })
@Where(clause = "deleted_at IS NULL")
@Data
@EqualsAndHashCode(callSuper = false)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AIQuotaRule extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 工厂ID（null表示全局默认规则）
     */
    @Column(name = "factory_id", length = 50)
    private String factoryId;

    /**
     * 周配额（默认20次/周）
     */
    @Builder.Default
    @Column(name = "weekly_quota", nullable = false)
    private Integer weeklyQuota = 20;

    /**
     * 角色配额系数
     * JSON格式: {"dispatcher": 2.0, "quality_inspector": 1.5, "worker": 1.0}
     * 最终配额 = weeklyQuota * multiplier
     */
    @Column(name = "role_multipliers", columnDefinition = "json")
    private String roleMultipliers;

    /**
     * 配额重置周期（1=周一, 7=周日）
     */
    @Builder.Default
    @Column(name = "reset_day_of_week", nullable = false)
    private Integer resetDayOfWeek = 1; // 默认周一

    /**
     * 是否启用
     */
    @Builder.Default
    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    /**
     * 规则优先级（数字越大优先级越高）
     */
    @Builder.Default
    @Column(name = "priority", nullable = false)
    private Integer priority = 0;

    /**
     * 规则描述
     */
    @Column(name = "description", length = 500)
    private String description;

    /**
     * 获取角色系数Map
     *
     * @return 角色系数Map，如果未配置则返回空Map
     */
    @Transient
    public Map<String, Double> getRoleMultipliersMap() {
        if (roleMultipliers == null || roleMultipliers.isEmpty()) {
            return new HashMap<>();
        }

        try {
            // 简单的JSON解析（生产环境应使用Jackson）
            Map<String, Double> map = new HashMap<>();
            String json = roleMultipliers.replaceAll("[{}\"]", "");
            for (String pair : json.split(",")) {
                String[] kv = pair.trim().split(":");
                if (kv.length == 2) {
                    map.put(kv[0].trim(), Double.parseDouble(kv[1].trim()));
                }
            }
            return map;
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    /**
     * 设置角色系数Map
     *
     * @param multipliers 角色系数Map
     */
    @Transient
    public void setRoleMultipliersMap(Map<String, Double> multipliers) {
        if (multipliers == null || multipliers.isEmpty()) {
            this.roleMultipliers = null;
            return;
        }

        // 简单的JSON序列化（生产环境应使用Jackson）
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Double> entry : multipliers.entrySet()) {
            if (!first) json.append(",");
            json.append("\"").append(entry.getKey()).append("\":").append(entry.getValue());
            first = false;
        }
        json.append("}");
        this.roleMultipliers = json.toString();
    }

    /**
     * 计算指定角色的实际配额
     *
     * @param role 用户角色
     * @return 该角色的实际配额
     */
    @Transient
    public Integer calculateQuotaForRole(String role) {
        Map<String, Double> multipliers = getRoleMultipliersMap();
        Double multiplier = multipliers.getOrDefault(role, 1.0);
        return (int) Math.ceil(weeklyQuota * multiplier);
    }

    /**
     * 获取配额重置日期枚举
     *
     * @return DayOfWeek枚举
     */
    @Transient
    public DayOfWeek getResetDayOfWeekEnum() {
        return DayOfWeek.of(resetDayOfWeek);
    }
}
