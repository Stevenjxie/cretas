package com.cretas.aims.ai.tool.impl.workprocess;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.AbstractTool;
import com.cretas.aims.constant.SeasoningProcessCategory;
import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.ProductProcessWorkflowService;
import com.cretas.aims.service.validation.ProductProcessWorkflowValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Preview-only validator for AI generated product-process Workflow patches. */
@Component
public class ProductProcessWorkflowConfigTool extends AbstractTool {

    private static final String TOOL_NAME = "canvas_product_process_workflow_config";
    private static final Set<String> ALLOWED_OPERATIONS = Set.of(
            "UPSERT_NODE", "REMOVE_NODE", "UPSERT_EDGE", "REMOVE_EDGE", "SET_NODE_FIELD",
            // 2026-08-07 阶段 4(画布即 BOM): 让 AI 能增删调料/辅料行。
            "UPSERT_MATERIAL_BINDING", "REMOVE_MATERIAL_BINDING");
    private static final Set<String> ALLOWED_FIELD_ROOTS = Set.of(
            "name", "skuId", "skuCode", "specification", "ports",
            "conversionRule", "reportingRequired",
            // 阶段 2: 副产是带标记的普通产出节点(不是第 5 种 kind)
            "isByproduct",
            // 阶段 4: BOM 字段进入 AI 可改范围
            "materialBindings", "injectionAmount");

    /**
     * 一条 materialBinding(调料/辅料投入行)允许出现的键。
     * 这是**投入**侧: 每 kg 投入多少克。副产是产出侧, 走节点+边, 不在这里。
     */
    private static final Set<String> MATERIAL_BINDING_KEYS = Set.of(
            "materialTypeId", "materialName", "dosagePerKgG", "subsequentPotRatio", "unit");

    /**
     * 只有这些工序类别才允许配「后续锅调料比例」/「注射量」。
     * ⛔ 判据用常量, 不写裸字符串 —— 见 SeasoningProcessCategory 的类注释。
     */
    private static final Set<String> POT_RATIO_CATEGORIES = Set.of(SeasoningProcessCategory.COOKING);
    private static final Set<String> INJECTION_CATEGORIES = Set.of(SeasoningProcessCategory.INJECTION);
    private static final Set<String> NODE_KINDS = Set.of(
            "RAW_MATERIAL", "PROCESS", "SEMI_FINISHED", "FINISHED_GOOD");
    private static final Set<String> MATERIAL_NODE_KINDS = Set.of(
            "RAW_MATERIAL", "SEMI_FINISHED", "FINISHED_GOOD");
    private static final Set<String> CONVERSION_MODES = Set.of(
            "ACTUAL_WEIGHT", "FIXED_RATIO", "SUM_OUTPUTS", "FORMULA");
    private static final Set<String> MATERIAL_DATA_KEYS = Set.of(
            "name", "skuId", "skuCode", "specification", "baseUnit", "bound");
    private static final Set<String> PROCESS_DATA_KEYS = Set.of(
            "workProcessId", "processName", "processCategory", "inputUnit", "outputUnit",
            "standardTime", "ports", "conversionRule", "reportingRequired",
            "allowMultipleUpstreamSources", "allowFinishedGoodsSource");
    private static final Set<String> PORT_KEYS = Set.of(
            "id", "direction", "materialNodeId", "materialName", "skuId",
            "materialKind", "unit", "ordinal");
    private static final Pattern SAFE_FIELD_PATH = Pattern.compile(
            "^[A-Za-z][A-Za-z0-9]*(?:\\.[A-Za-z][A-Za-z0-9]*)*$");
    /**
     * 落库能力的总开关，<b>默认关</b>。
     *
     * <p>Steve 2026-08-09 拍板：先合进 main，但在补上「productTypeId 的可信来源」之前
     * 保持关闭。原因是<b>决定覆写哪张画布的 productTypeId 目前完全由 AI 决定</b> ——
     * {@code factoryId} 已经钉在 context 上（AI 改不了），但 context 里<b>没有</b>
     * productTypeId 可用（只有 factoryId/tenantId/userId/userRole/permissions），
     * 所以无法比对。模型在多产品对话里把它填成同厂另一个产品时，
     * {@code requireWorkflowOwner} 会放行（确实是本厂产品），结果是给那个产品
     * <b>新建</b>一张内容是别人的草稿 —— 不报错、无症状，可能很久没人发现。
     *
     * <p>⛔ 打开它之前必须先做的事：让网关/控制器把「用户当前打开的是哪个产品」
     * 带进 context，并在这里比对。⛔ 不要因为「测试都绿」就打开 ——
     * 这个洞在单元测试里看不见，它需要的是 context 里那个字段存在。
     */
    public static final String WRITE_ENABLED_PROPERTY =
            "cretas.ai.canvas-workflow-write.enabled";

    private final ProductProcessWorkflowValidator workflowValidator;
    private final ProductProcessWorkflowService workflowService;
    private final boolean writeEnabled;

    public ProductProcessWorkflowConfigTool(
            ObjectMapper objectMapper,
            ProductProcessWorkflowValidator workflowValidator,
            ProductProcessWorkflowService workflowService,
            @Value("${" + WRITE_ENABLED_PROPERTY + ":false}") boolean writeEnabled) {
        this.objectMapper = objectMapper;
        this.workflowValidator = workflowValidator;
        this.workflowService = workflowService;
        this.writeEnabled = writeEnabled;
    }

