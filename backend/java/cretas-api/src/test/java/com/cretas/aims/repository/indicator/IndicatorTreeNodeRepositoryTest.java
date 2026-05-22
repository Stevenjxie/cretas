package com.cretas.aims.repository.indicator;

import com.cretas.aims.entity.indicator.IndicatorTreeNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 1 Sprint 1 Day 3 — JPA slice test for {@link IndicatorTreeNodeRepository}.
 *
 * <p>Verifies:
 * <ol>
 *   <li>findByParentIndicatorIdAndFactoryIdAndDeletedAtIsNull — direct children</li>
 *   <li>findRootsByFactoryId — parent_id IS NULL filter</li>
 *   <li>findSubtreeByPath — materialized_path LIKE prefix subtree query</li>
 * </ol>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository.indicator")
@DisplayName("IndicatorTreeNodeRepository — JPA slice (H2 PG-compat)")
class IndicatorTreeNodeRepositoryTest {

    private static final String F1 = "F-ITN-1";
    private static final String F2 = "F-ITN-2";

    @Autowired private IndicatorTreeNodeRepository repo;

    @Test
    @DisplayName("findRootsByFactoryId — only parent_id IS NULL returned, ordered by displayOrder")
    void findRoots() {
        // F1: 2 roots + 1 child
        IndicatorTreeNode root1 = repo.saveAndFlush(
                newNode(F1, "ind-yield-rate", null, 0, "ind-yield-rate", 1));
        IndicatorTreeNode root2 = repo.saveAndFlush(
                newNode(F1, "ind-quality", null, 0, "ind-quality", 2));
        IndicatorTreeNode child = repo.saveAndFlush(
                newNode(F1, "ind-raw-qualify", "ind-yield-rate", 1,
                        "ind-yield-rate/ind-raw-qualify", 1));
        // F2: separate root (must be isolated)
        repo.saveAndFlush(newNode(F2, "ind-other", null, 0, "ind-other", 1));

        List<IndicatorTreeNode> roots = repo.findRootsByFactoryId(F1);

        assertEquals(2, roots.size(), "only 2 roots (F1, parent_id NULL)");
        assertEquals(root1.getId(), roots.get(0).getId(), "displayOrder=1 first");
        assertEquals(root2.getId(), roots.get(1).getId(), "displayOrder=2 second");
        // Sanity: child not included
        assertTrue(roots.stream().noneMatch(n -> n.getId().equals(child.getId())),
                "child with parent_id NOT NULL must be excluded");
    }

    @Test
    @DisplayName("findByParentIndicatorIdAndFactoryId — direct children only, factory-isolated")
    void findChildrenByParent() {
        // F1: 3 children of same parent + 1 grandchild + 1 different parent
        repo.saveAndFlush(newNode(F1, "child-A", "parent-1", 1, "p/child-A", 1));
        repo.saveAndFlush(newNode(F1, "child-B", "parent-1", 1, "p/child-B", 2));
        repo.saveAndFlush(newNode(F1, "child-C", "parent-1", 1, "p/child-C", 3));
        repo.saveAndFlush(newNode(F1, "grandchild", "child-A", 2, "p/child-A/grandchild", 1));
        repo.saveAndFlush(newNode(F1, "other-child", "parent-2", 1, "other/other-child", 1));
        // F2: same parent_id — must be excluded
        repo.saveAndFlush(newNode(F2, "f2-child", "parent-1", 1, "p/f2-child", 1));

        List<IndicatorTreeNode> children = repo.findByParentIndicatorIdAndFactoryIdAndDeletedAtIsNull(
                "parent-1", F1);

        assertEquals(3, children.size(), "only direct children of parent-1 in F1");
        assertEquals("child-A", children.get(0).getIndicatorId());
        assertEquals("child-B", children.get(1).getIndicatorId());
        assertEquals("child-C", children.get(2).getIndicatorId());
    }

    @Test
    @DisplayName("findSubtreeByPath — materialized_path LIKE prefix returns descendants")
    void findSubtreeByPath() {
        // Build a tree:
        //   yield (depth 0, path="yield")
        //   ├─ raw-qualify (depth 1, path="yield/raw-qualify")
        //   │  └─ supplier-pass (depth 2, path="yield/raw-qualify/supplier-pass")
        //   └─ production-yield (depth 1, path="yield/production-yield")
        //   other-root (depth 0, path="other-root")  — must be excluded
        repo.saveAndFlush(newNode(F1, "yield", null, 0, "yield", 1));
        repo.saveAndFlush(newNode(F1, "raw-qualify", "yield", 1,
                "yield/raw-qualify", 1));
        repo.saveAndFlush(newNode(F1, "supplier-pass", "raw-qualify", 2,
                "yield/raw-qualify/supplier-pass", 1));
        repo.saveAndFlush(newNode(F1, "production-yield", "yield", 1,
                "yield/production-yield", 2));
        repo.saveAndFlush(newNode(F1, "other-root", null, 0, "other-root", 99));

        List<IndicatorTreeNode> subtree = repo.findSubtreeByPath(F1, "yield");

        // Subtree under "yield/" should be 3 nodes (raw-qualify + supplier-pass +
        // production-yield); "yield" itself is the root and won't match "yield/%".
        assertEquals(3, subtree.size());
        // depth ASC ordering
        assertEquals(1, subtree.get(0).getDepth().intValue());
        assertEquals(1, subtree.get(1).getDepth().intValue());
        assertEquals(2, subtree.get(2).getDepth().intValue());
    }

    // ============================================================
    // Helper
    // ============================================================

    private IndicatorTreeNode newNode(String factoryId, String indicatorId, String parentId,
                                       int depth, String path, int displayOrder) {
        IndicatorTreeNode n = new IndicatorTreeNode();
        n.setFactoryId(factoryId);
        n.setIndicatorId(indicatorId);
        n.setParentId(parentId);
        n.setDepth(depth);
        n.setMaterializedPath(path);
        n.setDisplayOrder(displayOrder);
        n.setWeight(BigDecimal.ONE);
        return n;
    }
}
