package com.cretas.aims.controller.inventory;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.inventory.CreatePurchaseOrderRequest;
import com.cretas.aims.dto.inventory.CreateReceiveRecordRequest;
import com.cretas.aims.dto.inventory.UpdatePurchaseOrderRequest;
import com.cretas.aims.dto.inventory.MaterialPriceComparisonDTO;
import com.cretas.aims.dto.inventory.PurchaseSuggestionResponse;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.entity.inventory.PurchaseReceiveRecord;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.security.PriceFieldResponseAdvice;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.inventory.PurchaseOrderPdfService;
import com.cretas.aims.service.inventory.PurchaseService;
import com.cretas.aims.service.workflow.WorkflowEngineService;
import com.cretas.aims.service.workflow.impl.WorkflowEngineServiceImpl;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance.InstanceStatus;
import com.cretas.aims.utils.TokenUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.cretas.aims.annotation.RequirePermission;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.cretas.aims.annotation.RequireModule;

@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/purchase")
@RequiredArgsConstructor
@org.springframework.validation.annotation.Validated  // Issue #816: enable @Min(1) on @RequestParam page/size
@Tag(name = "采购管理", description = "采购订单与入库管理（工厂/餐饮通用）")
public class PurchaseController {

    private final PurchaseService purchaseService;
    private final PurchaseOrderPdfService purchaseOrderPdfService;
    private final MobileService mobileService;
    private final PermissionService permissionService;
    private final UserRepository userRepository;
    private final com.cretas.aims.service.factory.WarehouseResolver warehouseResolver;
    private final com.cretas.aims.repository.factory.FactoryWarehouseRepository factoryWarehouseRepository;

