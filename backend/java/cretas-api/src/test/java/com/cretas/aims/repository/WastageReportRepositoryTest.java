package com.cretas.aims.repository;

import com.cretas.aims.entity.inventory.WastageReport;
import com.cretas.aims.repository.inventory.WastageReportRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JPA slice test for {@link WastageReportRepository} (W2 hotfix 回归网, 2026-06-10).
 *
 * <p>背景: findPendingByTrackTypes 初版 @Query 用了嵌套枚举全限定字面量, Hibernate 6
 * HQL 解析失败 → repository bean 启动期校验炸 → 应用起不来。Mockito 单测 mock 掉 repo
 * 完全测不到这一层; 本类用 @DataJpaTest 真启动 repository (顺带校验本接口全部查询),
 * 任何后续 HQL 错误在 CI 即爆, 不再漏到部署健康闸。
 *
 * <p>H2 PG-compat (application-test.properties), 模式同 SemiFinishedInventoryRepositoryTest。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@DisplayName("WastageReportRepository — JPA slice (启动期查询校验 + 轨道过滤)")
class WastageReportRepositoryTest {

    private static final String F1 = "F-WRT-1";
    private static final String F2 = "F-WRT-2";

    @Autowired
    private WastageReportRepository repo;

    private WastageReport report(String factoryId, WastageReport.TrackType track,
                                 WastageReport.Status status, String suffix) {
        WastageReport r = new WastageReport();
        r.setId("WR-" + factoryId + "-" + suffix);
        r.setFactoryId(factoryId);
        r.setReportNo("WRN-" + factoryId + "-" + suffix);
        r.setTrackType(track);
        r.setStatus(status);
        r.setMaterialBatchId("MB-X");
        r.setWastageQty(new BigDecimal("1.5"));
        r.setWastageReason(WastageReport.WastageReason.DAMAGED);
        r.setPhotoUrls("[]");
        r.setSubmittedBy(1L);
        return r;
    }

    @Test
    @DisplayName("repository bean 可创建 = 全部查询通过启动期校验 (hotfix 回归锚点)")
    void repositoryBoots() {
        assertNotNull(repo);
    }

    @Test
    @DisplayName("findByFactoryIdAndStatusAndTrackTypeIn — 按轨道+状态过滤, 工厂隔离")
    void pendingByTrackTypes_filtersTrackStatusAndFactory() {
        repo.save(report(F1, WastageReport.TrackType.WAREHOUSE, WastageReport.Status.PENDING_APPROVAL, "1"));
        repo.save(report(F1, WastageReport.TrackType.FACTORY, WastageReport.Status.PENDING_APPROVAL, "2"));
        repo.save(report(F1, WastageReport.TrackType.WAREHOUSE, WastageReport.Status.APPROVED, "3"));
        repo.save(report(F2, WastageReport.TrackType.WAREHOUSE, WastageReport.Status.PENDING_APPROVAL, "4"));

        var warehouseOnly = repo.findByFactoryIdAndStatusAndTrackTypeInOrderBySubmittedAtAsc(
                F1, WastageReport.Status.PENDING_APPROVAL,
                List.of(WastageReport.TrackType.WAREHOUSE), PageRequest.of(0, 10));
        assertEquals(1, warehouseOnly.getTotalElements());
        assertEquals("WR-" + F1 + "-1", warehouseOnly.getContent().get(0).getId());

        var bothTracks = repo.findByFactoryIdAndStatusAndTrackTypeInOrderBySubmittedAtAsc(
                F1, WastageReport.Status.PENDING_APPROVAL,
                List.of(WastageReport.TrackType.WAREHOUSE, WastageReport.TrackType.FACTORY),
                PageRequest.of(0, 10));
        assertEquals(2, bothTracks.getTotalElements());
    }

    @Test
    @DisplayName("findByFactoryIdWithFilters — 既有 CAST null-hint 查询同样通过校验")
    void existingFilterQueryStillValid() {
        repo.save(report(F1, WastageReport.TrackType.WAREHOUSE, WastageReport.Status.PENDING_APPROVAL, "5"));
        var page = repo.findByFactoryIdWithFilters(F1, null, null, PageRequest.of(0, 10));
        assertTrue(page.getTotalElements() >= 1);
    }
}
