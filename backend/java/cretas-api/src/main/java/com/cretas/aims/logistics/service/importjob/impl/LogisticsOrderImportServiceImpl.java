package com.cretas.aims.logistics.service.importjob.impl;

import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.logistics.dto.importjob.DeliveryOrderDto;
import com.cretas.aims.logistics.dto.importjob.LogisticsOrderImportRow;
import com.cretas.aims.logistics.dto.importjob.LogisticsOrderTemplateRow;
import com.cretas.aims.logistics.dto.importjob.ManualOrderCreateRequest;
import com.cretas.aims.logistics.dto.importjob.ManualOrderRow;
import com.cretas.aims.logistics.dto.importjob.OrderBatchDto;
import com.cretas.aims.logistics.dto.importjob.OrderImportPreviewRowDto;
import com.cretas.aims.logistics.dto.importjob.PreviewResultDto;
import com.cretas.aims.logistics.dto.importjob.RowErrorDto;
import com.cretas.aims.logistics.entity.LogisticsDeliveryOrder;
import com.cretas.aims.logistics.entity.LogisticsOrderBatch;
import com.cretas.aims.logistics.entity.LogisticsStoreMaster;
import com.cretas.aims.logistics.entity.enums.DeliveryOrderStatus;
import com.cretas.aims.logistics.entity.enums.LocationStatus;
import com.cretas.aims.logistics.entity.enums.OrderBatchStatus;
import com.cretas.aims.logistics.entity.enums.StoreMasterSource;
import com.cretas.aims.logistics.repository.LogisticsDeliveryOrderRepository;
import com.cretas.aims.logistics.repository.LogisticsOrderBatchRepository;
import com.cretas.aims.logistics.repository.LogisticsStoreMasterRepository;
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
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
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
    private final LogisticsStoreMasterRepository storeMasterRepo;

    private static final String SHEET_NAME = "物流订单导入模板";
    /** 模板第 2 行示例的哨兵订单号 —— 导入时按此精确匹配自动跳过示例行，用户不必删除，系统自动识别忽略。 */
    private static final String EXAMPLE_STORE_CODE = "示例数据·系统自动忽略此行";
    private static final int WINDOW_MAX_LEN = 8;
    /**
     * 单次 commit 内自动地理编码的最大调用数上限 (spec: ≤ ~50) — 保护
     * {@code amap.daily-query-budget} 不被单个大批次一次性打光。超出上限的订单诚实保留
     * {@code UNRESOLVED}, 不猜测/不伪造坐标 (可后续通过 {@link #updateLocation} 手工补录，
     * 或下次对该批次重新触发地理编码流程)。
     */
    private static final int GEOCODE_ON_COMMIT_CAP = 50;

    // 容忍常见 Excel 日期格式：横杠/斜杠 + 有无前导零。客户真实下单文件常见 2026/7/13（月/日无前导零）。
    // "M"/"d" 单字母 pattern 同时接受 1 位和 2 位（"7" 与 "07" 均可），故已覆盖 yyyy-MM-dd / yyyy/MM/dd。
    private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
            DateTimeFormatter.ofPattern("yyyy-M-d"),
            DateTimeFormatter.ofPattern("yyyy/M/d"),
            DateTimeFormatter.ofPattern("yyyyMMdd"));

    // ==================== 模板 ====================

    @Override
    public byte[] downloadTemplate() {
        // 模板 = 表头 + 第 2 行填好的示例，让用户照着填。示例订单号带「系统自动忽略」标注，
        // preview 会自动跳过这行 —— 用户不必删除，系统自动识别忽略。
        // 仅保留最必要的 6 列（订单号/门店名称/配送地址/箱数/重量kg/体积m³）。业务日期/件数/配送时间/
        // 经纬度/区域是选填，不放进下载模板避免客户困惑；导入解析仍用全列 LogisticsOrderImportRow，
        // 客户文件带这些额外列也能读。示例订单号带哨兵，preview 自动跳过本行。
        LogisticsOrderTemplateRow example = new LogisticsOrderTemplateRow();
        example.setStoreCode(EXAMPLE_STORE_CODE);   // 哨兵：导入时自动跳过本行
        example.setStoreName("沃尔玛浦东店");
        example.setAddress("上海市浦东新区世纪大道100号");
        example.setBoxes("2");
        example.setWeightKg("250");
        example.setVolumeCbm("1.5");
        return excelUtil.exportToExcel(List.of(example), LogisticsOrderTemplateRow.class, SHEET_NAME);
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

        // 跳过模板自带的示例行（哨兵订单号）—— 用户不必删除，系统自动识别忽略。
        rawRows = rawRows.stream()
                .filter(r -> !EXAMPLE_STORE_CODE.equals(trim(r.getStoreCode())))
                .toList();

        if (rawRows.isEmpty()) {
            throw new BusinessException(400, "文件不包含任何数据行")
                    .withHint("请下载模板并至少填写一行订单");
        }

        return buildPreviewFromRawRows(factoryId, rawRows, file.getOriginalFilename(), fingerprint, userId);
    }

    // ==================== Preview: manual (non-file) entry ====================

    /**
     * POST /order-import/manual — 前端表单收集的结构化行，映射为
     * {@link LogisticsOrderImportRow} 后复用与 xlsx/csv 上传完全相同的
     * {@link #buildPreviewFromRawRows} 流程 (逐行校验 + 批次/订单创建)，不重复实现校验逻辑。
     */
    @Override
    @Transactional
    public PreviewResultDto previewManual(String factoryId, ManualOrderCreateRequest request, Long userId) {
        if (request == null || request.getRows() == null || request.getRows().isEmpty()) {
            throw new BusinessException(400, "至少录入一行订单")
                    .withHint("请至少填写一行订单信息后提交");
        }

        String businessDate = trim(request.getBusinessDate());
        // 手动录入的 订单号(storeCode) 防呆自动生成: 调度员不需要凭空编订单号 (fool-proof-design —
        // "你告诉他这个东西你要收多少就行了")。留空时按 SM-{紧凑业务日期}-{行号} 确定性生成 (同内容重复
        // 提交生成相同编号 → 幂等语义不破; 行号保证批内唯一, 通过 seenStoreCodes 去重校验)。
        String compactDate = businessDate == null ? "NA" : businessDate.replaceAll("[^0-9]", "");
        if (compactDate.isBlank()) compactDate = "NA";
        List<LogisticsOrderImportRow> rawRows = new ArrayList<>(request.getRows().size());
        int seq = 0;
        for (ManualOrderRow r : request.getRows()) {
            seq++;
            LogisticsOrderImportRow row = new LogisticsOrderImportRow();
            row.setBusinessDate(businessDate);
            String storeCode = trim(r.getStoreCode());
            row.setStoreCode(isBlank(storeCode) ? "SM-" + compactDate + "-" + seq : storeCode);
            row.setStoreName(r.getStoreName());
            row.setAddress(r.getAddress());
            // 数量：件数 / 箱数 二选一，直接透传，由 buildPreviewFromRawRows 统一校验（至少填一项）。
            row.setPieces(r.getPieces());
            row.setBoxes(r.getBoxes());
            row.setWeightKg(r.getWeightKg());
            row.setVolumeCbm(r.getVolumeCbm());
            row.setWindowStart(r.getWindowStart());
            row.setWindowEnd(r.getWindowEnd());
            row.setLongitude(r.getLongitude());
            row.setLatitude(r.getLatitude());
            row.setAreaCode(r.getAreaCode());
            rawRows.add(row);
        }

        String fingerprint = manualFingerprint(businessDate, rawRows);
        return buildPreviewFromRawRows(factoryId, rawRows, "手动录入", fingerprint, userId);
    }

    /**
     * {@code sha256(businessDate + 逐行逐列拼接)} —— manual 路径没有文件字节可指纹，
     * 用与 {@link #preview} 相同精神的"内容指纹"复刻幂等语义 (相同内容重复提交 →
     * 复用既有 PREVIEWED 批次，见 {@link #buildPreviewFromRawRows})。
     */
    private static String manualFingerprint(String businessDate, List<LogisticsOrderImportRow> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(nz(businessDate));
        for (LogisticsOrderImportRow r : rows) {
            sb.append('|')
                    .append(nz(r.getStoreCode())).append(',')
                    .append(nz(r.getStoreName())).append(',')
                    .append(nz(r.getAddress())).append(',')
                    .append(nz(r.getPieces())).append(',')
                    .append(nz(r.getBoxes())).append(',')
                    .append(nz(r.getWeightKg())).append(',')
                    .append(nz(r.getVolumeCbm())).append(',')
                    .append(nz(r.getWindowStart())).append(',')
                    .append(nz(r.getWindowEnd())).append(',')
                    .append(nz(r.getLongitude())).append(',')
                    .append(nz(r.getLatitude())).append(',')
                    .append(nz(r.getAreaCode()));
        }
        return sha256Hex(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ==================== Preview: shared core (xlsx/csv + manual) ====================

    /**
     * xlsx/csv 上传与手动录入共享的核心流程：逐行校验 → 幂等 upsert 批次(PREVIEWED) →
     * 落库有效行 → 返回 {@link PreviewResultDto}。两条入口路径在此之前各自产出
     * {@code rawRows} + {@code fingerprint} 的方式不同 (文件字节 sha256 vs 内容拼接
     * sha256)，此后逻辑必须完全一致，不允许分叉 (spec §8.2 / handoff §11.1 两条路径
     * 校验规则/批次字段/返回 DTO 形状一致)。
     *
     * @param sourceLabel 批次 {@code sourceFilename} 字段的值 —— 文件路径传原始文件名，
     *                    manual 路径传 "手动录入" 固定标签。
     */
    private PreviewResultDto buildPreviewFromRawRows(String factoryId, List<LogisticsOrderImportRow> rawRows,
            String sourceLabel, String fingerprint, Long userId) {
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

            // 业务日期【选填】：客户下单文件常不带日期。留空不报错，整批都没有效日期时在下方兜底用当天
            // （一次导入 = 当天排线）。填了则容忍横杠/斜杠、有无前导零（如 2026-07-13 / 2026/7/13）。
            String businessDateRaw = trim(raw.getBusinessDate());
            LocalDate businessDate = null;
            if (!isBlank(businessDateRaw)) {
                businessDate = parseDate(businessDateRaw);
                if (businessDate == null) {
                    errors.add(err(rowNumber, "业务日期", "日期格式无效，示例：2026-07-13 或 2026/7/13"));
                }
            }

            String storeCode = trim(raw.getStoreCode());
            if (isBlank(storeCode)) errors.add(err(rowNumber, "订单号", "必填字段为空"));

            String storeName = trim(raw.getStoreName());
            if (isBlank(storeName)) errors.add(err(rowNumber, "门店名称", "必填字段为空"));

            String address = trim(raw.getAddress());
            if (isBlank(address)) errors.add(err(rowNumber, "配送地址", "必填字段为空"));

            // 数量：件数 / 箱数 二选一（门店下单的数量通常就是"多少箱货"）——至少填一个即可，
            // 另一个留空按 0。都留空才报错。填了的那个仍校验非负整数。
            boolean piecesBlank = isBlank(raw.getPieces());
            boolean boxesBlank = isBlank(raw.getBoxes());
            // 注意用 Integer.valueOf(0) 而非字面量 0：三元 `cond ? 0 : Integer` 会把 Integer 分支拆箱成 int，
            // 非数字时 parseNonNegativeInt 返回 null → 拆箱 NPE。装箱后保持 Integer，null 安全传递给校验。
            Integer pieces = piecesBlank ? Integer.valueOf(0) : parseNonNegativeInt(raw.getPieces(), rowNumber, "件数", errors);
            Integer boxes = boxesBlank ? Integer.valueOf(0) : parseNonNegativeInt(raw.getBoxes(), rowNumber, "箱数", errors);
            if (piecesBlank && boxesBlank) {
                errors.add(err(rowNumber, "数量", "件数、箱数至少填一项"));
            }
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
            // 业务日期【选填】：整批都没填有效日期时，默认用当天（一次导入 = 当天排线，符合客户
            // "日期列可以不填" 的诉求）。不再因缺日期整批拒收。
            resolvedBusinessDate = LocalDate.now();
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
                    .sourceFilename(sourceLabel)
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
            batch.setSourceFilename(sourceLabel);
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
                .sourceFilename(sourceLabel)
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
        resolveOrderCoordinates(factoryId, saved.getId());
        log.info("[LogisticsOrderImport] commit factory={} jobId={} → COMMITTED (validRows={})",
                factoryId, jobId, saved.getValidRows());
        return toDto(saved);
    }

    /**
     * 提交时解析该批次内缺经纬度的订单坐标 —— "解析一次, 逐日复用" (客户第一诉求): 每家门店的
     * 坐标只应该被解析/修正一次, 而不是天天对同一批 ~200 家门店重新 geocode。逐单两段式:
     *
     * <ol>
     *   <li><b>复用 {@link LogisticsStoreMaster}(免费, 不占 {@link #GEOCODE_ON_COMMIT_CAP})</b>
     *       —— 按 (factoryId, 归一化门店名称) 查主数据, 命中且已有坐标直接复制给订单, 一分钟省一次
     *       高德调用。</li>
     *   <li><b>否则回落地理编码</b>(占用每次 commit 的调用上限) —— 成功即置 {@code RESOLVED} 并
     *       upsert 门店主数据 (source=GEOCODED), 下次同名门店导入即可直接命中第一步; 失败
     *       (key 未配置 / 地址无法解析 / 超出单次上限) 一律保持 {@code UNRESOLVED}, 绝不伪造坐标
     *       (对齐 {@link AmapClient} 类头诚实降级铁律)。</li>
     * </ol>
     *
     * <p>订单本身导入时就自带坐标 (文件行提供 / 手动录入表单填写) 的场景不进入上述两段 ——
     * 直接用订单自带坐标播种/更新门店主数据 (source=IMPORT), 同样不占用预算。
     *
     * <p>预算上限只约束"实际发起的地理编码调用次数"; 命中主数据复用的订单不计入, 也不会因为
     * 上限触发而提前 {@code break} 整个循环 (后面还没处理到的订单里可能有能免费复用主数据的)。
     */
    private void resolveOrderCoordinates(String factoryId, String batchId) {
        List<LogisticsDeliveryOrder> orders = deliveryOrderRepo.findByFactoryIdAndBatchId(factoryId, batchId);
        int geocodeAttempted = 0;
        int geocodeResolved = 0;
        int reusedFromMaster = 0;
        for (LogisticsDeliveryOrder order : orders) {
            String normalizedName = normalizeStoreName(order.getStoreName());

            if (order.getLongitude() != null && order.getLatitude() != null) {
                // 已有坐标（导入时提供 / 之前已解析）—— 用这份坐标播种/更新门店主数据，
                // 让下次同名门店即使缺坐标也能直接复用，不必等它触发地理编码。
                upsertStoreMasterCoords(factoryId, normalizedName, order.getAddress(), order.getAreaCode(),
                        order.getLongitude(), order.getLatitude(), StoreMasterSource.IMPORT);
                continue;
            }

            // 1) 免费复用：门店主数据已有该门店的已解析坐标
            Optional<LogisticsStoreMaster> master = (normalizedName == null)
                    ? Optional.empty()
                    : storeMasterRepo.findByFactoryIdAndStoreNameAndDeletedAtIsNull(factoryId, normalizedName);
            if (master.isPresent() && master.get().getLongitude() != null && master.get().getLatitude() != null) {
                LogisticsStoreMaster m = master.get();
                order.setLongitude(m.getLongitude());
                order.setLatitude(m.getLatitude());
                order.setLocationStatus(LocationStatus.RESOLVED);
                deliveryOrderRepo.save(order);
                reusedFromMaster++;
                continue;
            }

            // 2) 未命中主数据 —— 回落地理编码（占用预算）
            if (order.getAddress() == null || order.getAddress().isBlank()) {
                continue; // 无地址可解析
            }
            if (geocodeAttempted >= GEOCODE_ON_COMMIT_CAP) {
                continue; // 单次 commit 预算保护；诚实保留 UNRESOLVED，不阻断后续订单的免费复用检查
            }
            geocodeAttempted++;
            Optional<double[]> coord = amapClient.geocode(order.getAddress());
            if (coord.isEmpty()) {
                continue; // 诚实降级: 保持 UNRESOLVED, 不猜测坐标, 也不污染门店主数据
            }
            double[] lngLat = coord.get();
            BigDecimal longitude = BigDecimal.valueOf(lngLat[0]);
            BigDecimal latitude = BigDecimal.valueOf(lngLat[1]);
            order.setLongitude(longitude);
            order.setLatitude(latitude);
            order.setLocationStatus(LocationStatus.RESOLVED);
            deliveryOrderRepo.save(order);
            geocodeResolved++;
            upsertStoreMasterCoords(factoryId, normalizedName, order.getAddress(), order.getAreaCode(),
                    longitude, latitude, StoreMasterSource.GEOCODED);
        }
        if (geocodeAttempted > 0 || reusedFromMaster > 0) {
            log.info("[LogisticsOrderImport] resolve-coordinates factory={} batch={} reusedFromStoreMaster={} "
                            + "geocodeAttempted={} geocodeResolved={}",
                    factoryId, batchId, reusedFromMaster, geocodeAttempted, geocodeResolved);
        }
    }

    /**
     * 创建/更新门店主数据的坐标 —— 按 (factoryId, 归一化门店名称) upsert。
     *
     * <p>调度员手工修正 ({@code source=MANUAL}, 见
     * {@code LogisticsResourceServiceImpl#updateStoreMaster}) 是"改一次以后就不用管了"的最终事实
     * 来源：自动路径 (GEOCODED / IMPORT) 绝不静默覆盖已有的 MANUAL 记录，否则次日导入携带旧/错
     * 坐标会把调度员刚修好的门店重新带偏 (fool-proof-design "改一次以后就不用管了" 的反面)。
     */
    private void upsertStoreMasterCoords(String factoryId, String normalizedName, String address, String areaCode,
            BigDecimal longitude, BigDecimal latitude, StoreMasterSource source) {
        if (normalizedName == null) {
            return; // 无门店名称可归档 (理论上店名必填, 防御)
        }
        Optional<LogisticsStoreMaster> existing =
                storeMasterRepo.findByFactoryIdAndStoreNameAndDeletedAtIsNull(factoryId, normalizedName);
        if (existing.isPresent() && existing.get().getSource() == StoreMasterSource.MANUAL) {
            return; // 保护调度员的手工修正，不被自动路径覆盖
        }
        LogisticsStoreMaster master = existing.orElseGet(() -> LogisticsStoreMaster.builder()
                .factoryId(factoryId)
                .storeName(normalizedName)
                .build());
        master.setLongitude(longitude);
        master.setLatitude(latitude);
        master.setLocationStatus(LocationStatus.RESOLVED);
        master.setSource(source);
        if (address != null && !address.isBlank()) {
            master.setAddress(address);
        }
        if (areaCode != null && !areaCode.isBlank()) {
            master.setAreaCode(areaCode);
        }
        storeMasterRepo.save(master);
    }

    /** trim + 折叠内部空白, 不 lowercase (中文场景) —— 门店主数据查重键归一化, 见 {@link LogisticsStoreMaster} 类注释。 */
    private static String normalizeStoreName(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim().replaceAll("\\s+", " ");
        return trimmed.isBlank() ? null : trimmed;
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
        // xlsx 也走「读原始表格 → 规范化表头匹配」（与 CSV 同一套），容忍表头带换行/空格（如「重量\nkg」）、
        // 列顺序不同 —— 客户真实文件表头常不规范，EasyExcel 精确匹配会整列漏读（曾导致「重量」全空）。
        return parseTable(readXlsxRaw(bytes));
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
        return parseTable(splitCsv(content));
    }

    /**
     * 原始二维表格（第 0 行表头 + 其余数据行）→ {@code LogisticsOrderImportRow} 列表。
     * 表头按 {@link ExcelProperty} 标签匹配字段，列序可变；表头做规范化（去掉所有空白含换行）后再比对，
     * 容忍客户文件的「重量\nkg」「重量 kg」等表头变体 —— 否则整列漏读（曾导致「重量」全空）。
     */
    private static List<LogisticsOrderImportRow> parseTable(List<List<String>> table) {
        if (table.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, Field> labelToField = new HashMap<>();
        for (Field f : LogisticsOrderImportRow.class.getDeclaredFields()) {
            ExcelProperty ep = f.getAnnotation(ExcelProperty.class);
            if (ep != null && ep.value().length > 0 && !ep.value()[0].isBlank()) {
                f.setAccessible(true);
                labelToField.put(normKey(ep.value()[0]), f);
            }
        }
        List<String> header = table.get(0);
        Map<Integer, Field> colToField = new HashMap<>();
        for (int c = 0; c < header.size(); c++) {
            Field f = labelToField.get(normKey(header.get(c)));
            if (f != null) {
                colToField.put(c, f);
            }
        }
        if (colToField.isEmpty()) {
            throw new BusinessException(400, "表头无法识别")
                    .withHint("请使用后端下载的模板表头（业务日期/订单号/门店名称/... ）");
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

    /** 表头规范化：去掉所有空白（含换行/制表符/全半角空格）后比对，容忍表头单元格的换行/空格变体。 */
    private static String normKey(String s) {
        return s == null ? "" : s.replaceAll("[\\s\\u3000]+", "");
    }

    /** 用 POI 把 xlsx 读成原始二维表格（每格转字符串），交给 {@link #parseTable} 统一匹配。 */
    private static List<List<String>> readXlsxRaw(byte[] bytes) {
        List<List<String>> table = new ArrayList<>();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getNumberOfSheets() > 0 ? wb.getSheetAt(0) : null;
            if (sheet == null) {
                return table;
            }
            DataFormatter fmt = new DataFormatter();
            for (Row row : sheet) {
                List<String> cells = new ArrayList<>();
                short last = row.getLastCellNum();
                for (int c = 0; c < last; c++) {
                    Cell cell = row.getCell(c, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                    cells.add(cellToString(cell, fmt));
                }
                table.add(cells);
            }
        } catch (IOException | RuntimeException e) {
            throw new BusinessException(400, "Excel 解析失败: " + e.getMessage())
                    .withHint("请确认是有效的 .xlsx 文件（或改用后端下载的模板）");
        }
        return table;
    }

    /** POI 单元格 → 字符串：数值不带科学计数/多余小数，日期转 yyyy-MM-dd，公式取计算结果。 */
    private static String cellToString(Cell cell, DataFormatter fmt) {
        if (cell == null) {
            return "";
        }
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield new java.text.SimpleDateFormat("yyyy-MM-dd").format(cell.getDateCellValue());
                }
                double d = cell.getNumericCellValue();
                yield (d == Math.rint(d) && !Double.isInfinite(d))
                        ? String.valueOf((long) d)
                        : BigDecimal.valueOf(d).toPlainString();
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> fmt.formatCellValue(cell);
            default -> "";
        };
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
