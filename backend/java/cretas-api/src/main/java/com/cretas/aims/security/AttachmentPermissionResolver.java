package com.cretas.aims.security;

import com.cretas.aims.entity.Attachment.EntityType;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.PermissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Entity-type aware RBAC + upload guardrails for {@link com.cretas.aims.controller.AttachmentController}.
 *
 * <p>Sprint 1 #658 shipped 8 attachment endpoints with zero RBAC; this resolver
 * supplies the entity-type-aware permission check that {@code @RequirePermission}
 * alone cannot express (the required module varies by the {@link EntityType}
 * passed in path/body/header). Admin roles bypass.
 *
 * <p>OSS upload-url guardrails — {@link #ALLOWED_CONTENT_TYPES} + {@link #MAX_UPLOAD_SIZE_BYTES} —
 * are enforced at signed-URL issue time to refuse oversize / executable uploads
 * before they hit object storage.
 *
 * @since 2026-05-15 (Sprint1-Fix-K2)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AttachmentPermissionResolver {

    private final PermissionService permissionService;
    private final UserRepository userRepository;

    /** Whitelisted upload MIME types — pdf / image / xlsx / docx / mp4. */
    public static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "application/pdf",
            "image/jpeg",
            "image/jpg",
            "image/png",
            "image/gif",
            "image/webp",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/msword",
            "video/mp4",
            "video/quicktime"
    );

    /** 10 MB hard ceiling on signed PUT URL issuance. */
    public static final long MAX_UPLOAD_SIZE_BYTES = 10L * 1024L * 1024L;

    /**
     * EntityType → module mapping. Each entity inherits the read/read_write
     * permission of its owning module ({@code module:read} for views,
     * {@code module:read_write} for mutations).
     */
    private static final Map<EntityType, String> ENTITY_MODULE;
    static {
        EnumMap<EntityType, String> m = new EnumMap<>(EntityType.class);
        m.put(EntityType.CUSTOMER, "sales");
        m.put(EntityType.CUSTOMER_TRACKING, "sales");
        m.put(EntityType.SALES_ORDER, "sales");
        m.put(EntityType.PURCHASE_ORDER, "procurement");
        m.put(EntityType.PURCHASE_RECEIPT, "procurement");
        m.put(EntityType.QUALITY_CHECK, "quality");
        m.put(EntityType.PRODUCTION_BATCH, "production");
        m.put(EntityType.PRODUCTION_REPORT, "production");
        m.put(EntityType.PAYMENT_VOUCHER, "finance");
        m.put(EntityType.INVOICE, "finance");
        m.put(EntityType.RD_SAMPLE, "rd");
        m.put(EntityType.RECEIPT, "finance");
        m.put(EntityType.RETURN_ORDER, "sales");
        m.put(EntityType.SHIPMENT, "sales");
        m.put(EntityType.INVENTORY, "warehouse");
        m.put(EntityType.WASTAGE_RECORD, "restaurant");
        m.put(EntityType.GROUP_LEADER_REPORT, "work_report");
        m.put(EntityType.EXPENSE_REPORT, "finance");
        m.put(EntityType.LEAVE_REQUEST, "hr");
        m.put(EntityType.TIMECLOCK_PHOTO, "hr");
        m.put(EntityType.ECN, "system");
        m.put(EntityType.CALL_RECORD, "sales");
        m.put(EntityType.GENERIC, "system");
        ENTITY_MODULE = java.util.Collections.unmodifiableMap(m);
    }

    /** Throws 403 if the resolved user lacks read access to attachments of {@code entityType}. */
    public void requireRead(User user, EntityType entityType) {
        require(user, entityType, false);
    }

    /** Throws 403 if the resolved user lacks read_write access to attachments of {@code entityType}. */
    public void requireWrite(User user, EntityType entityType) {
        require(user, entityType, true);
    }

    /**
     * Resolve the authenticated user from {@code request.userId} set by
     * {@link com.cretas.aims.config.JwtAuthInterceptor}. Returns {@code null} when
     * the attribute is missing or no row matches — caller MUST treat null as 401.
     */
    public User resolveCurrentUser(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Object userIdObj = request.getAttribute("userId");
        if (userIdObj == null) {
            return null;
        }
        Long userId;
        if (userIdObj instanceof Long) {
            userId = (Long) userIdObj;
        } else if (userIdObj instanceof Integer) {
            userId = ((Integer) userIdObj).longValue();
        } else if (userIdObj instanceof String) {
            try {
                userId = Long.parseLong((String) userIdObj);
            } catch (NumberFormatException e) {
                return null;
            }
        } else {
            return null;
        }
        try {
            return userRepository.findById(userId).orElse(null);
        } catch (Exception e) {
            log.debug("resolveCurrentUser failed for userId={}: {}", userId, e.getMessage());
            return null;
        }
    }

    /** Validate OSS upload-url request parameters before issuing a signed PUT URL. */
    public void validateUploadRequest(String contentType, Long declaredFileSize) {
        if (contentType == null || contentType.isBlank()) {
            throw new BusinessException(400, "fileType (Content-Type) 必填");
        }
        if (!ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase())) {
            throw new BusinessException(400,
                    "不支持的文件类型: " + contentType + " — 仅允许 pdf / image / xlsx / docx / mp4");
        }
        if (declaredFileSize != null && declaredFileSize > MAX_UPLOAD_SIZE_BYTES) {
            throw new BusinessException(400,
                    "文件超出 10MB 上限 (声明 " + declaredFileSize + " bytes)");
        }
    }

    private void require(User user, EntityType entityType, boolean write) {
        if (user == null) {
            throw new BusinessException(401, "未登录 — 无法访问附件");
        }
        if (entityType == null) {
            throw new BusinessException(400, "entityType 必填");
        }
        if (isAdmin(user)) {
            return;
        }
        String module = ENTITY_MODULE.get(entityType);
        if (module == null) {
            throw new BusinessException(403, "未知的附件实体类型 — 仅管理员可访问");
        }
        // 采购收货的写入口归仓储，采购仅保留来源单据的只读追溯。
        // 附件必须与收货动作使用同一职责边界，否则 warehouse_worker 能收货却无法上传供货凭证。
        String[] perms;
        if (entityType == EntityType.PURCHASE_RECEIPT) {
            perms = write
                    ? new String[]{"warehouse:read_write", "inventory:write"}
                    : new String[]{"warehouse:read", "warehouse:read_write",
                            "procurement:read", "procurement:read_write"};
        } else {
            perms = write
                    ? new String[]{module + ":read_write"}
                    : new String[]{module + ":read", module + ":read_write"};
        }
        boolean ok;
        try {
            ok = permissionService.hasAnyPermission(user, perms);
        } catch (Exception e) {
            log.warn("AttachmentPermissionResolver: permission lookup failed for userId={}, entityType={}, write={}: {}",
                    user.getId(), entityType, write, e.getMessage());
            ok = false;
        }
        if (!ok) {
            String roleLabel = user.getRoleEnum() == null ? "未知角色" : user.getRoleEnum().getDisplayName();
            throw new BusinessException(403,
                    "您的角色 [" + roleLabel + "] 无权" + (write ? "管理" : "查看")
                            + " [" + entityType.name() + "] 附件 (缺 " + String.join(" 或 ", perms) + ")");
        }
    }

    private boolean isAdmin(User user) {
        FactoryUserRole role = user.getRoleEnum();
        if (role == null) {
            return false;
        }
        return role == FactoryUserRole.platform_admin
                || role == FactoryUserRole.factory_super_admin;
    }
}
