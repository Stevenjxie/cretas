package com.cretas.aims.logistics.service.importjob.impl;

import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.logistics.dto.importjob.DeliveryOrderDto;
import com.cretas.aims.logistics.dto.importjob.LogisticsOrderImportRow;
import com.cretas.aims.logistics.dto.importjob.OrderBatchDto;
import com.cretas.aims.logistics.dto.importjob.OrderImportPreviewRowDto;
import com.cretas.aims.logistics.dto.importjob.PreviewResultDto;
import com.cretas.aims.logistics.dto.importjob.RowErrorDto;
import com.cretas.aims.logistics.entity.LogisticsDeliveryOrder;
import com.cretas.aims.logistics.entity.LogisticsOrderBatch;
import com.cretas.aims.logistics.entity.enums.DeliveryOrderStatus;
import com.cretas.aims.logistics.entity.enums.LocationStatus;
import com.cretas.aims.logistics.entity.enums.OrderBatchStatus;
import com.cretas.aims.logistics.repository.LogisticsDeliveryOrderRepository;
import com.cretas.aims.logistics.repository.LogisticsOrderBatchRepository;
import com.cretas.aims.logistics.service.importjob.LogisticsOrderImportService;
import com.cretas.aims.logistics.service.routing.AmapClient;
import com.cretas.aims.utils.ExcelUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.alibaba.excel.annotation.ExcelProperty;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 物流订单导入两段式实现 (handoff §8.2, spec §2/§7)。
 *
 * <p><b>查询规则</b>: "当前/可排线" 订单 = 所在批次 {@code status=COMMITTED}。preview 阶段
 * 落库的订单归属 {@code status=PREVIEWED} 批次，不算 live（Phase 3 路线生成读取时必须
 * 按批次状态过滤，本 service 只负责 import 侧）。
 *
 * <p><b>幂等</b>: {@code (factory_id, business_date, source_fingerprint)} 唯一。同一文件
 * 内容重复上传：批次仍是 PREVIEWED 则重新解析+替换其订单（软删除旧行, 插入新解析结果,
 * 允许模板/校验规则升级后重新预检同一文件）；批次已 COMMITTED/PLANNED 则只读复用（订单
 * 已是 live 数据, 不重新写)。commit 对已 COMMITTED/PLANNED 批次直接幂等返回, 不重复提交。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LogisticsOrderImportServiceImpl implements LogisticsOrderImportService {

    private final LogisticsOrderBatchRepository batchRepo;
    private final LogisticsDeliveryOrderRepository deliveryOrderRepo;
    private final ExcelUtil excelUtil;
    private final AmapClient amapClient;

    private static final String SHEET_NAME = "物流订单导入模板";
    private static final int WINDOW_MAX_LEN = 8;
    /**
     * 单次 commit 内自动地理编码的最大调用数上限 (spec: ≤ ~50) — 保护
     * {@code amap.daily-query-budget} 不被单个大批次一次性打光。超出上限的订单诚实保留
     * {@code UNRESOLVED}, 不猜测/不伪造坐标 (可后续通过 {@link #updateLocation} 手工补录，
     * 或下次对该批次重新触发地理编码流程)。
     */
    private static final int GEOCODE_ON_COMMIT_CAP = 50;

    private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("yyyyMMdd"));

    // ==================== 模板 ====================

    @Override
    public byte[] downloadTemplate() {
        return excelUtil.generateTemplate(LogisticsOrderImportRow.class, SHEET_NAME);
    }

    // ==================== Preview ====================

    @Override
    @Transactional
    public PreviewResultDto preview(String factoryId, MultipartFile file, Long userId) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "请上传订单文件").withHint("请选择一个 .xlsx 或 .csv 文件后重试");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            throw new BusinessException(400, "文件读取失败: " + e.getMessage())
                    .withHint("请重新选择文件后重试");
        }
        String fingerprint = sha256Hex(bytes);

        List<LogisticsOrderImportRow> rawRows;
        try {
            rawRows = parseRows(file, bytes);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("[LogisticsOrderImport] 文件解析失败 factory={} filename={}",
                    factoryId, file.getOriginalFilename(), e);
            throw new BusinessException(400, "文件解析失败: " + e.getMessage())
                    .withHint("请确认使用后端下载的模板，且未破坏表头（支持 .xlsx / .csv）");
        }

        if (rawRows.isEmpty()) {
            throw new BusinessException(400, "文件不包含任何数据行")
                    .withHint("请下载模板并至少填写一行订单");
        }

        // ---- 逐行校验 ----
        Set<String> seenStoreCodes = new HashSet<>();
        Set<String> seenFullRows = new HashSet<>();
        List<OrderImportPreviewRowDto> rows = new ArrayList<>(rawRows.size());
        List<RowErrorDto> allErrors = new ArrayList<>();
        int validRows = 0;
        LocalDate resolvedBusinessDate = null;

        for (int i = 0; i < rawRows.size(); i++) {
            int rowNumber = i + 1; // 1-based 数据行号（不含表头）
            LogisticsOrderImportRow raw = rawRows.get(i);
            List<RowErrorDto> errors = new ArrayList<>();

            String businessDateRaw = trim(raw.getBusinessDate());
            LocalDate businessDate = null;
            if (isBlank(businessDateRaw)) {
                errors.add(err(rowNumber, "业务日期", "必填字段为空"));
            } else {
                businessDate = parseDate(businessDateRaw);
                if (businessDate == null) {
                    errors.add(err(rowNumber, "业务日期", "日期格式无效，应为 yyyy-MM-dd"));
                }
            }

            String storeCode = trim(raw.getStoreCode());
            if (isBlank(storeCode)) errors.add(err(rowNumber, "订单号", "必填字段为空"));

            String storeName = trim(raw.getStoreName());
            if (isBlank(storeName)) errors.add(err(rowNumber, "门店名称", "必填字段为空"));

            String address = trim(raw.getAddress());
            if (isBlank(address)) errors.add(err(rowNumber, "配送地址", "必填字段为空"));

            Integer pieces = parseNonNegativeInt(raw.getPieces(), rowNumber, "件数", errors);
            Integer boxes = parseNonNegativeInt(raw.getBoxes(), rowNumber, "箱数", errors);
            BigDecimal weightKg = parsePositiveDecimal(raw.getWeightKg(), rowNumber, "重量kg", errors);
            BigDecimal volumeCbm = parsePositiveDecimal(raw.getVolumeCbm(), rowNumber, "体积m³", errors);

            String windowStart = trim(raw.getWindowStart());
            String windowEnd = trim(raw.getWindowEnd());
            if (!isBlank(windowStart) && windowStart.length() > WINDOW_MAX_LEN) {
                errors.add(err(rowNumber, "配送开始时间", "格式过长（最长 " + WINDOW_MAX_LEN + " 字符）"));
            }
            if (!isBlank(windowEnd) && windowEnd.length() > WINDOW_MAX_LEN) {
                errors.add(err(rowNumber, "配送结束时间", "格式过长（最长 " + WINDOW_MAX_LEN + " 字符）"));
            }

            String lonRaw = trim(raw.getLongitude());
            String latRaw = trim(raw.getLatitude());
            boolean lonPresent = !isBlank(lonRaw);
            boolean latPresent = !isBlank(latRaw);
            BigDecimal longitude = null;
            BigDecimal latitude = null;
            if (lonPresent) {
                try {
                    longitude = new BigDecimal(lonRaw);
                } catch (NumberFormatException e) {
                    errors.add(err(rowNumber, "经度", "非数字"));
                }
            }
            if (latPresent) {
                try {
                    latitude = new BigDecimal(latRaw);
                } catch (NumberFormatException e) {
                    errors.add(err(rowNumber, "纬度", "非数字"));
                }
            }
            if (lonPresent != latPresent) {
                errors.add(err(rowNumber, lonPresent ? "纬度" : "经度", "经度和纬度必须同时提供或同时留空"));
            }

            String areaCode = trim(raw.getAreaCode());

            // 同批订单号重复：保留第一次出现，后续标记为错误（不写库）
            if (!isBlank(storeCode) && !seenStoreCodes.add(storeCode)) {
                errors.add(err(rowNumber, "订单号", "订单号在文件内重复: " + storeCode));
            }

            // 整行完全重复（不仅订单号，逐列比对）
            String fullRowKey = String.join("",
                    nz(businessDateRaw), nz(storeCode), nz(storeName), nz(address),
                    nz(raw.getPieces()), nz(raw.getBoxes()), nz(raw.getWeightKg()), nz(raw.getVolumeCbm()),
                    nz(windowStart), nz(windowEnd), nz(lonRaw), nz(latRaw), nz(areaCode));
            if (!seenFullRows.add(fullRowKey)) {
                errors.add(err(rowNumber, "整行", "该行内容与文件内其他行完全重复"));
            }

            boolean valid = errors.isEmpty();
            if (valid) {
                validRows++;
            }
            // 批次归属日不要求整行完全有效——只要这一格本身能解析，就可以用来确定"这批订单是哪天的"，
            // 否则一个文件里只要有一行别的字段填错，就会连累到"完全无法识别业务日期"而整批拒收，
            // 掩盖了本应逐行报出的具体错误 (spec §8.2 精确行级错误优先于粗粒度拒收)。
            if (resolvedBusinessDate == null && businessDate != null) {
                resolvedBusinessDate = businessDate;
            }

            rows.add(OrderImportPreviewRowDto.builder()
                    .rowNumber(rowNumber)
                    .storeCode(storeCode)
                    .storeName(storeName)
                    .address(address)
                    .areaCode(areaCode)
                    .pieces(pieces)
                    .boxes(boxes)
                    .weightKg(weightKg)
                    .volumeCbm(volumeCbm)
                    .windowStart(windowStart)
                    .windowEnd(windowEnd)
                    .longitude(longitude)
                    .latitude(latitude)
                    .valid(valid)
                    .errors(errors)
                    .build());
            allErrors.addAll(errors);
        }

        int totalRows = rawRows.size();
        int errorRows = totalRows - validRows;

        // businessDate 决定批次归属；即便某些有效行不是必需相同日期，MVP 用第一条有效行的日期
        // 作为批次日期（一次上传=一天排线，符合客户"每天订单不同"的使用场景）。
        if (resolvedBusinessDate == null) {
            // 没有任何一行给出可用日期 —— 不能建批次（NOT NULL 约束），诚实报错而非猜测/伪造日期。
            throw new BusinessException(400, "无法识别业务日期，文件中每一行『业务日期』均缺失或格式无效")
                    .withHint("请检查『业务日期』列，格式应为 yyyy-MM-dd")
                    .withHintTarget("业务日期");
        }

        // ---- 幂等 upsert 批次 ----
        LogisticsOrderBatch batch = batchRepo
                .findByFactoryIdAndBusinessDateAndSourceFingerprintAndDeletedAtIsNull(
                        factoryId, resolvedBusinessDate, fingerprint)
                .orElse(null);

        boolean writeOrders;
        if (batch == null) {
            batch = LogisticsOrderBatch.builder()
                    .factoryId(factoryId)
                    .businessDate(resolvedBusinessDate)
                    .batchNumber(generateBatchNumber(factoryId, resolvedBusinessDate))
                    .sourceFilename(file.getOriginalFilename())
                    .sourceFingerprint(fingerprint)
                    .status(OrderBatchStatus.PREVIEWED)
                    .totalRows(totalRows)
                    .validRows(validRows)
                    .errorRows(errorRows)
                    .createdBy(userId != null ? String.valueOf(userId) : null)
                    .build();
            writeOrders = true;
        } else if (batch.getStatus() == OrderBatchStatus.PREVIEWED) {
            // 仍在草稿态：安全替换其订单（旧行软删除, 新解析结果重新落库）
            batch.setTotalRows(totalRows);
            batch.setValidRows(validRows);
            batch.setErrorRows(errorRows);
            batch.setSourceFilename(file.getOriginalFilename());
            List<LogisticsDeliveryOrder> existingOrders =
                    deliveryOrderRepo.findByFactoryIdAndBatchId(factoryId, batch.getId());
            if (!existingOrders.isEmpty()) {
                deliveryOrderRepo.deleteAll(existingOrders); // @SQLDelete → 软删除
                // Hibernate 默认 flush 顺序是"插入先于删除"（不管代码顺序）——如果不在这里强制 flush,
                // 下面重新插入的新行会在软删除的 UPDATE 之前执行, 与旧行撞
                // uq_ldo_batch_store(batch_id, store_code) 部分唯一索引 (旧行 deleted_at 还是 NULL)。
                deliveryOrderRepo.flush();
            }
            writeOrders = true;
        } else {
            // 已 COMMITTED/PLANNED/CANCELLED —— 订单已是既定事实，只读复用，不重写
            writeOrders = false;
        }

        batch = batchRepo.save(batch);

        if (writeOrders) {
            List<LogisticsDeliveryOrder> toInsert = new ArrayList<>(validRows);
            for (OrderImportPreviewRowDto row : rows) {
                if (!row.isValid()) continue;
                LocationStatus locationStatus = (row.getLongitude() != null && row.getLatitude() != null)
                        ? LocationStatus.RESOLVED : LocationStatus.UNRESOLVED;
                toInsert.add(LogisticsDeliveryOrder.builder()
                        .factoryId(factoryId)
                        .batchId(batch.getId())
                        .storeCode(row.getStoreCode())
                        .storeName(row.getStoreName())
                        .address(row.getAddress())
                        .areaCode(row.getAreaCode())
                        .pieces(row.getPieces())
                        .boxes(row.getBoxes())
                        .weightKg(row.getWeightKg())
                        .volumeCbm(row.getVolumeCbm())
                        .deliveryWindowStart(row.getWindowStart())
                        .deliveryWindowEnd(row.getWindowEnd())
                        .longitude(row.getLongitude())
                        .latitude(row.getLatitude())
                        .locationStatus(locationStatus)
                        .status(DeliveryOrderStatus.IMPORTED)
                        .sourceRowNumber(row.getRowNumber())
                        .build());
            }
            if (!toInsert.isEmpty()) {
                deliveryOrderRepo.saveAll(toInsert);
            }
        }

        log.info("[LogisticsOrderImport] preview factory={} jobId={} total={} valid={} errors={} ordersWritten={}",
                factoryId, batch.getId(), totalRows, validRows, errorRows, writeOrders);

        return PreviewResultDto.builder()
                .jobId(batch.getId())
                .businessDate(resolvedBusinessDate.toString())
                .sourceFilename(file.getOriginalFilename())
                .totalRows(totalRows)
                .validRows(validRows)
                .errorRows(errorRows)
                .rowErrors(allErrors)
                .rows(rows)
                .build();
    }

    // ==================== Commit ====================

    @Override
    @Transactional
    public OrderBatchDto commit(String factoryId, String jobId) {
        LogisticsOrderBatch batch = batchRepo.findByIdAndFactoryId(jobId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "导入批次不存在: " + jobId)
                        .withHint("请重新执行『预检』上传文件"));

        if (batch.getStatus() == OrderBatchStatus.COMMITTED || batch.getStatus() == OrderBatchStatus.PLANNED) {
            // 幂等：已提交过，重复点击直接返回既有批次，不重复写入
            return toDto(batch);
        }
        if (batch.getStatus() == OrderBatchStatus.CANCELLED) {
            throw new BusinessException(409, "该批次已取消，无法提交")
                    .withCode("BATCH_CANCELLED")
                    .withHint("请重新上传文件生成新的导入批次");
        }

        // status == PREVIEWED
        batch.setStatus(OrderBatchStatus.COMMITTED);
        LogisticsOrderBatch saved = batchRepo.save(batch);
        geocodeUnresolvedOrders(factoryId, saved.getId());
        log.info("[LogisticsOrderImport] commit factory={} jobId={} → COMMITTED (validRows={})",
                factoryId, jobId, saved.getValidRows());
        return toDto(saved);
    }

    /**
     * 提交时自动地理编码 — 对该批次内有地址但缺经纬度的订单调用高德 geocode, 成功即置
     * {@code RESOLVED}; 失败 (key 未配置 / 地址无法解析 / 超出单次上限) 一律保持
     * {@code UNRESOLVED}, 绝不伪造坐标 (对齐 {@link AmapClient} 类头诚实降级铁律)。
     */
    private void geocodeUnresolvedOrders(String factoryId, String batchId) {
        List<LogisticsDeliveryOrder> orders = deliveryOrderRepo.findByFactoryIdAndBatchId(factoryId, batchId);
        int attempted = 0;
        int resolved = 0;
        for (LogisticsDeliveryOrder order : orders) {
            if (order.getLongitude() != null && order.getLatitude() != null) {
                continue; // 已有坐标（导入时提供 / 之前已解析）
            }
            if (order.getAddress() == null || order.getAddress().isBlank()) {
                continue; // 无地址可解析
            }
            if (attempted >= GEOCODE_ON_COMMIT_CAP) {
                break; // 单次 commit 预算保护, 剩余诚实保留 UNRESOLVED
            }
            attempted++;
            Optional<double[]> coord = amapClient.geocode(order.getAddress());
            if (coord.isEmpty()) {
                continue; // 诚实降级: 保持 UNRESOLVED, 不猜测坐标
            }
            double[] lngLat = coord.get();
            order.setLongitude(BigDecimal.valueOf(lngLat[0]));
            order.setLatitude(BigDecimal.valueOf(lngLat[1]));
            order.setLocationStatus(LocationStatus.RESOLVED);
            deliveryOrderRepo.save(order);
            resolved++;
        }
        if (attempted > 0) {
            log.info("[LogisticsOrderImport] geocode-on-commit factory={} batch={} attempted={} resolved={}",
                    factoryId, batchId, attempted, resolved);
        }
    }

    // ==================== 查询 ====================

    @Override
    public Page<OrderBatchDto> listBatches(String factoryId, Pageable pageable) {
        return batchRepo.findByFactoryIdOrderByBusinessDateDesc(factoryId, pageable).map(this::toDto);
    }

    @Override
    public OrderBatchDto getBatch(String factoryId, String batchId) {
        return batchRepo.findByIdAndFactoryId(batchId, factoryId)
                .map(this::toDto)
                .orElseThrow(() -> new BusinessException(404, "导入批次不存在: " + batchId)
                        .withHint("请检查批次 ID 是否正确"));
    }

    @Override
    public Page<DeliveryOrderDto> listOrders(String factoryId, String batchId, Pageable pageable) {
        // 存在性校验（租户隔离）——批次不存在/不属于该工厂时明确 404，而不是静默返回空页
        batchRepo.findByIdAndFactoryId(batchId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "导入批次不存在: " + batchId)
                        .withHint("请检查批次 ID 是否正确"));
        return deliveryOrderRepo.findByFactoryIdAndBatchId(factoryId, batchId, pageable).map(this::toDto);
    }

    @Override
    @Transactional
    public DeliveryOrderDto updateLocation(String factoryId, String orderId, BigDecimal longitude, BigDecimal latitude) {
        if (longitude == null || latitude == null) {
            throw new BusinessException(400, "经度和纬度必须同时提供")
                    .withHintTarget(longitude == null ? "longitude" : "latitude");
        }
        LogisticsDeliveryOrder order = deliveryOrderRepo.findByIdAndFactoryId(orderId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "配送订单不存在: " + orderId)
                        .withHint("请检查订单 ID 是否正确"));
        order.setLongitude(longitude);
        order.setLatitude(latitude);
        order.setLocationStatus(LocationStatus.RESOLVED);
        LogisticsDeliveryOrder saved = deliveryOrderRepo.save(order);
        return toDto(saved);
    }

    // ==================== Helpers ====================

    private String generateBatchNumber(String factoryId, LocalDate businessDate) {
        long countToday = batchRepo.countByFactoryIdAndBusinessDate(factoryId, businessDate);
        String dateStr = businessDate.toString().replace("-", "");
        return String.format(Locale.ROOT, "LOG-%s-%03d", dateStr, countToday + 1);
    }

    private OrderBatchDto toDto(LogisticsOrderBatch batch) {
        return OrderBatchDto.builder()
                .id(batch.getId())
                .factoryId(batch.getFactoryId())
                .businessDate(batch.getBusinessDate())
                .batchNumber(batch.getBatchNumber())
                .sourceFilename(batch.getSourceFilename())
                .status(batch.getStatus())
                .totalRows(batch.getTotalRows())
                .validRows(batch.getValidRows())
                .errorRows(batch.getErrorRows())
                .createdBy(batch.getCreatedBy())
                .createdAt(batch.getCreatedAt())
                .version(batch.getVersion())
                .build();
    }

    private DeliveryOrderDto toDto(LogisticsDeliveryOrder order) {
        return DeliveryOrderDto.builder()
                .id(order.getId())
                .factoryId(order.getFactoryId())
                .batchId(order.getBatchId())
                .storeCode(order.getStoreCode())
                .storeName(order.getStoreName())
                .address(order.getAddress())
                .areaCode(order.getAreaCode())
                .pieces(order.getPieces())
                .boxes(order.getBoxes())
                .weightKg(order.getWeightKg())
                .volumeCbm(order.getVolumeCbm())
                .windowStart(order.getDeliveryWindowStart())
                .windowEnd(order.getDeliveryWindowEnd())
                .longitude(order.getLongitude())
                .latitude(order.getLatitude())
                .locationStatus(order.getLocationStatus())
                .status(order.getStatus())
                .sourceRowNumber(order.getSourceRowNumber())
                .version(order.getVersion())
                .build();
    }

    private static RowErrorDto err(int rowNumber, String column, String message) {
        return RowErrorDto.builder().rowNumber(rowNumber).column(column).message(message).build();
    }

    private static Integer parseNonNegativeInt(String raw, int rowNumber, String column, List<RowErrorDto> errors) {
        String v = trim(raw);
        if (isBlank(v)) {
            errors.add(err(rowNumber, column, "必填字段为空"));
            return null;
        }
        try {
            // 容忍 EasyExcel 对数值单元格可能给出的 "3.0" 形式
            BigDecimal decimal = new BigDecimal(v);
            int intValue = decimal.intValueExact();
            if (intValue < 0) {
                errors.add(err(rowNumber, column, "不能为负数"));
            }
            return intValue;
        } catch (ArithmeticException | NumberFormatException e) {
            errors.add(err(rowNumber, column, "非数字"));
            return null;
        }
    }

    private static BigDecimal parsePositiveDecimal(String raw, int rowNumber, String column, List<RowErrorDto> errors) {
        String v = trim(raw);
        if (isBlank(v)) {
            errors.add(err(rowNumber, column, "必填字段为空"));
            return null;
        }
        try {
            BigDecimal decimal = new BigDecimal(v);
            if (decimal.compareTo(BigDecimal.ZERO) <= 0) {
                errors.add(err(rowNumber, column, "必须为正数"));
            }
            return decimal;
        } catch (NumberFormatException e) {
            errors.add(err(rowNumber, column, "非数字"));
            return null;
        }
    }

    private static LocalDate parseDate(String raw) {
        String v = raw;
        // EasyExcel 有时把日期单元格转成 "yyyy-MM-dd HH:mm:ss"，只取日期部分
        int spaceIdx = v.indexOf(' ');
        if (spaceIdx > 0) {
            v = v.substring(0, spaceIdx);
        }
        for (DateTimeFormatter fmt : DATE_FORMATTERS) {
            try {
                return LocalDate.parse(v, fmt);
            } catch (DateTimeParseException ignored) {
                // try next formatter
            }
        }
        return null;
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    // ==================== 文件解析 (.xlsx / .csv) ====================

    /**
     * 按文件扩展名分发解析：{@code .csv} 走内置 quote-aware CSV 解析，其余(默认 {@code .xlsx})走 EasyExcel。
     * 两条路径都产出同一份 {@link LogisticsOrderImportRow} 列表，后续校验/落库逻辑与格式无关。
     */
    private List<LogisticsOrderImportRow> parseRows(MultipartFile file, byte[] bytes) {
        String name = file.getOriginalFilename();
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".csv")) {
            return parseCsv(bytes);
        }
        return excelUtil.importFromExcel(new ByteArrayInputStream(bytes), LogisticsOrderImportRow.class);
    }

    /**
     * CSV → {@code List<LogisticsOrderImportRow>}。表头按 {@link ExcelProperty} 标签匹配字段(与 Excel 模板
     * 表头一致、列序可变)，未识别的列忽略。UTF-8(容忍 BOM)，支持带引号字段(内含逗号/换行/双引号转义)。
     */
    private static List<LogisticsOrderImportRow> parseCsv(byte[] bytes) {
        String content = new String(bytes, StandardCharsets.UTF_8);
        if (content.startsWith("﻿")) {
            content = content.substring(1); // strip UTF-8 BOM
        }
        List<List<String>> table = splitCsv(content);
        if (table.isEmpty()) {
            return new ArrayList<>();
        }

        // @ExcelProperty 标签 -> 字段
        Map<String, Field> labelToField = new HashMap<>();
        for (Field f : LogisticsOrderImportRow.class.getDeclaredFields()) {
            ExcelProperty ep = f.getAnnotation(ExcelProperty.class);
            if (ep != null && ep.value().length > 0 && !ep.value()[0].isBlank()) {
                f.setAccessible(true);
                labelToField.put(ep.value()[0].trim(), f);
            }
        }
        // 列序号 -> 字段
        List<String> header = table.get(0);
        Map<Integer, Field> colToField = new HashMap<>();
        for (int c = 0; c < header.size(); c++) {
            String h = header.get(c) == null ? "" : header.get(c).trim();
            Field f = labelToField.get(h);
            if (f != null) {
                colToField.put(c, f);
            }
        }
        if (colToField.isEmpty()) {
            throw new BusinessException(400, "CSV 表头无法识别")
                    .withHint("请使用后端下载的模板表头（业务日期/订单号/门店名称/... ），或改用 .xlsx");
        }

        List<LogisticsOrderImportRow> rows = new ArrayList<>();
        for (int r = 1; r < table.size(); r++) {
            List<String> cols = table.get(r);
            if (cols.stream().allMatch(s -> s == null || s.isBlank())) {
                continue; // 跳过整行空白
            }
            LogisticsOrderImportRow row = new LogisticsOrderImportRow();
            for (Map.Entry<Integer, Field> e : colToField.entrySet()) {
                int idx = e.getKey();
                if (idx < cols.size()) {
                    try {
                        e.getValue().set(row, cols.get(idx));
                    } catch (IllegalAccessException ignore) {
                        // setAccessible(true) 已放开，理论不可达
                    }
                }
            }
            rows.add(row);
        }
        return rows;
    }

    /**
     * 最小 RFC-4180 CSV 解析：支持双引号字段(内含逗号/换行/{@code ""} 转义)，行分隔 {@code \n}(容忍 {@code \r\n})。
     */
    private static List<List<String>> splitCsv(String content) {
        List<List<String>> table = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                        field.append('"');
                        i++; // 转义的双引号
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(ch);
                }
            } else if (ch == '"') {
                inQuotes = true;
            } else if (ch == ',') {
                cur.add(field.toString());
                field.setLength(0);
            } else if (ch == '\r') {
                // 忽略，行结束由 \n 处理
            } else if (ch == '\n') {
                cur.add(field.toString());
                field.setLength(0);
                table.add(cur);
                cur = new ArrayList<>();
            } else {
                field.append(ch);
            }
        }
        // 文件末尾无换行时补最后一格/行
        if (field.length() > 0 || !cur.isEmpty()) {
            cur.add(field.toString());
            table.add(cur);
        }
        return table;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 保证提供的算法，理论不可达；万一发生也不能静默用弱指纹
            throw new IllegalStateException("SHA-256 不可用，无法计算文件指纹", e);
        }
    }
}
