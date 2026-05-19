package com.cretas.aims.repository;

import com.cretas.aims.entity.common.BusinessLink;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 业务单跨域关联 Repository (Sprint 3 Track-F).
 *
 * <p>软删除由 entity 的 @Where(deleted_at IS NULL) 自动过滤.
 */
@Repository
public interface BusinessLinkRepository extends JpaRepository<BusinessLink, String> {

    /** 我 link 了谁 (outbound). */
    List<BusinessLink> findByFactoryIdAndOwnerTypeAndOwnerId(
            String factoryId, String ownerType, String ownerId);

    /** 谁 link 了我 (inbound). */
    List<BusinessLink> findByFactoryIdAndTargetTypeAndTargetId(
            String factoryId, String targetType, String targetId);

    /** 按 linkType 分页 (e.g. 查该工厂所有 "sale" 关联). */
    Page<BusinessLink> findByFactoryIdAndLinkType(
            String factoryId, String linkType, Pageable pageable);

    /** 防重复 link 检查 — unique constraint 等价查询. */
    Optional<BusinessLink> findByOwnerTypeAndOwnerIdAndTargetTypeAndTargetId(
            String ownerType, String ownerId, String targetType, String targetId);

    /** Hard unlink (软删, factoryId 隔离). */
    @Modifying
    @Query("UPDATE BusinessLink b SET b.deletedAt = CURRENT_TIMESTAMP " +
           "WHERE b.factoryId = :factoryId " +
           "AND b.ownerType = :ownerType AND b.ownerId = :ownerId " +
           "AND b.targetType = :targetType AND b.targetId = :targetId " +
           "AND b.deletedAt IS NULL")
    int softDeleteLink(@Param("factoryId") String factoryId,
                       @Param("ownerType") String ownerType,
                       @Param("ownerId") String ownerId,
                       @Param("targetType") String targetType,
                       @Param("targetId") String targetId);

    /**
     * Sprint 5 Track C-2 (inline link counter): batch count of outbound links
     * grouped by ownerId. Mirrors {@code AttachmentRepository.countByEntities}
     * shape — returns {@code [ownerId, count]} rows for caller to collect into
     * a Map. Avoids N+1 list-row queries.
     *
     * <p>Use case: list page shows N sales orders; one query yields all
     * outbound link counts; consumer assembles {@code Map<orderId, count>}
     * and joins to {@code SalesOrderLinkCountsDTO}.
     */
    @Query("""
            SELECT b.ownerId AS ownerId, COUNT(b) AS cnt
            FROM BusinessLink b
            WHERE b.factoryId = :factoryId
              AND b.ownerType = :ownerType
              AND b.ownerId IN :ownerIds
            GROUP BY b.ownerId
            """)
    List<Object[]> countOutboundByOwners(@Param("factoryId") String factoryId,
                                         @Param("ownerType") String ownerType,
                                         @Param("ownerIds") List<String> ownerIds);

    /**
     * Sprint 5 Track C-2 (inline link counter): batch count of inbound links
     * grouped by targetId.
     */
    @Query("""
            SELECT b.targetId AS targetId, COUNT(b) AS cnt
            FROM BusinessLink b
            WHERE b.factoryId = :factoryId
              AND b.targetType = :targetType
              AND b.targetId IN :targetIds
            GROUP BY b.targetId
            """)
    List<Object[]> countInboundByTargets(@Param("factoryId") String factoryId,
                                         @Param("targetType") String targetType,
                                         @Param("targetIds") List<String> targetIds);
}
