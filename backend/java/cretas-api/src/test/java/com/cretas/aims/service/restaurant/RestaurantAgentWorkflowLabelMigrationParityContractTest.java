package com.cretas.aims.service.restaurant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 迁移 V20261029_47 写入的 nodes_json 必须与 Provisioner 常量<b>语义等价</b>。
 *
 * <h2>为什么需要这条</h2>
 *
 * <p>{@code RestaurantAgentActionWorkflowProvisioner.isCanonical()} 把存量
 * {@code nodes_json} 与常量 {@code NODES_JSON} <b>逐字比对</b>
 * （{@code objectMapper.readTree(NODES_JSON).equals(readTree(workflow.getNodesJson()))}）。
 *
 * <p>而 {@code provisionIfEligible} <b>只在缺失时创建</b>
 * （{@code existsBy…} → 「already exists; preserving tenant config」直接 return），
 * 所以改常量<b>不会</b>自动更新存量。二者任一先行都会炸：
 *
 * <ul>
 *   <li>只改常量 → 37 行全部 non-canonical →
 *       {@code RestaurantAgentActionWorkflowService.requireCanonicalWorkflow} 抛
 *       <b>503 RESTAURANT_AGENT_ACTION_WORKFLOW_INVALID</b> → 整个功能对这 37 个工厂不可用；</li>
 *   <li>只跑迁移 → 对称地一样坏。</li>
 * </ul>
 *
 * <p>所以这条测试守的性质是：<b>两处 JSON 必须同时改、且改成同一个东西</b>。
 * 哪天有人只动一边，这里立刻红。
 *
 * <p>⛔ 用 JSON 树比较而不是字符串比较 —— 缩进/换行不同不该判失败，
 * 而 {@code isCanonical} 本身也是树比较，这里与它同口径。
 */
@DisplayName("V20261029_47 迁移的 nodes_json 必须与 Provisioner 常量等价")
class RestaurantAgentWorkflowLabelMigrationParityContractTest {

    private static final Path MIGRATION = Paths.get(
            "src/main/resources/db/flyway/V20261029_47__restaurant_agent_workflow_labels_to_chinese.sql");
    private static final Path PROVISIONER = Paths.get(
            "src/main/java/com/cretas/aims/service/restaurant/RestaurantAgentActionWorkflowProvisioner.java");

    private final ObjectMapper mapper = new ObjectMapper();

    private String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /** 取迁移里 $json$...$json$ 之间那段。 */
    private String migrationNodesJson() throws IOException {
        Matcher m = Pattern.compile("\\$json\\$([\\s\\S]*?)\\$json\\$").matcher(read(MIGRATION));
        assertThat(m.find()).as("迁移里没找到 $json$ 块, 断言无效").isTrue();
        return m.group(1);
    }

    /** 取 Provisioner 的 NODES_JSON text block。 */
    private String provisionerNodesJson() throws IOException {
        String src = read(PROVISIONER);
        int begin = src.indexOf("NODES_JSON = \"\"\"");
        assertThat(begin).as("没找到 NODES_JSON, 断言无效").isGreaterThan(0);
        int open = src.indexOf("\"\"\"", begin) + 3;
        int close = src.indexOf("\"\"\"", open);
        assertThat(close).isGreaterThan(open);
        return src.substring(open, close);
    }

    @Test
    @DisplayName("阳性对照: 两份 JSON 都读得到且能解析")
    void positiveControl() throws Exception {
        assertThat(mapper.readTree(migrationNodesJson()).isArray()).isTrue();
        assertThat(mapper.readTree(provisionerNodesJson()).isArray()).isTrue();
    }

    @Test
    @DisplayName("🔴 迁移写入的 nodes_json 必须与常量等价 —— 否则 isCanonical 仍为 false, 全部 503")
    void migrationMatchesProvisionerConstant() throws Exception {
        JsonNode fromMigration = mapper.readTree(migrationNodesJson());
        JsonNode fromConstant = mapper.readTree(provisionerNodesJson());
        assertThat(fromMigration)
                .as("两者不一致 → 存量 37 行 non-canonical → "
                        + "requireCanonicalWorkflow 抛 503 RESTAURANT_AGENT_ACTION_WORKFLOW_INVALID")
                .isEqualTo(fromConstant);
    }

    @Test
    @DisplayName("label 必须是中文 —— 它直接显示在 OA 审批中心的「当前节点」列")
    void labelsAreChinese() throws Exception {
        JsonNode nodes = mapper.readTree(provisionerNodesJson());
        for (JsonNode node : nodes) {
            String label = node.path("label").asText("");
            assertThat(label).as("节点 %s 的 label 为空", node.path("id").asText()).isNotBlank();
            assertThat(label)
                    .as("节点 %s 的 label「%s」不含中文 —— 英文会直接显示给中文用户",
                            node.path("id").asText(), label)
                    .matches(".*[\\u4e00-\\u9fa5].*");
        }
    }

    @Test
    @DisplayName("description 也是中文 (同样会展示)")
    void descriptionIsChinese() throws Exception {
        String src = read(PROVISIONER);
        int i = src.indexOf("DESCRIPTION =");
        assertThat(i).isGreaterThan(0);
        String tail = src.substring(i, Math.min(i + 400, src.length()));
        assertThat(tail).matches("[\\s\\S]*[\\u4e00-\\u9fa5][\\s\\S]*");
        assertThat(tail).doesNotContain("Human review of missing dish cost data");
    }

    @Test
    @DisplayName("迁移只动原样未改的行 —— 不覆盖租户自定义")
    void migrationOnlyTouchesUnmodifiedRows() throws Exception {
        String sql = read(MIGRATION);
        assertThat(sql)
                .as("必须用旧 canonical 的特征串限定, 否则会把用户改过的配置也覆盖掉")
                .contains("LIKE '%Review dish cost data%'");
        assertThat(sql).as("必须有台账才能回滚").contains("backup_restaurant_agent_wf_20260802");
    }
}
