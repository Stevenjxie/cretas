package com.cretas.aims.service.productimport;

import com.cretas.aims.dto.producttype.importing.SkuImageMappingDTO;
import com.cretas.aims.dto.producttype.importing.SkuImportConfirmResultDTO;
import com.cretas.aims.dto.producttype.importing.SkuImportIssueDTO;
import com.cretas.aims.dto.producttype.importing.SkuImportPreviewDTO;
import com.cretas.aims.dto.producttype.importing.SkuImportPreviewRowDTO;
import com.cretas.aims.dto.producttype.ProductPackagingSpecDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.enums.ProductCategory;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.service.product.ProductPackagingSpecService;
import com.cretas.aims.service.unit.ProductSpecificationConversionSyncService;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitNormalizationResult;
import com.cretas.aims.service.unit.UnitDimension;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class SkuImportServiceImpl implements SkuImportService {

    static final List<String> HEADERS = List.of(
            "示例标记", "SKU编号*", "SKU名称*", "基本单位*", "标准克重(g)",
            "包装单位1", "每包装数量1", "包装单位2", "每包装数量2",
            "温区", "保质期(天)", "规格", "图片文件名", "备注");
    static final Map<String, String> SHEET_CATEGORIES = Map.of(
            "成品", ProductCategory.FINISHED_PRODUCT,
            "半成品", ProductCategory.SEMI_FINISHED,
            "客户自带原料加工", ProductCategory.CUSTOMER_MATERIAL,
            "纯代工", ProductCategory.CONTRACT_MANUFACTURING);
    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
    private static final int MAX_ROWS = 2000;
    private static final int MAX_TOKENS = 500;
    private static final Pattern SAFE_FACTORY_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Set<String> COUNT_UNITS = Set.of("盒", "袋", "件", "只", "瓶", "罐", "包", "桶", "箱", "个");

    private final ProductTypeRepository productTypeRepository;
    private final ObjectMapper objectMapper;
    private final ProductPackagingSpecService productPackagingSpecService;
    private final ProductSpecificationConversionSyncService specificationConversionSyncService;
    private final UnitContractService unitContractService;
    private final Map<String, PendingPreview> pendingPreviews = new ConcurrentHashMap<>();

    @Override
    public byte[] createTemplate() {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font bold = workbook.createFont();
            bold.setBold(true);
            headerStyle.setFont(bold);
            CellStyle exampleStyle = workbook.createCellStyle();
            exampleStyle.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
            exampleStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            CreationHelper helper = workbook.getCreationHelper();

            for (String sheetName : List.of("成品", "半成品", "客户自带原料加工", "纯代工")) {
                Sheet sheet = workbook.createSheet(sheetName);
                Row header = sheet.createRow(0);
                for (int i = 0; i < HEADERS.size(); i++) {
                    Cell cell = header.createCell(i);
                    cell.setCellValue(HEADERS.get(i));
                    cell.setCellStyle(headerStyle);
                    ClientAnchor anchor = helper.createClientAnchor();
                    anchor.setCol1(i);
                    anchor.setCol2(Math.min(i + 3, HEADERS.size()));
                    anchor.setRow1(0);
                    anchor.setRow2(4);
                    Comment comment = sheet.createDrawingPatriarch().createCellComment(anchor);
                    comment.setAuthor("Cretas");
                    comment.setString(helper.createRichTextString(headerHelp(i, sheetName)));
                    cell.setCellComment(comment);
                    sheet.setColumnWidth(i, i == 2 || i == 11 ? 24 * 256 : 16 * 256);
                }
                Row example = sheet.createRow(1);
                List<String> values = exampleValues(sheetName);
                for (int i = 0; i < values.size(); i++) {
                    Cell cell = example.createCell(i);
                    cell.setCellValue(values.get(i));
                    cell.setCellStyle(exampleStyle);
                }
                sheet.createFreezePane(0, 1);
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new BusinessException(500, "生成SKU导入模板失败", e);
        }
    }

    @Override
    public SkuImportPreviewDTO preview(String factoryId, Long userId, MultipartFile file, String imageMappingsJson) {
        validateUpload(factoryId, file);
        cleanupExpired();
        if (pendingPreviews.size() >= MAX_TOKENS) {
            throw new BusinessException(429, "导入预览过多，请稍后重试");
        }
        try {
            byte[] bytes = file.getBytes();
            String sha256 = sha256(bytes);
            List<SkuImageMappingDTO> mappings = parseImageMappings(factoryId, imageMappingsJson);
            ParseResult parsed = parseWorkbook(factoryId, bytes, mappings);
            String token = UUID.randomUUID().toString();
            boolean previewValid = parsed.errors().isEmpty()
                    && parsed.rows().stream().noneMatch(row -> "INVALID".equals(row.getStatus()))
                    && !parsed.validRows().isEmpty();
            PendingPreview pending = new PendingPreview(factoryId, userId, Instant.now().plus(30, ChronoUnit.MINUTES),
                    sha256, previewValid, parsed.validRows());
            pendingPreviews.put(token, pending);
            return SkuImportPreviewDTO.builder()
                    .previewToken(token)
                    .fileSha256(sha256)
                    .totalRows(parsed.rows().size())
                    .validRows(parsed.validRows().size())
                    .invalidRows((int) parsed.rows().stream().filter(r -> "INVALID".equals(r.getStatus())).count()
                            + parsed.errors().size())
                    .rows(parsed.rows())
                    .errors(parsed.errors())
                    .build();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(400, "无法解析SKU导入文件，请确认使用最新xlsx模板", e);
        }
    }

    @Override
    @Transactional
    @CacheEvict(value = {"productTypes", "productTypeOptions"}, key = "#factoryId")
    public SkuImportConfirmResultDTO confirm(String factoryId, Long userId, String previewToken) {
        PendingPreview pending = pendingPreviews.remove(previewToken);
        if (pending == null || pending.expiresAt().isBefore(Instant.now())) {
            throw new BusinessException(409, "导入预览已过期或已确认，请重新预览");
        }
        if (!pending.factoryId().equals(factoryId) || !pending.userId().equals(userId)) {
            throw new BusinessException(403, "无权确认该导入预览");
        }
        if (!pending.previewValid()) {
            throw new BusinessException(409, "导入预览存在错误，禁止确认，请修正后重新预览");
        }
        if (pending.rows().isEmpty()) {
            throw new BusinessException(400, "没有可导入的SKU");
        }
        Set<String> codes = new HashSet<>();
        for (ParsedRow row : pending.rows()) {
            if (!codes.add(row.code()) || productTypeRepository.existsByFactoryIdAndCode(factoryId, row.code())) {
                throw new BusinessException(409, "SKU编号已存在或重复，请重新预览: " + row.code());
            }
        }
        List<ProductType> entities = pending.rows().stream().map(row -> toEntity(factoryId, userId, row)).toList();
        try {
            productTypeRepository.saveAllAndFlush(entities);
            for (int i = 0; i < entities.size(); i++) {
                ProductType entity = entities.get(i);
                ParsedRow row = pending.rows().get(i);
                productPackagingSpecService.replace(entity, toPackagingDtos(entity, row.packagingSpecs()));
                specificationConversionSyncService.synchronize(entity);
            }
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(409, "SKU编号被其他导入占用，请重新预览", e);
        }
        return SkuImportConfirmResultDTO.builder().totalRows(entities.size()).createdCount(entities.size()).build();
    }

    private ParseResult parseWorkbook(String factoryId, byte[] bytes, List<SkuImageMappingDTO> mappings) throws Exception {
        Map<String, SkuImageMappingDTO> imageByCode = new HashMap<>();
        List<SkuImportIssueDTO> globalErrors = new ArrayList<>();
        for (SkuImageMappingDTO mapping : mappings) {
            String code = normalizeCode(mapping.getSkuCode());
            if (imageByCode.putIfAbsent(code, mapping) != null) {
                globalErrors.add(issue(null, null, "imageMappings", "DUPLICATE_IMAGE", "同一SKU只能映射一张图片: " + code));
            }
        }
        List<SkuImportPreviewRowDTO> previewRows = new ArrayList<>();
        List<ParsedRow> validRows = new ArrayList<>();
        Set<String> workbookCodes = new HashSet<>();
        DataFormatter formatter = new DataFormatter(Locale.SIMPLIFIED_CHINESE);
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            for (String required : SHEET_CATEGORIES.keySet()) {
                if (workbook.getSheet(required) == null) {
                    globalErrors.add(issue(required, null, null, "MISSING_SHEET", "缺少必需工作表: " + required));
                }
            }
            int dataRows = 0;
            for (String sheetName : List.of("成品", "半成品", "客户自带原料加工", "纯代工")) {
                Sheet sheet = workbook.getSheet(sheetName);
                if (sheet == null) continue;
                Map<String, Integer> columns = readColumns(sheet, formatter, globalErrors);
                for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                    Row row = sheet.getRow(rowIndex);
                    if (isEmpty(row, formatter)) continue;
                    if (++dataRows > MAX_ROWS) throw new BusinessException(400, "单次最多导入" + MAX_ROWS + "行SKU");
                    int displayRow = rowIndex + 1;
                    List<SkuImportIssueDTO> errors = new ArrayList<>();
                    if (hasFormula(row)) errors.add(issue(sheetName, displayRow, null, "FORMULA_NOT_ALLOWED", "导入数据不允许包含公式"));
                    String marker = value(row, columns.get("示例标记"), formatter);
                    String code = normalizeCode(value(row, columns.get("SKU编号"), formatter));
                    String name = normalizeText(value(row, columns.get("SKU名称"), formatter));
                    String unit = normalizeUnit(value(row, columns.get("基本单位"), formatter));
                    boolean countLikeUnit = COUNT_UNITS.contains(unit);
                    if (isExampleMarker(marker)) {
                        previewRows.add(previewRow(sheetName, displayRow, SHEET_CATEGORIES.get(sheetName), code, name,
                                unit, null, null, value(row, columns.get("图片文件名"), formatter), null,
                                "SKIPPED_EXAMPLE", List.of()));
                        continue;
                    }
                    required(errors, sheetName, displayRow, "skuCode", code, "SKU编号");
                    required(errors, sheetName, displayRow, "name", name, "SKU名称");
                    required(errors, sheetName, displayRow, "unit", unit, "基本单位");
                    if (!unit.isBlank()) {
                        UnitNormalizationResult normalized = unitContractService.normalize(factoryId, unit);
                        if (normalized == null || !normalized.recognized()) {
                            errors.add(issue(sheetName, displayRow, "unit", "UNKNOWN_UNIT", "基本单位不在当前工厂单位字典中: " + unit));
                        } else {
                            countLikeUnit = normalized.unit().dimension() == UnitDimension.COUNT
                                    || normalized.unit().dimension() == UnitDimension.PACKAGE;
                        }
                    }
                    if (name.length() > 255) errors.add(issue(sheetName, displayRow, "name", "TOO_LONG", "SKU名称不能超过255个字符"));
                    if (unit.length() > 20) errors.add(issue(sheetName, displayRow, "unit", "TOO_LONG", "基本单位不能超过20个字符"));
                    if (!code.isBlank() && !code.matches("[A-Z0-9._-]{1,50}")) {
                        errors.add(issue(sheetName, displayRow, "skuCode", "INVALID_CODE", "SKU编号仅支持字母、数字、点、横线和下划线，最长50位"));
                    }
                    if (!code.isBlank() && (!workbookCodes.add(code) || productTypeRepository.existsByFactoryIdAndCode(factoryId, code))) {
                        errors.add(issue(sheetName, displayRow, "skuCode", "DUPLICATE_CODE", "SKU编号在文件或系统中已存在: " + code));
                    }
                    BigDecimal grams = decimal(value(row, columns.get("标准克重(g)"), formatter), sheetName, displayRow, "gramsPerUnit", errors);
                    List<PackagingInput> packagingSpecs = new ArrayList<>();
                    PackagingInput packaging1 = parsePackaging(factoryId, row, columns, formatter,
                            sheetName, displayRow, 1, unit, errors);
                    PackagingInput packaging2 = parsePackaging(factoryId, row, columns, formatter,
                            sheetName, displayRow, 2, unit, errors);
                    if (packaging1 != null) packagingSpecs.add(packaging1);
                    if (packaging2 != null) packagingSpecs.add(packaging2);
                    if (packaging1 != null && packaging2 != null
                            && unitsEquivalent(factoryId, packaging1.packageUnit(), packaging2.packageUnit())
                            && packaging1.quantity().compareTo(packaging2.quantity()) == 0) {
                        errors.add(issue(sheetName, displayRow, "packaging2", "DUPLICATE_PACKAGING",
                                "包装规格1和包装规格2的包装单位与换算数量不能完全重复"));
                    }
                    Integer shelfLife = integer(value(row, columns.get("保质期(天)"), formatter), sheetName, displayRow, "shelfLifeDays", errors);
                    if (grams != null && grams.signum() <= 0) {
                        errors.add(issue(sheetName, displayRow, "gramsPerUnit", "INVALID_WEIGHT", "标准克重必须大于0"));
                    }
                    String category = SHEET_CATEGORIES.get(sheetName);
                    if (!ProductCategory.SEMI_FINISHED.equals(category) && countLikeUnit && grams == null) {
                        errors.add(issue(sheetName, displayRow, "gramsPerUnit", "WEIGHT_REQUIRED", "按件计数的成品必须填写标准克重"));
                    }
                    if (ProductCategory.SEMI_FINISHED.equals(category) && !packagingSpecs.isEmpty()) {
                        errors.add(issue(sheetName, displayRow, "packaging", "SEMI_PACKAGING_NOT_ALLOWED", "半成品只定义基本单位，不维护装箱规格"));
                    }
                    String providedSpecification = normalizeText(value(row, columns.get("规格"), formatter));
                    String generatedSpecification = buildSpecification(grams, unit, packagingSpecs);
                    String specification = providedSpecification.isBlank() ? generatedSpecification : providedSpecification;
                    if (ProductCategory.SEMI_FINISHED.equals(category) && !providedSpecification.isBlank()) {
                        errors.add(issue(sheetName, displayRow, "specification", "SEMI_SPEC_NOT_ALLOWED", "半成品不维护成品规格，请清空规格列"));
                    } else if (!providedSpecification.isBlank() && generatedSpecification.isBlank()) {
                        errors.add(issue(sheetName, displayRow, "specification", "SPEC_WITHOUT_STRUCTURE", "规格必须由标准克重和包装结构生成，请填写结构化列"));
                    } else if (!providedSpecification.isBlank()
                            && !normalizeSpecification(providedSpecification).equals(normalizeSpecification(generatedSpecification))) {
                        errors.add(issue(sheetName, displayRow, "specification", "SPEC_MISMATCH",
                                "规格与结构化列不一致，预期: " + generatedSpecification));
                    }
                    if (specification.length() > 200) {
                        errors.add(issue(sheetName, displayRow, "specification", "TOO_LONG", "规格不能超过200个字符"));
                    }
                    String imageFileName = normalizeText(value(row, columns.get("图片文件名"), formatter));
                    if (imageFileName.length() > 255) {
                        errors.add(issue(sheetName, displayRow, "imageFileName", "TOO_LONG", "图片文件名不能超过255个字符"));
                    }
                    SkuImageMappingDTO image = imageByCode.get(code);
                    String imageUrl = image == null ? null : image.getUrl();
                    String matchedImageName = image == null ? null : image.getFileName();
                    if (!imageFileName.isBlank() && image == null) {
                        errors.add(issue(sheetName, displayRow, "imageFileName", "IMAGE_NOT_UPLOADED", "图片文件名已填写，但未找到该SKU的安全上传映射"));
                    } else if (image != null && image.getFileName() != null
                            && !imageFileName.equals(image.getFileName())) {
                        errors.add(issue(sheetName, displayRow, "imageFileName", "IMAGE_FILENAME_MISMATCH",
                                "图片文件名必须与上传映射精确一致: " + image.getFileName()));
                    } else if (image != null && image.getFileName() == null && !imageFileName.isBlank()) {
                        errors.add(issue(sheetName, displayRow, "imageFileName", "IMAGE_FILENAME_MISMATCH",
                                "URL-only图片映射不应填写图片文件名"));
                    }
                    String status = errors.isEmpty() ? "VALID" : "INVALID";
                    previewRows.add(previewRow(sheetName, displayRow, category, code, name, unit, specification,
                            imageUrl, imageFileName, matchedImageName, status, errors));
                    if (errors.isEmpty()) {
                        validRows.add(new ParsedRow(code, name, unit, category, grams, List.copyOf(packagingSpecs),
                                normalizeText(value(row, columns.get("温区"), formatter)), shelfLife, specification,
                                imageUrl, normalizeText(value(row, columns.get("备注"), formatter))));
                    }
                }
            }
        }
        if (!globalErrors.isEmpty() || previewRows.stream().anyMatch(row -> "INVALID".equals(row.getStatus()))) {
            validRows.clear();
        }
        return new ParseResult(previewRows, globalErrors, validRows);
    }

    private PackagingInput parsePackaging(String factoryId, Row row, Map<String, Integer> columns,
            DataFormatter formatter, String sheetName, int displayRow, int slot, String baseUnit,
            List<SkuImportIssueDTO> errors) {
        String unitField = "包装单位" + slot;
        String quantityField = "每包装数量" + slot;
        String packageUnit = normalizeUnit(value(row, columns.get(unitField), formatter));
        String quantityText = value(row, columns.get(quantityField), formatter);
        boolean hasUnit = !packageUnit.isBlank();
        boolean hasQuantity = !quantityText.isBlank();
        if (!hasUnit && !hasQuantity) return null;
        if (hasUnit != hasQuantity) {
            errors.add(issue(sheetName, displayRow, "packaging" + slot, "INCOMPLETE_PACKAGING",
                    unitField + "和" + quantityField + "必须同时填写"));
            return null;
        }
        if (packageUnit.length() > 20) {
            errors.add(issue(sheetName, displayRow, "packageUnit" + slot, "TOO_LONG", unitField + "不能超过20个字符"));
        }
        UnitNormalizationResult normalized = unitContractService.normalize(factoryId, packageUnit);
        if (normalized == null || !normalized.recognized()) {
            errors.add(issue(sheetName, displayRow, "packageUnit" + slot, "UNKNOWN_UNIT",
                    unitField + "不在当前工厂单位字典中: " + packageUnit));
        }
        BigDecimal quantity = decimal(quantityText, sheetName, displayRow, "packageQuantity" + slot, errors);
        if (quantity == null) return null;
        if (quantity.signum() <= 0) {
            errors.add(issue(sheetName, displayRow, "packageQuantity" + slot, "INVALID_QUANTITY",
                    quantityField + "必须大于0"));
        }
        if (quantity.stripTrailingZeros().scale() > 0) {
            errors.add(issue(sheetName, displayRow, "packageQuantity" + slot, "INTEGER_REQUIRED",
                    quantityField + "必须是正整数"));
        }
        if (!baseUnit.isBlank() && unitsEquivalent(factoryId, packageUnit, baseUnit)) {
            errors.add(issue(sheetName, displayRow, "packageUnit" + slot, "SAME_AS_BASE_UNIT",
                    unitField + "不能与基本单位相同"));
        }
        return new PackagingInput(packageUnit, quantity);
    }

    private boolean unitsEquivalent(String factoryId, String left, String right) {
        return normalizeUnit(left).equals(normalizeUnit(right))
                || unitContractService.areEquivalent(factoryId, left, right);
    }

    private Map<String, Integer> readColumns(Sheet sheet, DataFormatter formatter, List<SkuImportIssueDTO> errors) {
        Map<String, Integer> columns = new HashMap<>();
        Row header = sheet.getRow(0);
        if (header != null) {
            for (Cell cell : header) {
                String name = normalizeText(formatter.formatCellValue(cell)).replace("*", "");
                columns.put(name, cell.getColumnIndex());
            }
        }
        for (String required : List.of("示例标记", "SKU编号", "SKU名称", "基本单位")) {
            if (!columns.containsKey(required)) {
                errors.add(issue(sheet.getSheetName(), 1, required, "MISSING_COLUMN", "缺少必需列: " + required));
            }
        }
        return columns;
    }

    private List<SkuImageMappingDTO> parseImageMappings(String factoryId, String json) throws Exception {
        if (json == null || json.isBlank()) return List.of();
        List<SkuImageMappingDTO> mappings = objectMapper.readValue(json, new TypeReference<>() {});
        if (mappings.size() > MAX_ROWS) throw new BusinessException(400, "图片映射数量过多");
        for (SkuImageMappingDTO mapping : mappings) {
            String code = normalizeCode(mapping.getSkuCode());
            String fileName = normalizeText(mapping.getFileName());
            if (code.isBlank() || mapping.getUrl() == null || mapping.getUrl().isBlank()) {
                throw new BusinessException(400, "imageMappings必须包含skuCode和url；上传文件匹配时另传fileName");
            }
            if (fileName.length() > 255 || mapping.getUrl().length() > 500) {
                throw new BusinessException(400, "图片文件名不能超过255字符，URL不能超过500字符");
            }
            mapping.setSkuCode(code);
            mapping.setFileName(fileName.isBlank() ? null : fileName);
            if (fileName.isBlank()) {
                validateHttpImageUrl(mapping.getUrl());
            } else {
                validateOwnedImageUrl(factoryId, mapping.getUrl());
            }
        }
        return mappings;
    }

    private void validateOwnedImageUrl(String factoryId, String value) {
        try {
            URI uri = URI.create(value).normalize();
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String path = uri.getPath() == null ? "" : uri.getPath();
            String expected = "/" + factoryId + "/images/product-images/";
            if (!("https".equals(scheme) || "http".equals(scheme)) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null || !path.startsWith(expected)) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException e) {
            throw new BusinessException(400, "图片URL必须来自当前工厂的product-images安全上传端点");
        }
    }

    private void validateHttpImageUrl(String value) {
        try {
            URI uri = URI.create(value).normalize();
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!("https".equals(scheme) || "http".equals(scheme)) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException e) {
            throw new BusinessException(400, "图片URL必须是有效的http/https地址");
        }
    }

    private ProductType toEntity(String factoryId, Long userId, ParsedRow row) {
        ProductType entity = new ProductType();
        entity.setId(UUID.randomUUID().toString());
        entity.setFactoryId(factoryId);
        entity.setCode(row.code());
        entity.setName(row.name());
        entity.setCategory(row.category());
        entity.setProductCategory(row.category());
        entity.setUnit(row.unit());
        entity.setGramsPerUnit(ProductCategory.SEMI_FINISHED.equals(row.category()) ? null : row.gramsPerUnit());
        PackagingInput defaultPackaging = row.packagingSpecs().isEmpty() ? null : row.packagingSpecs().get(0);
        entity.setLevel1Unit(ProductCategory.SEMI_FINISHED.equals(row.category()) || defaultPackaging == null
                ? null : defaultPackaging.packageUnit());
        entity.setBoxConversionCoefficient(ProductCategory.SEMI_FINISHED.equals(row.category()) || defaultPackaging == null
                ? null : defaultPackaging.quantity());
        entity.setTemperatureZone(emptyToNull(row.temperatureZone()));
        entity.setShelfLifeDays(row.shelfLifeDays());
        entity.setSpecification(ProductCategory.SEMI_FINISHED.equals(row.category()) ? null : emptyToNull(row.specification()));
        entity.setImageUrl(row.imageUrl());
        entity.setNotes(emptyToNull(row.notes()));
        entity.setIsActive(true);
        entity.setCreatedBy(userId);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        entity.setWorkHours(BigDecimal.ONE);
        entity.setComplexityScore(3);
        return entity;
    }

    private List<ProductPackagingSpecDTO> toPackagingDtos(ProductType product, List<PackagingInput> inputs) {
        if (ProductCategory.SEMI_FINISHED.equals(product.getProductCategory())) return List.of();
        List<ProductPackagingSpecDTO> result = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            PackagingInput input = inputs.get(i);
            result.add(new ProductPackagingSpecDTO(
                    null,
                    input.packageUnit() + "装规格" + (i + 1),
                    input.packageUnit(),
                    product.getUnit(),
                    input.quantity(),
                    i == 0,
                    true,
                    i,
                    null));
        }
        return result;
    }

    private void validateUpload(String factoryId, MultipartFile file) {
        if (!SAFE_FACTORY_ID.matcher(factoryId).matches()) throw new BusinessException(400, "工厂ID格式无效");
        if (file == null || file.isEmpty()) throw new BusinessException(400, "导入文件不能为空");
        if (file.getSize() > MAX_FILE_SIZE) throw new BusinessException(400, "SKU导入文件不能超过10MB");
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!filename.endsWith(".xlsx")) throw new BusinessException(400, "仅支持.xlsx格式，禁止宏工作簿");
    }

    private static SkuImportPreviewRowDTO previewRow(String sheet, int row, String category, String code,
            String name, String unit, String specification, String imageUrl, String imageFileName,
            String matchedImageName, String status, List<SkuImportIssueDTO> errors) {
        return SkuImportPreviewRowDTO.builder().sheetName(sheet).rowNumber(row).skuCategory(category)
                .skuCode(code).name(name).unit(unit).specification(specification).imageUrl(imageUrl)
                .imageFileName(imageFileName).matchedImageName(matchedImageName).status(status).errors(errors).build();
    }

    private static SkuImportIssueDTO issue(String sheet, Integer row, String field, String code, String message) {
        return SkuImportIssueDTO.builder().sheetName(sheet).rowNumber(row).field(field).code(code).message(message).build();
    }

    private static void required(List<SkuImportIssueDTO> errors, String sheet, int row, String field, String value, String label) {
        if (value == null || value.isBlank()) errors.add(issue(sheet, row, field, "REQUIRED", label + "不能为空"));
    }

    private static BigDecimal decimal(String value, String sheet, int row, String field, List<SkuImportIssueDTO> errors) {
        if (value == null || value.isBlank()) return null;
        try {
            return new BigDecimal(value.replace(",", ""));
        } catch (NumberFormatException e) {
            errors.add(issue(sheet, row, field, "INVALID_NUMBER", "请输入有效数字"));
            return null;
        }
    }

    private static Integer integer(String value, String sheet, int row, String field, List<SkuImportIssueDTO> errors) {
        BigDecimal number = decimal(value, sheet, row, field, errors);
        if (number == null) return null;
        try {
            int result = number.intValueExact();
            if (result < 0) throw new ArithmeticException();
            return result;
        } catch (ArithmeticException e) {
            errors.add(issue(sheet, row, field, "INVALID_INTEGER", "请输入非负整数"));
            return null;
        }
    }

    private static boolean hasFormula(Row row) {
        if (row == null) return false;
        for (Cell cell : row) if (cell.getCellType() == CellType.FORMULA) return true;
        return false;
    }

    private static boolean isEmpty(Row row, DataFormatter formatter) {
        if (row == null) return true;
        for (Cell cell : row) if (!normalizeText(formatter.formatCellValue(cell)).isBlank()) return false;
        return true;
    }

    private static String value(Row row, Integer column, DataFormatter formatter) {
        if (row == null || column == null) return "";
        Cell cell = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        return cell == null ? "" : normalizeText(formatter.formatCellValue(cell));
    }

    static String normalizeText(String value) {
        if (value == null) return "";
        return value.replace('\u3000', ' ').trim().replaceAll("\\s+", " ");
    }

    static String normalizeCode(String value) {
        return normalizeText(value).replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    static String normalizeUnit(String value) {
        String unit = normalizeText(value).toLowerCase(Locale.ROOT);
        return switch (unit) {
            case "公斤", "千克", "kgs", "kilogram", "kilograms" -> "kg";
            case "克", "gram", "grams" -> "g";
            case "pcs", "pc", "piece", "pieces", "个" -> "件";
            case "box", "case", "carton" -> "箱";
            default -> unit;
        };
    }

    private static boolean isExampleMarker(String value) {
        String marker = normalizeText(value).toUpperCase(Locale.ROOT);
        return "示例".equals(marker) || "EXAMPLE".equals(marker) || "样例".equals(marker);
    }

    private static String friendlyWeight(BigDecimal grams) {
        BigDecimal stripped = grams.stripTrailingZeros();
        if (stripped.compareTo(new BigDecimal("1000")) >= 0) {
            return stripped.divide(new BigDecimal("1000")).stripTrailingZeros().toPlainString() + "kg";
        }
        return stripped.toPlainString() + "g";
    }

    private static String buildSpecification(BigDecimal grams, String baseUnit, List<PackagingInput> packagingSpecs) {
        List<String> parts = new ArrayList<>();
        if (grams != null && baseUnit != null && !baseUnit.isBlank()) {
            parts.add(friendlyWeight(grams) + "/" + baseUnit);
        }
        for (PackagingInput packaging : packagingSpecs) {
            if (baseUnit == null || baseUnit.isBlank()) continue;
            parts.add(packaging.quantity().stripTrailingZeros().toPlainString()
                    + baseUnit + "/" + packaging.packageUnit());
            if (grams != null) {
                parts.add(friendlyWeight(grams.multiply(packaging.quantity())) + "/" + packaging.packageUnit());
            }
        }
        return String.join(" ", parts);
    }

    private static String normalizeSpecification(String value) {
        return normalizeText(value).replace(" ", "").toLowerCase(Locale.ROOT);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder result = new StringBuilder();
        for (byte b : digest) result.append(String.format("%02x", b));
        return result.toString();
    }

    private void cleanupExpired() {
        Instant now = Instant.now();
        pendingPreviews.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    private static String headerHelp(int index, String sheetName) {
        return switch (index) {
            case 0 -> "仅示例行填写“示例”；系统按内容标记跳过，不按固定行号跳过。";
            case 1 -> "必填。工厂内唯一，字母自动转大写，全角/首尾空格自动清理。";
            case 2 -> "必填。SKU显示名称。";
            case 3 -> "必填。可填写kg、g、盒、袋、件、只等；半成品不会被强制改为kg。";
            case 4 -> "计数单位成品必填，例如1盒=200g则填写200；半成品留空。";
            case 5, 6 -> "包装规格1：如填写，包装单位1和每包装数量1必须成对填写；作为默认包装规格；半成品留空。";
            case 7, 8 -> "包装规格2：可选；如填写，两列必须成对填写，且包装单位不得与规格1重复；半成品留空。";
            case 11 -> "由标准克重和包装规格1/2自动生成；若手工填写，必须与结构化列完全一致。";
            case 12 -> "可填图片文件名；导入前需在页面上传并绑定到SKU编号。";
            default -> "可选字段。当前工作表类别固定为“" + sheetName + "”，无需另填类别。";
        };
    }

    private static List<String> exampleValues(String sheetName) {
        return switch (sheetName) {
            case "半成品" -> List.of("示例", "SEMI-001", "示例半成品", "只", "", "", "", "", "",
                    "冷藏", "3", "", "", "此行不会导入");
            default -> List.of("示例", "SKU-001", "示例产品", "盒", "200", "箱", "50", "框", "200",
                    "冷藏", "30", "200g/盒 50盒/箱 10kg/箱 200盒/框 40kg/框", "SKU-001.png", "此行不会导入");
        };
    }

    private record PackagingInput(String packageUnit, BigDecimal quantity) {}

    private record ParsedRow(String code, String name, String unit, String category, BigDecimal gramsPerUnit,
            List<PackagingInput> packagingSpecs, String temperatureZone, Integer shelfLifeDays,
            String specification, String imageUrl, String notes) {}

    private record PendingPreview(String factoryId, Long userId, Instant expiresAt, String fileSha256,
            boolean previewValid, List<ParsedRow> rows) {}

    private record ParseResult(List<SkuImportPreviewRowDTO> rows, List<SkuImportIssueDTO> errors,
            List<ParsedRow> validRows) {}
}
