package com.cretas.aims.repository.material;

import com.cretas.aims.entity.material.MaterialCodeSegment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;

/**
 * SP8: 物料分段编码字典 Repository.
 */
@Repository
public interface MaterialCodeSegmentRepository extends JpaRepository<MaterialCodeSegment, Long> {

    /** 按工厂和层级查询 (软删除过滤由 @Where 处理). */
    List<MaterialCodeSegment> findByFactoryIdAndLevelOrderBySortOrderAscSegmentCodeAsc(
            String factoryId, Short level);

    /** 按工厂查询所有节点 (树形拼装用). */
    List<MaterialCodeSegment> findByFactoryIdOrderBySortOrderAscSegmentCodeAsc(String factoryId);

    /** 按工厂+编码查找 (唯一). */
    Optional<MaterialCodeSegment> findByFactoryIdAndSegmentCode(String factoryId, String segmentCode);

    /** Serializes 16-digit suffix allocation for a selected L3 node. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM MaterialCodeSegment s WHERE s.factoryId = :factoryId " +
           "AND s.segmentCode = :segmentCode")
    Optional<MaterialCodeSegment> lockByFactoryIdAndSegmentCode(
            @Param("factoryId") String factoryId,
            @Param("segmentCode") String segmentCode);

    /** 检查是否存在 (含软删除). */
    boolean existsByFactoryIdAndSegmentCode(String factoryId, String segmentCode);

    boolean existsByFactoryIdAndLevelAndParentCodeAndNormalizedLabelAndIdNot(
            String factoryId,
            Short level,
            String parentCode,
            String normalizedLabel,
            Long excludeId);

    /** 按工厂+父编码查询子节点. */
    List<MaterialCodeSegment> findByFactoryIdAndParentCodeOrderBySortOrderAscSegmentCodeAsc(
            String factoryId, String parentCode);

    /** 统计该工厂某层级的节点数 (判断是否已配置分段字典). */
    long countByFactoryIdAndLevel(String factoryId, Short level);

    /**
     * 查询该工厂中以 segmentCode 为前缀的所有 L3 品名节点.
     * 用于16位编码生成器检索同前缀已有编码序号.
     */
    @Query("SELECT s.segmentCode FROM MaterialCodeSegment s " +
           "WHERE s.factoryId = :factoryId AND s.level = 3 " +
           "AND s.segmentCode LIKE CONCAT(:prefix, '%')")
    List<String> findL3SegmentCodesByFactoryIdAndPrefix(
            @Param("factoryId") String factoryId,
            @Param("prefix") String prefix);
}
