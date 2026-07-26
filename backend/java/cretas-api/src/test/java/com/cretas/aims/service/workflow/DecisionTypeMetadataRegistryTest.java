package com.cretas.aims.service.workflow;

import com.cretas.aims.entity.config.ApprovalChainConfig.DecisionType;
import com.cretas.aims.service.workflow.DecisionTypeMetadata.Category;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DecisionTypeMetadataRegistry 单元测试 — Sprint 6 W3-B (2026-05-19).
 *
 * <p>验证 32 个 DecisionType 全部注册 + 元数据正确 + moduleCode 反向 lookup +
 * wired 数量统计.
 *
 * <p>Sprint 5 PR #55 H 扩 enum 14→32, 之前 admin UI hardcode 12 个 dropdown 选项, 这
 * 17 个 NEW 类的测试覆盖 (per W3-B spec "17 unit tests min") + 14 原有 + CUSTOM
 * + 元数据 framework 4 个 cross-cutting tests = 35+ tests.
 *
 * @since 2026-05-19
 */
class DecisionTypeMetadataRegistryTest {

    private DecisionTypeMetadataRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DecisionTypeMetadataRegistry();
        registry.init();
    }

    // ==================== Cross-cutting framework tests (4) ====================

    @Test
    @DisplayName("UT-MR-01: 全部 32 个 enum 都注册 (registry 完整性自检)")
    void allEnumValuesRegistered() {
        assertEquals(DecisionType.values().length, registry.size(),
                "registry size 必须等于 enum 总数 (包含 CUSTOM)");
        for (DecisionType dt : DecisionType.values()) {
            assertNotNull(registry.get(dt),
                    "DecisionType " + dt + " 元数据未注册 — 检查 init() 是否漏 register");
        }
    }

    @Test
    @DisplayName("UT-MR-02: getAll 返 read-only map, modify 抛 UnsupportedOperationException")
    void getAllReturnsReadOnlyMap() {
        Map<DecisionType, DecisionTypeMetadata> all = registry.getAll();
        assertEquals(registry.size(), all.size());
        assertThrows(UnsupportedOperationException.class,
                () -> all.put(DecisionType.CUSTOM, null),
                "getAll 必须返 unmodifiable view, 防止 caller 篡改 registry");
    }

    @Test
    @DisplayName("UT-MR-03: 每个 metadata 必有 chineseName / description / category / approverRoles")
    void allMetadataHasRequiredFields() {
        for (DecisionType dt : DecisionType.values()) {
            DecisionTypeMetadata m = registry.get(dt);
            assertNotNull(m.getChineseName(), dt + " chineseName null");
            assertFalse(m.getChineseName().isBlank(), dt + " chineseName blank");
            assertNotNull(m.getDescription(), dt + " description null");
            assertNotNull(m.getCategory(), dt + " category null");
            assertNotNull(m.getDefaultApproverRoles(), dt + " approverRoles null");
            assertFalse(m.getDefaultApproverRoles().isEmpty(),
                    dt + " approverRoles empty — 至少要 1 个默认角色");
        }
    }

    @Test
    @DisplayName("UT-MR-04: wiredCount + unwired 之和 = 32")
    void wiredCountConsistent() {
        int wired = registry.getWiredCount();
        int unwired = (int) registry.getAll().values().stream().filter(m -> !m.isWired()).count();
        assertEquals(registry.size(), wired + unwired,
                "wired + unwired 必须 = 总数");
        assertTrue(wired >= 7,
                "已 wired DecisionType 应至少覆盖采购/销售/付款/预算/调拨/盘点/餐饮复核, 实际=" + wired);
    }

    // ==================== 17 NEW DecisionType (Sprint 5 PR #55 H 加) ====================

    @Test
    @DisplayName("UT-MR-05: BOM_VERSION_APPROVAL — BOM 版本审批 (Sprint 6 W4-C 接入)")
    void bomVersionApprovalMetadata() {
        DecisionTypeMetadata m = registry.get(DecisionType.BOM_VERSION_APPROVAL);
        assertNotNull(m);
        assertEquals("BOM 版本审批", m.getChineseName());
        assertEquals(Category.PRODUCTION, m.getCategory());
        assertEquals("BOM_VERSION", m.getModuleCode());
        assertFalse(m.isWired(), "BOM_VERSION_APPROVAL 业务 service 接入留 W4-C, 当前 unwired");
        assertTrue(m.getDefaultApproverRoles().contains("technical_director"));
    }

    @Test
    @DisplayName("UT-MR-06: ECN_APPROVAL — 工程变更通知 (Sprint 6 W4-C 接入)")
    void ecnApprovalMetadata() {
        DecisionTypeMetadata m = registry.get(DecisionType.ECN_APPROVAL);
        assertNotNull(m);
        assertEquals("ECN 工程变更通知", m.getChineseName());
        assertEquals(Category.PRODUCTION, m.getCategory());
        assertEquals("ECN", m.getModuleCode());
    }

    @Test
    @DisplayName("UT-MR-07: WORK_ORDER_APPROVAL — 工单审批")
    void workOrderApprovalMetadata() {
        DecisionTypeMetadata m = registry.get(DecisionType.WORK_ORDER_APPROVAL);
        assertNotNull(m);
        assertEquals("工单审批", m.getChineseName());
        assertEquals(Category.PRODUCTION, m.getCategory());
    }

    @Test
    @DisplayName("UT-MR-08: MATERIAL_REQUISITION_APPROVAL — 请购单 (Sprint 5 D 已 entity, W3-B unwired)")
    void materialRequisitionApprovalMetadata() {
        DecisionTypeMetadata m = registry.get(DecisionType.MATERIAL_REQUISITION_APPROVAL);
        assertNotNull(m);
        assertEquals("请购单审批", m.getChineseName());
        assertEquals(Category.QUALITY_MATERIAL, m.getCategory());
        assertEquals("MATERIAL_REQUISITION", m.getModuleCode());
    }

    @Test
    @DisplayName("UT-MR-09: PURCHASE_PAYMENT_APPROVAL — 采购付款")
    void purchasePaymentApprovalMetadata() {
        DecisionTypeMetadata m = registry.get(DecisionType.PURCHASE_PAYMENT_APPROVAL);
        assertNotNull(m);
        assertEquals("采购付款申请审批", m.getChineseName());
        assertEquals(Category.PURCHASE_SUPPLIER, m.getCategory());
        assertTrue(m.isWired());
    }

    @Test
    @DisplayName("UT-MR-10: PURCHASE_RETURN_APPROVAL — 采购退货")
    void purchaseReturnApprovalMetadata() {
        DecisionTypeMetadata m = registry.get(DecisionType.PURCHASE_RETURN_APPROVAL);
        assertNotNull(m);
        assertEquals("采购退货审批", m.getChineseName());
        assertEquals(Category.PURCHASE_SUPPLIER, m.getCategory());
    }

    @Test
    @DisplayName("UT-MR-11: SALES_RETURN_APPROVAL — 销售退货")
    void salesReturnApprovalMetadata() {
        DecisionTypeMetadata m = registry.get(DecisionType.SALES_RETURN_APPROVAL);
        assertNotNull(m);
        assertEquals("销售退货审批", m.getChineseName());
        assertEquals(Category.SALES_CUSTOMER, m.getCategory());
    }

    @Test
    @DisplayName("UT-MR-12: SALES_DISCOUNT_APPROVAL — 销售折扣")
    void salesDiscountApprovalMetadata() {
        DecisionTypeMetadata m = registry.get(DecisionType.SALES_DISCOUNT_APPROVAL);
        assertNotNull(m);
        assertEquals("销售折扣审批", m.getChineseName());
        assertTrue(m.getDefaultApproverRoles().contains("finance_manager"),
                "销售折扣涉及让利, 默认审批人应含财务");
    }

    @Test
    @DisplayName("UT-MR-13: CUSTOMER_CREDIT_APPROVAL — 客户额度")
    void customerCreditApprovalMetadata() {
        DecisionTypeMetadata m = registry.get(DecisionType.CUSTOMER_CREDIT_APPROVAL);
        assertNotNull(m);
        assertEquals("客户额度审批", m.getChineseName());
        assertEquals(Category.SALES_CUSTOMER, m.getCategory());
    }

    @Test
    @DisplayName("UT-MR-14: INVOICE_ISSUANCE_APPROVAL — 发票开具")
    void invoiceIssuanceApprovalMetadata() {
        DecisionTypeMetadata m = registry.get(DecisionType.INVOICE_ISSUANCE_APPROVAL);
        assertNotNull(m);
        assertEquals("发票开具审批", m.getChineseName());
        assertEquals(Category.SALES_CUSTOMER, m.getCategory());
    }

    @Test
    @DisplayName("UT-MR-15: VOUCHER_APPROVAL — 凭证审核")
    void voucherApprovalMetadata() {
        DecisionTypeMetadata m = registry.get(DecisionType.VOUCHER_APPROVAL);
        assertNotNull(m);
        assertEquals("凭证审核", m.getChineseName());
        assertEquals(Category.FINANCE_VOUCHER, m.getCategory());
    }

    @Test
    @DisplayName("UT-MR-16: BUDGET_APPROVAL — 预算审批")
    void budgetApprovalMetadata() {
        DecisionTypeMetadata m = registry.get(DecisionType.BUDGET_APPROVAL);
        assertNotNull(m);
        assertEquals("预算审批", m.getChineseName());
    }

    @Test
    @DisplayName("UT-MR-17: PAYMENT_APPROVAL — 付款审批")
    void paymentApprovalMetadata() {
        DecisionTypeMetadata m = registry.get(DecisionType.PAYMENT_APPROVAL);
        assertNotNull(m);
        assertEquals("付款审批", m.getChineseName());
        assertEquals(Category.FINANCE_VOUCHER, m.getCategory());
    }

    @Test
    @DisplayName("UT-MR-18: TAX_FILING_APPROVAL — 税务申报")
    void taxFilingApprovalMetadata() {
        DecisionTypeMetadata m = registry.get(DecisionType.TAX_FILING_APPROVAL);
        assertNotNull(m);
        assertEquals("税务申报审批", m.getChineseName());
    }

    @Test
    @DisplayName("UT-MR-19: WAGE_RECORD_APPROVAL — 工资记录 (Sprint 6 W4-B 接入)")
    void wageRecordApprovalMetadata() {
        DecisionTypeMetadata m = registry.get(DecisionType.WAGE_RECORD_APPROVAL);
        assertNotNull(m);
        assertEquals("工资记录审批", m.getChineseName());
        assertEquals(Category.HR_WAGE, m.getCategory());
    }

    @Test
    @DisplayName("UT-MR-20: HIRE_APPROVAL — 招聘入职")
    void hireApprovalMetadata() {
        DecisionTypeMetadata m = registry.get(DecisionType.HIRE_APPROVAL);
        assertNotNull(m);
        assertEquals("招聘/入职审批", m.getChineseName());
    }

    @Test
    @DisplayName("UT-MR-21: INVENTORY_TRANSFER_APPROVAL — 库存调拨")
    void inventoryTransferApprovalMetadata() {
        DecisionTypeMetadata m = registry.get(DecisionType.INVENTORY_TRANSFER_APPROVAL);
        assertNotNull(m);
        assertEquals("库存调拨审批", m.getChineseName());
        assertEquals(Category.WAREHOUSE_TRANSFER, m.getCategory());
    }

    @Test
    @DisplayName("UT-MR-22: INVENTORY_ADJUSTMENT_APPROVAL — 库存调整")
    void inventoryAdjustmentApprovalMetadata() {
        DecisionTypeMetadata m = registry.get(DecisionType.INVENTORY_ADJUSTMENT_APPROVAL);
        assertNotNull(m);
        assertEquals("库存调整审批", m.getChineseName());
        assertTrue(m.isWired());
    }

    @Test
    @DisplayName("UT-MR-23: WASTAGE_APPROVAL — 损耗审批")
    void wastageApprovalMetadata() {
        DecisionTypeMetadata m = registry.get(DecisionType.WASTAGE_APPROVAL);
        assertNotNull(m);
        assertEquals("损耗记录审批", m.getChineseName());
        assertEquals(Category.WAREHOUSE_TRANSFER, m.getCategory());
    }

    // ==================== Existing 14 (sanity check) ====================

    @Test
    @DisplayName("UT-MR-24: LEAVE_APPROVAL — 旧式布尔门禁不等于 Canvas 运行时接入")
    void leaveApprovalIsNotCanvasWired() {
        DecisionTypeMetadata m = registry.get(DecisionType.LEAVE_APPROVAL);
        assertNotNull(m);
        assertFalse(m.isWired(), "未创建持久化 Canvas 实例前不得标记 wired");
        assertEquals("LEAVE", m.getModuleCode());
    }

    @Test
    @DisplayName("UT-MR-25: PURCHASE_ORDER_APPROVAL — Phase 1 wired, moduleCode=PURCHASE_ORDER (legacy 兼容)")
    void purchaseOrderApprovalLegacyCompat() {
        DecisionTypeMetadata m = registry.get(DecisionType.PURCHASE_ORDER_APPROVAL);
        assertNotNull(m);
        assertTrue(m.isWired());
        assertEquals("PURCHASE_ORDER", m.getModuleCode(),
                "moduleCode 必须 = 'PURCHASE_ORDER' 跟 WorkflowEngineServiceImpl.MODULE_TO_DECISION 一致");
    }

    @Test
    @DisplayName("UT-MR-26: SALES_ORDER_APPROVAL — Phase 1 wired, moduleCode=SALES_ORDER (legacy 兼容)")
    void salesOrderApprovalLegacyCompat() {
        DecisionTypeMetadata m = registry.get(DecisionType.SALES_ORDER_APPROVAL);
        assertNotNull(m);
        assertTrue(m.isWired());
        assertEquals("SALES_ORDER", m.getModuleCode());
    }

    @Test
    @DisplayName("UT-MR-27: CUSTOM — moduleCode null (不参与 moduleCode lookup)")
    void customMetadata() {
        DecisionTypeMetadata m = registry.get(DecisionType.CUSTOM);
        assertNotNull(m);
        assertEquals("自定义", m.getChineseName());
        assertEquals(Category.OTHER, m.getCategory());
        assertNull(m.getModuleCode(), "CUSTOM 不应参与 moduleCode lookup");
        assertFalse(m.isWired());
    }

    // ==================== moduleCode 反向 lookup ====================

    @Test
    @DisplayName("UT-MR-28: lookupByModuleCode — 已知 moduleCode 返对应 DecisionType")
    void lookupByModuleCodeHits() {
        assertEquals(DecisionType.LEAVE_APPROVAL, registry.lookupByModuleCode("LEAVE"));
        assertEquals(DecisionType.EXPENSE_APPROVAL, registry.lookupByModuleCode("EXPENSE"));
        assertEquals(DecisionType.PURCHASE_ORDER_APPROVAL, registry.lookupByModuleCode("PURCHASE_ORDER"));
        assertEquals(DecisionType.SALES_ORDER_APPROVAL, registry.lookupByModuleCode("SALES_ORDER"));
        assertEquals(DecisionType.BOM_VERSION_APPROVAL, registry.lookupByModuleCode("BOM_VERSION"));
        assertEquals(DecisionType.ECN_APPROVAL, registry.lookupByModuleCode("ECN"));
        // SP12 T2
        assertEquals(DecisionType.PRODUCTION_REVERSAL_APPROVAL, registry.lookupByModuleCode("PRODUCTION_REVERSAL"));
    }

    // ==================== SP12 T2: PRODUCTION_REVERSAL_APPROVAL ====================

    @Test
    @DisplayName("UT-MR-32: PRODUCTION_REVERSAL_APPROVAL — SP12 T2, category=PRODUCTION, moduleCode=PRODUCTION_REVERSAL, wired=false")
    void productionReversalApprovalMetadata() {
        DecisionTypeMetadata m = registry.get(DecisionType.PRODUCTION_REVERSAL_APPROVAL);
        assertNotNull(m, "PRODUCTION_REVERSAL_APPROVAL 未注册 — 检查 DecisionTypeMetadataRegistry.init()");
        assertEquals("生产撤回审批", m.getChineseName());
        assertEquals(Category.PRODUCTION, m.getCategory());
        assertEquals("PRODUCTION_REVERSAL", m.getModuleCode());
        assertFalse(m.isWired(), "SP12 T3 接入前 wired 应为 false");
        assertTrue(m.getDefaultApproverRoles().contains("factory_super_admin")
                        || m.getDefaultApproverRoles().contains("dispatcher")
                        || m.getDefaultApproverRoles().contains("workshop_supervisor"),
                "默认审批人应含主管相关角色 (factory_super_admin / dispatcher / workshop_supervisor)");
    }

    @Test
    @DisplayName("UT-MR-33: PRODUCTION_REVERSAL_APPROVAL — lookupByModuleCode 双向验证")
    void productionReversalModuleCodeLookup() {
        DecisionType dt = registry.lookupByModuleCode("PRODUCTION_REVERSAL");
        assertEquals(DecisionType.PRODUCTION_REVERSAL_APPROVAL, dt,
                "lookupByModuleCode(\"PRODUCTION_REVERSAL\") 应返 PRODUCTION_REVERSAL_APPROVAL");
    }

    @Test
    @DisplayName("Restaurant Agent action review is wired to the exact allowlisted module")
    void restaurantAgentActionReviewMetadata() {
        DecisionTypeMetadata metadata = registry.get(DecisionType.RESTAURANT_AGENT_ACTION_REVIEW);
        assertNotNull(metadata);
        assertEquals(Category.OTHER, metadata.getCategory());
        assertTrue(metadata.isWired());
        assertEquals("restaurant.dish-cost-data-review.v1", metadata.getModuleCode());
        assertEquals(DecisionType.RESTAURANT_AGENT_ACTION_REVIEW,
                registry.lookupByModuleCode("restaurant.dish-cost-data-review.v1"));
    }

    @Test
    @DisplayName("UT-MR-29: lookupByModuleCode — 未知 moduleCode 返 null")
    void lookupByModuleCodeMiss() {
        assertNull(registry.lookupByModuleCode("UNKNOWN_MODULE"));
        assertNull(registry.lookupByModuleCode(""));
        assertNull(registry.lookupByModuleCode(null));
    }

    @Test
    @DisplayName("UT-MR-30: lookupByModuleCode — 每个 wired DecisionType moduleCode 必须 unique")
    void moduleCodeUnique() {
        java.util.Map<String, DecisionType> seen = new java.util.HashMap<>();
        for (DecisionTypeMetadata m : registry.getAll().values()) {
            String mc = m.getModuleCode();
            if (mc == null) continue;  // CUSTOM 允许 null
            DecisionType prev = seen.put(mc, m.getDecisionType());
            assertNull(prev, String.format(
                    "moduleCode '%s' 被两个 DecisionType 共用: %s 和 %s (会导致 lookupByModuleCode 行为不确定)",
                    mc, prev, m.getDecisionType()));
        }
    }

    // ==================== Category 分组完整性 ====================

    @Test
    @DisplayName("UT-MR-31: 每个 Category 至少 1 个 DecisionType (UI 分组不会出现空 group)")
    void everyCategoryHasMembers() {
        java.util.Set<Category> seenCategories = new java.util.HashSet<>();
        for (DecisionTypeMetadata m : registry.getAll().values()) {
            seenCategories.add(m.getCategory());
        }
        for (Category c : Category.values()) {
            assertTrue(seenCategories.contains(c),
                    "Category " + c + " 无任何 DecisionType — UI 分组会出现空 group");
        }
    }
}
