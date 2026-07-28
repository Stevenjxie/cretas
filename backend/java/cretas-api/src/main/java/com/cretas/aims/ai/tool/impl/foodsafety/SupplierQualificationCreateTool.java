package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.foodsafety.SupplierQualification;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.foodsafety.SupplierQualificationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 创建供应商食品资质 Tool (WRITE + Preview) — Sprint 9 P2.C Phase B (食品行业法定).
 *
 * <p>登记新的供应商资质 (SC 证 / 进口商资质 / HACCP / 营业执照 / ISO 22000 等),
 * 自动按 expiry_date 距 today 计算初始 status:
 * <ul>
 *   <li>expiry_date > today + 30d → VALID</li>
 *   <li>today <= expiry_date <= today + 30d → EXPIRING</li>
 *   <li>expiry_date < today → EXPIRED (不应发生, 但防御性处理)</li>
 * </ul>
 *
 * <p>防呆 4 位一体:
 * <ul>
 *   <li>R1: preview 显当前同 supplier+type 已有几张证 + 是否重复</li>
 *   <li>R2: message 含 supplier 名称 + qualificationType 描述 + expiryDate (上下文)</li>
 *   <li>R3: qualificationType 严格 enum 校验</li>
 *   <li>R4: dedup — 同 supplier+type+cert# 已存在 → 返 status=DUPLICATE</li>
 * </ul>
 *
 * <p>LLM 触发场景:
 * <ul>
 *   <li>"登记供应商 X 的 SC 证"</li>
 *   <li>"录入 X 的 HACCP 证"</li>
 *   <li>"添加资质"</li>
 * </ul>
 *
 * <p>Intent Code: {@code SUPPLIER_QUALIFICATION_CREATE}
 */
@Slf4j
@Component
public class SupplierQualificationCreateTool extends AbstractBusinessTool {

    private static final Set<String> ALLOWED_TYPES = Set.of(
            "SC_LICENSE", "IMPORT_CERT", "HACCP_CERT", "BUSINESS_LICENSE",
            "ISO22000", "FSSC22000", "THIRD_PARTY_TEST", "OTHER"
    );

    @Autowired
    private SupplierQualificationRepository qualificationRepository;

    @Autowired
    private SupplierRepository supplierRepository;

    @Override
    public String getToolName() {
        return "supplier_qualification_create";
    }

