package com.cretas.aims.repository;

import com.cretas.aims.entity.ProductProcessWorkflowRevision;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 2026-08-08 真机事故的回归闸:软删的 revision 仍然占着 revision_number。
 *
 * <p>唯一约束 {@code uk_ppwr_workflow_revision UNIQUE (workflow_id, revision_number)} 建在
 * **物理表**上,不排除软删行;而实体上有 {@code @Where(deleted_at IS NULL)},于是任何 JPQL
 * 都看不见软删行。取号一旦走 JPQL 的 {@code max(revisionNumber)},就会重新发出一个**已被软删行
 * 占着**的号 —— 插入必撞唯一约束。
 *
 * <p>后果不是偶发而是永久:某条 workflow 的 4 号被软删后,它的画布**从此再也保存不了草稿**
 * (真机 F006/拓扑成品E 必现,两次重试两个不同追踪码,都是 500)。更糟的是 createRevision 的
 * catch 分支里原本还要再发一次 JPA 查询去找"竞态赢家",而 flush 失败后的 session 一查就抛
 * {@code AssertionFailure: null id ...},把可恢复的冲突翻译成给用户看的「系统处理异常」。
 *
 * <p>判据:**取号/判重必须跟约束所在的那张表同源**。约束不排除软删,取号就不能排除软删。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class WorkflowRevisionNumberSoftDeleteTest {

    private static final Long WORKFLOW_ID = 987654L;

    @Autowired ProductProcessWorkflowRevisionRepository revisionRepository;
    @Autowired ProductProcessWorkflowRepository workflowRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void maxRevisionNumberCountsSoftDeletedRows() {
        revisionRepository.saveAndFlush(revision(1, "hash-1"));
        revisionRepository.saveAndFlush(revision(2, "hash-2"));
        ProductProcessWorkflowRevision third = revisionRepository.saveAndFlush(revision(3, "hash-3"));

        // 软删 3 号 —— 只动 deleted_at, 行还在, 唯一约束仍然认它占着 3 号。
        jdbcTemplate.update(
                "update product_process_workflow_revisions set deleted_at = CURRENT_TIMESTAMP where id = ?",
                third.getId());

        // JPQL 版本在这里返回 2(看不见被软删的 3 号) ⇒ 取号 3 ⇒ 插入必撞 uk_ppwr_workflow_revision。
        assertThat(revisionRepository.findMaxRevisionNumber(WORKFLOW_ID))
                .as("软删行仍占着 revision_number, 取号必须把它数进来, 否则下一次保存必撞唯一约束")
                .isEqualTo(3);
    }

    @Test
    void nextNumberAfterSoftDeleteCanActuallyBeInserted() {
        revisionRepository.saveAndFlush(revision(1, "ins-hash-1"));
        ProductProcessWorkflowRevision second = revisionRepository.saveAndFlush(revision(2, "ins-hash-2"));
        jdbcTemplate.update(
                "update product_process_workflow_revisions set deleted_at = CURRENT_TIMESTAMP where id = ?",
                second.getId());

        // 端到端形态:按取号结果真的插一行。取号错了这里就抛 DataIntegrityViolationException,
        // 正是真机上那个 500 的来源 —— 断言"插得进去"比断言一个数字更贴近用户遭遇的故障。
        int next = revisionRepository.findMaxRevisionNumber(WORKFLOW_ID) + 1;
        revisionRepository.saveAndFlush(revision(next, "ins-hash-next"));

        assertThat(next).isEqualTo(3);
    }

    /**
     * 同因兄弟:workflow 的 definitionVersion 取号。唯一索引
     * {@code uk_product_process_workflow_version (factory_id, product_type_id, status, definition_version)}
     * 同样不排除软删行,所以取号同样不能排除。
     */
    @Test
    void maxDefinitionVersionCountsSoftDeletedWorkflows() {
        Long kept = insertWorkflow(1, null);
        insertWorkflow(2, "CURRENT_TIMESTAMP");
        assertThat(kept).isNotNull();

        assertThat(workflowRepository.findMaxDefinitionVersion("F-SOFTDEL", "FG-SOFTDEL"))
                .as("软删的 workflow 仍占着 definition_version, 取号必须数进来")
                .contains(2);
    }

    private Long insertWorkflow(int definitionVersion, String deletedAtExpr) {
        jdbcTemplate.update("""
                insert into product_process_workflows
                  (factory_id, product_type_id, schema_version, status, definition_version,
                   nodes_json, edges_json, viewport_json, lock_version, unit_review_required,
                   created_at, updated_at, deleted_at)
                values (?, ?, 1, 'DRAFT', ?, '[]', '[]', '{"x":0,"y":0,"zoom":1}', 0, false,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, %s)
                """.formatted(deletedAtExpr == null ? "null" : deletedAtExpr),
                "F-SOFTDEL", "FG-SOFTDEL", definitionVersion);
        return jdbcTemplate.queryForObject(
                "select id from product_process_workflows where factory_id = ? and definition_version = ?",
                Long.class, "F-SOFTDEL", definitionVersion);
    }

    private ProductProcessWorkflowRevision revision(int number, String hash) {
        ProductProcessWorkflowRevision revision = new ProductProcessWorkflowRevision();
        revision.setFactoryId("F-SOFTDEL");
        revision.setProductTypeId("FG-SOFTDEL");
        revision.setWorkflowId(WORKFLOW_ID);
        revision.setDefinitionVersion(1);
        revision.setRevisionNumber(number);
        revision.setRevisionHash(hash);
        revision.setStatus(ProductProcessWorkflowRevision.Status.DRAFT);
        return revision;
    }
}
