package com.cretas.aims.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 被组件扫描的类(@Service/@Component/...)上不许出现 {@code @ConditionalOnBean}。
 *
 * <h2>2026-08-12 实测事故</h2>
 * prod 部署失败, 报
 * {@code Parameter 4 of constructor in InvoiceServiceImpl required a bean of type
 * 'com.cretas.aims.service.OssService' that could not be found} —— 而当时那次部署
 * <b>唯一改动的是一个和 OSS 毫无关系的 BOM 服务的方法体</b>。
 *
 * <h2>机制</h2>
 * {@code @ConditionalOnBean} 只能匹配「到目前为止<b>已经处理过的</b>」bean 定义,
 * Spring 自己的 javadoc 因此写明它只应该用在自动配置类上。用在被扫描的 @Service 上时,
 * 条件成立与否取决于**扫描顺序**, 而扫描顺序 = jar 里 class 条目的**物理顺序**。
 *
 * <p>实测两份 jar(9361 个条目, 只有 4 个不同, 且全是那个 BOM 类):
 * <pre>
 *   能起来的:  OssConfig #1620,  OssServiceImpl #6765   ✅
 *   起不来的:  OssServiceImpl #1139, OssConfig #6406    ❌ 手动复现 3 次, 确定性
 * </pre>
 * 也就是说 <b>任何一次重新构建都可能把顺序翻过来</b>, 与改了什么代码无关 —— 一直没炸
 * 只是运气。这类缺陷 CI 全绿、单测全绿, 要到<b>启动那一刻</b>才炸, 而那一刻是 prod 部署。
 *
 * <h2>正解</h2>
 * 换成与被依赖的 @Bean <b>完全相同的属性条件</b>({@code @ConditionalOnProperty}) ——
 * 语义等价, 但与注册顺序无关。
 *
 * <p>这条闸读源文件、不起 Spring 上下文、不连库, 与 {@code FlywayVersionUniquenessTest}
 * 同一类 —— 都是「要到启动才炸」的缺陷, 必须在构建期就拦下。
 */
class ConditionalOnBeanStartupGuardTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java");

    /**
     * 类级注解写在第 0 列; 方法级的一定有缩进, 所以 {@code ^} 足以区分。
     *
     * <p>{@code (?:[\w.]+\.)?} 是必需的: 注解可以写成全限定名
     * ({@code @org.springframework...ConditionalOnBean})。第一版漏了它 —— 变异对照时
     * 我正是用全限定名把修复改回去的, 闸没红, 当场暴露了这个盲点。
     */
    private static final Pattern CLASS_LEVEL_STEREOTYPE =
            Pattern.compile("^@(?:[\\w.]+\\.)?(Service|Component|Repository|Controller|RestController)\\b",
                    Pattern.MULTILINE);

    /** 配置类/自动配置类上用 @ConditionalOnBean 是被支持的用法, 不在管辖范围。 */
    private static final Pattern CLASS_LEVEL_CONFIGURATION =
            Pattern.compile("^@(?:[\\w.]+\\.)?(Configuration|AutoConfiguration|EnableAutoConfiguration)\\b",
                    Pattern.MULTILINE);

    private static final Pattern CONDITIONAL_ON_BEAN =
            Pattern.compile("^@(?:[\\w.]+\\.)?ConditionalOnBean\\b", Pattern.MULTILINE);

    /**
     * ⚠️ 已知未修项 —— 这不是「豁免」, 是<b>还没处理的欠账</b>, 写在这里是为了让它可见。
     *
     * <p>{@code RedisConversationStateService}: {@code @Service}
     * {@code @ConditionalOnBean(StringRedisTemplate.class)}。它依赖的
     * {@code StringRedisTemplate} 来自 Spring Boot <b>自动配置</b>, 而自动配置在所有
     * 组件扫描<b>之后</b>才处理 —— 所以这个条件很可能<b>一直</b>不成立, 该 bean 从来
     * 没被创建过(prod 日志里 0 条它的痕迹, 与此一致)。
     *
     * <p>它<b>不会</b>导致启动失败: 唯一的注入点
     * {@code AIIntentServiceImpl#conversationStateService} 是
     * {@code @Autowired(required = false)}, 所以它只是<b>静默缺席</b>。
     *
     * <p>没有在同一个 PR 里一起修, 是因为修它等于<b>把一个长期关着的功能打开</b> ——
     * 那是行为变更, 需要单独评估, 不该混在一个「让 prod 起得来」的修复里。
     * 修好之后把这一项从本清单删掉, 本测试会因为「清单里有已不存在的项」而变红。
     */
    private static final Set<String> KNOWN_UNFIXED = Set.of("RedisConversationStateService");

    @Test
    @DisplayName("被扫描的组件上没有 @ConditionalOnBean(顺序依赖, 会在启动时随机炸)")
    void noConditionalOnBeanOnScannedComponents() throws IOException {
        Set<String> offenders = new TreeSet<>();

        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            List<Path> javaFiles = files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .toList();

            // 源码根必须真的扫到东西 —— 一个空的扫描结果会让这条闸「绿得毫无意义」。
            assertThat(javaFiles)
                    .as("源码根 %s 下应当有 Java 文件; 为空说明工作目录不对, 这条闸没在测任何东西",
                            SOURCE_ROOT.toAbsolutePath())
                    .isNotEmpty();

            for (Path file : javaFiles) {
                String source = Files.readString(file);
                if (!CONDITIONAL_ON_BEAN.matcher(source).find()) {
                    continue;
                }
                if (CLASS_LEVEL_CONFIGURATION.matcher(source).find()) {
                    continue;
                }
                if (CLASS_LEVEL_STEREOTYPE.matcher(source).find()) {
                    String name = file.getFileName().toString().replace(".java", "");
                    offenders.add(name);
                }
            }
        }

        assertThat(offenders)
                .as("""
                        这些被组件扫描的类带着 @ConditionalOnBean —— 它是否成立取决于 jar 里 \
                        class 条目的物理顺序, 任何一次重新构建都可能翻过来, 翻过来就是 prod \
                        启动失败(2026-08-12 实测)。改成与被依赖的 @Bean 相同的 \
                        @ConditionalOnProperty。""")
                .containsExactlyInAnyOrderElementsOf(KNOWN_UNFIXED);
    }
}