    @Override
    public String getDescription() {
        return "登记供应商食品资质 (WRITE + Preview) — SC 证 / 进口商资质 / HACCP / 营业执照 / ISO22000 等. "
                + "LLM 触发: '登记供应商 X 的 SC 证' / '录入 X 的 HACCP 证' / "
                + "'添加供应商资质'. 自动按 expiry_date 算初始 status.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> supplierId = new HashMap<>();
        supplierId.put("type", "string");
        supplierId.put("description", "供应商 ID (必填). UUID 格式, 关联 suppliers.id");
        properties.put("supplierId", supplierId);

        Map<String, Object> qualificationType = new HashMap<>();
        qualificationType.put("type", "string");
        qualificationType.put("description",
                "资质类型 (必填): SC_LICENSE / IMPORT_CERT / HACCP_CERT / BUSINESS_LICENSE / "
                        + "ISO22000 / FSSC22000 / THIRD_PARTY_TEST / OTHER");
        qualificationType.put("enum", List.of(
                "SC_LICENSE", "IMPORT_CERT", "HACCP_CERT", "BUSINESS_LICENSE",
                "ISO22000", "FSSC22000", "THIRD_PARTY_TEST", "OTHER"));
        properties.put("qualificationType", qualificationType);

        Map<String, Object> certificateNumber = new HashMap<>();
        certificateNumber.put("type", "string");
        certificateNumber.put("description", "证书编号 (必填). e.g. SC10532010300001");
        properties.put("certificateNumber", certificateNumber);

        Map<String, Object> issuingAuthority = new HashMap<>();
        issuingAuthority.put("type", "string");
        issuingAuthority.put("description", "颁发机构 (必填). e.g. 国家市场监督管理总局");
        properties.put("issuingAuthority", issuingAuthority);

        Map<String, Object> issueDate = new HashMap<>();
        issueDate.put("type", "string");
        issueDate.put("description", "颁发日期 (必填). ISO 格式 YYYY-MM-DD");
        properties.put("issueDate", issueDate);

        Map<String, Object> expiryDate = new HashMap<>();
        expiryDate.put("type", "string");
        expiryDate.put("description", "过期日期 (必填). ISO 格式 YYYY-MM-DD");
        properties.put("expiryDate", expiryDate);

        Map<String, Object> attachmentOssUrl = new HashMap<>();
        attachmentOssUrl.put("type", "string");
        attachmentOssUrl.put("description", "证书扫描件 OSS URL (可选)");
        properties.put("attachmentOssUrl", attachmentOssUrl);

        schema.put("properties", properties);
        schema.put("required", List.of(
                "supplierId", "qualificationType", "certificateNumber",
                "issuingAuthority", "issueDate", "expiryDate"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("supplierId", "qualificationType", "certificateNumber",
                "issuingAuthority", "issueDate", "expiryDate");
    }

    @Override
    public boolean supportsPreview() {
        return true;
    }

    @Override
    public ToolExecutor.ActionType getActionType() {
        return ToolExecutor.ActionType.WRITE;
    }

    @Override
    public ToolExecutor.RiskLevel getRiskLevel() {
        return ToolExecutor.RiskLevel.MEDIUM;
    }

    @Override
    protected Map<String, Object> doPreview(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        String supplierId = getString(params, "supplierId");
        String qualType = getString(params, "qualificationType");
        String certNumber = getString(params, "certificateNumber");
        String expiryStr = getString(params, "expiryDate");
        String issueStr = getString(params, "issueDate");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "PREVIEW");
        result.put("supplierId", supplierId);
        result.put("qualificationType", qualType);
        result.put("certificateNumber", certNumber);

        // 1. enum 校验
        if (!ALLOWED_TYPES.contains(qualType)) {
            result.put("canDo", false);
            result.put("message", String.format(
                    "⚠️ 资质类型 %s 不在合法 enum 列表. 合法值: %s",
                    qualType, String.join(", ", ALLOWED_TYPES)));
            return result;
        }

        // 2. 供应商存在性 + 名称
        Optional<Supplier> supOpt = supplierRepository.findByIdAndFactoryId(supplierId, factoryId);
        if (supOpt.isEmpty()) {
            result.put("canDo", false);
            result.put("message", String.format("⚠️ 供应商 %s 不存在", supplierId));
            return result;
        }
        Supplier sup = supOpt.get();
        result.put("supplierName", sup.getName());

        // 3. dedup
        long existing = qualificationRepository
                .countByFactoryIdAndSupplierIdAndQualificationTypeAndCertificateNumber(
                        factoryId, supplierId, qualType, certNumber);
        if (existing > 0) {
            result.put("canDo", false);
            result.put("status", "DUPLICATE");
            result.put("message", String.format(
                    "ℹ️ 供应商 %s 的 %s (证号 %s) 已存在, 无需重复登记",
                    sup.getName(), qualType, certNumber));
            return result;
        }

        // 4. 日期合理性 + 初始 status 推导
        LocalDate issueDate = parseDate(issueStr);
        LocalDate expiryDate = parseDate(expiryStr);
        if (issueDate == null || expiryDate == null) {
            result.put("canDo", false);
            result.put("message", "⚠️ 日期格式错误, 请用 ISO YYYY-MM-DD");
            return result;
        }
        if (expiryDate.isBefore(issueDate)) {
            result.put("canDo", false);
            result.put("message", String.format(
                    "⚠️ 过期日 %s 早于颁发日 %s, 数据不合理", expiryDate, issueDate));
            return result;
        }
        String initialStatus = computeInitialStatus(expiryDate);
        long daysUntilExpiry = ChronoUnit.DAYS.between(LocalDate.now(), expiryDate);
        result.put("initialStatus", initialStatus);
        result.put("expiryDate", expiryDate.toString());
        result.put("daysUntilExpiry", daysUntilExpiry);

        result.put("canDo", true);
        result.put("message", String.format(
                "✅ 登记 → 供应商 %s 的 %s (证号 %s), 有效期至 %s (初始 status=%s, 距过期 %d 天)",
                sup.getName(), qualType, certNumber, expiryDate, initialStatus,
                daysUntilExpiry));
        return result;
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        String supplierId = getString(params, "supplierId");
        String qualType = getString(params, "qualificationType");
        String certNumber = getString(params, "certificateNumber");
        String issuingAuth = getString(params, "issuingAuthority");
        String issueStr = getString(params, "issueDate");
        String expiryStr = getString(params, "expiryDate");
        String attachmentUrl = getString(params, "attachmentOssUrl");
        Long userId = getUserId(context);

        log.info("supplier_qualification_create — factory={} supplier={} type={} cert={} userId={}",
                factoryId, supplierId, qualType, certNumber, userId);

        // enum 校验
        if (!ALLOWED_TYPES.contains(qualType)) {
            throw new BusinessException(400,
                    String.format("资质类型 %s 不在合法 enum 列表", qualType));
        }

        // 供应商存在性
        Optional<Supplier> supOpt = supplierRepository.findByIdAndFactoryId(supplierId, factoryId);
        if (supOpt.isEmpty()) {
            throw new BusinessException(404,
                    String.format("供应商 %s 不存在", supplierId));
        }
        Supplier sup = supOpt.get();

        // dedup
        long existing = qualificationRepository
                .countByFactoryIdAndSupplierIdAndQualificationTypeAndCertificateNumber(
                        factoryId, supplierId, qualType, certNumber);
        if (existing > 0) {
            Map<String, Object> dup = new LinkedHashMap<>();
            dup.put("status", "DUPLICATE");
            dup.put("supplierId", supplierId);
            dup.put("supplierName", sup.getName());
            dup.put("qualificationType", qualType);
            dup.put("certificateNumber", certNumber);
            dup.put("message", String.format(
                    "ℹ️ 供应商 %s 的 %s (证号 %s) 已存在, 跳过", sup.getName(), qualType, certNumber));
            return dup;
        }

        // 日期解析 + 验证
        LocalDate issueDate = parseDate(issueStr);
        LocalDate expiryDate = parseDate(expiryStr);
        if (issueDate == null || expiryDate == null) {
            throw new BusinessException(400, "日期格式错误, 请用 ISO YYYY-MM-DD");
        }
        if (expiryDate.isBefore(issueDate)) {
            throw new BusinessException(400,
                    String.format("过期日 %s 早于颁发日 %s", expiryDate, issueDate));
        }

        String initialStatus = computeInitialStatus(expiryDate);

        SupplierQualification q = SupplierQualification.builder()
                .factoryId(factoryId)
                .supplierId(supplierId)
                .qualificationType(qualType)
                .certificateNumber(certNumber)
                .issuingAuthority(issuingAuth)
                .issueDate(issueDate)
                .expiryDate(expiryDate)
                .attachmentOssUrl(attachmentUrl)
                .status(initialStatus)
                .build();
        SupplierQualification saved = qualificationRepository.save(q);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", saved.getId());
        data.put("supplierId", supplierId);
        data.put("supplierName", sup.getName());
        data.put("qualificationType", qualType);
        data.put("certificateNumber", certNumber);
        data.put("issueDate", issueDate.toString());
        data.put("expiryDate", expiryDate.toString());
        data.put("status", initialStatus);
        data.put("daysUntilExpiry", ChronoUnit.DAYS.between(LocalDate.now(), expiryDate));
        data.put("actionHint", "/quality/supplier-qualifications?supplierId=" + supplierId);

        String message = String.format(
                "✅ 已登记 → 供应商 %s 的 %s (证号 %s), 有效期至 %s (status=%s)",
                sup.getName(), qualType, certNumber, expiryDate, initialStatus);
        return buildSimpleResult(message, data);
    }

    /** 按 expiry_date 距今天的天数算初始 status. */
    static String computeInitialStatus(LocalDate expiryDate) {
        LocalDate today = LocalDate.now();
        if (expiryDate.isBefore(today)) {
            return "EXPIRED";
        }
        long daysUntilExpiry = ChronoUnit.DAYS.between(today, expiryDate);
        if (daysUntilExpiry <= 30) {
            return "EXPIRING";
        }
        return "VALID";
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
