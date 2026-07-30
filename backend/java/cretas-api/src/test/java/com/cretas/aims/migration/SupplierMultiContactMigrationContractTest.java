package com.cretas.aims.migration;

import com.cretas.aims.entity.enums.SupplierAddressType;
import com.cretas.aims.entity.enums.SupplierContactType;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20261029_34 的静态契约。
 *
 * <p><b>为什么值得写</b>: migration 在 prod 上失败会 ABORT 整个部署 —— 一个
 * {@code BIGGSERIAL} 级别的拼写错就够了, 而这类错误编译期、单测期都碰不到。
 * 本测试把「幂等 / 表存在性守卫 / 回填 / 授权 / 枚举白名单」这几条上线前必须成立的
 * 性质钉成断言。
 */
class SupplierMultiContactMigrationContractTest {

    private static final String MIGRATION =
            "db/flyway/V20261029_34__supplier_multi_contact_address_bank.sql";

    @Test
    void createsThreeChildTablesAndTheShortNameColumn() throws Exception {
        String sql = read(MIGRATION);

        assertThat(sql)
                .contains("CREATE TABLE IF NOT EXISTS supplier_contacts")
                .contains("CREATE TABLE IF NOT EXISTS supplier_addresses")
                .contains("CREATE TABLE IF NOT EXISTS supplier_bank_accounts")
                .contains("ALTER TABLE suppliers ADD COLUMN IF NOT EXISTS short_name VARCHAR(50)");
    }

    @Test
    void isIdempotentSoARerunOnAPartiallyMigratedEnvironmentCannotFail() throws Exception {
        String sql = read(MIGRATION);

        // 建表 / 建索引 / 加列全部 IF (NOT) EXISTS
        assertThat(sql).doesNotContain("CREATE TABLE supplier_");
        assertThat(sql).doesNotContain("CREATE INDEX idx_supplier_");
        assertThat(sql).doesNotContain("CREATE UNIQUE INDEX uq_supplier");

        // 回填靠确定性 id + NOT EXISTS 守卫, 重跑不会插重复行 (三段回填各一处)
        assertThat(countOccurrences(sql, "NOT EXISTS (")).isGreaterThanOrEqualTo(3);
        assertThat(sql).contains(":supplier-primary-contact")
                       .contains(":supplier-primary-address")
                       .contains(":supplier-primary-bank");
    }

    @Test
    void guardsAgainstSuppliersTableMissingOnAFreshDatabase() throws Exception {
        String sql = read(MIGRATION);
        // 全新 CI DB 上 Flyway 先于 Hibernate ddl-auto 跑, suppliers 还不存在时
        // 裸 ALTER / 裸 INSERT..SELECT 会报 "relation does not exist" 并阻断启动。
        assertThat(sql).contains("to_regclass('public.suppliers')");
        assertThat(countOccurrences(sql, "to_regclass('public.suppliers')")).isGreaterThanOrEqualTo(2);
    }

    @Test
    void grantsToTheApplicationRoleAndGuardsAgainstTheRoleNotExisting() throws Exception {
        String sql = read(MIGRATION);
        assertThat(sql)
                .contains("FROM pg_roles WHERE rolname = 'cretas_user'")
                .contains("GRANT SELECT, INSERT, UPDATE, DELETE ON supplier_contacts TO cretas_user")
                .contains("GRANT SELECT, INSERT, UPDATE, DELETE ON supplier_addresses TO cretas_user")
                .contains("GRANT SELECT, INSERT, UPDATE, DELETE ON supplier_bank_accounts TO cretas_user");
    }

    @Test
    void usesApplicationGeneratedUuidPrimaryKeysSoThereIsNoSequenceToForgetToGrant() throws Exception {
        String sql = read(MIGRATION);
        // 自增列会隐式建 sequence, 授了表没授 sequence 就是 INSERT permission denied。
        // 必须先剥注释再断言 —— 注释里讲这件事本身会让断言假红 (实测踩到)。
        assertThat(stripComments(sql)).doesNotContain("SERIAL");
        long uuidPks = java.util.regex.Pattern
                .compile("\\bid\\s+VARCHAR\\(36\\)\\s+PRIMARY KEY", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(sql).results().count();
        assertThat(uuidPks).as("三张子表都必须是应用生成的 UUID 主键").isEqualTo(3);
    }

    @Test
    void checkWhitelistsCoverEveryEnumConstant() throws Exception {
        String sql = read(MIGRATION);
        // 与 EnumCheckConstraintDriftTest 同一类保护, 但在本 migration 上具名留痕:
        // 白名单漏一个值 = 代码写得进 PG 写不进。
        for (SupplierContactType t : SupplierContactType.values()) {
            assertThat(sql).as("ck_supplier_contact_type 缺 " + t.name())
                    .contains("'" + t.name() + "'");
        }
        for (SupplierAddressType t : SupplierAddressType.values()) {
            assertThat(sql).as("ck_supplier_address_type 缺 " + t.name())
                    .contains("'" + t.name() + "'");
        }
        assertThat(sql).contains("CONSTRAINT ck_supplier_contact_type CHECK (contact_type IN (")
                       .contains("CONSTRAINT ck_supplier_address_type CHECK (address_type IN (");
    }

    @Test
    void backfillPrefersTheColumnsThatActuallyCarryProdData() throws Exception {
        String sql = read(MIGRATION);
        // prod 实测: contact_person 50 行 / contact_name 6 行, 且两者从不同时有值;
        // phone 50 / contact_phone 12 同理。回填必须 COALESCE 两边, 只取一个会丢数据。
        assertThat(sql)
                .contains("COALESCE(NULLIF(btrim(s.contact_person), ''), btrim(s.contact_name))")
                .contains("COALESCE(NULLIF(btrim(s.phone), ''), s.contact_phone)")
                .contains("COALESCE(NULLIF(btrim(s.email), ''), s.contact_email)");

        // 存量有 bank_account 但没 bank_name 的行 (prod 6 vs 2), bank_name 是 NOT NULL,
        // 不给占位就会因为缺开户行把账号整条丢掉。
        assertThat(sql).contains("'未填写开户行'");
    }

    @Test
    void enforcesOneAndOnlyOnePrimaryPerCollection() throws Exception {
        String sql = read(MIGRATION);
        assertThat(sql)
                .contains("uq_supplier_contacts_primary")
                .contains("uq_supplier_addresses_primary")
                .contains("uq_supplier_bank_accounts_primary");
        // 软删行不参与, 否则删一条再建一条就撞唯一约束
        assertThat(countOccurrences(sql, "WHERE deleted_at IS NULL AND is_primary = TRUE")).isEqualTo(3);
    }

    @Test
    void keepsLegacySingleValueColumnsSoExistingReadersDoNotBreak() throws Exception {
        String sql = read(MIGRATION);
        // 采购单 PDF / 出纳付款单 / 溯源产地 / 导入导出 / 准入摘要 / AI Tool
        // 全部读 suppliers 上的单值列 —— 本次一列都不许删。
        assertThat(sql).doesNotContain("DROP COLUMN");
        assertThat(sql).doesNotContain("DROP TABLE");
    }

    /** 去掉 {@code --} 行注释, 只留真正的 DDL。 */
    private static String stripComments(String sql) {
        return sql.replaceAll("(?m)^\\s*--.*$", "");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private String read(String resource) throws Exception {
        try (var input = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            assertThat(input).as("找不到 migration: " + resource).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
