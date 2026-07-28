package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.foodsafety.SupplierQualification;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.foodsafety.SupplierQualificationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 供应商下单 gate 检查 Tool (READ + RULE) — Sprint 9 P2.C Phase B (PO 创建前 gate).
 *
 * <p>检查指定供应商是否所有强制性食品资质均有效, 返回 {@code canOrder: true/false}
 * + 详细 reason. 用于 PO create 前 client-side BLOCKING 显示 + server-side PO validator.
 *
 * <p>规则:
 * <ul>
 *   <li>无任何 EXPIRED 资质 → 通过</li>
 *   <li>任何 EXPIRED → canOrder=false (BLOCKING)</li>
 *   <li>有 EXPIRING (7天内) → canOrder=true 但 warning (允许下单, 但 UI 提示)</li>
 *   <li>有 REVOKED 资质中的强制类型 → canOrder=false</li>
 * </ul>
 *
 * <p>"强制类型" 缺省含: SC_LICENSE, BUSINESS_LICENSE. 若有 ISO22000/HACCP_CERT 也 EXPIRED
 * 仅 warning 不阻止 (这两个非法定强制).
 *
 * <p>防呆 4 位一体:
 * <ul>
 *   <li>R1: 返 canOrder=true/false + reason 明确</li>
 *   <li>R2: message 含供应商名称 + 具体哪份证 EXPIRED</li>
 *   <li>R5: 即使 BLOCKING 也含 actionHint 跳到资质管理页</li>
 * </ul>
 *
 * <p>LLM 触发场景:
 * <ul>
 *   <li>"X 能下单吗"</li>
 *   <li>"X 资质完整吗"</li>
 *   <li>"给 X 下采购单可以吗"</li>
 *   <li>"X 还能进货吗"</li>
 * </ul>
 *
 * <p>read-only.
 *
 * <p>Intent Code: {@code SUPPLIER_CAN_ORDER_CHECK}
 */
@Slf4j
@Component
public class SupplierCanOrderCheckTool extends AbstractBusinessTool {

    /** 强制类型 — EXPIRED 则禁止下单. */
    private static final Set<String> MANDATORY_TYPES = Set.of(
            "SC_LICENSE", "BUSINESS_LICENSE"
    );

    @Autowired
    private SupplierQualificationRepository qualificationRepository;

    @Autowired
    private SupplierRepository supplierRepository;

    @Override
    public String getToolName() {
        return "supplier_can_order_check";
    }

