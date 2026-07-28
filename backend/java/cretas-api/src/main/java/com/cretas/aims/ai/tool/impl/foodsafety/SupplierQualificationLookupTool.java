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

/**
 * 查询特定供应商的资质 Tool (READ) — Sprint 9 P2.C Phase B.
 *
 * <p>按 supplierId + 可选 qualificationType filter, 返回该供应商的全部 (或指定类型) 资质,
 * 按 expiry_date 升序. 包含已过期 / 已吊销 (透明展示状态).
 *
 * <p>LLM 触发场景:
 * <ul>
 *   <li>"查供应商 X 的资质"</li>
 *   <li>"X 有没有 SC 证"</li>
 *   <li>"X 的 HACCP 在哪"</li>
 *   <li>"查这家供应商所有证书"</li>
 * </ul>
 *
 * <p>read-only.
 *
 * <p>Intent Code: {@code SUPPLIER_QUALIFICATION_LOOKUP}
 */
@Slf4j
@Component
public class SupplierQualificationLookupTool extends AbstractBusinessTool {

    @Autowired
    private SupplierQualificationRepository qualificationRepository;

    @Autowired
    private SupplierRepository supplierRepository;

    @Override
    public String getToolName() {
        return "supplier_qualification_lookup";
    }

    @Override
    public String getDescription() {
        return "查询指定供应商的食品资质 (READ) — 按 supplierId + 可选 qualificationType. "
                + "LLM 触发: '查供应商 X 的资质' / 'X 有没有 SC 证' / "
                + "'X 的 HACCP 在哪'. read-only. 返全部状态 (VALID/EXPIRING/EXPIRED/REVOKED).";
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

        Map<String, Object> qualificationType = new HashMap<>();
        qualificationType.put("type", "string");
        qualificationType.put("description",
                "资质类型 (可选 filter): SC_LICENSE / IMPORT_CERT / HACCP_CERT / "
                        + "BUSINESS_LICENSE / ISO22000 / FSSC22000 / THIRD_PARTY_TEST / OTHER");
        properties.put("qualificationType", qualificationType);

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
        String qualType = getString(params, "qualificationType");
        log.info("supplier_qualification_lookup — factory={} supplier={} type={}",
                factoryId, supplierId, qualType);

        Optional<Supplier> supOpt = supplierRepository.findByIdAndFactoryId(supplierId, factoryId);
        String supplierName = supOpt.map(Supplier::getName).orElse(null);

        // 查全部状态 (4 种)
        List<String> allStatuses = List.of("VALID", "EXPIRING", "EXPIRED", "REVOKED");
        List<SupplierQualification> all = qualificationRepository
                .findByFactoryIdAndSupplierIdAndStatusIn(factoryId, supplierId, allStatuses);

        // 内存 filter by type
        if (qualType != null && !qualType.isBlank()) {
            all = all.stream()
                    .filter(q -> qualType.equals(q.getQualificationType()))
                    .toList();
        }

        LocalDate today = LocalDate.now();
        List<Map<String, Object>> rows = new ArrayList<>();
        int validCount = 0;
        int expiringCount = 0;
        int expiredCount = 0;
        int revokedCount = 0;

        for (SupplierQualification q : all) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", q.getId());
            row.put("qualificationType", q.getQualificationType());
            row.put("certificateNumber", q.getCertificateNumber());
            row.put("issuingAuthority", q.getIssuingAuthority());
            row.put("issueDate", q.getIssueDate() != null ? q.getIssueDate().toString() : null);
            row.put("expiryDate", q.getExpiryDate() != null ? q.getExpiryDate().toString() : null);
            long days = q.getExpiryDate() != null
                    ? ChronoUnit.DAYS.between(today, q.getExpiryDate()) : 0;
            row.put("daysUntilExpiry", days);
            row.put("status", q.getStatus());
            row.put("attachmentOssUrl", q.getAttachmentOssUrl());
            row.put("lastReviewedAt", q.getLastReviewedAt() != null
                    ? q.getLastReviewedAt().toString() : null);
            row.put("notes", q.getNotes());
            rows.add(row);

            switch (q.getStatus()) {
                case "VALID" -> validCount++;
                case "EXPIRING" -> expiringCount++;
                case "EXPIRED" -> expiredCount++;
                case "REVOKED" -> revokedCount++;
                default -> { /* ignore */ }
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("supplierId", supplierId);
        data.put("supplierName", supplierName);
        data.put("qualificationTypeFilter", qualType);
        data.put("totalCount", rows.size());
        data.put("validCount", validCount);
        data.put("expiringCount", expiringCount);
        data.put("expiredCount", expiredCount);
        data.put("revokedCount", revokedCount);
        data.put("qualifications", rows);
        data.put("actionHint", "/quality/supplier-qualifications?supplierId=" + supplierId);

        String message;
        if (rows.isEmpty()) {
            message = String.format("⚠️ 供应商 %s%s 无任何资质记录",
                    supplierName != null ? supplierName : supplierId,
                    qualType != null ? " 的 " + qualType : "");
        } else {
            message = String.format(
                    "供应商 %s 资质 %d 项: 有效 %d / 即将过期 %d / 已过期 %d / 已吊销 %d",
                    supplierName != null ? supplierName : supplierId,
                    rows.size(), validCount, expiringCount, expiredCount, revokedCount);
        }
        return buildSimpleResult(message, data);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
