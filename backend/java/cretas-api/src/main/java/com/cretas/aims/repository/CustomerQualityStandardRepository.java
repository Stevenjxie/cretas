package com.cretas.aims.repository;

import com.cretas.aims.entity.CustomerQualityStandard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 客户质量标准 Repository — Sprint 9 P1.1 Fix 1.
 *
 * <p>专为 {@code CustomerQualityStandardTool} 优先查询路径设计.
 * 命名遵守 Spring Data JPA derived query convention.
 *
 * @author Cretas Team
 * @since 2026-05-21 (Sprint 9 P1.1)
 */
@Repository
public interface CustomerQualityStandardRepository
        extends JpaRepository<CustomerQualityStandard, Long> {

    /**
     * 查工厂 + 客户的所有启用标准 (Tool 主路径).
     * 软删除自动过滤 (BaseEntity @Where).
     */
    List<CustomerQualityStandard> findByFactoryIdAndCustomerIdAndActiveTrue(
            String factoryId, String customerId);

    /**
     * 查工厂 + 客户 + 标准编码 (用于 upsert / 编辑).
     */
    Optional<CustomerQualityStandard> findByFactoryIdAndCustomerIdAndStandardCode(
            String factoryId, String customerId, String standardCode);

    /**
     * 查工厂 + 客户所有标准 (含 inactive, 用于管理 UI).
     */
    List<CustomerQualityStandard> findByFactoryIdAndCustomerId(
            String factoryId, String customerId);
}
