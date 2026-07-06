package com.cretas.aims.entity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 🔒🔒 DB CHECK 约束 ck_material_batch_no_overconsume 语义单测。
 *
 * <p>验证 Flyway {@code V20261027_42} 里的 CHECK 表达式
 * {@code COALESCE(used_quantity,0)+COALESCE(reserved_quantity,0) <= receipt_quantity}
 * 在数据库层真正拦截超扣, 且不误伤合法边界 —— 这是任何应用层写入点漏接 / 未来回归的最硬兜底
 * (mock repo 照不到 DB 约束, 见 .claude/rules feedback_mock_repo_misses_db_constraints)。
 *
 * <p>用独立 in-memory H2 (纯 JDBC, 无 Spring / 无 entity FK 图) 建一张仅含相关列 + 同一 CHECK 的
 * 镜像表, 直接以 SQL 断言 INSERT/UPDATE 行为。CHECK 表达式是标准 SQL, H2 与 Postgres 同义,
 * 故这里对约束<b>子句正确性</b>的验证等价于 prod PG (prod 实际约束由迁移在真 PG 上加, 另经 dry-run 核实)。
 */
class MaterialBatchOverConsumeCheckConstraintTest {

    private Connection conn;

    @BeforeEach
    void setUp() throws SQLException {
        conn = DriverManager.getConnection(
                "jdbc:h2:mem:mb_overconsume_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE material_batches (" +
                    "  id VARCHAR(191) PRIMARY KEY," +
                    "  receipt_quantity DECIMAL(10,2) NOT NULL," +
                    "  used_quantity DECIMAL(10,2) NOT NULL," +
                    "  reserved_quantity DECIMAL(10,2) NOT NULL," +
                    "  CONSTRAINT ck_material_batch_no_overconsume" +
                    "    CHECK (COALESCE(used_quantity,0) + COALESCE(reserved_quantity,0) <= receipt_quantity)" +
                    ")");
        }
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (conn != null) {
            try (Statement st = conn.createStatement()) {
                st.execute("DROP ALL OBJECTS");
            }
            conn.close();
        }
    }

    private int insert(String id, String receipt, String used, String reserved) throws SQLException {
        try (Statement st = conn.createStatement()) {
            return st.executeUpdate(String.format(
                    "INSERT INTO material_batches VALUES ('%s', %s, %s, %s)", id, receipt, used, reserved));
        }
    }

    // ── DB 层拦截真超扣 (复现 3 个 F006 违规值) ──

    @Test
    void insertOverConsumed_singleExceedsReceipt_rejectedByDb() {
        // 13.01 收货, 20 已用 → CHECK 拒绝落库 (若无约束, 这正是 3 个 F006 幽灵行的产生方式)。
        Throwable t = catchThrowable(() -> insert("b1", "13.01", "20.00", "0"));
        assertThat(t).isInstanceOf(SQLException.class);
        assertThat(t.getMessage()).contains("CK_MATERIAL_BATCH_NO_OVERCONSUME".toUpperCase());
    }

    @Test
    void insertOverConsumed_usedPlusReserved_rejectedByDb() throws SQLException {
        assertThatThrownBy(() -> insert("b2", "10", "7", "5")) // 12 > 10
                .isInstanceOf(SQLException.class);
    }

    @Test
    void updateIntoOverConsumed_rejectedByDb() throws SQLException {
        insert("b3", "10", "3", "0"); // 合法起点
        try (Statement st = conn.createStatement()) {
            assertThatThrownBy(() ->
                    st.executeUpdate("UPDATE material_batches SET used_quantity = 11 WHERE id = 'b3'"))
                    .isInstanceOf(SQLException.class);
        }
    }

    // ── 合法边界放行 (误伤-proof: ≤ 不是 <) ──

    @Test
    void insertFullyConsumed_usedEqualsReceipt_accepted() throws SQLException {
        assertThat(insert("b4", "13.01", "13.01", "0")).isEqualTo(1);
    }

    @Test
    void insertUsedPlusReservedEqualsReceipt_accepted() throws SQLException {
        assertThat(insert("b5", "10", "6", "4")).isEqualTo(1);
    }

    @Test
    void insertPartialConsumption_accepted() throws SQLException {
        assertThat(insert("b6", "100", "30", "20")).isEqualTo(1);
    }

    @Test
    void reversalReducesUsed_accepted() throws SQLException {
        insert("b7", "50", "40", "0");
        try (Statement st = conn.createStatement()) {
            // 撤销/退料使 used 下降 → 始终满足不变式, 不得被 CHECK 干扰。
            assertThatCode(() ->
                    st.executeUpdate("UPDATE material_batches SET used_quantity = 10 WHERE id = 'b7'"))
                    .doesNotThrowAnyException();
        }
    }
}
