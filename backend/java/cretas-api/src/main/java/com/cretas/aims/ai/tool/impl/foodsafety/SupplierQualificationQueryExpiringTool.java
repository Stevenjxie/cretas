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
 * 查询即将过期的供应商资质 Tool (READ + FILTER) — Sprint 9 P2.C Phase B.
 *
 * <p>Workdesk 预警 widget 主用. 返回工厂内 daysAhead 天内将过期或已过期的资质,
 * 按 expiry_date 升序 (最紧急在前).
 *
 * <p>daysAhead 缺省 30. 包含:
 * <ul>
 *   <li>EXPIRED — 已过期 (最高优先级)</li>
 *   <li>EXPIRING / VALID 且 expiry <= today + daysAhead</li>
 * </ul>
 *
 * <p>LLM 触发场景:
 * <ul>
 *   <li>"哪些供应商资质快过期了"</li>
 *   <li>"供应商证书到期预警"</li>
 *   <li>"7 天内过期的资质"</li>
 *   <li>"过期的 SC 证有哪些"</li>
 * </ul>
 *
 * <p>read-only.
 *
 * <p>Intent Code: {@code SUPPLIER_QUALIFICATION_QUERY_EXPIRING}
 */
@Slf4j
@Component
public class SupplierQualificationQueryExpiringTool extends AbstractBusinessTool {

    @Autowired
    private SupplierQualificationRepository qualificationRepository;

    @Autowired
    private SupplierRepository supplierRepository;

    @Override
    public String getToolName() {
        return "supplier_qualification_query_expiring";
    }

    @Override
    public String getDescription() {
        return "查询即将过期 (或已过期) 的供应商食品资质 (READ + FILTER) — Workdesk 预警 widget 用. "
                + "LLM 触发: '哪些供应商资质快过期了' / '供应商证书到期预警' / "
                + "'7 天内过期的资质' / '过期的 SC 证'. read-only. 默认 30 天.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> daysAhead = new HashMap<>();
        daysAhead.put("type", "integer");
        daysAhead.put("description", "提前天数 (默认 30). 返 [today, today+daysAhead] 区间内将过期的 + 全部已过期");
        properties.put("daysAhead", daysAhead);

        schema.put("properties", properties);
        // 全部参数都可选
        schema.put("required", List.of());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        Integer daysAhead = getInteger(params, "daysAhead", 30);
        if (daysAhead < 0) daysAhead = 30;

        LocalDate today = LocalDate.now();
        LocalDate threshold = today.plusDays(daysAhead);
        log.info("supplier_qualification_query_expiring — factory={} daysAhead={} (threshold={})",
                factoryId, daysAhead, threshold);

        // 1. EXPIRED — 已过期 (全部, 不限 daysAhead, 是最高优先级)
        List<SupplierQualification> expired = qualificationRepository
                .findByFactoryIdAndExpiryDateBeforeAndStatusIn(
                        factoryId, today, List.of("VALID", "EXPIRING", "EXPIRED"));

        // 2. 即将过期 (VALID 且 today <= expiry <= today + daysAhead)
        List<SupplierQualification> expiring = qualificationRepository
                .findExpiringWithin(factoryId, today, threshold);

        List<Map<String, Object>> expiredList = new ArrayList<>();
        for (SupplierQualification q : expired) {
            expiredList.add(buildRow(q, today, "EXPIRED"));
        }

        List<Map<String, Object>> urgentList = new ArrayList<>();  // 7 days
        List<Map<String, Object>> warningList = new ArrayList<>(); // 30 days
        for (SupplierQualification q : expiring) {
            long days = ChronoUnit.DAYS.between(today, q.getExpiryDate());
            String severity = days <= 7 ? "URGENT" : "WARNING";
            Map<String, Object> row = buildRow(q, today, severity);
            if (days <= 7) {
                urgentList.add(row);
            } else {
                warningList.add(row);
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("today", today.toString());
        data.put("daysAhead", daysAhead);
        data.put("expiredCount", expiredList.size());
        data.put("urgentCount", urgentList.size());
        data.put("warningCount", warningList.size());
        data.put("totalCount", expiredList.size() + urgentList.size() + warningList.size());
        data.put("expired", expiredList);   // 已过期 - ⛔ BLOCKING
        data.put("urgent", urgentList);     // 7天内 - 🔴 紧急
        data.put("warning", warningList);   // 30天内 - 🟡 提前
        data.put("actionHint", "/quality/supplier-qualifications?status=EXPIRING");

        String message = String.format(
                "%s 工厂资质预警: 已过期 %d 项 ⛔, 7 天内 %d 项 🔴, 30 天内 %d 项 🟡",
                factoryId, expiredList.size(), urgentList.size(), warningList.size());
        return buildSimpleResult(message, data);
    }

    private Map<String, Object> buildRow(SupplierQualification q, LocalDate today, String severity) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", q.getId());
        row.put("supplierId", q.getSupplierId());

        Optional<Supplier> supOpt = supplierRepository.findByIdAndFactoryId(
                q.getSupplierId(), q.getFactoryId());
        row.put("supplierName", supOpt.map(Supplier::getName).orElse(null));

        row.put("qualificationType", q.getQualificationType());
        row.put("certificateNumber", q.getCertificateNumber());
        row.put("issuingAuthority", q.getIssuingAuthority());
        row.put("expiryDate", q.getExpiryDate() != null ? q.getExpiryDate().toString() : null);
        long daysUntilExpiry = q.getExpiryDate() != null
                ? ChronoUnit.DAYS.between(today, q.getExpiryDate()) : 0;
        row.put("daysUntilExpiry", daysUntilExpiry);
        row.put("status", q.getStatus());
        row.put("severity", severity);
        row.put("attachmentOssUrl", q.getAttachmentOssUrl());
        return row;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
