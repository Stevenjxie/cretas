package com.cretas.aims.service.impl;

import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.finance.VoucherEntry;
import com.cretas.aims.entity.finance.VoucherTemplate;
import com.cretas.aims.entity.finance.VoucherTemplate.Direction;
import com.cretas.aims.entity.finance.VoucherTemplate.TemplateEntry;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.finance.VoucherTemplateRepository;
import com.cretas.aims.service.VoucherTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.SpelEvaluationException;
import org.springframework.expression.spel.SpelParseException;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link VoucherTemplateService} 实现.
 *
 * <p>SpEL eval 用 {@link SimpleEvaluationContext#forReadOnlyDataBinding} —
 * 跟 C-WF-VAR-1 SandboxedSpelEvaluator 同 security model, 阻断 T(...) / 反射 /
 * 写入. (两个 PR 都用 read-only data binding, 后续 follow-up PR 可 DRY.)
 *
 * @since 2026-05-17
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class VoucherTemplateServiceImpl implements VoucherTemplateService {

    private final VoucherTemplateRepository repository;

    private final SpelExpressionParser parser = new SpelExpressionParser();

    // ==================== Generator-facing API ====================

    @Override
    public Optional<VoucherTemplate> findActiveTemplate(String factoryId, VoucherType voucherType) {
        if (!StringUtils.hasText(factoryId) || voucherType == null) {
            return Optional.empty();
        }
        // Lookup 顺序: active default → 任意 active → empty
        Optional<VoucherTemplate> defaultOne = repository.findActiveDefaultByFactoryAndType(
                factoryId, voucherType);
        if (defaultOne.isPresent()) return defaultOne;
        return repository.findFirstActiveByFactoryAndType(factoryId, voucherType);
    }

    @Override
    public List<VoucherEntry> renderEntries(VoucherTemplate template, Object businessEntity) {
        if (template == null) {
            throw new BusinessException(500, "renderEntries: template 不能为 null");
        }
        List<TemplateEntry> entries = template.getEntries();
        if (entries == null || entries.isEmpty()) {
            throw new BusinessException(500,
                    "凭证模板 " + template.getName() + " entries 为空, 无法渲染")
                    .withHint("请编辑模板补充借贷分录");
        }

        // SimpleEvaluationContext.forReadOnlyDataBinding: 安全沙箱.
        SimpleEvaluationContext ctx = SimpleEvaluationContext
                .forReadOnlyDataBinding()
                .withInstanceMethods()
                .build();
        ctx.setVariable("entity", businessEntity);

        List<VoucherEntry> result = new ArrayList<>(entries.size());
        int lineNo = 1;
        for (TemplateEntry te : entries) {
            BigDecimal amount = evalAmount(te.getAmountExpression(), ctx, template, te);
            // SP11 价税分离: omitWhenZero 行 (税行) 求值为 0 时省略 → 零税退化两行,
            // 与 generator hardcoded buildEntries 的零税两行行为一致 (向后兼容)。
            if (Boolean.TRUE.equals(te.getOmitWhenZero()) && amount.signum() == 0) {
                continue;
            }
            VoucherEntry entry = VoucherEntry.builder()
                    .lineNo(te.getSortOrder() != null ? te.getSortOrder() : lineNo)
                    .subjectCode(te.getSubjectCode())
                    .subjectName(te.getSubjectName())
                    .debit(te.getDirection() == Direction.DEBIT ? amount : BigDecimal.ZERO)
                    .credit(te.getDirection() == Direction.CREDIT ? amount : BigDecimal.ZERO)
                    .description(te.getDescription())
                    .build();
            result.add(entry);
            lineNo++;
        }
        log.debug("Template '{}' rendered {} entries for {}", template.getName(), result.size(),
                businessEntity == null ? "null" : businessEntity.getClass().getSimpleName());
        return result;
    }

    private BigDecimal evalAmount(String spel, SimpleEvaluationContext ctx,
                                  VoucherTemplate template, TemplateEntry te) {
        if (!StringUtils.hasText(spel)) {
            throw new BusinessException(500,
                    "模板 " + template.getName() + " 分录 (科目 " + te.getSubjectCode()
                    + ") amountExpression 为空")
                    .withHint("请编辑模板, 设置 SpEL 表达式 如 #entity.totalAmount");
        }
        try {
            Expression expr = parser.parseExpression(spel);
            Object value = expr.getValue(ctx);
            if (value == null) return BigDecimal.ZERO;
            if (value instanceof BigDecimal bd) return bd;
            if (value instanceof Number n) return new BigDecimal(n.toString());
            if (value instanceof String s && !s.isBlank()) {
                try { return new BigDecimal(s); } catch (NumberFormatException ignored) { /* fall */ }
            }
            throw new BusinessException(500,
                    "模板 " + template.getName() + " 分录金额求值结果非数值: " + value)
                    .withHint("SpEL 表达式必须返回 BigDecimal/Number/数值字符串");
        } catch (SpelParseException | SpelEvaluationException e) {
            log.error("Template SpEL eval 失败 - template={}, spel={}, error={}",
                    template.getName(), spel, e.getMessage());
            throw new BusinessException(500,
                    "凭证模板 " + template.getName() + " 表达式求值失败: " + e.getMessage())
                    .withHint("请用 /api/mobile/{factoryId}/voucher-templates/{id}/preview 测试模板");
        }
    }

    // ==================== Admin CRUD ====================

    @Override
    public List<VoucherTemplate> listByFactory(String factoryId) {
        requireFactoryId(factoryId);
        return repository.findByFactoryIdOrderByVoucherTypeAscCreatedAtDesc(factoryId);
    }

    @Override
    public List<VoucherTemplate> listByFactoryAndType(String factoryId, VoucherType voucherType) {
        requireFactoryId(factoryId);
        return repository.findByFactoryIdOrderByVoucherTypeAscCreatedAtDesc(factoryId).stream()
                .filter(t -> t.getVoucherType() == voucherType)
                .toList();
    }

    @Override
    public Optional<VoucherTemplate> getById(String factoryId, String id) {
        return repository.findById(id)
                .filter(t -> factoryId.equals(t.getFactoryId()));
    }

    @Override
    @Transactional
    public VoucherTemplate create(String factoryId, VoucherTemplate template) {
        requireFactoryId(factoryId);
        validateTemplate(template);
        template.setFactoryId(factoryId);
        if (template.getIsActive() == null) template.setIsActive(true);
        if (template.getIsDefault() == null) template.setIsDefault(false);

        // R4 (幂等): 同 factory+voucherType+name 不重复创建
        boolean dup = repository.findByFactoryIdOrderByVoucherTypeAscCreatedAtDesc(factoryId)
                .stream()
                .anyMatch(t -> t.getVoucherType() == template.getVoucherType()
                        && template.getName().equals(t.getName()));
        if (dup) {
            throw new BusinessException(409,
                    "同 voucherType 已存在同名模板: " + template.getName())
                    .withHint("请改用其他名称, 或编辑现有模板")
                    .withHintTarget("name");
        }

        // 若设 isDefault=true, 取消同 factory+type 其他 default (DB unique partial 索引保证)
        if (Boolean.TRUE.equals(template.getIsDefault())) {
            clearOtherDefaults(factoryId, template.getVoucherType(), null);
        }
        VoucherTemplate saved = repository.save(template);
        log.info("VoucherTemplate created - id={}, factoryId={}, type={}, default={}",
                saved.getId(), factoryId, saved.getVoucherType(), saved.getIsDefault());
        return saved;
    }

    @Override
    @Transactional
    public VoucherTemplate update(String factoryId, String id, VoucherTemplate partial) {
        requireFactoryId(factoryId);
        VoucherTemplate existing = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("凭证模板", "id", id));
        if (!factoryId.equals(existing.getFactoryId())) {
            throw new BusinessException(403, "无权修改其他工厂的凭证模板");
        }

        if (partial.getName() != null) existing.setName(partial.getName());
        if (partial.getDescription() != null) existing.setDescription(partial.getDescription());
        if (partial.getEntries() != null && !partial.getEntries().isEmpty()) {
            existing.setEntries(partial.getEntries());
        }
        if (partial.getIsActive() != null) existing.setIsActive(partial.getIsActive());
        // isDefault 走专门 setAsDefault, update 不允许直接改, 避免唯一约束冲突
        VoucherTemplate saved = repository.save(existing);
        log.info("VoucherTemplate updated - id={}", saved.getId());
        return saved;
    }

    @Override
    @Transactional
    public void delete(String factoryId, String id) {
        requireFactoryId(factoryId);
        VoucherTemplate existing = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("凭证模板", "id", id));
        if (!factoryId.equals(existing.getFactoryId())) {
            throw new BusinessException(403, "无权删除其他工厂的凭证模板");
        }
        repository.delete(existing);
        log.info("VoucherTemplate deleted - id={}", id);
    }

    @Override
    @Transactional
    public VoucherTemplate setAsDefault(String factoryId, String id) {
        requireFactoryId(factoryId);
        VoucherTemplate target = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("凭证模板", "id", id));
        if (!factoryId.equals(target.getFactoryId())) {
            throw new BusinessException(403, "无权操作其他工厂的凭证模板");
        }
        clearOtherDefaults(factoryId, target.getVoucherType(), id);
        target.setIsDefault(true);
        return repository.save(target);
    }

    // ==================== helpers ====================

    private void validateTemplate(VoucherTemplate t) {
        if (t.getVoucherType() == null) {
            throw new BusinessException(400, "voucherType 不能为空").withHintTarget("voucherType");
        }
        if (!StringUtils.hasText(t.getName())) {
            throw new BusinessException(400, "name 不能为空").withHintTarget("name");
        }
        if (t.getEntries() == null || t.getEntries().isEmpty()) {
            throw new BusinessException(400, "entries 不能为空, 至少 1 借 + 1 贷")
                    .withHintTarget("entries");
        }
        // 借贷必平校验 (静态: 每条 entry 必须有 SpEL 表达式, 余额平衡运行时校验)
        boolean hasDebit = t.getEntries().stream().anyMatch(e -> e.getDirection() == Direction.DEBIT);
        boolean hasCredit = t.getEntries().stream().anyMatch(e -> e.getDirection() == Direction.CREDIT);
        if (!hasDebit || !hasCredit) {
            throw new BusinessException(400, "entries 必须同时含 DEBIT 和 CREDIT 分录")
                    .withHintTarget("entries");
        }
        for (TemplateEntry te : t.getEntries()) {
            if (!StringUtils.hasText(te.getSubjectCode())) {
                throw new BusinessException(400, "每条 entry 必须有 subjectCode")
                        .withHintTarget("entries");
            }
            if (te.getDirection() == null) {
                throw new BusinessException(400, "每条 entry 必须有 direction (DEBIT/CREDIT)")
                        .withHintTarget("entries");
            }
            if (!StringUtils.hasText(te.getAmountExpression())) {
                throw new BusinessException(400,
                        "每条 entry 必须有 amountExpression, 如 #entity.totalAmount")
                        .withHintTarget("entries");
            }
        }
    }

    private void clearOtherDefaults(String factoryId, VoucherType voucherType, String excludeId) {
        List<VoucherTemplate> existing = repository.findByFactoryIdOrderByVoucherTypeAscCreatedAtDesc(factoryId)
                .stream()
                .filter(t -> t.getVoucherType() == voucherType
                        && Boolean.TRUE.equals(t.getIsDefault())
                        && (excludeId == null || !excludeId.equals(t.getId())))
                .toList();
        for (VoucherTemplate other : existing) {
            other.setIsDefault(false);
            repository.save(other);
            log.info("Cleared default flag on - id={}, type={}", other.getId(), voucherType);
        }
    }

    private void requireFactoryId(String factoryId) {
        if (!StringUtils.hasText(factoryId)) {
            throw new BusinessException(400, "factoryId 不能为空");
        }
    }
}
