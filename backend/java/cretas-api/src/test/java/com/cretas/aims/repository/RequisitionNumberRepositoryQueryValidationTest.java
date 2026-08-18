package com.cretas.aims.repository;

import com.cretas.aims.entity.factory.FactoryMaterialRequisition;
import com.cretas.aims.repository.factory.FactoryMaterialRequisitionRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 闸 —— 领料单号的那条 <b>native</b> 查询必须真的能在 PostgreSQL 上跑，
 * 而且必须<b>看得到软删除行</b>。
 *
 * <h2>🔴 为什么这道闸非要连真库不可 (2026-08-18 连栽两次)</h2>
 *
 * <ol>
 *   <li><b>第一次</b>：发号写 {@code count(*) + 1}，而实体上有
 *       {@code @Where(clause = "deleted_at IS NULL")} —— 它静默作用到 JPQL，
 *       于是「发到第几号」被算成「还剩几张」。软删一张就永久错位一个号，
 *       prod 上当天再也建不出领料单（每次都发同一个号，撞唯一约束，
 *       用户看到「数据已存在，请勿重复提交」）。</li>
 *   <li><b>第二次（我自己的修复引入的）</b>：改成 native {@code MAX(...)} 之后，
 *       我的单测<b>把 repository 桩掉了</b>，那条 SQL 一次都没被执行过。
 *       上线后 Postgres 报 {@code could not determine data type of parameter $2}
 *       （{@code CONCAT(?, '%')} 在 native 查询里没有类型上下文），
 *       建单从 409 变成 <b>500</b>。</li>
 * </ol>
 *
 * <p>⚠️ 教训很直接：<b>native 查询不会被 Hibernate 在启动时校验</b>，
 * 桩掉 repository 的单测也不会执行它 —— 只有真的连库跑一次才知道它能不能跑。
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class RequisitionNumberRepositoryQueryValidationTest {

    private static final String FACTORY = "F-REQNO-TEST";
    private static final String PREFIX = "MR20260818";

    @Autowired TestEntityManager entityManager;
    @Autowired FactoryMaterialRequisitionRepository repository;
    @Autowired EntityManager em;

    private FactoryMaterialRequisition persist(String no) {
        FactoryMaterialRequisition r = new FactoryMaterialRequisition();
        r.setId(UUID.randomUUID().toString());
        r.setFactoryId(FACTORY);
        r.setRequisitionNo(no);
        r.setProductionPlanId("plan-" + no);
        r.setStatus(FactoryMaterialRequisition.Status.PENDING);
        return entityManager.persist(r);
    }

    /** ⛔ 用原生 SQL 软删 —— 走实体的话 {@code @Where} 会让后续读取看不见它，测不出我们要测的东西。 */
    private void softDelete(String no) {
        em.createNativeQuery(
                        "UPDATE factory_material_requisitions SET deleted_at = :now "
                                + "WHERE factory_id = :f AND requisition_no = :no")
                .setParameter("now", LocalDateTime.now())
                .setParameter("f", FACTORY)
                .setParameter("no", no)
                .executeUpdate();
    }

    @Test
    @DisplayName("🔴 那条 native 查询能在真 PostgreSQL 上跑, 且软删除行仍然算数")
    void maxRequisitionNoRunsOnRealDbAndSeesSoftDeletedRows() {
        persist(PREFIX + "-0001");
        persist(PREFIX + "-0002");
        FactoryMaterialRequisition third = persist(PREFIX + "-0003");
        assertThat(third).isNotNull();
        entityManager.flush();

        // 阳性对照: 先证明查询本身跑得动、读得到东西 —— 否则下面的断言分不清
        // 「软删除行可见」和「查询根本没返回」。
        String beforeDelete = repository.findMaxRequisitionNo(FACTORY, PREFIX + "%");
        assertThat(beforeDelete)
                .as("native 查询跑不动或读不到数据, 后面全是恒真")
                .isEqualTo(PREFIX + "-0003");

        // 把最大的那张软删 —— 这正是 prod 上发生的事
        softDelete(PREFIX + "-0003");
        entityManager.flush();
        entityManager.clear();

        String afterDelete = repository.findMaxRequisitionNo(FACTORY, PREFIX + "%");
        assertThat(afterDelete)
                .as("软删除之后 max 退回去了 —— 下一个号会重复发, 撞唯一约束")
                .isEqualTo(PREFIX + "-0003");

        // 阴性对照: JPQL 那条 count 会被 @Where 过滤 —— 用它对比, 说明两者确实不同
        long visibleCount = repository.countByFactoryIdAndRequisitionNoPrefix(FACTORY, PREFIX);
        assertThat(visibleCount)
                .as("JPQL count 没有被 @Where 过滤? 那这道闸就没在守东西了")
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("当天还没有单时返回 null (不是抛异常, 也不是空串)")
    void returnsNullWhenNoRowsForTheDay() {
        assertThat(repository.findMaxRequisitionNo(FACTORY, "MR19990101%")).isNull();
    }

    /**
     * 🔴 把「跑在哪个库上」明确打进日志 —— 这道闸有一半价值取决于它。
     *
     * <p>那次 500 的报错 {@code could not determine data type of parameter $2}
     * 是 <b>PostgreSQL 专有</b>的：H2 会照单全收 {@code CONCAT(?, '%')}。
     * 所以如果这个测试跑在 H2 上，它<b>抓不到</b>我当初那个缺陷 ——
     * 「软删除行可见」那条仍然有效，但「native SQL 在 prod 的库上跑得动」那条<b>没有</b>。
     *
     * <p>⛔ 不把这件事打出来的话，绿色会被读成「两件事都守住了」。
     * 本仓形态 E：宁可窄而可信 —— 说清它守不到什么，比假装守住了强。
     */
    @Test
    @DisplayName("🔴 把跑在哪个库上打出来 —— 这道闸有一半价值取决于它")
    void reportWhichDatabaseThisRanOn() throws Exception {
        String product = entityManager.getEntityManager()
                .unwrap(org.hibernate.Session.class)
                .doReturningWork(conn -> conn.getMetaData().getDatabaseProductName());
        boolean postgres = product != null && product.toLowerCase().contains("postgres");
        System.out.println("[REQNO-GATE] database=" + product
                + "  postgres=" + postgres
                + (postgres ? "  ✅ native SQL 语法真的被 prod 同款数据库验过了"
                            : "  ⚠️ 非 PostgreSQL: 本次只验了「软删除行可见」,"
                              + " 没验 native SQL 在 prod 数据库上的语法/类型推断"));
        assertThat(product).as("连数据库产品名都问不出来, 这个测试跑在什么上面?").isNotBlank();
    }
}
