package com.cretas.aims.repository;

import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生产计划列表搜索 — keyword 过滤 JPA slice test (headed-audit 修复, 2026-07-03).
 *
 * <p>Bug: web-admin 生产计划列表搜索框 (搜索计划编号/产品名称) 发出的 {@code keyword} 参数
 * 从未被应用到查询 (service 层完全忽略), 搜索框全无效, 返回结果不随关键词变化。
 *
 * <p>修复: {@link ProductionPlanRepository} 新增 keyword-aware 查询方法
 * (findByFactoryIdAndKeyword / ...AndStatusAndKeyword / ...AndStatusInAndKeyword),
 * 对 planNumber + productType.name 做 LIKE 匹配 (大小写不敏感), keyword 为 null 时不过滤
 * (PostgreSQL CAST null-guard, 见 database-entity-sync.md)。
 *
 * <p>复用 restock-fixtures.sql (F-TEST 工厂, product_types: PT-ZS="猪舌120g", PT-X="X产品")。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Sql(
    scripts = "/test-data/restock-fixtures.sql",
    config = @SqlConfig(errorMode = SqlConfig.ErrorMode.FAIL_ON_ERROR),
    executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD
)
@DisplayName("ProductionPlanRepository — keyword 搜索 (生产计划列表搜索框修复)")
class ProductionPlanKeywordSearchTest {

    @Autowired
    private ProductionPlanRepository repo;

    private static final String FID = "F-TEST";
    private static final Long UID = 1L;

    private ProductionPlan plan(String id, String planNumber, String productTypeId,
                                 ProductionPlanStatus status) {
        ProductionPlan p = new ProductionPlan();
        p.setId(id);
        p.setFactoryId(FID);
        p.setPlanNumber(planNumber);
        p.setProductTypeId(productTypeId);
        p.setPlannedQuantity(new BigDecimal("100"));
        p.setStatus(status);
        p.setCreatedBy(UID);
        return repo.saveAndFlush(p);
    }

    @Test
    @DisplayName("keyword=null → 不过滤, 返回全部")
    void nullKeyword_returnsAll() {
        plan("PP-KW-1", "PLAN-2026-0001", "PT-ZS", ProductionPlanStatus.PENDING);
        plan("PP-KW-2", "PLAN-2026-0002", "PT-X", ProductionPlanStatus.IN_PROGRESS);

        Page<ProductionPlan> page = repo.findByFactoryIdAndKeyword(FID, null, PageRequest.of(0, 20));

        assertEquals(2, page.getTotalElements());
    }

    @Test
    @DisplayName("keyword 匹配计划编号 (部分, 大小写不敏感)")
    void keyword_matchesPlanNumber_caseInsensitive() {
        plan("PP-KW-3", "PLAN-2026-ABCD", "PT-ZS", ProductionPlanStatus.PENDING);
        plan("PP-KW-4", "PLAN-2026-WXYZ", "PT-X", ProductionPlanStatus.PENDING);

        Page<ProductionPlan> page = repo.findByFactoryIdAndKeyword(FID, "abcd", PageRequest.of(0, 20));

        assertEquals(1, page.getTotalElements());
        assertEquals("PP-KW-3", page.getContent().get(0).getId());
    }

    @Test
    @DisplayName("keyword 匹配产品名称 (通过 product_type_id 只读 join)")
    void keyword_matchesProductName() {
        plan("PP-KW-5", "PLAN-2026-A", "PT-ZS", ProductionPlanStatus.PENDING); // 猪舌120g
        plan("PP-KW-6", "PLAN-2026-B", "PT-X", ProductionPlanStatus.PENDING);  // X产品

        Page<ProductionPlan> page = repo.findByFactoryIdAndKeyword(FID, "猪舌", PageRequest.of(0, 20));

        assertEquals(1, page.getTotalElements());
        assertEquals("PP-KW-5", page.getContent().get(0).getId());
    }

    @Test
    @DisplayName("keyword 不命中任何计划编号/产品名称 → 空结果 (证明搜索真的在过滤, 不是 no-op)")
    void keyword_noMatch_returnsEmpty() {
        plan("PP-KW-7", "PLAN-2026-C", "PT-ZS", ProductionPlanStatus.PENDING);

        Page<ProductionPlan> page = repo.findByFactoryIdAndKeyword(FID, "ZZZ-NO-MATCH", PageRequest.of(0, 20));

        assertTrue(page.getContent().isEmpty());
        assertEquals(0, page.getTotalElements());
    }

    @Test
    @DisplayName("keyword + status IN (UNFINISHED) 组合过滤")
    void keyword_combinedWithStatusIn() {
        plan("PP-KW-8", "PLAN-2026-D1", "PT-ZS", ProductionPlanStatus.PENDING);     // 猪舌 + PENDING → 命中
        plan("PP-KW-9", "PLAN-2026-D2", "PT-ZS", ProductionPlanStatus.COMPLETED);   // 猪舌 + COMPLETED → 状态排除
        plan("PP-KW-10", "PLAN-2026-D3", "PT-X", ProductionPlanStatus.PENDING);     // 非猪舌 → keyword 排除

        Page<ProductionPlan> page = repo.findByFactoryIdAndStatusInAndKeyword(
                FID,
                List.of(ProductionPlanStatus.PENDING, ProductionPlanStatus.IN_PROGRESS),
                "猪舌",
                PageRequest.of(0, 20));

        assertEquals(1, page.getTotalElements());
        assertEquals("PP-KW-8", page.getContent().get(0).getId());
    }

    @Test
    @DisplayName("keyword + 单一 status 组合过滤")
    void keyword_combinedWithSingleStatus() {
        plan("PP-KW-11", "PLAN-2026-E1", "PT-X", ProductionPlanStatus.PENDING);
        plan("PP-KW-12", "PLAN-2026-E2", "PT-X", ProductionPlanStatus.IN_PROGRESS);

        Page<ProductionPlan> page = repo.findByFactoryIdAndStatusAndKeyword(
                FID, ProductionPlanStatus.PENDING, "E1", PageRequest.of(0, 20));

        assertEquals(1, page.getTotalElements());
        assertEquals("PP-KW-11", page.getContent().get(0).getId());
    }
}
