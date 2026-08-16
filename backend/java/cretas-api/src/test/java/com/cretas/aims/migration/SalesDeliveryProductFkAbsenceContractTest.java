package com.cretas.aims.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code sales_delivery_items.product_type_id} 不许再有指向 {@code product_types} 的外键。
 *
 * <h2>🔴 2026-08-13 真机 E2E 抓到(LIUSHANMEN 生产)</h2>
 * 销售订单可以卖物料之后, 点「创建发货单」报
 * 「新建失败: 引用的『SKU 管理』数据不存在」——
 * 那不是业务校验, 是 {@code GlobalExceptionHandler} 把<b>外键冲突</b>翻译出来的
 * (它把 {@code product_types} 映射成「SKU 管理」)。
 *
 * <p>而兄弟表 {@code sales_order_items} 上<b>没有</b>这条外键 —— 同一个 id 在同一条链路的
 * 两张表上口径不一致, 于是订单行存得进、发货行存不进, <b>物料在半路被卡住</b>。
 * V20261029_84 去掉了它。
 *
 * <h2>这条闸守什么</h2>
 * 谁要是在后续迁移里把它加回来, 物料发货会<b>再次静默卡死</b>, 而且现场表现是一句
 * 指向「SKU 管理」的误导性提示 —— 排查的人会去翻商品目录, 那里什么问题都没有。
 *
 * <p>读迁移文件、不连库、毫秒级, 与 {@link FlywayVersionUniquenessTest} 同一类。
 *
 * <p>⚠️ CI 的 Java selector 目前只跑
 * {@code *RepositoryQueryValidationTest,*StartupGuardTest,FlywayVersionUniquenessTest},
 * <b>不覆盖本用例</b>(本仓 Java 全量套件只在 full_audit 跑)。
 */
class SalesDeliveryProductFkAbsenceContractTest {

    private static final Path MIGRATION_DIR = Path.of("src/main/resources/db/flyway");

    /** 匹配「给 sales_delivery_items 加外键并引用 product_types」的语句(容忍换行与大小写)。 */
    private static final Pattern READDS_FK = Pattern.compile(
            "ALTER\\s+TABLE\\s+(?:public\\.)?sales_delivery_items[\\s\\S]{0,400}?"
                    + "ADD\\s+CONSTRAINT[\\s\\S]{0,200}?FOREIGN\\s+KEY[\\s\\S]{0,120}?product_type_id"
                    + "[\\s\\S]{0,200}?REFERENCES\\s+(?:public\\.)?product_types",
            Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("没有迁移把 sales_delivery_items → product_types 的外键加回来")
    void noMigrationReAddsTheProductForeignKey() throws IOException {
        List<String> offenders = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(MIGRATION_DIR)) {
            files = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".sql"))
                    .sorted()
                    .toList();
        }

        // 空目录会让这条闸「绿得毫无意义」。
        assertThat(files)
                .as("迁移目录 %s 下应当有 .sql; 为空说明工作目录不对, 本闸没在测任何东西",
                        MIGRATION_DIR.toAbsolutePath())
                .isNotEmpty();

        for (Path file : files) {
            if (READDS_FK.matcher(Files.readString(file)).find()) {
                offenders.add(file.getFileName().toString());
            }
        }

        assertThat(offenders)
                .as("""
                        这些迁移把 sales_delivery_items.product_type_id → product_types 的外键加了回来。\
                        加回来之后物料行发货会再次被数据库拦死, 而现场只会看到一句指向「SKU 管理」\
                        的误导性提示(V20261029_84 正是为此去掉它的)。\
                        兄弟表 sales_order_items 上本来就没有这条约束, 两者必须保持一致。""")
                .isEmpty();
    }

    /** 去掉那条外键的迁移必须还在 —— 有人删掉它, 新库就会重新长出这条约束。 */
    @Test
    @DisplayName("去掉该外键的迁移仍然存在")
    void theDroppingMigrationIsStillPresent() throws IOException {
        try (Stream<Path> walk = Files.walk(MIGRATION_DIR)) {
            boolean present = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .anyMatch(p -> {
                        try {
                            String sql = Files.readString(p);
                            return sql.contains("sales_delivery_items")
                                    && sql.toUpperCase(Locale.ROOT).contains("DROP CONSTRAINT")
                                    && sql.contains("fk_sdi_product");
                        } catch (IOException e) {
                            return false;
                        }
                    });
            assertThat(present).as("应当存在一条 DROP CONSTRAINT fk_sdi_product 的迁移").isTrue();
        }
    }
}