    /**
     * Phase 2 issue #13 — workflow-scoped RBAC for {@link #approveOrder}.
     *
     * <p>当 PO 有 RUNNING workflow instance 时, approve 改用 {@code canTransition}
     * 检查当前 active node 的 {@code approverRoles}, 而非静态 {@code procurement:read_write}.
     * 这允许 {@code finance_manager} 角色 (无 procurement 写权限) 推进 workflow 的财务审批节点.
     *
     * <p>注入 impl 类而非 interface 是因为 {@code canTransition} 不在 {@link WorkflowEngineService}
     * interface 上 (Phase 1 stable API 约束). {@code required=false} 兼容 workflow bean 缺失场景.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WorkflowEngineService workflowEngine;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WorkflowEngineServiceImpl workflowEngineImpl;

    // ==================== 采购订单 ====================

    @RequireModule("purchase_order")
    @PostMapping("/orders")
    @Operation(summary = "创建采购订单")
    @RequirePermission("procurement:read_write")
    public ApiResponse<PurchaseOrder> createOrder(
            @PathVariable @NotBlank String factoryId,
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody CreatePurchaseOrderRequest request) {
        Long userId = extractUserId(authorization);
        log.info("创建采购订单: factoryId={}, supplierId={}", factoryId, request.getSupplierId());
        PurchaseOrder order = purchaseService.createPurchaseOrder(factoryId, request, userId);
        return ApiResponse.success("采购订单创建成功", order);
    }

    @GetMapping("/orders")
    @Operation(summary = "采购订单列表", description = "支持可选 salesOrderId 过滤 (W-12 fix: SO 详情页'关联采购' tab 依赖)")
    @RequirePermission({"procurement:read_write", "procurement:read"})
    public ApiResponse<PageResponse<PurchaseOrder>> listOrders(
            @PathVariable @NotBlank String factoryId,
            @RequestParam(required = false) String salesOrderId,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码必须大于0") int page,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "每页大小必须大于0") int size) {
        PageResponse<PurchaseOrder> result = (salesOrderId != null && !salesOrderId.isBlank())
                ? purchaseService.getPurchaseOrdersBySalesOrder(factoryId, salesOrderId, page, size)
                : purchaseService.getPurchaseOrders(factoryId, page, size);
        return ApiResponse.success("查询成功", result);
    }

    @GetMapping("/orders/by-status")
    @Operation(summary = "按状态查询采购订单")
    @RequirePermission({"procurement:read_write", "procurement:read"})
    public ApiResponse<PageResponse<PurchaseOrder>> listOrdersByStatus(
            @PathVariable @NotBlank String factoryId,
            @RequestParam PurchaseOrderStatus status,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码必须大于0") int page,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "每页大小必须大于0") int size,
            @RequestHeader("Authorization") String authorization) {
        // Issue #736 fix (2026-05-17): viewer 角色虽有 procurement:read 但不应看到 PENDING_FINANCE_REVIEW
        // 状态采购单 (含供应商名、总金额、价格异常告警 — 业务敏感). 走独立 FINANCE_REVIEW_VIEW_PERMISSION
        // 角色白名单 (procurement_manager / finance_manager / dispatcher / super_admin).
        if (status == PurchaseOrderStatus.PENDING_FINANCE_REVIEW) {
            User currentUser = resolveCurrentUser(authorization);
            boolean canViewFinanceReview = currentUser != null
                    && permissionService.hasPermission(currentUser,
                            com.cretas.aims.service.impl.PermissionServiceImpl.FINANCE_REVIEW_VIEW_PERMISSION);
            if (!canViewFinanceReview) {
                log.warn("RBAC reject (#736): user={} role={} 无 procurement:finance_review:view, "
                        + "status=PENDING_FINANCE_REVIEW 拒绝访问",
                        currentUser != null ? currentUser.getUsername() : "<null>",
                        currentUser != null ? currentUser.getRole() : "<null>");
                throw new BusinessException(403, "无权查看待财审采购单");
            }
        }
        PageResponse<PurchaseOrder> result = purchaseService.getPurchaseOrdersByStatus(factoryId, status, page, size);
        return ApiResponse.success("查询成功", result);
    }

    @GetMapping("/orders/{orderId}")
    @Operation(summary = "采购订单详情")
    @RequirePermission({"procurement:read_write", "procurement:read"})
    public ApiResponse<PurchaseOrder> getOrder(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String orderId) {
        PurchaseOrder order = purchaseService.getPurchaseOrderById(factoryId, orderId);
        return ApiResponse.success("查询成功", order);
    }

    @GetMapping("/orders/by-number/{orderNumber}")
    @Operation(summary = "按订单号查采购单详情 (PDF QR 扫码场景)",
            description = "PDF 二维码内容 = orderNumber (纯文本, 如 PO-20260514-001). " +
                    "仓管员扫 QR → 调本接口拿订单 + 关联明细 → 跳入库收货页. " +
                    "六扇门 May 7 transcript 客户原话: \"扫一下上面的拳运码... 开始入库\".")
    // 仓管员(warehouse:read_write)扫 PO 二维码进入库流程是设计内的合法操作, 但仓管员通常
    // 没有 procurement 权限 → 之前扫码报 403 (六扇门 F006 真客户 2026-06-16)。加入 warehouse
    // 权限: 收货环节读取 PO + 明细是收货的必要前置, 不构成越权(只读单据, 不能改采购单)。
    @RequirePermission({"procurement:read_write", "procurement:read", "warehouse:read_write", "warehouse:read"})
    public ApiResponse<PurchaseOrder> getOrderByNumber(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String orderNumber) {
        PurchaseOrder order = purchaseService.getPurchaseOrderByNumber(factoryId, orderNumber);
        return ApiResponse.success("查询成功", order);
    }

    /**
     * 采购订单 PDF (供货单) 下载.
     *
     * <p>六扇门 May 7 2026 transcript 客户需求:
     * <ul>
     *   <li>"采购订单要有打印功能" — 供应商打印后送货员带过来。</li>
     *   <li>"扫一下上面的拳运码" — PDF 含 Code128 一维条码 + QR 二维码 (内容 = orderNumber),
     *       仓管员扫码进入入库流程。</li>
     *   <li>"双方签字拍张照" — PDF 末尾留签收区。</li>
     * </ul>
     *
     * <p>响应是 PDF 二进制流, 浏览器作为附件下载 (Content-Disposition: attachment)。
     */
    @GetMapping("/orders/{orderId}/pdf")
    @Operation(summary = "下载采购订单 PDF (供货单)",
            description = "生成包含 Code128 条码 + QR 二维码的 PDF 供货单, 供应商打印 / 仓管员扫码入库 (六扇门 May 7 transcript). "
                    + "external=true 生成供应商对外版本, 完全移除价格列; internal 默认版本继续按 procurement:price:view 做价格脱敏.")
    @RequirePermission({"procurement:read_write", "procurement:read"})
    public ResponseEntity<byte[]> downloadOrderPdf(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String orderId,
            @RequestParam(defaultValue = "false") boolean external,
            @RequestHeader("Authorization") String authorization) {
        PurchaseOrder order = purchaseService.getPurchaseOrderById(factoryId, orderId);

        // RBAC defense-in-depth (P0-C fix, 2026-05-12): PriceFieldResponseAdvice (PR #423) 只处理 JSON
        // body, 不 walk byte[] PDF — chat4 R1-C deep test 抓到 warehouse curl /pdf 看到完整 单价/小计/合计.
        // 这里 mirror PermissionService.PRICE_VIEW_ROLES (镜像 @PriceSensitive JSON strip 决策),
        // 把 user 看不见的字段在 PDF 渲染入口替换为 "—".
        User currentUser = resolveCurrentUser(authorization);
        boolean maskPriceByPermission = currentUser == null
                || !permissionService.hasPermission(currentUser, PriceFieldResponseAdvice.PRICE_VIEW_PERMISSION);
        boolean maskPrice = external || maskPriceByPermission;

        byte[] pdfBytes = purchaseOrderPdfService.generatePurchaseOrderPdf(factoryId, orderId, maskPrice, external);

        // 文件名 = 供货单_{订单号}.pdf, 含中文需 RFC 5987 编码
        String filename = (external ? "供货单_对外_" : "采购订单_内部_")
                + (order.getOrderNumber() != null ? order.getOrderNumber() : orderId) + ".pdf";
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.add(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"order.pdf\"; filename*=UTF-8''" + encoded);
        headers.setContentLength(pdfBytes.length);
        log.info("下载采购订单 PDF: factoryId={}, orderId={}, bytes={}, external={}, maskPrice={}, userId={}",
                factoryId, orderId, pdfBytes.length, external, maskPrice,
                currentUser != null ? currentUser.getId() : null);
        return ResponseEntity.ok().headers(headers).body(pdfBytes);
    }