    @Override
    public String getToolName() {
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        return "Validates and previews local product-process WorkflowPatch objects without business execution";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "message", Map.of("type", "string"),
                        "definition", Map.of("type", "object"),
                        "selectedNodeId", Map.of("type", List.of("string", "null")),
                        "patches", Map.of("type", "array", "items", Map.of("type", "object"))),
                "required", List.of("definition", "patches"));
    }

    @Override
    public boolean supportsPreview() {
        return true;
    }

    @Override
    public ActionType getActionType() {
        return ActionType.GENERATE;
    }

    @Override
    public Set<String> getDomainTags() {
        return Set.of("product-process", "workflow", "configuration");
    }

    @Override
    public String preview(ToolCall toolCall, Map<String, Object> context) {
        try {
            ValidatedPatch validated = buildValidatedCandidate(parseArguments(toolCall));

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", "PREVIEW");
            data.put("applied", false);
            // ⚠️ 必须原样回显 patches(列表), 不能换成节点数 ——
            // ProductProcessWorkflowConfigToolTest:95 断言它是列表且 size==3。
            data.put("patches", validated.patches());
            return buildSuccessResult(data);
        } catch (MissingDefinitionException missing) {
            return buildSemanticError(
                    "WORKFLOW_DEFINITION_REQUIRED", "Workflow definition is required for preview");
        } catch (PatchRejectedException | BusinessException | IllegalArgumentException error) {
            // ⛔ 保留 rejectionMessage(error) —— origin/main:132 用它给出具体拒绝原因,
            // 换成固定串会让 agent 失去「为什么被拒」的信息, 那是能力倒退。
            return buildSemanticError("WORKFLOW_PATCH_REJECTED", rejectionMessage(error));
        }
    }

    /**
     * 解析入参 → 打补丁 → 跑草稿校验，返回可落库的候选。
     *
     * <p>⛔ preview 与 execute <b>必须</b>都走这里。各写一份会让「预览说能过、落库却过不了」
     * 成为可能 —— 那是本仓反复栽过的「同一概念两把尺子」。
     */
    private ValidatedPatch buildValidatedCandidate(Map<String, Object> arguments) {
        if (!(arguments.get("definition") instanceof Map<?, ?> definition)) {
            throw new MissingDefinitionException();
        }
        List<Map<String, Object>> patches = sanitizePatches(arguments.get("patches"));
        ProductProcessWorkflowDTO candidate = objectMapper.convertValue(
                definition, ProductProcessWorkflowDTO.class);
        applyCandidateBatch(candidate, patches);
        workflowValidator.validateForDraft(candidate);
        return new ValidatedPatch(candidate, patches);
    }

    /**
     * 校验通过的候选 + 原始补丁清单。
     *
     * <p>两样都要带回去：{@code preview} 要原样回显 patches（既有断言检查它是列表），
     * {@code execute} 要拿 candidate 去落库。合成一个返回值是为了让两条路
     * <b>物理上</b>不可能各走各的校验。
     */
    private record ValidatedPatch(
            ProductProcessWorkflowDTO candidate, List<Map<String, Object>> patches) {
    }

    /** definition 缺失与补丁被拒是两种不同的错，errorCode 也不同，所以要能分开捕获。 */
    private static final class MissingDefinitionException extends RuntimeException {
        private MissingDefinitionException() {
            super(null, null, false, false);
        }
    }

    /**
     * 会影响<b>扣料与成本</b>的补丁：{@code execute} 一律不落库，只能出预览。
     *
     * <p>这条分界不是我定的 —— {@code ProductProcessWorkflowConfigToolBomFieldsTest}
     * 的「约束 4」注释原文：<i>「改克数比改拓扑风险高（直接影响扣料与成本），
     * 所以这个工具结构上只能出预览。不是"前端记得弹审核框"，而是 execute 根本不写任何东西。」</i>
     *
     * <p>2026-08-09 Steve 拍板把这条约束<b>收窄到它真正针对的东西</b>：
     * 拓扑（加删节点/连线/改工序名）可以落草稿，克数与注射量仍然只能预览。
     * ⛔ 收窄不等于放宽 —— 注释担心的那件事一个字节都没让步。
     *
     * <p>⛔ 判据按补丁的 {@code op} 与 {@code path} <b>根</b>判，不按字符串包含判：
     * 按包含判会被工序名里恰好出现 "injection" 这类内容误伤，也会被换个写法绕过。
     */
    private static final Set<String> COST_BEARING_OPERATIONS = Set.of(
            "UPSERT_MATERIAL_BINDING", "REMOVE_MATERIAL_BINDING");
    private static final Set<String> COST_BEARING_FIELD_ROOTS = Set.of(
            "materialBindings", "injectionAmount");

    /** 这批补丁里有没有会动到扣料/成本的。⛔ 只要有一条, 整批都不落库。 */
    private boolean touchesCostBearingFields(List<Map<String, Object>> patches) {
        for (Map<String, Object> patch : patches) {
            String operation = String.valueOf(patch.get("op"));
            if (COST_BEARING_OPERATIONS.contains(operation)) {
                return true;
            }
            if ("SET_NODE_FIELD".equals(operation)) {
                String path = String.valueOf(patch.get("path"));
                String root = path.contains(".") ? path.substring(0, path.indexOf('.')) : path;
                if (COST_BEARING_FIELD_ROOTS.contains(root)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public String execute(ToolCall toolCall, Map<String, Object> context) {
        try {
            ValidatedPatch validated = buildValidatedCandidate(parseArguments(toolCall));

            if (!writeEnabled) {
                // ⛔ 开关关着时回到「只出预览」的老行为, 并把原因说清楚 ——
                // 不说原因的话, agent 会以为是补丁写错了, 反复重试同一件永远做不成的事。
                return buildSemanticError("WORKFLOW_AI_PREVIEW_ONLY",
                        "画布落库能力当前未开启，本次只生成预览；请人工在产品配置页保存");
            }

            if (touchesCostBearingFields(validated.patches())) {
                // ⛔ 整批拒绝, 不是「把克数那几条挑掉、其余照写」——
                // 部分应用会让 agent 以为整批生效了, 而实际画布处于它没预期的中间态。
                Map<String, Object> refusal = new LinkedHashMap<>();
                refusal.put("success", false);
                refusal.put("errorCode", "WORKFLOW_AI_PREVIEW_ONLY");
                refusal.put("error",
                        "涉及调料克数/注射量的补丁只能预览，请人工在产品配置页确认后再保存");
                return objectMapper.writeValueAsString(refusal);
            }

            ProductProcessWorkflowDTO candidate = validated.candidate();

            // ⛔ factoryId 只从 context 取。AI 能控制 definition 里的任何字段,
            // 让它决定写哪个租户 = 把租户隔离交给模型自觉。
            String factoryId = requireFactoryId(context);
            String productTypeId = candidate.getProductTypeId();
            if (productTypeId == null || productTypeId.isBlank()) {
                // ⛔ 不猜: 没有归属就不知道这张画布属于哪个成品, 猜错等于写到别的产品上。
                return buildSemanticError(
                        "WORKFLOW_OWNER_REQUIRED", "Workflow definition must carry productTypeId");
            }

            // 🔴 分流闸的第二道: 比对【库里的真实状态】, 不是比对补丁的自述。
            // 只看补丁会漏掉一整类改动 —— 详见 assertCostBearingFieldsUnchanged 的注释。
            if (!costBearingFieldsUnchanged(factoryId, productTypeId, candidate)) {
                return buildSemanticError("WORKFLOW_AI_PREVIEW_ONLY",
                        "该改动会变更调料克数/注射量，只能预览；请人工在产品配置页确认后再保存");
            }

            // ⛔ 只调 saveDraft。它按构造只写 DRAFT, 且自带租户归属校验 + 乐观锁。
            // 落库的四道闸全在它里面, 这里【一行都不重写】—— 重写等于把那些保证作废。
            ProductProcessWorkflowDTO saved =
                    workflowService.saveDraft(factoryId, productTypeId, candidate);

            // ⚠️ status 必须读 saved 的真实值, ⛔ 不许硬编码 "DRAFT"。
            // saveDraft 有一条【不建草稿】的早退路径: 无草稿 + 有已发布 + 图相同时
            // 直接 return 已发布那行, 一个字节都没写(ProductProcessWorkflowServiceImpl:93-99)。
            // 硬编码会把「库里一行没动」报成「已写入草稿」, 用户去画布找草稿会找不到 ——
            // 那是把「没写成」伪装成「写成了」, 本仓明令禁止。
            boolean actuallyWroteDraft =
                    "DRAFT".equals(saved.getStatus() == null ? null : saved.getStatus().toString());

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", saved.getStatus() == null ? "UNKNOWN" : saved.getStatus().toString());
            data.put("applied", actuallyWroteDraft);
            // 回传新 lockVersion: 不回传的话 agent 只能改一次, 第二次必然 409。
            data.put("lockVersion", saved.getLockVersion());
            data.put("hint", actuallyWroteDraft
                    ? "已写入草稿。发布需要人在产品配置页确认。"
                    : "这批改动与当前生效版本一致，没有产生新草稿。");
            return buildSuccessResult(data);
        } catch (MissingDefinitionException missing) {
            return buildSemanticError(
                    "WORKFLOW_DEFINITION_REQUIRED", "Workflow definition is required");
        } catch (BusinessException business) {
            // 409 冲突 / 400 归属不符都走这里。⛔ 不吞、不重试 —— 冲突意味着有人在同一张
            // 画布上工作, 悄悄重试会覆盖掉他。
            // ⚠️ 用 getErrorCode()(String) 不是 getCode() —— 后者返回 Integer 的 HTTP 码(409),
            // 传给 buildSemanticError(String, String) 会编译不过。
            String errorCode = business.getErrorCode() == null
                    ? "WORKFLOW_WRITE_REJECTED" : business.getErrorCode();
            return buildSemanticError(errorCode, business.getMessage());
        } catch (PatchRejectedException | IllegalArgumentException error) {
            return buildSemanticError("WORKFLOW_PATCH_REJECTED", rejectionMessage(error));
        } catch (Exception unexpected) {
            return buildSemanticError("WORKFLOW_PATCH_FAILED", "Workflow patch batch failed");
        }
    }

    /**
     * 要写下去的这张图，它的<b>成本相关字段</b>与库里现存的是否逐字相同。
     *
     * <h2>为什么光看补丁不够（整支审查抓出的 Critical）</h2>
     *
     * <p>{@link #touchesCostBearingFields} 分类的是<b>补丁清单</b>，而 {@code saveDraft}
     * 写下去的是 <b>AI 自己重发的那整张 definition 打完补丁之后的图</b>。两者不是同一个东西 ——
     * definition 与库里现存草稿之间的任何差异，从来没被任何闸看过。
     *
     * <p>它<b>必然</b>会被走到，不是理论风险：
     * <ol>
     *   <li>想改工序名只能用 {@code UPSERT_NODE} —— PROCESS 节点的 {@code SET_NODE_FIELD}
     *       不允许 {@code name}</li>
     *   <li>{@code UPSERT_NODE} 的清洗器 {@code PROCESS_DATA_KEYS} <b>不含</b>
     *       {@code materialBindings} —— agent 想让补丁通过，<b>必须</b>把调料字段拿掉</li>
     *   <li>{@code applyNodePatch} 的 UPSERT 分支是<b>整节点替换</b>，不继承任何字段</li>
     *   <li>分流闸只看 op/path，{@code UPSERT_NODE} 不在名单里 → 放行</li>
     * </ol>
     * 合起来：「把卤制改个名」会静默清空那道工序的全部调料克数，还回 {@code applied:true}。
     *
     * <p>📌 判据：<b>闸要判的是「真正要写下去的东西」，不是「补丁的自述」。</b>
     *
     * <p>⚠️ 读不到库里现存定义时（新产品第一张草稿）返回 {@code true} —— 没有可比对的基线，
     * 此时任何 materialBindings 都是新配的，而新配克数同样受第一道闸管辖
     * （补丁里带克数字段一定含 {@code UPSERT_MATERIAL_BINDING} 或 {@code materialBindings} 路径）。
     * ⛔ 这里不能改成返回 false，否则新产品永远建不了第一张草稿。
     */
    private boolean costBearingFieldsUnchanged(
            String factoryId, String productTypeId, ProductProcessWorkflowDTO candidate) {
        Optional<ProductProcessWorkflowDTO> stored =
                workflowService.getEditorDefinition(factoryId, productTypeId);
        if (stored.isEmpty()) {
            return true;
        }
        return costBearingFingerprint(stored.get()).equals(costBearingFingerprint(candidate));
    }

    /**
     * 一张图里全部成本相关字段的指纹：{@code nodeId -> {materialBindings, injectionAmount}}。
     *
     * <p>⛔ 只收这两样，不收整个 data —— 收整个 data 会让任何合法改动（改名、挪位置）都被判成
     * 「动了成本」，那道闸就会因为天天误报而被人绕过或删掉。
     */
    private Map<String, Object> costBearingFingerprint(ProductProcessWorkflowDTO definition) {
        Map<String, Object> fingerprint = new LinkedHashMap<>();
        if (definition == null || definition.getNodes() == null) {
            return fingerprint;
        }
        for (ProductProcessWorkflowDTO.Node node : definition.getNodes()) {
            if (node == null || node.getId() == null || node.getData() == null) {
                continue;
            }
            Map<String, Object> costBearing = new LinkedHashMap<>();
            for (String key : COST_BEARING_FIELD_ROOTS) {
                Object value = node.getData().get(key);
                if (value != null) {
                    costBearing.put(key, value);
                }
            }
            if (!costBearing.isEmpty()) {
                fingerprint.put(node.getId(), costBearing);
            }
        }
        return fingerprint;
    }

    /** ⛔ context 里没有 factoryId 就直接拒 —— 不许回退到入参里的任何值。 */
    private String requireFactoryId(Map<String, Object> context) {
        Object raw = context == null ? null : context.get("factoryId");
        String factoryId = raw == null ? null : raw.toString().trim();
        if (factoryId == null || factoryId.isEmpty()) {
            throw new IllegalArgumentException("factoryId missing from execution context");
        }
        return factoryId;
    }

    private List<Map<String, Object>> sanitizePatches(Object rawPatches) {
        if (!(rawPatches instanceof List<?> patches) || patches.isEmpty()) {
            throw new PatchRejectedException();
        }
        List<Map<String, Object>> accepted = new ArrayList<>();
        for (Object rawPatch : patches) {
            if (!(rawPatch instanceof Map<?, ?> patch)) {
                throw new PatchRejectedException();
            }
            Map<String, Object> sanitized = sanitizePatch(patch);
            if (sanitized == null) throw new PatchRejectedException();
            accepted.add(sanitized);
        }
        return List.copyOf(accepted);
    }

    private Map<String, Object> sanitizePatch(Map<?, ?> patch) {
        String operation = readNonBlankString(patch.get("op"));
        if (operation == null || !ALLOWED_OPERATIONS.contains(operation)) return null;
        return switch (operation) {
            case "UPSERT_NODE" -> sanitizeNodePatch(patch);
            case "REMOVE_NODE" -> sanitizeIdPatch(patch, "nodeId");
            case "UPSERT_EDGE" -> sanitizeEdgePatch(patch);
            case "REMOVE_EDGE" -> sanitizeIdPatch(patch, "edgeId");
            case "SET_NODE_FIELD" -> sanitizeFieldPatch(patch);
            case "UPSERT_MATERIAL_BINDING" -> sanitizeMaterialBindingPatch(patch);
            case "REMOVE_MATERIAL_BINDING" -> sanitizeRemoveMaterialBindingPatch(patch);
            default -> null;
        };
    }

    private Map<String, Object> sanitizeNodePatch(Map<?, ?> patch) {
        if (!hasExactKeys(patch, Set.of("op", "node")) || !(patch.get("node") instanceof Map<?, ?> node)) {
            return null;
        }
        Map<String, Object> sanitizedNode = sanitizeNode(node);
        return sanitizedNode == null ? null : linkedMap("op", "UPSERT_NODE", "node", sanitizedNode);
    }

    private Map<String, Object> sanitizeNode(Map<?, ?> node) {
        if (!hasExactKeys(node, Set.of("id", "kind", "position", "data"))) return null;
        String id = readNonBlankString(node.get("id"));
        String kind = readNonBlankString(node.get("kind"));
        if (id == null || kind == null || !NODE_KINDS.contains(kind)
                || !(node.get("position") instanceof Map<?, ?> position)
                || !(node.get("data") instanceof Map<?, ?> data)) return null;
        Map<String, Object> sanitizedPosition = sanitizePosition(position);
        Map<String, Object> sanitizedData = "PROCESS".equals(kind)
                ? sanitizeProcessData(data) : sanitizeMaterialData(data);
        if (sanitizedPosition == null || sanitizedData == null) return null;
        return linkedMap("id", id, "kind", kind, "position", sanitizedPosition, "data", sanitizedData);
    }

    private Map<String, Object> sanitizePosition(Map<?, ?> position) {
        if (!hasExactKeys(position, Set.of("x", "y"))
                || !isFiniteNumber(position.get("x")) || !isFiniteNumber(position.get("y"))) return null;
        return linkedMap("x", position.get("x"), "y", position.get("y"));
    }

    private Map<String, Object> sanitizeMaterialData(Map<?, ?> data) {
        if (!hasAllowedKeys(data, MATERIAL_DATA_KEYS)
                || readNonBlankString(data.get("name")) == null
                || !(data.get("skuId") instanceof String)
                || !isOptionalNullableString(data, "skuCode")
                || !isOptionalNullableString(data, "specification")
                || !isOptionalString(data, "baseUnit")
                || !isOptionalBoolean(data, "bound")) return null;
        return copyKnownValues(data, MATERIAL_DATA_KEYS);
    }

    private Map<String, Object> sanitizeProcessData(Map<?, ?> data) {
        if (!hasAllowedKeys(data, PROCESS_DATA_KEYS)
                || readNonBlankString(data.get("workProcessId")) == null
                || readNonBlankString(data.get("processName")) == null
                || readNonBlankString(data.get("inputUnit")) == null
                || readNonBlankString(data.get("outputUnit")) == null
                || !(data.get("reportingRequired") instanceof Boolean)
                || !(data.get("ports") instanceof List<?> ports)
                || !(data.get("conversionRule") instanceof Map<?, ?> conversionRule)
                || !isOptionalNullableString(data, "processCategory")
                || !isOptionalFiniteNumber(data, "standardTime")
                || !isOptionalBoolean(data, "allowMultipleUpstreamSources")
                || !isOptionalBoolean(data, "allowFinishedGoodsSource")) return null;
        List<Map<String, Object>> sanitizedPorts = sanitizePorts(ports);
        Map<String, Object> sanitizedRule = sanitizeConversionRule(conversionRule);
        if (sanitizedPorts == null || sanitizedRule == null) return null;
        Map<String, Object> result = copyKnownValues(data, PROCESS_DATA_KEYS);
        result.put("ports", sanitizedPorts);
        result.put("conversionRule", sanitizedRule);
        return result;
    }

    private List<Map<String, Object>> sanitizePorts(List<?> ports) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object rawPort : ports) {
            if (!(rawPort instanceof Map<?, ?> port) || !hasAllowedKeys(port, PORT_KEYS)) return null;
            String id = readNonBlankString(port.get("id"));
            String direction = readNonBlankString(port.get("direction"));
            String unit = readNonBlankString(port.get("unit"));
            if (id == null || !("INPUT".equals(direction) || "OUTPUT".equals(direction))
                    || unit == null || !(port.get("ordinal") instanceof Number ordinal)
                    || !isFiniteNumber(ordinal)
                    || ordinal.doubleValue() < 0 || ordinal.doubleValue() != Math.rint(ordinal.doubleValue())
                    || !isOptionalString(port, "materialNodeId")
                    || !isOptionalString(port, "materialName")
                    || !isOptionalString(port, "skuId")) return null;
            Object materialKind = port.get("materialKind");
            if (materialKind != null && (!(materialKind instanceof String kind)
                    || !MATERIAL_NODE_KINDS.contains(kind))) return null;
            result.add(copyKnownValues(port, PORT_KEYS));
        }
        return List.copyOf(result);
    }

    private Map<String, Object> sanitizeConversionRule(Map<?, ?> rule) {
        if (!hasAllowedKeys(rule, Set.of("mode", "expression"))) return null;
        String mode = readNonBlankString(rule.get("mode"));
        if (mode == null || !CONVERSION_MODES.contains(mode)) return null;
        Object expression = rule.get("expression");
        if (expression != null && !(expression instanceof String)) return null;
        return copyKnownValues(rule, Set.of("mode", "expression"));
    }

    private Map<String, Object> sanitizeEdgePatch(Map<?, ?> patch) {
        if (!hasExactKeys(patch, Set.of("op", "edge")) || !(patch.get("edge") instanceof Map<?, ?> edge)
                || !hasExactKeys(edge, Set.of("id", "source", "sourceHandle", "target", "targetHandle"))) return null;
        Map<String, Object> sanitizedEdge = new LinkedHashMap<>();
        for (String key : List.of("id", "source", "sourceHandle", "target", "targetHandle")) {
            String value = readNonBlankString(edge.get(key));
            if (value == null) return null;
            sanitizedEdge.put(key, value);
        }
        return linkedMap("op", "UPSERT_EDGE", "edge", sanitizedEdge);
    }

    private Map<String, Object> sanitizeIdPatch(Map<?, ?> patch, String idKey) {
        if (!hasExactKeys(patch, Set.of("op", idKey))) return null;
        String id = readNonBlankString(patch.get(idKey));
        return id == null ? null : linkedMap("op", patch.get("op"), idKey, id);
    }

    private Map<String, Object> sanitizeFieldPatch(Map<?, ?> patch) {
        if (!hasExactKeys(patch, Set.of("op", "nodeId", "path", "value"))) return null;
        String nodeId = readNonBlankString(patch.get("nodeId"));
        String path = readNonBlankString(patch.get("path"));
        if (nodeId == null || !isAllowedFieldPath(path) || !isAllowedFieldValue(path, patch.get("value"))) return null;
        return linkedMap("op", "SET_NODE_FIELD", "nodeId", nodeId, "path", path, "value", patch.get("value"));
    }

    /**
     * 一行调料/辅料投入的清洗(阶段 4)。
     *
     * 约束 2「数值域」在这里落地: **越界一律拒绝, 不截断**。
     * 截断会把「AI 说 5000 克/kg」悄悄变成「100 克/kg」并当成用户意图存下去 ——
     * 那比报错危险得多(扣料与成本都跟着错, 而且没有任何痕迹)。
     *
     * 约束 3「AI 不许凭空造物料」: materialTypeId 必填且非空; 它是否属于本工厂由
     * 应用阶段核对(见 applyMaterialBindingPatch), 因为那里才拿得到 factoryId 上下文。
     */
    private Map<String, Object> sanitizeMaterialBinding(Map<?, ?> binding) {
        if (!hasAllowedKeys(binding, MATERIAL_BINDING_KEYS)
                || readNonBlankString(binding.get("materialTypeId")) == null
                || !isOptionalString(binding, "materialName")
                || !isOptionalString(binding, "unit")) return null;

        Object dosage = binding.get("dosagePerKgG");
        if (dosage != null) {
            // 用量必须 > 0。0 不是「没配」, 是「配了个错的」——0 克/kg 会让这行在扣料时静默无效。
            if (!(dosage instanceof Number n) || !isFiniteNumber(n) || n.doubleValue() <= 0d) return null;
        }
        Object potRatio = binding.get("subsequentPotRatio");
        if (potRatio != null) {
            // 锅序比例 0–100(百分比)。0 合法: 「后续锅不再加这味调料」是真实配置。
            if (!(potRatio instanceof Number n) || !isFiniteNumber(n)
                    || n.doubleValue() < 0d || n.doubleValue() > 100d) return null;
        }
        return copyKnownValues(binding, MATERIAL_BINDING_KEYS);
    }

    private Map<String, Object> sanitizeMaterialBindingPatch(Map<?, ?> patch) {
        if (!hasExactKeys(patch, Set.of("op", "nodeId", "binding"))) return null;
        String nodeId = readNonBlankString(patch.get("nodeId"));
        if (nodeId == null || !(patch.get("binding") instanceof Map<?, ?> binding)) return null;
        Map<String, Object> sanitized = sanitizeMaterialBinding(binding);
        if (sanitized == null) return null;
        return linkedMap("op", "UPSERT_MATERIAL_BINDING", "nodeId", nodeId, "binding", sanitized);
    }

    private Map<String, Object> sanitizeRemoveMaterialBindingPatch(Map<?, ?> patch) {
        if (!hasExactKeys(patch, Set.of("op", "nodeId", "materialTypeId"))) return null;
        String nodeId = readNonBlankString(patch.get("nodeId"));
        String materialTypeId = readNonBlankString(patch.get("materialTypeId"));
        if (nodeId == null || materialTypeId == null) return null;
        return linkedMap("op", "REMOVE_MATERIAL_BINDING", "nodeId", nodeId,
                "materialTypeId", materialTypeId);
    }

    private boolean isAllowedFieldPath(String path) {
        if (path == null || !SAFE_FIELD_PATH.matcher(path).matches()) return false;
        String root = path.split("\\.", 2)[0];
        if (!ALLOWED_FIELD_ROOTS.contains(root)) return false;
        return switch (root) {
            case "conversionRule" -> Set.of("conversionRule", "conversionRule.mode", "conversionRule.expression").contains(path);
            case "ports" -> "ports".equals(path);
            // materialBindings 整体替换走 SET_NODE_FIELD; 单行增删走
            // UPSERT/REMOVE_MATERIAL_BINDING(更小的爆炸半径, AI 不必重发整张表)。
            case "materialBindings" -> "materialBindings".equals(path);
            default -> !path.contains(".");
        };
    }

    private List<Map<String, Object>> sanitizeMaterialBindings(List<?> bindings) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object raw : bindings) {
            if (!(raw instanceof Map<?, ?> binding)) return null;
            Map<String, Object> sanitized = sanitizeMaterialBinding(binding);
            if (sanitized == null) return null;
            result.add(sanitized);
        }
        return List.copyOf(result);
    }

    private boolean isAllowedFieldValue(String path, Object value) {
        return switch (path) {
            case "name" -> readNonBlankString(value) != null;
            case "skuId" -> value instanceof String;
            case "skuCode", "specification", "conversionRule.expression" ->
                    value == null || value instanceof String;
            case "reportingRequired" -> value instanceof Boolean;
            case "conversionRule.mode" -> value instanceof String mode && CONVERSION_MODES.contains(mode);
            case "conversionRule" -> value instanceof Map<?, ?> rule && sanitizeConversionRule(rule) != null;
            case "ports" -> value instanceof List<?> ports && sanitizePorts(ports) != null;
            case "isByproduct" -> value instanceof Boolean;
            case "materialBindings" -> value instanceof List<?> bindings && sanitizeMaterialBindings(bindings) != null;
            // 注射量 > 0。同 dosagePerKgG: 0 不是「不注射」, 不注射就不该是注射类工序。
            case "injectionAmount" -> value instanceof Number n && isFiniteNumber(n) && n.doubleValue() > 0d;
            default -> false;
        };
    }

    private boolean hasExactKeys(Map<?, ?> value, Set<String> keys) {
        return value.size() == keys.size() && hasAllowedKeys(value, keys);
    }

    private boolean hasAllowedKeys(Map<?, ?> value, Set<String> keys) {
        return value.keySet().stream().allMatch(key -> key instanceof String && keys.contains(key));
    }

    private Map<String, Object> copyKnownValues(Map<?, ?> source, Set<String> allowedKeys) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : allowedKeys) if (source.containsKey(key)) result.put(key, source.get(key));
        return result;
    }

    private String readNonBlankString(Object value) {
        return value instanceof String string && !string.isBlank() ? string : null;
    }

    private boolean isFiniteNumber(Object value) {
        return value instanceof Number number && Double.isFinite(number.doubleValue());
    }

    private boolean isOptionalString(Map<?, ?> value, String key) {
        return !value.containsKey(key) || value.get(key) instanceof String;
    }

    private boolean isOptionalNullableString(Map<?, ?> value, String key) {
        return !value.containsKey(key) || value.get(key) == null || value.get(key) instanceof String;
    }

    private boolean isOptionalBoolean(Map<?, ?> value, String key) {
        return !value.containsKey(key) || value.get(key) instanceof Boolean;
    }

    private boolean isOptionalFiniteNumber(Map<?, ?> value, String key) {
        return !value.containsKey(key) || value.get(key) == null || isFiniteNumber(value.get(key));
    }

    private void applyCandidateBatch(
            ProductProcessWorkflowDTO candidate,
            List<Map<String, Object>> patches) {
        patches.stream()
                .filter(patch -> Set.of("UPSERT_NODE", "REMOVE_NODE").contains(patch.get("op")))
                .forEach(patch -> applyNodePatch(candidate, patch));
        patches.stream()
                .filter(patch -> "SET_NODE_FIELD".equals(patch.get("op")))
                .forEach(patch -> applyFieldPatch(candidate, patch));
        patches.stream()
                .filter(patch -> Set.of("UPSERT_EDGE", "REMOVE_EDGE").contains(patch.get("op")))
                .forEach(patch -> applyEdgePatch(candidate, patch));
        patches.stream()
                .filter(patch -> String.valueOf(patch.get("op")).endsWith("_MATERIAL_BINDING"))
                .forEach(patch -> applyMaterialBindingPatch(candidate, patch));
    }

    private void applyNodePatch(
            ProductProcessWorkflowDTO candidate,
            Map<String, Object> patch) {
        if ("REMOVE_NODE".equals(patch.get("op"))) {
            String nodeId = String.valueOf(patch.get("nodeId"));
            boolean removed = candidate.getNodes().removeIf(node -> nodeId.equals(node.getId()));
            if (!removed) throw new PatchRejectedException();
            candidate.getEdges().removeIf(edge ->
                    nodeId.equals(edge.getSource()) || nodeId.equals(edge.getTarget()));
            return;
        }
        ProductProcessWorkflowDTO.Node next = objectMapper.convertValue(
                patch.get("node"), ProductProcessWorkflowDTO.Node.class);
        ProductProcessWorkflowDTO.Node existing = findNode(candidate, next.getId());
        if (existing != null && !Objects.equals(existing.getKind(), next.getKind())) {
            throw new PatchRejectedException();
        }
        if (existing == null) candidate.getNodes().add(next);
        else candidate.getNodes().set(candidate.getNodes().indexOf(existing), next);
    }

    private void applyFieldPatch(
            ProductProcessWorkflowDTO candidate,
            Map<String, Object> patch) {
        ProductProcessWorkflowDTO.Node target = findNode(candidate, String.valueOf(patch.get("nodeId")));
        if (target == null) throw new PatchRejectedException();
        String path = String.valueOf(patch.get("path"));
        if (!isPathCompatibleWithNodeKind(target.getKind(), path)) {
            throw new PatchRejectedException();
        }
        // 约束 1: 注射量只允许出现在注射类工序上
        if ("injectionAmount".equals(path)) {
            assertSeasoningCategoryAllows(target, "injectionAmount", INJECTION_CATEGORIES, "注射量");
        }
        // 整表替换同样要过类别闸 —— 否则 AI 绕开单行动作就能把锅序比例塞进任意工序
        if ("materialBindings".equals(path) && patch.get("value") instanceof List<?> rows) {
            boolean hasPotRatio = rows.stream().anyMatch(row ->
                    row instanceof Map<?, ?> map && map.get("subsequentPotRatio") != null);
            if (hasPotRatio) {
                assertSeasoningCategoryAllows(target, "subsequentPotRatio", POT_RATIO_CATEGORIES, "后续锅调料比例");
            }
        }
        Map<String, Object> data = target.getData();
        if (data == null) {
            data = new LinkedHashMap<>();
            target.setData(data);
        }
        Object value = patch.get("value");
        if (path.startsWith("conversionRule.")) {
            Object existingRule = data.get("conversionRule");
            Map<String, Object> rule = existingRule instanceof Map<?, ?> source
                    ? copyStringObjectMap(source)
                    : new LinkedHashMap<>();
            rule.put(path.substring("conversionRule.".length()), value);
            data.put("conversionRule", rule);
        } else {
            data.put(path, value);
        }
    }

    private void applyEdgePatch(
            ProductProcessWorkflowDTO candidate,
            Map<String, Object> patch) {
        if ("REMOVE_EDGE".equals(patch.get("op"))) {
            String edgeId = String.valueOf(patch.get("edgeId"));
            if (!candidate.getEdges().removeIf(edge -> edgeId.equals(edge.getId()))) {
                throw new PatchRejectedException();
            }
            return;
        }
        ProductProcessWorkflowDTO.Edge next = objectMapper.convertValue(
                patch.get("edge"), ProductProcessWorkflowDTO.Edge.class);
        ProductProcessWorkflowDTO.Edge existing = candidate.getEdges().stream()
                .filter(edge -> next.getId().equals(edge.getId()))
                .findFirst()
                .orElse(null);
        if (existing == null) candidate.getEdges().add(next);
        else candidate.getEdges().set(candidate.getEdges().indexOf(existing), next);
    }

    private boolean isPathCompatibleWithNodeKind(String kind, String path) {
        if ("PROCESS".equals(kind)) {
            return path.equals("ports")
                    || path.equals("conversionRule")
                    || path.startsWith("conversionRule.")
                    || path.equals("reportingRequired")
                    // 阶段 4: 调料投入与注射量挂在工序上
                    || path.equals("materialBindings")
                    || path.equals("injectionAmount");
        }
        return MATERIAL_NODE_KINDS.contains(kind)
                && Set.of("name", "skuId", "skuCode", "specification", "isByproduct").contains(path);
    }

    /**
     * 约束 1「类别闸」—— 往错类别的工序写调味参数一律拒绝, 并给出可读原因。
     *
     * 为什么必须在**应用阶段**而不是 sanitize 阶段判: sanitize 只看补丁本身,
     * 拿不到"目标工序是什么类别"。类别在图里, 只有把补丁对上节点才知道。
     *
     * ⛔ 判据用 SeasoningProcessCategory 常量, 不写裸字符串"熟制"/"注射"。
     */
    private void assertSeasoningCategoryAllows(
            ProductProcessWorkflowDTO.Node target,
            String parameter,
            Set<String> allowedCategories,
            String humanParameterName) {
        Map<String, Object> data = target.getData();
        Object category = data == null ? null : data.get("processCategory");
        String categoryText = category instanceof String text ? text.trim() : "";
        if (allowedCategories.contains(categoryText)) return;
        String processName = data == null ? null : readNonBlankString(data.get("processName"));
        throw new PatchRejectedException(String.format(
                "工序「%s」的类别是「%s」，不能配置%s —— %s只在%s类工序上有意义。"
                        + "如果这道工序确实需要，请先把工序类别改成%s。",
                processName == null ? target.getId() : processName,
                categoryText.isEmpty() ? "未设置" : categoryText,
                humanParameterName,
                humanParameterName,
                String.join("/", allowedCategories),
                String.join("/", allowedCategories)));
    }

    /**
     * 增删一行调料/辅料投入。
     *
     * 单行操作而不是整表替换 —— 爆炸半径更小: AI 想改一味调料的克数, 不必重发整张表,
     * 也就不会在重发时把它没提到的行悄悄丢掉。
     */
    private void applyMaterialBindingPatch(
            ProductProcessWorkflowDTO candidate,
            Map<String, Object> patch) {
        ProductProcessWorkflowDTO.Node target = findNode(candidate, String.valueOf(patch.get("nodeId")));
        if (target == null || !"PROCESS".equals(target.getKind())) {
            throw new PatchRejectedException();
        }
        Map<String, Object> data = target.getData();
        if (data == null) {
            data = new LinkedHashMap<>();
            target.setData(data);
        }
        List<Map<String, Object>> bindings = new ArrayList<>();
        if (data.get("materialBindings") instanceof List<?> existing) {
            for (Object row : existing) {
                if (row instanceof Map<?, ?> map) bindings.add(copyStringObjectMap(map));
            }
        }

        if ("REMOVE_MATERIAL_BINDING".equals(patch.get("op"))) {
            String materialTypeId = String.valueOf(patch.get("materialTypeId"));
            if (!bindings.removeIf(row -> materialTypeId.equals(String.valueOf(row.get("materialTypeId"))))) {
                throw new PatchRejectedException();
            }
            data.put("materialBindings", bindings);
            return;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> binding = (Map<String, Object>) patch.get("binding");
        // 约束 1: 锅序比例只允许出现在熟制类工序上
        if (binding.get("subsequentPotRatio") != null) {
            assertSeasoningCategoryAllows(target, "subsequentPotRatio", POT_RATIO_CATEGORIES, "后续锅调料比例");
        }
        String materialTypeId = String.valueOf(binding.get("materialTypeId"));
        int at = -1;
        for (int index = 0; index < bindings.size(); index++) {
            if (materialTypeId.equals(String.valueOf(bindings.get(index).get("materialTypeId")))) {
                at = index;
                break;
            }
        }
        if (at >= 0) bindings.set(at, binding);
        else bindings.add(binding);
        data.put("materialBindings", bindings);
    }

    private ProductProcessWorkflowDTO.Node findNode(
            ProductProcessWorkflowDTO definition,
            String nodeId) {
        return definition.getNodes().stream()
                .filter(node -> nodeId.equals(node.getId()))
                .findFirst()
                .orElse(null);
    }

    private Map<String, Object> copyStringObjectMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key instanceof String stringKey) result.put(stringKey, value);
        });
        return result;
    }

    /** 业务性拒绝带具体原因; 其余用通用文案(不把内部路径名/id 规则漏给用户)。 */
    private String rejectionMessage(Throwable error) {
        if (error instanceof PatchRejectedException rejected && rejected.readableReason() != null) {
            return rejected.readableReason();
        }
        return "Workflow patch batch rejected";
    }

    private String buildSemanticError(String errorCode, String message) {
        try {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("success", false);
            error.put("errorCode", errorCode);
            error.put("error", message);
            return objectMapper.writeValueAsString(error);
        } catch (Exception serializationError) {
            return "{\"success\":false,\"errorCode\":\"WORKFLOW_PATCH_REJECTED\","
                    + "\"error\":\"Workflow patch batch rejected\"}";
        }
    }

    /**
     * 补丁被拒。
     *
     * `reason` 是**给用户看的中文原因**(阶段 4 约束 1 要求「拒绝并给可读原因」)。
     * 空 reason = 结构性拒绝(路径不合法 / 节点不存在这类), 沿用通用文案;
     * 有 reason = 业务性拒绝(往错类别的工序写参数), 必须把原因带到界面 ——
     * 否则 AI 改不动却不说为什么, 用户只会反复重试同一句话。
     */
    private static final class PatchRejectedException extends RuntimeException {
        private final transient String reason;

        PatchRejectedException() {
            this(null);
        }

        PatchRejectedException(String reason) {
            super(reason);
            this.reason = reason;
        }

        String readableReason() {
            return reason;
        }
    }

    private Map<String, Object> linkedMap(Object... keyValues) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < keyValues.length; index += 2) {
            result.put(String.valueOf(keyValues[index]), keyValues[index + 1]);
        }
        return result;
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
