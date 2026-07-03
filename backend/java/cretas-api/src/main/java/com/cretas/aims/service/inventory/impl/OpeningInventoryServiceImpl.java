package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.dto.finance.OpeningApCorrectionRequest;
import com.cretas.aims.dto.finance.OpeningApCorrectionResult;
import com.cretas.aims.dto.finance.VoucherEntrySpec;
import com.cretas.aims.dto.material.OpeningInventoryItem;
import com.cretas.aims.dto.material.OpeningInventoryRequest;
import com.cretas.aims.dto.material.OpeningInventoryResult;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.InboundType;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.EncodingRuleService;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.factory.WarehouseInventoryGuardService;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.finance.ArApService;
import com.cretas.aims.service.inventory.OpeningInventoryService;
import com.cretas.aims.service.voucher.VoucherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@link OpeningInventoryService} 实现 —— 见接口注释。
 *
 * <p>会计约定 (China GAAP, 与 FactoryStocktakeServiceImpl OPENING 分支一致, Steve 2026-07-02 确认):
 * 期初存货 借 1403 原材料 / 贷 4001 实收资本。绝不进 6301 (会虚增建账当期损益), 绝不挂 2202 供应商应付。
 */
@Service
public class OpeningInventoryServiceImpl implements OpeningInventoryService {

    private static final Logger log = LoggerFactory.getLogger(OpeningInventoryServiceImpl.class);

    // -------- 科目 (mirror FactoryStocktakeServiceImpl OPENING 分支) --------
    private static final String SUBJECT_INVENTORY_CODE = "1403";
    private static final String SUBJECT_INVENTORY_NAME = "原材料";
    private static final String SUBJECT_OPENING_EQUITY_CODE = "4001";
    private static final String SUBJECT_OPENING_EQUITY_NAME = "实收资本";

    /** 期初建账批次的来源单据类型 (存 material_batches.source_doc_type)。no-AP。*/
    private static final String OPENING_SOURCE_DOC_TYPE = "OPENING";
    /** 期初建账凭证来源业务类型 (存 voucher.source_business_type; batchKey 为 source_business_id, 组成幂等键)。*/
    private static final String VOUCHER_SOURCE_TYPE_OPENING = "OPENING_INVENTORY";
    /** 幽灵应付修正的期初凭证来源业务类型 (与被修正的应付ID组成幂等键)。*/
    private static final String VOUCHER_SOURCE_TYPE_AP_CORRECTION = "OPENING_INVENTORY_AP_CORRECTION";

    private final MaterialBatchRepository materialBatchRepository;
    private final RawMaterialTypeRepository materialTypeRepository;
    private final WarehouseResolver warehouseResolver;
    private final VoucherService voucherService;
    private final ArApService arApService;
    private final MaterialBatchService materialBatchService;

    @Autowired(required = false)
    private WarehouseInventoryGuardService warehouseInventoryGuardService;
    @Autowired(required = false)
    private EncodingRuleService encodingRuleService;
    @Autowired(required = false)
    private ApplicationEventPublisher applicationEventPublisher;

    /**
     * 自注入 (proxy) 以便逐笔 AP 修正走<b>独立事务</b>。
     *
     * <p>⛔ doomed-tx 防护 (与 recordPayableIfAbsent 同源教训): {@code reverseOpeningPayable} 是
     * {@code @Transactional} 且对"类型不符/不存在"抛 {@code BusinessException}(RuntimeException)。若
     * 编排方法用一个共享事务循环调用, 任一笔抛异常会把共享事务标记 rollback-only, catch 也救不回,
     * 外层 commit 抛 {@code UnexpectedRollbackException} —— 一笔坏数据毁掉整批修正。故编排方法<b>不</b>开事务,
     * 每笔经 self-proxy 调 {@link #correctSingleOpeningAp} 拿<b>各自独立事务</b>: 单笔失败只回滚自己,
     * 其余照常修正。
     */
    @Autowired
    @org.springframework.context.annotation.Lazy
    private OpeningInventoryServiceImpl self;