    @RequireModule("purchase_order")
    @PutMapping("/orders/{orderId}")
    @Operation(summary = "编辑草稿采购订单 (partial update: 所有字段可选)")
    @RequirePermission("procurement:read_write")
    public ApiResponse<PurchaseOrder> updateDraftOrder(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String orderId,
            @Valid @RequestBody UpdatePurchaseOrderRequest request) {
        log.info("编辑草稿采购订单: factoryId={}, orderId={}", factoryId, orderId);
        PurchaseOrder order = purchaseService.updateDraftOrder(factoryId, orderId, request);
        return ApiResponse.success("采购订单更新成功", order);
    }

    @RequireModule("purchase_order")
    @PostMapping("/orders/{orderId}/submit")
    @Operation(summary = "提交采购订单")
    @RequirePermission("procurement:read_write")
    public ApiResponse<PurchaseOrder> submitOrder(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String orderId) {
        PurchaseOrder order = purchaseService.submitOrder(factoryId, orderId);
        return ApiResponse.success("采购订单已提交", order);
    }

    @RequireModule("purchase_order")
    @PostMapping("/orders/{orderId}/approve")
    @Operation(summary = "审批采购订单",
            description = "RBAC (issue #13): 有 RUNNING workflow instance 时按 active node "
                    + "approverRoles 授权 (workflow-scoped); 否则回退 procurement:read_write 静态权限. "
                    + "factory_super_admin 总是放行.")
    // @RequirePermission removed (issue #13): RBAC moved into method body for workflow-aware check.
    // Legacy branch (no workflow instance) still enforces procurement:read_write via permissionService.hasPermission.
    public ApiResponse<PurchaseOrder> approveOrder(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String orderId,
            @RequestHeader("Authorization") String authorization) {
        // TODO (Phase 2 / Sprint 4): 增加 @RequestParam(required=false) String fromNodeId
        // 让 parallel 场景下前端能精确指定本次审批操作的 active node id.
        // 目前 WorkflowEngineService.transitionNode 从 instance.currentNodeIds[0] 取 fromNode (B.4 简化).
        User currentUser = resolveCurrentUser(authorization);
        if (currentUser == null) {
            throw new BusinessException(401, "用户未登录或会话已过期");
        }

        // Phase 2 issue #13 — workflow-scoped RBAC:
        // 1. 有 RUNNING workflow instance → 检查当前 active node 的 approverRoles (canTransition).
        // 2. 无 instance → 静态 procurement:read_write (legacy path, 跟其他 PO endpoint 一致).
        // 3. factory_super_admin 总是放行 (canTransition 内已 cover; legacy 路径走 hasPermission).
        Optional<ApprovalWorkflowInstance> instanceOpt = (workflowEngine != null)
                ? workflowEngine.getCurrentInstance(factoryId, "PURCHASE_ORDER", orderId)
                : Optional.empty();

        if (instanceOpt.isPresent() && instanceOpt.get().getStatus() == InstanceStatus.RUNNING) {
            // Workflow path — 按 active node approverRoles 授权.
            boolean allowed = workflowEngineImpl != null
                    && workflowEngineImpl.canTransition(instanceOpt.get(), currentUser);
            if (!allowed) {
                String roleLabel = currentUser.getRoleEnum() == null
                        ? "未知角色" : currentUser.getRoleEnum().getDisplayName();
                throw new BusinessException(403,
                        String.format("您的角色 [%s] 不在当前审批节点的授权列表中", roleLabel))
                        .withHint("请联系工厂管理员在 Canvas → 审批工作流 中将本角色加入审批节点 approverRoles, "
                                + "或切换到有权限的账号 (e.g. factory_super_admin) 重试");
            }
        } else {
            // Legacy path — 静态权限 (跟原 @RequirePermission("procurement:read_write") 行为一致).
            boolean allowed = permissionService.hasPermission(currentUser, "procurement:read_write");
            if (!allowed) {
                String roleLabel = currentUser.getRoleEnum() == null
                        ? "未知角色" : currentUser.getRoleEnum().getDisplayName();
                throw new BusinessException(403,
                        String.format("您的角色 [%s] 在 [采购管理] 模块无 [读写] 权限", roleLabel))
                        .withHint("请联系工厂管理员在 Canvas → 模块权限 矩阵为角色 ["
                                + roleLabel + "] 开通 [采购管理] 的 [读写] 权限, 或切换到有权限的账号重试");
            }
        }

        PurchaseOrder order = purchaseService.approveOrder(factoryId, orderId, currentUser.getId());
        return ApiResponse.success("采购订单已审批", order);
    }

