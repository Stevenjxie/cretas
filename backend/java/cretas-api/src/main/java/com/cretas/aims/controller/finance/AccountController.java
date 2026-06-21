package com.cretas.aims.controller.finance;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.enums.AccountCategory;
import com.cretas.aims.entity.finance.Account;
import com.cretas.aims.service.finance.AccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Account (会计科目) REST API — Sprint 7 T1 Phase B.
 *
 * <p>路径 <code>/api/mobile/{factoryId}/finance/accounts</code>:
 * <ul>
 *   <li>GET            list (factory 自定义 + 系统标准 union, query active 可选)</li>
 *   <li>GET /tree      tree-grouped by category (帮 UI 渲染 4 大类树)</li>
 *   <li>GET /{id}      detail</li>
 *   <li>POST           create (factory 自定义科目, factoryId 由 path 注入)</li>
 *   <li>PUT /{id}      update name / description / active / sortOrder</li>
 *   <li>DELETE /{id}   soft delete (有子科目 / 被引用时 409)</li>
 * </ul>
 *
 * <p>RBAC: 读 finance:read / 写 finance:read_write (跟 VoucherController 对齐).
 */
@RestController
@RequestMapping("/api/mobile/{factoryId}/finance/accounts")
@RequiredArgsConstructor
@RequirePermission({"finance:read", "finance:read_write"})
public class AccountController {

    private final AccountService accountService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<Account>>> list(
            @PathVariable String factoryId,
            @RequestParam(value = "activeOnly", defaultValue = "false") boolean activeOnly,
            @RequestParam(value = "category", required = false) AccountCategory category) {
        List<Account> data;
        if (category != null) {
            data = accountService.listByCategory(factoryId, category);
        } else if (activeOnly) {
            data = accountService.listActiveVisible(factoryId);
        } else {
            data = accountService.listVisible(factoryId);
        }
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    /**
     * Tree view grouped by category — UI 用 (4 大类 x N 科目).
     * 返回 Map<category, List<Account>> 结构, 前端按 category 自行渲染 tabs.
     */
    @GetMapping("/tree")
    public ResponseEntity<ApiResponse<Map<AccountCategory, List<Account>>>> tree(
            @PathVariable String factoryId) {
        List<Account> all = accountService.listVisible(factoryId);
        Map<AccountCategory, List<Account>> grouped = new java.util.EnumMap<>(AccountCategory.class);
        for (AccountCategory cat : AccountCategory.values()) {
            grouped.put(cat, new java.util.ArrayList<>());
        }
        for (Account a : all) {
            grouped.get(a.getCategory()).add(a);
        }
        return ResponseEntity.ok(ApiResponse.success(grouped));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Account>> detail(
            @PathVariable String factoryId, @PathVariable String id) {
        Account a = accountService.findById(factoryId, id)
                .orElseThrow(() -> new com.cretas.aims.exception.BusinessException(404, "科目不存在: " + id));
        return ResponseEntity.ok(ApiResponse.success(a));
    }

    @PostMapping
    @RequirePermission("finance:read_write")
    public ResponseEntity<ApiResponse<Account>> create(
            @PathVariable String factoryId,
            @Valid @RequestBody Account body) {
        body.setFactoryId(factoryId);  // path 注入, 防客户端伪造
        body.setId(null);  // 强制 generate UUID
        Account saved = accountService.create(body);
        return ResponseEntity.ok(ApiResponse.success("科目已创建", saved));
    }

    @PutMapping("/{id}")
    @RequirePermission("finance:read_write")
    public ResponseEntity<ApiResponse<Account>> update(
            @PathVariable String factoryId,
            @PathVariable String id,
            @Valid @RequestBody Account body) {
        Account saved = accountService.update(factoryId, id, body);
        return ResponseEntity.ok(ApiResponse.success("科目已更新", saved));
    }

    @DeleteMapping("/{id}")
    @RequirePermission("finance:read_write")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable String factoryId, @PathVariable String id) {
        accountService.softDelete(factoryId, id);
        return ResponseEntity.ok(ApiResponse.successMessage("科目已删除"));
    }
}
