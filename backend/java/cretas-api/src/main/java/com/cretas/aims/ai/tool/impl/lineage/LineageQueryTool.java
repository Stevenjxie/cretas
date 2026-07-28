package com.cretas.aims.ai.tool.impl.lineage;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.lineage.BatchLineageClosure;
import com.cretas.aims.entity.lineage.BatchLineageEdge;
import com.cretas.aims.repository.lineage.BatchLineageClosureRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 批次溯源查询 Tool — Sprint 11 D4.
 *
 * <p>AI 工厂 chat 入口: 老板问 "这批成品用了哪批原料" / "这批原料流向了哪些客户" /
 * "RES_3101_009 这批货从哪来" 时, LLM 路由到 lineage_query Tool.
 *
 * <p>底层依赖 batch_lineage_closure (O(1) 祖先/后代查询, 由 fn_maintain_lineage_closure
 * 触发器自动维护) + batch_lineage_edges (含 quantityUsed / unit / eventTime / meta).
 *
 * @author Cretas Team
 * @since 2026-05-22 (Sprint 11 D4)
 */
@Slf4j
@Component
public class LineageQueryTool extends AbstractBusinessTool {

    @Autowired
    private BatchLineageClosureRepository closureRepository;

    @Autowired
    private BatchLineageEdgeRepository edgeRepository;

    /** 允许的 batch_type 取值 (per BatchLineageEdge.sourceType 枚举). */
    private static final Set<String> ALLOWED_BATCH_TYPES = Set.of(
            "MATERIAL_BATCH", "PRODUCTION_BATCH", "FINISHED_BATCH", "SHIPMENT_RECORD");

    /** 允许的 direction 取值. */
    private static final Set<String> ALLOWED_DIRECTIONS = Set.of("ANCESTORS", "DESCENDANTS", "BOTH");

    /** maxDepth 上限. */
    private static final int MAX_DEPTH_CAP = 10;
    private static final int DEFAULT_MAX_DEPTH = 5;

    @Override
    public String getToolName() {
        return "lineage_query";
    }

