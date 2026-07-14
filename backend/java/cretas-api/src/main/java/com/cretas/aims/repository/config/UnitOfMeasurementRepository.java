package com.cretas.aims.repository.config;

import com.cretas.aims.entity.config.UnitOfMeasurement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 计量单位配置 Repository
 *
 * 支持工厂级覆盖的单位查询:
 * - 优先返回工厂级配置
 * - 如果不存在则返回全局配置 (factoryId = '*')
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-31
 */
@Repository
public interface UnitOfMeasurementRepository extends JpaRepository<UnitOfMeasurement, String> {

    /**
     * 根据工厂ID和单位代码查询单位配置
     * 优先返回工厂级配置
     */
    @Query("SELECT u FROM UnitOfMeasurement u " +
           "WHERE u.unitCode = :unitCode " +
           "AND u.factoryId IN (:factoryId, '*') " +
           "AND u.isActive = true " +
           "AND u.deletedAt IS NULL " +
           "ORDER BY CASE WHEN u.factoryId = :factoryId THEN 1 ELSE 2 END")
    List<UnitOfMeasurement> findByFactoryIdAndUnitCode(
            @Param("factoryId") String factoryId,
            @Param("unitCode") String unitCode);

    /**
     * 根据工厂ID和分类查询所有单位
     */
    @Query("SELECT u FROM UnitOfMeasurement u " +
           "WHERE u.category = :category " +
           "AND u.factoryId IN (:factoryId, '*') " +
           "AND u.isActive = true " +
           "AND u.deletedAt IS NULL " +
           "ORDER BY u.sortOrder ASC, " +
           "CASE WHEN u.factoryId = :factoryId THEN 1 ELSE 2 END")
    List<UnitOfMeasurement> findByFactoryIdAndCategory(
            @Param("factoryId") String factoryId,
            @Param("category") String category);

    /**
     * 查询工厂可用的所有单位
     */
    @Query("SELECT u FROM UnitOfMeasurement u " +
           "WHERE u.factoryId IN (:factoryId, '*') " +
           "AND u.isActive = true " +
           "AND u.deletedAt IS NULL " +
           "ORDER BY CASE WHEN u.factoryId = :factoryId THEN 1 ELSE 2 END, " +
           "u.category ASC, u.sortOrder ASC")
    List<UnitOfMeasurement> findAllByFactoryId(@Param("factoryId") String factoryId);

    /**
     * 查询所有全局单位配置
     */
    List<UnitOfMeasurement> findByFactoryIdAndIsActiveTrueAndDeletedAtIsNullOrderByCategoryAscSortOrderAsc(
            String factoryId);

    /**
     * 查询所有可用的单位分类
     */
    @Query("SELECT DISTINCT u.category FROM UnitOfMeasurement u " +
           "WHERE u.isActive = true AND u.deletedAt IS NULL " +
           "ORDER BY u.category")
    List<String> findAllCategories();

    /**
     * 根据分类查询基础单位
     */
    @Query("SELECT u FROM UnitOfMeasurement u " +
           "WHERE u.category = :category " +
           "AND u.isBaseUnit = true " +
           "AND u.factoryId IN (:factoryId, '*') " +
           "AND u.isActive = true " +
           "AND u.deletedAt IS NULL " +
           "ORDER BY CASE WHEN u.factoryId = :factoryId THEN 1 ELSE 2 END")
    List<UnitOfMeasurement> findBaseUnitByFactoryIdAndCategory(
            @Param("factoryId") String factoryId,
            @Param("category") String category);

    /**
     * 检查单位是否存在
     */
    boolean existsByFactoryIdAndUnitCode(String factoryId, String unitCode);

    /**
     * 精确查询单个单位 (用于更新/删除)
     */
    Optional<UnitOfMeasurement> findByFactoryIdAndUnitCodeAndDeletedAtIsNull(
            String factoryId, String unitCode);

    /**
     * 查询同分类下的所有单位 (用于单位换算)
     */
    @Query("SELECT u FROM UnitOfMeasurement u " +
           "WHERE u.baseUnit = :baseUnit " +
           "AND u.factoryId IN (:factoryId, '*') " +
           "AND u.isActive = true " +
           "AND u.deletedAt IS NULL " +
           "ORDER BY u.conversionFactor ASC")
    List<UnitOfMeasurement> findByFactoryIdAndBaseUnit(
            @Param("factoryId") String factoryId,
            @Param("baseUnit") String baseUnit);

    /**
     * 统计工厂的单位配置数量
     */
    @Query("SELECT COUNT(u) FROM UnitOfMeasurement u " +
           "WHERE u.factoryId = :factoryId " +
           "AND u.isActive = true " +
           "AND u.deletedAt IS NULL")
    Long countByFactoryId(@Param("factoryId") String factoryId);

    /**
     * 查询系统内置单位
     */
    List<UnitOfMeasurement> findByIsSystemTrueAndIsActiveTrueAndDeletedAtIsNullOrderByCategoryAscSortOrderAsc();
}
