package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.inventory.WastageReport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 报损单 Repository (SP7 T3).
 */
@Repository
public interface WastageReportRepository extends JpaRepository<WastageReport, String> {

    Page<WastageReport> findByFactoryIdOrderByCreatedAtDesc(String factoryId, Pageable pageable);

    @Query("SELECT w FROM WastageReport w WHERE w.factoryId = :factoryId " +
           "AND (CAST(:trackType AS string) IS NULL OR w.trackType = :trackType) " +
           "AND (CAST(:status AS string) IS NULL OR w.status = :status) " +
           "ORDER BY w.createdAt DESC")
    Page<WastageReport> findByFactoryIdWithFilters(
            @Param("factoryId") String factoryId,
            @Param("trackType") WastageReport.TrackType trackType,
            @Param("status") WastageReport.Status status,
            Pageable pageable);

    /**
     * 按可见轨道集合查询待审批报损单 (W2 修, 2026-06-10; hotfix 同日)。
     *
     * <p>service 层按角色推导 visibleTracks 后调用（status 恒传 PENDING_APPROVAL）。
     * deletedAt IS NULL 由实体 @Where 处理。
     *
     * <p>⚠️ hotfix 教训: 初版用 @Query + 内部枚举类全限定字面量
     * ({@code com.cretas.aims...WastageReport.Status.PENDING_APPROVAL})，Hibernate 6
     * HQL 解析器不接受嵌套枚举的点路径 → repository bean 启动期校验失败 → 应用起不来
     * （Mockito 单测 mock 掉 repo 测不到; prod 蓝绿健康闸拦住未上线）。
     * 改派生方法名: 零 HQL, 构造即校验。回归网见 WastageReportRepositoryTest (@DataJpaTest)。
     */
    Page<WastageReport> findByFactoryIdAndStatusAndTrackTypeInOrderBySubmittedAtAsc(
            String factoryId,
            WastageReport.Status status,
            List<WastageReport.TrackType> trackTypes,
            Pageable pageable);
}