    @Override
    public String getDescription() {
        return "查询批次溯源 — 输入批次类型 + 批次号, 返回该批次的全部祖先 (用了哪些原料/源批次) " +
                "和全部后代 (流向哪些下游/客户). 适用: 老板问 \"这批成品用了哪批原料\" / " +
                "\"这批原料发给了谁\" / \"召回这批要查哪些客户\" / \"RES_3101_009 从哪来的\". " +
                "支持 MATERIAL_BATCH / PRODUCTION_BATCH / FINISHED_BATCH / SHIPMENT_RECORD 四种类型.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> batchType = new HashMap<>();
        batchType.put("type", "string");
        batchType.put("description", "批次类型: MATERIAL_BATCH (原料批次) / PRODUCTION_BATCH (生产批次) / " +
                "FINISHED_BATCH (成品批次) / SHIPMENT_RECORD (发货记录)");
        batchType.put("enum", List.copyOf(ALLOWED_BATCH_TYPES));
        properties.put("batch_type", batchType);

        Map<String, Object> batchId = new HashMap<>();
        batchId.put("type", "string");
        batchId.put("description", "批次唯一 ID — UUID 字符串 (FinishedBatch/MaterialBatch) 或数字字符串 (ProductionBatch)");
        properties.put("batch_id", batchId);

        Map<String, Object> direction = new HashMap<>();
        direction.put("type", "string");
        direction.put("description", "查询方向: ANCESTORS (仅祖先/上游) / DESCENDANTS (仅后代/下游) / BOTH (默认)");
        direction.put("enum", List.copyOf(ALLOWED_DIRECTIONS));
        direction.put("default", "BOTH");
        properties.put("direction", direction);

        Map<String, Object> maxDepth = new HashMap<>();
        maxDepth.put("type", "integer");
        maxDepth.put("description", "最大跳数 (1-10, 默认 5)");
        maxDepth.put("default", DEFAULT_MAX_DEPTH);
        maxDepth.put("minimum", 1);
        maxDepth.put("maximum", MAX_DEPTH_CAP);
        properties.put("max_depth", maxDepth);

        schema.put("properties", properties);
        schema.put("required", List.of("batch_type", "batch_id"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("batch_type", "batch_id");
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        return switch (paramName) {
            case "batch_type" -> "请问要查哪种批次? 原料批次 (MATERIAL_BATCH) / 生产批次 (PRODUCTION_BATCH) / " +
                    "成品批次 (FINISHED_BATCH) / 发货记录 (SHIPMENT_RECORD)?";
            case "batch_id" -> "请提供批次 ID 或批号 (例: RES_3101_009)";
            default -> super.getParameterQuestion(paramName);
        };
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
                                            Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        String batchType = upper(getString(params, "batch_type"));
        String batchId = getString(params, "batch_id");
        String direction = upperOrDefault(getString(params, "direction"), "BOTH");
        Integer maxDepth = getInteger(params, "max_depth", DEFAULT_MAX_DEPTH);

        if (!ALLOWED_BATCH_TYPES.contains(batchType)) {
            return buildSimpleResult(
                    "batch_type 不合法 (允许: " + ALLOWED_BATCH_TYPES + ")",
                    Map.of("status", "VALIDATION_ERROR", "field", "batch_type", "value", batchType));
        }
        if (!ALLOWED_DIRECTIONS.contains(direction)) {
            return buildSimpleResult(
                    "direction 不合法 (允许: " + ALLOWED_DIRECTIONS + ")",
                    Map.of("status", "VALIDATION_ERROR", "field", "direction", "value", direction));
        }
        int depthCap = Math.min(Math.max(1, maxDepth == null ? DEFAULT_MAX_DEPTH : maxDepth), MAX_DEPTH_CAP);

        log.info("lineage_query factoryId={} type={} id={} dir={} depth={}",
                factoryId, batchType, batchId, direction, depthCap);

        // 1) Closure graph (含 ancestors + descendants + self-edge depth=0)
        List<BatchLineageClosure> graph =
                closureRepository.findFullGraph(factoryId, batchId, batchType, depthCap);

        List<Map<String, Object>> ancestors = new ArrayList<>();
        List<Map<String, Object>> descendants = new ArrayList<>();
        for (BatchLineageClosure c : graph) {
            if (c.getDepth() == 0) continue;
            boolean iAmDescendant = c.getDescendantId().equals(batchId)
                    && c.getDescendantType().equals(batchType);
            if (iAmDescendant) {
                if ("DESCENDANTS".equals(direction)) continue;
                ancestors.add(serializeClosure(c, true));
            } else {
                if ("ANCESTORS".equals(direction)) continue;
                descendants.add(serializeClosure(c, false));
            }
        }

        // 2) Direct edges with metadata (quantityUsed / unit / eventTime / meta)
        List<Map<String, Object>> edges = new ArrayList<>();
        if (!"DESCENDANTS".equals(direction)) {
            for (BatchLineageEdge e :
                    edgeRepository.findByFactoryIdAndTargetIdAndTargetType(factoryId, batchId, batchType)) {
                edges.add(serializeEdge(e, "UPSTREAM"));
            }
        }
        if (!"ANCESTORS".equals(direction)) {
            for (BatchLineageEdge e :
                    edgeRepository.findByFactoryIdAndSourceIdAndSourceType(factoryId, batchId, batchType)) {
                edges.add(serializeEdge(e, "DOWNSTREAM"));
            }
        }

        // 3) Build result
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("batchType", batchType);
        data.put("batchId", batchId);
        data.put("direction", direction);
        data.put("maxDepth", depthCap);
        data.put("ancestors", ancestors);
        data.put("descendants", descendants);
        data.put("edges", edges);
        data.put("ancestorCount", ancestors.size());
        data.put("descendantCount", descendants.size());
        data.put("directEdgeCount", edges.size());

        if (ancestors.isEmpty() && descendants.isEmpty() && edges.isEmpty()) {
            data.put("status", "NO_LINEAGE_FOUND");
            return buildSimpleResult(
                    "未找到批次 " + batchType + ":" + batchId + " 的溯源信息. " +
                            "可能原因: 批次不存在 / 还未参与生产 / lineage 未配置.",
                    data);
        }

        String summary = buildSummary(batchType, batchId, ancestors.size(),
                descendants.size(), edges.size(), direction);
        return buildSimpleResult(summary, data);
    }

    private Map<String, Object> serializeClosure(BatchLineageClosure c, boolean iAmDescendant) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (iAmDescendant) {
            m.put("nodeType", c.getAncestorType());
            m.put("nodeId", c.getAncestorId());
        } else {
            m.put("nodeType", c.getDescendantType());
            m.put("nodeId", c.getDescendantId());
        }
        m.put("depth", c.getDepth());
        return m;
    }

    private Map<String, Object> serializeEdge(BatchLineageEdge e, String relation) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("relation", relation);
        m.put("edgeType", e.getEdgeType());
        m.put("sourceType", e.getSourceType());
        m.put("sourceId", e.getSourceId());
        m.put("targetType", e.getTargetType());
        m.put("targetId", e.getTargetId());
        m.put("quantityUsed", e.getQuantityUsed());
        m.put("unit", e.getUnit());
        m.put("eventTime", e.getEventTime());
        m.put("meta", e.getMeta());
        return m;
    }

    private static String buildSummary(String batchType, String batchId, int ancestorCount,
                                       int descendantCount, int edgeCount, String direction) {
        StringBuilder sb = new StringBuilder();
        sb.append(batchType).append(":").append(batchId).append(" — ");
        if (!"DESCENDANTS".equals(direction) && ancestorCount > 0) {
            sb.append("上游 ").append(ancestorCount).append(" 节点");
        }
        if (!"DESCENDANTS".equals(direction) && !"ANCESTORS".equals(direction)
                && ancestorCount > 0 && descendantCount > 0) {
            sb.append(", ");
        }
        if (!"ANCESTORS".equals(direction) && descendantCount > 0) {
            sb.append("下游 ").append(descendantCount).append(" 节点");
        }
        sb.append(", ").append(edgeCount).append(" 条直接关联边");
        return sb.toString();
    }

    private static String upper(String s) {
        return s == null ? null : s.trim().toUpperCase();
    }

    private static String upperOrDefault(String s, String dflt) {
        String u = upper(s);
        return u == null || u.isEmpty() ? dflt : u;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
