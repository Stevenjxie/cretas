package com.cretas.aims.ai.tool.impl.sop;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.dto.ChatCompletionRequest;
import com.cretas.aims.ai.dto.ChatCompletionResponse;
import com.cretas.aims.ai.dto.ChatMessage;
import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.config.DashScopeConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URL;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SOP 文档解析工具
 *
 * <p>解析 SOP 文档(PDF/Excel/图片)，提取工序步骤、时间要求、技能要求等信息。
 *
 * <p>支持的文件类型:
 * <ul>
 *   <li>PDF: 使用文本提取（通过 AI 解析），针对中文PDF优化</li>
 *   <li>Excel: 直接解析表格结构</li>
 *   <li>图片: 调用 OCR（DashScope Vision）</li>
 * </ul>
 *
 * <p>中文PDF优化:
 * <ul>
 *   <li>自动检测中文字符提取质量</li>
 *   <li>当文本提取失败或乱码时，自动切换到Vision AI OCR</li>
 *   <li>支持扫描件PDF的OCR识别</li>
 * </ul>
 *
 * @author Cretas Team
 * @version 1.1.0
 * @since 2026-01-21
 */
@Slf4j
@Component
public class SopParseDocumentTool extends AbstractBusinessTool {

    @Autowired
    @Lazy
    private DashScopeClient dashScopeClient;

    @Autowired
    @Lazy
    private DashScopeConfig dashScopeConfig;

    // 中文字符范围的正则表达式
    private static final Pattern CHINESE_CHAR_PATTERN = Pattern.compile("[\\u4e00-\\u9fff]");
    // 常见乱码字符模式
    private static final Pattern GARBLED_PATTERN = Pattern.compile("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f]|[\ufffd]{2,}");

    @Override
    public String getToolName() {
        return "sop_parse_document";
    }

