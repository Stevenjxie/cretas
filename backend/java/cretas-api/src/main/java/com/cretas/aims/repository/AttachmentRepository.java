package com.cretas.aims.repository;

import com.cretas.aims.entity.Attachment;
import com.cretas.aims.entity.Attachment.EntityType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Attachment JPA Repository — C-ATT-1.
 *
 * <p>所有查询自动应用 {@code @Where(deleted_at IS NULL)} (软删除过滤, 见 Attachment).
 *
 * <p>spec: SCHEMA_DESIGN §2.3
 *
 * @author Cretas Team — Track C
 * @since 2026-05-15 (C-ATT-1)
 */
@Repository
public interface AttachmentRepository extends JpaRepository<Attachment, String> {

    /**
     * 某实体的所有附件, 按 uploadedAt 倒序 — 详情页主查询.
     */
    List<Attachment> findByFactoryIdAndEntityTypeAndEntityIdOrderByUploadedAtDesc(
            String factoryId, EntityType entityType, String entityId);

    /** 批量拉取附件 — 报工审批列表合并现场证据 URL, 避免 N+1. */
    List<Attachment> findByFactoryIdAndEntityTypeAndEntityIdInOrderByUploadedAtAsc(
            String factoryId, EntityType entityType, List<String> entityIds);

    /**
     * 批量计数 — 列表页徽章场景. 一次查 N 个 entity 的附件数, 避免 N+1.
     *
     * <p>返回 {@code [entityId, count]} 二维数组; 调用方收成 Map.
     */
    @Query("""
            SELECT a.entityId AS entityId, COUNT(a) AS cnt
            FROM Attachment a
            WHERE a.factoryId = :factoryId
              AND a.entityType = :entityType
              AND a.entityId IN :ids
            GROUP BY a.entityId
            """)
    List<Object[]> countByEntities(
            @Param("factoryId") String factoryId,
            @Param("entityType") EntityType entityType,
            @Param("ids") List<String> ids);

    /**
     * Sprint 6 W3-A — 批量计数按 fileCategory 分组 (inline link counter
     * 3-chip 行内显示: 文件 / 图片 / 合同).
     *
     * <p>返回 {@code [entityId, fileCategory, count]} 三维数组; 调用方按 entityId
     * 收成 {@code Map<String, LinkChipCountsDTO>} 并把 fileCategory 映射到 3-chip 桶:
     * <ul>
     *   <li>{@code DOCUMENT / OTHER}  → fileCount</li>
     *   <li>{@code PHOTO / VIDEO}     → imageCount</li>
     *   <li>{@code CONTRACT}          → contractCount</li>
     *   <li>{@code VOUCHER / SIGNATURE} → 不计入 3-chip (在其他列展示)</li>
     * </ul>
     *
     * <p>避免 N+1: 一次 GROUP BY entity + fileCategory 拿全部桶, 调用方 1 次循环
     * 收成 DTO. 对比逐行 query 减少 ~50N 次 round-trip.
     *
     * @see com.cretas.aims.dto.attachment.LinkChipCountsDTO
     */
    @Query("""
            SELECT a.entityId AS entityId, a.fileCategory AS fileCategory, COUNT(a) AS cnt
            FROM Attachment a
            WHERE a.factoryId = :factoryId
              AND a.entityType = :entityType
              AND a.entityId IN :ids
            GROUP BY a.entityId, a.fileCategory
            """)
    List<Object[]> countByEntitiesGroupedByCategory(
            @Param("factoryId") String factoryId,
            @Param("entityType") EntityType entityType,
            @Param("ids") List<String> ids);

    /**
     * 单条详情 — 强制 factoryId 多租户隔离.
     */
    Optional<Attachment> findByFactoryIdAndId(String factoryId, String id);

    /**
     * 去重查询 — register 时, 同 factory 同 hash 返回已有 Attachment.
     *
     * <p>注: file_hash 可能为 null (老数据 / 客户端未算), 此时不去重.
     */
    Optional<Attachment> findByFactoryIdAndFileHash(String factoryId, String fileHash);

    Optional<Attachment> findByFactoryIdAndEntityTypeAndEntityIdAndFileHash(
            String factoryId, EntityType entityType, String entityId, String fileHash);

    long countByFactoryIdAndEntityTypeAndEntityId(String factoryId, EntityType entityType, String entityId);
}
