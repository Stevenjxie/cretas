package com.cretas.aims.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 同一个 Flyway 版本号不许出现两次。
 *
 * <p>2026-08-06 实测事故: 两个并行分支各自把新迁移编成 {@code V20261029_55},
 * 文件名不同({@code __restaurant_manager_reads_hr} vs
 * {@code __restaurant_owner_read_all_modules}) —— <b>git 不会报冲突</b>(改的不是
 * 同一区域, 连同一个文件都不是), 两份都合进了 main, CI 全绿。
 *
 * <p>症状要到<b>启动</b>才出现: Flyway 报 "Found more than one migration with
 * version 20261029.55" 并中止, 后端起不来。也就是说这类缺陷会一路绿到部署那一刻,
 * 而部署 prod 时炸 = 线上后端直接起不来。
 *
 * <p>这条闸测的是<b>文件名解析出的版本号</b>, 与 Flyway 自己的判定同源, 所以不需要
 * 起 Spring 上下文、不需要连库, 毫秒级。
 */
class FlywayVersionUniquenessTest {

    private static final Path MIGRATION_DIR =
            Path.of("src/main/resources/db/flyway");

    /** Flyway 命名: V<version>__<description>.sql; 版本里的 `_` 等价于 `.`。 */
    private static final Pattern MIGRATION_NAME =
            Pattern.compile("^V([0-9_]+)__.+\\.sql$");

    @Test
    @DisplayName("db/flyway 下没有两个迁移共用同一版本号")
    void noDuplicateMigrationVersions() throws IOException {
        assertThat(MIGRATION_DIR)
                .as("迁移目录不存在 —— 说明这条闸测错了地方, 不是「没问题」")
                .exists();

        Map<String, List<String>> byVersion = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(MIGRATION_DIR)) {
            files.map(p -> p.getFileName().toString())
                    .sorted()
                    .forEach(name -> {
                        Matcher m = MIGRATION_NAME.matcher(name);
                        if (!m.matches()) {
                            return;
                        }
                        // Flyway 把版本里的 '_' 当 '.' —— 20261029_55 与 20261029.55 是同一个版本
                        String version = m.group(1).replace('_', '.');
                        byVersion.computeIfAbsent(version, k -> new ArrayList<>()).add(name);
                    });
        }

        assertThat(byVersion)
                .as("一个迁移都没解析到 —— 命名规则变了或路径错了, 不是「没有重复」")
                .isNotEmpty();

        List<String> collisions = new ArrayList<>();
        byVersion.forEach((version, names) -> {
            if (names.size() > 1) {
                collisions.add(version + " -> " + String.join(", ", names));
            }
        });

        assertThat(collisions)
                .as("这些版本号被多个迁移共用 —— Flyway 启动时会报 "
                        + "\"Found more than one migration with version ...\" 并让后端起不来。"
                        + "修法: 让**尚未部署到 prod 那一侧**改用下一个空闲版本号")
                .isEmpty();
    }
}
