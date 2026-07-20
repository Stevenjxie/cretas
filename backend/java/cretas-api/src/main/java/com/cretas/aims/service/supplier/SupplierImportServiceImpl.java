package com.cretas.aims.service.supplier;

import com.cretas.aims.dto.supplier.*;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.SupplierImportReceipt;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.SupplierImportReceiptRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.service.SupplierService;
import com.cretas.aims.utils.ExcelUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SupplierImportServiceImpl implements SupplierImportService {
    private static final int MAX_ROWS = 1000;
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Set<String> TARGET_FIELDS = Set.of(
            "supplierCode", "name", "contactPerson", "phone", "email", "address",
            "bankAccount", "taxNumber", "notes");
    private static final Set<String> REQUIRED_FIELDS = Set.of("name", "contactPerson", "phone", "address");
    private static final Map<String, List<String>> ALIASES = aliases();
    private static final ConcurrentHashMap<String, Object> IDEMPOTENCY_LOCKS = new ConcurrentHashMap<>();

    private final ExcelUtil excelUtil;
    private final SupplierRepository supplierRepository;
    private final SupplierImportReceiptRepository receiptRepository;
    private final SupplierService supplierService;

    @Override
    public byte[] generateTemplate() {
        return excelUtil.generateTemplate(SupplierImportTemplateRow.class, "供应商导入模板");
    }

    @Override
    @Transactional(readOnly = true)
    public SupplierImportPreviewDTO preview(String factoryId, byte[] fileBytes, String mode,
                                            Map<String, String> columnMapping) {
        if (fileBytes == null || fileBytes.length == 0) throw new BusinessException(400, "Excel文件不能为空");
        String normalizedMode = mode == null ? "STANDARD" : mode.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("STANDARD", "SMART").contains(normalizedMode)) {
            throw new BusinessException(400, "mode 仅支持 STANDARD 或 SMART").withHintTarget("mode");
        }
        ExcelUtil.RawSheet sheet = excelUtil.readFirstSheetAsRows(new ByteArrayInputStream(fileBytes), MAX_ROWS);
        if (sheet.headers().isEmpty()) throw new BusinessException(400, "Excel缺少表头");

        Map<String, String> sourceToTarget = resolveMappings(sheet.headers(), normalizedMode, columnMapping);
        List<SupplierImportPreviewDTO.ColumnMapping> mappings = sourceToTarget.entrySet().stream()
                .map(e -> SupplierImportPreviewDTO.ColumnMapping.builder()
                        .sourceColumn(e.getKey()).targetField(e.getValue())
                        .confidence(columnMapping != null && columnMapping.containsKey(e.getKey()) ? 100
                                : mappingConfidence(e.getKey(), e.getValue()))
                        .required(REQUIRED_FIELDS.contains(e.getValue())).build())
                .toList();

        List<Supplier> existing = supplierRepository.findByFactoryId(factoryId);
        Set<String> dbNames = existing.stream().map(Supplier::getName).map(SupplierImportServiceImpl::identity)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<String> dbTaxes = existing.stream().map(Supplier::getTaxNumber).map(SupplierImportServiceImpl::identity)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<String> fileNames = new HashSet<>();
        Set<String> fileTaxes = new HashSet<>();
        List<SupplierImportPreviewDTO.Row> rows = new ArrayList<>();

        for (int index = 0; index < sheet.rows().size(); index++) {
            Map<String, String> source = sheet.rows().get(index);
            SupplierImportPreviewDTO.SupplierRowData data = mapRow(source, sourceToTarget);
            Map<String, String> errors = validateRow(data);
            String classification;
            if (isBlankRow(data)) {
                classification = "IGNORED";
            } else {
                String nameKey = identity(data.getName());
                String taxKey = identity(data.getTaxNumber());
                if (errors.isEmpty() && (dbNames.contains(nameKey) || (taxKey != null && dbTaxes.contains(taxKey)))) {
                    classification = "DUPLICATE";
                    errors.put("duplicate", dbNames.contains(nameKey) ? "当前工厂已存在同名供应商" : "当前工厂已存在相同税号供应商");
                } else if (errors.isEmpty() && (!fileNames.add(nameKey) || (taxKey != null && !fileTaxes.add(taxKey)))) {
                    classification = "DUPLICATE";
                    errors.put("duplicate", "同一文件内供应商名称或税号重复");
                } else {
                    if (nameKey != null) fileNames.add(nameKey);
                    if (taxKey != null) fileTaxes.add(taxKey);
                    classification = errors.isEmpty() ? "VALID" : "ERROR";
                }
            }
            rows.add(SupplierImportPreviewDTO.Row.builder().rowNumber(index + 2)
                    .classification(classification).data(data).errors(errors).build());
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("total", rows.size());
        for (String key : List.of("VALID", "DUPLICATE", "ERROR", "IGNORED")) {
            counts.put(key.toLowerCase(Locale.ROOT), (int) rows.stream()
                    .filter(row -> key.equals(row.getClassification())).count());
        }
        return SupplierImportPreviewDTO.builder().fileDigest(sha256(fileBytes)).mode(normalizedMode)
                .mappings(mappings).rows(rows).counts(counts).build();
    }

    @Override
    @Transactional
    public SupplierImportConfirmResultDTO confirm(String factoryId, SupplierImportConfirmRequest request, Long userId) {
        String key = factoryId + "\u0000" + request.getIdempotencyKey();
        Object lock = IDEMPOTENCY_LOCKS.computeIfAbsent(key, ignored -> new Object());
        synchronized (lock) {
            try {
                Optional<SupplierImportReceipt> existingReceipt = receiptRepository
                        .findByFactoryIdAndIdempotencyKey(factoryId, request.getIdempotencyKey());
                if (existingReceipt.isPresent()) {
                    SupplierImportReceipt receipt = existingReceipt.get();
                    if (!receipt.getFileDigest().equalsIgnoreCase(request.getFileDigest())) {
                        throw new BusinessException(409, "幂等键已用于其他导入文件")
                                .withHint("请使用原文件重试或生成新的幂等键").withHintTarget("idempotencyKey");
                    }
                    return replay(receipt);
                }

                // Claim the factory-scoped idempotency key before any supplier is written.
                // The database unique constraint serializes different JVMs/pods; the local
                // lock above only avoids duplicate work inside this process.
                SupplierImportReceipt receipt;
                try {
                    receipt = receiptRepository.saveAndFlush(SupplierImportReceipt.builder()
                            .factoryId(factoryId)
                            .idempotencyKey(request.getIdempotencyKey())
                            .fileDigest(request.getFileDigest().toLowerCase(Locale.ROOT))
                            .createdBy(userId)
                            .createdCount(0)
                            .supplierIds(null)
                            .build());
                } catch (DataIntegrityViolationException conflict) {
                    throw new BusinessException(409, "同一导入请求正在处理或已经完成")
                            .withCode("SUPPLIER_IMPORT_IDEMPOTENCY_CONFLICT")
                            .withHint("请勿重复确认；稍后以相同幂等键重试可读取既有结果")
                            .withHintTarget("idempotencyKey");
                }

                List<CreateSupplierRequest> createRequests = new ArrayList<>();
                Set<String> names = new HashSet<>();
                Set<String> taxes = new HashSet<>();
                Set<String> dbNames = supplierRepository.findByFactoryId(factoryId).stream()
                        .map(Supplier::getName).map(SupplierImportServiceImpl::identity).filter(Objects::nonNull)
                        .collect(Collectors.toSet());
                Set<String> dbTaxes = supplierRepository.findByFactoryId(factoryId).stream()
                        .map(Supplier::getTaxNumber).map(SupplierImportServiceImpl::identity).filter(Objects::nonNull)
                        .collect(Collectors.toSet());

                for (SupplierImportPreviewDTO.SupplierRowData row : request.getRows()) {
                    Map<String, String> errors = validateRow(row);
                    if (!errors.isEmpty()) {
                        throw new BusinessException(400, "导入行校验失败: " + String.join("；", errors.values()))
                                .withHintTarget(errors.keySet().iterator().next());
                    }
                    String nameKey = identity(row.getName());
                    String taxKey = identity(row.getTaxNumber());
                    if (dbNames.contains(nameKey) || !names.add(nameKey)
                            || (taxKey != null && (dbTaxes.contains(taxKey) || !taxes.add(taxKey)))) {
                        throw new BusinessException(409, "导入数据包含已存在或重复的供应商: " + row.getName())
                                .withHint("请返回预览取消勾选疑似重复行").withHintTarget("rows");
                    }
                    createRequests.add(toCreateRequest(row));
                }

                List<SupplierDTO> created = new ArrayList<>();
                for (CreateSupplierRequest create : createRequests) {
                    created.add(supplierService.createSupplier(factoryId, create, userId));
                }
                receipt.setCreatedCount(created.size());
                receipt.setSupplierIds(created.stream().map(SupplierDTO::getId).collect(Collectors.joining(",")));
                receipt = receiptRepository.save(receipt);
                return result(receipt, created, false);
            } finally {
                IDEMPOTENCY_LOCKS.remove(key, lock);
            }
        }
    }

    @Override
    public byte[] generateErrorReport(List<SupplierImportPreviewDTO.Row> rows) {
        List<SupplierImportErrorReportRow> report = rows == null ? List.of() : rows.stream()
                .filter(row -> !"VALID".equals(row.getClassification()) && !"IGNORED".equals(row.getClassification()))
                .map(row -> new SupplierImportErrorReportRow(row.getRowNumber(),
                        row.getData() != null ? row.getData().getName() : null,
                        row.getClassification(), row.getErrors() == null ? "" : String.join("；", row.getErrors().values())))
                .toList();
        return excelUtil.exportToExcel(report, SupplierImportErrorReportRow.class, "供应商导入错误报告");
    }

    private SupplierImportConfirmResultDTO replay(SupplierImportReceipt receipt) {
        List<String> ids = receipt.getSupplierIds() == null || receipt.getSupplierIds().isBlank()
                ? List.of() : Arrays.asList(receipt.getSupplierIds().split(","));
        List<SupplierDTO> suppliers = supplierRepository.findByIdInAndFactoryId(ids, receipt.getFactoryId()).stream()
                .map(s -> SupplierDTO.builder().id(s.getId()).factoryId(s.getFactoryId())
                        .supplierCode(s.getSupplierCode()).name(s.getName()).contactPerson(s.getContactPerson())
                        .phone(s.getPhone()).email(s.getEmail()).address(s.getAddress()).taxNumber(s.getTaxNumber())
                        .bankAccount(s.getBankAccount()).isActive(s.getIsActive())
                        .status(Boolean.TRUE.equals(s.getIsActive()) ? "ACTIVE" : "INACTIVE")
                        .profileComplete(SupplierProfileValidator.isComplete(s.getName(), s.getContactPerson(), s.getPhone(), s.getAddress()))
                        .build()).toList();
        return result(receipt, suppliers, true);
    }

    private static SupplierImportConfirmResultDTO result(SupplierImportReceipt receipt,
                                                          List<SupplierDTO> suppliers, boolean replayed) {
        return SupplierImportConfirmResultDTO.builder().receiptId(receipt.getId())
                .idempotencyKey(receipt.getIdempotencyKey()).createdCount(receipt.getCreatedCount())
                .skippedCount(0).failedCount(0).replayed(replayed).suppliers(suppliers).build();
    }

    private static CreateSupplierRequest toCreateRequest(SupplierImportPreviewDTO.SupplierRowData row) {
        CreateSupplierRequest request = new CreateSupplierRequest();
        request.setSupplierCode(clean(row.getSupplierCode())); request.setName(clean(row.getName()));
        request.setContactPerson(clean(row.getContactPerson())); request.setPhone(clean(row.getPhone()));
        request.setEmail(clean(row.getEmail())); request.setAddress(clean(row.getAddress()));
        request.setBankAccount(clean(row.getBankAccount())); request.setTaxNumber(clean(row.getTaxNumber()));
        request.setNotes(clean(row.getNotes()));
        return request;
    }

    private static Map<String, String> validateRow(SupplierImportPreviewDTO.SupplierRowData row) {
        Map<String, String> errors = new LinkedHashMap<>(SupplierProfileValidator.validate(
                row == null ? null : row.getName(), row == null ? null : row.getContactPerson(),
                row == null ? null : row.getPhone(), row == null ? null : row.getAddress()));
        if (row != null && clean(row.getEmail()) != null && !EMAIL.matcher(clean(row.getEmail())).matches()) {
            errors.put("email", "邮箱格式不正确");
        }
        if (row != null && clean(row.getSupplierCode()) != null && clean(row.getSupplierCode()).length() > 50) {
            errors.put("supplierCode", "供应商编码不能超过50个字符");
        }
        return errors;
    }

    private static boolean isBlankRow(SupplierImportPreviewDTO.SupplierRowData row) {
        return row == null || java.util.stream.Stream.of(row.getSupplierCode(), row.getName(), row.getContactPerson(), row.getPhone(),
                row.getEmail(), row.getAddress(), row.getBankAccount(), row.getTaxNumber(), row.getNotes())
                .allMatch(value -> clean(value) == null);
    }

    private static SupplierImportPreviewDTO.SupplierRowData mapRow(Map<String, String> source,
                                                                    Map<String, String> sourceToTarget) {
        SupplierImportPreviewDTO.SupplierRowData data = new SupplierImportPreviewDTO.SupplierRowData();
        sourceToTarget.forEach((sourceColumn, targetField) -> set(data, targetField, source.get(sourceColumn)));
        return data;
    }

    private static void set(SupplierImportPreviewDTO.SupplierRowData row, String field, String value) {
        String cleaned = clean(value);
        switch (field) {
            case "supplierCode" -> row.setSupplierCode(cleaned); case "name" -> row.setName(cleaned);
            case "contactPerson" -> row.setContactPerson(cleaned); case "phone" -> row.setPhone(cleaned);
            case "email" -> row.setEmail(cleaned); case "address" -> row.setAddress(cleaned);
            case "bankAccount" -> row.setBankAccount(cleaned); case "taxNumber" -> row.setTaxNumber(cleaned);
            case "notes" -> row.setNotes(cleaned); default -> { }
        }
    }

    private static Map<String, String> resolveMappings(List<String> headers, String mode,
                                                        Map<String, String> overrides) {
        Map<String, String> mappings = new LinkedHashMap<>();
        for (String source : headers) {
            String target = overrides != null ? overrides.get(source) : null;
            if (target != null && !TARGET_FIELDS.contains(target)) {
                throw new BusinessException(400, "未知目标字段: " + target).withHintTarget("columnMapping");
            }
            if (target == null) target = inferTarget(source, mode);
            if (target != null && !mappings.containsValue(target)) mappings.put(source, target);
        }
        Set<String> missing = new LinkedHashSet<>(REQUIRED_FIELDS);
        missing.removeAll(mappings.values());
        if (!missing.isEmpty()) {
            throw new BusinessException(400, "缺少必填列映射: " + String.join(", ", missing))
                    .withHint("智能识别模式下请确认源列到必填字段的映射").withHintTarget("columnMapping");
        }
        return mappings;
    }

    private static String inferTarget(String source, String mode) {
        String normalized = header(source);
        for (Map.Entry<String, List<String>> entry : ALIASES.entrySet()) {
            List<String> accepted = "STANDARD".equals(mode) ? List.of(entry.getValue().get(0)) : entry.getValue();
            if (accepted.stream().map(SupplierImportServiceImpl::header).anyMatch(normalized::equals)) return entry.getKey();
        }
        return null;
    }

    private static int mappingConfidence(String source, String target) {
        return header(ALIASES.get(target).get(0)).equals(header(source)) ? 100 : 85;
    }

    private static Map<String, List<String>> aliases() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put("name", List.of("供应商名称", "供应商", "供货商", "supplier", "name"));
        map.put("contactPerson", List.of("联系人", "联络人", "负责人", "contact", "contactperson"));
        map.put("phone", List.of("联系电话", "手机", "手机号", "电话", "座机", "phone", "mobile"));
        map.put("address", List.of("地址", "供应商地址", "联系地址", "address"));
        map.put("email", List.of("邮箱", "电子邮箱", "email"));
        map.put("bankAccount", List.of("银行账户", "银行账号", "账号", "bankaccount"));
        map.put("taxNumber", List.of("税号", "纳税人识别号", "统一社会信用代码", "taxnumber"));
        map.put("supplierCode", List.of("供应商编码", "供货商编码", "编码", "suppliercode"));
        map.put("notes", List.of("备注", "说明", "notes"));
        return Map.copyOf(map);
    }

    private static String header(String value) {
        return value == null ? "" : value.replace("*", "").replaceAll("[\\s_\\-（）()/:：]", "")
                .toLowerCase(Locale.ROOT);
    }
    private static String clean(String value) { return SupplierProfileValidator.trimToNull(value); }
    private static String identity(String value) {
        String cleaned = clean(value); return cleaned == null ? null : cleaned.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
    private static String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder result = new StringBuilder();
            for (byte b : digest) result.append(String.format("%02x", b));
            return result.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