    @RequireModule("purchase_order")
    @PostMapping("/orders/{orderId}/cancel")
    @Operation(summary = "取消采购订单")
    @RequirePermission("procurement:read_write")
    public ApiResponse<PurchaseOrder> cancelOrder(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String orderId) {
        PurchaseOrder order = purchaseService.cancelOrder(factoryId, orderId);
        return ApiResponse.success("采购订单已取消", order);
    }

    /**
     * 复制采购订单 — #860 follow-up.
     * 基于现有订单创建新草稿, 复制供应商/品项/价格, 不复制审批/到货状态.
     */
    @RequireModule("purchase_order")
    @PostMapping("/orders/{orderId}/copy")
    @Operation(summary = "复制采购订单 (创建新草稿)")
    @RequirePermission("procurement:read_write")
    public ApiResponse<PurchaseOrder> copyOrder(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String orderId,
            @RequestHeader("Authorization") String authorization) {
        Long userId = extractUserId(authorization);
        log.info("复制采购订单: factoryId={}, sourceOrderId={}", factoryId, orderId);
        PurchaseOrder order = purchaseService.copyPurchaseOrder(factoryId, orderId, userId);
        return ApiResponse.success("采购订单已复制为新草稿", order);
    }

    // ==================== 财务审核 ====================