    public OpeningInventoryServiceImpl(MaterialBatchRepository materialBatchRepository,
                                       RawMaterialTypeRepository materialTypeRepository,
                                       WarehouseResolver warehouseResolver,
                                       VoucherService voucherService,
                                       ArApService arApService,
                                       MaterialBatchService materialBatchService) {
        this.materialBatchRepository = materialBatchRepository;
        this.materialTypeRepository = materialTypeRepository;
        this.warehouseResolver = warehouseResolver;
        this.voucherService = voucherService;
        this.arApService = arApService;
        this.materialBatchService = materialBatchService;
    }

    // =====================================================================
    // 1) 期初建账
    // =====================================================================

    @Override
    @Transactional
    public OpeningInventoryResult createOpeningInventory(String factoryId, OpeningInventoryRequest request, Long userId) {
        if (request == null || request.getItems() == null || request.getItems().isEmpty()) {
            throw new BusinessException(400, "期初建账明细不能为空").withHintTarget("items");
        }
        List<OpeningInventoryItem> items = request.getItems();
        String batchKey = resolveBatchKey(factoryId, request);

        // ---- 幂等: 该 batchKey 已建过 → 返回既有结果, 不双建/不双过账 ----
        List<MaterialBatch> existing = materialBatchRepository
                .findByFactoryIdAndSourceDocTypeAndSourceDocIdOrderByBatchNumberAsc(
                        factoryId, OPENING_SOURCE_DOC_TYPE, batchKey);
        if (!existing.isEmpty()) {
            Voucher existingVoucher = voucherService
                    .findBySourceBusiness(VOUCHER_SOURCE_TYPE_OPENING, batchKey).orElse(null);
            log.info("期初建账幂等命中: factoryId={}, batchKey={}, batches={}, voucherId={}",
                    factoryId, batchKey, existing.size(),
                    existingVoucher != null ? existingVoucher.getId() : null);
            return buildResultFromExisting(existing, existingVoucher, batchKey);
        }

        BigDecimal totalOpeningValue = BigDecimal.ZERO;
        int priced = 0;
        int uncosted = 0;
        List<String> batchIds = new ArrayList<>();
        List<String> batchNumbers = new ArrayList<>();

        String notes = (request.getRemark() != null && !request.getRemark().isBlank())
                ? "期初建账: " + request.getRemark()
                : "期初建账";

        for (OpeningInventoryItem item : items) {
            // 建批次 (数量 = 期初数量) —— 与建壳共用 buildAndSaveOpeningBatch, 保证字段一致。
            MaterialBatch batch = buildAndSaveOpeningBatch(
                    factoryId, item, batchKey, item.getQuantity(), notes, userId);
            batchIds.add(batch.getId());
            batchNumbers.add(batch.getBatchNumber());

            if (item.getUnitPrice() != null && item.getUnitPrice().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal lineValue = batch.getReceiptQuantity()
                        .multiply(item.getUnitPrice()).setScale(2, RoundingMode.HALF_UP);
                totalOpeningValue = totalOpeningValue.add(lineValue);
                priced++;
                // 期初库存建立移动平均价基线。
                try {
                    materialBatchService.recalculateMovingAvgPrice(
                            item.getMaterialTypeId(), batch.getReceiptQuantity(), item.getUnitPrice(), batch.getId());
                } catch (Exception e) {
                    log.warn("期初建账更新移动均价失败 (非阻塞): materialTypeId={}, err={}",
                            item.getMaterialTypeId(), e.getMessage());
                }
            } else {
                uncosted++;
                log.warn("期初建账诚实-null: 未录单价, 建批次但不计入凭证金额 batchNumber={} materialTypeId={} qty={}",
                        batch.getBatchNumber(), item.getMaterialTypeId(), batch.getReceiptQuantity());
            }
            // 注: MaterialBatchCreatedEvent 已在 buildAndSaveOpeningBatch 内发布, 此处不再重复。
        }

        // ---- 过一张期初凭证 (借 1403 / 贷 4001 = Σ数量×单价) ----
        String voucherId = null;
        String voucherNumber = null;
        if (totalOpeningValue.compareTo(BigDecimal.ZERO) > 0) {
            List<VoucherEntrySpec> entries = new ArrayList<>();
            entries.add(new VoucherEntrySpec(SUBJECT_INVENTORY_CODE, SUBJECT_INVENTORY_NAME,
                    totalOpeningValue, null, "期初建账入库 " + batchKey));
            entries.add(new VoucherEntrySpec(SUBJECT_OPENING_EQUITY_CODE, SUBJECT_OPENING_EQUITY_NAME,
                    null, totalOpeningValue, "期初建账权益 " + batchKey));
            String summary = "期初建账 借1403原材料/贷4001实收资本"
                    + (request.getRemark() != null && !request.getRemark().isBlank() ? " " + request.getRemark() : "");
            Voucher voucher = voucherService.createManual(factoryId, VoucherType.INVENTORY_STOCKTAKE,
                    LocalDate.now(), entries, VOUCHER_SOURCE_TYPE_OPENING, batchKey, summary, userId);
            voucherId = voucher.getId();
            voucherNumber = voucher.getVoucherNumber();
            log.info("期初建账凭证已过账: factoryId={}, batchKey={}, voucherNumber={}, total={}, priced={}, uncosted={}",
                    factoryId, batchKey, voucherNumber, totalOpeningValue, priced, uncosted);
        } else {
            log.warn("期初建账全部行未录单价, 不过凭证 (只建批次数量): factoryId={}, batchKey={}, uncosted={}",
                    factoryId, batchKey, uncosted);
        }

        return OpeningInventoryResult.builder()
                .batchKey(batchKey)
                .idempotentHit(false)
                .createdCount(batchIds.size())
                .pricedCount(priced)
                .uncostedCount(uncosted)
                .totalOpeningValue(totalOpeningValue)
                .voucherId(voucherId)
                .voucherNumber(voucherNumber)
                .batchIds(batchIds)
                .batchNumbers(batchNumbers)
                .build();
    }

