package com.cretas.aims.controller.warehouse;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.entity.warehouse.ReusableContainer;
import com.cretas.aims.entity.warehouse.ReusableContainerTransaction;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.warehouse.ReusableContainerService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 周转耗材 (周转框) 进销存 — C4
 * 客户原话 (Apr 7 会议 3428-3475s): 周转框丢了客户要赔钱, 需要管理在库/在客户处
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/reusable-containers")
@RequiredArgsConstructor
@Tag(name = "周转耗材管理", description = "周转框/塑料筐进销存 — 在库/在客户处/丢失赔偿")
public class ReusableContainerController {

    private final ReusableContainerService service;

    /**
     * AUD-5 B-A3 sister sweep batch 3 (edge audit 2026-05-20): explicit length caps mirror PG
     * column widths in {@code reusable_containers} and {@code reusable_container_transactions}
     * tables (see {@link ReusableContainer} / {@link ReusableContainerTransaction}
     * {@code @Column(length=...)}). Without these, over-length input lets the request reach PG
     * and surfaces as {@code DataIntegrityViolationException} → generic 409 "数据处理异常".
     * Pre-check at controller boundary delivers a specific 400 with a hintTarget instead.
     *
     * <p>Mirrors PR #48 / PR #76 / PR #78 length-pre-check pattern.
     */
    private static final int CONTAINER_CODE_MAX_LENGTH = 64;
    private static final int CONTAINER_NAME_MAX_LENGTH = 128;
    private static final int SPECIFICATION_MAX_LENGTH = 128;
    private static final int CONTAINER_REMARK_MAX_LENGTH = 500;
    private static final int TRANSACTION_CUSTOMER_NAME_MAX_LENGTH = 128;
    private static final int TRANSACTION_REMARK_MAX_LENGTH = 500;

    @GetMapping
    public ApiResponse<Page<ReusableContainer>> list(
            @PathVariable @NotBlank String factoryId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(service.listContainers(factoryId, PageRequest.of(page, size)));
    }

    @GetMapping("/{id}")
    public ApiResponse<ReusableContainer> get(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String id) {
        return ApiResponse.success(service.getContainer(factoryId, id));
    }

    @RequirePermission({"warehouse:read_write"})
    @PostMapping
    public ApiResponse<ReusableContainer> create(
            @PathVariable @NotBlank String factoryId,
            @RequestBody ReusableContainer dto) {
        // AUD-5 B-A3 sister sweep batch 3: length pre-check for container code/name/spec/remark
        // BEFORE dispatching to service layer where PG would surface a generic 409.
        validateContainerLengths(dto);
        return ApiResponse.success("创建成功", service.createContainer(factoryId, dto));
    }

    @RequirePermission({"warehouse:read_write"})
    @PostMapping("/{id}/ship-out")
    public ApiResponse<ReusableContainerTransaction> shipOut(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String id,
            @RequestBody Map<String, Object> body) {
        // AUD-5 B-A3 sister sweep batch 3: length pre-check for transaction text fields.
        validateTransactionLengths(body);
        Integer quantity = parseInt(body.get("quantity"));
        return ApiResponse.success("已发出", service.shipOut(factoryId, id, quantity,
                (String) body.get("customerId"),
                (String) body.get("customerName"),
                (String) body.get("salesDeliveryId"),
                (String) body.get("remark")));
    }

    @RequirePermission({"warehouse:read_write"})
    @PostMapping("/{id}/return-in")
    public ApiResponse<ReusableContainerTransaction> returnIn(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String id,
            @RequestBody Map<String, Object> body) {
        // AUD-5 B-A3 sister sweep batch 3 (Rule 16: entry-point matrix — return-in path).
        validateTransactionLengths(body);
        Integer quantity = parseInt(body.get("quantity"));
        return ApiResponse.success("已归还", service.returnIn(factoryId, id, quantity,
                (String) body.get("customerId"),
                (String) body.get("customerName"),
                (String) body.get("remark")));
    }

    @RequirePermission({"warehouse:read_write"})
    @PostMapping("/{id}/loss")
    public ApiResponse<ReusableContainerTransaction> loss(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String id,
            @RequestBody Map<String, Object> body) {
        // AUD-5 B-A3 sister sweep batch 3 (Rule 16: entry-point matrix — loss path).
        validateTransactionLengths(body);
        Integer quantity = parseInt(body.get("quantity"));
        BigDecimal comp = body.get("compensationAmount") == null ? null
                : new BigDecimal(body.get("compensationAmount").toString());
        return ApiResponse.success("已登记丢失", service.markLoss(factoryId, id, quantity,
                (String) body.get("customerId"),
                (String) body.get("customerName"),
                comp,
                (String) body.get("remark")));
    }