    @Override
    public String getDescription() {
        return "解析SOP文档(PDF/Excel/图片)，提取工序步骤、时间要求、技能要求等信息。" +
               "支持自动识别文件类型并选择相应的解析策略。针对中文PDF进行了特别优化，" +
               "当文本提取失败时会自动切换到OCR模式。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // fileUrl: 文件URL（必需）
        Map<String, Object> fileUrl = new HashMap<>();
        fileUrl.put("type", "string");
        fileUrl.put("description", "SOP文档的URL地址，支持PDF、Excel、图片格式");
        properties.put("fileUrl", fileUrl);

        // fileType: 文件类型（可选，自动检测）
        Map<String, Object> fileType = new HashMap<>();
        fileType.put("type", "string");
        fileType.put("description", "文件类型: PDF, EXCEL, IMAGE。如不提供则自动检测。");
        fileType.put("enum", Arrays.asList("PDF", "EXCEL", "IMAGE"));
        properties.put("fileType", fileType);

        // forceOcr: 强制使用OCR（可选）
        Map<String, Object> forceOcr = new HashMap<>();
        forceOcr.put("type", "boolean");
        forceOcr.put("description", "是否强制使用Vision AI OCR解析PDF，适用于扫描件");
        properties.put("forceOcr", forceOcr);

        schema.put("properties", properties);
        schema.put("required", Collections.singletonList("fileUrl"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.singletonList("fileUrl");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        String fileUrl = getString(params, "fileUrl");
        String fileType = getString(params, "fileType");
        Boolean forceOcr = getBoolean(params, "forceOcr", false);

        // 自动检测文件类型
        if (fileType == null || fileType.isEmpty()) {
            fileType = detectFileType(fileUrl);
        }

        log.info("开始解析SOP文档: factoryId={}, fileUrl={}, fileType={}, forceOcr={}",
                factoryId, fileUrl, fileType, forceOcr);

        // 根据文件类型选择解析策略
        SopParseResult result;
        switch (fileType.toUpperCase()) {
            case "PDF":
                if (forceOcr) {
                    result = parsePdfWithVision(fileUrl, "用户请求强制使用OCR");
                } else {
                    result = parsePdfDocument(fileUrl);
                }
                break;
            case "EXCEL":
            case "XLS":
            case "XLSX":
                result = parseExcelDocument(fileUrl);
                break;
            case "IMAGE":
            case "PNG":
            case "JPG":
            case "JPEG":
                result = parseImageDocument(fileUrl);
                break;
            default:
                throw new IllegalArgumentException("不支持的文件类型: " + fileType);
        }

        log.info("SOP文档解析完成: 识别到 {} 个工序步骤, 解析方式: {}",
                result.getSteps().size(), result.getParseMethod());

        // 构建返回结果
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("fileUrl", fileUrl);
        response.put("fileType", fileType);
        response.put("content", result.getContent());
        response.put("steps", result.getSteps());
        response.put("stepCount", result.getSteps().size());
        response.put("metadata", result.getMetadata());
        response.put("parseMethod", result.getParseMethod());
        response.put("message", String.format("成功解析SOP文档，识别到 %d 个工序步骤 (方式: %s)",
                result.getSteps().size(), result.getParseMethod()));

        return response;
    }

    /**
     * 获取布尔类型参数
     */
    @Override
    protected Boolean getBoolean(Map<String, Object> params, String key, Boolean defaultValue) {
        Object value = params.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof String) return Boolean.parseBoolean((String) value);
        return defaultValue;
    }

    /**
     * 检测文件类型
     */
    private String detectFileType(String fileUrl) {
        String url = fileUrl.toLowerCase();
        if (url.endsWith(".pdf")) {
            return "PDF";
        } else if (url.endsWith(".xlsx") || url.endsWith(".xls")) {
            return "EXCEL";
        } else if (url.endsWith(".png") || url.endsWith(".jpg") || url.endsWith(".jpeg")) {
            return "IMAGE";
        }

        // 尝试从Content-Type判断
        log.warn("无法从URL后缀判断文件类型，默认使用PDF: {}", fileUrl);
        return "PDF";
    }

    /**
     * 解析PDF文档
     * 1. 从URL下载PDF
     * 2. 使用PDFBox提取文本
     * 3. 检测中文提取质量，必要时使用Vision AI作为备选
     * 4. 发送文本给AI进行结构化解析
     */
    private SopParseResult parsePdfDocument(String fileUrl) {
        // 1. 下载并提取PDF文本
        String pdfText;
        try {
            pdfText = extractPdfText(fileUrl);
            log.info("PDF文本提取完成，共 {} 个字符", pdfText.length());
        } catch (Exception e) {
            log.error("PDF文本提取失败: {}", e.getMessage(), e);
            // 尝试使用Vision AI作为备选方案
            log.info("尝试使用Vision AI解析PDF...");
            return parsePdfWithVision(fileUrl, "PDF文本提取失败: " + e.getMessage());
        }

        // 如果提取到的文本为空或太短，尝试Vision AI
        if (pdfText == null || pdfText.trim().length() < 10) {
            log.warn("PDF文本内容为空或太短，尝试使用Vision AI");
            return parsePdfWithVision(fileUrl, "PDF文本内容为空或无法提取");
        }

        // 2. 检测中文提取质量
        ChineseExtractionQuality quality = assessChineseExtractionQuality(pdfText);
        log.info("中文提取质量评估: score={}, chineseRatio={}, hasGarbled={}",
                quality.score, quality.chineseCharRatio, quality.hasGarbledChars);

        // 如果中文提取质量差（乱码或无中文），使用Vision AI
        if (quality.score < 0.3) {
            log.warn("中文PDF提取质量差(score={}), 切换到Vision AI", quality.score);
            return parsePdfWithVision(fileUrl, "中文字符提取质量差，使用OCR替代");
        }

        // 3. 使用AI来解析提取的文本内容
        String systemPrompt = """
            你是一个SOP文档解析专家。请分析提供的SOP文档内容，提取以下信息：
            1. 工序步骤列表（按顺序）
            2. 每个步骤的时间要求
            3. 每个步骤的技能要求
            4. 质检点
            5. 特殊设备要求

            请以JSON格式输出：
            {
                "steps": [
                    {
                        "orderIndex": 1,
                        "name": "步骤名称",
                        "description": "步骤描述",
                        "timeLimitMinutes": 10,
                        "skillLevel": 2,
                        "isQualityCheckpoint": false,
                        "equipmentRequired": []
                    }
                ],
                "metadata": {
                    "productName": "产品名称",
                    "totalEstimatedMinutes": 60,
                    "specialNotes": "特殊注意事项"
                }
            }

            仅返回JSON，不要包含其他文字。
            """;

        // 限制文本长度，避免超出token限制
        String truncatedText = pdfText.length() > 8000 ? pdfText.substring(0, 8000) + "\n...(内容已截断)" : pdfText;
        String userPrompt = "请解析以下SOP文档内容:\n\n" + truncatedText;

        try {
            String response = dashScopeClient.chat(systemPrompt, userPrompt);
            SopParseResult result = parseAiResponse(response, fileUrl);
            // 保存原始PDF文本
            result.setContent(pdfText);
            result.setParseMethod("PDFBox文本提取");
            return result;
        } catch (Exception e) {
            log.error("AI解析PDF内容失败: {}", e.getMessage(), e);
            // 返回原始文本，但没有结构化的步骤
            SopParseResult result = createEmptyResult(fileUrl, "AI解析失败: " + e.getMessage());
            result.setContent(pdfText);
            result.setParseMethod("PDFBox文本提取(AI解析失败)");
            return result;
        }
    }

    /**
     * 评估中文提取质量
     * 用于判断是否需要切换到Vision AI OCR
     */
    private ChineseExtractionQuality assessChineseExtractionQuality(String text) {
        ChineseExtractionQuality quality = new ChineseExtractionQuality();

        if (text == null || text.isEmpty()) {
            quality.score = 0;
            return quality;
        }

        // 统计中文字符数量
        Matcher chineseMatcher = CHINESE_CHAR_PATTERN.matcher(text);
        int chineseCount = 0;
        while (chineseMatcher.find()) {
            chineseCount++;
        }

        // 统计总可见字符数（排除空白）
        int totalVisibleChars = text.replaceAll("\\s", "").length();

        // 计算中文字符比例
        quality.chineseCharRatio = totalVisibleChars > 0 ?
                (double) chineseCount / totalVisibleChars : 0;

        // 检测乱码字符
        Matcher garbledMatcher = GARBLED_PATTERN.matcher(text);
        quality.hasGarbledChars = garbledMatcher.find();

        // 检测是否有大量连续的问号或替换字符（乱码的典型特征）
        int replacementCharCount = (int) text.chars().filter(c -> c == '\ufffd' || c == '?').count();
        double replacementRatio = totalVisibleChars > 0 ?
                (double) replacementCharCount / totalVisibleChars : 0;

        // 计算综合得分
        if (quality.hasGarbledChars || replacementRatio > 0.1) {
            quality.score = 0.1; // 有明显乱码
        } else if (quality.chineseCharRatio > 0.1) {
            // 预期是中文文档，中文比例越高得分越高
            quality.score = Math.min(1.0, quality.chineseCharRatio * 2);
        } else {
            // 可能是英文或中英混合文档，给予中等得分
            quality.score = 0.7;
        }

        // 额外检查：如果有大量不可打印字符，降低得分
        int unprintableCount = (int) text.chars()
                .filter(c -> c < 32 && c != '\n' && c != '\r' && c != '\t')
                .count();
        if (unprintableCount > totalVisibleChars * 0.05) {
            quality.score *= 0.5;
        }

        return quality;
    }

    /**
     * 使用Vision AI解析PDF（作为备选方案）
     * 将PDF URL直接传递给Vision模型进行OCR识别
     */
    private SopParseResult parsePdfWithVision(String fileUrl, String reason) {
        log.info("使用Vision AI解析PDF: url={}, reason={}", fileUrl, reason);

        String prompt = """
            你是一个SOP文档OCR和解析专家。请识别并分析这个PDF文档的内容，提取以下信息：
            1. 工序步骤列表（按顺序）
            2. 每个步骤的时间要求
            3. 每个步骤的技能要求
            4. 质检点
            5. 特殊设备要求

            请以JSON格式输出：
            {
                "content": "识别到的文档原始内容摘要",
                "steps": [
                    {
                        "orderIndex": 1,
                        "name": "步骤名称",
                        "description": "步骤描述",
                        "timeLimitMinutes": 10,
                        "skillLevel": 2,
                        "isQualityCheckpoint": false,
                        "equipmentRequired": []
                    }
                ],
                "metadata": {
                    "productName": "产品名称",
                    "totalEstimatedMinutes": 60,
                    "specialNotes": "特殊注意事项"
                }
            }

            仅返回JSON，不要包含其他文字。
            """;

        try {
            // 使用 Vision 模型分析PDF
            ChatCompletionRequest request = ChatCompletionRequest.builder()
                    .model(dashScopeConfig.getVisionModel())
                    .messages(List.of(ChatMessage.userWithImageUrl(prompt, fileUrl)))
                    .maxTokens(4000)
                    .temperature(0.3)
                    .build();

            ChatCompletionResponse response = dashScopeClient.chatCompletion(request);

            if (response.hasError()) {
                log.error("Vision API 调用失败: {}", response.getErrorMessage());
                return createEmptyResult(fileUrl, "Vision API 调用失败: " + response.getErrorMessage());
            }

            SopParseResult result = parseAiResponse(response.getContent(), fileUrl);
            result.setParseMethod("Vision AI OCR (" + reason + ")");

            // 尝试从响应中提取content字段
            try {
                String jsonStr = extractJson(response.getContent());
                if (jsonStr != null) {
                    Map<String, Object> parsed = objectMapper.readValue(jsonStr,
                            new TypeReference<Map<String, Object>>() {});
                    if (parsed.containsKey("content")) {
                        result.setContent((String) parsed.get("content"));
                    }
                }
            } catch (Exception e) {
                // 如果解析失败，使用原始响应作为content
                if (result.getContent() == null || result.getContent().isEmpty()) {
                    result.setContent(response.getContent());
                }
            }

            return result;
        } catch (Exception e) {
            log.error("Vision AI解析PDF失败: {}", e.getMessage(), e);
            return createEmptyResult(fileUrl, "Vision AI解析失败: " + e.getMessage());
        }
    }

    /**
     * 从URL下载PDF并提取文本
     * 针对中文PDF进行了优化配置
     */
    private String extractPdfText(String fileUrl) throws Exception {
        log.info("开始下载PDF: {}", fileUrl);

        try (InputStream is = new URL(fileUrl).openStream();
             PDDocument document = PDDocument.load(is)) {

            PDFTextStripper stripper = new PDFTextStripper();

            // 中文PDF优化配置
            stripper.setSortByPosition(true);  // 按位置排序，更好地保持中文阅读顺序
            stripper.setAddMoreFormatting(true);  // 添加更多格式化，保持段落结构
            stripper.setSpacingTolerance(0.5f);  // 调整字符间距容差
            stripper.setAverageCharTolerance(0.3f);  // 调整平均字符容差

            String text = stripper.getText(document);
            log.info("PDF解析成功，页数: {}, 文本长度: {}", document.getNumberOfPages(), text.length());

            return text;
        }
    }

    /**
     * 解析Excel文档
     */
    private SopParseResult parseExcelDocument(String fileUrl) {
        SopParseResult result = new SopParseResult();
        result.setContent("");
        result.setSteps(new ArrayList<>());
        result.setMetadata(new HashMap<>());
        result.setParseMethod("Excel直接解析");

        try (InputStream is = new URL(fileUrl).openStream();
             Workbook workbook = new XSSFWorkbook(is)) {

            Sheet sheet = workbook.getSheetAt(0);
            StringBuilder content = new StringBuilder();
            List<Map<String, Object>> steps = new ArrayList<>();

            // 解析表头
            Row headerRow = sheet.getRow(0);
            List<String> headers = new ArrayList<>();
            if (headerRow != null) {
                for (Cell cell : headerRow) {
                    headers.add(getCellValueAsString(cell));
                }
            }

            // 解析数据行
            int orderIndex = 1;
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                Map<String, Object> step = new LinkedHashMap<>();
                step.put("orderIndex", orderIndex++);

                StringBuilder rowContent = new StringBuilder();
                for (int j = 0; j < headers.size() && j < row.getLastCellNum(); j++) {
                    Cell cell = row.getCell(j);
                    String value = getCellValueAsString(cell);
                    String header = headers.get(j).toLowerCase();

                    // 映射常见列名
                    if (header.contains("步骤") || header.contains("name") || header.contains("工序")) {
                        step.put("name", value);
                    } else if (header.contains("描述") || header.contains("description")) {
                        step.put("description", value);
                    } else if (header.contains("时间") || header.contains("time") || header.contains("分钟")) {
                        step.put("timeLimitMinutes", parseMinutes(value));
                    } else if (header.contains("技能") || header.contains("skill") || header.contains("等级")) {
                        step.put("skillLevel", parseSkillLevel(value));
                    } else if (header.contains("质检") || header.contains("检查") || header.contains("quality")) {
                        step.put("isQualityCheckpoint", parseBoolean(value));
                    }

                    rowContent.append(value).append("\t");
                }

                // 确保步骤有名称
                if (!step.containsKey("name") || step.get("name") == null) {
                    Cell firstCell = row.getCell(0);
                    step.put("name", getCellValueAsString(firstCell));
                }

                if (step.get("name") != null && !step.get("name").toString().trim().isEmpty()) {
                    steps.add(step);
                }

                content.append(rowContent).append("\n");
            }

            result.setContent(content.toString());
            result.setSteps(steps);
            result.getMetadata().put("sheetName", sheet.getSheetName());
            result.getMetadata().put("totalRows", sheet.getLastRowNum());

        } catch (Exception e) {
            log.error("Excel解析失败: {}", e.getMessage(), e);
            return createEmptyResult(fileUrl, "Excel解析失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * 解析图片文档（通过Vision AI OCR）
     */
    private SopParseResult parseImageDocument(String fileUrl) {
        String prompt = """
            你是一个SOP文档OCR专家。请识别图片中的SOP文档内容，提取以下信息：
            1. 工序步骤列表（按顺序）
            2. 每个步骤的时间要求
            3. 每个步骤的技能要求
            4. 质检点
            5. 特殊设备要求

            请以JSON格式输出：
            {
                "steps": [
                    {
                        "orderIndex": 1,
                        "name": "步骤名称",
                        "description": "步骤描述",
                        "timeLimitMinutes": 10,
                        "skillLevel": 2,
                        "isQualityCheckpoint": false,
                        "equipmentRequired": []
                    }
                ],
                "metadata": {
                    "productName": "产品名称",
                    "totalEstimatedMinutes": 60,
                    "specialNotes": "特殊注意事项"
                }
            }

            仅返回 JSON，不要包含其他文字。
            """;

        try {
            // 使用 Vision 模型分析图片
            ChatCompletionRequest request = ChatCompletionRequest.builder()
                    .model(dashScopeConfig.getVisionModel())
                    .messages(List.of(ChatMessage.userWithImageUrl(prompt, fileUrl)))
                    .maxTokens(2000)
                    .temperature(0.3)
                    .build();

            ChatCompletionResponse response = dashScopeClient.chatCompletion(request);

            if (response.hasError()) {
                log.error("Vision API 调用失败: {}", response.getErrorMessage());
                return createEmptyResult(fileUrl, "Vision API 调用失败: " + response.getErrorMessage());
            }

            SopParseResult result = parseAiResponse(response.getContent(), fileUrl);
            result.setParseMethod("Vision AI OCR (图片)");
            return result;
        } catch (Exception e) {
            log.error("图片OCR解析失败: {}", e.getMessage(), e);
            return createEmptyResult(fileUrl, "图片OCR解析失败: " + e.getMessage());
        }
    }

    /**
     * 解析AI响应
     */
    private SopParseResult parseAiResponse(String response, String fileUrl) {
        SopParseResult result = new SopParseResult();
        result.setContent(response);
        result.setSteps(new ArrayList<>());
        result.setMetadata(new HashMap<>());

        try {
            // 提取JSON部分
            String jsonStr = extractJson(response);
            if (jsonStr != null) {
                Map<String, Object> parsed = objectMapper.readValue(jsonStr,
                        new TypeReference<Map<String, Object>>() {});

                if (parsed.containsKey("steps")) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> steps = (List<Map<String, Object>>) parsed.get("steps");
                    result.setSteps(steps);
                }

                if (parsed.containsKey("metadata")) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> metadata = (Map<String, Object>) parsed.get("metadata");
                    result.setMetadata(metadata);
                }
            }
        } catch (Exception e) {
            log.warn("AI响应解析失败，使用原始内容: {}", e.getMessage());
        }

        return result;
    }

    /**
     * 从文本中提取JSON
     */
    private String extractJson(String text) {
        // 尝试匹配 {...} 或 ```json ... ```
        Pattern jsonPattern = Pattern.compile("```json\\s*([\\s\\S]*?)\\s*```|\\{[\\s\\S]*\\}");
        Matcher matcher = jsonPattern.matcher(text);
        if (matcher.find()) {
            String match = matcher.group(1) != null ? matcher.group(1) : matcher.group();
            if (!match.startsWith("{")) {
                match = "{" + match + "}";
            }
            return match.trim();
        }
        return null;
    }

    /**
     * 获取单元格值为字符串
     */
    private String getCellValueAsString(Cell cell) {
        if (cell == null) return "";
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getLocalDateTimeCellValue().toString();
                }
                return String.valueOf((int) cell.getNumericCellValue());
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                try {
                    return cell.getStringCellValue();
                } catch (Exception e) {
                    return String.valueOf(cell.getNumericCellValue());
                }
            default:
                return "";
        }
    }

    /**
     * 解析分钟数
     */
    private Integer parseMinutes(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return Integer.parseInt(value.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 解析技能等级
     */
    private Integer parseSkillLevel(String value) {
        if (value == null || value.isEmpty()) return 1;
        try {
            int level = Integer.parseInt(value.replaceAll("[^0-9]", ""));
            return Math.min(Math.max(level, 1), 5);
        } catch (NumberFormatException e) {
            // 尝试解析文字描述
            if (value.contains("高级") || value.contains("专家")) return 5;
            if (value.contains("中级") || value.contains("熟练")) return 3;
            if (value.contains("初级") || value.contains("基础")) return 1;
            return 2;
        }
    }

    /**
     * 解析布尔值
     */
    private Boolean parseBoolean(String value) {
        if (value == null || value.isEmpty()) return false;
        return value.contains("是") || value.contains("yes") ||
               value.contains("true") || value.equals("1") ||
               value.contains("需要") || value.contains("必须");
    }

    /**
     * 创建空结果
     */
    private SopParseResult createEmptyResult(String fileUrl, String error) {
        SopParseResult result = new SopParseResult();
        result.setContent("");
        result.setSteps(new ArrayList<>());
        result.setMetadata(new HashMap<>());
        result.getMetadata().put("error", error);
        result.getMetadata().put("fileUrl", fileUrl);
        result.setParseMethod("解析失败");
        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("fileUrl".equals(paramName)) {
            return "请提供SOP文档的URL地址。";
        }
        return super.getParameterQuestion(paramName);
    }

    /**
     * 中文提取质量评估结果
     */
    private static class ChineseExtractionQuality {
        double score = 0;           // 综合得分 0-1
        double chineseCharRatio = 0; // 中文字符比例
        boolean hasGarbledChars = false; // 是否有乱码
    }

    /**
     * SOP解析结果内部类
     */
    private static class SopParseResult {
        private String content;
        private List<Map<String, Object>> steps;
        private Map<String, Object> metadata;
        private String parseMethod;  // 解析方法：PDFBox/Vision AI OCR

        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public List<Map<String, Object>> getSteps() { return steps; }
        public void setSteps(List<Map<String, Object>> steps) { this.steps = steps; }
        public Map<String, Object> getMetadata() { return metadata; }
        public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
        public String getParseMethod() { return parseMethod; }
        public void setParseMethod(String parseMethod) { this.parseMethod = parseMethod; }
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