    @Override
    public String getDescription() {
        return "供应商下单前资质检查 (READ + RULE) — 强制资质 (SC 证+营业执照) 有效才能下采购单. "
                + "返 canOrder=true/false + reason. LLM 触发: 'X 能下单吗' / 'X 资质完整吗' / "
                + "'给 X 下采购单可以吗' / 'X 还能进货吗'. PO 创建前 gate.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> supplierId = new HashMap<>();
        supplierId.put("type", "string");
        supplierId.put("description", "供应商 ID (必填). UUID");
        properties.put("supplierId", supplierId);

        schema.put("properties", properties);
        schema.put("required", List.of("supplierId"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("supplierId");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        String supplierId = getString(params, "supplierId");
        log.info("supplier_can_order_check — factory={} supplier={}", factoryId, supplierId);

        Optional<Supplier> supOpt = supplierRepository.findByIdAndFactoryId(supplierId, factoryId);
        if (supOpt.isEmpty()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("canOrder", false);
            data.put("supplierId", supplierId);
            data.put("supplierName", null);
            data.put("reason", "SUPPLIER_NOT_FOUND");
            data.put("severity", "BLOCKING");
            data.put("actionHint", "/quality/supplier-qualifications");
            return buildSimpleResult(
                    String.format("⛔ 供应商 %s 不存在, 无法下单", supplierId), data);
        }
        Supplier sup = supOpt.get();

        // 查全部 status (含 EXPIRED / REVOKED) 才能判断
        List<String> allStatuses = List.of("VALID", "EXPIRING", "EXPIRED", "REVOKED");
        List<SupplierQualification> all = qualificationRepository
                .findByFactoryIdAndSupplierIdAndStatusIn(factoryId, supplierId, allStatuses);

        LocalDate today = LocalDate.now();
        List<Map<String, Object>> blockingIssues = new ArrayList<>();
        List<Map<String, Object>> warnings = new ArrayList<>();

        // 1. 强制类型必须存在且 VALID/EXPIRING
        for (String mandatory : MANDATORY_TYPES) {
            List<SupplierQualification> ofType = all.stream()
                    .filter(q -> mandatory.equals(q.getQualificationType()))
                    .toList();

            // 全部 EXPIRED / REVOKED 或 缺失 → BLOCKING
            boolean hasUsable = ofType.stream()
                    .anyMatch(q -> "VALID".equals(q.getStatus()) || "EXPIRING".equals(q.getStatus()));

            if (!hasUsable) {
                Map<String, Object> issue = new LinkedHashMap<>();
                issue.put("qualificationType", mandatory);
                if (ofType.isEmpty()) {
                    issue.put("reason", "MISSING");
                    issue.put("detail", String.format("供应商缺少强制资质 %s", mandatory));
                } else {
                    SupplierQualification latest = ofType.get(0);
                    issue.put("reason", "EXPIRED_OR_REVOKED");
                    issue.put("detail", String.format(
                            "强制资质 %s (证号 %s) 已 %s",
                            mandatory, latest.getCertificateNumber(), latest.getStatus()));
                    issue.put("certificateNumber", latest.getCertificateNumber());
                    issue.put("expiryDate", latest.getExpiryDate() != null
                            ? latest.getExpiryDate().toString() : null);
                }
                blockingIssues.add(issue);
            }
        }

        // 2. EXPIRING (7天内) → warning (不 block)
        for (SupplierQualification q : all) {
            if (!"EXPIRING".equals(q.getStatus()) && !"VALID".equals(q.getStatus())) continue;
            if (q.getExpiryDate() == null) continue;
            long days = ChronoUnit.DAYS.between(today, q.getExpiryDate());
            if (days >= 0 && days <= 7) {
                Map<String, Object> warn = new LinkedHashMap<>();
                warn.put("qualificationType", q.getQualificationType());
                warn.put("certificateNumber", q.getCertificateNumber());
                warn.put("expiryDate", q.getExpiryDate().toString());
                warn.put("daysUntilExpiry", days);
                warn.put("severity", "URGENT");
                warnings.add(warn);
            }
        }

        boolean canOrder = blockingIssues.isEmpty();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("canOrder", canOrder);
        data.put("supplierId", supplierId);
        data.put("supplierName", sup.getName());
        data.put("blockingIssues", blockingIssues);
        data.put("warnings", warnings);
        data.put("mandatoryTypes", MANDATORY_TYPES);
        data.put("severity", canOrder ? (warnings.isEmpty() ? "OK" : "WARNING") : "BLOCKING");
        data.put("actionHint", "/quality/supplier-qualifications?supplierId=" + supplierId);

        String message;
        if (canOrder) {
            if (warnings.isEmpty()) {
                message = String.format("✅ 供应商 %s 资质完整, 可下单", sup.getName());
            } else {
                message = String.format("⚠️ 供应商 %s 可下单, 但有 %d 项资质 7 天内将过期, 建议尽快续期",
                        sup.getName(), warnings.size());
            }
        } else {
            StringBuilder details = new StringBuilder();
            for (Map<String, Object> issue : blockingIssues) {
                details.append(issue.get("detail")).append("; ");
            }
            message = String.format("⛔ [BLOCKING] 供应商 %s 不可下单: %s",
                    sup.getName(), details.toString().trim());
        }
        return buildSimpleResult(message, data);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