    @GetMapping("/{id}/transactions")
    public ApiResponse<List<ReusableContainerTransaction>> transactions(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String id) {
        return ApiResponse.success(service.listTransactions(factoryId, id));
    }

    @GetMapping("/customer-balances")
    public ApiResponse<List<Map<String, Object>>> customerBalances(
            @PathVariable @NotBlank String factoryId) {
        return ApiResponse.success(service.customerBalances(factoryId));
    }

    private Integer parseInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).intValue();
        return Integer.parseInt(o.toString());
    }

    // ==================== Boundary validators (AUD-5 B-A3 sister sweep batch 3) ====================

    /**
     * AUD-5 B-A3 sister sweep batch 3 (edge audit 2026-05-20): length pre-check for
     * {@link ReusableContainer} create path. Container code (64), name (128),
     * specification (128), remark (500) are all PG VARCHAR-bounded user input fields.
     * Mirrors PR #48 / PR #78 pattern.
     */
    private void validateContainerLengths(ReusableContainer dto) {
        String code = dto.getContainerCode();
        if (code != null && code.length() > CONTAINER_CODE_MAX_LENGTH) {
            throw new BusinessException(400,
                    "周转耗材编号最长 " + CONTAINER_CODE_MAX_LENGTH + " 字符 (当前 " + code.length() + ")")
                    .withHint("请使用更短的周转耗材编号")
                    .withSeverity("warning")
                    .withHintTarget("containerCode");
        }
        String name = dto.getContainerName();
        if (name != null && name.length() > CONTAINER_NAME_MAX_LENGTH) {
            throw new BusinessException(400,
                    "周转耗材名称最长 " + CONTAINER_NAME_MAX_LENGTH + " 字符 (当前 " + name.length() + ")")
                    .withHint("请使用更短的周转耗材名称")
                    .withSeverity("warning")
                    .withHintTarget("containerName");
        }
        String spec = dto.getSpecification();
        if (spec != null && spec.length() > SPECIFICATION_MAX_LENGTH) {
            throw new BusinessException(400,
                    "规格最长 " + SPECIFICATION_MAX_LENGTH + " 字符 (当前 " + spec.length() + ")")
                    .withHint("请使用更短的规格说明")
                    .withSeverity("warning")
                    .withHintTarget("specification");
        }
        String remark = dto.getRemark();
        if (remark != null && remark.length() > CONTAINER_REMARK_MAX_LENGTH) {
            throw new BusinessException(400,
                    "备注最长 " + CONTAINER_REMARK_MAX_LENGTH + " 字符 (当前 " + remark.length() + ")")
                    .withHint("请使用更短的备注 (上限 500 字符)")
                    .withSeverity("warning")
                    .withHintTarget("remark");
        }
    }

    /**
     * AUD-5 B-A3 sister sweep batch 3: length pre-check for {@link ReusableContainerTransaction}
     * fields {@code customerName} (VARCHAR 128) and {@code remark} (VARCHAR 500) on
     * ship-out / return-in / loss paths (Rule 16: entry-point matrix). Mirrors PR #78 pattern.
     */
    private void validateTransactionLengths(Map<String, Object> body) {
        Object name = body.get("customerName");
        if (name instanceof String nameStr && nameStr.length() > TRANSACTION_CUSTOMER_NAME_MAX_LENGTH) {
            throw new BusinessException(400,
                    "客户名称最长 " + TRANSACTION_CUSTOMER_NAME_MAX_LENGTH + " 字符 (当前 " + nameStr.length() + ")")
                    .withHint("请使用更短的客户名称")
                    .withSeverity("warning")
                    .withHintTarget("customerName");
        }
        Object remark = body.get("remark");
        if (remark instanceof String remarkStr && remarkStr.length() > TRANSACTION_REMARK_MAX_LENGTH) {
            throw new BusinessException(400,
                    "备注最长 " + TRANSACTION_REMARK_MAX_LENGTH + " 字符 (当前 " + remarkStr.length() + ")")
                    .withHint("请使用更短的备注 (上限 500 字符)")
                    .withSeverity("warning")
                    .withHintTarget("remark");
        }
    }
}
