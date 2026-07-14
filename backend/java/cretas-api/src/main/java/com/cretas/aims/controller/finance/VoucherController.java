package com.cretas.aims.controller.finance;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.finance.AuxiliaryAggregateRow;
import com.cretas.aims.dto.finance.VoucherBatchGenerateRequest;
import com.cretas.aims.dto.finance.VoucherBatchPostRequest;
import com.cretas.aims.dto.finance.VoucherBatchPostResultDTO;
import com.cretas.aims.dto.finance.VoucherGenerateRequest;
import com.cretas.aims.dto.finance.VoucherVoidRequest;
import com.cretas.aims.entity.enums.AuxiliaryType;
import com.cretas.aims.entity.enums.VoucherStatus;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.repository.VoucherEntryRepository;
import com.cretas.aims.repository.VoucherRepository;
import com.cretas.aims.service.voucher.VoucherService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Voucher REST API. Sprint3-E F-VFLAG-1.
 *
 * Endpoints:
 *   GET  /                       - page query (filter: status, type, sourceBusinessType)
 *   GET  /{id}                   - detail
 *   GET  /by-business/{type}/{id} - lookup by source business (idempotent check from UI)
 *   POST /generate                - single generate (idempotent)
 *   POST /batch-generate          - batch UNCREATED → CREATED
 *   POST /{id}/post               - DRAFT → POSTED
 *   POST /batch-post              - batch DRAFT → POSTED (per-id result, follow-up to #1228)
 *   POST /{id}/void               - any → VOID
 *
 * Class-level RBAC: finance:read (view). Per-method RBAC raises bar for writes.
 */
@RestController
@RequestMapping("/api/mobile/{factoryId}/finance/vouchers")
@RequiredArgsConstructor
@RequirePermission({"finance:read", "finance:read_write"})
public class VoucherController {

    private final VoucherService voucherService;
    private final VoucherRepository voucherRepo;
    private final VoucherEntryRepository voucherEntryRepo;

    // ==================== Read ====================

    @GetMapping
    public ResponseEntity<?> list(
            @PathVariable String factoryId,
            @RequestParam(value = "status", required = false) VoucherStatus status,
            @RequestParam(value = "type", required = false) VoucherType type,
            @RequestParam(value = "sourceBusinessType", required = false) String sourceBusinessType,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        // Issues #815/#816 fix (2026-05-17): unify on UI 1-idx convention to match
        // PurchaseController / UserController / ProductTypeController. Previously this
        // endpoint used Spring native 0-idx → ?page=1 returned silent empty content:[]
        // while totalElements > 0 (customer-visible bug F006 finance vouchers).
        // Reject page<1 / size<1 with 400 (consistent with PageRequest DTO @Min(1)).
        if (page < 1) {
            return ResponseEntity.badRequest().body(
                    Map.of("success", false, "message", "页码必须大于0", "code", "INVALID_PAGE"));
        }
        if (size < 1) {
            return ResponseEntity.badRequest().body(
                    Map.of("success", false, "message", "每页大小必须大于0", "code", "INVALID_SIZE"));
        }
        // Filter-bar 三维筛选 (status / type / sourceBusinessType) 通过一条 native query 处理任意子集组合
        // (VoucherRepository#searchVouchers, CAST(:param AS text) IS NULL 模式, 与 SystemLogRepository.searchLogs
        // 同一套 unbounded 财务台账筛选写法). ORDER BY 已内联在 native SQL 里, 这里传不带 Sort 的 Pageable。
        String statusStr = status != null ? status.name() : null;
        String typeStr = type != null ? type.name() : null;
        String sourceBusinessTypeParam =
                (sourceBusinessType != null && !sourceBusinessType.isBlank()) ? sourceBusinessType.trim() : null;
        PageRequest pageable = PageRequest.of(page - 1, size);
        Page<Voucher> result = voucherRepo.searchVouchers(
                factoryId, statusStr, typeStr, sourceBusinessTypeParam, pageable);
        return ResponseEntity.ok(Map.of("success", true, "data", result));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> detail(@PathVariable String factoryId, @PathVariable String id) {
        Optional<Voucher> v = voucherRepo.findByIdAndFactoryIdAndDeletedAtIsNull(id, factoryId);
        if (v.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("success", false, "message", "凭证不存在"));
        }
        return ResponseEntity.ok(Map.of("success", true, "data", v.get()));
    }

    @GetMapping("/by-business/{businessType}/{businessId}")
    public ResponseEntity<?> findByBusiness(
            @PathVariable String factoryId,
            @PathVariable String businessType,
            @PathVariable String businessId) {
        Optional<Voucher> v = voucherService.findBySourceBusiness(businessType, businessId);
        // 跨租户校验 (Rule 8 sweep): findBySourceBusiness 按 businessType+businessId 全局查询不带 factory,
        // 返回的凭证可能属于别厂。仅当凭证归属当前工厂时才返回, 否则视作"未生成"(不泄漏别厂凭证存在性)。
        if (v.isEmpty() || !factoryId.equals(v.get().getFactoryId())) {
            // Map.of(...) throws NPE on a null value ("data", null) — this "not yet
            // generated" branch is a normal, frequent response (idempotent check from
            // UI), so it must not 500. Use a null-tolerant HashMap instead.
            Map<String, Object> notFound = new HashMap<>();
            notFound.put("success", true);
            notFound.put("data", null);
            notFound.put("message", "未生成凭证");
            return ResponseEntity.ok(notFound);
        }
        return ResponseEntity.ok(Map.of("success", true, "data", v.get()));
    }

    // ==================== Write ====================

    // Issue #712 fix (2026-05-17): replaced raw Map<String,Object> bodies with typed DTOs + @Valid.

    @PostMapping("/generate")
    @RequirePermission("finance:read_write")
    public ResponseEntity<?> generate(
            @PathVariable String factoryId,
            @Valid @RequestBody VoucherGenerateRequest req) {
        Voucher v = voucherService.createFromBusiness(factoryId, req.getBusinessType(), req.getBusinessId());
        return ResponseEntity.ok(Map.of("success", true, "data", v, "message", "凭证已生成"));
    }

    @PostMapping("/batch-generate")
    @RequirePermission("finance:read_write")
    public ResponseEntity<?> batchGenerate(
            @PathVariable String factoryId,
            @Valid @RequestBody VoucherBatchGenerateRequest req) {
        int count = voucherService.batchCreateForFactory(factoryId, req.getBusinessType());
        Map<String, Object> data = new HashMap<>();
        data.put("businessType", req.getBusinessType());
        data.put("count", count);
        return ResponseEntity.ok(Map.of("success", true, "data", data, "message", "批量生成完成"));
    }

    @PostMapping("/{id}/post")
    @RequirePermission("finance:read_write")
    public ResponseEntity<?> post(
            @PathVariable String factoryId,
            @PathVariable String id,
            @RequestAttribute(value = "userId", required = false) Long userId) {
        Voucher v = voucherService.post(factoryId, id, userId);
        return ResponseEntity.ok(Map.of("success", true, "data", v, "message", "凭证已过账"));
    }

    /**
     * 批量过账 (follow-up to #1228 finding): 人工审核制下逐张过账对财务人员是负担, 支持一次性
     * 提交一批 voucherId → 逐张校验+过账 (与单张 {@link #post} 同一套规则), 每张独立结果返回
     * (成功/幂等跳过/失败+原因), 一张失败不影响其余凭证。
     */
    @PostMapping("/batch-post")
    @RequirePermission("finance:read_write")
    public ResponseEntity<?> batchPost(
            @PathVariable String factoryId,
            @Valid @RequestBody VoucherBatchPostRequest req,
            @RequestAttribute(value = "userId", required = false) Long userId) {
        List<VoucherBatchPostResultDTO> results = voucherService.batchPost(factoryId, req.getVoucherIds(), userId);
        long successCount = results.stream().filter(VoucherBatchPostResultDTO::isSuccess).count();
        long failureCount = results.size() - successCount;
        Map<String, Object> data = new HashMap<>();
        data.put("results", results);
        data.put("total", results.size());
        data.put("successCount", successCount);
        data.put("failureCount", failureCount);
        return ResponseEntity.ok(Map.of(
                "success", true, "data", data,
                "message", String.format("批量过账完成: %d 成功, %d 失败", successCount, failureCount)));
    }

    @PostMapping("/{id}/void")
    @RequirePermission("finance:read_write")
    public ResponseEntity<?> voidVoucher(
            @PathVariable String factoryId,
            @PathVariable String id,
            @Valid @RequestBody VoucherVoidRequest req,
            @RequestAttribute(value = "userId", required = false) Long userId) {
        String reason = (req.getReason() == null || req.getReason().isBlank()) ? "未填写原因" : req.getReason();
        voucherService.voidVoucher(factoryId, id, reason, userId);
        return ResponseEntity.ok(Map.of("success", true, "message", "凭证已作废"));
    }

    // ==================== Sprint 6 W4-A: 辅助核算多维度明细账 ====================

    /**
     * 按辅助核算实体聚合凭证分录, 返每个 entity 在期间内的借/贷/净余额/分录数.
     *
     * <p>支持 R-HJ Round 11 §G.1 多维度明细账:
     * <ul>
     *   <li>{@code type=CUSTOMER} → 客户应收明细账 (debit 正余额 = 客户欠款)</li>
     *   <li>{@code type=SUPPLIER} → 供应商应付明细账 (credit 正余额 = 我们欠供应商)</li>
     *   <li>{@code type=DEPT}     → 部门费用账</li>
     *   <li>{@code type=PROJECT}  → 项目核算账</li>
     *   <li>{@code type=INVENTORY}→ 库存商品按 SKU/批次明细账</li>
     *   <li>{@code type=EMPLOYEE} → 职员工资明细账</li>
     *   <li>{@code type=OUTSOURCER} → 委外商往来账 (enum 已就绪, generator 待 Sprint 7+)</li>
     * </ul>
     *
     * <p>查询计算 SQL 层: SUM(debit), SUM(credit), debit-credit, COUNT(*) 按
     * (auxiliaryType, auxiliaryEntityId) 分组, 排序 entryCount DESC.
     *
     * <p>排除 VOID 状态凭证. DRAFT / POSTED 都纳入余额 (与 R-HJ ERP 默认行为一致;
     * 财务希望看到草稿对余额的"潜在影响"). 后续如需仅 POSTED, 加 query param status=POSTED.
     *
     * @param factoryId 工厂 ID (path)
     * @param type      辅助核算类型 (required)
     * @param from      期间起始 (yyyy-MM-dd, inclusive)
     * @param to        期间结束 (yyyy-MM-dd, inclusive)
     * @return 聚合行列表 (按 entryCount DESC)
     */
    @GetMapping("/aggregate-by-auxiliary")
    public ResponseEntity<?> aggregateByAuxiliary(
            @PathVariable String factoryId,
            @RequestParam("type") AuxiliaryType type,
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (from.isAfter(to)) {
            return ResponseEntity.badRequest().body(
                    Map.of("success", false, "message", "from 不能晚于 to", "code", "INVALID_DATE_RANGE"));
        }
        List<AuxiliaryAggregateRow> rows = voucherEntryRepo.aggregateByAuxiliary(factoryId, type, from, to);
        Map<String, Object> data = new HashMap<>();
        data.put("auxiliaryType", type);
        data.put("dateRange", Map.of("from", from, "to", to));
        data.put("rows", rows);
        data.put("totalEntities", rows.size());
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }
}