    // =====================================================================
    // 1b) 盘点 OPENING create-from-zero 建壳 (期初已并入盘点, 见接口注释)
    // =====================================================================

    @Override
    @Transactional
    public MaterialBatch createOpeningBatchShell(String factoryId, OpeningInventoryItem item,
                                                 String batchKey, Long userId) {
        if (item == null || item.getMaterialTypeId() == null || item.getMaterialTypeId().isBlank()) {
            throw new BusinessException(400, "盘点期初新建批次缺少物料类型").withHintTarget("materialTypeId");
        }
        // 空壳: receiptQuantity=0, 不过凭证/不挂应付/不建移动均价基线。
        // 数量与价值由盘点 apply 的盘盈机制补入, 并计入盘点同一张期初凭证 (借1403/贷4001), 避免双过账。
        MaterialBatch shell = buildAndSaveOpeningBatch(
                factoryId, item, batchKey, BigDecimal.ZERO, "期初建账(盘点新建)", userId);
        log.info("盘点期初建壳: factoryId={}, batchNumber={}, materialTypeId={}, unitPrice={} (数量待盘盈补入)",
                factoryId, shell.getBatchNumber(), item.getMaterialTypeId(), item.getUnitPrice());
        return shell;
    }

    /**
     * 建一个期初批次并保存 + 发布 MaterialBatchCreatedEvent。期初建账 (数量=期初数量) 与
     * 盘点 OPENING 建壳 (数量=0) 共用此方法, 保证字段/来源单据/事件完全一致。凭证/应付/移动均价
     * 由各调用方按语义处理 (建壳不过凭证, 由盘点 apply 统一过账)。
     */
    private MaterialBatch buildAndSaveOpeningBatch(String factoryId, OpeningInventoryItem item,
            String batchKey, BigDecimal receiptQuantity, String notes, Long userId) {
        RawMaterialType materialType = materialTypeRepository.findById(item.getMaterialTypeId())
                .filter(mt -> factoryId.equals(mt.getFactoryId()))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "原材料类型不存在或不属于当前工厂: " + item.getMaterialTypeId()));

        String warehouseId = (item.getWarehouseId() != null && !item.getWarehouseId().isBlank())
                ? item.getWarehouseId()
                : warehouseResolver.resolvePurchaseInboundWh(factoryId);

        // 原料只能入 RAW/物流/legacy 仓 (守卫在 save 前抛 422, 不污染事务)。
        if (warehouseInventoryGuardService != null) {
            warehouseInventoryGuardService.assertCanReceive(warehouseId, factoryId, "RAW");
        }

        MaterialBatch batch = new MaterialBatch();
        batch.setId(UUID.randomUUID().toString());
        batch.setFactoryId(factoryId);
        batch.setMaterialTypeId(item.getMaterialTypeId());
        batch.setWarehouseId(warehouseId);
        // 建账日 = 今天 (避免补录时效锁); 生产日期单独记录。
        batch.setReceiptDate(LocalDate.now());
        batch.setProductionDate(item.getProductionDate());
        batch.setReceiptQuantity(receiptQuantity.setScale(2, RoundingMode.HALF_UP));
        batch.setQuantityUnit(resolveUnit(item, materialType));
        batch.setUnitPrice(item.getUnitPrice());   // 诚实-null: 可为空
        batch.setStatus(MaterialBatchStatus.AVAILABLE);
        batch.setInboundType(InboundType.LEGACY_IMPORT);   // 期初=历史/迁移导入语义
        batch.setSourceDocType(OPENING_SOURCE_DOC_TYPE);
        batch.setSourceDocId(batchKey);
        batch.setCreatedBy(userId);
        batch.setNotes(notes);
        resolveExpireDate(batch, item, materialType);

        // 批次号: 显式传入则用 (去重兜底), 否则系统生成 (防手敲重码)。
        String base = (item.getBatchNumber() != null && !item.getBatchNumber().isBlank())
                ? item.getBatchNumber()
                : generateMaterialBatchNumber(factoryId);
        batch.setBatchNumber(generateUniqueBatchNumber(base));

        batch = materialBatchRepository.save(batch);
        publishBatchCreatedEvent(factoryId, batch);
        return batch;
    }

    // =====================================================================
    // 2) 幽灵应付修正
    // =====================================================================

    @Override
    public OpeningApCorrectionResult correctMisroutedOpeningAp(
            String factoryId, OpeningApCorrectionRequest request, Long userId) {
        // ⚠️ 编排方法故意 NOT @Transactional —— 见 self 字段注释 (doomed-tx 防护)。每笔独立事务。
        if (request == null || request.getApTransactionIds() == null || request.getApTransactionIds().isEmpty()) {
            throw new BusinessException(400, "待修正的应付交易ID不能为空").withHintTarget("apTransactionIds");
        }

        List<OpeningApCorrectionResult.Outcome> outcomes = new ArrayList<>();
        int corrected = 0;
        int skipped = 0;
        BigDecimal totalReversed = BigDecimal.ZERO;

        for (String apId : request.getApTransactionIds()) {
            try {
                // 独立事务 (self-proxy): 红冲 + 补期初凭证 原子于同一笔; 失败只回滚这一笔。
                OpeningApCorrectionResult.Outcome outcome =
                        self.correctSingleOpeningAp(factoryId, apId, request.getReason(), userId);
                outcomes.add(outcome);
                corrected++;
                if (outcome.getAmount() != null) {
                    totalReversed = totalReversed.add(outcome.getAmount());
                }
            } catch (BusinessException e) {   // ResourceNotFoundException extends BusinessException
                skipped++;
                log.warn("幽灵应付修正跳过: apTransactionId={}, reason={}", apId, e.getMessage());
                outcomes.add(OpeningApCorrectionResult.Outcome.builder()
                        .apTransactionId(apId)
                        .status("SKIPPED")
                        .message(e.getMessage())
                        .build());
            }
        }

        return OpeningApCorrectionResult.builder()
                .correctedCount(corrected)
                .skippedCount(skipped)
                .totalReversedAmount(totalReversed)
                .outcomes(outcomes)
                .build();
    }

    /**
     * 单笔幽灵应付修正 —— <b>独立事务</b> (由编排方法经 self-proxy 调用, 见 {@link #self})。
     * 红冲应付 + 补期初凭证 原子于同一笔事务: 要么都成, 要么都回滚 (不会出现"应付红冲了但没补凭证")。
     * public 供 Spring 事务代理生效。
     */
    @Transactional
    public OpeningApCorrectionResult.Outcome correctSingleOpeningAp(
            String factoryId, String apId, String reason, Long userId) {
        // 幂等 + 校验 + 红冲应付台账 (供应商余额减回); 类型不符/不存在会抛 BusinessException。
        ArApTransaction reversal = arApService.reverseOpeningPayable(factoryId, apId, reason, userId);
        // reversal.amount 为负 (红冲额), 取绝对值 = 期初存货应记金额。
        BigDecimal amount = reversal.getAmount() != null ? reversal.getAmount().abs() : BigDecimal.ZERO;

        // 补正确的期初凭证 (借 1403 / 贷 4001) —— 幂等 via findBySourceBusiness。
        Voucher voucher = postApCorrectionVoucher(factoryId, apId, amount, reason, userId);

        // 红冲交易是本次新建还是既有 (幂等命中) —— 用它是否明显早于当下粗判, 仅供展示。
        boolean already = reversal.getCreatedAt() != null
                && reversal.getCreatedAt().isBefore(java.time.LocalDateTime.now().minusSeconds(2));
        return OpeningApCorrectionResult.Outcome.builder()
                .apTransactionId(apId)
                .status(already ? "ALREADY_CORRECTED" : "CORRECTED")
                .amount(amount)
                .reversalTransactionId(reversal.getId())
                .voucherId(voucher != null ? voucher.getId() : null)
                .build();
    }

    /** 补记幽灵应付修正的期初凭证 (借 1403 / 贷 4001), 以被修正应付ID为幂等键。金额非正则不过账。*/
    private Voucher postApCorrectionVoucher(String factoryId, String apId, BigDecimal amount,
                                            String reason, Long userId) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("幽灵应付修正: 金额非正, 不补期初凭证 apTransactionId={} amount={}", apId, amount);
            return null;
        }
        Optional<Voucher> existing = voucherService.findBySourceBusiness(VOUCHER_SOURCE_TYPE_AP_CORRECTION, apId);
        if (existing.isPresent()) {
            return existing.get();
        }
        List<VoucherEntrySpec> entries = new ArrayList<>();
        entries.add(new VoucherEntrySpec(SUBJECT_INVENTORY_CODE, SUBJECT_INVENTORY_NAME,
                amount, null, "期初建账修正(原误挂应付 " + apId + ")"));
        entries.add(new VoucherEntrySpec(SUBJECT_OPENING_EQUITY_CODE, SUBJECT_OPENING_EQUITY_NAME,
                null, amount, "期初建账权益(修正 " + apId + ")"));
        String summary = "期初建账修正 借1403原材料/贷4001实收资本 (红冲误挂应付 " + apId + ")"
                + (reason != null && !reason.isBlank() ? " " + reason : "");
        return voucherService.createManual(factoryId, VoucherType.INVENTORY_STOCKTAKE,
                LocalDate.now(), entries, VOUCHER_SOURCE_TYPE_AP_CORRECTION, apId, summary, userId);
    }

    // =====================================================================
    // helpers
    // =====================================================================

    /** 幂等键: 传入优先, 否则按 (工厂 + 全部行签名) 稳定 hash 派生, 防重复提交双建。*/
    private String resolveBatchKey(String factoryId, OpeningInventoryRequest request) {
        if (request.getBatchKey() != null && !request.getBatchKey().isBlank()) {
            String k = request.getBatchKey().trim();
            return k.length() > 64 ? k.substring(0, 64) : k;
        }
        String sig = request.getItems().stream()
                .map(this::itemSignature)
                .sorted()
                .collect(Collectors.joining("|"));
        return "OPEN-" + sha256Hex(factoryId + "|" + sig).substring(0, 55);
    }

    private String itemSignature(OpeningInventoryItem i) {
        return String.join(":",
                nvl(i.getMaterialTypeId()), nvl(i.getWarehouseId()),
                i.getQuantity() != null ? i.getQuantity().stripTrailingZeros().toPlainString() : "",
                i.getUnitPrice() != null ? i.getUnitPrice().stripTrailingZeros().toPlainString() : "",
                nvl(i.getBatchNumber()),
                i.getProductionDate() != null ? i.getProductionDate().toString() : "",
                i.getExpiryDate() != null ? i.getExpiryDate().toString() : "");
    }

    private static String nvl(String s) { return s != null ? s : ""; }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            // fail-safe: 内容 hashCode (仍确定性); 绝不用随机, 否则破坏幂等。
            return Integer.toHexString(s.hashCode());
        }
    }

    private String resolveUnit(OpeningInventoryItem item, RawMaterialType materialType) {
        if (item.getQuantityUnit() != null && !item.getQuantityUnit().isBlank()) {
            return item.getQuantityUnit();
        }
        return materialType.getUnit() != null ? materialType.getUnit() : "kg";
    }

    private void resolveExpireDate(MaterialBatch batch, OpeningInventoryItem item, RawMaterialType materialType) {
        if (item.getExpiryDate() != null) {
            batch.setExpireDate(item.getExpiryDate());
        } else if (materialType.getShelfLifeDays() != null && item.getProductionDate() != null) {
            batch.setExpireDate(item.getProductionDate().plusDays(materialType.getShelfLifeDays()));
        }
    }

    private void publishBatchCreatedEvent(String factoryId, MaterialBatch batch) {
        if (applicationEventPublisher == null) return;
        try {
            applicationEventPublisher.publishEvent(new com.cretas.aims.event.MaterialBatchCreatedEvent(
                    this, factoryId, batch.getId(), batch.getBatchNumber(),
                    batch.getMaterialTypeId(), batch.getReceiptQuantity(),
                    OPENING_SOURCE_DOC_TYPE, batch.getSourceDocId()));
        } catch (Exception e) {
            log.warn("发布 MaterialBatchCreatedEvent 失败 (期初建账, 非阻塞): batch={}, err={}",
                    batch.getId(), e.getMessage());
        }
    }

    /** 系统生成原料批次号 (复用 EncodingRule MATERIAL_BATCH; fail-open 回退默认格式)。*/
    private String generateMaterialBatchNumber(String factoryId) {
        if (encodingRuleService != null) {
            try {
                String code = encodingRuleService.generateCode(factoryId, "MATERIAL_BATCH");
                if (code != null && !code.isBlank()) {
                    return code;
                }
            } catch (Exception e) {
                log.warn("EncodingRule 生成期初批次号失败, 回退默认格式: {}", e.getMessage());
            }
        }
        String date = LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        return "OPEN-" + factoryId + "-" + date + "-" + String.format("%04d", System.currentTimeMillis() % 10000);
    }

    /** existsBy 兜底防撞唯一批次号。*/
    private String generateUniqueBatchNumber(String baseNumber) {
        String base = (baseNumber != null && !baseNumber.isBlank())
                ? baseNumber : "OPEN-" + System.currentTimeMillis();
        String batchNumber = base;
        int counter = 0;
        while (materialBatchRepository.existsByBatchNumber(batchNumber)) {
            counter++;
            batchNumber = base + "-" + counter;
        }
        return batchNumber;
    }

    private OpeningInventoryResult buildResultFromExisting(List<MaterialBatch> existing, Voucher voucher, String batchKey) {
        int priced = 0;
        int uncosted = 0;
        BigDecimal total = BigDecimal.ZERO;
        List<String> ids = new ArrayList<>();
        List<String> numbers = new ArrayList<>();
        for (MaterialBatch b : existing) {
            ids.add(b.getId());
            numbers.add(b.getBatchNumber());
            if (b.getUnitPrice() != null && b.getUnitPrice().compareTo(BigDecimal.ZERO) > 0
                    && b.getReceiptQuantity() != null) {
                priced++;
                total = total.add(b.getReceiptQuantity().multiply(b.getUnitPrice()).setScale(2, RoundingMode.HALF_UP));
            } else {
                uncosted++;
            }
        }
        return OpeningInventoryResult.builder()
                .batchKey(batchKey)
                .idempotentHit(true)
                .createdCount(existing.size())
                .pricedCount(priced)
                .uncostedCount(uncosted)
                .totalOpeningValue(total)
                .voucherId(voucher != null ? voucher.getId() : null)
                .voucherNumber(voucher != null ? voucher.getVoucherNumber() : null)
                .batchIds(ids)
                .batchNumbers(numbers)
                .build();
    }
}