    @RequireModule("purchase_order")
    @PostMapping("/orders/{orderId}/submit-for-finance-review")
    @Operation(summary = "提交采购订单财务审核")
    @RequirePermission("procurement:read_write")
    public ApiResponse<PurchaseOrder> submitForFinanceReview(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String orderId) {
        PurchaseOrder order = purchaseService.submitForFinanceReview(factoryId, orderId);
        return ApiResponse.success("已提交财务审核", order);
    }

    @RequireModule("purchase_order")
    @PostMapping("/orders/{orderId}/finance-approve")
    @Operation(summary = "采购订单财务审核通过")
    @RequirePermission("finance:read_write")
    public ApiResponse<PurchaseOrder> financeApprove(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String orderId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) java.util.Map<String, String> body) {
        Long userId = extractUserId(authorization);
        String notes = body != null ? body.get("notes") : null;
        PurchaseOrder order = purchaseService.financeApproveOrder(factoryId, orderId, userId, notes);
        return ApiResponse.success("财务审核通过", order);
    }

    @RequireModule("purchase_order")
    @PostMapping("/orders/{orderId}/finance-reject")
    @Operation(summary = "采购订单财务审核驳回")
    @RequirePermission("finance:read_write")
    public ApiResponse<PurchaseOrder> financeReject(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String orderId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody java.util.Map<String, String> body) {
        // Smoke v3 P2 fix (2026-05-17): 驳回必须给原因, 空 notes 拒绝
        String notes = body.get("notes");
        if (notes == null || notes.trim().isEmpty()) {
            throw new BusinessException(400, "驳回必须填写原因 (notes 不能为空)");
        }
        Long userId = extractUserId(authorization);
        PurchaseOrder order = purchaseService.financeRejectOrder(factoryId, orderId, userId, notes);
        return ApiResponse.success("财务审核已驳回", order);
    }

    // ==================== 入库管理 ====================

    @RequireModule("purchase_order")
    @PostMapping("/receives")
    @Operation(summary = "创建入库单")
    @RequirePermission({"procurement:read_write", "inventory:write"})
    public ApiResponse<PurchaseReceiveRecord> createReceive(
            @PathVariable @NotBlank String factoryId,
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody CreateReceiveRecordRequest request) {
        Long userId = extractUserId(authorization);
        log.info("创建入库单: factoryId={}, supplierId={}", factoryId, request.getSupplierId());
        PurchaseReceiveRecord record = purchaseService.createReceiveRecord(factoryId, request, userId);
        return ApiResponse.success("入库单创建成功", record);
    }

    /**
     * 采购入库默认仓 — 解析本工厂配置的 {@code PURCHASE_INBOUND_DEFAULT} 默认仓, 供前端「新建入库单」
     * 预填入库仓库 (防呆 Rule 1: 别让仓管每次手动重选)。
     *
     * <p>返回 {@link FactoryWarehouse} (含 code/name/type), 前端据此预选。honest-null: 若工厂缺少
     * 仓库 seed (resolver 抛异常) 或解析出的仓库已失效, 返回 {@code data=null} → 前端回退本地默认逻辑,
     * 不 500 阻断新建入库。
     *
     * <p>权限对齐入库单读取 (procurement read/write + inventory:write), 让实际收货的仓管/采购员
     * 都能拿到默认仓 —— 不像 {@code /factory/warehouse-defaults} 仅超管可读。read-only, 不改任何库存。
     */
    @GetMapping("/receives/default-warehouse")
    @Operation(summary = "采购入库默认仓 (解析 PURCHASE_INBOUND_DEFAULT 配置)")
    @RequirePermission({"procurement:read_write", "procurement:read", "inventory:write"})
    public ApiResponse<com.cretas.aims.entity.factory.FactoryWarehouse> getDefaultReceiveWarehouse(
            @PathVariable @NotBlank String factoryId) {
        com.cretas.aims.entity.factory.FactoryWarehouse warehouse = null;
        try {
            String warehouseId = warehouseResolver.resolvePurchaseInboundWh(factoryId);
            warehouse = factoryWarehouseRepository
                    .findByIdAndFactoryIdAndDeletedAtIsNull(warehouseId, factoryId)
                    .orElse(null);
        } catch (BusinessException e) {
            // 工厂缺 WH-LOG 兜底 seed (resolvePurchaseInboundWh fallback resolveId 抛 500) —
            // honest-null 返回, 让前端回退本地默认仓逻辑, 不阻断新建入库。
            log.warn("解析采购入库默认仓失败 factoryId={}: {}", factoryId, e.getMessage());
        }
        return ApiResponse.success("查询成功", warehouse);
    }

    @GetMapping("/receives")
    @Operation(summary = "入库单列表")
    @RequirePermission({"procurement:read_write", "procurement:read"})
    public ApiResponse<PageResponse<PurchaseReceiveRecord>> listReceives(
            @PathVariable @NotBlank String factoryId,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码必须大于0") int page,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "每页大小必须大于0") int size) {
        PageResponse<PurchaseReceiveRecord> result = purchaseService.getReceiveRecords(factoryId, page, size);
        return ApiResponse.success("查询成功", result);
    }

    @GetMapping("/receives/{receiveId}")
    @Operation(summary = "入库单详情")
    @RequirePermission({"procurement:read_write", "procurement:read"})
    public ApiResponse<PurchaseReceiveRecord> getReceive(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String receiveId) {
        PurchaseReceiveRecord record = purchaseService.getReceiveRecordById(factoryId, receiveId);
        return ApiResponse.success("查询成功", record);
    }

    @RequireModule("purchase_order")
    @PostMapping("/receives/{receiveId}/confirm")
    @Operation(summary = "确认入库（生成物料批次）")
    @RequirePermission({"procurement:read_write", "inventory:write"})
    public ApiResponse<PurchaseReceiveRecord> confirmReceive(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String receiveId,
            @RequestHeader("Authorization") String authorization) {
        Long userId = extractUserId(authorization);
        PurchaseReceiveRecord record = purchaseService.confirmReceive(factoryId, receiveId, userId);
        return ApiResponse.success("入库确认成功，物料批次已创建", record);
    }

    @GetMapping("/receives/by-order/{orderId}")
    @Operation(summary = "按采购订单查询入库记录")
    @RequirePermission({"procurement:read_write", "procurement:read"})
    public ApiResponse<List<PurchaseReceiveRecord>> getReceivesByOrder(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String orderId) {
        List<PurchaseReceiveRecord> records = purchaseService.getReceiveRecordsByOrder(orderId);
        return ApiResponse.success("查询成功", records);
    }

    // ==================== 统计 ====================

    @GetMapping("/statistics")
    @Operation(summary = "采购统计数据")
    @RequirePermission({"procurement:read_write", "procurement:read", "report:read"})
    public ApiResponse<Map<String, Object>> getStatistics(
            @PathVariable @NotBlank String factoryId) {
        Map<String, Object> stats = purchaseService.getPurchaseStatistics(factoryId);
        return ApiResponse.success("查询成功", stats);
    }

    /**
     * Issue #787 follow-up to PR #782 / #775: cumulative-received aggregate endpoint.
     *
     * <p>之前前端 '累计已收' 列在 RCV list 页 FE-only 聚合 current page rows — 跨 page 不准.
     * 现在前端改用此 endpoint, 后端按 PO items 直接读 receivedQuantity (confirmReceive 时累加).
     *
     * <p>Response: {@code {poId, orderNumber, plannedTotal, cumulativeReceived, lines: [...] }}
     */
    @GetMapping("/orders/{orderId}/cumulative-received")
    @Operation(summary = "采购订单累计已收汇总 (Issue #787)",
            description = "按行返回 plannedQty/receivedQty/pendingQty, 替代 FE-only page-rows 聚合.")
    @RequirePermission({"procurement:read_write", "procurement:read"})
    public ApiResponse<Map<String, Object>> getCumulativeReceived(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String orderId) {
        Map<String, Object> result = purchaseService.getCumulativeReceived(factoryId, orderId);
        return ApiResponse.success("查询成功", result);
    }

    /**
     * 单元 G (F006 R-B3): 采购订单分次收货时序明细.
     *
     * <p>客户张权 (5/8 system review): "收货数量要显示出来 (第一次收了多少第二次收了多少更直观)".
     * 不同于 {@link #getCumulativeReceived} (按 PO item 读累计总量), 本 endpoint 按收货发生
     * 时间顺序返回**每次收货事件**, 逐条带 1-based seq + 该次数量 + 明细.
     *
     * <p>Response: {@code [{seq, receiveId, receiveNumber, receiveDate, createdAt,
     *                        createdByName, totalQuantity, items: [...]}]} (空列表 = 暂无收货).
     */
    @GetMapping("/orders/{orderId}/receives")
    @Operation(summary = "采购订单分次收货时序明细 (单元G F006 R-B3)",
            description = "按 createdAt 升序返回每次收货事件 (第N次/日期/数量), 替代 FE-only page-rows 聚合.")
    @RequirePermission({"procurement:read_write", "procurement:read"})
    public ApiResponse<List<Map<String, Object>>> getOrderReceiveSequence(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String orderId) {
        List<Map<String, Object>> result = purchaseService.getOrderReceiveSequence(factoryId, orderId);
        return ApiResponse.success("查询成功", result);
    }

    // ==================== 三价对比 ====================

    @GetMapping("/orders/{orderId}/price-comparison")
    @Operation(summary = "采购订单三价对比", description = "对比每个行项目的BOM标准单价、移动平均价、当前采购单价")
    // Sprint1-Fix-K5 (2026-05-15): drop procurement:read AND finance:read —
    // 5x5 RBAC regression: viewer (has finance:read for cross-module read) still
    // leaked currentPrice. 三价对比仅 procurement:read_write + finance:read_write
    // (管理员/经理写权限) 可看; 只读角色 (viewer 等) deny。
    @RequirePermission({"procurement:read_write", "finance:read_write"})
    public ApiResponse<List<MaterialPriceComparisonDTO>> getOrderPriceComparison(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String orderId) {
        List<MaterialPriceComparisonDTO> result = purchaseService.getOrderPriceComparison(factoryId, orderId);
        return ApiResponse.success("查询成功", result);
    }

    @GetMapping("/materials/{materialTypeId}/price-info")
    @Operation(summary = "原料三价查询", description = "查询单个原料的BOM标准单价、移动平均价，可选传入当前价计算偏差")
    // Sprint1-Fix-K5 (2026-05-15): sister site of price-comparison, same lock (drop finance:read).
    @RequirePermission({"procurement:read_write", "finance:read_write"})
    public ApiResponse<MaterialPriceComparisonDTO> getMaterialPriceInfo(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String materialTypeId,
            @RequestParam(required = false) BigDecimal currentPrice) {
        MaterialPriceComparisonDTO result = purchaseService.getMaterialPriceInfo(factoryId, materialTypeId, currentPrice);
        return ApiResponse.success("查询成功", result);
    }

    // ==================== 开始采购 — 从 SO 生成采购建议 ====================

    /**
     * 从销售订单一键生成采购建议明细.
     *
     * <p>客户原话 (t2b 行1867-1902 [61:17-62:02]):
     * "做个弹窗…我直接点开始采购…不然新增有点麻烦，全部手写没意义"。
     *
     * <p>展开 BOM，扣减现有库存，返回预填 PO 明细（原辅料/包材 + 净需求数量）。
     * 无 BOM 配置时 hasBom=false，items 为空，前端诚实提示。
     *
     * <p>路径: {@code GET /api/mobile/{factoryId}/purchase/orders/suggestions/from-so/{salesOrderId}}
     */
    @GetMapping("/orders/suggestions/from-so/{salesOrderId}")
    @Operation(summary = "从销售订单生成采购建议", description = "展开BOM计算原辅料/包材需求量，扣减库存得净需求，返回预填PO明细")
    @RequirePermission({"procurement:read_write", "procurement:read"})
    public ApiResponse<PurchaseSuggestionResponse> generatePurchaseSuggestion(
            @PathVariable @jakarta.validation.constraints.NotBlank String factoryId,
            @PathVariable @jakarta.validation.constraints.NotBlank String salesOrderId) {
        PurchaseSuggestionResponse result = purchaseService.generatePurchaseSuggestion(factoryId, salesOrderId);
        return ApiResponse.success("采购建议生成成功", result);
    }

    /**
     * 多销售订单合并生成采购建议 (转录行3650: 多个 SO 用"加号"逐个追加合并成一张采购单).
     *
     * <p>跨所有 SO 按 materialTypeId 聚合需求, 统一扣一次库存得净需求。
     * 返回每行标注合并自哪几张 SO + 逐 SO 的 hasBom 摘要 (诚实暴露无配方的 SO)。
     *
     * <p>路径: {@code POST /api/mobile/{factoryId}/purchase/orders/suggestions/from-so-multi}
     */
    @PostMapping("/orders/suggestions/from-so-multi")
    @Operation(summary = "多销售订单合并生成采购建议",
            description = "跨多张SO展开BOM并按物料聚合需求, 统一扣减库存得净需求, 返回合并预填PO明细")
    @RequirePermission({"procurement:read_write", "procurement:read"})
    public ApiResponse<com.cretas.aims.dto.inventory.PurchaseSuggestionMultiResponse> generatePurchaseSuggestionMulti(
            @PathVariable @NotBlank String factoryId,
            @Valid @RequestBody com.cretas.aims.dto.inventory.MultiSoPurchaseSuggestionRequest request) {
        var result = purchaseService.generatePurchaseSuggestionMulti(factoryId, request.getSalesOrderIds());
        return ApiResponse.success("采购建议生成成功", result);
    }

    // ==================== 内部方法 ====================

    private Long extractUserId(String authorization) {
        String token = TokenUtils.extractToken(authorization);
        return mobileService.getUserFromToken(token).getId();
    }

    /**
     * Resolve the authenticated user entity (not DTO) for permission checks.
     * Returns {@code null} when token is missing or user is not found — caller decides
     * whether to fail open or closed. For RBAC defense-in-depth, callers MUST treat
     * null as "no permission" (closed-by-default).
     */
    private User resolveCurrentUser(String authorization) {
        try {
            Long userId = extractUserId(authorization);
            if (userId == null) {
                return null;
            }
            return userRepository.findById(userId).orElse(null);
        } catch (Exception e) {
            log.debug("resolveCurrentUser failed: {}", e.getMessage());
            return null;
        }
    }
}
